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

#ifndef BERBERIS_DECODER_RISCV64_DECODER_H_
#define BERBERIS_DECODER_RISCV64_DECODER_H_

#include <climits>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <type_traits>

#include "berberis/base/bit_util.h"

namespace berberis {

// Decode() method takes a sequence of bytes and decodes it into the instruction opcode and fields.
// The InsnConsumer's method corresponding to the decoded opcode is called with the decoded fields
// as an argument. Returned is the instruction size.
template <class InsnConsumer>
class Decoder {
 public:
  explicit Decoder(InsnConsumer* insn_consumer) : insn_consumer_(insn_consumer) {}

  // https://eel.is/c++draft/enum#dcl.enum-8
  // For an enumeration whose underlying type is fixed, the values of the enumeration are the values
  // of the underlying type. Otherwise, the values of the enumeration are the values representable
  // by a hypothetical integer type with minimal width M such that all enumerators can be
  // represented. The width of the smallest bit-field large enough to hold all the values of the
  // enumeration type is M. It is possible to define an enumeration that has values not defined by
  // any of its enumerators. If the enumerator-list is empty, the values of the enumeration are as
  // if the enumeration had a single enumerator with value 0.

  // To ensure that we wouldn't trigger UB by accident each opcode includes kMaxXXX value (kOpOcode,
  // kSystemOpcode and so on) which have all possible bit values set.
  enum class BaseOpcode {
    kLoad = 0b00'000,
    kLoadFp = 0b00'001,
    kCustom0 = 0b00'010,
    kMiscMem = 0b00'011,
    kOpImm = 0b00'100,
    kAuipc = 0b00'101,
    kOpImm32 = 0b00'110,
    // Reserved 0b00'111,
    kStore = 0b01'000,
    kStoreFp = 0b01'001,
    kCustom1 = 0b01'010,
    kAmo = 0b01'011,
    kOp = 0b01'100,
    kLui = 0b01'101,
    kOp32 = 0b01'110,
    // Reserved 0b01'111,
    kMAdd = 0b10'000,
    kMSub = 0b10'001,
    kNmSub = 0b10'010,
    kNmAdd = 0b10'011,
    kOpFp = 0b10'100,
    // Reserved 0b10'101,
    kCustom2 = 0b10'110,
    // Reserved 0b10'111,
    kBranch = 0b11'000,
    kJalr = 0b11'001,
    // Reserved 0b11'010,
    kJal = 0b11'011,
    kSystem = 0b11'100,
    // Reserved 0b11'101,
    kCustom3 = 0b11'110,
    // Reserved 0b11'111,
    kMaxBaseOpcode = 0b11'111,
  };

  enum class CompressedOpcode {
    kAddi4spn = 0b00'000,
    kFld = 0b001'00,
    kLw = 0b010'00,
    kLd = 0b011'00,
    // Reserved 0b00'100
    kFsd = 0b101'00,
    kSw = 0b110'00,
    kSd = 0b111'00,
    kAddi = 0b000'01,
    kAddiw = 0b001'01,
    kLi = 0b010'01,
    kLui_Addi16sp = 0b011'01,
    kMisc_Alu = 0b100'01,
    kJ = 0b101'01,
    kBeqz = 0b110'01,
    kBnez = 0b111'01,
    kSlli = 0b000'10,
    kFldsp = 0b001'10,
    kLwsp = 0b010'10,
    kDsp = 0b011'10,
    kJr_Jalr_Mv_Add = 0b100'10,
    kFdsp = 0b101'10,
    kSwsp = 0b110'10,
    kSdsp = 0b111'10,
    // instruction with 0bxxx'11 opcodes are not compressed instruction and can not be in this
    // table.
    kMaxCompressedOpcode = 0b111'11,
  };

  enum class CsrOpcode {
    kCsrrw = 0b01,
    kCsrrs = 0b10,
    kCsrrc = 0b11,
    kMaxCsrOpcode = 0b11,
  };

  enum class CsrImmOpcode {
    kCsrrwi = 0b01,
    kCsrrsi = 0b10,
    kCsrrci = 0b11,
    kMaxCsrOpcode = 0b11,
  };

  enum class FenceOpcode {
    kFence = 0b0000,
    kFenceTso = 0b1000,
    kFenceMaxOpcode = 0b1111,
  };

