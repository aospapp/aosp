# Copyright 2023 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

C_SRCS := ukey2_glue.cc
CC = clang++
UNAME := $(shell uname -s)
ifeq ($(UNAME),Darwin)
    FFI_LIB = ../../../../target/release/libukey2_c_ffi.dylib
else
    FFI_LIB = ../../../../target/release/libukey2_c_ffi.so
endif
ukey2:
	cargo build --release
	rm -rf build
	mkdir build
	$(CC) $(C_SRCS) $(FFI_LIB) -o build/ukey2
