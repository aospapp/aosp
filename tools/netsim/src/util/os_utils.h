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

#pragma once
// OS specific utility functions.

#include <optional>
#include <string>

namespace netsim {
namespace osutils {

/**
 * Return the value of environment variable if exists. Otherwise, the default
 * value.
 */
std::string GetEnv(const std::string &name, const std::string &default_value);

/**
 * Return the path containing runtime user files.
 */
std::string GetDiscoveryDirectory();

/**
 * Return the path of netsim ini file.
 */
std::string GetNetsimIniFilepath();

/**
 * Return the frontend grpc port.
 */
std::optional<std::string> GetServerAddress(bool frontend_server = true);
}  // namespace osutils
}  // namespace netsim
