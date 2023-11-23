// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#ifndef _LIBS_LOG_SAFETYNET_H
#define _LIBS_LOG_SAFETYNET_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#ifndef _ANDROID_USE_LIBLOG_SAFETYNET_INTERFACE
#ifndef __ANDROID_API__
#define __ANDROID_USE_LIBLOG_SAFETYNET_INTERFACE 1
#elif __ANDROID_API__ > 22 /* > Lollipop */
#define __ANDROID_USE_LIBLOG_SAFETYNET_INTERFACE 1
#else
#define __ANDROID_USE_LIBLOG_SAFETYNET_INTERFACE 0
#endif
#endif

#if __ANDROID_USE_LIBLOG_SAFETYNET_INTERFACE

#define android_errorWriteLog(tag, subTag) \
  __android_log_error_write(tag, subTag, -1, NULL, 0)

#define android_errorWriteWithInfoLog(tag, subTag, uid, data, dataLen) \
  __android_log_error_write(tag, subTag, uid, data, dataLen)

int __android_log_error_write(int tag, const char* subTag, int32_t uid,
                              const char* data, uint32_t dataLen);

#endif /* __ANDROID_USE_LIBLOG_SAFETYNET_INTERFACE */

#ifdef __cplusplus
}
#endif

#endif /* _LIBS_LOG_SAFETYNET_H */
