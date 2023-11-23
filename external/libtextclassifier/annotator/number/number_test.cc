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

#include <string>
#include <vector>

#include "annotator/collections.h"
#include "annotator/model_generated.h"
#include "annotator/types-test-util.h"
#include "annotator/types.h"
#include "utils/test-utils.h"
#include "utils/utf8/unicodetext.h"
#include "utils/utf8/unilib.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"

namespace libtextclassifier3 {
namespace {

using testing::AllOf;
using testing::ElementsAre;
using testing::Field;

const NumberAnnotatorOptions* TestingNumberAnnotatorOptions() {
  static const flatbuffers::DetachedBuffer* options_data = []() {
    NumberAnnotatorOptionsT options;
    options.enabled = true;
    options.allowed_prefix_codepoints.push_back('$');
    options.allowed_suffix_codepoints.push_back('%');

    flatbuffers::FlatBufferBuilder builder;
    builder.Finish(NumberAnnotatorOptions::Pack(builder, &options));
    return new flatbuffers::DetachedBuffer(builder.Release());
  }();

  return flatbuffers::GetRoot<NumberAnnotatorOptions>(options_data->data());
}

FeatureProcessor BuildFeatureProcessor(const UniLib* unilib) {
  static const flatbuffers::DetachedBuffer* options_data = []() {
    FeatureProcessorOptionsT options;
    options.context_size = 1;
    options.max_selection_span = 1;
    options.snap_label_span_boundaries_to_containing_tokens = false;
    options.ignored_span_boundary_codepoints.push_back(',');

    options.tokenization_codepoint_config.emplace_back(
        new TokenizationCodepointRangeT());
    auto& config = options.tokenization_codepoint_config.back();
    config->start = 32;
    config->end = 33;
    config->role = TokenizationCodepointRange_::Role_WHITESPACE_SEPARATOR;

    flatbuffers::FlatBufferBuilder builder;
    builder.Finish(FeatureProcessorOptions::Pack(builder, &options));
    return new flatbuffers::DetachedBuffer(builder.Release());
  }();

  const FeatureProcessorOptions* feature_processor_options =
      flatbuffers::GetRoot<FeatureProcessorOptions>(options_data->data());

  return FeatureProcessor(feature_processor_options, unilib);
}

class NumberAnnotatorTest : public ::testing::Test {
 protected:
  NumberAnnotatorTest()
      : INIT_UNILIB_FOR_TESTING(unilib_),
        feature_processor_(BuildFeatureProcessor(&unilib_)),
        number_annotator_(TestingNumberAnnotatorOptions(),
                          &feature_processor_) {}

