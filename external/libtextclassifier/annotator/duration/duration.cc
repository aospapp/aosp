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

#include "annotator/duration/duration.h"

#include <climits>
#include <cstdlib>

#include "annotator/collections.h"
#include "annotator/types.h"
#include "utils/base/logging.h"
#include "utils/strings/numbers.h"

namespace libtextclassifier3 {

using DurationUnit = internal::DurationUnit;

namespace internal {

namespace {
void FillDurationUnitMap(
    const flatbuffers::Vector<flatbuffers::Offset<flatbuffers::String>>*
        expressions,
    DurationUnit duration_unit,
    std::unordered_map<std::string, DurationUnit>* target_map) {
  if (expressions == nullptr) {
    return;
  }

  for (const flatbuffers::String* expression_string : *expressions) {
    (*target_map)[expression_string->c_str()] = duration_unit;
  }
}
}  // namespace

std::unordered_map<std::string, DurationUnit> BuildTokenToDurationUnitMapping(
    const DurationAnnotatorOptions* options) {
  std::unordered_map<std::string, DurationUnit> mapping;
  FillDurationUnitMap(options->week_expressions(), DurationUnit::WEEK,
                      &mapping);
  FillDurationUnitMap(options->day_expressions(), DurationUnit::DAY, &mapping);
  FillDurationUnitMap(options->hour_expressions(), DurationUnit::HOUR,
                      &mapping);
  FillDurationUnitMap(options->minute_expressions(), DurationUnit::MINUTE,
                      &mapping);
  FillDurationUnitMap(options->second_expressions(), DurationUnit::SECOND,
                      &mapping);
  return mapping;
}

std::unordered_set<std::string> BuildStringSet(
    const flatbuffers::Vector<flatbuffers::Offset<flatbuffers::String>>*
        strings) {
  std::unordered_set<std::string> result;
  if (strings == nullptr) {
    return result;
  }

  for (const flatbuffers::String* string_value : *strings) {
    result.insert(string_value->c_str());
  }

  return result;
}

}  // namespace internal

bool DurationAnnotator::ClassifyText(
    const UnicodeText& context, CodepointSpan selection_indices,
    AnnotationUsecase annotation_usecase,
    ClassificationResult* classification_result) const {
  if (!options_->enabled() || ((options_->enabled_annotation_usecases() &
                                (1 << annotation_usecase))) == 0) {
    return false;
  }

  const UnicodeText selection =
      UnicodeText::Substring(context, selection_indices.first,
                             selection_indices.second, /*do_copy=*/false);
  const std::vector<Token> tokens = feature_processor_->Tokenize(selection);

  AnnotatedSpan annotated_span;
  if (FindDurationStartingAt(context, tokens, 0, &annotated_span) !=
      tokens.size()) {
    return false;
  }

  TC3_CHECK(!annotated_span.classification.empty());

  *classification_result = annotated_span.classification[0];
  return true;
}

bool DurationAnnotator::FindAll(const UnicodeText& context,
                                const std::vector<Token>& tokens,
                                AnnotationUsecase annotation_usecase,
                                std::vector<AnnotatedSpan>* results) const {
  if (!options_->enabled() || ((options_->enabled_annotation_usecases() &
                                (1 << annotation_usecase))) == 0) {
    return true;
  }

  for (int i = 0; i < tokens.size();) {
    AnnotatedSpan span;
    const int next_i = FindDurationStartingAt(context, tokens, i, &span);
    if (next_i != i) {
      results->push_back(span);
      i = next_i;
    } else {
      i++;
    }
  }
  return true;
}

int DurationAnnotator::FindDurationStartingAt(const UnicodeText& context,
                                              const std::vector<Token>& tokens,
                                              int start_token_index,
                                              AnnotatedSpan* result) const {
  CodepointIndex start_index = kInvalidIndex;
  CodepointIndex end_index = kInvalidIndex;

  bool has_quantity = false;
  ParsedDurationAtom parsed_duration;

  std::vector<ParsedDurationAtom> parsed_duration_atoms;

  // This is the core algorithm for finding the duration expressions. It
  // basically iterates over tokens and changes the state variables above as it
  // goes.
  int token_index;
  for (token_index = start_token_index; token_index < tokens.size();
       token_index++) {
    const Token& token = tokens[token_index];

    if (ParseQuantityToken(token, &parsed_duration)) {
      has_quantity = true;
      if (start_index == kInvalidIndex) {
        start_index = token.start;
      }
      end_index = token.end;
    } else if (ParseDurationUnitToken(token, &parsed_duration.unit)) {
      if (start_index == kInvalidIndex) {
        start_index = token.start;
      }
      end_index = token.end;
      parsed_duration_atoms.push_back(parsed_duration);
      has_quantity = false;
      parsed_duration = ParsedDurationAtom();
    } else if (ParseFillerToken(token)) {
    } else {
      break;
    }
  }

  if (parsed_duration_atoms.empty()) {
    return start_token_index;
  }

  const bool parse_ended_without_unit_for_last_mentioned_quantity =
      has_quantity;

  ClassificationResult classification{Collections::Duration(),
                                      options_->score()};
  classification.priority_score = options_->priority_score();
  classification.duration_ms =
      ParsedDurationAtomsToMillis(parsed_duration_atoms);

  // Process suffix expressions like "and half" that don't have the
  // duration_unit explicitly mentioned.
  if (parse_ended_without_unit_for_last_mentioned_quantity &&
      parsed_duration.plus_half) {
    ParsedDurationAtom atom = ParsedDurationAtom::Half();
    atom.unit = parsed_duration_atoms.rbegin()->unit;
    classification.duration_ms += ParsedDurationAtomsToMillis({atom});
  }

  result->span = feature_processor_->StripBoundaryCodepoints(
      context, {start_index, end_index});
  result->classification.push_back(classification);
  result->source = AnnotatedSpan::Source::DURATION;

  return token_index;
}

int64 DurationAnnotator::ParsedDurationAtomsToMillis(
    const std::vector<ParsedDurationAtom>& atoms) const {
  int64 result = 0;
  for (auto atom : atoms) {
    int multiplier;
    switch (atom.unit) {
      case DurationUnit::WEEK:
        multiplier = 7 * 24 * 60 * 60 * 1000;
        break;
      case DurationUnit::DAY:
        multiplier = 24 * 60 * 60 * 1000;
        break;
      case DurationUnit::HOUR:
        multiplier = 60 * 60 * 1000;
        break;
      case DurationUnit::MINUTE:
        multiplier = 60 * 1000;
        break;
      case DurationUnit::SECOND:
        multiplier = 1000;
        break;
      case DurationUnit::UNKNOWN:
        TC3_LOG(ERROR) << "Requesting parse of UNKNOWN duration duration_unit.";
        return -1;
        break;
    }

    int value = atom.value;
    // This condition handles expressions like "an hour", where the quantity is
    // not specified. In this case we assume quantity 1. Except for cases like
    // "half hour".
    if (value == 0 && !atom.plus_half) {
      value = 1;
    }
    result += value * multiplier;
    result += atom.plus_half * multiplier / 2;
  }
  return result;
}

bool DurationAnnotator::ParseQuantityToken(const Token& token,
                                           ParsedDurationAtom* value) const {
  if (token.value.empty()) {
    return false;
  }

  std::string token_value_buffer;
  const std::string& token_value = feature_processor_->StripBoundaryCodepoints(
      token.value, &token_value_buffer);

  if (half_expressions_.find(token_value) != half_expressions_.end()) {
    value->plus_half = true;
    return true;
  }

  int32 parsed_value;
  if (ParseInt32(token_value.c_str(), &parsed_value)) {
    value->value = parsed_value;
    return true;
  }

  return false;
}

bool DurationAnnotator::ParseDurationUnitToken(
    const Token& token, DurationUnit* duration_unit) const {
  std::string token_value_buffer;
  const std::string& token_value = feature_processor_->StripBoundaryCodepoints(
      token.value, &token_value_buffer);

  const auto it = token_value_to_duration_unit_.find(token_value);
  if (it == token_value_to_duration_unit_.end()) {
    return false;
  }

  *duration_unit = it->second;
  return true;
}

bool DurationAnnotator::ParseFillerToken(const Token& token) const {
  std::string token_value_buffer;
  const std::string& token_value = feature_processor_->StripBoundaryCodepoints(
      token.value, &token_value_buffer);

  if (filler_expressions_.find(token_value) == filler_expressions_.end()) {
    return false;
  }

  return true;
}

}  // namespace libtextclassifier3
