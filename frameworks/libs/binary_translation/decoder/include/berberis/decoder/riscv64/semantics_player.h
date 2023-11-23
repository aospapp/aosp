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

#ifndef BERBERIS_DECODER_RISCV64_SEMANTICS_PLAYER_H_
#define BERBERIS_DECODER_RISCV64_SEMANTICS_PLAYER_H_

#include "berberis/base/overloaded.h"
#include "berberis/decoder/riscv64/decoder.h"

namespace berberis {

// This class expresses the semantics of instructions by calling a sequence of SemanticsListener
// callbacks.
template <class SemanticsListener>
class SemanticsPlayer {
 public:
  using Decoder = Decoder<SemanticsPlayer>;
  using Register = typename SemanticsListener::Register;
  using FpRegister = typename SemanticsListener::FpRegister;

  explicit SemanticsPlayer(SemanticsListener* listener) : listener_(listener) {}

  // Decoder's InsnConsumer implementation.

  void Csr(const typename Decoder::CsrArgs& args) {
    Register result;
    Register arg = GetRegOrZero(args.src);
    result = listener_->Csr(args.opcode, arg, args.csr);
    SetRegOrIgnore(args.dst, result);
  }

  void Csr(const typename Decoder::CsrImmArgs& args) {
    Register result;
    result = listener_->Csr(args.opcode, args.imm, args.csr);
    SetRegOrIgnore(args.dst, result);
  }

  void Fence(const typename Decoder::FenceArgs& args) {
    listener_->Fence(args.opcode,
                     args.src,
                     args.sw,
                     args.sr,
                     args.so,
                     args.si,
                     args.pw,
                     args.pr,
                     args.po,
                     args.pi);
    // The unused fields in the FENCE instructions — args.src and args.dst — are reserved for
    // finer-grain fences in future extensions. For forward compatibility, base implementations
    // shall ignore these fields, and standard software shall zero these fields. Likewise, many
    // args.opcode and predecessor/successor set settings are also reserved for future use. Base
    // implementations shall treat all such reserved configurations as normal fences with
    // args.opcode=0000, and standard software shall use only non-reserved configurations.
  }

  void FenceI(const typename Decoder::FenceIArgs& args) {
    Register arg = GetRegOrZero(args.src);
    listener_->FenceI(arg, args.imm);
    // The unused fields in the FENCE.I instruction, imm[11:0], rs1, and rd, are reserved for
    // finer-grain fences in future extensions. For forward compatibility, base implementations
    // shall ignore these fields, and standard software shall zero these fields.
  }

  template <typename OpArgs>
  void Op(OpArgs&& args) {
    Register arg1 = GetRegOrZero(args.src1);
    Register arg2 = GetRegOrZero(args.src2);
    Register result = Overloaded{[&](const typename Decoder::OpArgs& args) {
                                   return listener_->Op(args.opcode, arg1, arg2);
                                 },
                                 [&](const typename Decoder::Op32Args& args) {
                                   return listener_->Op32(args.opcode, arg1, arg2);
                                 }}(args);
    SetRegOrIgnore(args.dst, result);
  };

  void Amo(const typename Decoder::AmoArgs& args) {
    Register arg1 = GetRegOrZero(args.src1);
    Register arg2 = GetRegOrZero(args.src2);
    Register result = listener_->Amo(args.opcode, arg1, arg2, args.aq, args.rl);
    SetRegOrIgnore(args.dst, result);
  };

  void Lui(const typename Decoder::UpperImmArgs& args) {
    Register result = listener_->Lui(args.imm);
    SetRegOrIgnore(args.dst, result);
  }

  void Auipc(const typename Decoder::UpperImmArgs& args) {
    Register result = listener_->Auipc(args.imm);
    SetRegOrIgnore(args.dst, result);
  }

  void Load(const typename Decoder::LoadArgs& args) {
    Register arg = GetRegOrZero(args.src);
    Register result = listener_->Load(args.opcode, arg, args.offset);
    SetRegOrIgnore(args.dst, result);
  };

  void Load(const typename Decoder::LoadFpArgs& args) {
    Register arg = GetRegOrZero(args.src);
    FpRegister result = listener_->LoadFp(args.opcode, arg, args.offset);
    SetFpReg(args.dst, result);
  };

