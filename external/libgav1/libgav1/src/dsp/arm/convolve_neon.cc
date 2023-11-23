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

#include "src/dsp/convolve.h"
#include "src/dsp/dsp.h"

#if LIBGAV1_ENABLE_NEON

#include <arm_neon.h>

#include <algorithm>
#include <cassert>
#include <cstddef>
#include <cstdint>

#include "src/dsp/arm/common_neon.h"
#include "src/utils/common.h"

namespace libgav1 {
namespace dsp {
namespace low_bitdepth {
namespace {

constexpr int kBitdepth8 = 8;
constexpr int kIntermediateStride = kMaxSuperBlockSizeInPixels;
constexpr int kSubPixelMask = (1 << kSubPixelBits) - 1;
constexpr int kHorizontalOffset = 3;
constexpr int kVerticalOffset = 3;
constexpr int kInterRoundBitsVertical = 11;

int GetFilterIndex(const int filter_index, const int length) {
  if (length <= 4) {
    if (filter_index == kInterpolationFilterEightTap ||
        filter_index == kInterpolationFilterEightTapSharp) {
      return 4;
    }
    if (filter_index == kInterpolationFilterEightTapSmooth) {
      return 5;
    }
  }
  return filter_index;
}

inline int16x8_t ZeroExtend(const uint8x8_t in) {
  return vreinterpretq_s16_u16(vmovl_u8(in));
}

inline void Load8x8(const uint8_t* s, const ptrdiff_t p, int16x8_t* dst) {
  dst[0] = ZeroExtend(vld1_u8(s));
  s += p;
  dst[1] = ZeroExtend(vld1_u8(s));
  s += p;
  dst[2] = ZeroExtend(vld1_u8(s));
  s += p;
  dst[3] = ZeroExtend(vld1_u8(s));
  s += p;
  dst[4] = ZeroExtend(vld1_u8(s));
  s += p;
  dst[5] = ZeroExtend(vld1_u8(s));
  s += p;
  dst[6] = ZeroExtend(vld1_u8(s));
  s += p;
  dst[7] = ZeroExtend(vld1_u8(s));
}

// Multiply every entry in |src[]| by the corresponding lane in |taps| and sum.
// The sum of the entries in |taps| is always 128. In some situations negative
// values are used. This creates a situation where the positive taps sum to more
// than 128. An example is:
// {-4, 10, -24, 100, 60, -20, 8, -2}
// The negative taps never sum to < -128
// The center taps are always positive. The remaining positive taps never sum
// to > 128.
// Summing these naively can overflow int16_t. This can be avoided by adding the
// center taps last and saturating the result.
// We do not need to expand to int32_t because later in the function the value
// is shifted by |kFilterBits| (7) and saturated to uint8_t. This means any
// value over 255 << 7 (32576 because of rounding) is clamped.
template <int num_taps>
int16x8_t SumTaps(const int16x8_t* const src, const int16x8_t taps) {
  int16x8_t sum;
  if (num_taps == 8) {
    const int16x4_t taps_lo = vget_low_s16(taps);
    const int16x4_t taps_hi = vget_high_s16(taps);
    sum = vmulq_lane_s16(src[0], taps_lo, 0);
    sum = vmlaq_lane_s16(sum, src[1], taps_lo, 1);
    sum = vmlaq_lane_s16(sum, src[2], taps_lo, 2);
    sum = vmlaq_lane_s16(sum, src[5], taps_hi, 1);
    sum = vmlaq_lane_s16(sum, src[6], taps_hi, 2);
    sum = vmlaq_lane_s16(sum, src[7], taps_hi, 3);

    // Center taps.
    sum = vqaddq_s16(sum, vmulq_lane_s16(src[3], taps_lo, 3));
    sum = vqaddq_s16(sum, vmulq_lane_s16(src[4], taps_hi, 0));
  } else if (num_taps == 6) {
    const int16x4_t taps_lo = vget_low_s16(taps);
    const int16x4_t taps_hi = vget_high_s16(taps);
    sum = vmulq_lane_s16(src[0], taps_lo, 1);
    sum = vmlaq_lane_s16(sum, src[1], taps_lo, 2);
    sum = vmlaq_lane_s16(sum, src[4], taps_hi, 1);
    sum = vmlaq_lane_s16(sum, src[5], taps_hi, 2);

    // Center taps.
    sum = vqaddq_s16(sum, vmulq_lane_s16(src[2], taps_lo, 3));
    sum = vqaddq_s16(sum, vmulq_lane_s16(src[3], taps_hi, 0));
  } else if (num_taps == 4) {
    const int16x4_t taps_lo = vget_low_s16(taps);
    sum = vmulq_lane_s16(src[0], taps_lo, 0);
    sum = vmlaq_lane_s16(sum, src[3], taps_lo, 3);

    // Center taps.
    sum = vqaddq_s16(sum, vmulq_lane_s16(src[1], taps_lo, 1));
    sum = vqaddq_s16(sum, vmulq_lane_s16(src[2], taps_lo, 2));
  } else {
    assert(num_taps == 2);
    // All the taps are positive so there is no concern regarding saturation.
    const int16x4_t taps_lo = vget_low_s16(taps);
    sum = vmulq_lane_s16(src[0], taps_lo, 1);
    sum = vmlaq_lane_s16(sum, src[1], taps_lo, 2);
  }

  return sum;
}

// Add an offset to ensure the sum is positive and it fits within uint16_t.
template <int num_taps>
uint16x8_t SumTaps8To16(const int16x8_t* const src, const int16x8_t taps) {
  // The worst case sum of negative taps is -56. The worst case sum of positive
  // taps is 184. With the single pass versions of the Convolve we could safely
  // saturate to int16_t because it outranged the final shift and narrow to
  // uint8_t. For the 2D Convolve the intermediate values are 16 bits so we
  // don't have that option.
  // 184 * 255 = 46920 which is greater than int16_t can hold, but not uint16_t.
  // The minimum value we need to handle is -56 * 255 = -14280.
  // By offsetting the sum with 1 << 14 = 16384 we ensure that the sum is never
  // negative and that 46920 + 16384 = 63304 fits comfortably in uint16_t. This
  // allows us to use 16 bit registers instead of 32 bit registers.
  // When considering the bit operations it is safe to ignore signedness. Due to
  // the magic of 2's complement and well defined rollover rules the bit
  // representations are equivalent.
  const int16x4_t taps_lo = vget_low_s16(taps);
  const int16x4_t taps_hi = vget_high_s16(taps);
  // |offset| == 1 << (bitdepth + kFilterBits - 1);
  int16x8_t sum = vdupq_n_s16(1 << 14);
  if (num_taps == 8) {
    sum = vmlaq_lane_s16(sum, src[0], taps_lo, 0);
    sum = vmlaq_lane_s16(sum, src[1], taps_lo, 1);
    sum = vmlaq_lane_s16(sum, src[2], taps_lo, 2);
    sum = vmlaq_lane_s16(sum, src[3], taps_lo, 3);
    sum = vmlaq_lane_s16(sum, src[4], taps_hi, 0);
    sum = vmlaq_lane_s16(sum, src[5], taps_hi, 1);
    sum = vmlaq_lane_s16(sum, src[6], taps_hi, 2);
    sum = vmlaq_lane_s16(sum, src[7], taps_hi, 3);
  } else if (num_taps == 6) {
    sum = vmlaq_lane_s16(sum, src[0], taps_lo, 1);
    sum = vmlaq_lane_s16(sum, src[1], taps_lo, 2);
    sum = vmlaq_lane_s16(sum, src[2], taps_lo, 3);
    sum = vmlaq_lane_s16(sum, src[3], taps_hi, 0);
    sum = vmlaq_lane_s16(sum, src[4], taps_hi, 1);
    sum = vmlaq_lane_s16(sum, src[5], taps_hi, 2);
  } else if (num_taps == 4) {
    sum = vmlaq_lane_s16(sum, src[0], taps_lo, 2);
    sum = vmlaq_lane_s16(sum, src[1], taps_lo, 3);
    sum = vmlaq_lane_s16(sum, src[2], taps_hi, 0);
    sum = vmlaq_lane_s16(sum, src[3], taps_hi, 1);
  } else if (num_taps == 2) {
    sum = vmlaq_lane_s16(sum, src[0], taps_lo, 3);
    sum = vmlaq_lane_s16(sum, src[1], taps_hi, 0);
  }

  // This is guaranteed to be positive. Convert it for the final shift.
  return vreinterpretq_u16_s16(sum);
}

template <int num_taps, int filter_index, bool negative_outside_taps = true>
uint16x8_t SumCompoundHorizontalTaps(const uint8_t* const src,
                                     const uint8x8_t* const v_tap) {
  // Start with an offset to guarantee the sum is non negative.
  uint16x8_t v_sum = vdupq_n_u16(1 << 14);
  uint8x16_t v_src[8];
  v_src[0] = vld1q_u8(&src[0]);
  if (num_taps == 8) {
    v_src[1] = vextq_u8(v_src[0], v_src[0], 1);
    v_src[2] = vextq_u8(v_src[0], v_src[0], 2);
    v_src[3] = vextq_u8(v_src[0], v_src[0], 3);
    v_src[4] = vextq_u8(v_src[0], v_src[0], 4);
    v_src[5] = vextq_u8(v_src[0], v_src[0], 5);
    v_src[6] = vextq_u8(v_src[0], v_src[0], 6);
    v_src[7] = vextq_u8(v_src[0], v_src[0], 7);

    // tap signs : - + - + + - + -
    v_sum = vmlsl_u8(v_sum, vget_low_u8(v_src[0]), v_tap[0]);
    v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[1]), v_tap[1]);
    v_sum = vmlsl_u8(v_sum, vget_low_u8(v_src[2]), v_tap[2]);
    v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[3]), v_tap[3]);
    v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[4]), v_tap[4]);
    v_sum = vmlsl_u8(v_sum, vget_low_u8(v_src[5]), v_tap[5]);
    v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[6]), v_tap[6]);
    v_sum = vmlsl_u8(v_sum, vget_low_u8(v_src[7]), v_tap[7]);
  } else if (num_taps == 6) {
    v_src[1] = vextq_u8(v_src[0], v_src[0], 1);
    v_src[2] = vextq_u8(v_src[0], v_src[0], 2);
    v_src[3] = vextq_u8(v_src[0], v_src[0], 3);
    v_src[4] = vextq_u8(v_src[0], v_src[0], 4);
    v_src[5] = vextq_u8(v_src[0], v_src[0], 5);
    v_src[6] = vextq_u8(v_src[0], v_src[0], 6);
    if (filter_index == 0) {
      // tap signs : + - + + - +
      v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[1]), v_tap[1]);
      v_sum = vmlsl_u8(v_sum, vget_low_u8(v_src[2]), v_tap[2]);
      v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[3]), v_tap[3]);
      v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[4]), v_tap[4]);
      v_sum = vmlsl_u8(v_sum, vget_low_u8(v_src[5]), v_tap[5]);
      v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[6]), v_tap[6]);
    } else {
      if (negative_outside_taps) {
        // tap signs : - + + + + -
        v_sum = vmlsl_u8(v_sum, vget_low_u8(v_src[1]), v_tap[1]);
        v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[2]), v_tap[2]);
        v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[3]), v_tap[3]);
        v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[4]), v_tap[4]);
        v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[5]), v_tap[5]);
        v_sum = vmlsl_u8(v_sum, vget_low_u8(v_src[6]), v_tap[6]);
      } else {
        // tap signs : + + + + + +
        v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[1]), v_tap[1]);
        v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[2]), v_tap[2]);
        v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[3]), v_tap[3]);
        v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[4]), v_tap[4]);
        v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[5]), v_tap[5]);
        v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[6]), v_tap[6]);
      }
    }
  } else if (num_taps == 4) {
    v_src[2] = vextq_u8(v_src[0], v_src[0], 2);
    v_src[3] = vextq_u8(v_src[0], v_src[0], 3);
    v_src[4] = vextq_u8(v_src[0], v_src[0], 4);
    v_src[5] = vextq_u8(v_src[0], v_src[0], 5);
    if (filter_index == 4) {
      // tap signs : - + + -
      v_sum = vmlsl_u8(v_sum, vget_low_u8(v_src[2]), v_tap[2]);
      v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[3]), v_tap[3]);
      v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[4]), v_tap[4]);
      v_sum = vmlsl_u8(v_sum, vget_low_u8(v_src[5]), v_tap[5]);
    } else {
      // tap signs : + + + +
      v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[2]), v_tap[2]);
      v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[3]), v_tap[3]);
      v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[4]), v_tap[4]);
      v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[5]), v_tap[5]);
    }
  } else {
    assert(num_taps == 2);
    v_src[3] = vextq_u8(v_src[0], v_src[0], 3);
    v_src[4] = vextq_u8(v_src[0], v_src[0], 4);
    // tap signs : + +
    v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[3]), v_tap[3]);
    v_sum = vmlal_u8(v_sum, vget_low_u8(v_src[4]), v_tap[4]);
  }

  return v_sum;
}

