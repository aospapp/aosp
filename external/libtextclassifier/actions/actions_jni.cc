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

// JNI wrapper for actions.

#include "actions/actions_jni.h"

#include <jni.h>
#include <map>
#include <type_traits>
#include <vector>

#include "actions/actions-suggestions.h"
#include "annotator/annotator.h"
#include "annotator/annotator_jni_common.h"
#include "utils/base/integral_types.h"
#include "utils/intents/intent-generator.h"
#include "utils/intents/jni.h"
#include "utils/java/jni-cache.h"
#include "utils/java/scoped_local_ref.h"
#include "utils/java/string_utils.h"
#include "utils/memory/mmap.h"

using libtextclassifier3::ActionsSuggestions;
using libtextclassifier3::ActionsSuggestionsResponse;
using libtextclassifier3::ActionSuggestion;
using libtextclassifier3::ActionSuggestionOptions;
using libtextclassifier3::Annotator;
using libtextclassifier3::Conversation;
using libtextclassifier3::IntentGenerator;
using libtextclassifier3::ScopedLocalRef;
using libtextclassifier3::ToStlString;

// When using the Java's ICU, UniLib needs to be instantiated with a JavaVM
// pointer from JNI. When using a standard ICU the pointer is not needed and the
// objects are instantiated implicitly.
#ifdef TC3_UNILIB_JAVAICU
using libtextclassifier3::UniLib;
#endif

namespace libtextclassifier3 {

namespace {

// Cached state for model inference.
// Keeps a jni cache, intent generator and model instance so that they don't
// have to be recreated for each call.
class ActionsSuggestionsJniContext {
 public:
  static ActionsSuggestionsJniContext* Create(
      const std::shared_ptr<libtextclassifier3::JniCache>& jni_cache,
      std::unique_ptr<ActionsSuggestions> model) {
    if (jni_cache == nullptr || model == nullptr) {
      return nullptr;
    }
    std::unique_ptr<IntentGenerator> intent_generator =
        IntentGenerator::Create(model->model()->android_intent_options(),
                                model->model()->resources(), jni_cache);
    std::unique_ptr<RemoteActionTemplatesHandler> template_handler =
        libtextclassifier3::RemoteActionTemplatesHandler::Create(jni_cache);

    if (intent_generator == nullptr || template_handler == nullptr) {
      return nullptr;
    }

    return new ActionsSuggestionsJniContext(jni_cache, std::move(model),
                                            std::move(intent_generator),
                                            std::move(template_handler));
  }

  std::shared_ptr<libtextclassifier3::JniCache> jni_cache() const {
    return jni_cache_;
  }

  ActionsSuggestions* model() const { return model_.get(); }

  IntentGenerator* intent_generator() const { return intent_generator_.get(); }

  RemoteActionTemplatesHandler* template_handler() const {
    return template_handler_.get();
  }

 private:
  ActionsSuggestionsJniContext(
      const std::shared_ptr<libtextclassifier3::JniCache>& jni_cache,
      std::unique_ptr<ActionsSuggestions> model,
      std::unique_ptr<IntentGenerator> intent_generator,
      std::unique_ptr<RemoteActionTemplatesHandler> template_handler)
      : jni_cache_(jni_cache),
        model_(std::move(model)),
        intent_generator_(std::move(intent_generator)),
        template_handler_(std::move(template_handler)) {}

