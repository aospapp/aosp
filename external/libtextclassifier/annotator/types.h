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

#ifndef LIBTEXTCLASSIFIER_ANNOTATOR_TYPES_H_
#define LIBTEXTCLASSIFIER_ANNOTATOR_TYPES_H_

#include <time.h>
#include <algorithm>
#include <cmath>
#include <functional>
#include <map>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "annotator/entity-data_generated.h"
#include "utils/base/integral_types.h"
#include "utils/base/logging.h"
#include "utils/flatbuffers.h"
#include "utils/variant.h"

namespace libtextclassifier3 {

constexpr int kInvalidIndex = -1;

// Index for a 0-based array of tokens.
using TokenIndex = int;

// Index for a 0-based array of codepoints.
using CodepointIndex = int;

// Marks a span in a sequence of codepoints. The first element is the index of
// the first codepoint of the span, and the second element is the index of the
// codepoint one past the end of the span.
// TODO(b/71982294): Make it a struct.
using CodepointSpan = std::pair<CodepointIndex, CodepointIndex>;

inline bool SpansOverlap(const CodepointSpan& a, const CodepointSpan& b) {
  return a.first < b.second && b.first < a.second;
}

inline bool ValidNonEmptySpan(const CodepointSpan& span) {
  return span.first < span.second && span.first >= 0 && span.second >= 0;
}

template <typename T>
bool DoesCandidateConflict(
    const int considered_candidate, const std::vector<T>& candidates,
    const std::set<int, std::function<bool(int, int)>>& chosen_indices_set) {
  if (chosen_indices_set.empty()) {
    return false;
  }

  auto conflicting_it = chosen_indices_set.lower_bound(considered_candidate);
  // Check conflict on the right.
  if (conflicting_it != chosen_indices_set.end() &&
      SpansOverlap(candidates[considered_candidate].span,
                   candidates[*conflicting_it].span)) {
    return true;
  }

  // Check conflict on the left.
  // If we can't go more left, there can't be a conflict:
  if (conflicting_it == chosen_indices_set.begin()) {
    return false;
  }
  // Otherwise move one span left and insert if it doesn't overlap with the
  // candidate.
  --conflicting_it;
  if (!SpansOverlap(candidates[considered_candidate].span,
                    candidates[*conflicting_it].span)) {
    return false;
  }

  return true;
}

// Marks a span in a sequence of tokens. The first element is the index of the
// first token in the span, and the second element is the index of the token one
// past the end of the span.
// TODO(b/71982294): Make it a struct.
using TokenSpan = std::pair<TokenIndex, TokenIndex>;

// Returns the size of the token span. Assumes that the span is valid.
inline int TokenSpanSize(const TokenSpan& token_span) {
  return token_span.second - token_span.first;
}

// Returns a token span consisting of one token.
inline TokenSpan SingleTokenSpan(int token_index) {
  return {token_index, token_index + 1};
}

// Returns an intersection of two token spans. Assumes that both spans are valid
// and overlapping.
inline TokenSpan IntersectTokenSpans(const TokenSpan& token_span1,
                                     const TokenSpan& token_span2) {
  return {std::max(token_span1.first, token_span2.first),
          std::min(token_span1.second, token_span2.second)};
}

// Returns and expanded token span by adding a certain number of tokens on its
// left and on its right.
inline TokenSpan ExpandTokenSpan(const TokenSpan& token_span,
                                 int num_tokens_left, int num_tokens_right) {
  return {token_span.first - num_tokens_left,
          token_span.second + num_tokens_right};
}

// Token holds a token, its position in the original string and whether it was
// part of the input span.
struct Token {
  std::string value;
  CodepointIndex start;
  CodepointIndex end;

  // Whether the token is a padding token.
  bool is_padding;

  // Default constructor constructs the padding-token.
  Token()
      : value(""), start(kInvalidIndex), end(kInvalidIndex), is_padding(true) {}

  Token(const std::string& arg_value, CodepointIndex arg_start,
        CodepointIndex arg_end)
      : value(arg_value), start(arg_start), end(arg_end), is_padding(false) {}

  bool operator==(const Token& other) const {
    return value == other.value && start == other.start && end == other.end &&
           is_padding == other.is_padding;
  }

