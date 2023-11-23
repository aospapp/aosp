/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef BERBERIS_INTRINSICS_TYPE_TRAITS_H_
#define BERBERIS_INTRINSICS_TYPE_TRAITS_H_

#include <stdint.h>

#include "berberis/intrinsics/intrinsics_float.h"

namespace berberis {

// In specializations we define various derivative types:
//  Wide - type twice as wide, same signedness
template <typename T>
struct TypeTraits;

template <>
struct TypeTraits<uint8_t> {
  using Wide = uint16_t;
  static constexpr int kBits = 8;
};

template <>
struct TypeTraits<uint16_t> {
  using Wide = uint32_t;
  using Narrow = uint8_t;
  static constexpr int kBits = 16;
};

template <>
struct TypeTraits<uint32_t> {
  using Wide = uint64_t;
  using Narrow = uint16_t;
  static constexpr int kBits = 32;
  using Float = intrinsics::Float32;
};

template <>
struct TypeTraits<uint64_t> {
  using Narrow = uint32_t;
  static constexpr int kBits = 64;
  using Float = intrinsics::Float64;
#if defined(__x86_64__)
  using Wide = __uint128_t;
#endif
};

template <>
struct TypeTraits<int8_t> {
  using Wide = int16_t;
  static constexpr int kBits = 8;
};

template <>
struct TypeTraits<int16_t> {
  using Wide = int32_t;
  using Narrow = int8_t;
  static constexpr int kBits = 16;
};

template <>
struct TypeTraits<int32_t> {
  using Wide = int64_t;
  using Narrow = int16_t;
  static constexpr int kBits = 32;
  using Float = intrinsics::Float32;
};

template <>
struct TypeTraits<int64_t> {
  using Narrow = int32_t;
  static constexpr int kBits = 64;
  using Float = intrinsics::Float64;
#if defined(__x86_64__)
  using Wide = __int128_t;
#endif
};

template <>
struct TypeTraits<intrinsics::Float32> {
  using Int = int32_t;
  using Wide = intrinsics::Float64;
};

template <>
struct TypeTraits<intrinsics::Float64> {
  using Int = int64_t;
  using Narrow = intrinsics::Float32;
#if defined(__i386__) || defined(__x86_64__)
  static_assert(sizeof(long double) > sizeof(intrinsics::Float64));
  using Wide = long double;
#endif
};

#if defined(__x86_64__)

template <>
struct TypeTraits<__uint128_t> {
  static constexpr int kBits = 128;
  using Narrow = uint64_t;
};

#endif

}  // namespace berberis

#endif  // BERBERIS_INTRINSICS_TYPE_TRAITS_H_
