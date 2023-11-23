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

#ifndef BERBERIS_INTERPRETER_RISCV64_ATOMICS_H_
#define BERBERIS_INTERPRETER_RISCV64_ATOMICS_H_

#include <cstdint>
#include <type_traits>

namespace berberis {

namespace {

// We are not using std::atomic here because using reinterpret_cast to process normal integers via
// pointer to std::atomic is undefined behavior in C++. There was proposal (N4013) to make that
// behavior defined: https://www.open-std.org/jtc1/sc22/wg21/docs/papers/2014/n4013.html.
// Unfortunately it wasn't accepted and thus we would need to rely on the check for the compiler
// version (both clang and gcc are using layout which makes it safe to use std::atomic in that
// fashion). But the final complication makes even that impractical: while clang builtins support
// fetch_min and fetch_max operations that we need std::atomic don't expose these. This means that
// we would need to mix both styles in that file. At that point it becomes just simpler to go with
// clang/gcc builtins.

int AqRlToMemoryOrder(bool aq, bool rl) {
  if (aq) {
    if (rl) {
      return __ATOMIC_ACQ_REL;
    } else {
      return __ATOMIC_ACQUIRE;
    }
  } else {
    if (rl) {
      return __ATOMIC_RELEASE;
    } else {
      return __ATOMIC_RELAXED;
    }
  }
}

template <typename IntType>
uint64_t AtomicExchange(uint64_t arg1, uint64_t arg2, bool aq, bool rl) {
  static_assert(std::is_integral_v<IntType>, "AtomicExchange: IntType must be integral");
  static_assert(std::is_signed_v<IntType>, "AtomicExchange: IntType must be signed");
  auto ptr = ToHostAddr<IntType>(arg1);
  return __atomic_exchange_n(ptr, IntType(arg2), AqRlToMemoryOrder(aq, rl));
}

template <typename IntType>
uint64_t AtomicAdd(uint64_t arg1, uint64_t arg2, bool aq, bool rl) {
  static_assert(std::is_integral_v<IntType>, "AtomicAdd: IntType must be integral");
  static_assert(std::is_signed_v<IntType>, "AtomicAdd: IntType must be signed");
  auto ptr = ToHostAddr<IntType>(arg1);
  return __atomic_fetch_add(ptr, IntType(arg2), AqRlToMemoryOrder(aq, rl));
}

template <typename IntType>
uint64_t AtomicXor(uint64_t arg1, uint64_t arg2, bool aq, bool rl) {
  static_assert(std::is_integral_v<IntType>, "AtomicXor: IntType must be integral");
  static_assert(std::is_signed_v<IntType>, "AtomicXor: IntType must be signed");
  auto ptr = ToHostAddr<IntType>(arg1);
  return __atomic_fetch_xor(ptr, IntType(arg2), AqRlToMemoryOrder(aq, rl));
}

template <typename IntType>
uint64_t AtomicAnd(uint64_t arg1, uint64_t arg2, bool aq, bool rl) {
  static_assert(std::is_integral_v<IntType>, "AtomicAnd: IntType must be integral");
  static_assert(std::is_signed_v<IntType>, "AtomicAnd: IntType must be signed");
  auto ptr = ToHostAddr<IntType>(arg1);
  return __atomic_fetch_and(ptr, IntType(arg2), AqRlToMemoryOrder(aq, rl));
}

template <typename IntType>
uint64_t AtomicOr(uint64_t arg1, uint64_t arg2, bool aq, bool rl) {
  static_assert(std::is_integral_v<IntType>, "AtomicOr: IntType must be integral");
  static_assert(std::is_signed_v<IntType>, "AtomicOr: IntType must be signed");
  auto ptr = ToHostAddr<IntType>(arg1);
  return __atomic_fetch_or(ptr, IntType(arg2), AqRlToMemoryOrder(aq, rl));
}

template <typename IntType>
uint64_t AtomicMin(uint64_t arg1, uint64_t arg2, bool aq, bool rl) {
  static_assert(std::is_integral_v<IntType>, "AtomicMin: IntType must be integral");
  static_assert(std::is_signed_v<IntType>, "AtomicMin: IntType must be signed");
  auto ptr = ToHostAddr<IntType>(arg1);
  return __atomic_fetch_min(ptr, IntType(arg2), AqRlToMemoryOrder(aq, rl));
}

template <typename IntType>
uint64_t AtomicMax(uint64_t arg1, uint64_t arg2, bool aq, bool rl) {
  static_assert(std::is_integral_v<IntType>, "AtomicMax: IntType must be integral");
  static_assert(std::is_signed_v<IntType>, "AtomicMax: IntType must be signed");
  auto ptr = ToHostAddr<IntType>(arg1);
  return __atomic_fetch_max(ptr, IntType(arg2), AqRlToMemoryOrder(aq, rl));
}

template <typename IntType>
uint64_t AtomicMinu(uint64_t arg1, uint64_t arg2, bool aq, bool rl) {
  static_assert(std::is_integral_v<IntType>, "AtomicMinu: IntType must be integral");
  static_assert(!std::is_signed_v<IntType>, "AtomicMinu: IntType must be unsigned");
  auto ptr = ToHostAddr<IntType>(arg1);
  return std::make_signed_t<IntType>(
      __atomic_fetch_min(ptr, IntType(arg2), AqRlToMemoryOrder(aq, rl)));
}

template <typename IntType>
uint64_t AtomicMaxu(uint64_t arg1, uint64_t arg2, bool aq, bool rl) {
  static_assert(std::is_integral_v<IntType>, "AtomicMaxu: IntType must be integral");
  static_assert(!std::is_signed_v<IntType>, "AtomicMaxu: IntType must be unsigned");
  auto ptr = ToHostAddr<IntType>(arg1);
  return std::make_signed_t<IntType>(
      __atomic_fetch_max(ptr, IntType(arg2), AqRlToMemoryOrder(aq, rl)));
}

}  // namespace

}  // namespace berberis

#endif  // BERBERIS_INTERPRETER_RISCV64_ATOMICS_H_
