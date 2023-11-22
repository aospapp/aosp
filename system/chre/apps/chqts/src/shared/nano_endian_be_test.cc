/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// For this test, we pretend we're a big endian platform, even if we don't
// happen to be.
#define CHRE_NO_ENDIAN_H 1
#define __LITTLE_ENDIAN 0
#define __BIG_ENDIAN 1
#define __BYTE_ORDER __BIG_ENDIAN

#include <shared/nano_endian.h>

#include <cstdint>
#include <cstring>

#include <gtest/gtest.h>
#include <shared/array_length.h>


static constexpr uint8_t kLittleEndianRepresentation[4] = {
  0x01, 0x02, 0x03, 0x04 };
static constexpr uint8_t kBigEndianRepresentation[4] = {
  0x04, 0x03, 0x02, 0x01 };

TEST(EndianTest, LittleEndianToBigEndianHost) {
  alignas(alignof(uint32_t)) uint8_t bytes[4];
  ::memcpy(bytes, kLittleEndianRepresentation, sizeof(bytes));
  uint32_t *valuePtr = reinterpret_cast<uint32_t*>(bytes);

  nanoapp_testing::littleEndianToHost(valuePtr);
  EXPECT_EQ(0, ::memcmp(bytes, kBigEndianRepresentation, sizeof(bytes)));
}

TEST(EndianTest, BigEndianHostToLittleEndian) {
  alignas(alignof(uint32_t)) uint8_t bytes[4];
  ::memcpy(bytes, kBigEndianRepresentation, sizeof(bytes));
  uint32_t *valuePtr = reinterpret_cast<uint32_t*>(bytes);

  nanoapp_testing::hostToLittleEndian(valuePtr);
  EXPECT_EQ(0, ::memcmp(bytes, kLittleEndianRepresentation, sizeof(bytes)));
}
