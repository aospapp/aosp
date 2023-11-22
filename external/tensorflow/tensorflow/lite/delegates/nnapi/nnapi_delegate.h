/* Copyright 2017 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/
#ifndef TENSORFLOW_LITE_DELEGATES_NNAPI_NNAPI_DELEGATE_H_
#define TENSORFLOW_LITE_DELEGATES_NNAPI_NNAPI_DELEGATE_H_

#include "tensorflow/lite/c/c_api_internal.h"

namespace tflite {

// Return a delegate that can be used to use the NN API.
// e.g.
//   NnApiDelegate* delegate = NnApiDelegate();
//   interpreter->ModifyGraphWithDelegate(&delegate);
// NnApiDelegate() returns a singleton, so you should not free this
// pointer or worry about its lifetime.
//
// NOTE: The following are experimental and subject to change.
// User could provide a string representing the name of specified NNAPI
// accelerator. If an accelerator can be found based on the provided name,
// the delegate will try to compile and run the model use only the selected
// accelerator. An error will occur if the accelerator could not be found.
// It is the user's responsibility to handle errors from a specific accelerator.
TfLiteDelegate* NnApiDelegate(const char* device_name = nullptr);
}  // namespace tflite

#endif  // TENSORFLOW_LITE_DELEGATES_NNAPI_NNAPI_DELEGATE_H_