  enum class OpOpcode {
    kAdd = 0b0000'000'000,
    kSub = 0b0100'000'000,
    kSll = 0b0000'000'001,
    kSlt = 0b0000'000'010,
    kSltu = 0b0000'000'011,
    kXor = 0b0000'000'100,
    kSrl = 0b0000'000'101,
    kSra = 0b0100'000'101,
    kOr = 0b0000'000'110,
    kAnd = 0b0000'000'111,
    kMul = 0b0000'001'000,
    kMulh = 0b0000'001'001,
    kMulhsu = 0b0000'001'010,
    kMulhu = 0b0000'001'011,
    kDiv = 0b0000'001'100,
    kDivu = 0b0000'001'101,
    kRem = 0b0000'001'110,
    kRemu = 0b0000'001'111,
    kMaxOpOpcode = 0b1111'111'111,
  };

  enum class Op32Opcode {
    kAddw = 0b0000'000'000,
    kSubw = 0b0100'000'000,
    kSllw = 0b0000'000'001,
    kSrlw = 0b0000'000'101,
    kSraw = 0b0100'000'101,
    kMulw = 0b0000'001'000,
    kDivw = 0b0000'001'100,
    kDivuw = 0b0000'001'101,
    kRemw = 0b0000'001'110,
    kRemuw = 0b0000'001'111,
    kMaxOp32Opcode = 0b1111'111'111,
  };

  enum class AmoOpcode {
    kLrW = 0b00010'010,
    kScW = 0b00011'010,
    kAmoswapW = 0b00001'010,
    kAmoaddW = 0b00000'010,
    kAmoxorW = 0b00100'010,
    kAmoandW = 0b01100'010,
    kAmoorW = 0b01000'010,
    kAmominW = 0b10000'010,
    kAmomaxW = 0b10100'010,
    kAmominuW = 0b11000'010,
    kAmomaxuW = 0b11100'010,
    kLrD = 0b00010'011,
    kScD = 0b00011'011,
    kAmoswapD = 0b00001'011,
    kAmoaddD = 0b00000'011,
    kAmoxorD = 0b00100'011,
    kAmoandD = 0b01100'011,
    kAmoorD = 0b01000'011,
    kAmominD = 0b10000'011,
    kAmomaxD = 0b10100'011,
    kAmominuD = 0b11000'011,
    kAmomaxuD = 0b11100'011,
    kMaxAmoOpcode = 0b11111'111,
  };

  enum class OpFpOpcode {
    // Bit #2 = 1 means rm is an opcode extension.
    // Bit #3 = 1 means rs2 is an opcode extension
    // Bits #4, #1, and #0 - actual opcode.
    kFAdd = 0b0'0'0'00,
    kFSub = 0b0'0'0'01,
    kFMul = 0b0'0'0'10,
    kFDiv = 0b0'0'0'11,
    kMaxOpFpOpcode = 0b1'1'1'11,
  };

  enum class LoadOpcode {
    kLb = 0b000,
    kLh = 0b001,
    kLw = 0b010,
    kLd = 0b011,
    kLbu = 0b100,
    kLhu = 0b101,
    kLwu = 0b110,
    kMaxLoadOpcode = 0b1111,
  };

  enum class LoadFpOpcode {
    kFlw = 0b010,
    kFld = 0b011,
    kLoadFpMaxOpcode = 0b111,
  };

  enum class OpImmOpcode {
    kAddi = 0b000,
    kSlti = 0b010,
    kSltiu = 0b011,
    kXori = 0b100,
    kOri = 0b110,
    kAndi = 0b111,
    kMaxOpImmOpcode = 0b111,
  };

  enum class OpImm32Opcode {
    kAddiw = 0b000,
    kMaxOpImm32Opcode = 0b111,
  };

  enum class ShiftImmOpcode {
    kSlli = 0b000000'001,
    kSrli = 0b000000'101,
    kSrai = 0b010000'101,
    kMaxShiftImmOpcode = 0b11111'111,
  };

  enum class ShiftImm32Opcode {
    kSlliw = 0b0000000'001,
    kSrliw = 0b0000000'101,
    kSraiw = 0b0100000'101,
    kMaxShiftImm32Opcode = 0b111111'111,
  };