  std::shared_ptr<libtextclassifier3::JniCache> jni_cache_;
  std::unique_ptr<ActionsSuggestions> model_;
  std::unique_ptr<IntentGenerator> intent_generator_;
  std::unique_ptr<RemoteActionTemplatesHandler> template_handler_;
};

ActionSuggestionOptions FromJavaActionSuggestionOptions(JNIEnv* env,
                                                        jobject joptions) {
  ActionSuggestionOptions options = ActionSuggestionOptions::Default();
  return options;
}

jobjectArray ActionSuggestionsToJObjectArray(
    JNIEnv* env, const ActionsSuggestionsJniContext* context,
    jobject app_context,
    const reflection::Schema* annotations_entity_data_schema,
    const std::vector<ActionSuggestion>& action_result,
    const Conversation& conversation, const jstring device_locales,
    const bool generate_intents) {
  const ScopedLocalRef<jclass> result_class(
      env->FindClass(TC3_PACKAGE_PATH TC3_ACTIONS_CLASS_NAME_STR
                     "$ActionSuggestion"),
      env);
  if (!result_class) {
    TC3_LOG(ERROR) << "Couldn't find ActionSuggestion class.";
    return nullptr;
  }

  const jmethodID result_class_constructor = env->GetMethodID(
      result_class.get(), "<init>",
      "(Ljava/lang/String;Ljava/lang/String;F[L" TC3_PACKAGE_PATH
          TC3_NAMED_VARIANT_CLASS_NAME_STR
      ";[B[L" TC3_PACKAGE_PATH TC3_REMOTE_ACTION_TEMPLATE_CLASS_NAME_STR ";)V");
  const jobjectArray results =
      env->NewObjectArray(action_result.size(), result_class.get(), nullptr);
  for (int i = 0; i < action_result.size(); i++) {
    jobject extras = nullptr;

    const reflection::Schema* actions_entity_data_schema =
        context->model()->entity_data_schema();
    if (actions_entity_data_schema != nullptr &&
        !action_result[i].serialized_entity_data.empty()) {
      extras = context->template_handler()->EntityDataAsNamedVariantArray(
          actions_entity_data_schema, action_result[i].serialized_entity_data);
    }

    jbyteArray serialized_entity_data = nullptr;
    if (!action_result[i].serialized_entity_data.empty()) {
      serialized_entity_data =
          env->NewByteArray(action_result[i].serialized_entity_data.size());
      env->SetByteArrayRegion(
          serialized_entity_data, 0,
          action_result[i].serialized_entity_data.size(),
          reinterpret_cast<const jbyte*>(
              action_result[i].serialized_entity_data.data()));
    }

    jobject remote_action_templates_result = nullptr;
    if (generate_intents) {
      std::vector<RemoteActionTemplate> remote_action_templates;
      if (context->intent_generator()->GenerateIntents(
              device_locales, action_result[i], conversation, app_context,
              actions_entity_data_schema, annotations_entity_data_schema,
              &remote_action_templates)) {
        remote_action_templates_result =
            context->template_handler()->RemoteActionTemplatesToJObjectArray(
                remote_action_templates);
      }
    }

    ScopedLocalRef<jstring> reply = context->jni_cache()->ConvertToJavaString(
        action_result[i].response_text);

    ScopedLocalRef<jobject> result(env->NewObject(
        result_class.get(), result_class_constructor, reply.get(),
        env->NewStringUTF(action_result[i].type.c_str()),
        static_cast<jfloat>(action_result[i].score), extras,
        serialized_entity_data, remote_action_templates_result));
    env->SetObjectArrayElement(results, i, result.get());
  }
  return results;
}

ConversationMessage FromJavaConversationMessage(JNIEnv* env, jobject jmessage) {
  if (!jmessage) {
    return {};
  }

  const ScopedLocalRef<jclass> message_class(
      env->FindClass(TC3_PACKAGE_PATH TC3_ACTIONS_CLASS_NAME_STR
                     "$ConversationMessage"),
      env);
  const std::pair<bool, jobject> status_or_text = CallJniMethod0<jobject>(
      env, jmessage, message_class.get(), &JNIEnv::CallObjectMethod, "getText",
      "Ljava/lang/String;");
  const std::pair<bool, int32> status_or_user_id =
      CallJniMethod0<int32>(env, jmessage, message_class.get(),
                            &JNIEnv::CallIntMethod, "getUserId", "I");
  const std::pair<bool, int64> status_or_reference_time = CallJniMethod0<int64>(
      env, jmessage, message_class.get(), &JNIEnv::CallLongMethod,
      "getReferenceTimeMsUtc", "J");
  const std::pair<bool, jobject> status_or_reference_timezone =
      CallJniMethod0<jobject>(env, jmessage, message_class.get(),
                              &JNIEnv::CallObjectMethod, "getReferenceTimezone",
                              "Ljava/lang/String;");
  const std::pair<bool, jobject> status_or_detected_text_language_tags =
      CallJniMethod0<jobject>(
          env, jmessage, message_class.get(), &JNIEnv::CallObjectMethod,
          "getDetectedTextLanguageTags", "Ljava/lang/String;");
  if (!status_or_text.first || !status_or_user_id.first ||
      !status_or_detected_text_language_tags.first ||
      !status_or_reference_time.first || !status_or_reference_timezone.first) {
    return {};
  }

  ConversationMessage message;
  message.text = ToStlString(env, static_cast<jstring>(status_or_text.second));
  message.user_id = status_or_user_id.second;
  message.reference_time_ms_utc = status_or_reference_time.second;
  message.reference_timezone = ToStlString(
      env, static_cast<jstring>(status_or_reference_timezone.second));
  message.detected_text_language_tags = ToStlString(
      env, static_cast<jstring>(status_or_detected_text_language_tags.second));
  return message;
}

Conversation FromJavaConversation(JNIEnv* env, jobject jconversation) {
  if (!jconversation) {
    return {};
  }

  const ScopedLocalRef<jclass> conversation_class(
      env->FindClass(TC3_PACKAGE_PATH TC3_ACTIONS_CLASS_NAME_STR
                     "$Conversation"),
      env);

  const std::pair<bool, jobject> status_or_messages = CallJniMethod0<jobject>(
      env, jconversation, conversation_class.get(), &JNIEnv::CallObjectMethod,
      "getConversationMessages",
      "[L" TC3_PACKAGE_PATH TC3_ACTIONS_CLASS_NAME_STR "$ConversationMessage;");

  if (!status_or_messages.first) {
    return {};
  }

  const jobjectArray jmessages =
      reinterpret_cast<jobjectArray>(status_or_messages.second);

  const int size = env->GetArrayLength(jmessages);

  std::vector<ConversationMessage> messages;
  for (int i = 0; i < size; i++) {
    jobject jmessage = env->GetObjectArrayElement(jmessages, i);
    ConversationMessage message = FromJavaConversationMessage(env, jmessage);
    messages.push_back(message);
  }
  Conversation conversation;
  conversation.messages = messages;
  return conversation;
}

jstring GetLocalesFromMmap(JNIEnv* env, libtextclassifier3::ScopedMmap* mmap) {
  if (!mmap->handle().ok()) {
    return env->NewStringUTF("");
  }
  const ActionsModel* model = libtextclassifier3::ViewActionsModel(
      mmap->handle().start(), mmap->handle().num_bytes());
  if (!model || !model->locales()) {
    return env->NewStringUTF("");
  }
  return env->NewStringUTF(model->locales()->c_str());
}

jint GetVersionFromMmap(JNIEnv* env, libtextclassifier3::ScopedMmap* mmap) {
  if (!mmap->handle().ok()) {
    return 0;
  }
  const ActionsModel* model = libtextclassifier3::ViewActionsModel(
      mmap->handle().start(), mmap->handle().num_bytes());
  if (!model) {
    return 0;
  }
  return model->version();
}

jstring GetNameFromMmap(JNIEnv* env, libtextclassifier3::ScopedMmap* mmap) {
  if (!mmap->handle().ok()) {
    return env->NewStringUTF("");
  }
  const ActionsModel* model = libtextclassifier3::ViewActionsModel(
      mmap->handle().start(), mmap->handle().num_bytes());
  if (!model || !model->name()) {
    return env->NewStringUTF("");
  }
  return env->NewStringUTF(model->name()->c_str());
}
}  // namespace
}  // namespace libtextclassifier3

