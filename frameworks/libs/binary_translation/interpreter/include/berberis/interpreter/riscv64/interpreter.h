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

#ifndef BERBERIS_INTERPRETER_RISCV64_INTERPRETER_H_
#define BERBERIS_INTERPRETER_RISCV64_INTERPRETER_H_

#include "cstdint"

#include "berberis/guest_state/guest_state_riscv64.h"

namespace berberis {

void InterpretInsn(ThreadState* state);
void RunSyscall(ThreadState* state);

}  // namespace berberis

#endif  // BERBERIS_INTERPRETER_RISCV64_INTERPRETER_H_