  enum class StoreOpcode {
    kSb = 0b000,
    kSh = 0b001,
    kSw = 0b010,
    kSd = 0b011,
    kMaxStoreOpcode = 0b111,
  };

  enum class StoreFpOpcode {
    kFsw = 0b010,
    kFsd = 0b011,
    kMaxStoreFpOpcode = 0b111,
  };

  enum class SystemOpcode {
    kEcall = 0b000000000000'00000'000'00000,
    kEbreak = 0b000000000001'00000'000'00000,
    kMaxSystemOpcode = 0b111111111111'11111'111'11111,
  };

  enum class BranchOpcode {
    kBeq = 0b000,
    kBne = 0b001,
    kBlt = 0b100,
    kBge = 0b101,
    kBltu = 0b110,
    kBgeu = 0b111,
    kMaxBranchOpcode = 0b111,
  };

  enum class CsrRegister {
    kFFlags = 0b00'00'0000'0001,
    kFrm = 0b00'00'0000'0010,
    kFCsr = 0b00'00'0000'0011,
    kMaxCsrRegister = 0b11'11'1111'1111,
  };

  enum class FloatSize {
    kFloat = 0b00,
    kDouble = 0b01,
    kHalf = 0b10,
    kQuad = 0b11,
    kMaxFloatSize = 0b11,
  };

  struct AmoArgs {
    AmoOpcode opcode;
    uint8_t dst;
    uint8_t src1;
    uint8_t src2;
    bool rl : 1;
    bool aq : 1;
  };

  struct CsrArgs {
    CsrOpcode opcode;
    uint8_t dst;
    uint8_t src;
    CsrRegister csr;
  };

  struct CsrImmArgs {
    CsrImmOpcode opcode;
    uint8_t dst;
    uint8_t imm;
    CsrRegister csr;
  };

  struct FenceArgs {
    FenceOpcode opcode;
    uint8_t dst;
    uint8_t src;
    bool sw : 1;
    bool sr : 1;
    bool so : 1;
    bool si : 1;
    bool pw : 1;
    bool pr : 1;
    bool po : 1;
    bool pi : 1;
  };

  struct FenceIArgs {
    uint8_t dst;
    uint8_t src;
    int16_t imm;
  };

  template <typename OpcodeType>
  struct OpArgsTemplate {
    OpcodeType opcode;
    uint8_t dst;
    uint8_t src1;
    uint8_t src2;
  };

  using OpArgs = OpArgsTemplate<OpOpcode>;
  using Op32Args = OpArgsTemplate<Op32Opcode>;

  template <typename OpcodeType>
  struct LoadArgsTemplate {
    OpcodeType opcode;
    uint8_t dst;
    uint8_t src;
    int16_t offset;
  };

  using LoadArgs = LoadArgsTemplate<LoadOpcode>;
  using LoadFpArgs = LoadArgsTemplate<LoadFpOpcode>;

  template <typename OpcodeType>
  struct OpImmArgsTemplate {
    OpcodeType opcode;
    uint8_t dst;
    uint8_t src;
    int16_t imm;
  };

  using OpImmArgs = OpImmArgsTemplate<OpImmOpcode>;
  using OpImm32Args = OpImmArgsTemplate<OpImm32Opcode>;

  struct SystemArgs {
    SystemOpcode opcode;
  };

  template <typename OpcodeType>
  struct ShiftImmArgsTemplate {
    OpcodeType opcode;
    uint8_t dst;
    uint8_t src;
    uint8_t imm;
  };

  using ShiftImmArgs = ShiftImmArgsTemplate<ShiftImmOpcode>;
  using ShiftImm32Args = ShiftImmArgsTemplate<ShiftImm32Opcode>;

  template <typename OpcodeType>
  struct StoreArgsTemplate {
    OpcodeType opcode;
    uint8_t src;
    int16_t offset;
    uint8_t data;
  };

  using StoreArgs = StoreArgsTemplate<StoreOpcode>;
  using StoreFpArgs = StoreArgsTemplate<StoreFpOpcode>;

  struct OpFpArgs {
    OpFpOpcode opcode;
    FloatSize float_size;
    uint8_t dst;
    uint8_t src1;
    uint8_t src2;
    uint8_t rm;
  };

