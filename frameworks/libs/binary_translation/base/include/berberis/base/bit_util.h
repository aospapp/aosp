/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef BERBERIS_BASE_BIT_UTIL_H_
#define BERBERIS_BASE_BIT_UTIL_H_

#include <cstdint>
#include <cstring>
#include <type_traits>

#include "berberis/base/checks.h"

namespace berberis {

template <typename T>
constexpr bool IsPowerOf2(T x) {
  static_assert(std::is_integral_v<T>, "IsPowerOf2: T must be integral");
  DCHECK(x != 0);
  return (x & (x - 1)) == 0;
}

template <typename T>
constexpr T AlignDown(T x, size_t align) {
  static_assert(std::is_integral_v<T>, "AlignDown: T must be integral");
  DCHECK(IsPowerOf2(align));
  return x & ~(align - 1);
}

template <typename T>
constexpr T AlignUp(T x, size_t align) {
  return AlignDown(x + align - 1, align);
}

template <typename T>
constexpr bool IsAligned(T x, size_t align) {
  return AlignDown(x, align) == x;
}

// Helper to align pointers.
template <typename T>
constexpr T* AlignDown(T* p, size_t align) {
  return reinterpret_cast<T*>(AlignDown(reinterpret_cast<uintptr_t>(p), align));
}

// Helper to align pointers.
template <typename T>
constexpr T* AlignUp(T* p, size_t align) {
  return reinterpret_cast<T*>(AlignUp(reinterpret_cast<uintptr_t>(p), align));
}

// Helper to align pointers.
template <typename T>
constexpr bool IsAligned(T* p, size_t align) {
  return IsAligned(reinterpret_cast<uintptr_t>(p), align);
}

template <typename T>
constexpr T BitUtilLog2(T x) {
  static_assert(std::is_integral_v<T>, "Log2: T must be integral");
  DCHECK(IsPowerOf2(x));
  return x == 1 ? 0 : BitUtilLog2(x >> 1) + 1;
}

// Verify that argument value fits into a target.
template <typename ResultType, typename ArgumentType>
inline bool IsInRange(ArgumentType x) {
  // Note: conversion from wider integer type into narrow integer type is always
  // defined.  Conversion to unsigned produces well-defined result while conversion
  // to signed type produces implementation-defined result but in both cases value
  // is guaranteed to be unchanged if it can be represented in the destination type
  // and is *some* valid value if it's unrepesentable.
  //
  // Quote from the standard (including "note" in the standard):
  //   If the destination type is unsigned, the resulting value is the least unsigned
  // integer congruent to the source integer (modulo 2ⁿ where n is the number of bits
  // used to represent the unsigned type). [ Note: In a two’s complement representation,
  // this conversion is conceptual and there is no change in the bit pattern (if there
  // is no truncation). — end note ]
  //   If the destination type is signed, the value is unchanged if it can be represented
  // in the destination type; otherwise, the value is implementation-defined.

  return static_cast<ResultType>(x) == x;
}

// bit_cast<Dest, Source> is a well-defined equivalent of address-casting:
//   *reinterpret_cast<Dest*>(&source)
// See chromium base/macros.h for details.
template <class Dest, class Source>
inline Dest bit_cast(const Source& source) {
  static_assert(sizeof(Dest) == sizeof(Source),
                "bit_cast: source and destination must be of same size");
  static_assert(std::is_trivially_copyable_v<Dest>,
                "bit_cast: destination must be trivially copyable");
  static_assert(std::is_trivially_copyable_v<Source>,
                "bit_cast: source must be trivially copyable");
  Dest dest;
  memcpy(&dest, &source, sizeof(dest));
  return dest;
}

}  // namespace berberis

#endif  // BERBERIS_BASE_BIT_UTIL_H_