template <int num_taps, int filter_index>
uint16x8_t SumHorizontalTaps2xH(const uint8_t* src, const ptrdiff_t src_stride,
                                const uint8x8_t* const v_tap) {
  constexpr int positive_offset_bits = kBitdepth8 + kFilterBits - 1;
  uint16x8_t sum = vdupq_n_u16(1 << positive_offset_bits);
  uint8x8_t input0 = vld1_u8(src);
  src += src_stride;
  uint8x8_t input1 = vld1_u8(src);
  uint8x8x2_t input = vzip_u8(input0, input1);

  if (num_taps == 2) {
    // tap signs : + +
    sum = vmlal_u8(sum, vext_u8(input.val[0], input.val[1], 6), v_tap[3]);
    sum = vmlal_u8(sum, input.val[1], v_tap[4]);
  } else if (filter_index == 4) {
    // tap signs : - + + -
    sum = vmlsl_u8(sum, RightShift<4 * 8>(input.val[0]), v_tap[2]);
    sum = vmlal_u8(sum, vext_u8(input.val[0], input.val[1], 6), v_tap[3]);
    sum = vmlal_u8(sum, input.val[1], v_tap[4]);
    sum = vmlsl_u8(sum, RightShift<2 * 8>(input.val[1]), v_tap[5]);
  } else {
    // tap signs : + + + +
    sum = vmlal_u8(sum, RightShift<4 * 8>(input.val[0]), v_tap[2]);
    sum = vmlal_u8(sum, vext_u8(input.val[0], input.val[1], 6), v_tap[3]);
    sum = vmlal_u8(sum, input.val[1], v_tap[4]);
    sum = vmlal_u8(sum, RightShift<2 * 8>(input.val[1]), v_tap[5]);
  }

  return vrshrq_n_u16(sum, kInterRoundBitsHorizontal);
}

// TODO(johannkoenig): Rename this function. It works for more than just
// compound convolutions.
template <int num_taps, int step, int filter_index,
          bool negative_outside_taps = true, bool is_2d = false,
          bool is_8bit = false>
void ConvolveCompoundHorizontalBlock(const uint8_t* src,
                                     const ptrdiff_t src_stride,
                                     void* const dest,
                                     const ptrdiff_t pred_stride,
                                     const int width, const int height,
                                     const uint8x8_t* const v_tap) {
  const uint16x8_t v_compound_round_offset = vdupq_n_u16(1 << (kBitdepth8 + 4));
  const int16x8_t v_inter_round_bits_0 =
      vdupq_n_s16(-kInterRoundBitsHorizontal);

  auto* dest8 = static_cast<uint8_t*>(dest);
  auto* dest16 = static_cast<uint16_t*>(dest);

  if (width > 4) {
    int y = 0;
    do {
      int x = 0;
      do {
        uint16x8_t v_sum =
            SumCompoundHorizontalTaps<num_taps, filter_index,
                                      negative_outside_taps>(&src[x], v_tap);
        if (is_8bit) {
          // Split shifts the way they are in C. They can be combined but that
          // makes removing the 1 << 14 offset much more difficult.
          v_sum = vrshrq_n_u16(v_sum, kInterRoundBitsHorizontal);
          int16x8_t v_sum_signed = vreinterpretq_s16_u16(vsubq_u16(
              v_sum, vdupq_n_u16(1 << (14 - kInterRoundBitsHorizontal))));
          uint8x8_t result = vqrshrun_n_s16(
              v_sum_signed, kFilterBits - kInterRoundBitsHorizontal);
          vst1_u8(&dest8[x], result);
        } else {
          v_sum = vrshlq_u16(v_sum, v_inter_round_bits_0);
          if (!is_2d) {
            v_sum = vaddq_u16(v_sum, v_compound_round_offset);
          }
          vst1q_u16(&dest16[x], v_sum);
        }
        x += step;
      } while (x < width);
      src += src_stride;
      dest8 += pred_stride;
      dest16 += pred_stride;
    } while (++y < height);
    return;
  } else if (width == 4) {
    int y = 0;
    do {
      uint16x8_t v_sum =
          SumCompoundHorizontalTaps<num_taps, filter_index,
                                    negative_outside_taps>(&src[0], v_tap);
      if (is_8bit) {
        v_sum = vrshrq_n_u16(v_sum, kInterRoundBitsHorizontal);
        int16x8_t v_sum_signed = vreinterpretq_s16_u16(vsubq_u16(
            v_sum, vdupq_n_u16(1 << (14 - kInterRoundBitsHorizontal))));
        uint8x8_t result = vqrshrun_n_s16(
            v_sum_signed, kFilterBits - kInterRoundBitsHorizontal);
        StoreLo4(&dest8[0], result);
      } else {
        v_sum = vrshlq_u16(v_sum, v_inter_round_bits_0);
        if (!is_2d) {
          v_sum = vaddq_u16(v_sum, v_compound_round_offset);
        }
        vst1_u16(&dest16[0], vget_low_u16(v_sum));
      }
      src += src_stride;
      dest8 += pred_stride;
      dest16 += pred_stride;
    } while (++y < height);
    return;
  }

  // Horizontal passes only needs to account for |num_taps| 2 and 4 when
  // |width| == 2.
  assert(width == 2);
  assert(num_taps <= 4);

  constexpr int positive_offset_bits = kBitdepth8 + kFilterBits - 1;
  // Leave off + 1 << (kBitdepth8 + 3).
  constexpr int compound_round_offset = 1 << (kBitdepth8 + 4);

  if (num_taps <= 4) {
    int y = 0;
    do {
      // TODO(johannkoenig): Re-order the values for storing.
      uint16x8_t sum =
          SumHorizontalTaps2xH<num_taps, filter_index>(src, src_stride, v_tap);

      if (is_2d) {
        dest16[0] = vgetq_lane_u16(sum, 0);
        dest16[1] = vgetq_lane_u16(sum, 2);
        dest16 += pred_stride;
        dest16[0] = vgetq_lane_u16(sum, 1);
        dest16[1] = vgetq_lane_u16(sum, 3);
        dest16 += pred_stride;
      } else if (!is_8bit) {
        // None of the test vectors hit this path but the unit tests do.
        sum = vaddq_u16(sum, vdupq_n_u16(compound_round_offset));

        dest16[0] = vgetq_lane_u16(sum, 0);
        dest16[1] = vgetq_lane_u16(sum, 2);
        dest16 += pred_stride;
        dest16[0] = vgetq_lane_u16(sum, 1);
        dest16[1] = vgetq_lane_u16(sum, 3);
        dest16 += pred_stride;
      } else {
        // Split shifts the way they are in C. They can be combined but that
        // makes removing the 1 << 14 offset much more difficult.
        int16x8_t sum_signed = vreinterpretq_s16_u16(vsubq_u16(
            sum, vdupq_n_u16(
                     1 << (positive_offset_bits - kInterRoundBitsHorizontal))));
        uint8x8_t result =
            vqrshrun_n_s16(sum_signed, kFilterBits - kInterRoundBitsHorizontal);

        // Could de-interleave and vst1_lane_u16().
        dest8[0] = vget_lane_u8(result, 0);
        dest8[1] = vget_lane_u8(result, 2);
        dest8 += pred_stride;

        dest8[0] = vget_lane_u8(result, 1);
        dest8[1] = vget_lane_u8(result, 3);
        dest8 += pred_stride;
      }

      src += src_stride << 1;
      y += 2;
    } while (y < height - 1);

    // The 2d filters have an odd |height| because the horizontal pass generates
    // context for the vertical pass.
    if (is_2d) {
      assert(height % 2 == 1);
      uint16x8_t sum = vdupq_n_u16(1 << positive_offset_bits);
      uint8x8_t input = vld1_u8(src);
      if (filter_index == 3) {  // |num_taps| == 2
        sum = vmlal_u8(sum, RightShift<3 * 8>(input), v_tap[3]);
        sum = vmlal_u8(sum, RightShift<4 * 8>(input), v_tap[4]);
      } else if (filter_index == 4) {
        sum = vmlsl_u8(sum, RightShift<2 * 8>(input), v_tap[2]);
        sum = vmlal_u8(sum, RightShift<3 * 8>(input), v_tap[3]);
        sum = vmlal_u8(sum, RightShift<4 * 8>(input), v_tap[4]);
        sum = vmlsl_u8(sum, RightShift<5 * 8>(input), v_tap[5]);
      } else {
        assert(filter_index == 5);
        sum = vmlal_u8(sum, RightShift<2 * 8>(input), v_tap[2]);
        sum = vmlal_u8(sum, RightShift<3 * 8>(input), v_tap[3]);
        sum = vmlal_u8(sum, RightShift<4 * 8>(input), v_tap[4]);
        sum = vmlal_u8(sum, RightShift<5 * 8>(input), v_tap[5]);
        sum = vrshrq_n_u16(sum, kInterRoundBitsHorizontal);
      }
      Store2<0>(dest16, sum);
    }
  }
}