  struct BranchArgs {
    BranchOpcode opcode;
    uint8_t src1;
    uint8_t src2;
    int16_t offset;
  };

  struct UpperImmArgs {
    uint8_t dst;
    int32_t imm;
  };

  struct JumpAndLinkArgs {
    uint8_t dst;
    int32_t offset;
    uint8_t insn_len;
  };

  struct JumpAndLinkRegisterArgs {
    uint8_t dst;
    uint8_t base;
    int16_t offset;
    uint8_t insn_len;
  };

  uint8_t Decode(const uint16_t* code) {
    constexpr uint16_t kInsnLenMask = uint16_t{0b11};
    if ((*code & kInsnLenMask) != kInsnLenMask) {
      code_ = *code;
      return DecodeCompressedInstruction();
    }
    // Warning: do not cast and dereference the pointer
    // since the address may not be 4-bytes aligned.
    memcpy(&code_, code, sizeof(code_));
    return DecodeBaseInstruction();
  }

  uint8_t DecodeCompressedInstruction() {
    CompressedOpcode opcode_bits{(GetBits<uint8_t, 13, 3>() << 2) | GetBits<uint8_t, 0, 2>()};

    switch (opcode_bits) {
      case CompressedOpcode::kJ:
        DecodeCJ();
        break;
      case CompressedOpcode::kAddi4spn:
        DecodeCAddi4spn();
        break;
      case CompressedOpcode::kAddi:
        DecodeCAddi();
        break;
      case CompressedOpcode::kFld:
        DecodeCompressedLoadStore<LoadFpOpcode::kFld>();
        break;
      case CompressedOpcode::kLw:
        DecodeCompressedLoadStore<LoadOpcode::kLw>();
        break;
      case CompressedOpcode::kLd:
        DecodeCompressedLoadStore<LoadOpcode::kLd>();
        break;
      case CompressedOpcode::kFsd:
        DecodeCompressedLoadStore<StoreFpOpcode::kFsd>();
        break;
      case CompressedOpcode::kSd:
        DecodeCompressedLoadStore<StoreOpcode::kSd>();
        break;
      default:
        insn_consumer_->Unimplemented();
    }
    return 2;
  }

  template <auto opcode>
  void DecodeCompressedLoadStore() {
    uint8_t low_imm = GetBits<uint8_t, 5, 2>();
    uint8_t high_imm = GetBits<uint8_t, 10, 3>();
    uint8_t imm;
    if constexpr ((uint8_t(opcode) & 1) == 0) {
      constexpr uint8_t kLwLow[4] = {0x0, 0x40, 0x04, 0x44};
      imm = (kLwLow[low_imm] | high_imm << 3);
    } else {
      imm = (low_imm << 6 | high_imm << 3);
    }
    uint8_t rd = GetBits<uint8_t, 2, 3>();
    uint8_t rs = GetBits<uint8_t, 7, 3>();
    if constexpr (std::is_same_v<decltype(opcode), StoreOpcode> ||
                  std::is_same_v<decltype(opcode), StoreFpOpcode>) {
      const StoreArgsTemplate<decltype(opcode)> args = {
          .opcode = opcode,
          .src = uint8_t(8 + rs),
          .offset = imm,
          .data = uint8_t(8 + rd),
      };
      insn_consumer_->Store(args);
    } else {
      const LoadArgsTemplate<decltype(opcode)> args = {
          .opcode = opcode,
          .dst = uint8_t(8 + rd),
          .src = uint8_t(8 + rs),
          .offset = imm,
      };
      insn_consumer_->Load(args);
    }
  }

  void DecodeCAddi() {
    uint8_t low_imm = GetBits<uint8_t, 2, 5>();
    uint8_t high_imm = GetBits<uint8_t, 12, 1>();
    int8_t imm = SignExtend<6>(high_imm << 5 | low_imm);
    uint8_t r = GetBits<uint8_t, 7, 5>();
    if (r == 0 || imm == 0) {
      insn_consumer_->Nop();
    }
    const OpImmArgs args = {
        .opcode = OpImmOpcode::kAddi,
        .dst = r,
        .src = r,
        .imm = imm,
    };
    insn_consumer_->OpImm(args);
  }

