/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "utils/java/jni-base.h"

#include <jni.h>
#include <type_traits>
#include <vector>

#include "utils/base/integral_types.h"
#include "utils/java/scoped_local_ref.h"
#include "utils/java/string_utils.h"
#include "utils/memory/mmap.h"

using libtextclassifier3::JStringToUtf8String;
using libtextclassifier3::ScopedLocalRef;

namespace libtextclassifier3 {

std::string ToStlString(JNIEnv* env, const jstring& str) {
  std::string result;
  JStringToUtf8String(env, str, &result);
  return result;
}

jint GetFdFromFileDescriptor(JNIEnv* env, jobject fd) {
  ScopedLocalRef<jclass> fd_class(env->FindClass("java/io/FileDescriptor"),
                                  env);
  if (fd_class == nullptr) {
    TC3_LOG(ERROR) << "Couldn't find FileDescriptor.";
    return reinterpret_cast<jlong>(nullptr);
  }
  jfieldID fd_class_descriptor =
      env->GetFieldID(fd_class.get(), "descriptor", "I");
  if (fd_class_descriptor == nullptr) {
    env->ExceptionClear();
    fd_class_descriptor = env->GetFieldID(fd_class.get(), "fd", "I");
  }
  if (fd_class_descriptor == nullptr) {
    TC3_LOG(ERROR) << "Couldn't find descriptor.";
    return reinterpret_cast<jlong>(nullptr);
  }
  return env->GetIntField(fd, fd_class_descriptor);
}

jint GetFdFromAssetFileDescriptor(JNIEnv* env, jobject afd) {
  ScopedLocalRef<jclass> afd_class(
      env->FindClass("android/content/res/AssetFileDescriptor"), env);
  if (afd_class == nullptr) {
    TC3_LOG(ERROR) << "Couldn't find AssetFileDescriptor.";
    return reinterpret_cast<jlong>(nullptr);
  }
  jmethodID afd_class_getFileDescriptor = env->GetMethodID(
      afd_class.get(), "getFileDescriptor", "()Ljava/io/FileDescriptor;");
  if (afd_class_getFileDescriptor == nullptr) {
    TC3_LOG(ERROR) << "Couldn't find getFileDescriptor.";
    return reinterpret_cast<jlong>(nullptr);
  }
  jobject bundle_jfd = env->CallObjectMethod(afd, afd_class_getFileDescriptor);
  return GetFdFromFileDescriptor(env, bundle_jfd);
}

}  // namespace libtextclassifier3
