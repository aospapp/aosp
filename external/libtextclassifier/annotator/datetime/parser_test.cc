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

#include <time.h>
#include <fstream>
#include <iostream>
#include <memory>
#include <string>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "annotator/annotator.h"
#include "annotator/datetime/parser.h"
#include "annotator/model_generated.h"
#include "annotator/types-test-util.h"
#include "utils/testing/annotator.h"

using testing::ElementsAreArray;

namespace libtextclassifier3 {
namespace {

std::string GetModelPath() {
  return TC3_TEST_DATA_DIR;
}

std::string ReadFile(const std::string& file_name) {
  std::ifstream file_stream(file_name);
  return std::string(std::istreambuf_iterator<char>(file_stream), {});
}

class ParserTest : public testing::Test {
 public:
  void SetUp() override {
    // Loads default unmodified model. Individual tests can call LoadModel to
    // make changes.
    LoadModel([](ModelT* model) {});
  }

  template <typename Fn>
  void LoadModel(Fn model_visitor_fn) {
    std::string model_buffer = ReadFile(GetModelPath() + "test_model.fb");
    model_buffer_ = ModifyAnnotatorModel(model_buffer, model_visitor_fn);
    classifier_ = Annotator::FromUnownedBuffer(model_buffer_.data(),
                                               model_buffer_.size(), &unilib_);
    TC3_CHECK(classifier_);
    parser_ = classifier_->DatetimeParserForTests();
    TC3_CHECK(parser_);
  }

  bool HasNoResult(const std::string& text, bool anchor_start_end = false,
                   const std::string& timezone = "Europe/Zurich",
                   AnnotationUsecase annotation_usecase =
                       AnnotationUsecase_ANNOTATION_USECASE_SMART) {
    std::vector<DatetimeParseResultSpan> results;
    if (!parser_->Parse(text, 0, timezone, /*locales=*/"", ModeFlag_ANNOTATION,
                        annotation_usecase, anchor_start_end, &results)) {
      TC3_LOG(ERROR) << text;
      TC3_CHECK(false);
    }
    return results.empty();
  }

  bool ParsesCorrectly(const std::string& marked_text,
                       const std::vector<int64>& expected_ms_utcs,
                       DatetimeGranularity expected_granularity,
                       bool anchor_start_end = false,
                       const std::string& timezone = "Europe/Zurich",
                       const std::string& locales = "en-US",
                       AnnotationUsecase annotation_usecase =
                           AnnotationUsecase_ANNOTATION_USECASE_SMART) {
    const UnicodeText marked_text_unicode =
        UTF8ToUnicodeText(marked_text, /*do_copy=*/false);
    auto brace_open_it =
        std::find(marked_text_unicode.begin(), marked_text_unicode.end(), '{');
    auto brace_end_it =
        std::find(marked_text_unicode.begin(), marked_text_unicode.end(), '}');
    TC3_CHECK(brace_open_it != marked_text_unicode.end());
    TC3_CHECK(brace_end_it != marked_text_unicode.end());

    std::string text;
    text +=
        UnicodeText::UTF8Substring(marked_text_unicode.begin(), brace_open_it);
    text += UnicodeText::UTF8Substring(std::next(brace_open_it), brace_end_it);
    text += UnicodeText::UTF8Substring(std::next(brace_end_it),
                                       marked_text_unicode.end());

    std::vector<DatetimeParseResultSpan> results;

    if (!parser_->Parse(text, 0, timezone, locales, ModeFlag_ANNOTATION,
                        annotation_usecase, anchor_start_end, &results)) {
      TC3_LOG(ERROR) << text;
      TC3_CHECK(false);
    }
    if (results.empty()) {
      TC3_LOG(ERROR) << "No results.";
      return false;
    }

    const int expected_start_index =
        std::distance(marked_text_unicode.begin(), brace_open_it);
    // The -1 bellow is to account for the opening bracket character.
    const int expected_end_index =
        std::distance(marked_text_unicode.begin(), brace_end_it) - 1;

    std::vector<DatetimeParseResultSpan> filtered_results;
    for (const DatetimeParseResultSpan& result : results) {
      if (SpansOverlap(result.span,
                       {expected_start_index, expected_end_index})) {
        filtered_results.push_back(result);
      }
    }

    std::vector<DatetimeParseResultSpan> expected{
        {{expected_start_index, expected_end_index},
         {},
         /*target_classification_score=*/1.0,
         /*priority_score=*/0.1}};
    expected[0].data.resize(expected_ms_utcs.size());
    for (int i = 0; i < expected_ms_utcs.size(); i++) {
      expected[0].data[i] = {expected_ms_utcs[i], expected_granularity};
    }

    const bool matches =
        testing::Matches(ElementsAreArray(expected))(filtered_results);
    if (!matches) {
      TC3_LOG(ERROR) << "Expected: " << expected[0];
      if (filtered_results.empty()) {
        TC3_LOG(ERROR) << "But got no results.";
      }
      TC3_LOG(ERROR) << "Actual: " << filtered_results[0];
    }

    return matches;
  }