  void DecodeCJ() {
    constexpr uint16_t kJHigh[32] = {
        0x0,    0x400,  0x100,  0x500,  0x200,  0x600,  0x300,  0x700,  0x10,   0x410,  0x110,
        0x510,  0x210,  0x610,  0x310,  0x710,  0xf800, 0xfc00, 0xf900, 0xfd00, 0xfa00, 0xfe00,
        0xfb00, 0xff00, 0xf810, 0xfc10, 0xf910, 0xfd10, 0xfa10, 0xfe10, 0xfb10, 0xff10,
    };
    constexpr uint8_t kJLow[64] = {
        0x0,  0x20, 0x2,  0x22, 0x4,  0x24, 0x6,  0x26, 0x8,  0x28, 0xa,  0x2a, 0xc,
        0x2c, 0xe,  0x2e, 0x80, 0xa0, 0x82, 0xa2, 0x84, 0xa4, 0x86, 0xa6, 0x88, 0xa8,
        0x8a, 0xaa, 0x8c, 0xac, 0x8e, 0xae, 0x40, 0x60, 0x42, 0x62, 0x44, 0x64, 0x46,
        0x66, 0x48, 0x68, 0x4a, 0x6a, 0x4c, 0x6c, 0x4e, 0x6e, 0xc0, 0xe0, 0xc2, 0xe2,
        0xc4, 0xe4, 0xc6, 0xe6, 0xc8, 0xe8, 0xca, 0xea, 0xcc, 0xec, 0xce, 0xee,
    };
    const JumpAndLinkArgs args = {
        .dst = 0,
        .offset =
            bit_cast<int16_t>(kJHigh[GetBits<uint16_t, 8, 5>()]) | kJLow[GetBits<uint16_t, 2, 6>()],
        .insn_len = 2,
    };
    insn_consumer_->JumpAndLink(args);
  }

  void DecodeCAddi4spn() {
    constexpr uint8_t kAddi4spnHigh[16] = {
        0x0, 0x40, 0x80, 0xc0, 0x4, 0x44, 0x84, 0xc4, 0x8, 0x48, 0x88, 0xc8, 0xc, 0x4c, 0x8c, 0xcc};
    constexpr uint8_t kAddi4spnLow[16] = {
        0x0, 0x2, 0x1, 0x3, 0x10, 0x12, 0x11, 0x13, 0x20, 0x22, 0x21, 0x23, 0x30, 0x32, 0x31, 0x33};
    int16_t imm = (kAddi4spnHigh[GetBits<uint8_t, 9, 4>()] | kAddi4spnLow[GetBits<uint8_t, 5, 4>()])
                  << 2;
    // If immediate is zero then this instruction is treated as unimplemented.
    // This includes RISC-V dedicated 16bit “unimplemented instruction” 0x0000.
    if (imm == 0) {
      return Undefined();
    }
    const OpImmArgs args = {
        .opcode = OpImmOpcode::kAddi,
        .dst = uint8_t(8 + GetBits<uint8_t, 2, 3>()),
        .src = 2,
        .imm = imm,
    };
    insn_consumer_->OpImm(args);
  }

  uint8_t DecodeBaseInstruction() {
    BaseOpcode opcode_bits{GetBits<uint8_t, 2, 5>()};

    switch (opcode_bits) {
      case BaseOpcode::kMiscMem:
        DecodeMiscMem();
        break;
      case BaseOpcode::kOp:
        DecodeOp<OpOpcode>();
        break;
      case BaseOpcode::kOp32:
        DecodeOp<Op32Opcode>();
        break;
      case BaseOpcode::kAmo:
        DecodeAmo();
        break;
      case BaseOpcode::kLoad:
        DecodeLoad<LoadOpcode>();
        break;
      case BaseOpcode::kLoadFp:
        DecodeLoad<LoadFpOpcode>();
        break;
      case BaseOpcode::kOpImm:
        DecodeOp<OpImmOpcode, ShiftImmOpcode, 6>();
        break;
      case BaseOpcode::kOpImm32:
        DecodeOp<OpImm32Opcode, ShiftImm32Opcode, 5>();
        break;
      case BaseOpcode::kOpFp:
        DecodeOpFp();
        break;
      case BaseOpcode::kStore:
        DecodeStore<StoreOpcode>();
        break;
      case BaseOpcode::kStoreFp:
        DecodeStore<StoreFpOpcode>();
        break;
      case BaseOpcode::kBranch:
        DecodeBranch();
        break;
      case BaseOpcode::kJal:
        DecodeJumpAndLink();
        break;
      case BaseOpcode::kJalr:
        DecodeJumpAndLinkRegister();
        break;
      case BaseOpcode::kSystem:
        DecodeSystem();
        break;
      case BaseOpcode::kLui:
        DecodeLui();
        break;
      case BaseOpcode::kAuipc:
        DecodeAuipc();
        break;
      default:
        insn_consumer_->Unimplemented();
    }
    return 4;
  }

