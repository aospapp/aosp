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

#ifndef LIBTEXTCLASSIFIER_UTILS_JAVA_JNI_BASE_H_
#define LIBTEXTCLASSIFIER_UTILS_JAVA_JNI_BASE_H_

#include <jni.h>
#include <string>

// When we use a macro as an argument for a macro, an additional level of
// indirection is needed, if the macro argument is used with # or ##.
#define TC3_ADD_QUOTES_HELPER(TOKEN) #TOKEN
#define TC3_ADD_QUOTES(TOKEN) TC3_ADD_QUOTES_HELPER(TOKEN)

#ifndef TC3_PACKAGE_NAME
#define TC3_PACKAGE_NAME com_google_android_textclassifier
#endif

#ifndef TC3_PACKAGE_PATH
#define TC3_PACKAGE_PATH \
  "com/google/android/textclassifier/"
#endif

#define TC3_JNI_METHOD_NAME_INTERNAL(package_name, class_name, method_name) \
  Java_##package_name##_##class_name##_##method_name

#define TC3_JNI_METHOD_PRIMITIVE(return_type, package_name, class_name, \
                                 method_name)                           \
  JNIEXPORT return_type JNICALL TC3_JNI_METHOD_NAME_INTERNAL(           \
      package_name, class_name, method_name)

// The indirection is needed to correctly expand the TC3_PACKAGE_NAME macro.
// See the explanation near TC3_ADD_QUOTES macro.
#define TC3_JNI_METHOD2(return_type, package_name, class_name, method_name) \
  TC3_JNI_METHOD_PRIMITIVE(return_type, package_name, class_name, method_name)

#define TC3_JNI_METHOD(return_type, class_name, method_name) \
  TC3_JNI_METHOD2(return_type, TC3_PACKAGE_NAME, class_name, method_name)

#define TC3_JNI_METHOD_NAME2(package_name, class_name, method_name) \
  TC3_JNI_METHOD_NAME_INTERNAL(package_name, class_name, method_name)

#define TC3_JNI_METHOD_NAME(class_name, method_name) \
  TC3_JNI_METHOD_NAME2(TC3_PACKAGE_NAME, class_name, method_name)

namespace libtextclassifier3 {

template <typename T, typename F>
std::pair<bool, T> CallJniMethod0(JNIEnv* env, jobject object,
                                  jclass class_object, F function,
                                  const std::string& method_name,
                                  const std::string& return_java_type) {
  const jmethodID method = env->GetMethodID(class_object, method_name.c_str(),
                                            ("()" + return_java_type).c_str());
  if (!method) {
    return std::make_pair(false, T());
  }
  return std::make_pair(true, (env->*function)(object, method));
}

std::string ToStlString(JNIEnv* env, const jstring& str);

// Get system-level file descriptor from AssetFileDescriptor.
jint GetFdFromAssetFileDescriptor(JNIEnv* env, jobject afd);

// Get system-level file descriptor from FileDescriptor.
jint GetFdFromFileDescriptor(JNIEnv* env, jobject fd);
}  // namespace libtextclassifier3

#endif  // LIBTEXTCLASSIFIER_UTILS_JAVA_JNI_BASE_H_
