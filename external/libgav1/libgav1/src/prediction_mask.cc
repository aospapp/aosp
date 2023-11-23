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

#include "src/prediction_mask.h"

#include <algorithm>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>

#include "src/utils/array_2d.h"
#include "src/utils/common.h"
#include "src/utils/constants.h"
#include "src/utils/logging.h"
#include "src/utils/memory.h"

namespace libgav1 {
namespace {

constexpr int kWedgeDirectionTypes = 16;

enum kWedgeDirection : uint8_t {
  kWedgeHorizontal,
  kWedgeVertical,
  kWedgeOblique27,
  kWedgeOblique63,
  kWedgeOblique117,
  kWedgeOblique153,
};

constexpr uint8_t kWedgeCodebook[3][16][3] = {{{kWedgeOblique27, 4, 4},
                                               {kWedgeOblique63, 4, 4},
                                               {kWedgeOblique117, 4, 4},
                                               {kWedgeOblique153, 4, 4},
                                               {kWedgeHorizontal, 4, 2},
                                               {kWedgeHorizontal, 4, 4},
                                               {kWedgeHorizontal, 4, 6},
                                               {kWedgeVertical, 4, 4},
                                               {kWedgeOblique27, 4, 2},
                                               {kWedgeOblique27, 4, 6},
                                               {kWedgeOblique153, 4, 2},
                                               {kWedgeOblique153, 4, 6},
                                               {kWedgeOblique63, 2, 4},
                                               {kWedgeOblique63, 6, 4},
                                               {kWedgeOblique117, 2, 4},
                                               {kWedgeOblique117, 6, 4}},
                                              {{kWedgeOblique27, 4, 4},
                                               {kWedgeOblique63, 4, 4},
                                               {kWedgeOblique117, 4, 4},
                                               {kWedgeOblique153, 4, 4},
                                               {kWedgeVertical, 2, 4},
                                               {kWedgeVertical, 4, 4},
                                               {kWedgeVertical, 6, 4},
                                               {kWedgeHorizontal, 4, 4},
                                               {kWedgeOblique27, 4, 2},
                                               {kWedgeOblique27, 4, 6},
                                               {kWedgeOblique153, 4, 2},
                                               {kWedgeOblique153, 4, 6},
                                               {kWedgeOblique63, 2, 4},
                                               {kWedgeOblique63, 6, 4},
                                               {kWedgeOblique117, 2, 4},
                                               {kWedgeOblique117, 6, 4}},
                                              {{kWedgeOblique27, 4, 4},
                                               {kWedgeOblique63, 4, 4},
                                               {kWedgeOblique117, 4, 4},
                                               {kWedgeOblique153, 4, 4},
                                               {kWedgeHorizontal, 4, 2},
                                               {kWedgeHorizontal, 4, 6},
                                               {kWedgeVertical, 2, 4},
                                               {kWedgeVertical, 6, 4},
                                               {kWedgeOblique27, 4, 2},
                                               {kWedgeOblique27, 4, 6},
                                               {kWedgeOblique153, 4, 2},
                                               {kWedgeOblique153, 4, 6},
                                               {kWedgeOblique63, 2, 4},
                                               {kWedgeOblique63, 6, 4},
                                               {kWedgeOblique117, 2, 4},
                                               {kWedgeOblique117, 6, 4}}};

constexpr uint8_t kWedgeFlipSignLookup[9][16] = {
    {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 0, 1},  // kBlock8x8
    {1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 1, 1, 1, 0, 1},  // kBlock8x16
    {1, 1, 1, 1, 0, 1, 1, 1, 0, 1, 0, 1, 1, 1, 0, 1},  // kBlock8x32
    {1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 1, 1, 1, 0, 1},  // kBlock16x8
    {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 0, 1},  // kBlock16x16
    {1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 1, 1, 1, 0, 1},  // kBlock16x32
    {1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 1, 0, 1, 0, 1},  // kBlock32x8
    {1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 1, 1, 1, 0, 1},  // kBlock32x16
    {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 0, 1},  // kBlock32x32
};

constexpr uint8_t kWedgeMasterObliqueOdd[kWedgeMaskMasterSize] = {
    0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,
    0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  1,  2,  6,  18,
    37, 53, 60, 63, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
    64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64};

constexpr uint8_t kWedgeMasterObliqueEven[kWedgeMaskMasterSize] = {
    0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,
    0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  1,  4,  11, 27,
    46, 58, 62, 63, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
    64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64};

constexpr uint8_t kWedgeMasterVertical[kWedgeMaskMasterSize] = {
    0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,
    0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  2,  7,  21,
    43, 57, 62, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
    64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64};

constexpr uint8_t kInterIntraWeights[kMaxSuperBlockSizeInPixels] = {
    60, 58, 56, 54, 52, 50, 48, 47, 45, 44, 42, 41, 39, 38, 37, 35, 34, 33, 32,
    31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 22, 21, 20, 19, 19, 18, 18, 17, 16,
    16, 15, 15, 14, 14, 13, 13, 12, 12, 12, 11, 11, 10, 10, 10, 9,  9,  9,  8,
    8,  8,  8,  7,  7,  7,  7,  6,  6,  6,  6,  6,  5,  5,  5,  5,  5,  4,  4,
    4,  4,  4,  4,  4,  4,  3,  3,  3,  3,  3,  3,  3,  3,  3,  2,  2,  2,  2,
    2,  2,  2,  2,  2,  2,  2,  2,  2,  2,  2,  1,  1,  1,  1,  1,  1,  1,  1,
    1,  1,  1,  1,  1,  1,  1,  1,  1,  1,  1,  1,  1,  1};

int BlockShape(BlockSize block_size) {
  const int width = kNum4x4BlocksWide[block_size];
  const int height = kNum4x4BlocksHigh[block_size];
  if (height > width) return 0;
  if (height < width) return 1;
  return 2;
}

uint8_t GetWedgeDirection(BlockSize block_size, int index) {
  return kWedgeCodebook[BlockShape(block_size)][index][0];
}

uint8_t GetWedgeOffsetX(BlockSize block_size, int index) {
  return kWedgeCodebook[BlockShape(block_size)][index][1];
}

uint8_t GetWedgeOffsetY(BlockSize block_size, int index) {
  return kWedgeCodebook[BlockShape(block_size)][index][2];
}

}  // namespace

void GenerateWedgeMask(uint8_t* const wedge_master_mask_data,
                       uint8_t* const wedge_masks_data) {
  // Generate master masks.
  Array2DView<uint8_t> master_mask(6, kMaxMaskBlockSize,
                                   wedge_master_mask_data);
  uint8_t* master_mask_row = master_mask[kWedgeVertical];
  const int stride = kWedgeMaskMasterSize;
  for (int y = 0; y < kWedgeMaskMasterSize; ++y) {
    memcpy(master_mask_row, kWedgeMasterVertical, kWedgeMaskMasterSize);
    master_mask_row += stride;
  }

  AlignedUniquePtr<uint8_t> wedge_master_oblique_even =
      MakeAlignedUniquePtr<uint8_t>(
          16, kWedgeMaskMasterSize + DivideBy2(kWedgeMaskMasterSize));
  AlignedUniquePtr<uint8_t> wedge_master_oblique_odd =
      MakeAlignedUniquePtr<uint8_t>(
          16, kWedgeMaskMasterSize + DivideBy2(kWedgeMaskMasterSize));
  if (wedge_master_oblique_even == nullptr ||
      wedge_master_oblique_odd == nullptr) {
    LIBGAV1_DLOG(ERROR, "Failed to allocate memory for master mask.");
    return;
  }
  const int offset = DivideBy4(kWedgeMaskMasterSize);
  memset(wedge_master_oblique_even.get(), 0, offset);
  memcpy(wedge_master_oblique_even.get() + offset, kWedgeMasterObliqueEven,
         kWedgeMaskMasterSize);
  memset(wedge_master_oblique_even.get() + offset + kWedgeMaskMasterSize, 64,
         offset);
  memset(wedge_master_oblique_odd.get(), 0, offset - 1);
  memcpy(wedge_master_oblique_odd.get() + offset - 1, kWedgeMasterObliqueOdd,
         kWedgeMaskMasterSize);
  memset(wedge_master_oblique_odd.get() + offset + kWedgeMaskMasterSize - 1, 64,
         offset + 1);
  master_mask_row = master_mask[kWedgeOblique63];
  for (int y = 0, shift = 0; y < kWedgeMaskMasterSize; y += 2, ++shift) {
    memcpy(master_mask_row, wedge_master_oblique_even.get() + shift,
           kWedgeMaskMasterSize);
    master_mask_row += stride;
    memcpy(master_mask_row, wedge_master_oblique_odd.get() + shift,
           kWedgeMaskMasterSize);
    master_mask_row += stride;
  }

  uint8_t* const master_mask_horizontal = master_mask[kWedgeHorizontal];
  uint8_t* master_mask_vertical = master_mask[kWedgeVertical];
  uint8_t* const master_mask_oblique_27 = master_mask[kWedgeOblique27];
  uint8_t* master_mask_oblique_63 = master_mask[kWedgeOblique63];
  uint8_t* master_mask_oblique_117 = master_mask[kWedgeOblique117];
  uint8_t* const master_mask_oblique_153 = master_mask[kWedgeOblique153];
  for (int y = 0; y < kWedgeMaskMasterSize; ++y) {
    for (int x = 0; x < kWedgeMaskMasterSize; ++x) {
      const uint8_t mask_value = master_mask_oblique_63[x];
      master_mask_horizontal[x * stride + y] = master_mask_vertical[x];
      master_mask_oblique_27[x * stride + y] = mask_value;
      master_mask_oblique_117[kWedgeMaskMasterSize - 1 - x] = 64 - mask_value;
      master_mask_oblique_153[(kWedgeMaskMasterSize - 1 - x) * stride + y] =
          64 - mask_value;
    }
    master_mask_vertical += stride;
    master_mask_oblique_63 += stride;
    master_mask_oblique_117 += stride;
  }

  // Generate wedge masks.
  const int wedge_mask_stride_1 = kMaxMaskBlockSize;
  const int wedge_mask_stride_2 = wedge_mask_stride_1 * 16;
  const int wedge_mask_stride_3 = wedge_mask_stride_2 * 2;
  int block_size_index = 0;
  int wedge_masks_offset = 0;
  for (int size = kBlock8x8; size <= kBlock32x32; ++size) {
    if (!kIsWedgeCompoundModeAllowed.Contains(size)) continue;

    const int width = kBlockWidthPixels[size];
    const int height = kBlockHeightPixels[size];
    assert(width >= 8);
    assert(width <= 32);
    assert(height >= 8);
    assert(height <= 32);

    const auto block_size = static_cast<BlockSize>(size);
    for (int index = 0; index < kWedgeDirectionTypes; ++index) {
      const uint8_t direction = GetWedgeDirection(block_size, index);
      const uint8_t offset_x =
          DivideBy2(kWedgeMaskMasterSize) -
          ((GetWedgeOffsetX(block_size, index) * width) >> 3);
      const uint8_t offset_y =
          DivideBy2(kWedgeMaskMasterSize) -
          ((GetWedgeOffsetY(block_size, index) * height) >> 3);
      const uint8_t flip_sign = kWedgeFlipSignLookup[block_size_index][index];

      const int offset_1 = block_size_index * wedge_mask_stride_3 +
                           flip_sign * wedge_mask_stride_2 +
                           index * wedge_mask_stride_1;
      const int offset_2 = block_size_index * wedge_mask_stride_3 +
                           (1 - flip_sign) * wedge_mask_stride_2 +
                           index * wedge_mask_stride_1;

      uint8_t* wedge_masks_row = wedge_masks_data + offset_1;
      uint8_t* wedge_masks_row_flip = wedge_masks_data + offset_2;
      master_mask_row = &master_mask[direction][offset_y * stride + offset_x];
      for (int y = 0; y < height; ++y) {
        memcpy(wedge_masks_row, master_mask_row, width);
        for (int x = 0; x < width; ++x) {
          // TODO(chengchen): sign flip may not be needed.
          // Only need to return 64 - mask_value, when get mask.
          wedge_masks_row_flip[x] = 64 - wedge_masks_row[x];
        }
        wedge_masks_row += stride;
        wedge_masks_row_flip += stride;
        master_mask_row += stride;
      }
      wedge_masks_offset += width * height;
    }

    block_size_index++;
  }
}

void GenerateWeightMask(const uint16_t* prediction_1, const ptrdiff_t stride_1,
                        const uint16_t* prediction_2, const ptrdiff_t stride_2,
                        const bool mask_is_inverse, const int width,
                        const int height, const int bitdepth, uint8_t* mask,
                        const ptrdiff_t mask_stride) {
#if LIBGAV1_MAX_BITDEPTH == 12
  const int inter_post_round_bits = (bitdepth == 12) ? 2 : 4;
#else
  constexpr int inter_post_round_bits = 4;
#endif
  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      const int rounding_bits = bitdepth - 8 + inter_post_round_bits;
      const int difference = RightShiftWithRounding(
          std::abs(prediction_1[x] - prediction_2[x]), rounding_bits);
      const auto mask_value =
          static_cast<uint8_t>(std::min(DivideBy16(difference) + 38, 64));
      mask[x] = mask_is_inverse ? 64 - mask_value : mask_value;
    }
    prediction_1 += stride_1;
    prediction_2 += stride_2;
    mask += mask_stride;
  }
}