 private:
  template <typename ResultType, uint32_t start, uint32_t size>
  ResultType GetBits() {
    static_assert(std::is_unsigned_v<ResultType>, "Only unsigned types are supported");
    static_assert(sizeof(ResultType) * CHAR_BIT >= size, "Too small ResultType for size");
    static_assert((start + size) <= 32 && size > 0, "Invalid start or size value");
    uint32_t shifted_val = code_ << (32 - start - size);
    return static_cast<ResultType>(shifted_val >> (32 - size));
  }

  // Signextend bits from size to the corresponding signed type of sizeof(Type) size.
  // If the result of this function is assigned to a wider signed type it'll automatically
  // sign-extend.
  template <unsigned size, typename Type>
  static auto SignExtend(const Type val) {
    static_assert(std::is_integral_v<Type>, "Only integral types are supported");
    static_assert(size > 0 && size < (sizeof(Type) * CHAR_BIT), "Invalid size value");
    typedef std::make_signed_t<Type> SignedType;
    struct {
      SignedType val : size;
    } holder = {.val = static_cast<SignedType>(val)};
    // Compiler takes care of sign-extension of the field with the specified bit-length.
    return static_cast<SignedType>(holder.val);
  }

  void Undefined() {
    // TODO(b/265372622): Handle undefined differently from unimplemented.
    insn_consumer_->Unimplemented();
  }

  void DecodeMiscMem() {
    uint8_t low_opcode = GetBits<uint8_t, 12, 3>();
    switch (low_opcode) {
      case 0b000: {
        uint8_t high_opcode = GetBits<uint8_t, 28, 4>();
        FenceOpcode opcode = FenceOpcode{high_opcode};
        const FenceArgs args = {
            .opcode = opcode,
            .dst = GetBits<uint8_t, 7, 5>(),
            .src = GetBits<uint8_t, 15, 5>(),
            .sw = bool(GetBits<uint8_t, 20, 1>()),
            .sr = bool(GetBits<uint8_t, 21, 1>()),
            .so = bool(GetBits<uint8_t, 22, 1>()),
            .si = bool(GetBits<uint8_t, 23, 1>()),
            .pw = bool(GetBits<uint8_t, 24, 1>()),
            .pr = bool(GetBits<uint8_t, 25, 1>()),
            .pi = bool(GetBits<uint8_t, 26, 1>()),
            .po = bool(GetBits<uint8_t, 27, 1>()),
        };
        insn_consumer_->Fence(args);
        break;
      }
      case 0b001: {
        uint16_t imm = GetBits<uint16_t, 20, 12>();
        const FenceIArgs args = {
            .dst = GetBits<uint8_t, 7, 5>(),
            .src = GetBits<uint8_t, 15, 5>(),
            .imm = SignExtend<12>(imm),
        };
        insn_consumer_->FenceI(args);
        break;
      }
      default:
        return Undefined();
    }
  }

  template <typename OpcodeType>
  void DecodeOp() {
    uint16_t low_opcode = GetBits<uint16_t, 12, 3>();
    uint16_t high_opcode = GetBits<uint16_t, 25, 7>();
    OpcodeType opcode{int16_t(low_opcode | (high_opcode << 3))};
    const OpArgsTemplate<OpcodeType> args = {
        .opcode = opcode,
        .dst = GetBits<uint8_t, 7, 5>(),
        .src1 = GetBits<uint8_t, 15, 5>(),
        .src2 = GetBits<uint8_t, 20, 5>(),
    };
    insn_consumer_->Op(args);
  }

