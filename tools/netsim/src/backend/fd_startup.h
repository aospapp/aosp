/*
 * Copyright 2022 The Android Open Source Project
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

/// Connect HCI file descriptors (from cuttlefish) to the simulator.

#pragma once

#include <memory>
#include <string>

namespace netsim {
namespace hci {

/// FdStartup is the transport for devices that use the grpc connection
/// mechanism.
///
class FdStartup {
 public:
  // Pure virtual destructor for derived classes
  virtual ~FdStartup() = 0;

  // Virtual constructor
  static std::unique_ptr<FdStartup> Create();

  /// Attaches fds to rootcanal and starts processing packets.
  ///
  /// \param StartupInfo as a json string
  //
  virtual bool Connect(const std::string &) = 0;
};

}  // namespace hci
}  // namespace netsim
