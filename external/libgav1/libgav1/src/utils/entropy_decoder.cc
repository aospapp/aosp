// Copyright 2019 The libgav1 Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "src/utils/entropy_decoder.h"

#include <cassert>

#include "src/utils/common.h"
#include "src/utils/constants.h"

namespace {

constexpr uint32_t kReadBitMask = ~255;
// This constant is used to set the value of |bits_| so that bits can be read
// after end of stream without trying to refill the buffer for a reasonably long
// time.
constexpr int kLargeBitCount = 0x4000;
constexpr int kCdfPrecision = 6;
constexpr int kMinimumProbabilityPerSymbol = 4;

// This function computes the "cur" variable as specified inside the do-while
// loop in Section 8.2.6 of the spec. This function is monotonically
// decreasing as the values of index increases (note that the |cdf| array is
// sorted in decreasing order).
uint32_t ScaleCdf(uint16_t values_in_range_shifted, const uint16_t* const cdf,
                  int index, int symbol_count) {
  return ((values_in_range_shifted * (cdf[index] >> kCdfPrecision)) >> 1) +
         (kMinimumProbabilityPerSymbol * (symbol_count - index));
}

void UpdateCdf(uint16_t* const cdf, int symbol_count, int symbol) {
  const uint16_t count = cdf[symbol_count];
  // rate is computed in the spec as:
  //  3 + ( cdf[N] > 15 ) + ( cdf[N] > 31 ) + Min(FloorLog2(N), 2)
  // In this case cdf[N] is |count|.
  // Min(FloorLog2(N), 2) is 1 for symbol_count == {2, 3} and 2 for all
  // symbol_count > 3. So the equation becomes:
  //  4 + (count > 15) + (count > 31) + (symbol_count > 3).
  // Note that the largest value for count is 32 (it is not incremented beyond
  // 32). So using that information:
  //  count >> 4 is 0 for count from 0 to 15.
  //  count >> 4 is 1 for count from 16 to 31.
  //  count >> 4 is 2 for count == 31.
  // Now, the equation becomes:
  //  4 + (count >> 4) + (symbol_count > 3).
  // Since (count >> 4) can only be 0 or 1 or 2, the addition can be replaced
  // with bitwise or. So the final equation is:
  // (4 | (count >> 4)) + (symbol_count > 3).
  const int rate = (4 | (count >> 4)) + static_cast<int>(symbol_count > 3);
  // Hints for further optimizations:
  //
  // 1. clang can vectorize this for loop with width 4, even though the loop
  // contains an if-else statement. Therefore, it may be advantageous to use
  // "i < symbol_count" as the loop condition when symbol_count is 8, 12, or 16
  // (a multiple of 4 that's not too small).
  //
  // 2. The for loop can be rewritten in the following form, which would enable
  // clang to vectorize the loop with width 8:
  //
  //   const int mask = (1 << rate) - 1;
  //   for (int i = 0; i < symbol_count - 1; ++i) {
  //     const uint16_t a = (i < symbol) ? kCdfMaxProbability : mask;
  //     cdf[i] += static_cast<int16_t>(a - cdf[i]) >> rate;
  //   }
  //
  // The subtraction (a - cdf[i]) relies on the overflow semantics of unsigned
  // integer arithmetic. The result of the unsigned subtraction is cast to a
  // signed integer and right-shifted. This requires the right shift of a
  // signed integer be an arithmetic shift, which is true for clang, gcc, and
  // Visual C++.
  for (int i = 0; i < symbol_count - 1; ++i) {
    if (i < symbol) {
      cdf[i] += (libgav1::kCdfMaxProbability - cdf[i]) >> rate;
    } else {
      cdf[i] -= cdf[i] >> rate;
    }
  }
  cdf[symbol_count] += static_cast<uint16_t>(count < 32);
}

}  // namespace

