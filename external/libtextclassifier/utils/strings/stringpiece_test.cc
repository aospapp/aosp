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

#include "utils/strings/stringpiece.h"

namespace libtextclassifier3 {
namespace {

TEST(StringPieceTest, EndsWith) {
  EXPECT_TRUE(EndsWith("hello there!", "there!"));
  EXPECT_TRUE(EndsWith("hello there!", "!"));
  EXPECT_FALSE(EndsWith("hello there!", "there"));
  EXPECT_FALSE(EndsWith("hello there!", " hello there!"));
  EXPECT_TRUE(EndsWith("hello there!", ""));
  EXPECT_FALSE(EndsWith("", "hello there!"));
}

TEST(StringPieceTest, StartsWith) {
  EXPECT_TRUE(StartsWith("hello there!", "hello"));
  EXPECT_TRUE(StartsWith("hello there!", "hello "));
  EXPECT_FALSE(StartsWith("hello there!", "there!"));
  EXPECT_FALSE(StartsWith("hello there!", " hello there! "));
  EXPECT_TRUE(StartsWith("hello there!", ""));
  EXPECT_FALSE(StartsWith("", "hello there!"));
}

TEST(StringPieceTest, ConsumePrefix) {
  StringPiece str("hello there!");
  EXPECT_TRUE(ConsumePrefix(&str, "hello "));
  EXPECT_EQ(str.ToString(), "there!");
  EXPECT_TRUE(ConsumePrefix(&str, "there"));
  EXPECT_EQ(str.ToString(), "!");
  EXPECT_FALSE(ConsumePrefix(&str, "!!"));
  EXPECT_TRUE(ConsumePrefix(&str, ""));
  EXPECT_TRUE(ConsumePrefix(&str, "!"));
  EXPECT_EQ(str.ToString(), "");
  EXPECT_TRUE(ConsumePrefix(&str, ""));
  EXPECT_FALSE(ConsumePrefix(&str, "!"));
}

}  // namespace
}  // namespace libtextclassifier3
