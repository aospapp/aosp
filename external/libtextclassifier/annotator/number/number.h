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

#ifndef LIBTEXTCLASSIFIER_ANNOTATOR_NUMBER_NUMBER_H_
#define LIBTEXTCLASSIFIER_ANNOTATOR_NUMBER_NUMBER_H_

#include <string>
#include <unordered_set>
#include <vector>

#include "annotator/feature-processor.h"
#include "annotator/model_generated.h"
#include "annotator/types.h"
#include "utils/utf8/unicodetext.h"

namespace libtextclassifier3 {

// Annotator of numbers in text.
//
// Only supports values in range [-999 999 999, 999 999 999] (inclusive).
//
// TODO(zilka): Add support for non-ASCII digits.
// TODO(zilka): Add support for written-out numbers.
class NumberAnnotator {
 public:
  explicit NumberAnnotator(const NumberAnnotatorOptions* options,
                           const FeatureProcessor* feature_processor)
      : options_(options),
        feature_processor_(feature_processor),
        allowed_prefix_codepoints_(
            FlatbuffersVectorToSet(options->allowed_prefix_codepoints())),
        allowed_suffix_codepoints_(
            FlatbuffersVectorToSet(options->allowed_suffix_codepoints())) {}

  // Classifies given text, and if it is a number, it passes the result in
  // 'classification_result' and returns true, otherwise returns false.
  bool ClassifyText(const UnicodeText& context, CodepointSpan selection_indices,
                    AnnotationUsecase annotation_usecase,
                    ClassificationResult* classification_result) const;

  // Finds all number instances in the input text.
  bool FindAll(const UnicodeText& context_unicode,
               AnnotationUsecase annotation_usecase,
               std::vector<AnnotatedSpan>* result) const;

 private:
  static std::unordered_set<int> FlatbuffersVectorToSet(
      const flatbuffers::Vector<int32_t>* codepoints);

  // Parses the text to an int64 value and returns true if succeeded, otherwise
  // false. Also returns the number of prefix/suffix codepoints that were
  // stripped from the number.
  bool ParseNumber(const UnicodeText& text, int64* result,
                   int* num_prefix_codepoints,
                   int* num_suffix_codepoints) const;

  const NumberAnnotatorOptions* options_;
  const FeatureProcessor* feature_processor_;
  const std::unordered_set<int> allowed_prefix_codepoints_;
  const std::unordered_set<int> allowed_suffix_codepoints_;
};

}  // namespace libtextclassifier3

#endif  // LIBTEXTCLASSIFIER_ANNOTATOR_NUMBER_NUMBER_H_
