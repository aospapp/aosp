/*
 * Copyright 2023 The Android Open Source Project
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
#include <memory>
#include <string>

#include "model.pb.h"

/** Manages the WiFi chip emulation provided by the WiFi service library.
 *
 * Owns the WiFi service, setup, and manages the packet flow into and out of
 * WiFi service.
 */

namespace netsim::wifi::facade {

void Reset(uint32_t);
void Remove(uint32_t);
void Patch(uint32_t, const model::Chip::Radio &);
model::Chip::Radio Get(uint32_t);
uint32_t Add(uint32_t simulation_device);

void Start();
void Stop();

}  // namespace netsim::wifi::facade
