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

#include "utils/strings/utf8.h"

namespace libtextclassifier3 {
bool IsValidUTF8(const char *src, int size) {
  for (int i = 0; i < size;) {
    const int char_length = ValidUTF8CharLength(src + i, size - i);
    if (char_length <= 0) {
      return false;
    }
    i += char_length;
  }
  return true;
}

int ValidUTF8CharLength(const char *src, int size) {
  // Unexpected trail byte.
  if (IsTrailByte(src[0])) {
    return -1;
  }

  const int num_codepoint_bytes = GetNumBytesForUTF8Char(&src[0]);
  if (num_codepoint_bytes <= 0 || num_codepoint_bytes > size) {
    return -1;
  }

  // Check that remaining bytes in the codepoint are trailing bytes.
  for (int k = 1; k < num_codepoint_bytes; k++) {
    if (!IsTrailByte(src[k])) {
      return -1;
    }
  }

  return num_codepoint_bytes;
}

}  // namespace libtextclassifier3