  bool ParsesCorrectly(const std::string& marked_text,
                       const int64 expected_ms_utc,
                       DatetimeGranularity expected_granularity,
                       bool anchor_start_end = false,
                       const std::string& timezone = "Europe/Zurich",
                       const std::string& locales = "en-US",
                       AnnotationUsecase annotation_usecase =
                           AnnotationUsecase_ANNOTATION_USECASE_SMART) {
    return ParsesCorrectly(marked_text, std::vector<int64>{expected_ms_utc},
                           expected_granularity, anchor_start_end, timezone,
                           locales, annotation_usecase);
  }

  bool ParsesCorrectlyGerman(const std::string& marked_text,
                             const std::vector<int64>& expected_ms_utcs,
                             DatetimeGranularity expected_granularity) {
    return ParsesCorrectly(marked_text, expected_ms_utcs, expected_granularity,
                           /*anchor_start_end=*/false,
                           /*timezone=*/"Europe/Zurich", /*locales=*/"de");
  }

  bool ParsesCorrectlyGerman(const std::string& marked_text,
                             const int64 expected_ms_utc,
                             DatetimeGranularity expected_granularity) {
    return ParsesCorrectly(marked_text, expected_ms_utc, expected_granularity,
                           /*anchor_start_end=*/false,
                           /*timezone=*/"Europe/Zurich", /*locales=*/"de");
  }

