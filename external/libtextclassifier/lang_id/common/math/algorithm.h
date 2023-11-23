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

// Generic utils similar to those from the C++ header <algorithm>.

#ifndef NLP_SAFT_COMPONENTS_COMMON_MOBILE_MATH_ALGORITHM_H_
#define NLP_SAFT_COMPONENTS_COMMON_MOBILE_MATH_ALGORITHM_H_

#include <algorithm>
#include <vector>

namespace libtextclassifier3 {
namespace mobile {

// Returns index of max element from the vector |elements|.  Returns 0 if
// |elements| is empty.  T should be a type that can be compared by operator<.
template<typename T>
inline int GetArgMax(const std::vector<T> &elements) {
  return std::distance(
      elements.begin(),
      std::max_element(elements.begin(), elements.end()));
}

// Returns index of min element from the vector |elements|.  Returns 0 if
// |elements| is empty.  T should be a type that can be compared by operator<.
template<typename T>
inline int GetArgMin(const std::vector<T> &elements) {
  return std::distance(
      elements.begin(),
      std::min_element(elements.begin(), elements.end()));
}

}  // namespace mobile
}  // namespace nlp_saft

#endif  // NLP_SAFT_COMPONENTS_COMMON_MOBILE_MATH_ALGORITHM_H_