using libtextclassifier3::ActionsSuggestionsJniContext;
using libtextclassifier3::ActionSuggestionsToJObjectArray;
using libtextclassifier3::FromJavaActionSuggestionOptions;
using libtextclassifier3::FromJavaConversation;

TC3_JNI_METHOD(jlong, TC3_ACTIONS_CLASS_NAME, nativeNewActionsModel)
(JNIEnv* env, jobject thiz, jint fd, jbyteArray serialized_preconditions) {
  std::shared_ptr<libtextclassifier3::JniCache> jni_cache =
      libtextclassifier3::JniCache::Create(env);
  std::string preconditions;
  if (serialized_preconditions != nullptr &&
      !libtextclassifier3::JByteArrayToString(env, serialized_preconditions,
                                              &preconditions)) {
    TC3_LOG(ERROR) << "Could not convert serialized preconditions.";
    return 0;
  }
#ifdef TC3_UNILIB_JAVAICU
  return reinterpret_cast<jlong>(ActionsSuggestionsJniContext::Create(
      jni_cache,
      ActionsSuggestions::FromFileDescriptor(
          fd, std::unique_ptr<UniLib>(new UniLib(jni_cache)), preconditions)));
#else
  return reinterpret_cast<jlong>(ActionsSuggestionsJniContext::Create(
      jni_cache, ActionsSuggestions::FromFileDescriptor(fd, /*unilib=*/nullptr,
                                                        preconditions)));
#endif  // TC3_UNILIB_JAVAICU
}