  template <typename OpImmArgs>
  void OpImm(OpImmArgs&& args) {
    Register arg = GetRegOrZero(args.src);
    Register result = Overloaded{[&](const typename Decoder::OpImmArgs& args) {
                                   return listener_->OpImm(args.opcode, arg, args.imm);
                                 },
                                 [&](const typename Decoder::OpImm32Args& args) {
                                   return listener_->OpImm32(args.opcode, arg, args.imm);
                                 },
                                 [&](const typename Decoder::ShiftImmArgs& args) {
                                   return listener_->ShiftImm(args.opcode, arg, args.imm);
                                 },
                                 [&](const typename Decoder::ShiftImm32Args& args) {
                                   return listener_->ShiftImm32(args.opcode, arg, args.imm);
                                 }}(args);
    SetRegOrIgnore(args.dst, result);
  };

  void OpFp(const typename Decoder::OpFpArgs& args) {
    FpRegister arg1 = GetFpReg(args.src1);
    FpRegister arg2 = GetFpReg(args.src2);
    FpRegister result = listener_->OpFp(args.opcode, args.float_size, args.rm, arg1, arg2);
    SetFpReg(args.dst, result);
  }

  void Store(const typename Decoder::StoreArgs& args) {
    Register arg = GetRegOrZero(args.src);
    Register data = GetRegOrZero(args.data);
    listener_->Store(args.opcode, arg, args.offset, data);
  };

  void Store(const typename Decoder::StoreFpArgs& args) {
    Register arg = GetRegOrZero(args.src);
    FpRegister data = GetFpReg(args.data);
    listener_->StoreFp(args.opcode, arg, args.offset, data);
  };

  void Branch(const typename Decoder::BranchArgs& args) {
    Register arg1 = GetRegOrZero(args.src1);
    Register arg2 = GetRegOrZero(args.src2);
    listener_->Branch(args.opcode, arg1, arg2, args.offset);
  };

  void JumpAndLink(const typename Decoder::JumpAndLinkArgs& args) {
    Register result = listener_->JumpAndLink(args.offset, args.insn_len);
    SetRegOrIgnore(args.dst, result);
  };

  void JumpAndLinkRegister(const typename Decoder::JumpAndLinkRegisterArgs& args) {
    Register base = GetRegOrZero(args.base);
    Register result = listener_->JumpAndLinkRegister(base, args.offset, args.insn_len);
    SetRegOrIgnore(args.dst, result);
  };

  // We may have executed a signal handler just after the syscall. If that handler changed x10, then
  // overwriting x10 here would be incorrect. On the other hand asynchronous signals are unlikely to
  // change CPU state, so we don't support this at the moment for simplicity."
  void System(const typename Decoder::SystemArgs& args) {
    if (args.opcode != Decoder::SystemOpcode::kEcall) {
      return Unimplemented();
    }
    Register syscall_nr = GetRegOrZero(17);
    Register arg0 = GetRegOrZero(10);
    Register arg1 = GetRegOrZero(11);
    Register arg2 = GetRegOrZero(12);
    Register arg3 = GetRegOrZero(13);
    Register arg4 = GetRegOrZero(14);
    Register arg5 = GetRegOrZero(15);
    Register result = listener_->Ecall(syscall_nr, arg0, arg1, arg2, arg3, arg4, arg5);
    SetRegOrIgnore(10, result);
  }

  void Nop() { listener_->Nop(); }

  void Unimplemented() { listener_->Unimplemented(); };

 private:
  Register GetRegOrZero(uint8_t reg) {
    return reg == 0 ? listener_->GetImm(0) : listener_->GetReg(reg);
  }

  void SetRegOrIgnore(uint8_t reg, Register value) {
    if (reg != 0) {
      listener_->SetReg(reg, value);
    }
  }

  FpRegister GetFpReg(uint8_t reg) { return listener_->GetFpReg(reg); }

  void SetFpReg(uint8_t reg, FpRegister value) { listener_->SetFpReg(reg, value); }

  SemanticsListener* listener_;
};

}  // namespace berberis

#endif  // BERBERIS_DECODER_RISCV64_SEMANTICS_PLAYER_H_