  UniLib unilib_;
  FeatureProcessor feature_processor_;
  NumberAnnotator number_annotator_;
};

TEST_F(NumberAnnotatorTest, ClassifiesAndParsesNumberCorrectly) {
  ClassificationResult classification_result;
  EXPECT_TRUE(number_annotator_.ClassifyText(
      UTF8ToUnicodeText("... 12345 ..."), {4, 9},
      AnnotationUsecase_ANNOTATION_USECASE_RAW, &classification_result));

  EXPECT_EQ(classification_result.collection, "number");
  EXPECT_EQ(classification_result.numeric_value, 12345);
}

TEST_F(NumberAnnotatorTest, ClassifiesNonNumberCorrectly) {
  ClassificationResult classification_result;
  EXPECT_FALSE(number_annotator_.ClassifyText(
      UTF8ToUnicodeText("... 123a45 ..."), {4, 10},
      AnnotationUsecase_ANNOTATION_USECASE_RAW, &classification_result));
}

TEST_F(NumberAnnotatorTest, FindsAllNumbersInText) {
  std::vector<AnnotatedSpan> result;
  EXPECT_TRUE(number_annotator_.FindAll(
      UTF8ToUnicodeText("... 12345 ... 9 is my number and I paid $99 and "
                        "sometimes 27% but not 68# nor #68"),
      AnnotationUsecase_ANNOTATION_USECASE_RAW, &result));

  ASSERT_EQ(result.size(), 4);
  ASSERT_EQ(result[0].classification.size(), 1);
  EXPECT_EQ(result[0].classification[0].collection, "number");
  EXPECT_EQ(result[0].classification[0].numeric_value, 12345);
  ASSERT_EQ(result[1].classification.size(), 1);
  EXPECT_EQ(result[1].classification[0].collection, "number");
  EXPECT_EQ(result[1].classification[0].numeric_value, 9);
  ASSERT_EQ(result[2].classification.size(), 1);
  EXPECT_EQ(result[2].classification[0].collection, "number");
  EXPECT_EQ(result[2].classification[0].numeric_value, 99);
  ASSERT_EQ(result[3].classification.size(), 1);
  EXPECT_EQ(result[3].classification[0].collection, "number");
  EXPECT_EQ(result[3].classification[0].numeric_value, 27);
}

TEST_F(NumberAnnotatorTest, FindsNumberWithPunctuation) {
  std::vector<AnnotatedSpan> result;
  EXPECT_TRUE(number_annotator_.FindAll(
      UTF8ToUnicodeText("Come at 9, ok?"),
      AnnotationUsecase_ANNOTATION_USECASE_RAW, &result));

  EXPECT_THAT(
      result,
      ElementsAre(
          AllOf(Field(&AnnotatedSpan::span, CodepointSpan(8, 9)),
                Field(&AnnotatedSpan::classification,
                      ElementsAre(AllOf(
                          Field(&ClassificationResult::collection, "number"),
                          Field(&ClassificationResult::numeric_value, 9)))))));
}

TEST_F(NumberAnnotatorTest, HandlesNumbersAtBeginning) {
  std::vector<AnnotatedSpan> result;
  EXPECT_TRUE(number_annotator_.FindAll(
      UTF8ToUnicodeText("-5"), AnnotationUsecase_ANNOTATION_USECASE_RAW,
      &result));

  EXPECT_THAT(
      result,
      ElementsAre(
          AllOf(Field(&AnnotatedSpan::span, CodepointSpan(0, 2)),
                Field(&AnnotatedSpan::classification,
                      ElementsAre(AllOf(
                          Field(&ClassificationResult::collection, "number"),
                          Field(&ClassificationResult::numeric_value, -5)))))));
}

TEST_F(NumberAnnotatorTest, WhenLowestSupportedNumberParsesIt) {
  ClassificationResult classification_result;
  EXPECT_TRUE(number_annotator_.ClassifyText(
      UTF8ToUnicodeText("-999999999999999999"), {0, 19},
      AnnotationUsecase_ANNOTATION_USECASE_RAW, &classification_result));

  EXPECT_THAT(
      classification_result,
      AllOf(Field(&ClassificationResult::collection, "number"),
            Field(&ClassificationResult::numeric_value, -999999999999999999L)));
}

TEST_F(NumberAnnotatorTest, WhenLargestSupportedNumberParsesIt) {
  ClassificationResult classification_result;
  EXPECT_TRUE(number_annotator_.ClassifyText(
      UTF8ToUnicodeText("999999999999999999"), {0, 18},
      AnnotationUsecase_ANNOTATION_USECASE_RAW, &classification_result));

  EXPECT_THAT(
      classification_result,
      AllOf(Field(&ClassificationResult::collection, "number"),
            Field(&ClassificationResult::numeric_value, 999999999999999999L)));
}

TEST_F(NumberAnnotatorTest, WhenFirstLowestNonSupportedNumberDoesNotParseIt) {
  ClassificationResult classification_result;
  EXPECT_FALSE(number_annotator_.ClassifyText(
      UTF8ToUnicodeText("-10000000000000000000"), {0, 21},
      AnnotationUsecase_ANNOTATION_USECASE_RAW, &classification_result));
}

TEST_F(NumberAnnotatorTest, WhenFirstLargestNonSupportedNumberDoesNotParseIt) {
  ClassificationResult classification_result;
  EXPECT_FALSE(number_annotator_.ClassifyText(
      UTF8ToUnicodeText("10000000000000000000"), {0, 20},
      AnnotationUsecase_ANNOTATION_USECASE_RAW, &classification_result));
}

TEST_F(NumberAnnotatorTest, WhenLargeNumberDoesNotParseIt) {
  ClassificationResult classification_result;
  EXPECT_FALSE(number_annotator_.ClassifyText(
      UTF8ToUnicodeText("1234567890123456789012345678901234567890"), {0, 40},
      AnnotationUsecase_ANNOTATION_USECASE_RAW, &classification_result));
}

TEST_F(NumberAnnotatorTest, WhenMultipleMinusSignsDoesNotParseIt) {
  ClassificationResult classification_result;
  EXPECT_FALSE(number_annotator_.ClassifyText(
      UTF8ToUnicodeText("--10"), {0, 4},
      AnnotationUsecase_ANNOTATION_USECASE_RAW, &classification_result));
}

TEST_F(NumberAnnotatorTest, WhenMinusSignSuffixDoesNotParseIt) {
  ClassificationResult classification_result;
  EXPECT_FALSE(number_annotator_.ClassifyText(
      UTF8ToUnicodeText("10-"), {0, 3},
      AnnotationUsecase_ANNOTATION_USECASE_RAW, &classification_result));
}

TEST_F(NumberAnnotatorTest, WhenMinusInTheMiddleDoesNotParseIt) {
  ClassificationResult classification_result;
  EXPECT_FALSE(number_annotator_.ClassifyText(
      UTF8ToUnicodeText("2016-2017"), {0, 9},
      AnnotationUsecase_ANNOTATION_USECASE_RAW, &classification_result));
}

TEST_F(NumberAnnotatorTest, WhenSuffixWithoutNumberDoesNotParseIt) {
  std::vector<AnnotatedSpan> result;
  EXPECT_TRUE(number_annotator_.FindAll(
      UTF8ToUnicodeText("... % ..."), AnnotationUsecase_ANNOTATION_USECASE_RAW,
      &result));

  ASSERT_EQ(result.size(), 0);
}

TEST_F(NumberAnnotatorTest, WhenPrefixWithoutNumberDoesNotParseIt) {
  std::vector<AnnotatedSpan> result;
  EXPECT_TRUE(number_annotator_.FindAll(
      UTF8ToUnicodeText("... $ ..."), AnnotationUsecase_ANNOTATION_USECASE_RAW,
      &result));

  ASSERT_EQ(result.size(), 0);
}

TEST_F(NumberAnnotatorTest, WhenPrefixAndSuffixWithoutNumberDoesNotParseIt) {
  std::vector<AnnotatedSpan> result;
  EXPECT_TRUE(number_annotator_.FindAll(
      UTF8ToUnicodeText("... $% ..."), AnnotationUsecase_ANNOTATION_USECASE_RAW,
      &result));

  ASSERT_EQ(result.size(), 0);
}

}  // namespace
}  // namespace libtextclassifier3
