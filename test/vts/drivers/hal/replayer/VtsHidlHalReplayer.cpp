/*
 * Copyright (C) 2016 The Android Open Source Project
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
#include "VtsHidlHalReplayer.h"

#include <fcntl.h>
#include <unistd.h>
#include <fstream>
#include <iostream>
#include <string>

#include <google/protobuf/io/zero_copy_stream_impl.h>
#include <google/protobuf/text_format.h>

#include "VtsProfilingUtil.h"
#include "driver_base/DriverBase.h"
#include "utils/InterfaceSpecUtil.h"
#include "utils/StringUtil.h"

using namespace std;

namespace android {
namespace vts {

bool VtsHidlHalReplayer::ReplayTrace(const string& trace_file,
                                     const string& hal_service_name) {
  // Parse the trace file to get the sequence of function calls.
  int fd =
      open(trace_file.c_str(), O_RDONLY, S_IRUSR | S_IWUSR | S_IRGRP | S_IROTH);
  if (fd < 0) {
    cerr << "Can not open trace file: " << trace_file
         << "error: " << std::strerror(errno);
    return false;
  }

  google::protobuf::io::FileInputStream input(fd);

  VtsProfilingRecord call_msg;
  VtsProfilingRecord expected_result_msg;
  while (readOneDelimited(&call_msg, &input) &&
         readOneDelimited(&expected_result_msg, &input)) {
    if (call_msg.event() != InstrumentationEventType::SERVER_API_ENTRY &&
        call_msg.event() != InstrumentationEventType::CLIENT_API_ENTRY &&
        call_msg.event() != InstrumentationEventType::SYNC_CALLBACK_ENTRY &&
        call_msg.event() != InstrumentationEventType::ASYNC_CALLBACK_ENTRY &&
        call_msg.event() != InstrumentationEventType::PASSTHROUGH_ENTRY) {
      cerr << "Expected a call message but got message with event: "
           << call_msg.event();
      continue;
    }
    if (expected_result_msg.event() !=
            InstrumentationEventType::SERVER_API_EXIT &&
        expected_result_msg.event() !=
            InstrumentationEventType::CLIENT_API_EXIT &&
        expected_result_msg.event() !=
            InstrumentationEventType::SYNC_CALLBACK_EXIT &&
        expected_result_msg.event() !=
            InstrumentationEventType::ASYNC_CALLBACK_EXIT &&
        expected_result_msg.event() !=
            InstrumentationEventType::PASSTHROUGH_EXIT) {
      cerr << "Expected a result message but got message with event: "
           << call_msg.event();
      continue;
    }

    cout << __func__ << ": replay function: " << call_msg.func_msg().name();

    string package_name = call_msg.package();
    float version = call_msg.version();
    string interface_name = call_msg.interface();
    DriverBase* driver = driver_manager_->GetDriverForHidlHalInterface(
        package_name, version, interface_name, hal_service_name);
    if (!driver) {
      cerr << __func__ << ": couldn't get a driver base class" << endl;
      return false;
    }

    vts::FunctionSpecificationMessage result_msg;
    if (!driver->CallFunction(call_msg.func_msg(), "" /*callback_socket_name*/,
                              &result_msg)) {
      cerr << __func__ << ": replay function fail." << endl;
      return false;
    }
    if (!driver->VerifyResults(expected_result_msg.func_msg(), result_msg)) {
      // Verification is not strict, i.e. if fail, output error message and
      // continue the process.
      cerr << __func__ << ": verification fail." << endl;
    }
    call_msg.Clear();
    expected_result_msg.Clear();
  }
  return true;
}

}  // namespace vts
}  // namespace android
