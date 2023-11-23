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

#include "model.pb.h"
#include "netsim-cxx/src/lib.rs.h"  // For Cxx methods.

namespace netsim::uwb::facade {

void Patch(uint32_t, const model::Chip::Radio &);

model::Chip::Radio Get(uint32_t);

// The following methods are defined in netsim-cxx/src/lib.rs.cc.
void Start() noexcept;

void Stop() noexcept;

void Reset(::std::uint32_t _facade_id) noexcept;

void Remove(::std::uint32_t _facade_id) noexcept;

::std::uint32_t Add(::std::uint32_t _chip_id) noexcept;

}  // namespace netsim::uwb::facade