  bool IsContainedInSpan(CodepointSpan span) const {
    return start >= span.first && end <= span.second;
  }
};

// Pretty-printing function for Token.
logging::LoggingStringStream& operator<<(logging::LoggingStringStream& stream,
                                         const Token& token);

enum DatetimeGranularity {
  GRANULARITY_UNKNOWN = -1,  // GRANULARITY_UNKNOWN is used as a proxy for this
                             // structure being uninitialized.
  GRANULARITY_YEAR = 0,
  GRANULARITY_MONTH = 1,
  GRANULARITY_WEEK = 2,
  GRANULARITY_DAY = 3,
  GRANULARITY_HOUR = 4,
  GRANULARITY_MINUTE = 5,
  GRANULARITY_SECOND = 6
};

struct DatetimeParseResult {
  // The absolute time in milliseconds since the epoch in UTC.
  int64 time_ms_utc;

  // The precision of the estimate then in to calculating the milliseconds
  DatetimeGranularity granularity;

  DatetimeParseResult() : time_ms_utc(0), granularity(GRANULARITY_UNKNOWN) {}

  DatetimeParseResult(int64 arg_time_ms_utc,
                      DatetimeGranularity arg_granularity)
      : time_ms_utc(arg_time_ms_utc), granularity(arg_granularity) {}

  bool IsSet() const { return granularity != GRANULARITY_UNKNOWN; }

  bool operator==(const DatetimeParseResult& other) const {
    return granularity == other.granularity && time_ms_utc == other.time_ms_utc;
  }
};

const float kFloatCompareEpsilon = 1e-5;

struct DatetimeParseResultSpan {
  CodepointSpan span;
  std::vector<DatetimeParseResult> data;
  float target_classification_score;
  float priority_score;

  bool operator==(const DatetimeParseResultSpan& other) const {
    return span == other.span && data == other.data &&
           std::abs(target_classification_score -
                    other.target_classification_score) < kFloatCompareEpsilon &&
           std::abs(priority_score - other.priority_score) <
               kFloatCompareEpsilon;
  }
};

// Pretty-printing function for DatetimeParseResultSpan.
logging::LoggingStringStream& operator<<(logging::LoggingStringStream& stream,
                                         const DatetimeParseResultSpan& value);

struct ClassificationResult {
  std::string collection;
  float score;
  DatetimeParseResult datetime_parse_result;
  std::string serialized_knowledge_result;
  std::string contact_name, contact_given_name, contact_nickname,
      contact_email_address, contact_phone_number, contact_id;
  std::string app_name, app_package_name;
  int64 numeric_value;

  // Length of the parsed duration in milliseconds.
  int64 duration_ms;

  // Internal score used for conflict resolution.
  float priority_score;


  // Entity data information.
  std::string serialized_entity_data;
  const EntityData* entity_data() {
    return LoadAndVerifyFlatbuffer<EntityData>(serialized_entity_data.data(),
                                               serialized_entity_data.size());
  }

  explicit ClassificationResult() : score(-1.0f), priority_score(-1.0) {}

  ClassificationResult(const std::string& arg_collection, float arg_score)
      : collection(arg_collection),
        score(arg_score),
        priority_score(arg_score) {}

  ClassificationResult(const std::string& arg_collection, float arg_score,
                       float arg_priority_score)
      : collection(arg_collection),
        score(arg_score),
        priority_score(arg_priority_score) {}
};

// Pretty-printing function for ClassificationResult.
logging::LoggingStringStream& operator<<(logging::LoggingStringStream& stream,
                                         const ClassificationResult& result);

// Pretty-printing function for std::vector<ClassificationResult>.
logging::LoggingStringStream& operator<<(
    logging::LoggingStringStream& stream,
    const std::vector<ClassificationResult>& results);

// Represents a result of Annotate call.
struct AnnotatedSpan {
  enum class Source { OTHER, KNOWLEDGE, DURATION, DATETIME };

  // Unicode codepoint indices in the input string.
  CodepointSpan span = {kInvalidIndex, kInvalidIndex};

  // Classification result for the span.
  std::vector<ClassificationResult> classification;

  // The source of the annotation, used in conflict resolution.
  Source source = Source::OTHER;

  AnnotatedSpan() = default;

  AnnotatedSpan(CodepointSpan arg_span,
                std::vector<ClassificationResult> arg_classification)
      : span(arg_span), classification(std::move(arg_classification)) {}
};

// Pretty-printing function for AnnotatedSpan.
logging::LoggingStringStream& operator<<(logging::LoggingStringStream& stream,
                                         const AnnotatedSpan& span);

// StringPiece analogue for std::vector<T>.
template <class T>
class VectorSpan {
 public:
  VectorSpan() : begin_(), end_() {}
  VectorSpan(const std::vector<T>& v)  // NOLINT(runtime/explicit)
      : begin_(v.begin()), end_(v.end()) {}
  VectorSpan(typename std::vector<T>::const_iterator begin,
             typename std::vector<T>::const_iterator end)
      : begin_(begin), end_(end) {}

