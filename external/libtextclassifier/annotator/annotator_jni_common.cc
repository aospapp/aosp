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

#include "annotator/annotator_jni_common.h"

#include "utils/java/jni-base.h"
#include "utils/java/scoped_local_ref.h"

namespace libtextclassifier3 {
namespace {

std::unordered_set<std::string> EntityTypesFromJObject(JNIEnv* env,
                                                       const jobject& jobject) {
  std::unordered_set<std::string> entity_types;
  jobjectArray jentity_types = reinterpret_cast<jobjectArray>(jobject);
  const int size = env->GetArrayLength(jentity_types);
  for (int i = 0; i < size; ++i) {
    jstring jentity_type =
        reinterpret_cast<jstring>(env->GetObjectArrayElement(jentity_types, i));
    entity_types.insert(ToStlString(env, jentity_type));
  }
  return entity_types;
}

template <typename T>
T FromJavaOptionsInternal(JNIEnv* env, jobject joptions,
                          const std::string& class_name) {
  if (!joptions) {
    return {};
  }

  const ScopedLocalRef<jclass> options_class(env->FindClass(class_name.c_str()),
                                             env);
  if (!options_class) {
    return {};
  }

  const std::pair<bool, jobject> status_or_locales = CallJniMethod0<jobject>(
      env, joptions, options_class.get(), &JNIEnv::CallObjectMethod,
      "getLocale", "Ljava/lang/String;");
  const std::pair<bool, jobject> status_or_reference_timezone =
      CallJniMethod0<jobject>(env, joptions, options_class.get(),
                              &JNIEnv::CallObjectMethod, "getReferenceTimezone",
                              "Ljava/lang/String;");
  const std::pair<bool, int64> status_or_reference_time_ms_utc =
      CallJniMethod0<int64>(env, joptions, options_class.get(),
                            &JNIEnv::CallLongMethod, "getReferenceTimeMsUtc",
                            "J");
  const std::pair<bool, jobject> status_or_detected_text_language_tags =
      CallJniMethod0<jobject>(
          env, joptions, options_class.get(), &JNIEnv::CallObjectMethod,
          "getDetectedTextLanguageTags", "Ljava/lang/String;");
  const std::pair<bool, int> status_or_annotation_usecase =
      CallJniMethod0<int>(env, joptions, options_class.get(),
                          &JNIEnv::CallIntMethod, "getAnnotationUsecase", "I");

  if (!status_or_locales.first || !status_or_reference_timezone.first ||
      !status_or_reference_time_ms_utc.first ||
      !status_or_detected_text_language_tags.first ||
      !status_or_annotation_usecase.first) {
    return {};
  }

  T options;
  options.locales =
      ToStlString(env, reinterpret_cast<jstring>(status_or_locales.second));
  options.reference_timezone = ToStlString(
      env, reinterpret_cast<jstring>(status_or_reference_timezone.second));
  options.reference_time_ms_utc = status_or_reference_time_ms_utc.second;
  options.detected_text_language_tags = ToStlString(
      env,
      reinterpret_cast<jstring>(status_or_detected_text_language_tags.second));
  options.annotation_usecase =
      static_cast<AnnotationUsecase>(status_or_annotation_usecase.second);
  return options;
}
}  // namespace

SelectionOptions FromJavaSelectionOptions(JNIEnv* env, jobject joptions) {
  if (!joptions) {
    return {};
  }

  const ScopedLocalRef<jclass> options_class(
      env->FindClass(TC3_PACKAGE_PATH TC3_ANNOTATOR_CLASS_NAME_STR
                     "$SelectionOptions"),
      env);
  const std::pair<bool, jobject> status_or_locales = CallJniMethod0<jobject>(
      env, joptions, options_class.get(), &JNIEnv::CallObjectMethod,
      "getLocales", "Ljava/lang/String;");
  const std::pair<bool, int> status_or_annotation_usecase =
      CallJniMethod0<int>(env, joptions, options_class.get(),
                          &JNIEnv::CallIntMethod, "getAnnotationUsecase", "I");
  if (!status_or_locales.first || !status_or_annotation_usecase.first) {
    return {};
  }

  SelectionOptions options;
  options.locales =
      ToStlString(env, reinterpret_cast<jstring>(status_or_locales.second));
  options.annotation_usecase =
      static_cast<AnnotationUsecase>(status_or_annotation_usecase.second);

  return options;
}

ClassificationOptions FromJavaClassificationOptions(JNIEnv* env,
                                                    jobject joptions) {
  return FromJavaOptionsInternal<ClassificationOptions>(
      env, joptions,
      TC3_PACKAGE_PATH TC3_ANNOTATOR_CLASS_NAME_STR "$ClassificationOptions");
}

AnnotationOptions FromJavaAnnotationOptions(JNIEnv* env, jobject joptions) {
  if (!joptions) return {};
  const ScopedLocalRef<jclass> options_class(
      env->FindClass(TC3_PACKAGE_PATH TC3_ANNOTATOR_CLASS_NAME_STR
                     "$AnnotationOptions"),
      env);
  if (!options_class) return {};
  const std::pair<bool, jobject> status_or_entity_types =
      CallJniMethod0<jobject>(env, joptions, options_class.get(),
                              &JNIEnv::CallObjectMethod, "getEntityTypes",
                              "[Ljava/lang/String;");
  if (!status_or_entity_types.first) return {};
  const std::pair<bool, bool> status_or_enable_serialized_entity_data =
      CallJniMethod0<bool>(env, joptions, options_class.get(),
                           &JNIEnv::CallBooleanMethod,
                           "isSerializedEntityDataEnabled", "Z");
  if (!status_or_enable_serialized_entity_data.first) return {};
  AnnotationOptions annotation_options =
      FromJavaOptionsInternal<AnnotationOptions>(
          env, joptions,
          TC3_PACKAGE_PATH TC3_ANNOTATOR_CLASS_NAME_STR "$AnnotationOptions");
  annotation_options.entity_types =
      EntityTypesFromJObject(env, status_or_entity_types.second);
  annotation_options.is_serialized_entity_data_enabled =
      status_or_enable_serialized_entity_data.second;
  return annotation_options;
}

}  // namespace libtextclassifier3
