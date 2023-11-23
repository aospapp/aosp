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

#ifndef LIBTEXTCLASSIFIER_UTILS_SENTENCEPIECE_MATCHER_H_
#define LIBTEXTCLASSIFIER_UTILS_SENTENCEPIECE_MATCHER_H_

#include <vector>
#include "utils/strings/stringpiece.h"

namespace libtextclassifier3 {

struct TrieMatch {
  TrieMatch() {}
  TrieMatch(int id, int match_length) : id(id), match_length(match_length) {}
  int id = -1;
  int match_length = -1;
};

class SentencePieceMatcher {
 public:
  virtual ~SentencePieceMatcher() {}

  // Find matches that are prefixes of a string.
  virtual bool FindAllPrefixMatches(StringPiece input,
                                    std::vector<TrieMatch>* matches) const = 0;

  // Find the longest prefix match of a string.
  virtual bool LongestPrefixMatch(StringPiece input,
                                  TrieMatch* longest_match) const = 0;
};

}  // namespace libtextclassifier3

#endif  // LIBTEXTCLASSIFIER_UTILS_SENTENCEPIECE_MATCHER_H_