  const T& operator[](typename std::vector<T>::size_type i) const {
    return *(begin_ + i);
  }

  int size() const { return end_ - begin_; }
  typename std::vector<T>::const_iterator begin() const { return begin_; }
  typename std::vector<T>::const_iterator end() const { return end_; }
  const float* data() const { return &(*begin_); }

 private:
  typename std::vector<T>::const_iterator begin_;
  typename std::vector<T>::const_iterator end_;
};

struct DateParseData {
  enum class Relation {
    UNSPECIFIED = 0,
    NEXT = 1,
    NEXT_OR_SAME = 2,
    LAST = 3,
    NOW = 4,
    TOMORROW = 5,
    YESTERDAY = 6,
    PAST = 7,
    FUTURE = 8
  };

  enum class RelationType {
    UNSPECIFIED = 0,
    SUNDAY = 1,
    MONDAY = 2,
    TUESDAY = 3,
    WEDNESDAY = 4,
    THURSDAY = 5,
    FRIDAY = 6,
    SATURDAY = 7,
    DAY = 8,
    WEEK = 9,
    MONTH = 10,
    YEAR = 11,
    HOUR = 12,
    MINUTE = 13,
    SECOND = 14,
  };

  enum Fields {
    YEAR_FIELD = 1 << 0,
    MONTH_FIELD = 1 << 1,
    DAY_FIELD = 1 << 2,
    HOUR_FIELD = 1 << 3,
    MINUTE_FIELD = 1 << 4,
    SECOND_FIELD = 1 << 5,
    AMPM_FIELD = 1 << 6,
    ZONE_OFFSET_FIELD = 1 << 7,
    DST_OFFSET_FIELD = 1 << 8,
    RELATION_FIELD = 1 << 9,
    RELATION_TYPE_FIELD = 1 << 10,
    RELATION_DISTANCE_FIELD = 1 << 11
  };

  enum class AMPM { AM = 0, PM = 1 };

  enum class TimeUnit {
    DAYS = 1,
    WEEKS = 2,
    MONTHS = 3,
    HOURS = 4,
    MINUTES = 5,
    SECONDS = 6,
    YEARS = 7
  };

  // Bit mask of fields which have been set on the struct
  int field_set_mask = 0;

  // Fields describing absolute date fields.
  // Year of the date seen in the text match.
  int year = 0;
  // Month of the year starting with January = 1.
  int month = 0;
  // Day of the month starting with 1.
  int day_of_month = 0;
  // Hour of the day with a range of 0-23,
  // values less than 12 need the AMPM field below or heuristics
  // to definitively determine the time.
  int hour = 0;
  // Hour of the day with a range of 0-59.
  int minute = 0;
  // Hour of the day with a range of 0-59.
  int second = 0;
  // 0 == AM, 1 == PM
  AMPM ampm = AMPM::AM;
  // Number of hours offset from UTC this date time is in.
  int zone_offset = 0;
  // Number of hours offest for DST
  int dst_offset = 0;

  // The permutation from now that was made to find the date time.
  Relation relation = Relation::UNSPECIFIED;
  // The unit of measure of the change to the date time.
  RelationType relation_type = RelationType::UNSPECIFIED;
  // The number of units of change that were made.
  int relation_distance = 0;

  DateParseData() = default;

  DateParseData(int field_set_mask, int year, int month, int day_of_month,
                int hour, int minute, int second, AMPM ampm, int zone_offset,
                int dst_offset, Relation relation, RelationType relation_type,
                int relation_distance) {
    this->field_set_mask = field_set_mask;
    this->year = year;
    this->month = month;
    this->day_of_month = day_of_month;
    this->hour = hour;
    this->minute = minute;
    this->second = second;
    this->ampm = ampm;
    this->zone_offset = zone_offset;
    this->dst_offset = dst_offset;
    this->relation = relation;
    this->relation_type = relation_type;
    this->relation_distance = relation_distance;
  }
};

// Pretty-printing function for DateParseData.
logging::LoggingStringStream& operator<<(logging::LoggingStringStream& stream,
                                         const DateParseData& data);

}  // namespace libtextclassifier3

#endif  // LIBTEXTCLASSIFIER_ANNOTATOR_TYPES_H_
