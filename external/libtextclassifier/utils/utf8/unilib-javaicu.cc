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

#include "utils/utf8/unilib-javaicu.h"

#include <algorithm>
#include <cassert>
#include <cctype>
#include <map>

#include "utils/java/string_utils.h"

namespace libtextclassifier3 {
namespace {

// -----------------------------------------------------------------------------
// Native implementations.
// -----------------------------------------------------------------------------

#define ARRAYSIZE(a) sizeof(a) / sizeof(*a)

// Derived from http://www.unicode.org/Public/UNIDATA/UnicodeData.txt
// grep -E "Ps" UnicodeData.txt | \
//   sed -rne "s/^([0-9A-Z]{4});.*(PAREN|BRACKET|BRAKCET|BRACE).*/0x\1, /p"
// IMPORTANT: entries with the same offsets in kOpeningBrackets and
//            kClosingBrackets must be counterparts.
constexpr char32 kOpeningBrackets[] = {
    0x0028, 0x005B, 0x007B, 0x0F3C, 0x2045, 0x207D, 0x208D, 0x2329, 0x2768,
    0x276A, 0x276C, 0x2770, 0x2772, 0x2774, 0x27E6, 0x27E8, 0x27EA, 0x27EC,
    0x27EE, 0x2983, 0x2985, 0x2987, 0x2989, 0x298B, 0x298D, 0x298F, 0x2991,
    0x2993, 0x2995, 0x2997, 0x29FC, 0x2E22, 0x2E24, 0x2E26, 0x2E28, 0x3008,
    0x300A, 0x300C, 0x300E, 0x3010, 0x3014, 0x3016, 0x3018, 0x301A, 0xFD3F,
    0xFE17, 0xFE35, 0xFE37, 0xFE39, 0xFE3B, 0xFE3D, 0xFE3F, 0xFE41, 0xFE43,
    0xFE47, 0xFE59, 0xFE5B, 0xFE5D, 0xFF08, 0xFF3B, 0xFF5B, 0xFF5F, 0xFF62};
constexpr int kNumOpeningBrackets = ARRAYSIZE(kOpeningBrackets);

// grep -E "Pe" UnicodeData.txt | \
//   sed -rne "s/^([0-9A-Z]{4});.*(PAREN|BRACKET|BRAKCET|BRACE).*/0x\1, /p"
constexpr char32 kClosingBrackets[] = {
    0x0029, 0x005D, 0x007D, 0x0F3D, 0x2046, 0x207E, 0x208E, 0x232A, 0x2769,
    0x276B, 0x276D, 0x2771, 0x2773, 0x2775, 0x27E7, 0x27E9, 0x27EB, 0x27ED,
    0x27EF, 0x2984, 0x2986, 0x2988, 0x298A, 0x298C, 0x298E, 0x2990, 0x2992,
    0x2994, 0x2996, 0x2998, 0x29FD, 0x2E23, 0x2E25, 0x2E27, 0x2E29, 0x3009,
    0x300B, 0x300D, 0x300F, 0x3011, 0x3015, 0x3017, 0x3019, 0x301B, 0xFD3E,
    0xFE18, 0xFE36, 0xFE38, 0xFE3A, 0xFE3C, 0xFE3E, 0xFE40, 0xFE42, 0xFE44,
    0xFE48, 0xFE5A, 0xFE5C, 0xFE5E, 0xFF09, 0xFF3D, 0xFF5D, 0xFF60, 0xFF63};
constexpr int kNumClosingBrackets = ARRAYSIZE(kClosingBrackets);

// grep -E "WS" UnicodeData.txt | sed -re "s/([0-9A-Z]+);.*/0x\1, /"
constexpr char32 kWhitespaces[] = {
    0x000C,  0x0020,  0x1680,  0x2000,  0x2001,  0x2002,  0x2003,  0x2004,
    0x2005,  0x2006,  0x2007,  0x2008,  0x2009,  0x200A,  0x2028,  0x205F,
    0x21C7,  0x21C8,  0x21C9,  0x21CA,  0x21F6,  0x2B31,  0x2B84,  0x2B85,
    0x2B86,  0x2B87,  0x2B94,  0x3000,  0x4DCC,  0x10344, 0x10347, 0x1DA0A,
    0x1DA0B, 0x1DA0C, 0x1DA0D, 0x1DA0E, 0x1DA0F, 0x1DA10, 0x1F4F0, 0x1F500,
    0x1F501, 0x1F502, 0x1F503, 0x1F504, 0x1F5D8, 0x1F5DE};
constexpr int kNumWhitespaces = ARRAYSIZE(kWhitespaces);

// grep -E "Nd" UnicodeData.txt | sed -re "s/([0-9A-Z]+);.*/0x\1, /"
// As the name suggests, these ranges are always 10 codepoints long, so we just
// store the end of the range.
constexpr char32 kDecimalDigitRangesEnd[] = {
    0x0039,  0x0669,  0x06f9,  0x07c9,  0x096f,  0x09ef,  0x0a6f,  0x0aef,
    0x0b6f,  0x0bef,  0x0c6f,  0x0cef,  0x0d6f,  0x0def,  0x0e59,  0x0ed9,
    0x0f29,  0x1049,  0x1099,  0x17e9,  0x1819,  0x194f,  0x19d9,  0x1a89,
    0x1a99,  0x1b59,  0x1bb9,  0x1c49,  0x1c59,  0xa629,  0xa8d9,  0xa909,
    0xa9d9,  0xa9f9,  0xaa59,  0xabf9,  0xff19,  0x104a9, 0x1106f, 0x110f9,
    0x1113f, 0x111d9, 0x112f9, 0x11459, 0x114d9, 0x11659, 0x116c9, 0x11739,
    0x118e9, 0x11c59, 0x11d59, 0x16a69, 0x16b59, 0x1d7ff};
constexpr int kNumDecimalDigitRangesEnd = ARRAYSIZE(kDecimalDigitRangesEnd);

// grep -E "Lu" UnicodeData.txt | sed -re "s/([0-9A-Z]+);.*/0x\1, /"
// There are three common ways in which upper/lower case codepoint ranges
// were introduced: one offs, dense ranges, and ranges that alternate between
// lower and upper case. For the sake of keeping out binary size down, we
// treat each independently.
constexpr char32 kUpperSingles[] = {
    0x01b8, 0x01bc, 0x01c4, 0x01c7, 0x01ca, 0x01f1, 0x0376, 0x037f,
    0x03cf, 0x03f4, 0x03fa, 0x10c7, 0x10cd, 0x2102, 0x2107, 0x2115,
    0x2145, 0x2183, 0x2c72, 0x2c75, 0x2cf2, 0xa7b6};
constexpr int kNumUpperSingles = ARRAYSIZE(kUpperSingles);
constexpr char32 kUpperRanges1Start[] = {
    0x0041, 0x00c0, 0x00d8, 0x0181, 0x018a, 0x018e, 0x0193, 0x0196,
    0x019c, 0x019f, 0x01b2, 0x01f7, 0x023a, 0x023d, 0x0244, 0x0389,
    0x0392, 0x03a3, 0x03d2, 0x03fd, 0x0531, 0x10a0, 0x13a0, 0x1f08,
    0x1f18, 0x1f28, 0x1f38, 0x1f48, 0x1f68, 0x1fb8, 0x1fc8, 0x1fd8,
    0x1fe8, 0x1ff8, 0x210b, 0x2110, 0x2119, 0x212b, 0x2130, 0x213e,
    0x2c00, 0x2c63, 0x2c6e, 0x2c7e, 0xa7ab, 0xa7b0};
constexpr int kNumUpperRanges1Start = ARRAYSIZE(kUpperRanges1Start);
constexpr char32 kUpperRanges1End[] = {
    0x005a, 0x00d6, 0x00de, 0x0182, 0x018b, 0x0191, 0x0194, 0x0198,
    0x019d, 0x01a0, 0x01b3, 0x01f8, 0x023b, 0x023e, 0x0246, 0x038a,
    0x03a1, 0x03ab, 0x03d4, 0x042f, 0x0556, 0x10c5, 0x13f5, 0x1f0f,
    0x1f1d, 0x1f2f, 0x1f3f, 0x1f4d, 0x1f6f, 0x1fbb, 0x1fcb, 0x1fdb,
    0x1fec, 0x1ffb, 0x210d, 0x2112, 0x211d, 0x212d, 0x2133, 0x213f,
    0x2c2e, 0x2c64, 0x2c70, 0x2c80, 0xa7ae, 0xa7b4};
constexpr int kNumUpperRanges1End = ARRAYSIZE(kUpperRanges1End);
constexpr char32 kUpperRanges2Start[] = {
    0x0100, 0x0139, 0x014a, 0x0179, 0x0184, 0x0187, 0x01a2, 0x01a7, 0x01ac,
    0x01af, 0x01b5, 0x01cd, 0x01de, 0x01f4, 0x01fa, 0x0241, 0x0248, 0x0370,
    0x0386, 0x038c, 0x038f, 0x03d8, 0x03f7, 0x0460, 0x048a, 0x04c1, 0x04d0,
    0x1e00, 0x1e9e, 0x1f59, 0x2124, 0x2c60, 0x2c67, 0x2c82, 0x2ceb, 0xa640,
    0xa680, 0xa722, 0xa732, 0xa779, 0xa77e, 0xa78b, 0xa790, 0xa796};
constexpr int kNumUpperRanges2Start = ARRAYSIZE(kUpperRanges2Start);
constexpr char32 kUpperRanges2End[] = {
    0x0136, 0x0147, 0x0178, 0x017d, 0x0186, 0x0189, 0x01a6, 0x01a9, 0x01ae,
    0x01b1, 0x01b7, 0x01db, 0x01ee, 0x01f6, 0x0232, 0x0243, 0x024e, 0x0372,
    0x0388, 0x038e, 0x0391, 0x03ee, 0x03f9, 0x0480, 0x04c0, 0x04cd, 0x052e,
    0x1e94, 0x1efe, 0x1f5f, 0x212a, 0x2c62, 0x2c6d, 0x2ce2, 0x2ced, 0xa66c,
    0xa69a, 0xa72e, 0xa76e, 0xa77d, 0xa786, 0xa78d, 0xa792, 0xa7aa};
constexpr int kNumUpperRanges2End = ARRAYSIZE(kUpperRanges2End);

// grep -E "Lu" UnicodeData.txt | \
//   sed -rne "s/^([0-9A-Z]+);.*;([0-9A-Z]+);$/(0x\1, 0x\2), /p"
// We have two strategies for mapping from upper to lower case. We have single
// character lookups that do not follow a pattern, and ranges for which there
// is a constant codepoint shift.
// Note that these ranges ignore anything that's not an upper case character,
// so when applied to a non-uppercase character the result is incorrect.
constexpr int kToLowerSingles[] = {
    0x0130, 0x0178, 0x0181, 0x0186, 0x018b, 0x018e, 0x018f, 0x0190, 0x0191,
    0x0194, 0x0196, 0x0197, 0x0198, 0x019c, 0x019d, 0x019f, 0x01a6, 0x01a9,
    0x01ae, 0x01b7, 0x01f6, 0x01f7, 0x0220, 0x023a, 0x023d, 0x023e, 0x0243,
    0x0244, 0x0245, 0x037f, 0x0386, 0x038c, 0x03cf, 0x03f4, 0x03f9, 0x04c0,
    0x1e9e, 0x1fec, 0x2126, 0x212a, 0x212b, 0x2132, 0x2183, 0x2c60, 0x2c62,
    0x2c63, 0x2c64, 0x2c6d, 0x2c6e, 0x2c6f, 0x2c70, 0xa77d, 0xa78d, 0xa7aa,
    0xa7ab, 0xa7ac, 0xa7ad, 0xa7ae, 0xa7b0, 0xa7b1, 0xa7b2, 0xa7b3};
constexpr int kNumToLowerSingles = ARRAYSIZE(kToLowerSingles);
constexpr int kToLowerSinglesOffsets[] = {
    -199,   -121,   210,    206,    1,      79,     202,    203,    1,
    207,    211,    209,    1,      211,    213,    214,    218,    218,
    218,    219,    -97,    -56,    -130,   10795,  -163,   10792,  -195,
    69,     71,     116,    38,     64,     8,      -60,    -7,     15,
    -7615,  -7,     -7517,  -8383,  -8262,  28,     1,      1,      -10743,
    -3814,  -10727, -10780, -10749, -10783, -10782, -35332, -42280, -42308,
    -42319, -42315, -42305, -42308, -42258, -42282, -42261, 928};
constexpr int kNumToLowerSinglesOffsets = ARRAYSIZE(kToLowerSinglesOffsets);
constexpr int kToLowerRangesStart[] = {
    0x0041, 0x0100, 0x0189, 0x01a0, 0x01b1, 0x01b3, 0x0388,  0x038e,  0x0391,
    0x03d8, 0x03fd, 0x0400, 0x0410, 0x0460, 0x0531, 0x10a0,  0x13a0,  0x13f0,
    0x1e00, 0x1f08, 0x1fba, 0x1fc8, 0x1fd8, 0x1fda, 0x1fe8,  0x1fea,  0x1ff8,
    0x1ffa, 0x2c00, 0x2c67, 0x2c7e, 0x2c80, 0xff21, 0x10400, 0x10c80, 0x118a0};
constexpr int kNumToLowerRangesStart = ARRAYSIZE(kToLowerRangesStart);
constexpr int kToLowerRangesEnd[] = {
    0x00de, 0x0187, 0x019f, 0x01af, 0x01b2, 0x0386, 0x038c,  0x038f,  0x03cf,
    0x03fa, 0x03ff, 0x040f, 0x042f, 0x052e, 0x0556, 0x10cd,  0x13ef,  0x13f5,
    0x1efe, 0x1fb9, 0x1fbb, 0x1fcb, 0x1fd9, 0x1fdb, 0x1fe9,  0x1fec,  0x1ff9,
    0x2183, 0x2c64, 0x2c75, 0x2c7f, 0xa7b6, 0xff3a, 0x104d3, 0x10cb2, 0x118bf};
constexpr int kNumToLowerRangesEnd = ARRAYSIZE(kToLowerRangesEnd);
constexpr int kToLowerRangesOffsets[] = {
    32, 1,    205,  1,    217,   1, 37,     63, 32,  1,   -130, 80,
    32, 1,    48,   7264, 38864, 8, 1,      -8, -74, -86, -8,   -100,
    -8, -112, -128, -126, 48,    1, -10815, 1,  32,  40,  64,   32};
constexpr int kNumToLowerRangesOffsets = ARRAYSIZE(kToLowerRangesOffsets);

#undef ARRAYSIZE

static_assert(kNumOpeningBrackets == kNumClosingBrackets,
              "mismatching number of opening and closing brackets");
static_assert(kNumUpperRanges1Start == kNumUpperRanges1End,
              "number of uppercase stride 1 range starts/ends doesn't match");
static_assert(kNumUpperRanges2Start == kNumUpperRanges2End,
              "number of uppercase stride 2 range starts/ends doesn't match");
static_assert(kNumToLowerSingles == kNumToLowerSinglesOffsets,
              "number of to lower singles and offsets doesn't match");
static_assert(kNumToLowerRangesStart == kNumToLowerRangesEnd,
              "mismatching number of range starts/ends for to lower ranges");
static_assert(kNumToLowerRangesStart == kNumToLowerRangesOffsets,
              "number of to lower ranges and offsets doesn't match");

constexpr int kNoMatch = -1;

// Returns the index of the element in the array that matched the given
// codepoint, or kNoMatch if the element didn't exist.
// The input array must be in sorted order.
int GetMatchIndex(const char32* array, int array_length, char32 c) {
  const char32* end = array + array_length;
  const auto find_it = std::lower_bound(array, end, c);
  if (find_it != end && *find_it == c) {
    return find_it - array;
  } else {
    return kNoMatch;
  }
}

// Returns the index of the range in the array that overlapped the given
// codepoint, or kNoMatch if no such range existed.
// The input array must be in sorted order.
int GetOverlappingRangeIndex(const char32* arr, int arr_length,
                             int range_length, char32 c) {
  const char32* end = arr + arr_length;
  const auto find_it = std::lower_bound(arr, end, c);
  if (find_it == end) {
    return kNoMatch;
  }
  // The end is inclusive, we so subtract one less than the range length.
  const char32 range_end = *find_it;
  const char32 range_start = range_end - (range_length - 1);
  if (c < range_start || range_end < c) {
    return kNoMatch;
  } else {
    return find_it - arr;
  }
}

// As above, but with explicit codepoint start and end indices for the range.
// The input array must be in sorted order.
int GetOverlappingRangeIndex(const char32* start_arr, const char32* end_arr,
                             int arr_length, int stride, char32 c) {
  const char32* end_arr_end = end_arr + arr_length;
  const auto find_it = std::lower_bound(end_arr, end_arr_end, c);
  if (find_it == end_arr_end) {
    return kNoMatch;
  }
  // Find the corresponding start.
  const int range_index = find_it - end_arr;
  const char32 range_start = start_arr[range_index];
  const char32 range_end = *find_it;
  if (c < range_start || range_end < c) {
    return kNoMatch;
  }
  if ((c - range_start) % stride == 0) {
    return range_index;
  } else {
    return kNoMatch;
  }
}

}  // anonymous namespace

UniLib::UniLib() {
  TC3_LOG(FATAL) << "Java ICU UniLib must be initialized with a JniCache.";
}

UniLib::UniLib(const std::shared_ptr<JniCache>& jni_cache)
    : jni_cache_(jni_cache) {}

bool UniLib::IsOpeningBracket(char32 codepoint) const {
  return GetMatchIndex(kOpeningBrackets, kNumOpeningBrackets, codepoint) >= 0;
}

bool UniLib::IsClosingBracket(char32 codepoint) const {
  return GetMatchIndex(kClosingBrackets, kNumClosingBrackets, codepoint) >= 0;
}

bool UniLib::IsWhitespace(char32 codepoint) const {
  return GetMatchIndex(kWhitespaces, kNumWhitespaces, codepoint) >= 0;
}

bool UniLib::IsDigit(char32 codepoint) const {
  return GetOverlappingRangeIndex(kDecimalDigitRangesEnd,
                                  kNumDecimalDigitRangesEnd,
                                  /*range_length=*/10, codepoint) >= 0;
}

bool UniLib::IsUpper(char32 codepoint) const {
  if (GetMatchIndex(kUpperSingles, kNumUpperSingles, codepoint) >= 0) {
    return true;
  } else if (GetOverlappingRangeIndex(kUpperRanges1Start, kUpperRanges1End,
                                      kNumUpperRanges1Start, /*stride=*/1,
                                      codepoint) >= 0) {
    return true;
  } else if (GetOverlappingRangeIndex(kUpperRanges2Start, kUpperRanges2End,
                                      kNumUpperRanges2Start, /*stride=*/2,
                                      codepoint) >= 0) {
    return true;
  } else {
    return false;
  }
}

char32 UniLib::ToLower(char32 codepoint) const {
  // Make sure we still produce output even if the method is called for a
  // codepoint that's not an uppercase character.
  if (!IsUpper(codepoint)) {
    return codepoint;
  }
  const int singles_idx =
      GetMatchIndex(kToLowerSingles, kNumToLowerSingles, codepoint);
  if (singles_idx >= 0) {
    return codepoint + kToLowerSinglesOffsets[singles_idx];
  }
  const int ranges_idx =
      GetOverlappingRangeIndex(kToLowerRangesStart, kToLowerRangesEnd,
                               kNumToLowerRangesStart, /*stride=*/1, codepoint);
  if (ranges_idx >= 0) {
    return codepoint + kToLowerRangesOffsets[ranges_idx];
  }
  return codepoint;
}

char32 UniLib::GetPairedBracket(char32 codepoint) const {
  const int open_offset =
      GetMatchIndex(kOpeningBrackets, kNumOpeningBrackets, codepoint);
  if (open_offset >= 0) {
    return kClosingBrackets[open_offset];
  }
  const int close_offset =
      GetMatchIndex(kClosingBrackets, kNumClosingBrackets, codepoint);
  if (close_offset >= 0) {
    return kOpeningBrackets[close_offset];
  }
  return codepoint;
}

// -----------------------------------------------------------------------------
// Implementations that call out to JVM. Behold the beauty.
// -----------------------------------------------------------------------------

bool UniLib::ParseInt32(const UnicodeText& text, int* result) const {
  if (jni_cache_) {
    JNIEnv* env = jni_cache_->GetEnv();
    const ScopedLocalRef<jstring> text_java =
        jni_cache_->ConvertToJavaString(text);
    jint res = env->CallStaticIntMethod(jni_cache_->integer_class.get(),
                                        jni_cache_->integer_parse_int,
                                        text_java.get());
    if (jni_cache_->ExceptionCheckAndClear()) {
      return false;
    }
    *result = res;
    return true;
  }
  return false;
}

std::unique_ptr<UniLib::RegexPattern> UniLib::CreateRegexPattern(
    const UnicodeText& regex) const {
  return std::unique_ptr<UniLib::RegexPattern>(
      new UniLib::RegexPattern(jni_cache_.get(), regex, /*lazy=*/false));
}

std::unique_ptr<UniLib::RegexPattern> UniLib::CreateLazyRegexPattern(
    const UnicodeText& regex) const {
  return std::unique_ptr<UniLib::RegexPattern>(
      new UniLib::RegexPattern(jni_cache_.get(), regex, /*lazy=*/true));
}

UniLib::RegexPattern::RegexPattern(const JniCache* jni_cache,
                                   const UnicodeText& pattern, bool lazy)
    : jni_cache_(jni_cache),
      pattern_(nullptr, jni_cache ? jni_cache->jvm : nullptr),
      initialized_(false),
      initialization_failure_(false),
      pattern_text_(pattern) {
  if (!lazy) {
    LockedInitializeIfNotAlready();
  }
}

void UniLib::RegexPattern::LockedInitializeIfNotAlready() const {
  std::lock_guard<std::mutex> guard(mutex_);
  if (initialized_ || initialization_failure_) {
    return;
  }

  if (jni_cache_) {
    JNIEnv* jenv = jni_cache_->GetEnv();
    const ScopedLocalRef<jstring> regex_java =
        jni_cache_->ConvertToJavaString(pattern_text_);
    pattern_ = MakeGlobalRef(jenv->CallStaticObjectMethod(
                                 jni_cache_->pattern_class.get(),
                                 jni_cache_->pattern_compile, regex_java.get()),
                             jenv, jni_cache_->jvm);

    if (jni_cache_->ExceptionCheckAndClear() || pattern_ == nullptr) {
      initialization_failure_ = true;
      pattern_.reset();
      return;
    }

    initialized_ = true;
    pattern_text_.clear();  // We don't need this anymore.
  }
}

constexpr int UniLib::RegexMatcher::kError;
constexpr int UniLib::RegexMatcher::kNoError;

std::unique_ptr<UniLib::RegexMatcher> UniLib::RegexPattern::Matcher(
    const UnicodeText& context) const {
  LockedInitializeIfNotAlready();  // Possibly lazy initialization.
  if (initialization_failure_) {
    return nullptr;
  }

  if (jni_cache_) {
    JNIEnv* env = jni_cache_->GetEnv();
    const jstring context_java =
        jni_cache_->ConvertToJavaString(context).release();
    if (!context_java) {
      return nullptr;
    }
    const jobject matcher = env->CallObjectMethod(
        pattern_.get(), jni_cache_->pattern_matcher, context_java);
    if (jni_cache_->ExceptionCheckAndClear() || !matcher) {
      return nullptr;
    }
    return std::unique_ptr<UniLib::RegexMatcher>(new RegexMatcher(
        jni_cache_, MakeGlobalRef(matcher, env, jni_cache_->jvm),
        MakeGlobalRef(context_java, env, jni_cache_->jvm)));
  } else {
    // NOTE: A valid object needs to be created here to pass the interface
    // tests.
    return std::unique_ptr<UniLib::RegexMatcher>(
        new RegexMatcher(jni_cache_, nullptr, nullptr));
  }
}

UniLib::RegexMatcher::RegexMatcher(const JniCache* jni_cache,
                                   ScopedGlobalRef<jobject> matcher,
                                   ScopedGlobalRef<jstring> text)
    : jni_cache_(jni_cache),
      matcher_(std::move(matcher)),
      text_(std::move(text)) {}

bool UniLib::RegexMatcher::Matches(int* status) const {
  if (jni_cache_) {
    *status = kNoError;
    const bool result = jni_cache_->GetEnv()->CallBooleanMethod(
        matcher_.get(), jni_cache_->matcher_matches);
    if (jni_cache_->ExceptionCheckAndClear()) {
      *status = kError;
      return false;
    }
    return result;
  } else {
    *status = kError;
    return false;
  }
}

bool UniLib::RegexMatcher::ApproximatelyMatches(int* status) {
  *status = kNoError;

  jni_cache_->GetEnv()->CallObjectMethod(matcher_.get(),
                                         jni_cache_->matcher_reset);
  if (jni_cache_->ExceptionCheckAndClear()) {
    *status = kError;
    return kError;
  }

  if (!Find(status) || *status != kNoError) {
    return false;
  }

  const int found_start = jni_cache_->GetEnv()->CallIntMethod(
      matcher_.get(), jni_cache_->matcher_start_idx, 0);
  if (jni_cache_->ExceptionCheckAndClear()) {
    *status = kError;
    return kError;
  }

  const int found_end = jni_cache_->GetEnv()->CallIntMethod(
      matcher_.get(), jni_cache_->matcher_end_idx, 0);
  if (jni_cache_->ExceptionCheckAndClear()) {
    *status = kError;
    return kError;
  }

  int context_length_bmp = jni_cache_->GetEnv()->CallIntMethod(
      text_.get(), jni_cache_->string_length);
  if (jni_cache_->ExceptionCheckAndClear()) {
    *status = kError;
    return false;
  }

  if (found_start != 0 || found_end != context_length_bmp) {
    return false;
  }

  return true;
}

bool UniLib::RegexMatcher::UpdateLastFindOffset() const {
  if (!last_find_offset_dirty_) {
    return true;
  }

  const int find_offset = jni_cache_->GetEnv()->CallIntMethod(
      matcher_.get(), jni_cache_->matcher_start_idx, 0);
  if (jni_cache_->ExceptionCheckAndClear()) {
    return false;
  }

  const int codepoint_count = jni_cache_->GetEnv()->CallIntMethod(
      text_.get(), jni_cache_->string_code_point_count, last_find_offset_,
      find_offset);
  if (jni_cache_->ExceptionCheckAndClear()) {
    return false;
  }

  last_find_offset_codepoints_ += codepoint_count;
  last_find_offset_ = find_offset;
  last_find_offset_dirty_ = false;

  return true;
}

bool UniLib::RegexMatcher::Find(int* status) {
  if (jni_cache_) {
    const bool result = jni_cache_->GetEnv()->CallBooleanMethod(
        matcher_.get(), jni_cache_->matcher_find);
    if (jni_cache_->ExceptionCheckAndClear()) {
      *status = kError;
      return false;
    }

    last_find_offset_dirty_ = true;
    *status = kNoError;
    return result;
  } else {
    *status = kError;
    return false;
  }
}

int UniLib::RegexMatcher::Start(int* status) const {
  return Start(/*group_idx=*/0, status);
}

int UniLib::RegexMatcher::Start(int group_idx, int* status) const {
  if (jni_cache_) {
    *status = kNoError;

    if (!UpdateLastFindOffset()) {
      *status = kError;
      return kError;
    }

    const int java_index = jni_cache_->GetEnv()->CallIntMethod(
        matcher_.get(), jni_cache_->matcher_start_idx, group_idx);
    if (jni_cache_->ExceptionCheckAndClear()) {
      *status = kError;
      return kError;
    }

    // If the group didn't participate in the match the index is -1.
    if (java_index == -1) {
      return -1;
    }

    const int unicode_index = jni_cache_->GetEnv()->CallIntMethod(
        text_.get(), jni_cache_->string_code_point_count, last_find_offset_,
        java_index);
    if (jni_cache_->ExceptionCheckAndClear()) {
      *status = kError;
      return kError;
    }

    return unicode_index + last_find_offset_codepoints_;
  } else {
    *status = kError;
    return kError;
  }
}

int UniLib::RegexMatcher::End(int* status) const {
  return End(/*group_idx=*/0, status);
}

int UniLib::RegexMatcher::End(int group_idx, int* status) const {
  if (jni_cache_) {
    *status = kNoError;

    if (!UpdateLastFindOffset()) {
      *status = kError;
      return kError;
    }

    const int java_index = jni_cache_->GetEnv()->CallIntMethod(
        matcher_.get(), jni_cache_->matcher_end_idx, group_idx);
    if (jni_cache_->ExceptionCheckAndClear()) {
      *status = kError;
      return kError;
    }

    // If the group didn't participate in the match the index is -1.
    if (java_index == -1) {
      return -1;
    }

    const int unicode_index = jni_cache_->GetEnv()->CallIntMethod(
        text_.get(), jni_cache_->string_code_point_count, last_find_offset_,
        java_index);
    if (jni_cache_->ExceptionCheckAndClear()) {
      *status = kError;
      return kError;
    }

    return unicode_index + last_find_offset_codepoints_;
  } else {
    *status = kError;
    return kError;
  }
}

UnicodeText UniLib::RegexMatcher::Group(int* status) const {
  if (jni_cache_) {
    JNIEnv* jenv = jni_cache_->GetEnv();
    const ScopedLocalRef<jstring> java_result(
        reinterpret_cast<jstring>(
            jenv->CallObjectMethod(matcher_.get(), jni_cache_->matcher_group)),
        jenv);
    if (jni_cache_->ExceptionCheckAndClear() || !java_result) {
      *status = kError;
      return UTF8ToUnicodeText("", /*do_copy=*/false);
    }

    std::string result;
    if (!JStringToUtf8String(jenv, java_result.get(), &result)) {
      *status = kError;
      return UTF8ToUnicodeText("", /*do_copy=*/false);
    }
    *status = kNoError;
    return UTF8ToUnicodeText(result, /*do_copy=*/true);
  } else {
    *status = kError;
    return UTF8ToUnicodeText("", /*do_copy=*/false);
  }
}

UnicodeText UniLib::RegexMatcher::Group(int group_idx, int* status) const {
  if (jni_cache_) {
    JNIEnv* jenv = jni_cache_->GetEnv();
    const ScopedLocalRef<jstring> java_result(
        reinterpret_cast<jstring>(jenv->CallObjectMethod(
            matcher_.get(), jni_cache_->matcher_group_idx, group_idx)),
        jenv);
    if (jni_cache_->ExceptionCheckAndClear()) {
      *status = kError;
      TC3_LOG(ERROR) << "Exception occurred";
      return UTF8ToUnicodeText("", /*do_copy=*/false);
    }

    // java_result is nullptr when the group did not participate in the match.
    // For these cases other UniLib implementations return empty string, and
    // the participation can be checked by checking if Start() == -1.
    if (!java_result) {
      *status = kNoError;
      return UTF8ToUnicodeText("", /*do_copy=*/false);
    }

    std::string result;
    if (!JStringToUtf8String(jenv, java_result.get(), &result)) {
      *status = kError;
      return UTF8ToUnicodeText("", /*do_copy=*/false);
    }
    *status = kNoError;
    return UTF8ToUnicodeText(result, /*do_copy=*/true);
  } else {
    *status = kError;
    return UTF8ToUnicodeText("", /*do_copy=*/false);
  }
}

constexpr int UniLib::BreakIterator::kDone;

UniLib::BreakIterator::BreakIterator(const JniCache* jni_cache,
                                     const UnicodeText& text)
    : jni_cache_(jni_cache),
      text_(nullptr, jni_cache ? jni_cache->jvm : nullptr),
      iterator_(nullptr, jni_cache ? jni_cache->jvm : nullptr),
      last_break_index_(0),
      last_unicode_index_(0) {
  if (jni_cache_) {
    JNIEnv* jenv = jni_cache_->GetEnv();
    text_ = MakeGlobalRef(jni_cache_->ConvertToJavaString(text).release(), jenv,
                          jni_cache->jvm);
    if (!text_) {
      return;
    }

    iterator_ = MakeGlobalRef(
        jenv->CallStaticObjectMethod(jni_cache->breakiterator_class.get(),
                                     jni_cache->breakiterator_getwordinstance,
                                     jni_cache->locale_us.get()),
        jenv, jni_cache->jvm);
    if (!iterator_) {
      return;
    }
    jenv->CallVoidMethod(iterator_.get(), jni_cache->breakiterator_settext,
                         text_.get());
  }
}

int UniLib::BreakIterator::Next() {
  if (jni_cache_) {
    const int break_index = jni_cache_->GetEnv()->CallIntMethod(
        iterator_.get(), jni_cache_->breakiterator_next);
    if (jni_cache_->ExceptionCheckAndClear() ||
        break_index == BreakIterator::kDone) {
      return BreakIterator::kDone;
    }

    const int token_unicode_length = jni_cache_->GetEnv()->CallIntMethod(
        text_.get(), jni_cache_->string_code_point_count, last_break_index_,
        break_index);
    if (jni_cache_->ExceptionCheckAndClear()) {
      return BreakIterator::kDone;
    }

    last_break_index_ = break_index;
    return last_unicode_index_ += token_unicode_length;
  }
  return BreakIterator::kDone;
}

std::unique_ptr<UniLib::BreakIterator> UniLib::CreateBreakIterator(
    const UnicodeText& text) const {
  return std::unique_ptr<UniLib::BreakIterator>(
      new UniLib::BreakIterator(jni_cache_.get(), text));
}

}  // namespace libtextclassifier3
