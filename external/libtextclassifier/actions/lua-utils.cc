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

#include "actions/lua-utils.h"

namespace libtextclassifier3 {
namespace {
static constexpr const char* kTextKey = "text";
static constexpr const char* kTimeUsecKey = "parsed_time_ms_utc";
static constexpr const char* kGranularityKey = "granularity";
static constexpr const char* kCollectionKey = "collection";
static constexpr const char* kNameKey = "name";
static constexpr const char* kScoreKey = "score";
static constexpr const char* kPriorityScoreKey = "priority_score";
static constexpr const char* kTypeKey = "type";
static constexpr const char* kResponseTextKey = "response_text";
static constexpr const char* kAnnotationKey = "annotation";
static constexpr const char* kSpanKey = "span";
static constexpr const char* kMessageKey = "message";
static constexpr const char* kBeginKey = "begin";
static constexpr const char* kEndKey = "end";
static constexpr const char* kClassificationKey = "classification";
static constexpr const char* kSerializedEntity = "serialized_entity";
static constexpr const char* kEntityKey = "entity";
}  // namespace

template <>
int AnnotationIterator<ClassificationResult>::Item(
    const std::vector<ClassificationResult>* annotations, StringPiece key,
    lua_State* state) const {
  // Lookup annotation by collection.
  for (const ClassificationResult& annotation : *annotations) {
    if (key.Equals(annotation.collection)) {
      PushAnnotation(annotation, entity_data_schema_, env_);
      return 1;
    }
  }
  TC3_LOG(ERROR) << "No annotation with collection: " << key.ToString()
                 << " found.";
  lua_error(state);
  return 0;
}

template <>
int AnnotationIterator<ActionSuggestionAnnotation>::Item(
    const std::vector<ActionSuggestionAnnotation>* annotations, StringPiece key,
    lua_State* state) const {
  // Lookup annotation by name.
  for (const ActionSuggestionAnnotation& annotation : *annotations) {
    if (key.Equals(annotation.name)) {
      PushAnnotation(annotation, entity_data_schema_, env_);
      return 1;
    }
  }
  TC3_LOG(ERROR) << "No annotation with name: " << key.ToString() << " found.";
  lua_error(state);
  return 0;
}

void PushAnnotation(const ClassificationResult& classification,
                    const reflection::Schema* entity_data_schema,
                    LuaEnvironment* env) {
  if (entity_data_schema == nullptr ||
      classification.serialized_entity_data.empty()) {
    // Empty table.
    lua_newtable(env->state());
  } else {
    env->PushFlatbuffer(entity_data_schema,
                        flatbuffers::GetRoot<flatbuffers::Table>(
                            classification.serialized_entity_data.data()));
  }
  lua_pushinteger(env->state(),
                  classification.datetime_parse_result.time_ms_utc);
  lua_setfield(env->state(), /*idx=*/-2, kTimeUsecKey);
  lua_pushinteger(env->state(),
                  classification.datetime_parse_result.granularity);
  lua_setfield(env->state(), /*idx=*/-2, kGranularityKey);
  env->PushString(classification.collection);
  lua_setfield(env->state(), /*idx=*/-2, kCollectionKey);
  lua_pushnumber(env->state(), classification.score);
  lua_setfield(env->state(), /*idx=*/-2, kScoreKey);
  env->PushString(classification.serialized_entity_data);
  lua_setfield(env->state(), /*idx=*/-2, kSerializedEntity);
}

void PushAnnotation(const ClassificationResult& classification,
                    StringPiece text,
                    const reflection::Schema* entity_data_schema,
                    LuaEnvironment* env) {
  PushAnnotation(classification, entity_data_schema, env);
  env->PushString(text);
  lua_setfield(env->state(), /*idx=*/-2, kTextKey);
}

void PushAnnotatedSpan(
    const AnnotatedSpan& annotated_span,
    const AnnotationIterator<ClassificationResult>& annotation_iterator,
    LuaEnvironment* env) {
  lua_newtable(env->state());
  {
    lua_newtable(env->state());
    lua_pushinteger(env->state(), annotated_span.span.first);
    lua_setfield(env->state(), /*idx=*/-2, kBeginKey);
    lua_pushinteger(env->state(), annotated_span.span.second);
    lua_setfield(env->state(), /*idx=*/-2, kEndKey);
  }
  lua_setfield(env->state(), /*idx=*/-2, kSpanKey);
  annotation_iterator.NewIterator(kClassificationKey,
                                  &annotated_span.classification, env->state());
  lua_setfield(env->state(), /*idx=*/-2, kClassificationKey);
}

MessageTextSpan ReadSpan(LuaEnvironment* env) {
  MessageTextSpan span;
  lua_pushnil(env->state());
  while (lua_next(env->state(), /*idx=*/-2)) {
    const StringPiece key = env->ReadString(/*index=*/-2);
    if (key.Equals(kMessageKey)) {
      span.message_index =
          static_cast<int>(lua_tonumber(env->state(), /*idx=*/-1));
    } else if (key.Equals(kBeginKey)) {
      span.span.first =
          static_cast<int>(lua_tonumber(env->state(), /*idx=*/-1));
    } else if (key.Equals(kEndKey)) {
      span.span.second =
          static_cast<int>(lua_tonumber(env->state(), /*idx=*/-1));
    } else if (key.Equals(kTextKey)) {
      span.text = env->ReadString(/*index=*/-1).ToString();
    } else {
      TC3_LOG(INFO) << "Unknown span field: " << key.ToString();
    }
    lua_pop(env->state(), 1);
  }
  return span;
}

int ReadAnnotations(const reflection::Schema* entity_data_schema,
                    LuaEnvironment* env,
                    std::vector<ActionSuggestionAnnotation>* annotations) {
  if (lua_type(env->state(), /*idx=*/-1) != LUA_TTABLE) {
    TC3_LOG(ERROR) << "Expected annotations table, got: "
                   << lua_type(env->state(), /*idx=*/-1);
    lua_pop(env->state(), 1);
    lua_error(env->state());
    return LUA_ERRRUN;
  }

  // Read actions.
  lua_pushnil(env->state());
  while (lua_next(env->state(), /*idx=*/-2)) {
    if (lua_type(env->state(), /*idx=*/-1) != LUA_TTABLE) {
      TC3_LOG(ERROR) << "Expected annotation table, got: "
                     << lua_type(env->state(), /*idx=*/-1);
      lua_pop(env->state(), 1);
      continue;
    }
    annotations->push_back(ReadAnnotation(entity_data_schema, env));
    lua_pop(env->state(), 1);
  }
  return LUA_OK;
}

ActionSuggestionAnnotation ReadAnnotation(
    const reflection::Schema* entity_data_schema, LuaEnvironment* env) {
  ActionSuggestionAnnotation annotation;
  lua_pushnil(env->state());
  while (lua_next(env->state(), /*idx=*/-2)) {
    const StringPiece key = env->ReadString(/*index=*/-2);
    if (key.Equals(kNameKey)) {
      annotation.name = env->ReadString(/*index=*/-1).ToString();
    } else if (key.Equals(kSpanKey)) {
      annotation.span = ReadSpan(env);
    } else if (key.Equals(kEntityKey)) {
      annotation.entity = ReadClassificationResult(entity_data_schema, env);
    } else {
      TC3_LOG(ERROR) << "Unknown annotation field: " << key.ToString();
    }
    lua_pop(env->state(), 1);
  }
  return annotation;
}

ClassificationResult ReadClassificationResult(
    const reflection::Schema* entity_data_schema, LuaEnvironment* env) {
  ClassificationResult classification;
  lua_pushnil(env->state());
  while (lua_next(env->state(), /*idx=*/-2)) {
    const StringPiece key = env->ReadString(/*index=*/-2);
    if (key.Equals(kCollectionKey)) {
      classification.collection = env->ReadString(/*index=*/-1).ToString();
    } else if (key.Equals(kScoreKey)) {
      classification.score =
          static_cast<float>(lua_tonumber(env->state(), /*idx=*/-1));
    } else if (key.Equals(kTimeUsecKey)) {
      classification.datetime_parse_result.time_ms_utc =
          static_cast<int64>(lua_tonumber(env->state(), /*idx=*/-1));
    } else if (key.Equals(kGranularityKey)) {
      classification.datetime_parse_result.granularity =
          static_cast<DatetimeGranularity>(
              lua_tonumber(env->state(), /*idx=*/-1));
    } else if (key.Equals(kSerializedEntity)) {
      classification.serialized_entity_data =
          env->ReadString(/*index=*/-1).ToString();
    } else if (key.Equals(kEntityKey)) {
      auto buffer = ReflectiveFlatbufferBuilder(entity_data_schema).NewRoot();
      env->ReadFlatbuffer(buffer.get());
      classification.serialized_entity_data = buffer->Serialize();
    } else {
      TC3_LOG(INFO) << "Unknown classification result field: "
                    << key.ToString();
    }
    lua_pop(env->state(), 1);
  }
  return classification;
}

void PushAnnotation(const ActionSuggestionAnnotation& annotation,
                    const reflection::Schema* entity_data_schema,
                    LuaEnvironment* env) {
  PushAnnotation(annotation.entity, annotation.span.text, entity_data_schema,
                 env);
  env->PushString(annotation.name);
  lua_setfield(env->state(), /*idx=*/-2, kNameKey);
  {
    lua_newtable(env->state());
    lua_pushinteger(env->state(), annotation.span.message_index);
    lua_setfield(env->state(), /*idx=*/-2, kMessageKey);
    lua_pushinteger(env->state(), annotation.span.span.first);
    lua_setfield(env->state(), /*idx=*/-2, kBeginKey);
    lua_pushinteger(env->state(), annotation.span.span.second);
    lua_setfield(env->state(), /*idx=*/-2, kEndKey);
  }
  lua_setfield(env->state(), /*idx=*/-2, kSpanKey);
}

void PushAction(
    const ActionSuggestion& action,
    const reflection::Schema* entity_data_schema,
    const AnnotationIterator<ActionSuggestionAnnotation>& annotation_iterator,
    LuaEnvironment* env) {
  if (entity_data_schema == nullptr || action.serialized_entity_data.empty()) {
    // Empty table.
    lua_newtable(env->state());
  } else {
    env->PushFlatbuffer(entity_data_schema,
                        flatbuffers::GetRoot<flatbuffers::Table>(
                            action.serialized_entity_data.data()));
  }
  env->PushString(action.type);
  lua_setfield(env->state(), /*idx=*/-2, kTypeKey);
  env->PushString(action.response_text);
  lua_setfield(env->state(), /*idx=*/-2, kResponseTextKey);
  lua_pushnumber(env->state(), action.score);
  lua_setfield(env->state(), /*idx=*/-2, kScoreKey);
  lua_pushnumber(env->state(), action.priority_score);
  lua_setfield(env->state(), /*idx=*/-2, kPriorityScoreKey);
  annotation_iterator.NewIterator(kAnnotationKey, &action.annotations,
                                  env->state());
  lua_setfield(env->state(), /*idx=*/-2, kAnnotationKey);
}

ActionSuggestion ReadAction(
    const reflection::Schema* actions_entity_data_schema,
    const reflection::Schema* annotations_entity_data_schema,
    LuaEnvironment* env) {
  ActionSuggestion action;
  lua_pushnil(env->state());
  while (lua_next(env->state(), /*idx=*/-2)) {
    const StringPiece key = env->ReadString(/*index=*/-2);
    if (key.Equals(kResponseTextKey)) {
      action.response_text = env->ReadString(/*index=*/-1).ToString();
    } else if (key.Equals(kTypeKey)) {
      action.type = env->ReadString(/*index=*/-1).ToString();
    } else if (key.Equals(kScoreKey)) {
      action.score = static_cast<float>(lua_tonumber(env->state(), /*idx=*/-1));
    } else if (key.Equals(kPriorityScoreKey)) {
      action.priority_score =
          static_cast<float>(lua_tonumber(env->state(), /*idx=*/-1));
    } else if (key.Equals(kAnnotationKey)) {
      ReadAnnotations(actions_entity_data_schema, env, &action.annotations);
    } else if (key.Equals(kEntityKey)) {
      auto buffer =
          ReflectiveFlatbufferBuilder(actions_entity_data_schema).NewRoot();
      env->ReadFlatbuffer(buffer.get());
      action.serialized_entity_data = buffer->Serialize();
    } else {
      TC3_LOG(INFO) << "Unknown action field: " << key.ToString();
    }
    lua_pop(env->state(), 1);
  }
  return action;
}

int ReadActions(const reflection::Schema* actions_entity_data_schema,
                const reflection::Schema* annotations_entity_data_schema,
                LuaEnvironment* env, std::vector<ActionSuggestion>* actions) {
  if (lua_type(env->state(), /*idx=*/-1) != LUA_TTABLE) {
    TC3_LOG(ERROR) << "Expected actions table, got: "
                   << lua_type(env->state(), /*idx=*/-1);
    lua_pop(env->state(), 1);
    lua_error(env->state());
    return LUA_ERRRUN;
  }

  // Read actions.
  lua_pushnil(env->state());
  while (lua_next(env->state(), /*idx=*/-2)) {
    if (lua_type(env->state(), /*idx=*/-1) != LUA_TTABLE) {
      TC3_LOG(ERROR) << "Expected action table, got: "
                     << lua_type(env->state(), /*idx=*/-1);
      lua_pop(env->state(), 1);
      continue;
    }
    actions->push_back(ReadAction(actions_entity_data_schema,
                                  annotations_entity_data_schema, env));
    lua_pop(env->state(), /*n=1*/ 1);
  }
  lua_pop(env->state(), /*n=*/1);

  return LUA_OK;
}

int ConversationIterator::Item(const std::vector<ConversationMessage>* messages,
                               const int64 pos, lua_State* state) const {
  const ConversationMessage& message = (*messages)[pos];
  lua_newtable(state);
  lua_pushinteger(state, message.user_id);
  lua_setfield(state, /*idx=*/-2, "user_id");
  env_->PushString(message.text);
  lua_setfield(state, /*idx=*/-2, "text");
  lua_pushinteger(state, message.reference_time_ms_utc);
  lua_setfield(state, /*idx=*/-2, "time_ms_utc");
  env_->PushString(message.reference_timezone);
  lua_setfield(state, /*idx=*/-2, "timezone");
  annotated_span_iterator_.NewIterator("annotation", &message.annotations,
                                       state);
  lua_setfield(state, /*idx=*/-2, "annotation");
  return 1;
}

}  // namespace libtextclassifier3
