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

#ifndef BERBERIS_GUEST_STATE_GUEST_STATE_RISCV64_H_
#define BERBERIS_GUEST_STATE_GUEST_STATE_RISCV64_H_

#include <cstdint>

#include "berberis/base/dependent_false.h"
#include "berberis/base/macros.h"
#include "berberis/guest_state/guest_addr.h"

namespace berberis {

struct CPUState {
  // x1 to x31.
  uint64_t x[31];
  // f0 to f31. We are using uint64_t because C++ may change values of NaN when they are passed from
  // or to function and RISC-V uses NaN-boxing which would make things problematic.
  uint64_t f[32];
  // RISC-V has five rounding modes, while x86-64 has only four.
  //
  // Extra rounding mode (RMM in RISC-V documentation) is emulated but requires the use of
  // FE_TOWARDZERO mode for correct work.
  //
  // Additionally RISC-V implementation is supposed to support three “illegal” rounding modes and
  // when they are selected all instructions which use rounding mode trigger “undefined instruction”
  // exception.
  //
  // For simplicity we always keep full rounding mode (3 bits) in the frm field and set host
  // rounding mode to appropriate one.
  //
  // Exceptions, on the other hand, couldn't be stored here efficiently, instead we rely on the fact
  // that x86-64 implements all five exceptions that RISC-V needs (and more).
  uint8_t frm : 3;
  GuestAddr insn_addr;
};

template <uint8_t kIndex>
inline uint64_t GetXReg(const CPUState& state) {
  static_assert(kIndex > 0);
  static_assert((kIndex - 1) < arraysize(state.x));
  return state.x[kIndex - 1];
}

template <uint8_t kIndex>
inline void SetXReg(CPUState& state, uint64_t val) {
  static_assert(kIndex > 0);
  static_assert((kIndex - 1) < arraysize(state.x));
  state.x[kIndex - 1] = val;
}

template <uint8_t kIndex>
inline uint64_t GetFReg(const CPUState& state) {
  static_assert((kIndex) < arraysize(state.f));
  return state.f[kIndex];
}

template <uint8_t kIndex>
inline void SetFReg(CPUState& state, uint64_t val) {
  static_assert((kIndex) < arraysize(state.f));
  state.f[kIndex] = val;
}

enum class RegisterType {
  kReg,
  kFpReg,
};

template <RegisterType register_type, uint8_t kIndex>
inline auto GetReg(const CPUState& state) {
  if constexpr (register_type == RegisterType::kReg) {
    return GetXReg<kIndex>(state);
  } else if constexpr (register_type == RegisterType::kFpReg) {
    return GetFReg<kIndex>(state);
  } else {
    static_assert(kDependentValueFalse<register_type>, "Unsupported register type");
  }
}

template <RegisterType register_type, uint8_t kIndex, typename Register>
inline auto SetReg(CPUState& state, Register val) {
  if constexpr (register_type == RegisterType::kReg) {
    return SetXReg<kIndex>(state, val);
  } else if constexpr (register_type == RegisterType::kFpReg) {
    return SetFReg<kIndex>(state, val);
  } else {
    static_assert(kDependentValueFalse<register_type>, "Unsupported register type");
  }
}

struct ThreadState {
  CPUState cpu;
};

}  // namespace berberis

#endif  // BERBERIS_GUEST_STATE_GUEST_STATE_RISCV64_H_