void GenerateInterIntraMask(const int mode, const int width, const int height,
                            uint8_t* const mask, const ptrdiff_t mask_stride) {
  const int scale = kMaxSuperBlockSizeInPixels / std::max(width, height);
  uint8_t* mask_row = mask;
  const uint8_t* inter_intra_weight = kInterIntraWeights;
  // TODO(chengchen): Reorder mode types if we have stats that which modes are
  // used often.
  if (mode == kInterIntraModeVertical) {
    for (int y = 0; y < height; ++y) {
      memset(mask_row, *inter_intra_weight, width);
      mask_row += mask_stride;
      inter_intra_weight += scale;
    }
  } else if (mode == kInterIntraModeHorizontal) {
    for (int x = 0; x < width; ++x) {
      mask_row[x] = *inter_intra_weight;
      inter_intra_weight += scale;
    }
    mask_row += mask_stride;
    for (int y = 1; y < height; ++y) {
      memcpy(mask_row, mask, width);
      mask_row += mask_stride;
    }
  } else if (mode == kInterIntraModeSmooth) {
    uint8_t weight_row[64];
    const int size = std::min(width, height);
    for (int x = 0; x < width; ++x) {
      weight_row[x] = *inter_intra_weight;
      if (x < size) inter_intra_weight += scale;
    }
    int y;
    for (y = 0; y < std::min(width, height); ++y) {
      memcpy(mask_row, weight_row, y);
      memset(mask_row + y, weight_row[y], width - y);
      mask_row += mask_stride;
    }
    for (; y < height; ++y) {
      memcpy(mask_row, weight_row, width);
      mask_row += mask_stride;
    }
  } else {
    assert(mode == kInterIntraModeDc);
    for (int y = 0; y < height; ++y) {
      memset(mask_row, 32, width);
      mask_row += mask_stride;
    }
  }
}

}  // namespace libgav1
