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

#ifndef _LIBS_LOG_PROPERTIES_H
#define _LIBS_LOG_PROPERTIES_H

#ifdef __cplusplus
extern "C" {
#endif

#ifndef __ANDROID_USE_LIBLOG_IS_DEBUGGABLE_INTERFACE
#ifndef __ANDROID_API__
#define __ANDROID_USE_LIBLOG_IS_DEBUGGABLE_INTERFACE 1
#elif __ANDROID_API__ > 24 /* > Nougat */
#define __ANDROID_USE_LIBLOG_IS_DEBUGGABLE_INTERFACE 1
#else
#define __ANDROID_USE_LIBLOG_IS_DEBUGGABLE_INTERFACE 0
#endif
#endif

#if __ANDROID_USE_LIBLOG_IS_DEBUGGABLE_INTERFACE
int __android_log_is_debuggable();
#endif

#ifdef __cplusplus
}
#endif

#endif /* _LIBS_LOG_PROPERTIES_H */
