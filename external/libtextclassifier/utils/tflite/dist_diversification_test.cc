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

#include "utils/tflite/dist_diversification.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "tensorflow/lite/interpreter.h"
#include "tensorflow/lite/kernels/register.h"
#include "tensorflow/lite/kernels/test_util.h"
#include "tensorflow/lite/model.h"

namespace libtextclassifier3 {
namespace {

class DistanceDiversificationOpModel : public tflite::SingleOpModel {
 public:
  explicit DistanceDiversificationOpModel(int matrix_rows);
  void SetDistanceMatrix(const std::initializer_list<float>& values) {
    PopulateTensor(distance_matrix_, values);
  }
  void SetNumOutput(int length) { PopulateTensor(num_results_, {length}); }
  void SetMinDistance(float min_distance) {
    PopulateTensor(min_distance_, {min_distance});
  }
  int GetOutputLen() { return ExtractVector<int>(output_len_).front(); }
  std::vector<int> GetOutputIndexes(int output_length) {
    auto res = ExtractVector<int>(output_indexes_);
    res.resize(output_length);
    return res;
  }

 private:
  int distance_matrix_;
  int num_results_;
  int min_distance_;

  int output_len_;
  int output_indexes_;
};

DistanceDiversificationOpModel::DistanceDiversificationOpModel(
    int matrix_rows) {
  distance_matrix_ = AddInput(tflite::TensorType_FLOAT32);
  min_distance_ = AddInput(tflite::TensorType_FLOAT32);
  num_results_ = AddInput(tflite::TensorType_INT32);

  output_indexes_ = AddOutput(tflite::TensorType_INT32);
  output_len_ = AddOutput(tflite::TensorType_INT32);
  SetCustomOp("DistanceDiversification", {},
              tflite::ops::custom::Register_DISTANCE_DIVERSIFICATION);
  BuildInterpreter({{matrix_rows, matrix_rows}, {1}, {1}});
}

// Tests
TEST(DistanceDiversificationOp, Simple) {
  DistanceDiversificationOpModel m(5);
  m.SetDistanceMatrix({0.0, 0.1, 0.2, 0.3, 0.4, 0.1, 0.0, 0.1, 0.2,
                       0.3, 0.2, 0.1, 0.0, 0.1, 0.2, 0.3, 0.2, 0.1,
                       0.0, 0.1, 0.4, 0.3, 0.2, 0.1, 0.0});
  m.SetMinDistance(0.21);
  m.SetNumOutput(3);
  m.Invoke();
  const int output_length = m.GetOutputLen();
  EXPECT_EQ(output_length, 2);
  EXPECT_THAT(m.GetOutputIndexes(output_length), testing::ElementsAre(0, 3));
}

}  // namespace
}  // namespace libtextclassifier3
