//===- llvm/InlineAsm.h - Class to represent inline asm strings -*- C++ -*-===//
//
// Part of the LLVM Project, under the Apache License v2.0 with LLVM Exceptions.
// See https://llvm.org/LICENSE.txt for license information.
// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
//
//===----------------------------------------------------------------------===//
//
// This class represents the inline asm strings, which are Value*'s that are
// used as the callee operand of call instructions.  InlineAsm's are uniqued
// like constants, and created via InlineAsm::get(...).
//
//===----------------------------------------------------------------------===//

#ifndef LLVM_IR_INLINEASM_H
#define LLVM_IR_INLINEASM_H

#include "llvm/ADT/StringRef.h"
#include "llvm/IR/Value.h"
#include <cassert>
#include <string>
#include <vector>

namespace llvm {

class FunctionType;
class PointerType;
template <class ConstantClass> class ConstantUniqueMap;

class InlineAsm final : public Value {
public:
  enum AsmDialect {
    AD_ATT,
    AD_Intel
  };

private:
  friend struct InlineAsmKeyType;
  friend class ConstantUniqueMap<InlineAsm>;

  std::string AsmString, Constraints;
  FunctionType *FTy;
  bool HasSideEffects;
  bool IsAlignStack;
  AsmDialect Dialect;

  InlineAsm(FunctionType *Ty, const std::string &AsmString,
            const std::string &Constraints, bool hasSideEffects,
            bool isAlignStack, AsmDialect asmDialect);

  /// When the ConstantUniqueMap merges two types and makes two InlineAsms
  /// identical, it destroys one of them with this method.
  void destroyConstant();

public:
  InlineAsm(const InlineAsm &) = delete;
  InlineAsm &operator=(const InlineAsm &) = delete;

  /// InlineAsm::get - Return the specified uniqued inline asm string.
  ///
  static InlineAsm *get(FunctionType *Ty, StringRef AsmString,
                        StringRef Constraints, bool hasSideEffects,
                        bool isAlignStack = false,
                        AsmDialect asmDialect = AD_ATT);

  bool hasSideEffects() const { return HasSideEffects; }
  bool isAlignStack() const { return IsAlignStack; }
  AsmDialect getDialect() const { return Dialect; }

  /// getType - InlineAsm's are always pointers.
  ///
  PointerType *getType() const {
    return reinterpret_cast<PointerType*>(Value::getType());
  }

  /// getFunctionType - InlineAsm's are always pointers to functions.
  ///
  FunctionType *getFunctionType() const;

  const std::string &getAsmString() const { return AsmString; }
  const std::string &getConstraintString() const { return Constraints; }

  /// Verify - This static method can be used by the parser to check to see if
  /// the specified constraint string is legal for the type.  This returns true
  /// if legal, false if not.
  ///
  static bool Verify(FunctionType *Ty, StringRef Constraints);

  // Constraint String Parsing
  enum ConstraintPrefix {
    isInput,            // 'x'
    isOutput,           // '=x'
    isClobber           // '~x'
  };

  using ConstraintCodeVector = std::vector<std::string>;

  struct SubConstraintInfo {
    /// MatchingInput - If this is not -1, this is an output constraint where an
    /// input constraint is required to match it (e.g. "0").  The value is the
    /// constraint number that matches this one (for example, if this is
    /// constraint #0 and constraint #4 has the value "0", this will be 4).
    int MatchingInput = -1;

    /// Code - The constraint code, either the register name (in braces) or the
    /// constraint letter/number.
    ConstraintCodeVector Codes;

    /// Default constructor.
    SubConstraintInfo() = default;
  };

  using SubConstraintInfoVector = std::vector<SubConstraintInfo>;
  struct ConstraintInfo;
  using ConstraintInfoVector = std::vector<ConstraintInfo>;

  struct ConstraintInfo {
    /// Type - The basic type of the constraint: input/output/clobber
    ///
    ConstraintPrefix Type = isInput;

    /// isEarlyClobber - "&": output operand writes result before inputs are all
    /// read.  This is only ever set for an output operand.
    bool isEarlyClobber = false;

    /// MatchingInput - If this is not -1, this is an output constraint where an
    /// input constraint is required to match it (e.g. "0").  The value is the
    /// constraint number that matches this one (for example, if this is
    /// constraint #0 and constraint #4 has the value "0", this will be 4).
    int MatchingInput = -1;

    /// hasMatchingInput - Return true if this is an output constraint that has
    /// a matching input constraint.
    bool hasMatchingInput() const { return MatchingInput != -1; }

    /// isCommutative - This is set to true for a constraint that is commutative
    /// with the next operand.
    bool isCommutative = false;

    /// isIndirect - True if this operand is an indirect operand.  This means
    /// that the address of the source or destination is present in the call
    /// instruction, instead of it being returned or passed in explicitly.  This
    /// is represented with a '*' in the asm string.
    bool isIndirect = false;

    /// Code - The constraint code, either the register name (in braces) or the
    /// constraint letter/number.
    ConstraintCodeVector Codes;

    /// isMultipleAlternative - '|': has multiple-alternative constraints.
    bool isMultipleAlternative = false;