TC3_JNI_METHOD(jlong, TC3_ACTIONS_CLASS_NAME, nativeNewActionsModelFromPath)
(JNIEnv* env, jobject thiz, jstring path, jbyteArray serialized_preconditions) {
  std::shared_ptr<libtextclassifier3::JniCache> jni_cache =
      libtextclassifier3::JniCache::Create(env);
  const std::string path_str = ToStlString(env, path);
  std::string preconditions;
  if (serialized_preconditions != nullptr &&
      !libtextclassifier3::JByteArrayToString(env, serialized_preconditions,
                                              &preconditions)) {
    TC3_LOG(ERROR) << "Could not convert serialized preconditions.";
    return 0;
  }
#ifdef TC3_UNILIB_JAVAICU
  return reinterpret_cast<jlong>(ActionsSuggestionsJniContext::Create(
      jni_cache, ActionsSuggestions::FromPath(
                     path_str, std::unique_ptr<UniLib>(new UniLib(jni_cache)),
                     preconditions)));
#else
  return reinterpret_cast<jlong>(ActionsSuggestionsJniContext::Create(
      jni_cache, ActionsSuggestions::FromPath(path_str, /*unilib=*/nullptr,
                                              preconditions)));
#endif  // TC3_UNILIB_JAVAICU
}

TC3_JNI_METHOD(jobjectArray, TC3_ACTIONS_CLASS_NAME, nativeSuggestActions)
(JNIEnv* env, jobject clazz, jlong ptr, jobject jconversation, jobject joptions,
 jlong annotatorPtr, jobject app_context, jstring device_locales,
 jboolean generate_intents) {
  if (!ptr) {
    return nullptr;
  }
  const Conversation conversation = FromJavaConversation(env, jconversation);
  const ActionSuggestionOptions options =
      FromJavaActionSuggestionOptions(env, joptions);
  const ActionsSuggestionsJniContext* context =
      reinterpret_cast<ActionsSuggestionsJniContext*>(ptr);
  const Annotator* annotator = reinterpret_cast<Annotator*>(annotatorPtr);

  const ActionsSuggestionsResponse response =
      context->model()->SuggestActions(conversation, annotator, options);

  const reflection::Schema* anntotations_entity_data_schema =
      annotator ? annotator->entity_data_schema() : nullptr;
  return ActionSuggestionsToJObjectArray(
      env, context, app_context, anntotations_entity_data_schema,
      response.actions, conversation, device_locales, generate_intents);
}

TC3_JNI_METHOD(void, TC3_ACTIONS_CLASS_NAME, nativeCloseActionsModel)
(JNIEnv* env, jobject clazz, jlong model_ptr) {
  const ActionsSuggestionsJniContext* context =
      reinterpret_cast<ActionsSuggestionsJniContext*>(model_ptr);
  delete context;
}

TC3_JNI_METHOD(jstring, TC3_ACTIONS_CLASS_NAME, nativeGetLocales)
(JNIEnv* env, jobject clazz, jint fd) {
  const std::unique_ptr<libtextclassifier3::ScopedMmap> mmap(
      new libtextclassifier3::ScopedMmap(fd));
  return libtextclassifier3::GetLocalesFromMmap(env, mmap.get());
}

TC3_JNI_METHOD(jstring, TC3_ACTIONS_CLASS_NAME, nativeGetName)
(JNIEnv* env, jobject clazz, jint fd) {
  const std::unique_ptr<libtextclassifier3::ScopedMmap> mmap(
      new libtextclassifier3::ScopedMmap(fd));
  return libtextclassifier3::GetNameFromMmap(env, mmap.get());
}

TC3_JNI_METHOD(jint, TC3_ACTIONS_CLASS_NAME, nativeGetVersion)
(JNIEnv* env, jobject clazz, jint fd) {
  const std::unique_ptr<libtextclassifier3::ScopedMmap> mmap(
      new libtextclassifier3::ScopedMmap(fd));
  return libtextclassifier3::GetVersionFromMmap(env, mmap.get());
}
