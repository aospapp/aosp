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

#ifndef LIBTEXTCLASSIFIER_ACTIONS_LUA_UTILS_H_
#define LIBTEXTCLASSIFIER_ACTIONS_LUA_UTILS_H_

#include "actions/types.h"
#include "annotator/types.h"
#include "utils/flatbuffers.h"
#include "utils/lua-utils.h"

#ifdef __cplusplus
extern "C" {
#endif
#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"
#ifdef __cplusplus
}
#endif

// Action specific shared lua utilities.
namespace libtextclassifier3 {

// Provides an annotation to lua.
void PushAnnotation(const ClassificationResult& classification,
                    const reflection::Schema* entity_data_schema,
                    LuaEnvironment* env);
void PushAnnotation(const ClassificationResult& classification,
                    StringPiece text,
                    const reflection::Schema* entity_data_schema,
                    LuaEnvironment* env);
void PushAnnotation(const ActionSuggestionAnnotation& annotation,
                    const reflection::Schema* entity_data_schema,
                    LuaEnvironment* env);

// A lua iterator to enumerate annotation.
template <typename Annotation>
class AnnotationIterator
    : public LuaEnvironment::ItemIterator<std::vector<Annotation>> {
 public:
  AnnotationIterator(const reflection::Schema* entity_data_schema,
                     LuaEnvironment* env)
      : env_(env), entity_data_schema_(entity_data_schema) {}
  int Item(const std::vector<Annotation>* annotations, const int64 pos,
           lua_State* state) const override {
    PushAnnotation((*annotations)[pos], entity_data_schema_, env_);
    return 1;
  }
  int Item(const std::vector<Annotation>* annotations, StringPiece key,
           lua_State* state) const override;

 private:
  LuaEnvironment* env_;
  const reflection::Schema* entity_data_schema_;
};

template <>
int AnnotationIterator<ClassificationResult>::Item(
    const std::vector<ClassificationResult>* annotations, StringPiece key,
    lua_State* state) const;

template <>
int AnnotationIterator<ActionSuggestionAnnotation>::Item(
    const std::vector<ActionSuggestionAnnotation>* annotations, StringPiece key,
    lua_State* state) const;

void PushAnnotatedSpan(
    const AnnotatedSpan& annotated_span,
    const AnnotationIterator<ClassificationResult>& annotation_iterator,
    LuaEnvironment* env);

MessageTextSpan ReadSpan(LuaEnvironment* env);
ActionSuggestionAnnotation ReadAnnotation(
    const reflection::Schema* entity_data_schema, LuaEnvironment* env);
int ReadAnnotations(const reflection::Schema* entity_data_schema,
                    LuaEnvironment* env,
                    std::vector<ActionSuggestionAnnotation>* annotations);
ClassificationResult ReadClassificationResult(
    const reflection::Schema* entity_data_schema, LuaEnvironment* env);

// A lua iterator to enumerate annotated spans.
class AnnotatedSpanIterator
    : public LuaEnvironment::ItemIterator<std::vector<AnnotatedSpan>> {
 public:
  AnnotatedSpanIterator(
      const AnnotationIterator<ClassificationResult>& annotation_iterator,
      LuaEnvironment* env)
      : env_(env), annotation_iterator_(annotation_iterator) {}
  AnnotatedSpanIterator(const reflection::Schema* entity_data_schema,
                        LuaEnvironment* env)
      : env_(env), annotation_iterator_(entity_data_schema, env) {}

  int Item(const std::vector<AnnotatedSpan>* spans, const int64 pos,
           lua_State* state) const override {
    PushAnnotatedSpan((*spans)[pos], annotation_iterator_, env_);
    return /*num results=*/1;
  }

 private:
  LuaEnvironment* env_;
  AnnotationIterator<ClassificationResult> annotation_iterator_;
};

// Provides an action to lua.
void PushAction(
    const ActionSuggestion& action,
    const reflection::Schema* entity_data_schema,
    const AnnotationIterator<ActionSuggestionAnnotation>& annotation_iterator,
    LuaEnvironment* env);

ActionSuggestion ReadAction(
    const reflection::Schema* actions_entity_data_schema,
    const reflection::Schema* annotations_entity_data_schema,
    LuaEnvironment* env);
int ReadActions(const reflection::Schema* actions_entity_data_schema,
                const reflection::Schema* annotations_entity_data_schema,
                LuaEnvironment* env, std::vector<ActionSuggestion>* actions);

// A lua iterator to enumerate actions suggestions.
class ActionsIterator
    : public LuaEnvironment::ItemIterator<std::vector<ActionSuggestion>> {
 public:
  ActionsIterator(const reflection::Schema* entity_data_schema,
                  const reflection::Schema* annotations_entity_data_schema,
                  LuaEnvironment* env)
      : env_(env),
        entity_data_schema_(entity_data_schema),
        annotation_iterator_(annotations_entity_data_schema, env) {}
  int Item(const std::vector<ActionSuggestion>* actions, const int64 pos,
           lua_State* state) const override {
    PushAction((*actions)[pos], entity_data_schema_, annotation_iterator_,
               env_);
    return /*num results=*/1;
  }

 private:
  LuaEnvironment* env_;
  const reflection::Schema* entity_data_schema_;
  AnnotationIterator<ActionSuggestionAnnotation> annotation_iterator_;
};

// Conversation message lua iterator.
class ConversationIterator
    : public LuaEnvironment::ItemIterator<std::vector<ConversationMessage>> {
 public:
  ConversationIterator(
      const AnnotationIterator<ClassificationResult>& annotation_iterator,
      LuaEnvironment* env)
      : env_(env),
        annotated_span_iterator_(
            AnnotatedSpanIterator(annotation_iterator, env)) {}
  ConversationIterator(const reflection::Schema* entity_data_schema,
                       LuaEnvironment* env)
      : env_(env),
        annotated_span_iterator_(
            AnnotatedSpanIterator(entity_data_schema, env)) {}

  int Item(const std::vector<ConversationMessage>* messages, const int64 pos,
           lua_State* state) const override;

 private:
  LuaEnvironment* env_;
  AnnotatedSpanIterator annotated_span_iterator_;
};

}  // namespace libtextclassifier3

#endif  // LIBTEXTCLASSIFIER_ACTIONS_LUA_UTILS_H_
