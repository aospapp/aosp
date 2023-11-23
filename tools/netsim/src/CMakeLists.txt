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

add_subdirectory(proto)

android_add_library(
  TARGET util-lib
  LICENSE Apache-2.0
  SRC util/filesystem.h
      util/ini_file.cc
      util/ini_file.h
      util/log.cc
      util/log.h
      util/os_utils.cc
      util/os_utils.h
      util/string_utils.cc
      util/string_utils.h)
target_include_directories(util-lib PRIVATE .)

if(TARGET Rust::Rustc)
  set(cxx_bridge_binary_folder
      ${CMAKE_BINARY_DIR}/cargo/build/${Rust_CARGO_TARGET_CACHED}/cxxbridge)
  set(common_header ${cxx_bridge_binary_folder}/rust/cxx.h)
  set(cxx_bridge_source_file "src/lib.rs")
  set(crate_name "frontend-client-cxx")
  set(binding_header
      ${cxx_bridge_binary_folder}/${crate_name}/${cxx_bridge_source_file}.h)
  set(binding_source
      ${cxx_bridge_binary_folder}/${crate_name}/${cxx_bridge_source_file}.cc)

  # Make sure we have the cxx files generated before we build them.
  add_custom_command(OUTPUT ${common_header} ${binding_header} ${binding_source}
                     COMMAND DEPENDS ${crate_name}-static)

  android_add_library(
    TARGET frontend-client
    LICENSE Apache-2.0
    SRC ${binding_header} ${binding_source} ${common_header}
        frontend/frontend_client.cc frontend/frontend_client.h
    DEPS grpc++ netsim-cli-proto-lib protobuf::libprotobuf util-lib)
  target_include_directories(frontend-client PRIVATE .
                             PUBLIC ${cxx_bridge_binary_folder})
endif()

android_add_library(
  TARGET packet-streamer-client-lib
  LICENSE Apache-2.0
  SRC backend/packet_streamer_client.cc backend/packet_streamer_client.h
  DEPS android-emu-base
       android-emu-base-headers
       android-emu-base-process
       grpc++
       packet-streamer-proto-lib
       protobuf::libprotobuf
       util-lib)
target_include_directories(packet-streamer-client-lib
                           PUBLIC ${CMAKE_CURRENT_LIST_DIR})

if(TARGET Rust::Rustc)
  set(cxx_bridge_binary_folder
      ${CMAKE_BINARY_DIR}/cargo/build/${Rust_CARGO_TARGET_CACHED}/cxxbridge)
  set(common_header ${cxx_bridge_binary_folder}/rust/cxx.h)
  set(cxx_bridge_source_file "src/lib.rs")
  set(crate_name "netsim-cxx")
  set(binding_header
      ${cxx_bridge_binary_folder}/${crate_name}/${cxx_bridge_source_file}.h)
  set(binding_source
      ${cxx_bridge_binary_folder}/${crate_name}/${cxx_bridge_source_file}.cc)

  # Make sure we have the cxx files generated before we build them.
  add_custom_command(OUTPUT ${common_header} ${binding_header} ${binding_source}
                     COMMAND DEPENDS ${crate_name}-static)

  android_add_library(
    TARGET netsimd-lib
    LICENSE Apache-2.0
    SRC ${binding_header}
        ${binding_source}
        ${common_header}
        backend/backend_packet_hub.h
        backend/grpc_server.cc
        backend/grpc_server.h
        controller/chip.cc
        controller/chip.h
        controller/controller.cc
        controller/controller.h
        controller/device.cc
        controller/device.h
        controller/device_notify_manager.cc
        controller/device_notify_manager.h
        controller/scene_controller.cc
        controller/scene_controller.h
        core/server.cc
        core/server.h
        frontend/frontend_client_stub.cc
        frontend/frontend_client_stub.h
        frontend/frontend_server.cc
        frontend/frontend_server.h
        frontend/server_response_writable.h
        hci/async_manager.cc
        hci/bluetooth_facade.cc
        hci/bluetooth_facade.h
        hci/hci_debug.cc
        hci/hci_debug.h
        hci/hci_packet_transport.cc
        hci/hci_packet_transport.h
        packet_hub/packet_hub.cc
        packet_hub/packet_hub.h
        uwb/uwb_facade.cc
        uwb/uwb_facade.h
        wifi/wifi_facade.cc
        wifi/wifi_facade.h
        wifi/wifi_packet_hub.h
    DEPS android-emu-base
         android-emu-base-headers
         grpc++
         libbt-rootcanal
         netsim-cxx
         netsimd-proto-lib
         protobuf::libprotobuf
         util-lib)
  target_include_directories(
    netsimd-lib PRIVATE . ${PROTOBUF_INCLUDE_DIR} ../rust/netsim-cxx
                        ../rust/netsim-cxx/cxx
    PUBLIC ${cxx_bridge_binary_folder})
  target_compile_definitions(netsimd-lib PUBLIC NETSIM_ANDROID_EMULATOR)

endif()
