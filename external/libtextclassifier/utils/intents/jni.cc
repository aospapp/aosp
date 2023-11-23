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

#include "utils/intents/jni.h"
#include <memory>
#include "utils/intents/intent-generator.h"
#include "utils/java/scoped_local_ref.h"

namespace libtextclassifier3 {

// The macros below are intended to reduce the boilerplate and avoid
// easily introduced copy/paste errors.
#define TC3_CHECK_JNI_PTR(PTR) TC3_CHECK((PTR) != nullptr)
#define TC3_GET_CLASS(FIELD, NAME)                                           \
  handler->FIELD = MakeGlobalRef(env->FindClass(NAME), env, jni_cache->jvm); \
  TC3_CHECK_JNI_PTR(handler->FIELD) << "Error finding class: " << NAME;
#define TC3_GET_METHOD(CLASS, FIELD, NAME, SIGNATURE)                       \
  handler->FIELD = env->GetMethodID(handler->CLASS.get(), NAME, SIGNATURE); \
  TC3_CHECK(handler->FIELD) << "Error finding method: " << NAME;

std::unique_ptr<RemoteActionTemplatesHandler>
RemoteActionTemplatesHandler::Create(
    const std::shared_ptr<JniCache>& jni_cache) {
  JNIEnv* env = jni_cache->GetEnv();
  if (env == nullptr) {
    return nullptr;
  }

  std::unique_ptr<RemoteActionTemplatesHandler> handler(
      new RemoteActionTemplatesHandler(jni_cache));

  TC3_GET_CLASS(integer_class_, "java/lang/Integer");
  TC3_GET_METHOD(integer_class_, integer_init_, "<init>", "(I)V");

  TC3_GET_CLASS(remote_action_template_class_,
                TC3_PACKAGE_PATH TC3_REMOTE_ACTION_TEMPLATE_CLASS_NAME_STR);
  TC3_GET_METHOD(
      remote_action_template_class_, remote_action_template_init_, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/"
      "String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/"
      "Integer;[Ljava/lang/String;Ljava/lang/String;[L" TC3_PACKAGE_PATH
          TC3_NAMED_VARIANT_CLASS_NAME_STR ";Ljava/lang/Integer;)V");

  TC3_GET_CLASS(named_variant_class_,
                TC3_PACKAGE_PATH TC3_NAMED_VARIANT_CLASS_NAME_STR);

  TC3_GET_METHOD(named_variant_class_, named_variant_from_int_, "<init>",
                 "(Ljava/lang/String;I)V");
  TC3_GET_METHOD(named_variant_class_, named_variant_from_long_, "<init>",
                 "(Ljava/lang/String;J)V");
  TC3_GET_METHOD(named_variant_class_, named_variant_from_float_, "<init>",
                 "(Ljava/lang/String;F)V");
  TC3_GET_METHOD(named_variant_class_, named_variant_from_double_, "<init>",
                 "(Ljava/lang/String;D)V");
  TC3_GET_METHOD(named_variant_class_, named_variant_from_bool_, "<init>",
                 "(Ljava/lang/String;Z)V");
  TC3_GET_METHOD(named_variant_class_, named_variant_from_string_, "<init>",
                 "(Ljava/lang/String;Ljava/lang/String;)V");

  return handler;
}

jstring RemoteActionTemplatesHandler::AsUTF8String(
    const Optional<std::string>& optional) const {
  if (!optional.has_value()) {
    return nullptr;
  }
  return jni_cache_->ConvertToJavaString(optional.value()).release();
}

jobject RemoteActionTemplatesHandler::AsInteger(
    const Optional<int>& optional) const {
  return (optional.has_value()
              ? jni_cache_->GetEnv()->NewObject(integer_class_.get(),
                                                integer_init_, optional.value())
              : nullptr);
}

jobjectArray RemoteActionTemplatesHandler::AsStringArray(
    const std::vector<std::string>& values) const {
  if (values.empty()) {
    return nullptr;
  }
  jobjectArray result = jni_cache_->GetEnv()->NewObjectArray(
      values.size(), jni_cache_->string_class.get(), nullptr);
  if (result == nullptr) {
    return nullptr;
  }
  for (int k = 0; k < values.size(); k++) {
    ScopedLocalRef<jstring> value_str =
        jni_cache_->ConvertToJavaString(values[k]);
    jni_cache_->GetEnv()->SetObjectArrayElement(result, k, value_str.get());
  }
  return result;
}

jobject RemoteActionTemplatesHandler::AsNamedVariant(
    const std::string& name_str, const Variant& value) const {
  ScopedLocalRef<jstring> name = jni_cache_->ConvertToJavaString(name_str);
  if (name == nullptr) {
    return nullptr;
  }
  switch (value.GetType()) {
    case Variant::TYPE_INT_VALUE:
      return jni_cache_->GetEnv()->NewObject(named_variant_class_.get(),
                                             named_variant_from_int_,
                                             name.get(), value.IntValue());
    case Variant::TYPE_INT64_VALUE:
      return jni_cache_->GetEnv()->NewObject(named_variant_class_.get(),
                                             named_variant_from_long_,
                                             name.get(), value.Int64Value());
    case Variant::TYPE_FLOAT_VALUE:
      return jni_cache_->GetEnv()->NewObject(named_variant_class_.get(),
                                             named_variant_from_float_,
                                             name.get(), value.FloatValue());
    case Variant::TYPE_DOUBLE_VALUE:
      return jni_cache_->GetEnv()->NewObject(named_variant_class_.get(),
                                             named_variant_from_double_,
                                             name.get(), value.DoubleValue());
    case Variant::TYPE_BOOL_VALUE:
      return jni_cache_->GetEnv()->NewObject(named_variant_class_.get(),
                                             named_variant_from_bool_,
                                             name.get(), value.BoolValue());
    case Variant::TYPE_STRING_VALUE: {
      ScopedLocalRef<jstring> value_jstring =
          jni_cache_->ConvertToJavaString(value.StringValue());
      if (value_jstring == nullptr) {
        return nullptr;
      }
      return jni_cache_->GetEnv()->NewObject(named_variant_class_.get(),
                                             named_variant_from_string_,
                                             name.get(), value_jstring.get());
    }
    default:
      return nullptr;
  }
}

jobjectArray RemoteActionTemplatesHandler::AsNamedVariantArray(
    const std::map<std::string, Variant>& values) const {
  if (values.empty()) {
    return nullptr;
  }
  jobjectArray result = jni_cache_->GetEnv()->NewObjectArray(
      values.size(), named_variant_class_.get(), nullptr);
  int element_index = 0;
  for (auto key_value_pair : values) {
    if (!key_value_pair.second.HasValue()) {
      element_index++;
      continue;
    }
    ScopedLocalRef<jobject> named_extra(
        AsNamedVariant(key_value_pair.first, key_value_pair.second),
        jni_cache_->GetEnv());
    if (named_extra == nullptr) {
      return nullptr;
    }
    jni_cache_->GetEnv()->SetObjectArrayElement(result, element_index,
                                                named_extra.get());
    element_index++;
  }
  return result;
}

jobjectArray RemoteActionTemplatesHandler::RemoteActionTemplatesToJObjectArray(
    const std::vector<RemoteActionTemplate>& remote_actions) const {
  const jobjectArray results = jni_cache_->GetEnv()->NewObjectArray(
      remote_actions.size(), remote_action_template_class_.get(), nullptr);
  if (results == nullptr) {
    return nullptr;
  }
  for (int i = 0; i < remote_actions.size(); i++) {
    const RemoteActionTemplate& remote_action = remote_actions[i];
    const jstring title_without_entity =
        AsUTF8String(remote_action.title_without_entity);
    const jstring title_with_entity =
        AsUTF8String(remote_action.title_with_entity);
    const jstring description = AsUTF8String(remote_action.description);
    const jstring description_with_app_name =
        AsUTF8String(remote_action.description_with_app_name);
    const jstring action = AsUTF8String(remote_action.action);
    const jstring data = AsUTF8String(remote_action.data);
    const jstring type = AsUTF8String(remote_action.type);
    const jobject flags = AsInteger(remote_action.flags);
    const jobjectArray category = AsStringArray(remote_action.category);
    const jstring package = AsUTF8String(remote_action.package_name);
    const jobjectArray extra = AsNamedVariantArray(remote_action.extra);
    const jobject request_code = AsInteger(remote_action.request_code);
    ScopedLocalRef<jobject> result(
        jni_cache_->GetEnv()->NewObject(
            remote_action_template_class_.get(), remote_action_template_init_,
            title_without_entity, title_with_entity, description,
            description_with_app_name, action, data, type, flags, category,
            package, extra, request_code),
        jni_cache_->GetEnv());
    if (result == nullptr) {
      return nullptr;
    }
    jni_cache_->GetEnv()->SetObjectArrayElement(results, i, result.get());
  }
  return results;
}

jobject RemoteActionTemplatesHandler::EntityDataAsNamedVariantArray(
    const reflection::Schema* entity_data_schema,
    const std::string& serialized_entity_data) const {
  ReflectiveFlatbufferBuilder entity_data_builder(entity_data_schema);
  std::unique_ptr<ReflectiveFlatbuffer> buffer = entity_data_builder.NewRoot();
  buffer->MergeFromSerializedFlatbuffer(serialized_entity_data);
  std::map<std::string, Variant> entity_data_map = buffer->AsFlatMap();
  return AsNamedVariantArray(entity_data_map);
}

}  // namespace libtextclassifier3
