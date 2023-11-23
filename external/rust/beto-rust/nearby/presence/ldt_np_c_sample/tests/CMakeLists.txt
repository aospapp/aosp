# Copyright 2022 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

cmake_minimum_required(VERSION 3.14)

# GoogleTest requires at least C++14
set(CMAKE_CXX_STANDARD 14)

include(FetchContent)
FetchContent_Declare(
  googletest
  GIT_REPOSITORY https://github.com/google/googletest.git
  GIT_TAG release-1.12.1
)
FetchContent_MakeAvailable(googletest)
enable_testing()

find_package(RapidJSON REQUIRED)
message(STATUS "RapidJson include directories: ${RAPIDJSON_INCLUDE_DIRS}${RapidJSON_INCLUDE_DIRS}")
include_directories("${RAPIDJSON_INCLUDE_DIRS}${RapidJSON_INCLUDE_DIRS}")

find_package(benchmark REQUIRED)
add_compile_options(-fsanitize=address)

add_executable(
  np_ffi_tests
  np_ffi_tests.cc
)

add_executable(
  benchmarks
  benchmarks.cc
)

target_link_libraries(
  benchmarks
  "${CMAKE_SOURCE_DIR}/ldt_np_adv_ffi/target/release/libldt_np_adv_ffi.a"
  OpenSSL::SSL
  benchmark::benchmark
  dl pthread -fsanitize=address
)

target_link_libraries(
  np_ffi_tests
  "${CMAKE_SOURCE_DIR}/ldt_np_adv_ffi/target/release/libldt_np_adv_ffi.a"
  GTest::gtest_main
  OpenSSL::SSL
  dl pthread -fsanitize=address
)

include(GoogleTest)
gtest_discover_tests(np_ffi_tests)