  void DecodeAmo() {
    uint16_t low_opcode = GetBits<uint16_t, 12, 3>();
    uint16_t high_opcode = GetBits<uint16_t, 27, 5>();
    // lr instruction must have rs2 == 0
    if (high_opcode == 0b00010 && GetBits<uint8_t, 20, 5>() != 0) {
      return Undefined();
    }
    AmoOpcode opcode = AmoOpcode{low_opcode | (high_opcode << 3)};
    const AmoArgs args = {
        .opcode = opcode,
        .dst = GetBits<uint8_t, 7, 5>(),
        .src1 = GetBits<uint8_t, 15, 5>(),
        .src2 = GetBits<uint8_t, 20, 5>(),
        .rl = bool(GetBits<uint8_t, 25, 1>()),
        .aq = bool(GetBits<uint8_t, 26, 1>()),
    };
    insn_consumer_->Amo(args);
  }

  void DecodeLui() {
    int32_t imm = GetBits<uint32_t, 12, 20>();
    const UpperImmArgs args = {
        .dst = GetBits<uint8_t, 7, 5>(),
        .imm = imm << 12,
    };
    insn_consumer_->Lui(args);
  }

  void DecodeAuipc() {
    int32_t imm = GetBits<uint32_t, 12, 20>();
    const UpperImmArgs args = {
        .dst = GetBits<uint8_t, 7, 5>(),
        .imm = imm << 12,
    };
    insn_consumer_->Auipc(args);
  }

  template <typename OpcodeType>
  void DecodeLoad() {
    OpcodeType opcode{GetBits<uint8_t, 12, 3>()};
    const LoadArgsTemplate<OpcodeType> args = {
        .opcode = opcode,
        .dst = GetBits<uint8_t, 7, 5>(),
        .src = GetBits<uint8_t, 15, 5>(),
        .offset = SignExtend<12>(GetBits<uint16_t, 20, 12>()),
    };
    insn_consumer_->Load(args);
  }

  template <typename OpcodeType>
  void DecodeStore() {
    OpcodeType opcode{GetBits<uint8_t, 12, 3>()};

    uint16_t low_imm = GetBits<uint16_t, 7, 5>();
    uint16_t high_imm = GetBits<uint16_t, 25, 7>();

    const StoreArgsTemplate<OpcodeType> args = {
        .opcode = opcode,
        .src = GetBits<uint8_t, 15, 5>(),
        .offset = SignExtend<12>(int16_t(low_imm | (high_imm << 5))),
        .data = GetBits<uint8_t, 20, 5>(),
    };
    insn_consumer_->Store(args);
  }

  template <typename OpOpcodeType, typename ShiftOcodeType, uint32_t kShiftFieldSize>
  void DecodeOp() {
    uint8_t low_opcode = GetBits<uint8_t, 12, 3>();
    if (low_opcode != 0b001 && low_opcode != 0b101) {
      OpOpcodeType opcode{low_opcode};

      uint16_t imm = GetBits<uint16_t, 20, 12>();

      const OpImmArgsTemplate<OpOpcodeType> args = {
          .opcode = opcode,
          .dst = GetBits<uint8_t, 7, 5>(),
          .src = GetBits<uint8_t, 15, 5>(),
          .imm = SignExtend<12>(imm),
      };
      insn_consumer_->OpImm(args);
    } else {
      uint16_t high_opcode = GetBits<uint16_t, 20 + kShiftFieldSize, 12 - kShiftFieldSize>();
      ShiftOcodeType opcode{int16_t(low_opcode | (high_opcode << 3))};

      const ShiftImmArgsTemplate<ShiftOcodeType> args = {
          .opcode = opcode,
          .dst = GetBits<uint8_t, 7, 5>(),
          .src = GetBits<uint8_t, 15, 5>(),
          .imm = GetBits<uint8_t, 20, kShiftFieldSize>(),
      };
      insn_consumer_->OpImm(args);
    }
  }

