#
# Copyright 2020 The TensorFlow Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set(LLVM_TARGET_DEFINITIONS lmhlo_passes.td)
mlir_tablegen(lmhlo_passes.h.inc -gen-pass-decls -name LMHLO)
add_public_tablegen_target(MLIRLmhloPassIncGen)

set(LLVM_TARGET_DEFINITIONS disc_ral_passes.td)
mlir_tablegen(disc_ral_passes.h.inc -gen-pass-decls -name RAL)
add_public_tablegen_target(MLIRDiscRalPassIncGen)