// Process 16 bit inputs and output 32 bits.
template <int num_taps>
uint32x4x2_t Sum2DVerticalTaps(const int16x8_t* const src,
                               const int16x8_t taps) {
  // In order to get the rollover correct with the lengthening instruction we
  // need to treat these as signed so that they sign extend properly.
  const int16x4_t taps_lo = vget_low_s16(taps);
  const int16x4_t taps_hi = vget_high_s16(taps);
  // An offset to guarantee the sum is non negative. Captures 56 * -4590 =
  // 257040 (worst case negative value from horizontal pass). It should be
  // possible to use 1 << 18 (262144) instead of 1 << 19 but there probably
  // isn't any benefit.
  // |offset_bits| = bitdepth + 2 * kFilterBits - kInterRoundBitsHorizontal
  // == 19.
  int32x4_t sum_lo = vdupq_n_s32(1 << 19);
  int32x4_t sum_hi = sum_lo;
  if (num_taps == 8) {
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[0]), taps_lo, 0);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[0]), taps_lo, 0);
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[1]), taps_lo, 1);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[1]), taps_lo, 1);
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[2]), taps_lo, 2);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[2]), taps_lo, 2);
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[3]), taps_lo, 3);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[3]), taps_lo, 3);

    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[4]), taps_hi, 0);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[4]), taps_hi, 0);
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[5]), taps_hi, 1);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[5]), taps_hi, 1);
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[6]), taps_hi, 2);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[6]), taps_hi, 2);
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[7]), taps_hi, 3);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[7]), taps_hi, 3);
  } else if (num_taps == 6) {
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[0]), taps_lo, 1);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[0]), taps_lo, 1);
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[1]), taps_lo, 2);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[1]), taps_lo, 2);
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[2]), taps_lo, 3);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[2]), taps_lo, 3);

    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[3]), taps_hi, 0);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[3]), taps_hi, 0);
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[4]), taps_hi, 1);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[4]), taps_hi, 1);
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[5]), taps_hi, 2);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[5]), taps_hi, 2);
  } else if (num_taps == 4) {
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[0]), taps_lo, 2);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[0]), taps_lo, 2);
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[1]), taps_lo, 3);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[1]), taps_lo, 3);

    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[2]), taps_hi, 0);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[2]), taps_hi, 0);
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[3]), taps_hi, 1);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[3]), taps_hi, 1);
  } else if (num_taps == 2) {
    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[0]), taps_lo, 3);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[0]), taps_lo, 3);

    sum_lo = vmlal_lane_s16(sum_lo, vget_low_s16(src[1]), taps_hi, 0);
    sum_hi = vmlal_lane_s16(sum_hi, vget_high_s16(src[1]), taps_hi, 0);
  }

  // This is guaranteed to be positive. Convert it for the final shift.
  const uint32x4x2_t return_val = {vreinterpretq_u32_s32(sum_lo),
                                   vreinterpretq_u32_s32(sum_hi)};
  return return_val;
}

// Process 16 bit inputs and output 32 bits.
template <int num_taps>
uint32x4_t Sum2DVerticalTaps(const int16x4_t* const src, const int16x8_t taps) {
  // In order to get the rollover correct with the lengthening instruction we
  // need to treat these as signed so that they sign extend properly.
  const int16x4_t taps_lo = vget_low_s16(taps);
  const int16x4_t taps_hi = vget_high_s16(taps);
  // An offset to guarantee the sum is non negative. Captures 56 * -4590 =
  // 257040 (worst case negative value from horizontal pass). It should be
  // possible to use 1 << 18 (262144) instead of 1 << 19 but there probably
  // isn't any benefit.
  // |offset_bits| = bitdepth + 2 * kFilterBits - kInterRoundBitsHorizontal
  // == 19.
  int32x4_t sum = vdupq_n_s32(1 << 19);
  if (num_taps == 8) {
    sum = vmlal_lane_s16(sum, src[0], taps_lo, 0);
    sum = vmlal_lane_s16(sum, src[1], taps_lo, 1);
    sum = vmlal_lane_s16(sum, src[2], taps_lo, 2);
    sum = vmlal_lane_s16(sum, src[3], taps_lo, 3);

    sum = vmlal_lane_s16(sum, src[4], taps_hi, 0);
    sum = vmlal_lane_s16(sum, src[5], taps_hi, 1);
    sum = vmlal_lane_s16(sum, src[6], taps_hi, 2);
    sum = vmlal_lane_s16(sum, src[7], taps_hi, 3);
  } else if (num_taps == 6) {
    sum = vmlal_lane_s16(sum, src[0], taps_lo, 1);
    sum = vmlal_lane_s16(sum, src[1], taps_lo, 2);
    sum = vmlal_lane_s16(sum, src[2], taps_lo, 3);

    sum = vmlal_lane_s16(sum, src[3], taps_hi, 0);
    sum = vmlal_lane_s16(sum, src[4], taps_hi, 1);
    sum = vmlal_lane_s16(sum, src[5], taps_hi, 2);
  } else if (num_taps == 4) {
    sum = vmlal_lane_s16(sum, src[0], taps_lo, 2);
    sum = vmlal_lane_s16(sum, src[1], taps_lo, 3);

    sum = vmlal_lane_s16(sum, src[2], taps_hi, 0);
    sum = vmlal_lane_s16(sum, src[3], taps_hi, 1);
  } else if (num_taps == 2) {
    sum = vmlal_lane_s16(sum, src[0], taps_lo, 3);

    sum = vmlal_lane_s16(sum, src[1], taps_hi, 0);
  }

  // This is guaranteed to be positive. Convert it for the final shift.
  return vreinterpretq_u32_s32(sum);
}

template <int num_taps, bool is_compound = false>
void Filter2DVertical(const uint16_t* src, const ptrdiff_t src_stride,
                      void* const dst, const ptrdiff_t dst_stride,
                      const int width, const int height, const int16x8_t taps,
                      const int inter_round_bits_vertical) {
  constexpr int next_row = num_taps - 1;
  const int32x4_t v_inter_round_bits_vertical =
      vdupq_n_s32(-inter_round_bits_vertical);

  auto* dst8 = static_cast<uint8_t*>(dst);
  auto* dst16 = static_cast<uint16_t*>(dst);

  if (width > 4) {
    int x = 0;
    do {
      int16x8_t srcs[8];
      srcs[0] = vreinterpretq_s16_u16(vld1q_u16(src + x));
      if (num_taps >= 4) {
        srcs[1] = vreinterpretq_s16_u16(vld1q_u16(src + x + src_stride));
        srcs[2] = vreinterpretq_s16_u16(vld1q_u16(src + x + 2 * src_stride));
        if (num_taps >= 6) {
          srcs[3] = vreinterpretq_s16_u16(vld1q_u16(src + x + 3 * src_stride));
          srcs[4] = vreinterpretq_s16_u16(vld1q_u16(src + x + 4 * src_stride));
          if (num_taps == 8) {
            srcs[5] =
                vreinterpretq_s16_u16(vld1q_u16(src + x + 5 * src_stride));
            srcs[6] =
                vreinterpretq_s16_u16(vld1q_u16(src + x + 6 * src_stride));
          }
        }
      }

      int y = 0;
      do {
        srcs[next_row] = vreinterpretq_s16_u16(
            vld1q_u16(src + x + (y + next_row) * src_stride));

        const uint32x4x2_t sums = Sum2DVerticalTaps<num_taps>(srcs, taps);
        if (is_compound) {
          const uint16x8_t results = vcombine_u16(
              vmovn_u32(vqrshlq_u32(sums.val[0], v_inter_round_bits_vertical)),
              vmovn_u32(vqrshlq_u32(sums.val[1], v_inter_round_bits_vertical)));
          vst1q_u16(dst16 + x + y * dst_stride, results);
        } else {
          const uint16x8_t first_shift =
              vcombine_u16(vqrshrn_n_u32(sums.val[0], kInterRoundBitsVertical),
                           vqrshrn_n_u32(sums.val[1], kInterRoundBitsVertical));
          // |single_round_offset| == (1 << bitdepth) + (1 << (bitdepth - 1)) ==
          // 384
          const uint8x8_t results =
              vqmovn_u16(vqsubq_u16(first_shift, vdupq_n_u16(384)));

          vst1_u8(dst8 + x + y * dst_stride, results);
        }

        srcs[0] = srcs[1];
        if (num_taps >= 4) {
          srcs[1] = srcs[2];
          srcs[2] = srcs[3];
          if (num_taps >= 6) {
            srcs[3] = srcs[4];
            srcs[4] = srcs[5];
            if (num_taps == 8) {
              srcs[5] = srcs[6];
              srcs[6] = srcs[7];
            }
          }
        }
      } while (++y < height);
      x += 8;
    } while (x < width);
    return;
  }

  assert(width == 4);
  int16x4_t srcs[8];
  srcs[0] = vreinterpret_s16_u16(vld1_u16(src));
  src += src_stride;
  if (num_taps >= 4) {
    srcs[1] = vreinterpret_s16_u16(vld1_u16(src));
    src += src_stride;
    srcs[2] = vreinterpret_s16_u16(vld1_u16(src));
    src += src_stride;
    if (num_taps >= 6) {
      srcs[3] = vreinterpret_s16_u16(vld1_u16(src));
      src += src_stride;
      srcs[4] = vreinterpret_s16_u16(vld1_u16(src));
      src += src_stride;
      if (num_taps == 8) {
        srcs[5] = vreinterpret_s16_u16(vld1_u16(src));
        src += src_stride;
        srcs[6] = vreinterpret_s16_u16(vld1_u16(src));
        src += src_stride;
      }
    }
  }

  int y = 0;
  do {
    srcs[next_row] = vreinterpret_s16_u16(vld1_u16(src));
    src += src_stride;

    const uint32x4_t sums = Sum2DVerticalTaps<num_taps>(srcs, taps);
    if (is_compound) {
      const uint16x4_t results =
          vmovn_u32(vqrshlq_u32(sums, v_inter_round_bits_vertical));
      vst1_u16(dst16, results);
      dst16 += dst_stride;
    } else {
      const uint16x4_t first_shift =
          vqrshrn_n_u32(sums, kInterRoundBitsVertical);
      // |single_round_offset| == (1 << bitdepth) + (1 << (bitdepth - 1)) ==
      // 384
      const uint8x8_t results = vqmovn_u16(
          vcombine_u16(vqsub_u16(first_shift, vdup_n_u16(384)), vdup_n_u16(0)));

      StoreLo4(dst8, results);
      dst8 += dst_stride;
    }

    srcs[0] = srcs[1];
    if (num_taps >= 4) {
      srcs[1] = srcs[2];
      srcs[2] = srcs[3];
      if (num_taps >= 6) {
        srcs[3] = srcs[4];
        srcs[4] = srcs[5];
        if (num_taps == 8) {
          srcs[5] = srcs[6];
          srcs[6] = srcs[7];
        }
      }
    }
  } while (++y < height);
}

template <bool is_2d = false, bool is_8bit = false>
void HorizontalPass(const uint8_t* const src, const ptrdiff_t src_stride,
                    void* const dst, const ptrdiff_t dst_stride,
                    const int width, const int height, const int subpixel,
                    const int filter_index) {
  // Duplicate the absolute value for each tap.  Negative taps are corrected
  // by using the vmlsl_u8 instruction.  Positive taps use vmlal_u8.
  uint8x8_t v_tap[kSubPixelTaps];
  const int filter_id = (subpixel >> 6) & kSubPixelMask;
  for (int k = 0; k < kSubPixelTaps; ++k) {
    v_tap[k] = vreinterpret_u8_s8(
        vabs_s8(vdup_n_s8(kSubPixelFilters[filter_index][filter_id][k])));
  }

  if (filter_index == 2) {  // 8 tap.
    ConvolveCompoundHorizontalBlock<8, 8, 2, true, is_2d, is_8bit>(
        src, src_stride, dst, dst_stride, width, height, v_tap);
  } else if (filter_index == 1) {  // 6 tap.
    // Check if outside taps are positive.
    if ((filter_id == 1) | (filter_id == 15)) {
      ConvolveCompoundHorizontalBlock<6, 8, 1, false, is_2d, is_8bit>(
          src, src_stride, dst, dst_stride, width, height, v_tap);
    } else {
      ConvolveCompoundHorizontalBlock<6, 8, 1, true, is_2d, is_8bit>(
          src, src_stride, dst, dst_stride, width, height, v_tap);
    }
  } else if (filter_index == 0) {  // 6 tap.
    ConvolveCompoundHorizontalBlock<6, 8, 0, true, is_2d, is_8bit>(
        src, src_stride, dst, dst_stride, width, height, v_tap);
  } else if (filter_index == 4) {  // 4 tap.
    ConvolveCompoundHorizontalBlock<4, 8, 4, true, is_2d, is_8bit>(
        src, src_stride, dst, dst_stride, width, height, v_tap);
  } else if (filter_index == 5) {  // 4 tap.
    ConvolveCompoundHorizontalBlock<4, 8, 5, true, is_2d, is_8bit>(
        src, src_stride, dst, dst_stride, width, height, v_tap);
  } else {  // 2 tap.
    ConvolveCompoundHorizontalBlock<2, 8, 3, true, is_2d, is_8bit>(
        src, src_stride, dst, dst_stride, width, height, v_tap);
  }
}

