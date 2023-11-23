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

#include "minikin/Font.h"

#include <gtest/gtest.h>

#include "BufferUtils.h"
#include "FontTestUtils.h"
#include "FreeTypeMinikinFontForTest.h"

namespace minikin {

namespace {

size_t getHeapSize() {
    struct mallinfo info = mallinfo();
    return info.uordblks;
}

}  // namespace

TEST(FontTest, BufferTest) {
    FreeTypeMinikinFontForTestFactory::init();
    auto minikinFont = std::make_shared<FreeTypeMinikinFontForTest>(getTestFontPath("Ascii.ttf"));
    std::shared_ptr<Font> original = Font::Builder(minikinFont).build();
    std::vector<uint8_t> buffer = writeToBuffer<Font>(*original);

    BufferReader reader(buffer.data());
    Font font(&reader);
    EXPECT_EQ(minikinFont->GetFontPath(), font.typeface()->GetFontPath());
    EXPECT_EQ(original->style(), font.style());
    EXPECT_EQ(original->getLocaleListId(), font.getLocaleListId());
    // baseFont() should return the same non-null instance when called twice.
    const auto& baseFont = font.baseFont();
    EXPECT_NE(nullptr, baseFont);
    EXPECT_EQ(baseFont, font.baseFont());
    // typeface() should return the same non-null instance when called twice.
    const auto& typeface = font.typeface();
    EXPECT_NE(nullptr, typeface);
    EXPECT_EQ(typeface, font.typeface());
    std::vector<uint8_t> newBuffer = writeToBuffer<Font>(font);
    EXPECT_EQ(buffer, newBuffer);
}

TEST(FontTest, MoveConstructorTest) {
    FreeTypeMinikinFontForTestFactory::init();
    // Note: by definition, only BufferReader-based Font can be moved.
    auto minikinFont = std::make_shared<FreeTypeMinikinFontForTest>(getTestFontPath("Ascii.ttf"));
    std::shared_ptr<Font> original = Font::Builder(minikinFont).build();
    std::vector<uint8_t> buffer = writeToBuffer<Font>(*original);

    size_t baseHeapSize = getHeapSize();
    {
        BufferReader reader(buffer.data());
        Font moveFrom(&reader);
        Font moveTo(std::move(moveFrom));
        EXPECT_EQ(nullptr, moveFrom.mExternalRefsHolder.load());
        EXPECT_EQ(nullptr, moveTo.mExternalRefsHolder.load());
    }
    EXPECT_EQ(baseHeapSize, getHeapSize());
    {
        BufferReader reader(buffer.data());
        Font moveFrom(&reader);
        std::shared_ptr<MinikinFont> typeface = moveFrom.typeface();
        Font moveTo(std::move(moveFrom));
        EXPECT_EQ(nullptr, moveFrom.mExternalRefsHolder.load());
        EXPECT_EQ(typeface, moveTo.typeface());
    }
    EXPECT_EQ(baseHeapSize, getHeapSize());
}

TEST(FontTest, MoveAssignmentTest) {
    FreeTypeMinikinFontForTestFactory::init();
    // Note: by definition, only BufferReader-based Font can be moved.
    auto minikinFont = std::make_shared<FreeTypeMinikinFontForTest>(getTestFontPath("Ascii.ttf"));
    std::shared_ptr<Font> original = Font::Builder(minikinFont).build();
    std::vector<uint8_t> buffer = writeToBuffer<Font>(*original);

    size_t baseHeapSize = getHeapSize();
    {
        // mExternalRefsHolder: null -> null
        BufferReader reader(buffer.data());
        Font moveFrom(&reader);
        BufferReader reader2(buffer.data());
        Font moveTo(&reader2);
        moveTo = std::move(moveFrom);
        EXPECT_EQ(nullptr, moveFrom.mExternalRefsHolder.load());
        EXPECT_EQ(nullptr, moveTo.mExternalRefsHolder.load());
    }
    EXPECT_EQ(baseHeapSize, getHeapSize());
    {
        // mExternalRefsHolder: non-null -> null
        BufferReader reader(buffer.data());
        Font moveFrom(&reader);
        std::shared_ptr<MinikinFont> typeface = moveFrom.typeface();
        BufferReader reader2(buffer.data());
        Font moveTo(&reader2);
        moveTo = std::move(moveFrom);
        EXPECT_EQ(nullptr, moveFrom.mExternalRefsHolder.load());
        EXPECT_EQ(typeface, moveTo.typeface());
    }
    EXPECT_EQ(baseHeapSize, getHeapSize());
    {
        // mExternalRefsHolder: null -> non-null
        BufferReader reader(buffer.data());
        Font moveFrom(&reader);
        BufferReader reader2(buffer.data());
        Font moveTo(&reader2);
        moveTo.typeface();
        moveTo = std::move(moveFrom);
        EXPECT_EQ(nullptr, moveFrom.mExternalRefsHolder.load());
        EXPECT_EQ(nullptr, moveTo.mExternalRefsHolder.load());
    }
    EXPECT_EQ(baseHeapSize, getHeapSize());
    {
        // mExternalRefsHolder: non-null -> non-null
        BufferReader reader(buffer.data());
        Font moveFrom(&reader);
        std::shared_ptr<MinikinFont> typeface = moveFrom.typeface();
        BufferReader reader2(buffer.data());
        Font moveTo(&reader2);
        moveTo.typeface();
        moveTo = std::move(moveFrom);
        EXPECT_EQ(nullptr, moveFrom.mExternalRefsHolder.load());
        EXPECT_EQ(typeface, moveTo.typeface());
    }
    EXPECT_EQ(baseHeapSize, getHeapSize());
}

}  // namespace minikin
