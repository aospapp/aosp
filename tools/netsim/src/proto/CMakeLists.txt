# Copyright 2022 The Android Open Source Project
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

cmake_minimum_required(VERSION 3.5)

# For netsimd (netsimd-proto-lib)
protobuf_generate_grpc_cpp(
  SOURCE_DIR ${CMAKE_CURRENT_LIST_DIR}
  SOURCES ${CMAKE_CURRENT_LIST_DIR}/common.proto
          ${CMAKE_CURRENT_LIST_DIR}/frontend.proto
          ${CMAKE_CURRENT_LIST_DIR}/hci_packet.proto
          ${CMAKE_CURRENT_LIST_DIR}/model.proto
          ${CMAKE_CURRENT_LIST_DIR}/packet_streamer.proto
          ${CMAKE_CURRENT_LIST_DIR}/startup.proto
  OUTPUT_DIR ${CMAKE_CURRENT_BINARY_DIR}
  GENERATED NETSIM_PROTO_SRC)

android_add_library(TARGET netsimd-proto-lib LICENSE Apache-2.0
                    SRC ${NETSIM_PROTO_SRC} DEPS grpc++ protobuf::libprotobuf)

target_include_directories(netsimd-proto-lib PRIVATE ${PROTOBUF_INCLUDE_DIR} ..
                           PUBLIC ${CMAKE_CURRENT_BINARY_DIR})

# For netsim-cli (netsim-cli-proto-lib)
protobuf_generate_grpc_cpp(
  SOURCE_DIR ${CMAKE_CURRENT_LIST_DIR}
  SOURCES ${CMAKE_CURRENT_LIST_DIR}/common.proto
          ${CMAKE_CURRENT_LIST_DIR}/frontend.proto
          ${CMAKE_CURRENT_LIST_DIR}/model.proto
  OUTPUT_DIR ${CMAKE_CURRENT_BINARY_DIR}
  GENERATED NETSIM_FRONTEND_GRPC_SRC)

android_add_library(
  TARGET netsim-cli-proto-lib LICENSE Apache-2.0 SRC ${NETSIM_FRONTEND_GRPC_SRC}
  DEPS grpc++ netsimd-proto-lib protobuf::libprotobuf)

target_include_directories(
  netsim-cli-proto-lib PRIVATE ${PROTOBUF_INCLUDE_DIR} ..
  PUBLIC ${CMAKE_CURRENT_BINARY_DIR})

# For network packet streamer client (packet-streamer-proto-lib)
protobuf_generate_grpc_cpp(
  SOURCE_DIR ${CMAKE_CURRENT_LIST_DIR}
  SOURCES ${CMAKE_CURRENT_LIST_DIR}/common.proto
          ${CMAKE_CURRENT_LIST_DIR}/hci_packet.proto
          ${CMAKE_CURRENT_LIST_DIR}/packet_streamer.proto
          ${CMAKE_CURRENT_LIST_DIR}/startup.proto
  OUTPUT_DIR ${CMAKE_CURRENT_BINARY_DIR}
  GENERATED NETSIM_PACKET_STREAMER_GRPC_SRC)

android_add_library(
  TARGET packet-streamer-proto-lib LICENSE Apache-2.0
  SRC ${NETSIM_PACKET_STREAMER_GRPC_SRC} DEPS grpc++ netsimd-proto-lib
                                              protobuf::libprotobuf)

target_include_directories(
  packet-streamer-proto-lib PRIVATE ${PROTOBUF_INCLUDE_DIR} ..
  PUBLIC ${CMAKE_CURRENT_BINARY_DIR})