// There are three forms of this function:
// 2D: input 8bit, output 16bit. |is_compound| has no effect.
// 1D Horizontal: input 8bit, output 8bit.
// 1D Compound Horizontal: input 8bit, output 16bit. Different rounding from 2D.
// |width| is guaranteed to be 2 because all other cases are handled in neon.
template <bool is_2d = true, bool is_compound = false>
void HorizontalPass2xH(const uint8_t* src, const ptrdiff_t src_stride,
                       void* const dst, const ptrdiff_t dst_stride,
                       const int height, const int filter_index, const int taps,
                       const int subpixel) {
  // Even though |is_compound| has no effect when |is_2d| is true we block this
  // combination in case the compiler gets confused.
  static_assert(!is_2d || !is_compound, "|is_compound| is ignored.");
  // Since this only handles |width| == 2, we only need to be concerned with
  // 2 or 4 tap filters.
  assert(taps == 2 || taps == 4);
  auto* dst8 = static_cast<uint8_t*>(dst);
  auto* dst16 = static_cast<uint16_t*>(dst);

  const int compound_round_offset =
      (1 << (kBitdepth8 + 4)) + (1 << (kBitdepth8 + 3));

  const int filter_id = (subpixel >> 6) & kSubPixelMask;
  const int taps_start = (kSubPixelTaps - taps) / 2;
  int y = 0;
  do {
    int x = 0;
    do {
      int sum;
      if (is_2d) {
        // An offset to guarantee the sum is non negative.
        sum = 1 << (kBitdepth8 + kFilterBits - 1);
      } else if (is_compound) {
        sum = 0;
      } else {
        // 1D non-Compound. The C uses a two stage shift with rounding. Here the
        // shifts are combined and the rounding bit from the first stage is
        // added in.
        // (sum + 4 >> 3) + 8) >> 4 == (sum + 64 + 4) >> 7
        sum = 4;
      }
      for (int k = 0; k < taps; ++k) {
        const int tap = k + taps_start;
        sum += kSubPixelFilters[filter_index][filter_id][tap] * src[x + k];
      }
      if (is_2d) {
        dst16[x] = static_cast<int16_t>(
            RightShiftWithRounding(sum, kInterRoundBitsHorizontal));
      } else if (is_compound) {
        sum = RightShiftWithRounding(sum, kInterRoundBitsHorizontal);
        dst16[x] = sum + compound_round_offset;
      } else {
        // 1D non-Compound.
        dst8[x] = static_cast<uint8_t>(
            Clip3(RightShiftWithRounding(sum, kFilterBits), 0, 255));
      }
    } while (++x < 2);

    src += src_stride;
    dst8 += dst_stride;
    dst16 += dst_stride;
  } while (++y < height);
}

// This will always need to handle all |filter_index| values. Even with |width|
// restricted to 2 the value of |height| can go up to at least 16.
template <bool is_2d = true, bool is_compound = false>
void VerticalPass2xH(const void* const src, const ptrdiff_t src_stride,
                     void* const dst, const ptrdiff_t dst_stride,
                     const int height, const int inter_round_bits_vertical,
                     const int filter_index, const int taps,
                     const int subpixel) {
  const auto* src8 = static_cast<const uint8_t*>(src);
  const auto* src16 = static_cast<const uint16_t*>(src);
  auto* dst8 = static_cast<uint8_t*>(dst);
  auto* dst16 = static_cast<uint16_t*>(dst);
  const int filter_id = (subpixel >> 6) & kSubPixelMask;
  const int taps_start = (kSubPixelTaps - taps) / 2;
  constexpr int max_pixel_value = (1 << kBitdepth8) - 1;

  int y = 0;
  do {
    int x = 0;
    do {
      int sum;
      if (is_2d) {
        sum = 1 << (kBitdepth8 + 2 * kFilterBits - kInterRoundBitsHorizontal);
      } else if (is_compound) {
        // TODO(johannkoenig): Keeping the sum positive is valuable for neon but
        // may not actually help the C implementation. Investigate removing
        // this.
        // Use this offset to cancel out 1 << (kBitdepth8 + 3) >> 3 from
        // |compound_round_offset|.
        sum = (1 << (kBitdepth8 + 3)) << 3;
      } else {
        sum = 0;
      }

      for (int k = 0; k < taps; ++k) {
        const int tap = k + taps_start;
        if (is_2d) {
          sum += kSubPixelFilters[filter_index][filter_id][tap] *
                 src16[x + k * src_stride];
        } else {
          sum += kSubPixelFilters[filter_index][filter_id][tap] *
                 src8[x + k * src_stride];
        }
      }

      if (is_2d) {
        if (is_compound) {
          dst16[x] = static_cast<uint16_t>(
              RightShiftWithRounding(sum, inter_round_bits_vertical));
        } else {
          constexpr int single_round_offset =
              (1 << kBitdepth8) + (1 << (kBitdepth8 - 1));
          dst8[x] = static_cast<uint8_t>(
              Clip3(RightShiftWithRounding(sum, kInterRoundBitsVertical) -
                        single_round_offset,
                    0, max_pixel_value));
        }
      } else if (is_compound) {
        // Leave off + 1 << (kBitdepth8 + 3).
        constexpr int compound_round_offset = 1 << (kBitdepth8 + 4);
        dst16[x] = RightShiftWithRounding(sum, 3) + compound_round_offset;
      } else {
        // 1D non-compound.
        dst8[x] = static_cast<uint8_t>(Clip3(
            RightShiftWithRounding(sum, kFilterBits), 0, max_pixel_value));
      }
    } while (++x < 2);

    src8 += src_stride;
    src16 += src_stride;
    dst8 += dst_stride;
    dst16 += dst_stride;
  } while (++y < height);
}

int NumTapsInFilter(const int filter_index) {
  if (filter_index < 2) {
    // Despite the names these only use 6 taps.
    // kInterpolationFilterEightTap
    // kInterpolationFilterEightTapSmooth
    return 6;
  }

  if (filter_index == 2) {
    // kInterpolationFilterEightTapSharp
    return 8;
  }

  if (filter_index == 3) {
    // kInterpolationFilterBilinear
    return 2;
  }

  assert(filter_index > 3);
  // For small sizes (width/height <= 4) the large filters are replaced with 4
  // tap options.
  // If the original filters were |kInterpolationFilterEightTap| or
  // |kInterpolationFilterEightTapSharp| then it becomes
  // |kInterpolationFilterSwitchable|.
  // If it was |kInterpolationFilterEightTapSmooth| then it becomes an unnamed 4
  // tap filter.
  return 4;
}

void Convolve2D_NEON(const void* const reference,
                     const ptrdiff_t reference_stride,
                     const int horizontal_filter_index,
                     const int vertical_filter_index,
                     const int /*inter_round_bits_vertical*/,
                     const int subpixel_x, const int subpixel_y,
                     const int /*step_x*/, const int /*step_y*/,
                     const int width, const int height, void* prediction,
                     const ptrdiff_t pred_stride) {
  const int horiz_filter_index = GetFilterIndex(horizontal_filter_index, width);
  const int vert_filter_index = GetFilterIndex(vertical_filter_index, height);
  const int horizontal_taps = NumTapsInFilter(horiz_filter_index);
  const int vertical_taps = NumTapsInFilter(vert_filter_index);

  // The output of the horizontal filter is guaranteed to fit in 16 bits.
  uint16_t
      intermediate_result[kMaxSuperBlockSizeInPixels *
                          (kMaxSuperBlockSizeInPixels + kSubPixelTaps - 1)];
  const int intermediate_stride = width;
  const int intermediate_height = height + vertical_taps - 1;

  if (width >= 4) {
    const ptrdiff_t src_stride = reference_stride;
    const auto* src = static_cast<const uint8_t*>(reference) -
                      (vertical_taps / 2 - 1) * src_stride - kHorizontalOffset;

    HorizontalPass<true>(src, src_stride, intermediate_result,
                         intermediate_stride, width, intermediate_height,
                         subpixel_x, horiz_filter_index);

    // Vertical filter.
    auto* dest = static_cast<uint8_t*>(prediction);
    const ptrdiff_t dest_stride = pred_stride;
    const int filter_id = ((subpixel_y & 1023) >> 6) & kSubPixelMask;
    const int16x8_t taps =
        vld1q_s16(kSubPixelFilters[vert_filter_index][filter_id]);

    if (vertical_taps == 8) {
      Filter2DVertical<8>(intermediate_result, intermediate_stride, dest,
                          dest_stride, width, height, taps, 0);
    } else if (vertical_taps == 6) {
      Filter2DVertical<6>(intermediate_result, intermediate_stride, dest,
                          dest_stride, width, height, taps, 0);
    } else if (vertical_taps == 4) {
      Filter2DVertical<4>(intermediate_result, intermediate_stride, dest,
                          dest_stride, width, height, taps, 0);
    } else {  // |vertical_taps| == 2
      Filter2DVertical<2>(intermediate_result, intermediate_stride, dest,
                          dest_stride, width, height, taps, 0);
    }
  } else {
    assert(width == 2);
    // Horizontal filter.
    const auto* const src = static_cast<const uint8_t*>(reference) -
                            ((vertical_taps / 2) - 1) * reference_stride -
                            ((horizontal_taps / 2) - 1);

    HorizontalPass2xH(src, reference_stride, intermediate_result,
                      intermediate_stride, intermediate_height,
                      horiz_filter_index, horizontal_taps, subpixel_x);

    // Vertical filter.
    auto* dest = static_cast<uint8_t*>(prediction);
    const ptrdiff_t dest_stride = pred_stride;

    VerticalPass2xH(intermediate_result, intermediate_stride, dest, dest_stride,
                    height, 0, vert_filter_index, vertical_taps, subpixel_y);
  }
}

template <int tap_lane0, int tap_lane1>
inline int16x8_t CombineFilterTapsLong(const int16x8_t sum,
                                       const int16x8_t src0, int16x8_t src1,
                                       int16x4_t taps0, int16x4_t taps1) {
  int32x4_t sum_lo = vmovl_s16(vget_low_s16(sum));
  int32x4_t sum_hi = vmovl_s16(vget_high_s16(sum));
  const int16x8_t product0 = vmulq_lane_s16(src0, taps0, tap_lane0);
  const int16x8_t product1 = vmulq_lane_s16(src1, taps1, tap_lane1);
  const int32x4_t center_vals_lo =
      vaddl_s16(vget_low_s16(product0), vget_low_s16(product1));
  const int32x4_t center_vals_hi =
      vaddl_s16(vget_high_s16(product0), vget_high_s16(product1));

  sum_lo = vaddq_s32(sum_lo, center_vals_lo);
  sum_hi = vaddq_s32(sum_hi, center_vals_hi);
  return vcombine_s16(vrshrn_n_s32(sum_lo, 3), vrshrn_n_s32(sum_hi, 3));
}