    /// multipleAlternatives - If there are multiple alternative constraints,
    /// this array will contain them.  Otherwise it will be empty.
    SubConstraintInfoVector multipleAlternatives;

    /// The currently selected alternative constraint index.
    unsigned currentAlternativeIndex = 0;

    /// Default constructor.
    ConstraintInfo() = default;

    /// Parse - Analyze the specified string (e.g. "=*&{eax}") and fill in the
    /// fields in this structure.  If the constraint string is not understood,
    /// return true, otherwise return false.
    bool Parse(StringRef Str, ConstraintInfoVector &ConstraintsSoFar);

    /// selectAlternative - Point this constraint to the alternative constraint
    /// indicated by the index.
    void selectAlternative(unsigned index);
  };

  /// ParseConstraints - Split up the constraint string into the specific
  /// constraints and their prefixes.  If this returns an empty vector, and if
  /// the constraint string itself isn't empty, there was an error parsing.
  static ConstraintInfoVector ParseConstraints(StringRef ConstraintString);

  /// ParseConstraints - Parse the constraints of this inlineasm object,
  /// returning them the same way that ParseConstraints(str) does.
  ConstraintInfoVector ParseConstraints() const {
    return ParseConstraints(Constraints);
  }

  // Methods for support type inquiry through isa, cast, and dyn_cast:
  static bool classof(const Value *V) {
    return V->getValueID() == Value::InlineAsmVal;
  }

  // These are helper methods for dealing with flags in the INLINEASM SDNode
  // in the backend.
  //
  // The encoding of the flag word is currently:
  //   Bits 2-0 - A Kind_* value indicating the kind of the operand.
  //   Bits 15-3 - The number of SDNode operands associated with this inline
  //               assembly operand.
  //   If bit 31 is set:
  //     Bit 30-16 - The operand number that this operand must match.
  //                 When bits 2-0 are Kind_Mem, the Constraint_* value must be
  //                 obtained from the flags for this operand number.
  //   Else if bits 2-0 are Kind_Mem:
  //     Bit 30-16 - A Constraint_* value indicating the original constraint
  //                 code.
  //   Else:
  //     Bit 30-16 - The register class ID to use for the operand.

  // Fixed operands on an INLINEASM SDNode.
  static constexpr uint32_t Op_InputChain = 0;
  static constexpr uint32_t Op_AsmString = 1;
  static constexpr uint32_t Op_MDNode = 2;
  static constexpr uint32_t Op_ExtraInfo = 3;  // HasSideEffects, IsAlignStack,
                                               // AsmDialect.
  static constexpr uint32_t Op_FirstOperand = 4;

  // Fixed operands on an INLINEASM MachineInstr.
  static constexpr uint32_t MIOp_AsmString = 0;
  static constexpr uint32_t MIOp_ExtraInfo = 1;  // HasSideEffects,
                                                 // IsAlignStack, AsmDialect.
  static constexpr uint32_t MIOp_FirstOperand = 2;

  // Interpretation of the MIOp_ExtraInfo bit field.
  static constexpr uint32_t Extra_HasSideEffects = 1;
  static constexpr uint32_t Extra_IsAlignStack = 2;
  static constexpr uint32_t Extra_AsmDialect = 4;
  static constexpr uint32_t Extra_MayLoad = 8;
  static constexpr uint32_t Extra_MayStore = 16;
  static constexpr uint32_t Extra_IsConvergent = 32;

  // Inline asm operands map to multiple SDNode / MachineInstr operands.
  // The first operand is an immediate describing the asm operand, the low
  // bits is the kind:
  static constexpr uint32_t Kind_RegUse = 1;   // Input register, "r".
  static constexpr uint32_t Kind_RegDef = 2;   // Output register, "=r".
  static constexpr uint32_t Kind_RegDefEarlyClobber = 3;
                                               // Early-clobber output register,
                                               // "=&r".
  static constexpr uint32_t Kind_Clobber = 4;  // Clobbered register, "~r".
  static constexpr uint32_t Kind_Imm = 5;      // Immediate.
  static constexpr uint32_t Kind_Mem = 6;      // Memory operand, "m".

  // Memory constraint codes.
  // These could be tablegenerated but there's little need to do that since
  // there's plenty of space in the encoding to support the union of all
  // constraint codes for all targets.
  static constexpr uint32_t Constraint_Unknown = 0;
  static constexpr uint32_t Constraint_es = 1;
  static constexpr uint32_t Constraint_i = 2;
  static constexpr uint32_t Constraint_m = 3;
  static constexpr uint32_t Constraint_o = 4;
  static constexpr uint32_t Constraint_v = 5;
  static constexpr uint32_t Constraint_A = 6;
  static constexpr uint32_t Constraint_Q = 7;
  static constexpr uint32_t Constraint_R = 8;
  static constexpr uint32_t Constraint_S = 9;
  static constexpr uint32_t Constraint_T = 10;
  static constexpr uint32_t Constraint_Um = 11;
  static constexpr uint32_t Constraint_Un = 12;
  static constexpr uint32_t Constraint_Uq = 13;
  static constexpr uint32_t Constraint_Us = 14;
  static constexpr uint32_t Constraint_Ut = 15;
  static constexpr uint32_t Constraint_Uv = 16;
  static constexpr uint32_t Constraint_Uy = 17;
  static constexpr uint32_t Constraint_X = 18;
  static constexpr uint32_t Constraint_Z= 19;
  static constexpr uint32_t Constraint_ZC = 20;
  static constexpr uint32_t Constraint_Zy = 21;
  static constexpr uint32_t Constraints_Max = Constraint_Zy;
  static constexpr uint32_t Constraints_ShiftAmount = 16;