 protected:
  std::string model_buffer_;
  std::unique_ptr<Annotator> classifier_;
  const DatetimeParser* parser_;
  UniLib unilib_;
};

// Test with just a few cases to make debugging of general failures easier.
TEST_F(ParserTest, ParseShort) {
  EXPECT_TRUE(
      ParsesCorrectly("{January 1, 1988}", 567990000000, GRANULARITY_DAY));
}

TEST_F(ParserTest, Parse) {
  EXPECT_TRUE(
      ParsesCorrectly("{January 1, 1988}", 567990000000, GRANULARITY_DAY));
  EXPECT_TRUE(
      ParsesCorrectly("{january 31 2018}", 1517353200000, GRANULARITY_DAY));
  EXPECT_TRUE(ParsesCorrectly("lorem {1 january 2018} ipsum", 1514761200000,
                              GRANULARITY_DAY));
  EXPECT_TRUE(ParsesCorrectly("{09/Mar/2004 22:02:40}", 1078866160000,
                              GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly("{Dec 2, 2010 2:39:58 AM}", 1291253998000,
                              GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly("{Jun 09 2011 15:28:14}", 1307626094000,
                              GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly("{Mar 16 08:12:04}", {6419524000, 6462724000},
                              GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly("{2010-06-26 02:31:29}",
                              {1277512289000, 1277555489000},
                              GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly("{2006/01/22 04:11:05}",
                              {1137899465000, 1137942665000},
                              GRANULARITY_SECOND));
  EXPECT_TRUE(
      ParsesCorrectly("{11:42:35}", {38555000, 81755000}, GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly("{23/Apr 11:42:35}", {9715355000, 9758555000},
                              GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly("{23/Apr/2015 11:42:35}",
                              {1429782155000, 1429825355000},
                              GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly("{23-Apr-2015 11:42:35}",
                              {1429782155000, 1429825355000},
                              GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly("{23 Apr 2015 11:42:35}",
                              {1429782155000, 1429825355000},
                              GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly("{04/23/15 11:42:35}",
                              {1429782155000, 1429825355000},
                              GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly("{04/23/2015 11:42:35}",
                              {1429782155000, 1429825355000},
                              GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly("{9/28/2011 2:23:15 PM}", 1317212595000,
                              GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly(
      "Are sentiments apartments decisively the especially alteration. "
      "Thrown shy denote ten ladies though ask saw. Or by to he going "
      "think order event music. Incommode so intention defective at "
      "convinced. Led income months itself and houses you. After nor "
      "you leave might share court balls. {19/apr/2010 06:36:15} Are "
      "sentiments apartments decisively the especially alteration. "
      "Thrown shy denote ten ladies though ask saw. Or by to he going "
      "think order event music. Incommode so intention defective at "
      "convinced. Led income months itself and houses you. After nor "
      "you leave might share court balls. ",
      {1271651775000, 1271694975000}, GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectly("{january 1 2018 at 4:30}",
                              {1514777400000, 1514820600000},
                              GRANULARITY_MINUTE));
  EXPECT_TRUE(ParsesCorrectly("{january 1 2018 at 4:30 am}", 1514777400000,
                              GRANULARITY_MINUTE));
  EXPECT_TRUE(ParsesCorrectly("{january 1 2018 at 4pm}", 1514818800000,
                              GRANULARITY_HOUR));

  EXPECT_TRUE(ParsesCorrectly("{today at 0:00}", {-3600000, 39600000},
                              GRANULARITY_MINUTE));
  EXPECT_TRUE(ParsesCorrectly(
      "{today at 0:00}", {-57600000, -14400000}, GRANULARITY_MINUTE,
      /*anchor_start_end=*/false, "America/Los_Angeles"));
  EXPECT_TRUE(ParsesCorrectly("{tomorrow at 4:00}", {97200000, 140400000},
                              GRANULARITY_MINUTE));
  EXPECT_TRUE(ParsesCorrectly("{tomorrow at 4am}", 97200000, GRANULARITY_HOUR));
  EXPECT_TRUE(
      ParsesCorrectly("{wednesday at 4am}", 529200000, GRANULARITY_HOUR));
  EXPECT_TRUE(ParsesCorrectly("last seen {today at 9:01 PM}", 72060000,
                              GRANULARITY_MINUTE));
  EXPECT_TRUE(ParsesCorrectly("set an alarm for {7am tomorrow}", 108000000,
                              GRANULARITY_HOUR));
  EXPECT_TRUE(
      ParsesCorrectly("set an alarm for {7 a.m}", 21600000, GRANULARITY_HOUR));
}

TEST_F(ParserTest, ParseWithAnchor) {
  EXPECT_TRUE(ParsesCorrectly("{January 1, 1988}", 567990000000,
                              GRANULARITY_DAY, /*anchor_start_end=*/false));
  EXPECT_TRUE(ParsesCorrectly("{January 1, 1988}", 567990000000,
                              GRANULARITY_DAY, /*anchor_start_end=*/true));
  EXPECT_TRUE(ParsesCorrectly("lorem {1 january 2018} ipsum", 1514761200000,
                              GRANULARITY_DAY, /*anchor_start_end=*/false));
  EXPECT_TRUE(HasNoResult("lorem 1 january 2018 ipsum",
                          /*anchor_start_end=*/true));
}

TEST_F(ParserTest, ParseWithRawUsecase) {
  // Annotated for RAW usecase.
  EXPECT_TRUE(ParsesCorrectly(
      "{tomorrow}", 82800000, GRANULARITY_DAY, /*anchor_start_end=*/false,
      /*timezone=*/"Europe/Zurich", /*locales=*/"en-US",
      /*annotation_usecase=*/AnnotationUsecase_ANNOTATION_USECASE_RAW));

  EXPECT_TRUE(ParsesCorrectly(
      "call me {in two hours}", 7200000, GRANULARITY_HOUR,
      /*anchor_start_end=*/false,
      /*timezone=*/"Europe/Zurich", /*locales=*/"en-US",
      /*annotation_usecase=*/AnnotationUsecase_ANNOTATION_USECASE_RAW));

  EXPECT_TRUE(ParsesCorrectly(
      "call me {next month}", 2674800000, GRANULARITY_MONTH,
      /*anchor_start_end=*/false,
      /*timezone=*/"Europe/Zurich", /*locales=*/"en-US",
      /*annotation_usecase=*/AnnotationUsecase_ANNOTATION_USECASE_RAW));
  EXPECT_TRUE(ParsesCorrectly(
      "what's the time {now}", -3600000, GRANULARITY_DAY,
      /*anchor_start_end=*/false,
      /*timezone=*/"Europe/Zurich", /*locales=*/"en-US",
      /*annotation_usecase=*/AnnotationUsecase_ANNOTATION_USECASE_RAW));

  EXPECT_TRUE(ParsesCorrectly(
      "call me on {Saturday}", 169200000, GRANULARITY_DAY,
      /*anchor_start_end=*/false,
      /*timezone=*/"Europe/Zurich", /*locales=*/"en-US",
      /*annotation_usecase=*/AnnotationUsecase_ANNOTATION_USECASE_RAW));

  // Not annotated for Smart usecase.
  EXPECT_TRUE(HasNoResult(
      "{tomorrow}", /*anchor_start_end=*/false,
      /*timezone=*/"Europe/Zurich",
      /*annotation_usecase=*/AnnotationUsecase_ANNOTATION_USECASE_SMART));
}

TEST_F(ParserTest, ParsesNoonAndMidnightCorrectly) {
  EXPECT_TRUE(ParsesCorrectly("{January 1, 1988 12:30am}", 567991800000,
                              GRANULARITY_MINUTE));
  EXPECT_TRUE(ParsesCorrectly("{January 1, 1988 12:30pm}", 568035000000,
                              GRANULARITY_MINUTE));
}

TEST_F(ParserTest, ParseGerman) {
  EXPECT_TRUE(
      ParsesCorrectlyGerman("{Januar 1 2018}", 1514761200000, GRANULARITY_DAY));
  EXPECT_TRUE(
      ParsesCorrectlyGerman("{1 2 2018}", 1517439600000, GRANULARITY_DAY));
  EXPECT_TRUE(ParsesCorrectlyGerman("lorem {1 Januar 2018} ipsum",
                                    1514761200000, GRANULARITY_DAY));
  EXPECT_TRUE(ParsesCorrectlyGerman("{19/Apr/2010:06:36:15}",
                                    {1271651775000, 1271694975000},
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{09/März/2004 22:02:40}", 1078866160000,
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{Dez 2, 2010 2:39:58}",
                                    {1291253998000, 1291297198000},
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{Juni 09 2011 15:28:14}", 1307626094000,
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman(
      "{März 16 08:12:04}", {6419524000, 6462724000}, GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{2010-06-26 02:31:29}",
                                    {1277512289000, 1277555489000},
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{2006/01/22 04:11:05}",
                                    {1137899465000, 1137942665000},
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{11:42:35}", {38555000, 81755000},
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman(
      "{23/Apr 11:42:35}", {9715355000, 9758555000}, GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{23/Apr/2015:11:42:35}",
                                    {1429782155000, 1429825355000},
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{23/Apr/2015 11:42:35}",
                                    {1429782155000, 1429825355000},
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{23-Apr-2015 11:42:35}",
                                    {1429782155000, 1429825355000},
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{23 Apr 2015 11:42:35}",
                                    {1429782155000, 1429825355000},
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{04/23/15 11:42:35}",
                                    {1429782155000, 1429825355000},
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{04/23/2015 11:42:35}",
                                    {1429782155000, 1429825355000},
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{19/apr/2010:06:36:15}",
                                    {1271651775000, 1271694975000},
                                    GRANULARITY_SECOND));
  EXPECT_TRUE(ParsesCorrectlyGerman("{januar 1 2018 um 4:30}",
                                    {1514777400000, 1514820600000},
                                    GRANULARITY_MINUTE));
  EXPECT_TRUE(ParsesCorrectlyGerman("{januar 1 2018 um 4:30 nachm}",
                                    1514820600000, GRANULARITY_MINUTE));
  EXPECT_TRUE(ParsesCorrectlyGerman("{januar 1 2018 um 4 nachm}", 1514818800000,
                                    GRANULARITY_HOUR));
  EXPECT_TRUE(
      ParsesCorrectlyGerman("{14.03.2017}", 1489446000000, GRANULARITY_DAY));
  EXPECT_TRUE(ParsesCorrectlyGerman("{morgen 0:00}", {82800000, 126000000},
                                    GRANULARITY_MINUTE));
  EXPECT_TRUE(ParsesCorrectlyGerman("{morgen um 4:00}", {97200000, 140400000},
                                    GRANULARITY_MINUTE));
  EXPECT_TRUE(
      ParsesCorrectlyGerman("{morgen um 4 vorm}", 97200000, GRANULARITY_HOUR));
}

TEST_F(ParserTest, ParseNonUs) {
  EXPECT_TRUE(ParsesCorrectly("{1/5/15}", 1430431200000, GRANULARITY_DAY,
                              /*anchor_start_end=*/false,
                              /*timezone=*/"Europe/Zurich",
                              /*locales=*/"en-GB"));
  EXPECT_TRUE(ParsesCorrectly("{1/5/15}", 1430431200000, GRANULARITY_DAY,
                              /*anchor_start_end=*/false,
                              /*timezone=*/"Europe/Zurich", /*locales=*/"en"));
}

TEST_F(ParserTest, ParseUs) {
  EXPECT_TRUE(ParsesCorrectly("{1/5/15}", 1420412400000, GRANULARITY_DAY,
                              /*anchor_start_end=*/false,
                              /*timezone=*/"Europe/Zurich",
                              /*locales=*/"en-US"));
  EXPECT_TRUE(ParsesCorrectly("{1/5/15}", 1420412400000, GRANULARITY_DAY,
                              /*anchor_start_end=*/false,
                              /*timezone=*/"Europe/Zurich",
                              /*locales=*/"es-US"));
}

TEST_F(ParserTest, ParseUnknownLanguage) {
  EXPECT_TRUE(ParsesCorrectly("bylo to {31. 12. 2015} v 6 hodin", 1451516400000,
                              GRANULARITY_DAY,
                              /*anchor_start_end=*/false,
                              /*timezone=*/"Europe/Zurich", /*locales=*/"xx"));
}

TEST_F(ParserTest, WhenAlternativesEnabledGeneratesAlternatives) {
  LoadModel([](ModelT* model) {
    model->datetime_model->generate_alternative_interpretations_when_ambiguous =
        true;
  });

  EXPECT_TRUE(ParsesCorrectly("{january 1 2018 at 4:30}",
                              {1514777400000, 1514820600000},
                              GRANULARITY_MINUTE));
  EXPECT_TRUE(ParsesCorrectly("{monday 3pm}", 396000000, GRANULARITY_HOUR));
  EXPECT_TRUE(ParsesCorrectly("{monday 3:00}", {352800000, 396000000},
                              GRANULARITY_MINUTE));
}

TEST_F(ParserTest, WhenAlternativesDisabledDoesNotGenerateAlternatives) {
  LoadModel([](ModelT* model) {
    model->datetime_model->generate_alternative_interpretations_when_ambiguous =
        false;
  });

  EXPECT_TRUE(ParsesCorrectly("{january 1 2018 at 4:30}", 1514777400000,
                              GRANULARITY_MINUTE));
}

class ParserLocaleTest : public testing::Test {
 public:
  void SetUp() override;
  bool HasResult(const std::string& input, const std::string& locales);

 protected:
  UniLib unilib_;
  CalendarLib calendarlib_;
  flatbuffers::FlatBufferBuilder builder_;
  std::unique_ptr<DatetimeParser> parser_;
};

void AddPattern(const std::string& regex, int locale,
                std::vector<std::unique_ptr<DatetimeModelPatternT>>* patterns) {
  patterns->emplace_back(new DatetimeModelPatternT);
  patterns->back()->regexes.emplace_back(new DatetimeModelPattern_::RegexT);
  patterns->back()->regexes.back()->pattern = regex;
  patterns->back()->regexes.back()->groups.push_back(
      DatetimeGroupType_GROUP_UNUSED);
  patterns->back()->locales.push_back(locale);
}

void ParserLocaleTest::SetUp() {
  DatetimeModelT model;
  model.use_extractors_for_locating = false;
  model.locales.clear();
  model.locales.push_back("en-US");
  model.locales.push_back("en-CH");
  model.locales.push_back("zh-Hant");
  model.locales.push_back("en-*");
  model.locales.push_back("zh-Hant-*");
  model.locales.push_back("*-CH");
  model.locales.push_back("default");
  model.default_locales.push_back(6);

  AddPattern(/*regex=*/"en-US", /*locale=*/0, &model.patterns);
  AddPattern(/*regex=*/"en-CH", /*locale=*/1, &model.patterns);
  AddPattern(/*regex=*/"zh-Hant", /*locale=*/2, &model.patterns);
  AddPattern(/*regex=*/"en-all", /*locale=*/3, &model.patterns);
  AddPattern(/*regex=*/"zh-Hant-all", /*locale=*/4, &model.patterns);
  AddPattern(/*regex=*/"all-CH", /*locale=*/5, &model.patterns);
  AddPattern(/*regex=*/"default", /*locale=*/6, &model.patterns);

  builder_.Finish(DatetimeModel::Pack(builder_, &model));
  const DatetimeModel* model_fb =
      flatbuffers::GetRoot<DatetimeModel>(builder_.GetBufferPointer());
  ASSERT_TRUE(model_fb);

  parser_ = DatetimeParser::Instance(model_fb, unilib_, calendarlib_,
                                     /*decompressor=*/nullptr);
  ASSERT_TRUE(parser_);
}

bool ParserLocaleTest::HasResult(const std::string& input,
                                 const std::string& locales) {
  std::vector<DatetimeParseResultSpan> results;
  EXPECT_TRUE(parser_->Parse(
      input, /*reference_time_ms_utc=*/0,
      /*reference_timezone=*/"", locales, ModeFlag_ANNOTATION,
      AnnotationUsecase_ANNOTATION_USECASE_SMART, false, &results));
  return results.size() == 1;
}

TEST_F(ParserLocaleTest, English) {
  EXPECT_TRUE(HasResult("en-US", /*locales=*/"en-US"));
  EXPECT_FALSE(HasResult("en-CH", /*locales=*/"en-US"));
  EXPECT_FALSE(HasResult("en-US", /*locales=*/"en-CH"));
  EXPECT_TRUE(HasResult("en-CH", /*locales=*/"en-CH"));
  EXPECT_TRUE(HasResult("default", /*locales=*/"en-CH"));
}

TEST_F(ParserLocaleTest, TraditionalChinese) {
  EXPECT_TRUE(HasResult("zh-Hant-all", /*locales=*/"zh-Hant"));
  EXPECT_TRUE(HasResult("zh-Hant-all", /*locales=*/"zh-Hant-TW"));
  EXPECT_TRUE(HasResult("zh-Hant-all", /*locales=*/"zh-Hant-SG"));
  EXPECT_FALSE(HasResult("zh-Hant-all", /*locales=*/"zh-SG"));
  EXPECT_FALSE(HasResult("zh-Hant-all", /*locales=*/"zh"));
  EXPECT_TRUE(HasResult("default", /*locales=*/"zh"));
  EXPECT_TRUE(HasResult("default", /*locales=*/"zh-Hant-SG"));
}

TEST_F(ParserLocaleTest, SwissEnglish) {
  EXPECT_TRUE(HasResult("all-CH", /*locales=*/"de-CH"));
  EXPECT_TRUE(HasResult("all-CH", /*locales=*/"en-CH"));
  EXPECT_TRUE(HasResult("en-all", /*locales=*/"en-CH"));
  EXPECT_FALSE(HasResult("all-CH", /*locales=*/"de-DE"));
  EXPECT_TRUE(HasResult("default", /*locales=*/"de-CH"));
  EXPECT_TRUE(HasResult("default", /*locales=*/"en-CH"));
}

}  // namespace
}  // namespace libtextclassifier3
