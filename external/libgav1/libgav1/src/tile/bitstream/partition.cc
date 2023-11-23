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

#include <cassert>
#include <cstdint>

#include "src/symbol_decoder_context.h"
#include "src/tile.h"
#include "src/utils/block_parameters_holder.h"
#include "src/utils/common.h"
#include "src/utils/constants.h"
#include "src/utils/entropy_decoder.h"
#include "src/utils/types.h"

namespace libgav1 {
namespace {

uint16_t InverseCdfProbability(uint16_t probability) {
  return kCdfMaxProbability - probability;
}

uint16_t CdfElementProbability(const uint16_t* const cdf, uint8_t element) {
  return (element > 0 ? cdf[element - 1] : uint16_t{kCdfMaxProbability}) -
         cdf[element];
}

void PartitionCdfGatherHorizontalAlike(const uint16_t* const partition_cdf,
                                       BlockSize block_size,
                                       uint16_t* const cdf) {
  cdf[0] = kCdfMaxProbability;
  cdf[0] -= CdfElementProbability(partition_cdf, kPartitionHorizontal);
  cdf[0] -= CdfElementProbability(partition_cdf, kPartitionSplit);
  cdf[0] -=
      CdfElementProbability(partition_cdf, kPartitionHorizontalWithTopSplit);
  cdf[0] -=
      CdfElementProbability(partition_cdf, kPartitionHorizontalWithBottomSplit);
  cdf[0] -=
      CdfElementProbability(partition_cdf, kPartitionVerticalWithLeftSplit);
  if (block_size != kBlock128x128) {
    cdf[0] -= CdfElementProbability(partition_cdf, kPartitionHorizontal4);
  }
  cdf[0] = InverseCdfProbability(cdf[0]);
  cdf[1] = 0;
  cdf[2] = 0;
}

void PartitionCdfGatherVerticalAlike(const uint16_t* const partition_cdf,
                                     BlockSize block_size,
                                     uint16_t* const cdf) {
  cdf[0] = kCdfMaxProbability;
  cdf[0] -= CdfElementProbability(partition_cdf, kPartitionVertical);
  cdf[0] -= CdfElementProbability(partition_cdf, kPartitionSplit);
  cdf[0] -=
      CdfElementProbability(partition_cdf, kPartitionVerticalWithLeftSplit);
  cdf[0] -=
      CdfElementProbability(partition_cdf, kPartitionVerticalWithRightSplit);
  cdf[0] -=
      CdfElementProbability(partition_cdf, kPartitionHorizontalWithTopSplit);
  if (block_size != kBlock128x128) {
    cdf[0] -= CdfElementProbability(partition_cdf, kPartitionVertical4);
  }
  cdf[0] = InverseCdfProbability(cdf[0]);
  cdf[1] = 0;
  cdf[2] = 0;
}

}  // namespace

uint16_t* Tile::GetPartitionCdf(int row4x4, int column4x4,
                                BlockSize block_size) {
  const int block_size_log2 = k4x4WidthLog2[block_size];
  int top = 0;
  if (IsInside(row4x4 - 1, column4x4)) {
    top = static_cast<int>(
        k4x4WidthLog2[block_parameters_holder_.Find(row4x4 - 1, column4x4)
                          ->size] < block_size_log2);
  }
  int left = 0;
  if (IsInside(row4x4, column4x4 - 1)) {
    left = static_cast<int>(
        k4x4HeightLog2[block_parameters_holder_.Find(row4x4, column4x4 - 1)
                           ->size] < block_size_log2);
  }
  const int context = left * 2 + top;
  return symbol_decoder_context_.partition_cdf[block_size_log2 - 1][context];
}

bool Tile::ReadPartition(int row4x4, int column4x4, BlockSize block_size,
                         bool has_rows, bool has_columns,
                         Partition* const partition) {
  if (IsBlockSmallerThan8x8(block_size)) {
    *partition = kPartitionNone;
    return true;
  }
  if (!has_rows && !has_columns) {
    *partition = kPartitionSplit;
    return true;
  }
  uint16_t* const partition_cdf =
      GetPartitionCdf(row4x4, column4x4, block_size);
  if (partition_cdf == nullptr) {
    return false;
  }
  if (has_rows && has_columns) {
    const int bsize_log2 = k4x4WidthLog2[block_size];
    // The partition block size should be 8x8 or above.
    assert(bsize_log2 > 0);
    const int cdf_size = SymbolDecoderContext::PartitionCdfSize(bsize_log2);
    *partition =
        static_cast<Partition>(reader_.ReadSymbol(partition_cdf, cdf_size));
  } else if (has_columns) {
    uint16_t cdf[3];
    PartitionCdfGatherVerticalAlike(partition_cdf, block_size, cdf);
    *partition = reader_.ReadSymbolWithoutCdfUpdate(cdf) ? kPartitionSplit
                                                         : kPartitionHorizontal;
  } else {
    uint16_t cdf[3];
    PartitionCdfGatherHorizontalAlike(partition_cdf, block_size, cdf);
    *partition = reader_.ReadSymbolWithoutCdfUpdate(cdf) ? kPartitionSplit
                                                         : kPartitionVertical;
  }
  return true;
}

}  // namespace libgav1