namespace libgav1 {

constexpr uint32_t DaalaBitReader::kWindowSize;  // static.

DaalaBitReader::DaalaBitReader(const uint8_t* data, size_t size,
                               bool allow_update_cdf)
    : data_(data),
      size_(size),
      data_index_(0),
      allow_update_cdf_(allow_update_cdf) {
  window_diff_ = (WindowSize{1} << (kWindowSize - 1)) - 1;
  values_in_range_ = kCdfMaxProbability;
  bits_ = -15;
  PopulateBits();
}

// This is similar to the ReadSymbol() implementation but it is optimized based
// on the following facts:
//   * The probability is fixed at half. So some multiplications can be replaced
//     with bit operations.
//   * Symbol count is fixed at 2.
int DaalaBitReader::ReadBit() {
  const uint32_t curr =
      ((values_in_range_ & kReadBitMask) >> 1) + kMinimumProbabilityPerSymbol;
  const WindowSize zero_threshold = static_cast<WindowSize>(curr)
                                    << (kWindowSize - 16);
  int bit = 1;
  if (window_diff_ >= zero_threshold) {
    values_in_range_ -= curr;
    window_diff_ -= zero_threshold;
    bit = 0;
  } else {
    values_in_range_ = curr;
  }
  NormalizeRange();
  return bit;
}

int64_t DaalaBitReader::ReadLiteral(int num_bits) {
  assert(num_bits <= 32);
  uint32_t literal = 0;
  for (int bit = num_bits - 1; bit >= 0; --bit) {
    literal |= static_cast<uint32_t>(ReadBit()) << bit;
  }
  return literal;
}

int DaalaBitReader::ReadSymbol(uint16_t* const cdf, int symbol_count) {
  const int symbol = ReadSymbolImpl(cdf, symbol_count);
  if (allow_update_cdf_) {
    UpdateCdf(cdf, symbol_count, symbol);
  }
  return symbol;
}

bool DaalaBitReader::ReadSymbol(uint16_t* cdf) {
  const bool symbol = ReadSymbolImpl(cdf) != 0;
  if (allow_update_cdf_) {
    const uint16_t count = cdf[2];
    // rate is computed in the spec as:
    //  3 + ( cdf[N] > 15 ) + ( cdf[N] > 31 ) + Min(FloorLog2(N), 2)
    // In this case N is 2 and cdf[N] is |count|. So the equation becomes:
    //  4 + (count > 15) + (count > 31)
    // Note that the largest value for count is 32 (it is not incremented beyond
    // 32). So using that information:
    //  count >> 4 is 0 for count from 0 to 15.
    //  count >> 4 is 1 for count from 16 to 31.
    //  count >> 4 is 2 for count == 31.
    // Now, the equation becomes:
    //  4 + (count >> 4).
    // Since (count >> 4) can only be 0 or 1 or 2, the addition can be replaced
    // with bitwise or. So the final equation is:
    //  4 | (count >> 4).
    const int rate = 4 | (count >> 4);
    if (symbol) {
      cdf[0] += (kCdfMaxProbability - cdf[0]) >> rate;
    } else {
      cdf[0] -= cdf[0] >> rate;
    }
    cdf[2] += static_cast<uint16_t>(count < 32);
  }
  return symbol;
}

bool DaalaBitReader::ReadSymbolWithoutCdfUpdate(uint16_t* cdf) {
  return ReadSymbolImpl(cdf) != 0;
}

template <int symbol_count>
int DaalaBitReader::ReadSymbol(uint16_t* const cdf) {
  static_assert(symbol_count >= 3 && symbol_count <= 16, "");
  const int symbol = (symbol_count <= 13)
                         ? ReadSymbolImpl(cdf, symbol_count)
                         : ReadSymbolImplBinarySearch(cdf, symbol_count);
  if (allow_update_cdf_) {
    UpdateCdf(cdf, symbol_count, symbol);
  }
  return symbol;
}

int DaalaBitReader::ReadSymbolImpl(const uint16_t* const cdf,
                                   int symbol_count) {
  assert(cdf[symbol_count - 1] == 0);
  --symbol_count;
  uint32_t curr = values_in_range_;
  int symbol = -1;
  uint32_t prev;
  const auto symbol_value =
      static_cast<uint32_t>(window_diff_ >> (kWindowSize - 16));
  uint32_t delta = kMinimumProbabilityPerSymbol * symbol_count;
  // Search through the |cdf| array to determine where the scaled cdf value and
  // |symbol_value| cross over.
  do {
    prev = curr;
    curr = (((values_in_range_ >> 8) * (cdf[++symbol] >> kCdfPrecision)) >> 1) +
           delta;
    delta -= kMinimumProbabilityPerSymbol;
  } while (symbol_value < curr);
  values_in_range_ = prev - curr;
  window_diff_ -= static_cast<WindowSize>(curr) << (kWindowSize - 16);
  NormalizeRange();
  return symbol;
}

int DaalaBitReader::ReadSymbolImplBinarySearch(const uint16_t* const cdf,
                                               int symbol_count) {
  assert(cdf[symbol_count - 1] == 0);
  assert(symbol_count > 1 && symbol_count <= 16);
  --symbol_count;
  const auto symbol_value =
      static_cast<uint32_t>(window_diff_ >> (kWindowSize - 16));
  // Search through the |cdf| array to determine where the scaled cdf value and
  // |symbol_value| cross over. Since the CDFs are sorted, we can use binary
  // search to do this. Let |symbol| be the index of the first |cdf| array
  // entry whose scaled cdf value is less than or equal to |symbol_value|. The
  // binary search maintains the invariant:
  //   low <= symbol <= high + 1
  // and terminates when low == high + 1.
  int low = 0;
  int high = symbol_count - 1;
  // The binary search maintains the invariants that |prev| is the scaled cdf
  // value for low - 1 and |curr| is the scaled cdf value for high + 1. (By
  // convention, the scaled cdf value for -1 is values_in_range_.) When the
  // binary search terminates, |prev| is the scaled cdf value for symbol - 1
  // and |curr| is the scaled cdf value for |symbol|.
  uint32_t prev = values_in_range_;
  uint32_t curr = 0;
  const uint16_t values_in_range_shifted = values_in_range_ >> 8;
  do {
    const int mid = DivideBy2(low + high);
    const uint32_t scaled_cdf =
        ScaleCdf(values_in_range_shifted, cdf, mid, symbol_count);
    if (symbol_value < scaled_cdf) {
      low = mid + 1;
      prev = scaled_cdf;
    } else {
      high = mid - 1;
      curr = scaled_cdf;
    }
  } while (low <= high);
  assert(low == high + 1);
  // At this point, |low| is the symbol that has been decoded.
  values_in_range_ = prev - curr;
  window_diff_ -= static_cast<WindowSize>(curr) << (kWindowSize - 16);
  NormalizeRange();
  return low;
}

int DaalaBitReader::ReadSymbolImpl(const uint16_t* const cdf) {
  assert(cdf[1] == 0);
  const auto symbol_value =
      static_cast<uint32_t>(window_diff_ >> (kWindowSize - 16));
  const uint32_t curr = ScaleCdf(values_in_range_ >> 8, cdf, 0, 1);
  const int symbol = static_cast<int>(symbol_value < curr);
  if (symbol == 1) {
    values_in_range_ = curr;
  } else {
    values_in_range_ -= curr;
    window_diff_ -= static_cast<WindowSize>(curr) << (kWindowSize - 16);
  }
  NormalizeRange();
  return symbol;
}

void DaalaBitReader::PopulateBits() {
  int shift = kWindowSize - 9 - (bits_ + 15);
  for (; shift >= 0 && data_index_ < size_; shift -= 8) {
    window_diff_ ^= static_cast<WindowSize>(data_[data_index_++]) << shift;
    bits_ += 8;
  }
  if (data_index_ >= size_) {
    bits_ = kLargeBitCount;
  }
}

void DaalaBitReader::NormalizeRange() {
  const int bits_used = 15 - FloorLog2(values_in_range_);
  bits_ -= bits_used;
  window_diff_ = ((window_diff_ + 1) << bits_used) - 1;
  values_in_range_ <<= bits_used;
  if (bits_ < 0) PopulateBits();
}

// Explicit instantiations.
template int DaalaBitReader::ReadSymbol<3>(uint16_t* cdf);
template int DaalaBitReader::ReadSymbol<4>(uint16_t* cdf);
template int DaalaBitReader::ReadSymbol<5>(uint16_t* cdf);
template int DaalaBitReader::ReadSymbol<7>(uint16_t* cdf);
template int DaalaBitReader::ReadSymbol<8>(uint16_t* cdf);
template int DaalaBitReader::ReadSymbol<11>(uint16_t* cdf);
template int DaalaBitReader::ReadSymbol<13>(uint16_t* cdf);
template int DaalaBitReader::ReadSymbol<16>(uint16_t* cdf);

}  // namespace libgav1
