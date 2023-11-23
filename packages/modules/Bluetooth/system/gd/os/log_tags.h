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

// These log levels may need to be mapped to system values. These values are
// used to control the log level via init flags.
enum LogLevels {
  LOG_TAG_FATAL = 0,
  LOG_TAG_ERROR,
  LOG_TAG_WARN,
  LOG_TAG_NOTICE,
  LOG_TAG_INFO,
  LOG_TAG_DEBUG,
  LOG_TAG_VERBOSE
};
