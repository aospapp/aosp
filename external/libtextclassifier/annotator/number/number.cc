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

#include "annotator/number/number.h"

#include <climits>
#include <cstdlib>

#include "annotator/collections.h"
#include "utils/base/logging.h"

namespace libtextclassifier3 {

bool NumberAnnotator::ClassifyText(
    const UnicodeText& context, CodepointSpan selection_indices,
    AnnotationUsecase annotation_usecase,
    ClassificationResult* classification_result) const {
  int64 parsed_value;
  int num_prefix_codepoints;
  int num_suffix_codepoints;
  if (ParseNumber(UnicodeText::Substring(context, selection_indices.first,
                                         selection_indices.second),
                  &parsed_value, &num_prefix_codepoints,
                  &num_suffix_codepoints)) {
    ClassificationResult classification{Collections::Number(), 1.0};
    TC3_CHECK(classification_result != nullptr);
    classification_result->collection = Collections::Number();
    classification_result->score = options_->score();
    classification_result->priority_score = options_->priority_score();
    classification_result->numeric_value = parsed_value;
    return true;
  }
  return false;
}

bool NumberAnnotator::FindAll(const UnicodeText& context,
                              AnnotationUsecase annotation_usecase,
                              std::vector<AnnotatedSpan>* result) const {
  if (!options_->enabled() || ((1 << annotation_usecase) &
                               options_->enabled_annotation_usecases()) == 0) {
    return true;
  }

  const std::vector<Token> tokens = feature_processor_->Tokenize(context);
  for (const Token& token : tokens) {
    const UnicodeText token_text =
        UTF8ToUnicodeText(token.value, /*do_copy=*/false);
    int64 parsed_value;
    int num_prefix_codepoints;
    int num_suffix_codepoints;
    if (ParseNumber(token_text, &parsed_value, &num_prefix_codepoints,
                    &num_suffix_codepoints)) {
      ClassificationResult classification{Collections::Number(),
                                          options_->score()};
      classification.numeric_value = parsed_value;
      classification.priority_score = options_->priority_score();

      AnnotatedSpan annotated_span;
      annotated_span.span = {token.start + num_prefix_codepoints,
                             token.end - num_suffix_codepoints};
      annotated_span.classification.push_back(classification);

      result->push_back(annotated_span);
    }
  }

  return true;
}

std::unordered_set<int> NumberAnnotator::FlatbuffersVectorToSet(
    const flatbuffers::Vector<int32_t>* codepoints) {
  if (codepoints == nullptr) {
    return std::unordered_set<int>{};
  }

  std::unordered_set<int> result;
  for (const int codepoint : *codepoints) {
    result.insert(codepoint);
  }
  return result;
}

namespace {
UnicodeText::const_iterator ConsumeAndParseNumber(
    const UnicodeText::const_iterator& it_begin,
    const UnicodeText::const_iterator& it_end, int64* result) {
  *result = 0;

  // See if there's a sign in the beginning of the number.
  int sign = 1;
  auto it = it_begin;
  if (it != it_end) {
    if (*it == '-') {
      ++it;
      sign = -1;
    } else if (*it == '+') {
      ++it;
      sign = 1;
    }
  }

  while (it != it_end) {
    if (*it >= '0' && *it <= '9') {
      // When overflow is imminent we'll fail to parse the number.
      if (*result > INT64_MAX / 10) {
        return it_begin;
      }
      *result *= 10;
      *result += *it - '0';
    } else {
      *result *= sign;
      return it;
    }

    ++it;
  }

  *result *= sign;
  return it_end;
}
}  // namespace

bool NumberAnnotator::ParseNumber(const UnicodeText& text, int64* result,
                                  int* num_prefix_codepoints,
                                  int* num_suffix_codepoints) const {
  TC3_CHECK(result != nullptr && num_prefix_codepoints != nullptr &&
            num_suffix_codepoints != nullptr);
  auto it = text.begin();
  auto it_end = text.end();

  // Strip boundary codepoints from both ends.
  const CodepointSpan original_span{0, text.size_codepoints()};
  const CodepointSpan stripped_span =
      feature_processor_->StripBoundaryCodepoints(text, original_span);
  const int num_stripped_end = (original_span.second - stripped_span.second);
  std::advance(it, stripped_span.first);
  std::advance(it_end, -num_stripped_end);

  // Consume prefix codepoints.
  *num_prefix_codepoints = stripped_span.first;
  while (it != text.end()) {
    if (allowed_prefix_codepoints_.find(*it) ==
        allowed_prefix_codepoints_.end()) {
      break;
    }

    ++it;
    ++(*num_prefix_codepoints);
  }

  auto it_start = it;
  it = ConsumeAndParseNumber(it, text.end(), result);
  if (it == it_start) {
    return false;
  }

  // Consume suffix codepoints.
  bool valid_suffix = true;
  *num_suffix_codepoints = 0;
  while (it != it_end) {
    if (allowed_suffix_codepoints_.find(*it) ==
        allowed_suffix_codepoints_.end()) {
      valid_suffix = false;
      break;
    }

    ++it;
    ++(*num_suffix_codepoints);
  }
  *num_suffix_codepoints += num_stripped_end;
  return valid_suffix;
}

}  // namespace libtextclassifier3