// TODO(b/133525024): Replace usage of this function with version that uses
// unsigned trick, once cl/263050071 is submitted.
template <int num_taps>
inline int16x8_t SumTapsCompound(const int16x8_t* const src,
                                 const int16x8_t taps) {
  int16x8_t sum = vdupq_n_s16(1 << (kBitdepth8 + kFilterBits - 1));
  if (num_taps == 8) {
    const int16x4_t taps_lo = vget_low_s16(taps);
    const int16x4_t taps_hi = vget_high_s16(taps);
    sum = vmlaq_lane_s16(sum, src[0], taps_lo, 0);
    sum = vmlaq_lane_s16(sum, src[1], taps_lo, 1);
    sum = vmlaq_lane_s16(sum, src[2], taps_lo, 2);
    sum = vmlaq_lane_s16(sum, src[5], taps_hi, 1);
    sum = vmlaq_lane_s16(sum, src[6], taps_hi, 2);
    sum = vmlaq_lane_s16(sum, src[7], taps_hi, 3);

    // Center taps may sum to as much as 160, which pollutes the sign bit in
    // int16 types.
    sum = CombineFilterTapsLong<3, 0>(sum, src[3], src[4], taps_lo, taps_hi);
  } else if (num_taps == 6) {
    const int16x4_t taps_lo = vget_low_s16(taps);
    const int16x4_t taps_hi = vget_high_s16(taps);
    sum = vmlaq_lane_s16(sum, src[0], taps_lo, 0);
    sum = vmlaq_lane_s16(sum, src[1], taps_lo, 1);
    sum = vmlaq_lane_s16(sum, src[4], taps_hi, 0);
    sum = vmlaq_lane_s16(sum, src[5], taps_hi, 1);

    // Center taps in filter 0 may sum to as much as 148, which pollutes the
    // sign bit in int16 types. This is not true of filter 1.
    sum = CombineFilterTapsLong<2, 3>(sum, src[2], src[3], taps_lo, taps_lo);
  } else if (num_taps == 4) {
    const int16x4_t taps_lo = vget_low_s16(taps);
    sum = vmlaq_lane_s16(sum, src[0], taps_lo, 0);
    sum = vmlaq_lane_s16(sum, src[3], taps_lo, 3);

    // Center taps.
    sum = vqaddq_s16(sum, vmulq_lane_s16(src[1], taps_lo, 1));
    sum = vrshrq_n_s16(vqaddq_s16(sum, vmulq_lane_s16(src[2], taps_lo, 2)),
                       kInterRoundBitsHorizontal);
  } else {
    assert(num_taps == 2);
    // All the taps are positive so there is no concern regarding saturation.
    const int16x4_t taps_lo = vget_low_s16(taps);
    sum = vmlaq_lane_s16(sum, src[0], taps_lo, 0);
    sum = vrshrq_n_s16(vmlaq_lane_s16(sum, src[1], taps_lo, 1),
                       kInterRoundBitsHorizontal);
  }
  return sum;
}

// |grade_x| determines an upper limit on how many whole-pixel steps will be
// realized with 8 |step_x| increments.
template <int filter_index, int num_taps, int grade_x>
inline void ConvolveHorizontalScaled_NEON(const uint8_t* src,
                                          const ptrdiff_t src_stride,
                                          const int width, const int subpixel_x,
                                          const int step_x,
                                          const int intermediate_height,
                                          int16_t* dst) {
  const int dst_stride = kMaxSuperBlockSizeInPixels;
  const int kernel_offset = (8 - num_taps) / 2;
  const int ref_x = subpixel_x >> kScaleSubPixelBits;
  int y = intermediate_height;
  do {  // y > 0
    int p = subpixel_x;
    int prev_p = p;
    int x = 0;
    int16x8_t s[(grade_x + 1) * 8];
    const uint8_t* src_x =
        &src[(p >> kScaleSubPixelBits) - ref_x + kernel_offset];
    // TODO(petersonab,b/139707209): Fix source buffer overreads.
    // For example, when |height| == 2 and |num_taps| == 8 then
    // |intermediate_height| == 9. On the second pass this will load and
    // transpose 7 rows past where |src| may end.
    Load8x8(src_x, src_stride, s);
    Transpose8x8(s);
    if (grade_x > 1) {
      Load8x8(src_x + 8, src_stride, &s[8]);
      Transpose8x8(&s[8]);
    }

    do {  // x < width
      int16x8_t result[8];
      src_x = &src[(p >> kScaleSubPixelBits) - ref_x + kernel_offset];
      // process 8 src_x steps
      Load8x8(src_x + 8, src_stride, &s[8]);
      Transpose8x8(&s[8]);
      if (grade_x > 1) {
        Load8x8(src_x + 16, src_stride, &s[16]);
        Transpose8x8(&s[16]);
      }
      // Remainder after whole index increments.
      int pixel_offset = p & ((1 << kScaleSubPixelBits) - 1);
      for (int z = 0; z < 8; ++z) {
        const int16x8_t filter = vld1q_s16(
            &kSubPixelFilters[filter_index][(p >> 6) & 0xF][kernel_offset]);
        result[z] = SumTapsCompound<num_taps>(
            &s[pixel_offset >> kScaleSubPixelBits], filter);
        pixel_offset += step_x;
        p += step_x;
      }

      // Transpose the 8x8 filtered values back to dst.
      Transpose8x8(result);

      vst1q_s16(&dst[x + 0 * dst_stride], result[0]);
      vst1q_s16(&dst[x + 1 * dst_stride], result[1]);
      vst1q_s16(&dst[x + 2 * dst_stride], result[2]);
      vst1q_s16(&dst[x + 3 * dst_stride], result[3]);
      vst1q_s16(&dst[x + 4 * dst_stride], result[4]);
      vst1q_s16(&dst[x + 5 * dst_stride], result[5]);
      vst1q_s16(&dst[x + 6 * dst_stride], result[6]);
      vst1q_s16(&dst[x + 7 * dst_stride], result[7]);

      for (int i = 0; i < 8; ++i) {
        s[i] =
            s[(p >> kScaleSubPixelBits) - (prev_p >> kScaleSubPixelBits) + i];
        if (grade_x > 1) {
          s[i + 8] = s[(p >> kScaleSubPixelBits) -
                       (prev_p >> kScaleSubPixelBits) + i + 8];
        }
      }

      prev_p = p;
      x += 8;
    } while (x < width);

    src += src_stride * 8;
    dst += dst_stride * 8;
    y -= 8;
  } while (y > 0);
}

inline uint8x16_t GetPositive2TapFilter(const int tap_index) {
  assert(tap_index < 2);
  constexpr uint8_t kSubPixel2TapFilterColumns[2][16] = {
      {128, 120, 112, 104, 96, 88, 80, 72, 64, 56, 48, 40, 32, 24, 16, 8},
      {0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 96, 104, 112, 120}};

  return vld1q_u8(kSubPixel2TapFilterColumns[tap_index]);
}

inline void ConvolveKernelHorizontal2Tap(const uint8_t* src,
                                         const ptrdiff_t src_stride,
                                         const int width, const int subpixel_x,
                                         const int step_x,
                                         const int intermediate_height,
                                         int16_t* intermediate) {
  const int kIntermediateStride = kMaxSuperBlockSizeInPixels;
  // Account for the 0-taps that precede the 2 nonzero taps.
  const int kernel_offset = 3;
  const int ref_x = subpixel_x >> kScaleSubPixelBits;
  const int step_x8 = step_x << 3;
  const uint8x16_t filter_taps0 = GetPositive2TapFilter(0);
  const uint8x16_t filter_taps1 = GetPositive2TapFilter(1);
  const uint16x8_t sum = vdupq_n_u16(1 << (kBitdepth8 + kFilterBits - 1));
  uint16x8_t index_steps = vmulq_n_u16(vmovl_u8(vcreate_u8(0x0706050403020100)),
                                       static_cast<uint16_t>(step_x));

  const uint8x8_t filter_index_mask = vdup_n_u8(kSubPixelMask);
  for (int x = 0, p = subpixel_x; x < width; x += 8, p += step_x8) {
    const uint8_t* src_x =
        &src[(p >> kScaleSubPixelBits) - ref_x + kernel_offset];
    int16_t* intermediate_x = intermediate + x;
    // Only add steps to the 10-bit truncated p to avoid overflow.
    const uint16x8_t p_fraction = vdupq_n_u16(p & 1023);
    const uint16x8_t subpel_index_offsets = vaddq_u16(index_steps, p_fraction);
    const uint8x8_t filter_indices =
        vand_u8(vshrn_n_u16(subpel_index_offsets, 6), filter_index_mask);
    // This is a special case. The 2-tap filter has no negative taps, so we
    // can use unsigned values.
    // For each x, a lane of tapsK has
    // kSubPixelFilters[filter_index][filter_id][k], where filter_id depends
    // on x.
    const uint8x8_t taps0 = VQTbl1U8(filter_taps0, filter_indices);
    const uint8x8_t taps1 = VQTbl1U8(filter_taps1, filter_indices);
    for (int y = 0; y < intermediate_height; ++y) {
      // Load a pool of samples to select from using stepped indices.
      uint8x16_t src_vals = vld1q_u8(src_x);
      const uint8x8_t src_indices =
          vmovn_u16(vshrq_n_u16(subpel_index_offsets, kScaleSubPixelBits));

      // For each x, a lane of srcK contains src_x[k].
      const uint8x8_t src0 = VQTbl1U8(src_vals, src_indices);
      const uint8x8_t src1 =
          VQTbl1U8(src_vals, vadd_u8(src_indices, vdup_n_u8(1)));

      const uint16x8_t product0 = vmlal_u8(sum, taps0, src0);
      // product0 + product1
      const uint16x8_t result = vmlal_u8(product0, taps1, src1);

      vst1q_s16(intermediate_x, vreinterpretq_s16_u16(vrshrq_n_u16(result, 3)));
      src_x += src_stride;
      intermediate_x += kIntermediateStride;
    }
  }
}

inline uint8x16_t GetPositive4TapFilter(const int tap_index) {
  assert(tap_index < 4);
  constexpr uint8_t kSubPixel4TapPositiveFilterColumns[4][16] = {
      {0, 30, 26, 22, 20, 18, 16, 14, 12, 12, 10, 8, 6, 4, 4, 2},
      {128, 62, 62, 62, 60, 58, 56, 54, 52, 48, 46, 44, 42, 40, 36, 34},
      {0, 34, 36, 40, 42, 44, 46, 48, 52, 54, 56, 58, 60, 62, 62, 62},
      {0, 2, 4, 4, 6, 8, 10, 12, 12, 14, 16, 18, 20, 22, 26, 30}};

  uint8x16_t filter_taps =
      vld1q_u8(kSubPixel4TapPositiveFilterColumns[tap_index]);
  return filter_taps;
}