  void DecodeBranch() {
    BranchOpcode opcode{GetBits<uint8_t, 12, 3>()};

    // Decode the offset.
    auto low_imm = GetBits<uint16_t, 8, 4>();
    auto mid_imm = GetBits<uint16_t, 25, 6>();
    auto bit11_imm = GetBits<uint16_t, 7, 1>();
    auto bit12_imm = GetBits<uint16_t, 31, 1>();
    auto offset =
        static_cast<int16_t>(low_imm | (mid_imm << 4) | (bit11_imm << 10) | (bit12_imm << 11));

    const BranchArgs args = {
        .opcode = opcode,
        .src1 = GetBits<uint8_t, 15, 5>(),
        .src2 = GetBits<uint8_t, 20, 5>(),
        // The offset is encoded as 2-byte units, we need to multiply by 2.
        .offset = SignExtend<13>(int16_t(offset * 2)),
    };
    insn_consumer_->Branch(args);
  }

  void DecodeJumpAndLink() {
    // Decode the offset.
    auto low_imm = GetBits<uint32_t, 21, 10>();
    auto mid_imm = GetBits<uint32_t, 12, 8>();
    auto bit11_imm = GetBits<uint32_t, 20, 1>();
    auto bit20_imm = GetBits<uint32_t, 31, 1>();
    auto offset =
        static_cast<int32_t>(low_imm | (bit11_imm << 10) | (mid_imm << 11) | (bit20_imm << 19));

    const JumpAndLinkArgs args = {
        .dst = GetBits<uint8_t, 7, 5>(),
        // The offset is encoded as 2-byte units, we need to multiply by 2.
        .offset = SignExtend<21>(offset * 2),
        .insn_len = 4,
    };
    insn_consumer_->JumpAndLink(args);
  }

  void DecodeOpFp() {
    uint8_t float_size = GetBits<uint8_t, 25, 2>();
    uint8_t opcode_bits = GetBits<uint8_t, 27, 5>();
    const OpFpArgs args = {
        .opcode = OpFpOpcode(opcode_bits),
        .float_size = FloatSize(float_size),
        .dst = GetBits<uint8_t, 7, 5>(),
        .src1 = GetBits<uint8_t, 15, 5>(),
        .src2 = GetBits<uint8_t, 20, 5>(),
        .rm = GetBits<uint8_t, 12, 3>(),
    };
    insn_consumer_->OpFp(args);
  }

  void DecodeSystem() {
    uint8_t low_opcode = GetBits<uint8_t, 12, 2>();
    if (low_opcode == 0b00) {
      int32_t opcode = GetBits<uint32_t, 7, 25>();
      const SystemArgs args = {
          .opcode = SystemOpcode(opcode),
      };
      return insn_consumer_->System(args);
    }
    if (GetBits<uint8_t, 14, 1>()) {
      CsrImmOpcode opcode = CsrImmOpcode(low_opcode);
      const CsrImmArgs args = {
          .opcode = opcode,
          .dst = GetBits<uint8_t, 7, 5>(),
          .imm = GetBits<uint8_t, 15, 5>(),
          .csr = CsrRegister(GetBits<uint16_t, 20, 12>()),
      };
      return insn_consumer_->Csr(args);
    }
    CsrOpcode opcode = CsrOpcode(low_opcode);
    const CsrArgs args = {
        .opcode = opcode,
        .dst = GetBits<uint8_t, 7, 5>(),
        .src = GetBits<uint8_t, 15, 5>(),
        .csr = CsrRegister(GetBits<uint16_t, 20, 12>()),
    };
    return insn_consumer_->Csr(args);
  }

  void DecodeJumpAndLinkRegister() {
    if (GetBits<uint8_t, 12, 3>() != 0b000) {
      Undefined();
      return;
    }
    // Decode sign-extend offset.
    int16_t offset = GetBits<uint16_t, 20, 12>();
    offset = static_cast<int16_t>(offset << 4) >> 4;

    const JumpAndLinkRegisterArgs args = {
        .dst = GetBits<uint8_t, 7, 5>(),
        .base = GetBits<uint8_t, 15, 5>(),
        .offset = offset,
        .insn_len = 4,
    };
    insn_consumer_->JumpAndLinkRegister(args);
  }

  InsnConsumer* insn_consumer_;
  uint32_t code_;
};

}  // namespace berberis

#endif  // BERBERIS_DECODER_RISCV64_DECODER_H_
