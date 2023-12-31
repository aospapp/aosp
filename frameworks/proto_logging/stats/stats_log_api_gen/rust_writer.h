/*
 * Copyright (C) 2021, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <stdio.h>

#include "Collation.h"

namespace android {
namespace stats_log_api_gen {

int write_stats_log_rust(FILE* out, const Atoms& atoms, const AtomDecl& attributionDecl,
                         const int minApiLevel, const char* rustHeaderCrate);

void write_stats_log_rust_header(FILE* out, const Atoms& atoms, const AtomDecl& attributionDecl,
                                 const char* rustHeaderCrate);

}  // namespace stats_log_api_gen
}  // namespace android