// This filter is only possible when width <= 4.
inline void ConvolveKernelHorizontalPositive4Tap(
    const uint8_t* src, const ptrdiff_t src_stride, const int subpixel_x,
    const int step_x, const int intermediate_height, int16_t* intermediate) {
  const int kernel_offset = 2;
  const int ref_x = subpixel_x >> kScaleSubPixelBits;
  const uint8x8_t filter_index_mask = vdup_n_u8(kSubPixelMask);
  const uint8x16_t filter_taps0 = GetPositive4TapFilter(0);
  const uint8x16_t filter_taps1 = GetPositive4TapFilter(1);
  const uint8x16_t filter_taps2 = GetPositive4TapFilter(2);
  const uint8x16_t filter_taps3 = GetPositive4TapFilter(3);
  uint16x8_t index_steps = vmulq_n_u16(vmovl_u8(vcreate_u8(0x0706050403020100)),
                                       static_cast<uint16_t>(step_x));
  int p = subpixel_x;
  const uint16x8_t base = vdupq_n_u16(1 << (kBitdepth8 + kFilterBits - 1));
  // First filter is special, just a 128 tap on the center.
  const uint8_t* src_x =
      &src[(p >> kScaleSubPixelBits) - ref_x + kernel_offset];
  // Only add steps to the 10-bit truncated p to avoid overflow.
  const uint16x8_t p_fraction = vdupq_n_u16(p & 1023);
  const uint16x8_t subpel_index_offsets = vaddq_u16(index_steps, p_fraction);
  const uint8x8_t filter_indices =
      vand_u8(vshrn_n_u16(subpel_index_offsets, 6), filter_index_mask);
  // Note that filter_id depends on x.
  // For each x, tapsK has kSubPixelFilters[filter_index][filter_id][k].
  const uint8x8_t taps0 = VQTbl1U8(filter_taps0, filter_indices);
  const uint8x8_t taps1 = VQTbl1U8(filter_taps1, filter_indices);
  const uint8x8_t taps2 = VQTbl1U8(filter_taps2, filter_indices);
  const uint8x8_t taps3 = VQTbl1U8(filter_taps3, filter_indices);

  const uint8x8_t src_indices =
      vmovn_u16(vshrq_n_u16(subpel_index_offsets, kScaleSubPixelBits));
  for (int y = 0; y < intermediate_height; ++y) {
    // Load a pool of samples to select from using stepped index vectors.
    uint8x16_t src_vals = vld1q_u8(src_x);

    // For each x, srcK contains src_x[k] where k=1.
    // Whereas taps come from different arrays, src pixels are drawn from the
    // same contiguous line.
    const uint8x8_t src0 = VQTbl1U8(src_vals, src_indices);
    const uint8x8_t src1 =
        VQTbl1U8(src_vals, vadd_u8(src_indices, vdup_n_u8(1)));
    const uint8x8_t src2 =
        VQTbl1U8(src_vals, vadd_u8(src_indices, vdup_n_u8(2)));
    const uint8x8_t src3 =
        VQTbl1U8(src_vals, vadd_u8(src_indices, vdup_n_u8(3)));

    uint16x8_t sum = vmlal_u8(base, taps0, src0);
    sum = vmlal_u8(sum, taps1, src1);
    sum = vmlal_u8(sum, taps2, src2);
    sum = vmlal_u8(sum, taps3, src3);

    vst1_s16(intermediate,
             vreinterpret_s16_u16(vrshr_n_u16(vget_low_u16(sum), 3)));

    src_x += src_stride;
    intermediate += kIntermediateStride;
  }
}

inline uint8x16_t GetSigned4TapFilter(const int tap_index) {
  assert(tap_index < 4);
  // The first and fourth taps of each filter are negative. However
  // 128 does not fit in an 8-bit signed integer. Thus we use subtraction to
  // keep everything unsigned.
  constexpr uint8_t kSubPixel4TapSignedFilterColumns[4][16] = {
      {0, 4, 8, 10, 12, 12, 14, 12, 12, 10, 10, 10, 8, 6, 4, 2},
      {128, 126, 122, 116, 110, 102, 94, 84, 76, 66, 58, 48, 38, 28, 18, 8},
      {0, 8, 18, 28, 38, 48, 58, 66, 76, 84, 94, 102, 110, 116, 122, 126},
      {0, 2, 4, 6, 8, 10, 10, 10, 12, 12, 14, 12, 12, 10, 8, 4}};

  uint8x16_t filter_taps =
      vld1q_u8(kSubPixel4TapSignedFilterColumns[tap_index]);
  return filter_taps;
}

// This filter is only possible when width <= 4.
inline void ConvolveKernelHorizontalSigned4Tap(
    const uint8_t* src, const ptrdiff_t src_stride, const int subpixel_x,
    const int step_x, const int intermediate_height, int16_t* intermediate) {
  const int kernel_offset = 2;
  const int ref_x = subpixel_x >> kScaleSubPixelBits;
  const uint8x8_t filter_index_mask = vdup_n_u8(kSubPixelMask);
  const uint8x16_t filter_taps0 = GetSigned4TapFilter(0);
  const uint8x16_t filter_taps1 = GetSigned4TapFilter(1);
  const uint8x16_t filter_taps2 = GetSigned4TapFilter(2);
  const uint8x16_t filter_taps3 = GetSigned4TapFilter(3);
  const uint16x8_t index_steps = vmulq_n_u16(vmovl_u8(vcreate_u8(0x03020100)),
                                             static_cast<uint16_t>(step_x));

  const uint16x8_t base = vdupq_n_u16(1 << (kBitdepth8 + kFilterBits - 1));
  int p = subpixel_x;
  const uint8_t* src_x =
      &src[(p >> kScaleSubPixelBits) - ref_x + kernel_offset];
  // Only add steps to the 10-bit truncated p to avoid overflow.
  const uint16x8_t p_fraction = vdupq_n_u16(p & 1023);
  const uint16x8_t subpel_index_offsets = vaddq_u16(index_steps, p_fraction);
  const uint8x8_t filter_indices =
      vand_u8(vshrn_n_u16(subpel_index_offsets, 6), filter_index_mask);
  // Note that filter_id depends on x.
  // For each x, tapsK has kSubPixelFilters[filter_index][filter_id][k].
  const uint8x8_t taps0 = VQTbl1U8(filter_taps0, filter_indices);
  const uint8x8_t taps1 = VQTbl1U8(filter_taps1, filter_indices);
  const uint8x8_t taps2 = VQTbl1U8(filter_taps2, filter_indices);
  const uint8x8_t taps3 = VQTbl1U8(filter_taps3, filter_indices);
  for (int y = 0; y < intermediate_height; ++y) {
    // Load a pool of samples to select from using stepped indices.
    uint8x16_t src_vals = vld1q_u8(src_x);
    const uint8x8_t src_indices =
        vmovn_u16(vshrq_n_u16(subpel_index_offsets, kScaleSubPixelBits));

    // For each x, srcK contains src_x[k] where k=1.
    // Whereas taps come from different arrays, src pixels are drawn from the
    // same contiguous line.
    const uint8x8_t src0 = VQTbl1U8(src_vals, src_indices);
    const uint8x8_t src1 =
        VQTbl1U8(src_vals, vadd_u8(src_indices, vdup_n_u8(1)));
    const uint8x8_t src2 =
        VQTbl1U8(src_vals, vadd_u8(src_indices, vdup_n_u8(2)));
    const uint8x8_t src3 =
        VQTbl1U8(src_vals, vadd_u8(src_indices, vdup_n_u8(3)));

    // Offsetting by base permits a guaranteed positive.
    uint16x8_t sum = vmlsl_u8(base, taps0, src0);
    sum = vmlal_u8(sum, taps1, src1);
    sum = vmlal_u8(sum, taps2, src2);
    sum = vmlsl_u8(sum, taps3, src3);

    vst1_s16(intermediate,
             vreinterpret_s16_u16(vrshr_n_u16(vget_low_u16(sum), 3)));
    src_x += src_stride;
    intermediate += kIntermediateStride;
  }
}

void ConvolveCompoundScale2D_NEON(
    const void* const reference, const ptrdiff_t reference_stride,
    const int horizontal_filter_index, const int vertical_filter_index,
    const int inter_round_bits_vertical, const int subpixel_x,
    const int subpixel_y, const int step_x, const int step_y, const int width,
    const int height, void* prediction, const ptrdiff_t pred_stride) {
  const int intermediate_height =
      (((height - 1) * step_y + (1 << kScaleSubPixelBits) - 1) >>
       kScaleSubPixelBits) +
      kSubPixelTaps;
  // TODO(b/133525024): Decide whether it's worth branching to a special case
  // when step_x or step_y is 1024.
  assert(step_x <= 2048);
  // The output of the horizontal filter, i.e. the intermediate_result, is
  // guaranteed to fit in int16_t.
  int16_t intermediate_result[kMaxSuperBlockSizeInPixels *
                              (2 * kMaxSuperBlockSizeInPixels + 8)];

  // Horizontal filter.
  // Filter types used for width <= 4 are different from those for width > 4.
  // When width > 4, the valid filter index range is always [0, 3].
  // When width <= 4, the valid filter index range is always [3, 5].
  // Similarly for height.
  const int kIntermediateStride = kMaxSuperBlockSizeInPixels;
  int filter_index = GetFilterIndex(horizontal_filter_index, width);
  int16_t* intermediate = intermediate_result;
  const auto* src = static_cast<const uint8_t*>(reference);
  const ptrdiff_t src_stride = reference_stride;
  auto* dest = static_cast<uint16_t*>(prediction);
  switch (filter_index) {
    case 0:
      if (step_x < 1024) {
        ConvolveHorizontalScaled_NEON<0, 6, 1>(
            src, src_stride, width, subpixel_x, step_x, intermediate_height,
            intermediate);
      } else {
        ConvolveHorizontalScaled_NEON<0, 6, 2>(
            src, src_stride, width, subpixel_x, step_x, intermediate_height,
            intermediate);
      }
      break;
    case 1:
      if (step_x < 1024) {
        ConvolveHorizontalScaled_NEON<1, 6, 1>(
            src, src_stride, width, subpixel_x, step_x, intermediate_height,
            intermediate);
      } else {
        ConvolveHorizontalScaled_NEON<1, 6, 2>(
            src, src_stride, width, subpixel_x, step_x, intermediate_height,
            intermediate);
      }
      break;
    case 2:
      if (step_x <= 1024) {
        ConvolveHorizontalScaled_NEON<2, 8, 1>(
            src, src_stride, width, subpixel_x, step_x, intermediate_height,
            intermediate);
      } else {
        ConvolveHorizontalScaled_NEON<2, 8, 2>(
            src, src_stride, width, subpixel_x, step_x, intermediate_height,
            intermediate);
      }
      break;
    case 3:
      ConvolveKernelHorizontal2Tap(src, src_stride, width, subpixel_x, step_x,
                                   intermediate_height, intermediate);
      break;
    case 4:
      assert(width <= 4);
      ConvolveKernelHorizontalSigned4Tap(src, src_stride, subpixel_x, step_x,
                                         intermediate_height, intermediate);
      break;
    default:
      assert(filter_index == 5);
      ConvolveKernelHorizontalPositive4Tap(src, src_stride, subpixel_x, step_x,
                                           intermediate_height, intermediate);
  }
  // Vertical filter.
  filter_index = GetFilterIndex(vertical_filter_index, height);
  intermediate = intermediate_result;
  const int offset_bits = kBitdepth8 + 2 * kFilterBits - 3;
  for (int y = 0, p = subpixel_y & 1023; y < height; ++y, p += step_y) {
    const int filter_id = (p >> 6) & kSubPixelMask;
    for (int x = 0; x < width; ++x) {
      // An offset to guarantee the sum is non negative.
      int sum = 1 << offset_bits;
      for (int k = 0; k < kSubPixelTaps; ++k) {
        sum +=
            kSubPixelFilters[filter_index][filter_id][k] *
            intermediate[((p >> kScaleSubPixelBits) + k) * kIntermediateStride +
                         x];
      }
      assert(sum >= 0 && sum < (1 << (offset_bits + 2)));
      dest[x] = static_cast<uint16_t>(
          RightShiftWithRounding(sum, inter_round_bits_vertical));
    }
    dest += pred_stride;
  }
}

