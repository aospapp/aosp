//
// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

#ifndef NATIVE_BRIDGE_SUPPORT_VDSO_INTERCEPTABLE_FUNCTIONS_H_
#define NATIVE_BRIDGE_SUPPORT_VDSO_INTERCEPTABLE_FUNCTIONS_H_

#include <assert.h>
#include <stdint.h>

#include "native_bridge_support/vdso/vdso.h"

#if defined(__arm__)

#define INTERCEPTABLE_STUB_ASM_CALL(name)                      \
  extern "C" void __attribute((target("arm"), naked)) name() { \
    __asm__ __volatile__(                                      \
        "ldr r12, 1f\n"                                        \
        "0: ldr r12, [pc, r12]\n"                              \
        "bx r12\n"                                             \
        ".p2align 2\n"                                         \
        "1: .long " #name "_var-(0b+8)");                      \
  }

#elif defined(__aarch64__)

#define INTERCEPTABLE_STUB_ASM_CALL(name)            \
  extern "C" void __attribute((naked)) name() {      \
    __asm__ __volatile__("adrp x8, " #name           \
                         "_var\n"                    \
                         "ldr x8, [x8, :lo12:" #name \
                         "_var]\n"                   \
                         "br x8");                   \
  }

#else

#error Unknown architecture, only arm and aarch64 are supported.

#endif

#define DEFINE_INTERCEPTABLE_STUB_VARIABLE(name) uintptr_t name;

#define INIT_INTERCEPTABLE_STUB_VARIABLE(library_name, name) \
  name =                                                     \
      *reinterpret_cast<uintptr_t*>(native_bridge_find_proxy_library_symbol(library_name, #name));

#define DEFINE_INTERCEPTABLE_STUB_FUNCTION(name)                     \
  extern "C" void name();                                            \
  static uintptr_t __attribute((used)) name##_var asm(#name "_var"); \
  INTERCEPTABLE_STUB_ASM_CALL(name);

#define INIT_INTERCEPTABLE_STUB_FUNCTION(library_name, name) \
  name##_var = native_bridge_find_proxy_library_symbol(library_name, #name);

#endif  // NATIVE_BRIDGE_SUPPORT_VDSO_INTERCEPTABLE_FUNCTIONS_H_
