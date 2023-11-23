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

#include <vector>

#include "utils/tflite/token_encoder.h"
#include "gtest/gtest.h"
#include "third_party/absl/flags/flag.h"
#include "tensorflow/lite/interpreter.h"
#include "tensorflow/lite/kernels/register.h"
#include "tensorflow/lite/kernels/test_util.h"
#include "tensorflow/lite/model.h"

namespace libtextclassifier3 {
namespace {

class TokenEncoderOpModel : public tflite::SingleOpModel {
 public:
  TokenEncoderOpModel(std::initializer_list<int> input_shape,
                      std::initializer_list<int> attribute_shape);
  void SetNumTokens(const std::initializer_list<int>& num_tokens) {
    PopulateTensor(input_num_tokens_, num_tokens);
    PopulateTensor(input_length_, {static_cast<int32_t>(num_tokens.size())});
  }
  void SetMaxOutputLength(int length) {
    PopulateTensor(input_output_maxlength_, {length});
  }
  void SetInt32Attribute(const std::initializer_list<int>& attribute) {
    PopulateTensor(input_attributes_int32_, attribute);
  }
  void SetFloatAttribute(const std::initializer_list<float>& attribute) {
    PopulateTensor(input_attributes_float_, attribute);
  }
  std::vector<int> GetOutputPositions() {
    return ExtractVector<int>(output_positions_);
  }
  std::vector<int> GetOutputAttributeInt32() {
    return ExtractVector<int>(output_attributes_int32_);
  }
  std::vector<float> GetOutputAttributeFloat() {
    return ExtractVector<float>(output_attributes_float_);
  }
  int GetOutputLength() { return ExtractVector<int>(output_length_)[0]; }

 private:
  int input_num_tokens_;
  int input_length_;
  int input_output_maxlength_;
  int input_attributes_int32_;
  int input_attributes_float_;

  int output_positions_;
  int output_length_;
  int output_attributes_int32_;
  int output_attributes_float_;
};

TokenEncoderOpModel::TokenEncoderOpModel(
    std::initializer_list<int> input_shape,
    std::initializer_list<int> attribute_shape) {
  input_num_tokens_ = AddInput(tflite::TensorType_INT32);
  input_length_ = AddInput(tflite::TensorType_INT32);
  input_output_maxlength_ = AddInput(tflite::TensorType_INT32);
  input_attributes_int32_ = AddInput(tflite::TensorType_INT32);
  input_attributes_float_ = AddInput(tflite::TensorType_FLOAT32);

  output_positions_ = AddOutput(tflite::TensorType_INT32);
  output_length_ = AddOutput(tflite::TensorType_INT32);
  output_attributes_int32_ = AddOutput(tflite::TensorType_INT32);
  output_attributes_float_ = AddOutput(tflite::TensorType_FLOAT32);

  SetCustomOp("TokenEncoder", {}, tflite::ops::custom::Register_TOKEN_ENCODER);
  BuildInterpreter({input_shape, {1}, {1}, attribute_shape, attribute_shape});
}

// Tests
TEST(TokenEncoderTest, SimpleEncoder) {
  TokenEncoderOpModel m({1, 1}, {1, 1});
  m.SetNumTokens({1});
  m.SetMaxOutputLength(10);
  m.SetInt32Attribute({7});
  m.SetFloatAttribute({3.f});

  m.Invoke();

  EXPECT_EQ(m.GetOutputLength(), 3);
  EXPECT_THAT(m.GetOutputPositions(),
              testing::ElementsAre(0, 1, 2, 10, 10, 10, 10, 10, 10, 10));
  EXPECT_THAT(m.GetOutputAttributeInt32(),
              testing::ElementsAre(7, 7, 7, 7, 7, 7, 7, 7, 7, 7));
  EXPECT_THAT(
      m.GetOutputAttributeFloat(),
      testing::ElementsAre(3.f, 3.f, 3.f, 3.f, 3.f, 3.f, 3.f, 3.f, 3.f, 3.f));
}

TEST(TokenEncoderTest, ManyMessages) {
  TokenEncoderOpModel m({1, 3}, {1, 3});
  m.SetInt32Attribute({1, 2, 3});
  m.SetFloatAttribute({5.f, 4.f, 3.f});
  m.SetNumTokens({1, 1, 1});
  m.SetMaxOutputLength(10);

  m.Invoke();

  EXPECT_EQ(m.GetOutputLength(), 9);
  EXPECT_THAT(m.GetOutputPositions(),
              testing::ElementsAre(0, 1, 2, 0, 1, 2, 0, 1, 2, 10));
  EXPECT_THAT(m.GetOutputAttributeInt32(),
              testing::ElementsAre(1, 1, 1, 2, 2, 2, 3, 3, 3, 3));
  EXPECT_THAT(
      m.GetOutputAttributeFloat(),
      testing::ElementsAre(5.f, 5.f, 5.f, 4.f, 4.f, 4.f, 3.f, 3.f, 3.f, 3.f));
}

TEST(TokenEncoderTest, ManyMessagesMultipleTokens) {
  TokenEncoderOpModel m({1, 4}, {1, 4});
  m.SetInt32Attribute({1, 2, 3, 4});
  m.SetFloatAttribute({5.f, 4.f, 3.f, 2.f});
  m.SetNumTokens({1, 2, 3, 4});
  m.SetMaxOutputLength(9);

  m.Invoke();

  EXPECT_EQ(m.GetOutputLength(), 9);
  EXPECT_THAT(m.GetOutputPositions(),
              testing::ElementsAre(2, 3, 4, 0, 1, 2, 3, 4, 5));
  EXPECT_THAT(m.GetOutputAttributeInt32(),
              testing::ElementsAre(3, 3, 3, 4, 4, 4, 4, 4, 4));
  EXPECT_THAT(
      m.GetOutputAttributeFloat(),
      testing::ElementsAre(3.f, 3.f, 3.f, 2.f, 2.f, 2.f, 2.f, 2.f, 2.f));
}

}  // namespace
}  // namespace libtextclassifier3
