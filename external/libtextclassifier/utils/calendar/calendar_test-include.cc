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

#include "utils/calendar/calendar_test-include.h"

namespace libtextclassifier3 {
namespace test_internal {

TEST_F(CalendarTest, Interface) {
  int64 time;
  DatetimeGranularity granularity;
  std::string timezone;
  bool result = calendarlib_.InterpretParseData(
      DateParseData{/*field_set_mask=*/0, /*year=*/0, /*month=*/0,
                    /*day_of_month=*/0, /*hour=*/0, /*minute=*/0, /*second=*/0,
                    /*ampm=*/static_cast<DateParseData::AMPM>(0),
                    /*zone_offset=*/0, /*dst_offset=*/0,
                    static_cast<DateParseData::Relation>(0),
                    static_cast<DateParseData::RelationType>(0),
                    /*relation_distance=*/0},
      0L, "Zurich", "en-CH", &time, &granularity);
  TC3_LOG(INFO) << result;
}

TEST_F(CalendarTest, SetsZeroTimeWhenNotRelative) {
  int64 time;
  DatetimeGranularity granularity;
  DateParseData data;

  data.year = 2018;
  data.field_set_mask = DateParseData::YEAR_FIELD;
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      data,
      /*reference_time_ms_utc=*/0L, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-CH", &time, &granularity));
  EXPECT_EQ(time, 1514761200000L /* Jan 01 2018 00:00:00 */);

  ASSERT_TRUE(calendarlib_.InterpretParseData(
      data,
      /*reference_time_ms_utc=*/1L, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-CH", &time, &granularity));
  EXPECT_EQ(time, 1514761200000L /* Jan 01 2018 00:00:00 */);
}

TEST_F(CalendarTest, RoundingToGranularityBasic) {
  int64 time;
  DatetimeGranularity granularity;
  DateParseData data;

  data.year = 2018;
  data.field_set_mask = DateParseData::YEAR_FIELD;
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      data,
      /*reference_time_ms_utc=*/0L, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-CH", &time, &granularity));
  EXPECT_EQ(time, 1514761200000L /* Jan 01 2018 00:00:00 */);

  data.month = 4;
  data.field_set_mask |= DateParseData::MONTH_FIELD;
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      data,
      /*reference_time_ms_utc=*/0L, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-CH", &time, &granularity));
  EXPECT_EQ(time, 1522533600000L /* Apr 01 2018 00:00:00 */);

  data.day_of_month = 25;
  data.field_set_mask |= DateParseData::DAY_FIELD;
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      data,
      /*reference_time_ms_utc=*/0L, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-CH", &time, &granularity));
  EXPECT_EQ(time, 1524607200000L /* Apr 25 2018 00:00:00 */);

  data.hour = 9;
  data.field_set_mask |= DateParseData::HOUR_FIELD;
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      data,
      /*reference_time_ms_utc=*/0L, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-CH", &time, &granularity));
  EXPECT_EQ(time, 1524639600000L /* Apr 25 2018 09:00:00 */);

  data.minute = 33;
  data.field_set_mask |= DateParseData::MINUTE_FIELD;
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      data,
      /*reference_time_ms_utc=*/0L, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-CH", &time, &granularity));
  EXPECT_EQ(time, 1524641580000 /* Apr 25 2018 09:33:00 */);

  data.second = 59;
  data.field_set_mask |= DateParseData::SECOND_FIELD;
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      data,
      /*reference_time_ms_utc=*/0L, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-CH", &time, &granularity));
  EXPECT_EQ(time, 1524641639000 /* Apr 25 2018 09:33:59 */);
}

TEST_F(CalendarTest, RoundingToGranularityWeek) {
  int64 time;
  DatetimeGranularity granularity;
  // Prepare data structure that means: "next week"
  DateParseData data;
  data.field_set_mask =
      DateParseData::RELATION_FIELD | DateParseData::RELATION_TYPE_FIELD;
  data.relation = DateParseData::Relation::NEXT;
  data.relation_type = DateParseData::RelationType::WEEK;

  ASSERT_TRUE(calendarlib_.InterpretParseData(
      data,
      /*reference_time_ms_utc=*/0L, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"de-CH", &time, &granularity));
  EXPECT_EQ(time, 342000000L /* Mon Jan 05 1970 00:00:00 */);

  ASSERT_TRUE(calendarlib_.InterpretParseData(
      data,
      /*reference_time_ms_utc=*/0L, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-US", &time, &granularity));
  EXPECT_EQ(time, 255600000L /* Sun Jan 04 1970 00:00:00 */);
}

TEST_F(CalendarTest, RelativeTime) {
  const int field_mask = DateParseData::RELATION_FIELD |
                         DateParseData::RELATION_TYPE_FIELD |
                         DateParseData::RELATION_DISTANCE_FIELD;
  const int64 ref_time = 1524648839000L; /* 25 April 2018 09:33:59 */
  int64 time;
  DatetimeGranularity granularity;

  // Two Weds from now.
  const DateParseData future_wed_parse = {
      field_mask,
      /*year=*/0,
      /*month=*/0,
      /*day_of_month=*/0,
      /*hour=*/0,
      /*minute=*/0,
      /*second=*/0,
      static_cast<DateParseData::AMPM>(0),
      /*zone_offset=*/0,
      /*dst_offset=*/0,
      DateParseData::Relation::FUTURE,
      DateParseData::RelationType::WEDNESDAY,
      /*relation_distance=*/2};
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      future_wed_parse, ref_time, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-US", &time, &granularity));
  EXPECT_EQ(time, 1525858439000L /* Wed May 09 2018 11:33:59 */);
  EXPECT_EQ(granularity, GRANULARITY_DAY);

  // Next Wed.
  const DateParseData next_wed_parse = {field_mask,
                                        /*year=*/0,
                                        /*month=*/0,
                                        /*day_of_month=*/0,
                                        /*hour=*/0,
                                        /*minute=*/0,
                                        /*second=*/0,
                                        static_cast<DateParseData::AMPM>(0),
                                        /*zone_offset=*/0,
                                        /*dst_offset=*/0,
                                        DateParseData::Relation::NEXT,
                                        DateParseData::RelationType::WEDNESDAY,
                                        /*relation_distance=*/0};
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      next_wed_parse, ref_time, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-US", &time, &granularity));
  EXPECT_EQ(time, 1525253639000L /* Wed May 02 2018 11:33:59 */);
  EXPECT_EQ(granularity, GRANULARITY_DAY);

  // Same Wed.
  const DateParseData same_wed_parse = {field_mask,
                                        /*year=*/0,
                                        /*month=*/0,
                                        /*day_of_month=*/0,
                                        /*hour=*/0,
                                        /*minute=*/0,
                                        /*second=*/0,
                                        static_cast<DateParseData::AMPM>(0),
                                        /*zone_offset=*/0,
                                        /*dst_offset=*/0,
                                        DateParseData::Relation::NEXT_OR_SAME,
                                        DateParseData::RelationType::WEDNESDAY,
                                        /*relation_distance=*/0};
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      same_wed_parse, ref_time, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-US", &time, &granularity));
  EXPECT_EQ(time, 1524648839000L /* Wed Apr 25 2018 11:33:59 */);
  EXPECT_EQ(granularity, GRANULARITY_DAY);

  // Previous Wed.
  const DateParseData last_wed_parse = {field_mask,
                                        /*year=*/0,
                                        /*month=*/0,
                                        /*day_of_month=*/0,
                                        /*hour=*/0,
                                        /*minute=*/0,
                                        /*second=*/0,
                                        static_cast<DateParseData::AMPM>(0),
                                        /*zone_offset=*/0,
                                        /*dst_offset=*/0,
                                        DateParseData::Relation::LAST,
                                        DateParseData::RelationType::WEDNESDAY,
                                        /*relation_distance=*/0};
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      last_wed_parse, ref_time, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-US", &time, &granularity));
  EXPECT_EQ(time, 1524044039000L /* Wed Apr 18 2018 11:33:59 */);
  EXPECT_EQ(granularity, GRANULARITY_DAY);

  // Two Weds ago.
  const DateParseData past_wed_parse = {field_mask,
                                        /*year=*/0,
                                        /*month=*/0,
                                        /*day_of_month=*/0,
                                        /*hour=*/0,
                                        /*minute=*/0,
                                        /*second=*/0,
                                        static_cast<DateParseData::AMPM>(0),
                                        /*zone_offset=*/0,
                                        /*dst_offset=*/0,
                                        DateParseData::Relation::PAST,
                                        DateParseData::RelationType::WEDNESDAY,
                                        /*relation_distance=*/2};
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      past_wed_parse, ref_time, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-US", &time, &granularity));
  EXPECT_EQ(time, 1523439239000L /* Wed Apr 11 2018 11:33:59 */);
  EXPECT_EQ(granularity, GRANULARITY_DAY);

  // In 3 hours.
  const DateParseData in_3_hours_parse = {
      field_mask,
      /*year=*/0,
      /*month=*/0,
      /*day_of_month=*/0,
      /*hour=*/0,
      /*minute=*/0,
      /*second=*/0,
      /*ampm=*/static_cast<DateParseData::AMPM>(0),
      /*zone_offset=*/0,
      /*dst_offset=*/0,
      DateParseData::Relation::FUTURE,
      DateParseData::RelationType::HOUR,
      /*relation_distance=*/3};
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      in_3_hours_parse, ref_time, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-US", &time, &granularity));
  EXPECT_EQ(time, 1524659639000L /* Wed Apr 25 2018 14:33:59 */);
  EXPECT_EQ(granularity, GRANULARITY_HOUR);

  // In 5 minutes.
  const DateParseData in_5_minutes_parse = {
      field_mask,
      /*year=*/0,
      /*month=*/0,
      /*day_of_month=*/0,
      /*hour=*/0,
      /*minute=*/0,
      /*second=*/0,
      /*ampm=*/static_cast<DateParseData::AMPM>(0),
      /*zone_offset=*/0,
      /*dst_offset=*/0,
      DateParseData::Relation::FUTURE,
      DateParseData::RelationType::MINUTE,
      /*relation_distance=*/5};
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      in_5_minutes_parse, ref_time, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-US", &time, &granularity));
  EXPECT_EQ(time, 1524649139000L /* Wed Apr 25 2018 14:33:59 */);
  EXPECT_EQ(granularity, GRANULARITY_MINUTE);

  // In 10 seconds.
  const DateParseData in_10_seconds_parse = {
      field_mask,
      /*year=*/0,
      /*month=*/0,
      /*day_of_month=*/0,
      /*hour=*/0,
      /*minute=*/0,
      /*second=*/0,
      /*ampm=*/static_cast<DateParseData::AMPM>(0),
      /*zone_offset=*/0,
      /*dst_offset=*/0,
      DateParseData::Relation::FUTURE,
      DateParseData::RelationType::SECOND,
      /*relation_distance=*/10};
  ASSERT_TRUE(calendarlib_.InterpretParseData(
      in_10_seconds_parse, ref_time, /*reference_timezone=*/"Europe/Zurich",
      /*reference_locale=*/"en-US", &time, &granularity));
  EXPECT_EQ(time, 1524648849000L /* Wed Apr 25 2018 14:33:59 */);
  EXPECT_EQ(granularity, GRANULARITY_SECOND);
}

}  // namespace test_internal
}  // namespace libtextclassifier3