  static constexpr uint32_t Flag_MatchingOperand = 0x80000000;

  static unsigned getFlagWord(unsigned Kind, unsigned NumOps) {
    assert(((NumOps << 3) & ~0xffff) == 0 && "Too many inline asm operands!");
    assert(Kind >= Kind_RegUse && Kind <= Kind_Mem && "Invalid Kind");
    return Kind | (NumOps << 3);
  }

  static bool isRegDefKind(unsigned Flag){ return getKind(Flag) == Kind_RegDef;}
  static bool isImmKind(unsigned Flag) { return getKind(Flag) == Kind_Imm; }
  static bool isMemKind(unsigned Flag) { return getKind(Flag) == Kind_Mem; }
  static bool isRegDefEarlyClobberKind(unsigned Flag) {
    return getKind(Flag) == Kind_RegDefEarlyClobber;
  }
  static bool isClobberKind(unsigned Flag) {
    return getKind(Flag) == Kind_Clobber;
  }

  /// getFlagWordForMatchingOp - Augment an existing flag word returned by
  /// getFlagWord with information indicating that this input operand is tied
  /// to a previous output operand.
  static unsigned getFlagWordForMatchingOp(unsigned InputFlag,
                                           unsigned MatchedOperandNo) {
    assert(MatchedOperandNo <= 0x7fff && "Too big matched operand");
    assert((InputFlag & ~0xffff) == 0 && "High bits already contain data");
    return InputFlag | Flag_MatchingOperand | (MatchedOperandNo << 16);
  }

  /// getFlagWordForRegClass - Augment an existing flag word returned by
  /// getFlagWord with the required register class for the following register
  /// operands.
  /// A tied use operand cannot have a register class, use the register class
  /// from the def operand instead.
  static unsigned getFlagWordForRegClass(unsigned InputFlag, unsigned RC) {
    // Store RC + 1, reserve the value 0 to mean 'no register class'.
    ++RC;
    assert(!isImmKind(InputFlag) && "Immediates cannot have a register class");
    assert(!isMemKind(InputFlag) && "Memory operand cannot have a register class");
    assert(RC <= 0x7fff && "Too large register class ID");
    assert((InputFlag & ~0xffff) == 0 && "High bits already contain data");
    return InputFlag | (RC << 16);
  }

  /// Augment an existing flag word returned by getFlagWord with the constraint
  /// code for a memory constraint.
  static unsigned getFlagWordForMem(unsigned InputFlag, unsigned Constraint) {
    assert(isMemKind(InputFlag) && "InputFlag is not a memory constraint!");
    assert(Constraint <= 0x7fff && "Too large a memory constraint ID");
    assert(Constraint <= Constraints_Max && "Unknown constraint ID");
    assert((InputFlag & ~0xffff) == 0 && "High bits already contain data");
    return InputFlag | (Constraint << Constraints_ShiftAmount);
  }

  static unsigned convertMemFlagWordToMatchingFlagWord(unsigned InputFlag) {
    assert(isMemKind(InputFlag));
    return InputFlag & ~(0x7fff << Constraints_ShiftAmount);
  }

  static unsigned getKind(unsigned Flags) {
    return Flags & 7;
  }

  static unsigned getMemoryConstraintID(unsigned Flag) {
    assert(isMemKind(Flag));
    return (Flag >> Constraints_ShiftAmount) & 0x7fff;
  }

  /// getNumOperandRegisters - Extract the number of registers field from the
  /// inline asm operand flag.
  static unsigned getNumOperandRegisters(unsigned Flag) {
    return (Flag & 0xffff) >> 3;
  }

  /// isUseOperandTiedToDef - Return true if the flag of the inline asm
  /// operand indicates it is an use operand that's matched to a def operand.
  static bool isUseOperandTiedToDef(unsigned Flag, unsigned &Idx) {
    if ((Flag & Flag_MatchingOperand) == 0)
      return false;
    Idx = (Flag & ~Flag_MatchingOperand) >> 16;
    return true;
  }

  /// hasRegClassConstraint - Returns true if the flag contains a register
  /// class constraint.  Sets RC to the register class ID.
  static bool hasRegClassConstraint(unsigned Flag, unsigned &RC) {
    if (Flag & Flag_MatchingOperand)
      return false;
    unsigned High = Flag >> 16;
    // getFlagWordForRegClass() uses 0 to mean no register class, and otherwise
    // stores RC + 1.
    if (!High)
      return false;
    RC = High - 1;
    return true;
  }
};

} // end namespace llvm

#endif // LLVM_IR_INLINEASM_H
