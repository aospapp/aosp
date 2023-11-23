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

#include "gtest/gtest.h"

#include "berberis/runtime/execute_guest.h"

#include <cstdint>

#include "berberis/guest_state/guest_addr.h"
#include "berberis/guest_state/guest_state_riscv64.h"

namespace berberis {

namespace {

TEST(ExecuteGuestRiscv64, Basic) {
  const uint32_t code[] = {
      0x003100b3,  // add x1, x2, x3
      0x004090b3,  // sll x1, x1, x4
  };
  ThreadState state{};
  state.cpu.insn_addr = ToGuestAddr(&code[0]);
  SetXReg<2>(state.cpu, 10);
  SetXReg<3>(state.cpu, 11);
  SetXReg<4>(state.cpu, 1);
  ExecuteGuest(&state, ToGuestAddr(&code[0]) + 8);
  EXPECT_EQ(GetXReg<1>(state.cpu), 42u);
}

}  // namespace

}  // namespace berberis
