/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "utils/strings/utf8.h"

namespace libtextclassifier3 {
namespace {

TEST(Utf8Test, GetNumBytesForUTF8Char) {
  EXPECT_EQ(GetNumBytesForUTF8Char("\x00"), 0);
  EXPECT_EQ(GetNumBytesForUTF8Char("h"), 1);
  EXPECT_EQ(GetNumBytesForUTF8Char("😋"), 4);
  EXPECT_EQ(GetNumBytesForUTF8Char("㍿"), 3);
}

TEST(Utf8Test, IsValidUTF8) {
  EXPECT_TRUE(IsValidUTF8("1234😋hello", 13));
  EXPECT_TRUE(IsValidUTF8("\u304A\u00B0\u106B", 8));
  EXPECT_TRUE(IsValidUTF8("this is a test😋😋😋", 26));
  EXPECT_TRUE(IsValidUTF8("\xf0\x9f\x98\x8b", 4));
  // Too short (string is too short).
  EXPECT_FALSE(IsValidUTF8("\xf0\x9f", 2));
  // Too long (too many trailing bytes).
  EXPECT_FALSE(IsValidUTF8("\xf0\x9f\x98\x8b\x8b", 5));
  // Too short (too few trailing bytes).
  EXPECT_FALSE(IsValidUTF8("\xf0\x9f\x98\x61\x61", 5));
}

TEST(Utf8Test, ValidUTF8CharLength) {
  EXPECT_EQ(ValidUTF8CharLength("1234😋hello", 13), 1);
  EXPECT_EQ(ValidUTF8CharLength("\u304A\u00B0\u106B", 8), 3);
  EXPECT_EQ(ValidUTF8CharLength("this is a test😋😋😋", 26), 1);
  EXPECT_EQ(ValidUTF8CharLength("\xf0\x9f\x98\x8b", 4), 4);
  // Too short (string is too short).
  EXPECT_EQ(ValidUTF8CharLength("\xf0\x9f", 2), -1);
  // Too long (too many trailing bytes). First character is valid.
  EXPECT_EQ(ValidUTF8CharLength("\xf0\x9f\x98\x8b\x8b", 5), 4);
  // Too short (too few trailing bytes).
  EXPECT_EQ(ValidUTF8CharLength("\xf0\x9f\x98\x61\x61", 5), -1);
}

}  // namespace
}  // namespace libtextclassifier3
