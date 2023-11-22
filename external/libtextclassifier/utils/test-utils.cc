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

#include "utils/test-utils.h"

#include <iterator>

#include "utils/strings/split.h"
#include "utils/strings/stringpiece.h"

namespace libtextclassifier3 {

using libtextclassifier3::Token;

// Returns a list of Tokens for given input string. Can't handle non-ASCII
// input.
std::vector<Token> TokenizeAsciiOnSpace(const std::string& text) {
  std::vector<Token> result;
  for (const StringPiece token : strings::Split(text, ' ')) {
    const int start_offset = std::distance(text.data(), token.data());
    const int token_length = token.length();
    result.push_back(
        Token{token.ToString(), start_offset, start_offset + token_length});
  }
  return result;
}

}  // namespace  libtextclassifier3
