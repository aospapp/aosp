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

#include "annotator/types.h"

namespace libtextclassifier3 {

logging::LoggingStringStream& operator<<(logging::LoggingStringStream& stream,
                                         const Token& token) {
  if (!token.is_padding) {
    return stream << "Token(\"" << token.value << "\", " << token.start << ", "
                  << token.end << ")";
  } else {
    return stream << "Token()";
  }
}

namespace {
std::string FormatMillis(int64 time_ms_utc) {
  long time_seconds = time_ms_utc / 1000;  // NOLINT
  char buffer[512];
  strftime(buffer, sizeof(buffer), "%a %Y-%m-%d %H:%M:%S %Z",
           localtime(&time_seconds));
  return std::string(buffer);
}
}  // namespace

logging::LoggingStringStream& operator<<(logging::LoggingStringStream& stream,
                                         const DatetimeParseResultSpan& value) {
  stream << "DatetimeParseResultSpan({" << value.span.first << ", "
         << value.span.second << "}, {";
  for (const DatetimeParseResult& data : value.data) {
    stream << "{/*time_ms_utc=*/ " << data.time_ms_utc << " /* "
           << FormatMillis(data.time_ms_utc) << " */, /*granularity=*/ "
           << data.granularity << "}, ";
  }
  stream << "})";
  return stream;
}

logging::LoggingStringStream& operator<<(logging::LoggingStringStream& stream,
                                         const ClassificationResult& result) {
  return stream << "ClassificationResult(" << result.collection
                << ", /*score=*/ " << result.score << ", /*priority_score=*/ "
                << result.priority_score << ")";
}

logging::LoggingStringStream& operator<<(
    logging::LoggingStringStream& stream,
    const std::vector<ClassificationResult>& results) {
  stream = stream << "{\n";
  for (const ClassificationResult& result : results) {
    stream = stream << "    " << result << "\n";
  }
  stream = stream << "}";
  return stream;
}

logging::LoggingStringStream& operator<<(logging::LoggingStringStream& stream,
                                         const AnnotatedSpan& span) {
  std::string best_class;
  float best_score = -1;
  if (!span.classification.empty()) {
    best_class = span.classification[0].collection;
    best_score = span.classification[0].score;
  }
  return stream << "Span(" << span.span.first << ", " << span.span.second
                << ", " << best_class << ", " << best_score << ")";
}

logging::LoggingStringStream& operator<<(logging::LoggingStringStream& stream,
                                         const DateParseData& data) {
  // TODO(zilka): Add human-readable form of field_set_mask and the enum fields.
  stream = stream << "DateParseData {\n";
  stream = stream << "  field_set_mask: " << data.field_set_mask << "\n";
  stream = stream << "  year: " << data.year << "\n";
  stream = stream << "  month: " << data.month << "\n";
  stream = stream << "  day_of_month: " << data.day_of_month << "\n";
  stream = stream << "  hour: " << data.hour << "\n";
  stream = stream << "  minute: " << data.minute << "\n";
  stream = stream << "  second: " << data.second << "\n";
  stream = stream << "  ampm: " << static_cast<int>(data.ampm) << "\n";
  stream = stream << "  zone_offset: " << data.zone_offset << "\n";
  stream = stream << "  dst_offset: " << data.dst_offset << "\n";
  stream = stream << "  relation: " << static_cast<int>(data.relation) << "\n";
  stream = stream << "  relation_type: " << static_cast<int>(data.relation_type)
                  << "\n";
  stream = stream << "  relation_distance: " << data.relation_distance << "\n";
  stream = stream << "}";
  return stream;
}

}  // namespace libtextclassifier3
