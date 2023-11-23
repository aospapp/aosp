/*
 * Copyright (C) 2023 The Android Open Source Project
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

#ifndef BERBERIS_FP_REGS_H_
#define BERBERIS_FP_REGS_H_

#include <cstring>

#include "berberis/base/bit_util.h"
#include "berberis/intrinsics/intrinsics_float.h"

namespace berberis {

template <typename FloatType>
inline FloatType NanUnboxFPRegToFloat(uint64_t arg);

template <>
inline intrinsics::Float32 NanUnboxFPRegToFloat(uint64_t arg) {
  // Apart from transfer operations (e.g. loads and stores), all other floating-point operations on
  // narrower n-bit operations, n < FLEN, check if the input operands are correctly NaN-boxed, i.e.,
  // all upper FLEN−n bits are 1. If so, the n least-significant bits of the input are used as the
  // input value, otherwise the input value is treated as an n-bit canonical NaN.
  if ((arg & 0xffff'ffff'0000'0000) != 0xffff'ffff'0000'0000) {
    return bit_cast<intrinsics::Float32>(0x7fc00000);
  }
  intrinsics::Float32 result;
  memcpy(&result, &arg, sizeof(intrinsics::Float32));
  return result;
}

template <>
inline intrinsics::Float64 NanUnboxFPRegToFloat(uint64_t arg) {
  return bit_cast<intrinsics::Float64>(arg);
}

template <typename FloatType>
inline uint64_t NanBoxFloatToFPReg(FloatType arg);

template <>
inline uint64_t NanBoxFloatToFPReg(intrinsics::Float32 arg) {
  return bit_cast<uint32_t>(arg) | 0xffff'ffff'0000'0000;
}

template <>
inline uint64_t NanBoxFloatToFPReg(intrinsics::Float64 arg) {
  return bit_cast<uint64_t>(arg);
}

}  // namespace berberis

#endif  // BERBERIS_FP_REGS_H_