void ConvolveHorizontal_NEON(const void* const reference,
                             const ptrdiff_t reference_stride,
                             const int horizontal_filter_index,
                             const int /*vertical_filter_index*/,
                             const int /*inter_round_bits_vertical*/,
                             const int subpixel_x, const int /*subpixel_y*/,
                             const int /*step_x*/, const int /*step_y*/,
                             const int width, const int height,
                             void* prediction, const ptrdiff_t pred_stride) {
  // For 8 (and 10) bit calculations |inter_round_bits_horizontal| is 3.
  const int filter_index = GetFilterIndex(horizontal_filter_index, width);
  // Set |src| to the outermost tap.
  const auto* src = static_cast<const uint8_t*>(reference) - kHorizontalOffset;
  auto* dest = static_cast<uint8_t*>(prediction);

  HorizontalPass<false, true>(src, reference_stride, dest, pred_stride, width,
                              height, subpixel_x, filter_index);
}

template <int min_width, int num_taps>
void FilterVertical(const uint8_t* src, const ptrdiff_t src_stride,
                    uint8_t* dst, const ptrdiff_t dst_stride, const int width,
                    const int height, const int16x8_t taps) {
  constexpr int next_row = num_taps - 1;
  // |src| points to the outermost tap of the first value. When doing fewer than
  // 8 taps it needs to be adjusted.
  if (num_taps == 6) {
    src += src_stride;
  } else if (num_taps == 4) {
    src += 2 * src_stride;
  } else if (num_taps == 2) {
    src += 3 * src_stride;
  }

  int x = 0;
  do {
    int16x8_t srcs[8];
    srcs[0] = ZeroExtend(vld1_u8(src + x));
    if (num_taps >= 4) {
      srcs[1] = ZeroExtend(vld1_u8(src + x + src_stride));
      srcs[2] = ZeroExtend(vld1_u8(src + x + 2 * src_stride));
      if (num_taps >= 6) {
        srcs[3] = ZeroExtend(vld1_u8(src + x + 3 * src_stride));
        srcs[4] = ZeroExtend(vld1_u8(src + x + 4 * src_stride));
        if (num_taps == 8) {
          srcs[5] = ZeroExtend(vld1_u8(src + x + 5 * src_stride));
          srcs[6] = ZeroExtend(vld1_u8(src + x + 6 * src_stride));
        }
      }
    }

    int y = 0;
    do {
      srcs[next_row] =
          ZeroExtend(vld1_u8(src + x + (y + next_row) * src_stride));

      const int16x8_t sums = SumTaps<num_taps>(srcs, taps);
      const uint8x8_t results = vqrshrun_n_s16(sums, kFilterBits);

      if (min_width == 4) {
        StoreLo4(dst + x + y * dst_stride, results);
      } else {
        vst1_u8(dst + x + y * dst_stride, results);
      }

      srcs[0] = srcs[1];
      if (num_taps >= 4) {
        srcs[1] = srcs[2];
        srcs[2] = srcs[3];
        if (num_taps >= 6) {
          srcs[3] = srcs[4];
          srcs[4] = srcs[5];
          if (num_taps == 8) {
            srcs[5] = srcs[6];
            srcs[6] = srcs[7];
          }
        }
      }
    } while (++y < height);
    x += 8;
  } while (x < width);
}

// This function is a simplified version of Convolve2D_C.
// It is called when it is single prediction mode, where only vertical
// filtering is required.
// The output is the single prediction of the block, clipped to valid pixel
// range.
void ConvolveVertical_NEON(const void* const reference,
                           const ptrdiff_t reference_stride,
                           const int /*horizontal_filter_index*/,
                           const int vertical_filter_index,
                           const int /*inter_round_bits_vertical*/,
                           const int /*subpixel_x*/, const int subpixel_y,
                           const int /*step_x*/, const int /*step_y*/,
                           const int width, const int height, void* prediction,
                           const ptrdiff_t pred_stride) {
  const int filter_index = GetFilterIndex(vertical_filter_index, height);
  const ptrdiff_t src_stride = reference_stride;
  const auto* src =
      static_cast<const uint8_t*>(reference) - kVerticalOffset * src_stride;
  auto* dest = static_cast<uint8_t*>(prediction);
  const ptrdiff_t dest_stride = pred_stride;
  const int filter_id = (subpixel_y >> 6) & kSubPixelMask;
  // First filter is always a copy.
  if (filter_id == 0) {
    // Move |src| down the actual values and not the start of the context.
    src = static_cast<const uint8_t*>(reference);
    int y = 0;
    do {
      memcpy(dest, src, width * sizeof(src[0]));
      src += src_stride;
      dest += dest_stride;
    } while (++y < height);
    return;
  }

  // Break up by # of taps
  // |filter_index| taps  enum InterpolationFilter
  //        0       6     kInterpolationFilterEightTap
  //        1       6     kInterpolationFilterEightTapSmooth
  //        2       8     kInterpolationFilterEightTapSharp
  //        3       2     kInterpolationFilterBilinear
  //        4       4     kInterpolationFilterSwitchable
  //        5       4     !!! SECRET FILTER !!! only for Wx4.
  if (width >= 4) {
    if (filter_index == 2) {  // 8 tap.
      const int16x8_t taps =
          vld1q_s16(kSubPixelFilters[filter_index][filter_id]);
      if (width == 4) {
        FilterVertical<4, 8>(src, src_stride, dest, dest_stride, width, height,
                             taps);
      } else {
        FilterVertical<8, 8>(src, src_stride, dest, dest_stride, width, height,
                             taps);
      }
    } else if (filter_index < 2) {  // 6 tap.
      const int16x8_t taps =
          vld1q_s16(kSubPixelFilters[filter_index][filter_id]);
      if (width == 4) {
        FilterVertical<4, 6>(src, src_stride, dest, dest_stride, width, height,
                             taps);
      } else {
        FilterVertical<8, 6>(src, src_stride, dest, dest_stride, width, height,
                             taps);
      }
    } else if (filter_index > 3) {  // 4 tap.
      // Store taps in vget_low_s16(taps).
      const int16x8_t taps =
          vld1q_s16(kSubPixelFilters[filter_index][filter_id] + 2);
      if (width == 4) {
        FilterVertical<4, 4>(src, src_stride, dest, dest_stride, width, height,
                             taps);
      } else {
        FilterVertical<8, 4>(src, src_stride, dest, dest_stride, width, height,
                             taps);
      }
    } else {  // 2 tap.
      // Store taps in vget_low_s16(taps).
      const int16x8_t taps =
          vld1q_s16(kSubPixelFilters[filter_index][filter_id] + 2);
      if (width == 4) {
        FilterVertical<4, 2>(src, src_stride, dest, dest_stride, width, height,
                             taps);
      } else {
        FilterVertical<8, 2>(src, src_stride, dest, dest_stride, width, height,
                             taps);
      }
    }
  } else {
    assert(width == 2);
    const int taps = NumTapsInFilter(filter_index);
    src =
        static_cast<const uint8_t*>(reference) - ((taps / 2) - 1) * src_stride;
    VerticalPass2xH</*is_2d=*/false>(src, src_stride, dest, pred_stride, height,
                                     0, filter_index, taps, subpixel_y);
  }
}

void ConvolveCompoundCopy_NEON(
    const void* const reference, const ptrdiff_t reference_stride,
    const int /*horizontal_filter_index*/, const int /*vertical_filter_index*/,
    const int /*inter_round_bits_vertical*/, const int /*subpixel_x*/,
    const int /*subpixel_y*/, const int /*step_x*/, const int /*step_y*/,
    const int width, const int height, void* prediction,
    const ptrdiff_t pred_stride) {
  const auto* src = static_cast<const uint8_t*>(reference);
  const ptrdiff_t src_stride = reference_stride;
  auto* dest = static_cast<uint16_t*>(prediction);
  const int bitdepth = 8;
  const int compound_round_offset =
      (1 << (bitdepth + 4)) + (1 << (bitdepth + 3));
  const uint16x8_t v_compound_round_offset = vdupq_n_u16(compound_round_offset);

  if (width >= 16) {
    int y = 0;
    do {
      int x = 0;
      do {
        const uint8x16_t v_src = vld1q_u8(&src[x]);
        const uint16x8_t v_src_x16_lo = vshll_n_u8(vget_low_u8(v_src), 4);
        const uint16x8_t v_src_x16_hi = vshll_n_u8(vget_high_u8(v_src), 4);
        const uint16x8_t v_dest_lo =
            vaddq_u16(v_src_x16_lo, v_compound_round_offset);
        const uint16x8_t v_dest_hi =
            vaddq_u16(v_src_x16_hi, v_compound_round_offset);
        vst1q_u16(&dest[x], v_dest_lo);
        x += 8;
        vst1q_u16(&dest[x], v_dest_hi);
        x += 8;
      } while (x < width);
      src += src_stride;
      dest += pred_stride;
    } while (++y < height);
  } else if (width == 8) {
    int y = 0;
    do {
      const uint8x8_t v_src = vld1_u8(&src[0]);
      const uint16x8_t v_src_x16 = vshll_n_u8(v_src, 4);
      vst1q_u16(&dest[0], vaddq_u16(v_src_x16, v_compound_round_offset));
      src += src_stride;
      dest += pred_stride;
    } while (++y < height);
  } else if (width == 4) {
    const uint8x8_t zero = vdup_n_u8(0);
    int y = 0;
    do {
      const uint8x8_t v_src = LoadLo4(&src[0], zero);
      const uint16x8_t v_src_x16 = vshll_n_u8(v_src, 4);
      const uint16x8_t v_dest = vaddq_u16(v_src_x16, v_compound_round_offset);
      vst1_u16(&dest[0], vget_low_u16(v_dest));
      src += src_stride;
      dest += pred_stride;
    } while (++y < height);
  } else {  // width == 2
    assert(width == 2);
    int y = 0;
    do {
      dest[0] = (src[0] << 4) + compound_round_offset;
      dest[1] = (src[1] << 4) + compound_round_offset;
      src += src_stride;
      dest += pred_stride;
    } while (++y < height);
  }
}

