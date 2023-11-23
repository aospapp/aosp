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

// Helper utilities for testing Annotator.

#ifndef LIBTEXTCLASSIFIER_UTILS_TESTING_ANNOTATOR_H_
#define LIBTEXTCLASSIFIER_UTILS_TESTING_ANNOTATOR_H_

#include <memory>
#include <string>

#include "annotator/model_generated.h"
#include "annotator/types.h"
#include "flatbuffers/flatbuffers.h"

namespace libtextclassifier3 {

// Loads FlatBuffer model, unpacks it and passes it to the visitor_fn so that it
// can modify it. Afterwards the modified unpacked model is serialized back to a
// flatbuffer.
template <typename Fn>
std::string ModifyAnnotatorModel(const std::string& model_flatbuffer,
                                 Fn visitor_fn) {
  std::unique_ptr<ModelT> unpacked_model =
      UnPackModel(model_flatbuffer.c_str());

  visitor_fn(unpacked_model.get());

  flatbuffers::FlatBufferBuilder builder;
  FinishModelBuffer(builder, Model::Pack(builder, unpacked_model.get()));

  return std::string(reinterpret_cast<char*>(builder.GetBufferPointer()),
                     builder.GetSize());
}

}  // namespace libtextclassifier3

#endif  // LIBTEXTCLASSIFIER_UTILS_TESTING_ANNOTATOR_H_
