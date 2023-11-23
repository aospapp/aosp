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

#include "lang_id/lang-id_jni.h"

#include <jni.h>
#include <type_traits>
#include <vector>

#include "utils/base/logging.h"
#include "utils/java/scoped_local_ref.h"
#include "lang_id/fb_model/lang-id-from-fb.h"
#include "lang_id/lang-id.h"

using libtextclassifier3::ScopedLocalRef;
using libtextclassifier3::ToStlString;
using libtextclassifier3::mobile::lang_id::GetLangIdFromFlatbufferFile;
using libtextclassifier3::mobile::lang_id::GetLangIdFromFlatbufferFileDescriptor;
using libtextclassifier3::mobile::lang_id::LangId;
using libtextclassifier3::mobile::lang_id::LangIdResult;

namespace {
jobjectArray LangIdResultToJObjectArray(JNIEnv* env,
                                        const LangIdResult& lang_id_result) {
  const ScopedLocalRef<jclass> result_class(
      env->FindClass(TC3_PACKAGE_PATH TC3_LANG_ID_CLASS_NAME_STR
                     "$LanguageResult"),
      env);
  if (!result_class) {
    TC3_LOG(ERROR) << "Couldn't find LanguageResult class.";
    return nullptr;
  }

  // clang-format off
  const std::vector<std::pair<std::string, float>>& predictions =
      lang_id_result.predictions;
  // clang-format on
  const jmethodID result_class_constructor =
      env->GetMethodID(result_class.get(), "<init>", "(Ljava/lang/String;F)V");
  const jobjectArray results =
      env->NewObjectArray(predictions.size(), result_class.get(), nullptr);
  for (int i = 0; i < predictions.size(); i++) {
    ScopedLocalRef<jobject> result(
        env->NewObject(result_class.get(), result_class_constructor,
                       env->NewStringUTF(predictions[i].first.c_str()),
                       static_cast<jfloat>(predictions[i].second)));
    env->SetObjectArrayElement(results, i, result.get());
  }
  return results;
}
}  // namespace

TC3_JNI_METHOD(jlong, TC3_LANG_ID_CLASS_NAME, nativeNew)
(JNIEnv* env, jobject thiz, jint fd) {
  std::unique_ptr<LangId> lang_id = GetLangIdFromFlatbufferFileDescriptor(fd);
  if (!lang_id->is_valid()) {
    return reinterpret_cast<jlong>(nullptr);
  }
  return reinterpret_cast<jlong>(lang_id.release());
}

TC3_JNI_METHOD(jlong, TC3_LANG_ID_CLASS_NAME, nativeNewFromPath)
(JNIEnv* env, jobject thiz, jstring path) {
  const std::string path_str = ToStlString(env, path);
  std::unique_ptr<LangId> lang_id = GetLangIdFromFlatbufferFile(path_str);
  if (!lang_id->is_valid()) {
    return reinterpret_cast<jlong>(nullptr);
  }
  return reinterpret_cast<jlong>(lang_id.release());
}

TC3_JNI_METHOD(jobjectArray, TC3_LANG_ID_CLASS_NAME, nativeDetectLanguages)
(JNIEnv* env, jobject clazz, jlong ptr, jstring text) {
  LangId* model = reinterpret_cast<LangId*>(ptr);
  if (!model) {
    return nullptr;
  }

  const std::string text_str = ToStlString(env, text);
  LangIdResult result;
  model->FindLanguages(text_str, &result);

  return LangIdResultToJObjectArray(env, result);
}

TC3_JNI_METHOD(void, TC3_LANG_ID_CLASS_NAME, nativeClose)
(JNIEnv* env, jobject clazz, jlong ptr) {
  if (!ptr) {
    TC3_LOG(ERROR) << "Trying to close null LangId.";
    return;
  }
  LangId* model = reinterpret_cast<LangId*>(ptr);
  delete model;
}

TC3_JNI_METHOD(jint, TC3_LANG_ID_CLASS_NAME, nativeGetVersion)
(JNIEnv* env, jobject clazz, jlong ptr) {
  if (!ptr) {
    return -1;
  }
  LangId* model = reinterpret_cast<LangId*>(ptr);
  return model->GetModelVersion();
}

TC3_JNI_METHOD(jint, TC3_LANG_ID_CLASS_NAME, nativeGetVersionFromFd)
(JNIEnv* env, jobject clazz, jint fd) {
  std::unique_ptr<LangId> lang_id = GetLangIdFromFlatbufferFileDescriptor(fd);
  if (!lang_id->is_valid()) {
    return -1;
  }
  return lang_id->GetModelVersion();
}

TC3_JNI_METHOD(jfloat, TC3_LANG_ID_CLASS_NAME, nativeGetLangIdThreshold)
(JNIEnv* env, jobject thizz, jlong ptr) {
  if (!ptr) {
    return -1.0;
  }
  LangId* model = reinterpret_cast<LangId*>(ptr);
  return model->GetFloatProperty("text_classifier_langid_threshold", -1.0);
}