// Input 8 bits and output 16 bits.
template <int min_width, int num_taps>
void FilterCompoundVertical(const uint8_t* src, const ptrdiff_t src_stride,
                            uint16_t* dst, const ptrdiff_t dst_stride,
                            const int width, const int height,
                            const int16x8_t taps) {
  constexpr int next_row = num_taps - 1;
  // |src| points to the outermost tap of the first value. When doing fewer than
  // 8 taps it needs to be adjusted.
  if (num_taps == 6) {
    src += src_stride;
  } else if (num_taps == 4) {
    src += 2 * src_stride;
  } else if (num_taps == 2) {
    src += 3 * src_stride;
  }

  const uint16x8_t compound_round_offset = vdupq_n_u16(1 << 12);

  int x = 0;
  do {
    int16x8_t srcs[8];
    srcs[0] = ZeroExtend(vld1_u8(src + x));
    if (num_taps >= 4) {
      srcs[1] = ZeroExtend(vld1_u8(src + x + src_stride));
      srcs[2] = ZeroExtend(vld1_u8(src + x + 2 * src_stride));
      if (num_taps >= 6) {
        srcs[3] = ZeroExtend(vld1_u8(src + x + 3 * src_stride));
        srcs[4] = ZeroExtend(vld1_u8(src + x + 4 * src_stride));
        if (num_taps == 8) {
          srcs[5] = ZeroExtend(vld1_u8(src + x + 5 * src_stride));
          srcs[6] = ZeroExtend(vld1_u8(src + x + 6 * src_stride));
        }
      }
    }

    int y = 0;
    do {
      srcs[next_row] =
          ZeroExtend(vld1_u8(src + x + (y + next_row) * src_stride));

      const uint16x8_t sums = SumTaps8To16<num_taps>(srcs, taps);
      const uint16x8_t shifted = vrshrq_n_u16(sums, 3);
      // In order to keep the sum in 16 bits we add an offset to the sum
      // (1 << (bitdepth + kFilterBits - 1) == 1 << 14). This ensures that the
      // results will never be negative.
      // Normally ConvolveCompoundVertical would add |compound_round_offset| at
      // the end. Instead we use that to compensate for the initial offset.
      // (1 << (bitdepth + 4)) + (1 << (bitdepth + 3)) == (1 << 12) + (1 << 11)
      // After taking into account the shift above:
      // RightShiftWithRounding(LeftShift(sum, bits_shift),
      //                        inter_round_bits_vertical)
      // where bits_shift == kFilterBits - kInterRoundBitsHorizontal == 4
      // and inter_round_bits_vertical == 7
      // and simplifying it to RightShiftWithRounding(sum, 3)
      // we see that the initial offset of 1 << 14 >> 3 == 1 << 11 and
      // |compound_round_offset| can be simplified to 1 << 12.
      const uint16x8_t offset = vaddq_u16(shifted, compound_round_offset);

      if (min_width == 4) {
        vst1_u16(dst + x + y * dst_stride, vget_low_u16(offset));
      } else {
        vst1q_u16(dst + x + y * dst_stride, offset);
      }

      srcs[0] = srcs[1];
      if (num_taps >= 4) {
        srcs[1] = srcs[2];
        srcs[2] = srcs[3];
        if (num_taps >= 6) {
          srcs[3] = srcs[4];
          srcs[4] = srcs[5];
          if (num_taps == 8) {
            srcs[5] = srcs[6];
            srcs[6] = srcs[7];
          }
        }
      }
    } while (++y < height);
    x += 8;
  } while (x < width);
}

void ConvolveCompoundVertical_NEON(
    const void* const reference, const ptrdiff_t reference_stride,
    const int /*horizontal_filter_index*/, const int vertical_filter_index,
    const int /*inter_round_bits_vertical*/, const int /*subpixel_x*/,
    const int subpixel_y, const int /*step_x*/, const int /*step_y*/,
    const int width, const int height, void* prediction,
    const ptrdiff_t pred_stride) {
  const int filter_index = GetFilterIndex(vertical_filter_index, height);
  const ptrdiff_t src_stride = reference_stride;
  const auto* src =
      static_cast<const uint8_t*>(reference) - kVerticalOffset * src_stride;
  auto* dest = static_cast<uint16_t*>(prediction);
  const int filter_id = (subpixel_y >> 6) & kSubPixelMask;

  if (width >= 4) {
    const int16x8_t taps = vld1q_s16(kSubPixelFilters[filter_index][filter_id]);

    if (filter_index == 2) {  // 8 tap.
      if (width == 4) {
        FilterCompoundVertical<4, 8>(src, src_stride, dest, pred_stride, width,
                                     height, taps);
      } else {
        FilterCompoundVertical<8, 8>(src, src_stride, dest, pred_stride, width,
                                     height, taps);
      }
    } else if (filter_index < 2) {  // 6 tap.
      if (width == 4) {
        FilterCompoundVertical<4, 6>(src, src_stride, dest, pred_stride, width,
                                     height, taps);
      } else {
        FilterCompoundVertical<8, 6>(src, src_stride, dest, pred_stride, width,
                                     height, taps);
      }
    } else if (filter_index == 3) {  // 2 tap.
      if (width == 4) {
        FilterCompoundVertical<4, 2>(src, src_stride, dest, pred_stride, width,
                                     height, taps);
      } else {
        FilterCompoundVertical<8, 2>(src, src_stride, dest, pred_stride, width,
                                     height, taps);
      }
    } else if (filter_index > 3) {  // 4 tap.
      if (width == 4) {
        FilterCompoundVertical<4, 4>(src, src_stride, dest, pred_stride, width,
                                     height, taps);
      } else {
        FilterCompoundVertical<8, 4>(src, src_stride, dest, pred_stride, width,
                                     height, taps);
      }
    }
  } else {
    assert(width == 2);
    const int taps = NumTapsInFilter(filter_index);
    src =
        static_cast<const uint8_t*>(reference) - ((taps / 2) - 1) * src_stride;
    VerticalPass2xH</*is_2d=*/false, /*is_compound=*/true>(
        src, src_stride, dest, pred_stride, height, 0, filter_index, taps,
        subpixel_y);
  }
}

void ConvolveCompoundHorizontal_NEON(
    const void* const reference, const ptrdiff_t reference_stride,
    const int horizontal_filter_index, const int /*vertical_filter_index*/,
    const int /*inter_round_bits_vertical*/, const int subpixel_x,
    const int /*subpixel_y*/, const int /*step_x*/, const int /*step_y*/,
    const int width, const int height, void* prediction,
    const ptrdiff_t pred_stride) {
  const int filter_index = GetFilterIndex(horizontal_filter_index, width);
  const auto* src = static_cast<const uint8_t*>(reference) - kHorizontalOffset;
  auto* dest = static_cast<uint16_t*>(prediction);

  HorizontalPass(src, reference_stride, dest, pred_stride, width, height,
                 subpixel_x, filter_index);
}

void ConvolveCompound2D_NEON(const void* const reference,
                             const ptrdiff_t reference_stride,
                             const int horizontal_filter_index,
                             const int vertical_filter_index,
                             const int inter_round_bits_vertical,
                             const int subpixel_x, const int subpixel_y,
                             const int /*step_x*/, const int /*step_y*/,
                             const int width, const int height,
                             void* prediction, const ptrdiff_t pred_stride) {
  // The output of the horizontal filter, i.e. the intermediate_result, is
  // guaranteed to fit in int16_t.
  uint16_t
      intermediate_result[kMaxSuperBlockSizeInPixels *
                          (kMaxSuperBlockSizeInPixels + kSubPixelTaps - 1)];
  const int intermediate_stride = kMaxSuperBlockSizeInPixels;

  // Horizontal filter.
  // Filter types used for width <= 4 are different from those for width > 4.
  // When width > 4, the valid filter index range is always [0, 3].
  // When width <= 4, the valid filter index range is always [4, 5].
  // Similarly for height.
  const int horiz_filter_index = GetFilterIndex(horizontal_filter_index, width);
  const int vert_filter_index = GetFilterIndex(vertical_filter_index, height);
  const int horizontal_taps = NumTapsInFilter(horiz_filter_index);
  const int vertical_taps = NumTapsInFilter(vert_filter_index);
  uint16_t* intermediate = intermediate_result;
  const int intermediate_height = height + vertical_taps - 1;
  const ptrdiff_t src_stride = reference_stride;
  const auto* src = static_cast<const uint8_t*>(reference) -
                    kVerticalOffset * src_stride - kHorizontalOffset;
  auto* dest = static_cast<uint16_t*>(prediction);
  int filter_id = (subpixel_x >> 6) & kSubPixelMask;

  if (width >= 4) {
    // TODO(johannkoenig): Use |width| for |intermediate_stride|.
    src = static_cast<const uint8_t*>(reference) -
          (vertical_taps / 2 - 1) * src_stride - kHorizontalOffset;
    HorizontalPass<true>(src, src_stride, intermediate_result,
                         intermediate_stride, width, intermediate_height,
                         subpixel_x, horiz_filter_index);

    // Vertical filter.
    intermediate = intermediate_result;
    filter_id = ((subpixel_y & 1023) >> 6) & kSubPixelMask;

    const ptrdiff_t dest_stride = pred_stride;
    const int16x8_t taps =
        vld1q_s16(kSubPixelFilters[vert_filter_index][filter_id]);

    if (vertical_taps == 8) {
      Filter2DVertical<8, /*is_compound=*/true>(
          intermediate, intermediate_stride, dest, dest_stride, width, height,
          taps, inter_round_bits_vertical);
    } else if (vertical_taps == 6) {
      Filter2DVertical<6, /*is_compound=*/true>(
          intermediate, intermediate_stride, dest, dest_stride, width, height,
          taps, inter_round_bits_vertical);
    } else if (vertical_taps == 4) {
      Filter2DVertical<4, /*is_compound=*/true>(
          intermediate, intermediate_stride, dest, dest_stride, width, height,
          taps, inter_round_bits_vertical);
    } else {  // |vertical_taps| == 2
      Filter2DVertical<2, /*is_compound=*/true>(
          intermediate, intermediate_stride, dest, dest_stride, width, height,
          taps, inter_round_bits_vertical);
    }
  } else {
    src = static_cast<const uint8_t*>(reference) -
          ((vertical_taps / 2) - 1) * src_stride - ((horizontal_taps / 2) - 1);

    HorizontalPass2xH(src, src_stride, intermediate_result, intermediate_stride,
                      intermediate_height, horiz_filter_index, horizontal_taps,
                      subpixel_x);

    VerticalPass2xH</*is_2d=*/true, /*is_compound=*/true>(
        intermediate_result, intermediate_stride, dest, pred_stride, height,
        inter_round_bits_vertical, vert_filter_index, vertical_taps,
        subpixel_y);
  }
}

void Init8bpp() {
  Dsp* const dsp = dsp_internal::GetWritableDspTable(8);
  assert(dsp != nullptr);
  dsp->convolve[0][0][0][1] = ConvolveHorizontal_NEON;
  dsp->convolve[0][0][1][0] = ConvolveVertical_NEON;
  dsp->convolve[0][0][1][1] = Convolve2D_NEON;

  dsp->convolve[0][1][0][0] = ConvolveCompoundCopy_NEON;
  dsp->convolve[0][1][0][1] = ConvolveCompoundHorizontal_NEON;
  dsp->convolve[0][1][1][0] = ConvolveCompoundVertical_NEON;
  dsp->convolve[0][1][1][1] = ConvolveCompound2D_NEON;

  // TODO(petersonab,b/139707209): Fix source buffer overreads.
  // dsp->convolve_scale[1] = ConvolveCompoundScale2D_NEON;
  static_cast<void>(ConvolveCompoundScale2D_NEON);
}

}  // namespace
}  // namespace low_bitdepth

void ConvolveInit_NEON() { low_bitdepth::Init8bpp(); }

}  // namespace dsp
}  // namespace libgav1

#else   // !LIBGAV1_ENABLE_NEON

namespace libgav1 {
namespace dsp {

void ConvolveInit_NEON() {}

}  // namespace dsp
}  // namespace libgav1
#endif  // LIBGAV1_ENABLE_NEON
