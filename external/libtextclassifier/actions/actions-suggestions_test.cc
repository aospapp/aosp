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

#include "actions/actions-suggestions.h"

#include <fstream>
#include <iterator>
#include <memory>

#include "actions/actions_model_generated.h"
#include "actions/test_utils.h"
#include "actions/zlib-utils.h"
#include "annotator/collections.h"
#include "annotator/types.h"
#include "utils/flatbuffers.h"
#include "utils/flatbuffers_generated.h"
#include "utils/hash/farmhash.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "flatbuffers/flatbuffers.h"
#include "flatbuffers/reflection.h"

namespace libtextclassifier3 {
namespace {
using testing::_;

constexpr char kModelFileName[] = "actions_suggestions_test.model";
constexpr char kHashGramModelFileName[] =
    "actions_suggestions_test.hashgram.model";

std::string ReadFile(const std::string& file_name) {
  std::ifstream file_stream(file_name);
  return std::string(std::istreambuf_iterator<char>(file_stream), {});
}

std::string GetModelPath() {
  return "";
}

class ActionsSuggestionsTest : public testing::Test {
 protected:
  ActionsSuggestionsTest() : INIT_UNILIB_FOR_TESTING(unilib_) {}
  std::unique_ptr<ActionsSuggestions> LoadTestModel() {
    return ActionsSuggestions::FromPath(GetModelPath() + kModelFileName,
                                        &unilib_);
  }
  std::unique_ptr<ActionsSuggestions> LoadHashGramTestModel() {
    return ActionsSuggestions::FromPath(GetModelPath() + kHashGramModelFileName,
                                        &unilib_);
  }
  UniLib unilib_;
};

TEST_F(ActionsSuggestionsTest, InstantiateActionSuggestions) {
  EXPECT_THAT(LoadTestModel(), testing::NotNull());
}

TEST_F(ActionsSuggestionsTest, SuggestActions) {
  std::unique_ptr<ActionsSuggestions> actions_suggestions = LoadTestModel();
  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1, "Where are you?", /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/{}, /*locales=*/"en"}}});
  EXPECT_EQ(response.actions.size(), 3 /* share_location + 2 smart replies*/);
}

TEST_F(ActionsSuggestionsTest, SuggestNoActionsForUnknownLocale) {
  std::unique_ptr<ActionsSuggestions> actions_suggestions = LoadTestModel();
  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1, "Where are you?", /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/{}, /*locales=*/"zz"}}});
  EXPECT_THAT(response.actions, testing::IsEmpty());
}

TEST_F(ActionsSuggestionsTest, SuggestActionsFromAnnotations) {
  std::unique_ptr<ActionsSuggestions> actions_suggestions = LoadTestModel();
  AnnotatedSpan annotation;
  annotation.span = {11, 15};
  annotation.classification = {ClassificationResult("address", 1.0)};
  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1, "are you at home?",
             /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/{annotation},
             /*locales=*/"en"}}});
  ASSERT_GE(response.actions.size(), 1);
  EXPECT_EQ(response.actions.front().type, "view_map");
  EXPECT_EQ(response.actions.front().score, 1.0);
}

TEST_F(ActionsSuggestionsTest, SuggestActionsFromAnnotationsWithEntityData) {
  const std::string actions_model_string =
      ReadFile(GetModelPath() + kModelFileName);
  std::unique_ptr<ActionsModelT> actions_model =
      UnPackActionsModel(actions_model_string.c_str());
  SetTestEntityDataSchema(actions_model.get());

  // Set custom actions from annotations config.
  actions_model->annotation_actions_spec->annotation_mapping.clear();
  actions_model->annotation_actions_spec->annotation_mapping.emplace_back(
      new AnnotationActionsSpec_::AnnotationMappingT);
  AnnotationActionsSpec_::AnnotationMappingT* mapping =
      actions_model->annotation_actions_spec->annotation_mapping.back().get();
  mapping->annotation_collection = "address";
  mapping->action.reset(new ActionSuggestionSpecT);
  mapping->action->type = "save_location";
  mapping->action->score = 1.0;
  mapping->action->priority_score = 2.0;
  mapping->entity_field.reset(new FlatbufferFieldPathT);
  mapping->entity_field->field.emplace_back(new FlatbufferFieldT);
  mapping->entity_field->field.back()->field_name = "location";

  flatbuffers::FlatBufferBuilder builder;
  FinishActionsModelBuffer(builder,
                           ActionsModel::Pack(builder, actions_model.get()));
  std::unique_ptr<ActionsSuggestions> actions_suggestions =
      ActionsSuggestions::FromUnownedBuffer(
          reinterpret_cast<const uint8_t*>(builder.GetBufferPointer()),
          builder.GetSize(), &unilib_);

  AnnotatedSpan annotation;
  annotation.span = {11, 15};
  annotation.classification = {ClassificationResult("address", 1.0)};
  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1, "are you at home?",
             /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/{annotation},
             /*locales=*/"en"}}});
  ASSERT_GE(response.actions.size(), 1);
  EXPECT_EQ(response.actions.front().type, "save_location");
  EXPECT_EQ(response.actions.front().score, 1.0);

  // Check that the `location` entity field holds the text from the address
  // annotation.
  const flatbuffers::Table* entity =
      flatbuffers::GetAnyRoot(reinterpret_cast<const unsigned char*>(
          response.actions.front().serialized_entity_data.data()));
  EXPECT_EQ(entity->GetPointer<const flatbuffers::String*>(/*field=*/6)->str(),
            "home");
}

TEST_F(ActionsSuggestionsTest, SuggestActionsFromDuplicatedAnnotations) {
  std::unique_ptr<ActionsSuggestions> actions_suggestions = LoadTestModel();
  AnnotatedSpan flight_annotation;
  flight_annotation.span = {11, 15};
  flight_annotation.classification = {ClassificationResult("flight", 2.5)};
  AnnotatedSpan flight_annotation2;
  flight_annotation2.span = {35, 39};
  flight_annotation2.classification = {ClassificationResult("flight", 3.0)};
  AnnotatedSpan email_annotation;
  email_annotation.span = {55, 68};
  email_annotation.classification = {ClassificationResult("email", 2.0)};

  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1,
             "call me at LX38 or send message to LX38 or test@test.com.",
             /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/
             {flight_annotation, flight_annotation2, email_annotation},
             /*locales=*/"en"}}});

  ASSERT_GE(response.actions.size(), 2);
  EXPECT_EQ(response.actions[0].type, "track_flight");
  EXPECT_EQ(response.actions[0].score, 3.0);
  EXPECT_EQ(response.actions[1].type, "send_email");
  EXPECT_EQ(response.actions[1].score, 2.0);
}

TEST_F(ActionsSuggestionsTest, SuggestActionsAnnotationsNoDeduplication) {
  const std::string actions_model_string =
      ReadFile(GetModelPath() + kModelFileName);
  std::unique_ptr<ActionsModelT> actions_model =
      UnPackActionsModel(actions_model_string.c_str());
  // Disable deduplication.
  actions_model->annotation_actions_spec->deduplicate_annotations = false;
  flatbuffers::FlatBufferBuilder builder;
  FinishActionsModelBuffer(builder,
                           ActionsModel::Pack(builder, actions_model.get()));
  std::unique_ptr<ActionsSuggestions> actions_suggestions =
      ActionsSuggestions::FromUnownedBuffer(
          reinterpret_cast<const uint8_t*>(builder.GetBufferPointer()),
          builder.GetSize(), &unilib_);
  AnnotatedSpan flight_annotation;
  flight_annotation.span = {11, 15};
  flight_annotation.classification = {ClassificationResult("flight", 2.5)};
  AnnotatedSpan flight_annotation2;
  flight_annotation2.span = {35, 39};
  flight_annotation2.classification = {ClassificationResult("flight", 3.0)};
  AnnotatedSpan email_annotation;
  email_annotation.span = {55, 68};
  email_annotation.classification = {ClassificationResult("email", 2.0)};

  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1,
             "call me at LX38 or send message to LX38 or test@test.com.",
             /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/
             {flight_annotation, flight_annotation2, email_annotation},
             /*locales=*/"en"}}});

  ASSERT_GE(response.actions.size(), 3);
  EXPECT_EQ(response.actions[0].type, "track_flight");
  EXPECT_EQ(response.actions[0].score, 3.0);
  EXPECT_EQ(response.actions[1].type, "track_flight");
  EXPECT_EQ(response.actions[1].score, 2.5);
  EXPECT_EQ(response.actions[2].type, "send_email");
  EXPECT_EQ(response.actions[2].score, 2.0);
}

ActionsSuggestionsResponse TestSuggestActionsFromAnnotations(
    const std::function<void(ActionsModelT*)>& set_config_fn,
    const UniLib* unilib = nullptr) {
  const std::string actions_model_string =
      ReadFile(GetModelPath() + kModelFileName);
  std::unique_ptr<ActionsModelT> actions_model =
      UnPackActionsModel(actions_model_string.c_str());

  // Set custom config.
  set_config_fn(actions_model.get());

  // Disable smart reply for easier testing.
  actions_model->preconditions->min_smart_reply_triggering_score = 1.0;

  flatbuffers::FlatBufferBuilder builder;
  FinishActionsModelBuffer(builder,
                           ActionsModel::Pack(builder, actions_model.get()));
  std::unique_ptr<ActionsSuggestions> actions_suggestions =
      ActionsSuggestions::FromUnownedBuffer(
          reinterpret_cast<const uint8_t*>(builder.GetBufferPointer()),
          builder.GetSize(), unilib);

  AnnotatedSpan flight_annotation;
  flight_annotation.span = {15, 19};
  flight_annotation.classification = {ClassificationResult("flight", 2.0)};
  AnnotatedSpan email_annotation;
  email_annotation.span = {0, 16};
  email_annotation.classification = {ClassificationResult("email", 1.0)};

  return actions_suggestions->SuggestActions(
      {{{/*user_id=*/ActionsSuggestions::kLocalUserId,
         "hehe@android.com",
         /*reference_time_ms_utc=*/0,
         /*reference_timezone=*/"Europe/Zurich",
         /*annotations=*/
         {email_annotation},
         /*locales=*/"en"},
        {/*user_id=*/2,
         "yoyo@android.com",
         /*reference_time_ms_utc=*/0,
         /*reference_timezone=*/"Europe/Zurich",
         /*annotations=*/
         {email_annotation},
         /*locales=*/"en"},
        {/*user_id=*/1,
         "test@android.com",
         /*reference_time_ms_utc=*/0,
         /*reference_timezone=*/"Europe/Zurich",
         /*annotations=*/
         {email_annotation},
         /*locales=*/"en"},
        {/*user_id=*/1,
         "I am on flight LX38.",
         /*reference_time_ms_utc=*/0,
         /*reference_timezone=*/"Europe/Zurich",
         /*annotations=*/
         {flight_annotation},
         /*locales=*/"en"}}});
}

TEST_F(ActionsSuggestionsTest, SuggestActionsWithAnnotationsOnlyLastMessage) {
  const ActionsSuggestionsResponse response = TestSuggestActionsFromAnnotations(
      [](ActionsModelT* actions_model) {
        actions_model->annotation_actions_spec->include_local_user_messages =
            false;
        actions_model->annotation_actions_spec->only_until_last_sent = true;
        actions_model->annotation_actions_spec->max_history_from_any_person = 1;
        actions_model->annotation_actions_spec->max_history_from_last_person =
            1;
      },
      &unilib_);
  EXPECT_EQ(response.actions.size(), 1);
  EXPECT_EQ(response.actions[0].type, "track_flight");
}

TEST_F(ActionsSuggestionsTest, SuggestActionsWithAnnotationsOnlyLastPerson) {
  const ActionsSuggestionsResponse response = TestSuggestActionsFromAnnotations(
      [](ActionsModelT* actions_model) {
        actions_model->annotation_actions_spec->include_local_user_messages =
            false;
        actions_model->annotation_actions_spec->only_until_last_sent = true;
        actions_model->annotation_actions_spec->max_history_from_any_person = 1;
        actions_model->annotation_actions_spec->max_history_from_last_person =
            3;
      },
      &unilib_);
  EXPECT_EQ(response.actions.size(), 2);
  EXPECT_EQ(response.actions[0].type, "track_flight");
  EXPECT_EQ(response.actions[1].type, "send_email");
}

TEST_F(ActionsSuggestionsTest, SuggestActionsWithAnnotationsFromAny) {
  const ActionsSuggestionsResponse response = TestSuggestActionsFromAnnotations(
      [](ActionsModelT* actions_model) {
        actions_model->annotation_actions_spec->include_local_user_messages =
            false;
        actions_model->annotation_actions_spec->only_until_last_sent = true;
        actions_model->annotation_actions_spec->max_history_from_any_person = 2;
        actions_model->annotation_actions_spec->max_history_from_last_person =
            1;
      },
      &unilib_);
  EXPECT_EQ(response.actions.size(), 2);
  EXPECT_EQ(response.actions[0].type, "track_flight");
  EXPECT_EQ(response.actions[1].type, "send_email");
}

TEST_F(ActionsSuggestionsTest,
       SuggestActionsWithAnnotationsFromAnyManyMessages) {
  const ActionsSuggestionsResponse response = TestSuggestActionsFromAnnotations(
      [](ActionsModelT* actions_model) {
        actions_model->annotation_actions_spec->include_local_user_messages =
            false;
        actions_model->annotation_actions_spec->only_until_last_sent = true;
        actions_model->annotation_actions_spec->max_history_from_any_person = 3;
        actions_model->annotation_actions_spec->max_history_from_last_person =
            1;
      },
      &unilib_);
  EXPECT_EQ(response.actions.size(), 3);
  EXPECT_EQ(response.actions[0].type, "track_flight");
  EXPECT_EQ(response.actions[1].type, "send_email");
  EXPECT_EQ(response.actions[2].type, "send_email");
}

TEST_F(ActionsSuggestionsTest,
       SuggestActionsWithAnnotationsFromAnyManyMessagesButNotLocalUser) {
  const ActionsSuggestionsResponse response = TestSuggestActionsFromAnnotations(
      [](ActionsModelT* actions_model) {
        actions_model->annotation_actions_spec->include_local_user_messages =
            false;
        actions_model->annotation_actions_spec->only_until_last_sent = true;
        actions_model->annotation_actions_spec->max_history_from_any_person = 5;
        actions_model->annotation_actions_spec->max_history_from_last_person =
            1;
      },
      &unilib_);
  EXPECT_EQ(response.actions.size(), 3);
  EXPECT_EQ(response.actions[0].type, "track_flight");
  EXPECT_EQ(response.actions[1].type, "send_email");
  EXPECT_EQ(response.actions[2].type, "send_email");
}

TEST_F(ActionsSuggestionsTest,
       SuggestActionsWithAnnotationsFromAnyManyMessagesAlsoFromLocalUser) {
  const ActionsSuggestionsResponse response = TestSuggestActionsFromAnnotations(
      [](ActionsModelT* actions_model) {
        actions_model->annotation_actions_spec->include_local_user_messages =
            true;
        actions_model->annotation_actions_spec->only_until_last_sent = false;
        actions_model->annotation_actions_spec->max_history_from_any_person = 5;
        actions_model->annotation_actions_spec->max_history_from_last_person =
            1;
      },
      &unilib_);
  EXPECT_EQ(response.actions.size(), 4);
  EXPECT_EQ(response.actions[0].type, "track_flight");
  EXPECT_EQ(response.actions[1].type, "send_email");
  EXPECT_EQ(response.actions[2].type, "send_email");
  EXPECT_EQ(response.actions[3].type, "send_email");
}

void TestSuggestActionsWithThreshold(
    const std::function<void(ActionsModelT*)>& set_value_fn,
    const UniLib* unilib = nullptr, const int expected_size = 0,
    const std::string& preconditions_overwrite = "") {
  const std::string actions_model_string =
      ReadFile(GetModelPath() + kModelFileName);
  std::unique_ptr<ActionsModelT> actions_model =
      UnPackActionsModel(actions_model_string.c_str());
  set_value_fn(actions_model.get());
  flatbuffers::FlatBufferBuilder builder;
  FinishActionsModelBuffer(builder,
                           ActionsModel::Pack(builder, actions_model.get()));
  std::unique_ptr<ActionsSuggestions> actions_suggestions =
      ActionsSuggestions::FromUnownedBuffer(
          reinterpret_cast<const uint8_t*>(builder.GetBufferPointer()),
          builder.GetSize(), unilib, preconditions_overwrite);
  ASSERT_TRUE(actions_suggestions);
  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1, "I have the low-ground. Where are you?",
             /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/{}, /*locales=*/"en"}}});
  EXPECT_LE(response.actions.size(), expected_size);
}

TEST_F(ActionsSuggestionsTest, SuggestActionsWithTriggeringScore) {
  TestSuggestActionsWithThreshold(
      [](ActionsModelT* actions_model) {
        actions_model->preconditions->min_smart_reply_triggering_score = 1.0;
      },
      &unilib_,
      /*expected_size=*/1 /*no smart reply, only actions*/
  );
}

TEST_F(ActionsSuggestionsTest, SuggestActionsWithMinReplyScore) {
  TestSuggestActionsWithThreshold(
      [](ActionsModelT* actions_model) {
        actions_model->preconditions->min_reply_score_threshold = 1.0;
      },
      &unilib_,
      /*expected_size=*/1 /*no smart reply, only actions*/
  );
}

TEST_F(ActionsSuggestionsTest, SuggestActionsWithSensitiveTopicScore) {
  TestSuggestActionsWithThreshold(
      [](ActionsModelT* actions_model) {
        actions_model->preconditions->max_sensitive_topic_score = 0.0;
      },
      &unilib_,
      /*expected_size=*/4 /* no sensitive prediction in test model*/);
}

TEST_F(ActionsSuggestionsTest, SuggestActionsWithMaxInputLength) {
  TestSuggestActionsWithThreshold(
      [](ActionsModelT* actions_model) {
        actions_model->preconditions->max_input_length = 0;
      },
      &unilib_);
}

TEST_F(ActionsSuggestionsTest, SuggestActionsWithMinInputLength) {
  TestSuggestActionsWithThreshold(
      [](ActionsModelT* actions_model) {
        actions_model->preconditions->min_input_length = 100;
      },
      &unilib_);
}

TEST_F(ActionsSuggestionsTest, SuggestActionsWithPreconditionsOverwrite) {
  TriggeringPreconditionsT preconditions_overwrite;
  preconditions_overwrite.max_input_length = 0;
  flatbuffers::FlatBufferBuilder builder;
  builder.Finish(
      TriggeringPreconditions::Pack(builder, &preconditions_overwrite));
  TestSuggestActionsWithThreshold(
      // Keep model untouched.
      [](ActionsModelT* actions_model) {}, &unilib_,
      /*expected_size=*/0,
      std::string(reinterpret_cast<const char*>(builder.GetBufferPointer()),
                  builder.GetSize()));
}

#ifdef TC3_UNILIB_ICU
TEST_F(ActionsSuggestionsTest, SuggestActionsLowConfidence) {
  TestSuggestActionsWithThreshold(
      [](ActionsModelT* actions_model) {
        actions_model->preconditions->suppress_on_low_confidence_input = true;
        actions_model->low_confidence_rules.reset(new RulesModelT);
        actions_model->low_confidence_rules->rule.emplace_back(
            new RulesModel_::RuleT);
        actions_model->low_confidence_rules->rule.back()->pattern =
            "low-ground";
      },
      &unilib_);
}

TEST_F(ActionsSuggestionsTest, SuggestActionsLowConfidenceInputOutput) {
  const std::string actions_model_string =
      ReadFile(GetModelPath() + kModelFileName);
  std::unique_ptr<ActionsModelT> actions_model =
      UnPackActionsModel(actions_model_string.c_str());
  // Add custom triggering rule.
  actions_model->rules.reset(new RulesModelT());
  actions_model->rules->rule.emplace_back(new RulesModel_::RuleT);
  RulesModel_::RuleT* rule = actions_model->rules->rule.back().get();
  rule->pattern = "^(?i:hello\\s(there))$";
  {
    std::unique_ptr<RulesModel_::Rule_::RuleActionSpecT> rule_action(
        new RulesModel_::Rule_::RuleActionSpecT);
    rule_action->action.reset(new ActionSuggestionSpecT);
    rule_action->action->type = "text_reply";
    rule_action->action->response_text = "General Desaster!";
    rule_action->action->score = 1.0f;
    rule_action->action->priority_score = 1.0f;
    rule->actions.push_back(std::move(rule_action));
  }
  {
    std::unique_ptr<RulesModel_::Rule_::RuleActionSpecT> rule_action(
        new RulesModel_::Rule_::RuleActionSpecT);
    rule_action->action.reset(new ActionSuggestionSpecT);
    rule_action->action->type = "text_reply";
    rule_action->action->response_text = "General Kenobi!";
    rule_action->action->score = 1.0f;
    rule_action->action->priority_score = 1.0f;
    rule->actions.push_back(std::move(rule_action));
  }

  // Add input-output low confidence rule.
  actions_model->preconditions->suppress_on_low_confidence_input = true;
  actions_model->low_confidence_rules.reset(new RulesModelT);
  actions_model->low_confidence_rules->rule.emplace_back(
      new RulesModel_::RuleT);
  actions_model->low_confidence_rules->rule.back()->pattern = "hello";
  actions_model->low_confidence_rules->rule.back()->output_pattern =
      "(?i:desaster)";

  flatbuffers::FlatBufferBuilder builder;
  FinishActionsModelBuffer(builder,
                           ActionsModel::Pack(builder, actions_model.get()));
  std::unique_ptr<ActionsSuggestions> actions_suggestions =
      ActionsSuggestions::FromUnownedBuffer(
          reinterpret_cast<const uint8_t*>(builder.GetBufferPointer()),
          builder.GetSize(), &unilib_);
  ASSERT_TRUE(actions_suggestions);
  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1, "hello there",
             /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/{}, /*locales=*/"en"}}});
  ASSERT_GE(response.actions.size(), 1);
  EXPECT_EQ(response.actions[0].response_text, "General Kenobi!");
}

TEST_F(ActionsSuggestionsTest,
       SuggestActionsLowConfidenceInputOutputOverwrite) {
  const std::string actions_model_string =
      ReadFile(GetModelPath() + kModelFileName);
  std::unique_ptr<ActionsModelT> actions_model =
      UnPackActionsModel(actions_model_string.c_str());
  actions_model->low_confidence_rules.reset();

  // Add custom triggering rule.
  actions_model->rules.reset(new RulesModelT());
  actions_model->rules->rule.emplace_back(new RulesModel_::RuleT);
  RulesModel_::RuleT* rule = actions_model->rules->rule.back().get();
  rule->pattern = "^(?i:hello\\s(there))$";
  {
    std::unique_ptr<RulesModel_::Rule_::RuleActionSpecT> rule_action(
        new RulesModel_::Rule_::RuleActionSpecT);
    rule_action->action.reset(new ActionSuggestionSpecT);
    rule_action->action->type = "text_reply";
    rule_action->action->response_text = "General Desaster!";
    rule_action->action->score = 1.0f;
    rule_action->action->priority_score = 1.0f;
    rule->actions.push_back(std::move(rule_action));
  }
  {
    std::unique_ptr<RulesModel_::Rule_::RuleActionSpecT> rule_action(
        new RulesModel_::Rule_::RuleActionSpecT);
    rule_action->action.reset(new ActionSuggestionSpecT);
    rule_action->action->type = "text_reply";
    rule_action->action->response_text = "General Kenobi!";
    rule_action->action->score = 1.0f;
    rule_action->action->priority_score = 1.0f;
    rule->actions.push_back(std::move(rule_action));
  }

  // Add custom triggering rule via overwrite.
  actions_model->preconditions->low_confidence_rules.reset();
  TriggeringPreconditionsT preconditions;
  preconditions.suppress_on_low_confidence_input = true;
  preconditions.low_confidence_rules.reset(new RulesModelT);
  preconditions.low_confidence_rules->rule.emplace_back(new RulesModel_::RuleT);
  preconditions.low_confidence_rules->rule.back()->pattern = "hello";
  preconditions.low_confidence_rules->rule.back()->output_pattern =
      "(?i:desaster)";
  flatbuffers::FlatBufferBuilder preconditions_builder;
  preconditions_builder.Finish(
      TriggeringPreconditions::Pack(preconditions_builder, &preconditions));
  std::string serialize_preconditions = std::string(
      reinterpret_cast<const char*>(preconditions_builder.GetBufferPointer()),
      preconditions_builder.GetSize());

  flatbuffers::FlatBufferBuilder builder;
  FinishActionsModelBuffer(builder,
                           ActionsModel::Pack(builder, actions_model.get()));
  std::unique_ptr<ActionsSuggestions> actions_suggestions =
      ActionsSuggestions::FromUnownedBuffer(
          reinterpret_cast<const uint8_t*>(builder.GetBufferPointer()),
          builder.GetSize(), &unilib_, serialize_preconditions);

  ASSERT_TRUE(actions_suggestions);
  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1, "hello there",
             /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/{}, /*locales=*/"en"}}});
  ASSERT_GE(response.actions.size(), 1);
  EXPECT_EQ(response.actions[0].response_text, "General Kenobi!");
}
#endif

TEST_F(ActionsSuggestionsTest, SuppressActionsFromAnnotationsOnSensitiveTopic) {
  const std::string actions_model_string =
      ReadFile(GetModelPath() + kModelFileName);
  std::unique_ptr<ActionsModelT> actions_model =
      UnPackActionsModel(actions_model_string.c_str());

  // Don't test if no sensitivity score is produced
  if (actions_model->tflite_model_spec->output_sensitive_topic_score < 0) {
    return;
  }

  actions_model->preconditions->max_sensitive_topic_score = 0.0;
  actions_model->preconditions->suppress_on_sensitive_topic = true;
  flatbuffers::FlatBufferBuilder builder;
  FinishActionsModelBuffer(builder,
                           ActionsModel::Pack(builder, actions_model.get()));
  std::unique_ptr<ActionsSuggestions> actions_suggestions =
      ActionsSuggestions::FromUnownedBuffer(
          reinterpret_cast<const uint8_t*>(builder.GetBufferPointer()),
          builder.GetSize(), &unilib_);
  AnnotatedSpan annotation;
  annotation.span = {11, 15};
  annotation.classification = {
      ClassificationResult(Collections::Address(), 1.0)};
  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1, "are you at home?",
             /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/{annotation},
             /*locales=*/"en"}}});
  EXPECT_THAT(response.actions, testing::IsEmpty());
}

TEST_F(ActionsSuggestionsTest, SuggestActionsWithLongerConversation) {
  const std::string actions_model_string =
      ReadFile(GetModelPath() + kModelFileName);
  std::unique_ptr<ActionsModelT> actions_model =
      UnPackActionsModel(actions_model_string.c_str());

  // Allow a larger conversation context.
  actions_model->max_conversation_history_length = 10;

  flatbuffers::FlatBufferBuilder builder;
  FinishActionsModelBuffer(builder,
                           ActionsModel::Pack(builder, actions_model.get()));
  std::unique_ptr<ActionsSuggestions> actions_suggestions =
      ActionsSuggestions::FromUnownedBuffer(
          reinterpret_cast<const uint8_t*>(builder.GetBufferPointer()),
          builder.GetSize(), &unilib_);
  AnnotatedSpan annotation;
  annotation.span = {11, 15};
  annotation.classification = {
      ClassificationResult(Collections::Address(), 1.0)};
  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/ActionsSuggestions::kLocalUserId, "hi, how are you?",
             /*reference_time_ms_utc=*/10000,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/{}, /*locales=*/"en"},
            {/*user_id=*/1, "good! are you at home?",
             /*reference_time_ms_utc=*/15000,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/{annotation},
             /*locales=*/"en"}}});
  ASSERT_GE(response.actions.size(), 1);
  EXPECT_EQ(response.actions[0].type, "view_map");
  EXPECT_EQ(response.actions[0].score, 1.0);
}

TEST_F(ActionsSuggestionsTest, CreateActionsFromClassificationResult) {
  std::unique_ptr<ActionsSuggestions> actions_suggestions = LoadTestModel();
  AnnotatedSpan annotation;
  annotation.span = {8, 12};
  annotation.classification = {
      ClassificationResult(Collections::Flight(), 1.0)};

  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1, "I'm on LX38?",
             /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/{annotation},
             /*locales=*/"en"}}});

  ASSERT_GE(response.actions.size(), 2);
  EXPECT_EQ(response.actions[0].type, "track_flight");
  EXPECT_EQ(response.actions[0].score, 1.0);
  EXPECT_EQ(response.actions[0].annotations.size(), 1);
  EXPECT_EQ(response.actions[0].annotations[0].span.message_index, 0);
  EXPECT_EQ(response.actions[0].annotations[0].span.span, annotation.span);
}

#ifdef TC3_UNILIB_ICU
TEST_F(ActionsSuggestionsTest, CreateActionsFromRules) {
  const std::string actions_model_string =
      ReadFile(GetModelPath() + kModelFileName);
  std::unique_ptr<ActionsModelT> actions_model =
      UnPackActionsModel(actions_model_string.c_str());
  ASSERT_TRUE(DecompressActionsModel(actions_model.get()));

  actions_model->rules.reset(new RulesModelT());
  actions_model->rules->rule.emplace_back(new RulesModel_::RuleT);
  RulesModel_::RuleT* rule = actions_model->rules->rule.back().get();
  rule->pattern = "^(?i:hello\\s(there))$";
  rule->actions.emplace_back(new RulesModel_::Rule_::RuleActionSpecT);
  rule->actions.back()->action.reset(new ActionSuggestionSpecT);
  ActionSuggestionSpecT* action = rule->actions.back()->action.get();
  action->type = "text_reply";
  action->response_text = "General Kenobi!";
  action->score = 1.0f;
  action->priority_score = 1.0f;

  // Set capturing groups for entity data.
  rule->actions.back()->capturing_group.emplace_back(
      new RulesModel_::Rule_::RuleActionSpec_::RuleCapturingGroupT);
  RulesModel_::Rule_::RuleActionSpec_::RuleCapturingGroupT* greeting_group =
      rule->actions.back()->capturing_group.back().get();
  greeting_group->group_id = 0;
  greeting_group->entity_field.reset(new FlatbufferFieldPathT);
  greeting_group->entity_field->field.emplace_back(new FlatbufferFieldT);
  greeting_group->entity_field->field.back()->field_name = "greeting";
  rule->actions.back()->capturing_group.emplace_back(
      new RulesModel_::Rule_::RuleActionSpec_::RuleCapturingGroupT);
  RulesModel_::Rule_::RuleActionSpec_::RuleCapturingGroupT* location_group =
      rule->actions.back()->capturing_group.back().get();
  location_group->group_id = 1;
  location_group->entity_field.reset(new FlatbufferFieldPathT);
  location_group->entity_field->field.emplace_back(new FlatbufferFieldT);
  location_group->entity_field->field.back()->field_name = "location";

  // Set test entity data schema.
  SetTestEntityDataSchema(actions_model.get());

  // Use meta data to generate custom serialized entity data.
  ReflectiveFlatbufferBuilder entity_data_builder(
      flatbuffers::GetRoot<reflection::Schema>(
          actions_model->actions_entity_data_schema.data()));
  std::unique_ptr<ReflectiveFlatbuffer> entity_data =
      entity_data_builder.NewRoot();
  entity_data->Set("person", "Kenobi");
  action->serialized_entity_data = entity_data->Serialize();

  flatbuffers::FlatBufferBuilder builder;
  FinishActionsModelBuffer(builder,
                           ActionsModel::Pack(builder, actions_model.get()));
  std::unique_ptr<ActionsSuggestions> actions_suggestions =
      ActionsSuggestions::FromUnownedBuffer(
          reinterpret_cast<const uint8_t*>(builder.GetBufferPointer()),
          builder.GetSize(), &unilib_);

  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1, "hello there", /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/{}, /*locales=*/"en"}}});
  EXPECT_GE(response.actions.size(), 1);
  EXPECT_EQ(response.actions[0].response_text, "General Kenobi!");

  // Check entity data.
  const flatbuffers::Table* entity =
      flatbuffers::GetAnyRoot(reinterpret_cast<const unsigned char*>(
          response.actions[0].serialized_entity_data.data()));
  EXPECT_EQ(entity->GetPointer<const flatbuffers::String*>(/*field=*/4)->str(),
            "hello there");
  EXPECT_EQ(entity->GetPointer<const flatbuffers::String*>(/*field=*/6)->str(),
            "there");
  EXPECT_EQ(entity->GetPointer<const flatbuffers::String*>(/*field=*/8)->str(),
            "Kenobi");
}

TEST_F(ActionsSuggestionsTest, CreatesTextRepliesFromRules) {
  const std::string actions_model_string =
      ReadFile(GetModelPath() + kModelFileName);
  std::unique_ptr<ActionsModelT> actions_model =
      UnPackActionsModel(actions_model_string.c_str());
  ASSERT_TRUE(DecompressActionsModel(actions_model.get()));

  actions_model->rules.reset(new RulesModelT());
  actions_model->rules->rule.emplace_back(new RulesModel_::RuleT);
  RulesModel_::RuleT* rule = actions_model->rules->rule.back().get();
  rule->pattern = "(?i:reply (stop|quit|end) (?:to|for) )";
  rule->actions.emplace_back(new RulesModel_::Rule_::RuleActionSpecT);

  // Set capturing groups for entity data.
  rule->actions.back()->capturing_group.emplace_back(
      new RulesModel_::Rule_::RuleActionSpec_::RuleCapturingGroupT);
  RulesModel_::Rule_::RuleActionSpec_::RuleCapturingGroupT* code_group =
      rule->actions.back()->capturing_group.back().get();
  code_group->group_id = 1;
  code_group->text_reply.reset(new ActionSuggestionSpecT);
  code_group->text_reply->score = 1.0f;
  code_group->text_reply->priority_score = 1.0f;

  flatbuffers::FlatBufferBuilder builder;
  FinishActionsModelBuffer(builder,
                           ActionsModel::Pack(builder, actions_model.get()));
  std::unique_ptr<ActionsSuggestions> actions_suggestions =
      ActionsSuggestions::FromUnownedBuffer(
          reinterpret_cast<const uint8_t*>(builder.GetBufferPointer()),
          builder.GetSize(), &unilib_);

  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1,
             "visit test.com or reply STOP to cancel your subscription",
             /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/{}, /*locales=*/"en"}}});
  EXPECT_GE(response.actions.size(), 1);
  EXPECT_EQ(response.actions[0].response_text, "STOP");
}

TEST_F(ActionsSuggestionsTest, DeduplicateActions) {
  std::unique_ptr<ActionsSuggestions> actions_suggestions = LoadTestModel();
  ActionsSuggestionsResponse response = actions_suggestions->SuggestActions(
      {{{/*user_id=*/1, "Where are you?", /*reference_time_ms_utc=*/0,
         /*reference_timezone=*/"Europe/Zurich",
         /*annotations=*/{}, /*locales=*/"en"}}});

  // Check that the location sharing model triggered.
  bool has_location_sharing_action = false;
  for (const ActionSuggestion action : response.actions) {
    if (action.type == ActionsSuggestions::kShareLocation) {
      has_location_sharing_action = true;
      break;
    }
  }
  EXPECT_TRUE(has_location_sharing_action);
  const int num_actions = response.actions.size();

  // Add custom rule for location sharing.
  const std::string actions_model_string =
      ReadFile(GetModelPath() + kModelFileName);
  std::unique_ptr<ActionsModelT> actions_model =
      UnPackActionsModel(actions_model_string.c_str());
  ASSERT_TRUE(DecompressActionsModel(actions_model.get()));

  actions_model->rules.reset(new RulesModelT());
  actions_model->rules->rule.emplace_back(new RulesModel_::RuleT);
  actions_model->rules->rule.back()->pattern = "^(?i:where are you[.?]?)$";
  actions_model->rules->rule.back()->actions.emplace_back(
      new RulesModel_::Rule_::RuleActionSpecT);
  actions_model->rules->rule.back()->actions.back()->action.reset(
      new ActionSuggestionSpecT);
  ActionSuggestionSpecT* action =
      actions_model->rules->rule.back()->actions.back()->action.get();
  action->score = 1.0f;
  action->type = ActionsSuggestions::kShareLocation;

  flatbuffers::FlatBufferBuilder builder;
  FinishActionsModelBuffer(builder,
                           ActionsModel::Pack(builder, actions_model.get()));
  actions_suggestions = ActionsSuggestions::FromUnownedBuffer(
      reinterpret_cast<const uint8_t*>(builder.GetBufferPointer()),
      builder.GetSize(), &unilib_);

  response = actions_suggestions->SuggestActions(
      {{{/*user_id=*/1, "Where are you?", /*reference_time_ms_utc=*/0,
         /*reference_timezone=*/"Europe/Zurich",
         /*annotations=*/{}, /*locales=*/"en"}}});
  EXPECT_EQ(response.actions.size(), num_actions);
}

TEST_F(ActionsSuggestionsTest, DeduplicateConflictingActions) {
  std::unique_ptr<ActionsSuggestions> actions_suggestions = LoadTestModel();
  AnnotatedSpan annotation;
  annotation.span = {7, 11};
  annotation.classification = {
      ClassificationResult(Collections::Flight(), 1.0)};
  ActionsSuggestionsResponse response = actions_suggestions->SuggestActions(
      {{{/*user_id=*/1, "I'm on LX38",
         /*reference_time_ms_utc=*/0,
         /*reference_timezone=*/"Europe/Zurich",
         /*annotations=*/{annotation},
         /*locales=*/"en"}}});

  // Check that the phone actions are present.
  EXPECT_GE(response.actions.size(), 1);
  EXPECT_EQ(response.actions[0].type, "track_flight");

  // Add custom rule.
  const std::string actions_model_string =
      ReadFile(GetModelPath() + kModelFileName);
  std::unique_ptr<ActionsModelT> actions_model =
      UnPackActionsModel(actions_model_string.c_str());
  ASSERT_TRUE(DecompressActionsModel(actions_model.get()));

  actions_model->rules.reset(new RulesModelT());
  actions_model->rules->rule.emplace_back(new RulesModel_::RuleT);
  RulesModel_::RuleT* rule = actions_model->rules->rule.back().get();
  rule->pattern = "^(?i:I'm on ([a-z0-9]+))$";
  rule->actions.emplace_back(new RulesModel_::Rule_::RuleActionSpecT);
  rule->actions.back()->action.reset(new ActionSuggestionSpecT);
  ActionSuggestionSpecT* action = rule->actions.back()->action.get();
  action->score = 1.0f;
  action->priority_score = 2.0f;
  action->type = "test_code";
  rule->actions.back()->capturing_group.emplace_back(
      new RulesModel_::Rule_::RuleActionSpec_::RuleCapturingGroupT);
  RulesModel_::Rule_::RuleActionSpec_::RuleCapturingGroupT* code_group =
      rule->actions.back()->capturing_group.back().get();
  code_group->group_id = 1;
  code_group->annotation_name = "code";
  code_group->annotation_type = "code";

  flatbuffers::FlatBufferBuilder builder;
  FinishActionsModelBuffer(builder,
                           ActionsModel::Pack(builder, actions_model.get()));
  actions_suggestions = ActionsSuggestions::FromUnownedBuffer(
      reinterpret_cast<const uint8_t*>(builder.GetBufferPointer()),
      builder.GetSize(), &unilib_);

  response = actions_suggestions->SuggestActions(
      {{{/*user_id=*/1, "I'm on LX38",
         /*reference_time_ms_utc=*/0,
         /*reference_timezone=*/"Europe/Zurich",
         /*annotations=*/{annotation},
         /*locales=*/"en"}}});
  EXPECT_GE(response.actions.size(), 1);
  EXPECT_EQ(response.actions[0].type, "test_code");
}
#endif

TEST_F(ActionsSuggestionsTest, SuggestActionsRanking) {
  std::unique_ptr<ActionsSuggestions> actions_suggestions = LoadTestModel();
  std::vector<AnnotatedSpan> annotations(2);
  annotations[0].span = {11, 15};
  annotations[0].classification = {ClassificationResult("address", 1.0)};
  annotations[1].span = {19, 23};
  annotations[1].classification = {ClassificationResult("address", 2.0)};
  const ActionsSuggestionsResponse& response =
      actions_suggestions->SuggestActions(
          {{{/*user_id=*/1, "are you at home or work?",
             /*reference_time_ms_utc=*/0,
             /*reference_timezone=*/"Europe/Zurich",
             /*annotations=*/annotations,
             /*locales=*/"en"}}});
  EXPECT_GE(response.actions.size(), 2);
  EXPECT_EQ(response.actions[0].type, "view_map");
  EXPECT_EQ(response.actions[0].score, 2.0);
  EXPECT_EQ(response.actions[1].type, "view_map");
  EXPECT_EQ(response.actions[1].score, 1.0);
}

TEST_F(ActionsSuggestionsTest, VisitActionsModel) {
  EXPECT_TRUE(VisitActionsModel<bool>(GetModelPath() + kModelFileName,
                                      [](const ActionsModel* model) {
                                        if (model == nullptr) {
                                          return false;
                                        }
                                        return true;
                                      }));
  EXPECT_FALSE(VisitActionsModel<bool>(GetModelPath() + "non_existing_model.fb",
                                       [](const ActionsModel* model) {
                                         if (model == nullptr) {
                                           return false;
                                         }
                                         return true;
                                       }));
}

TEST_F(ActionsSuggestionsTest, SuggestActionsWithHashGramModel) {
  std::unique_ptr<ActionsSuggestions> actions_suggestions =
      LoadHashGramTestModel();
  ASSERT_TRUE(actions_suggestions != nullptr);
  {
    const ActionsSuggestionsResponse& response =
        actions_suggestions->SuggestActions(
            {{{/*user_id=*/1, "hello",
               /*reference_time_ms_utc=*/0,
               /*reference_timezone=*/"Europe/Zurich",
               /*annotations=*/{},
               /*locales=*/"en"}}});
    EXPECT_THAT(response.actions, testing::IsEmpty());
  }
  {
    const ActionsSuggestionsResponse& response =
        actions_suggestions->SuggestActions(
            {{{/*user_id=*/1, "where are you",
               /*reference_time_ms_utc=*/0,
               /*reference_timezone=*/"Europe/Zurich",
               /*annotations=*/{},
               /*locales=*/"en"}}});
    EXPECT_THAT(
        response.actions,
        ElementsAre(testing::Field(&ActionSuggestion::type, "share_location")));
  }
  {
    const ActionsSuggestionsResponse& response =
        actions_suggestions->SuggestActions(
            {{{/*user_id=*/1, "do you know johns number",
               /*reference_time_ms_utc=*/0,
               /*reference_timezone=*/"Europe/Zurich",
               /*annotations=*/{},
               /*locales=*/"en"}}});
    EXPECT_THAT(
        response.actions,
        ElementsAre(testing::Field(&ActionSuggestion::type, "share_contact")));
  }
}

// Test class to expose token embedding methods for testing.
class TestingMessageEmbedder : private ActionsSuggestions {
 public:
  explicit TestingMessageEmbedder(const ActionsModel* model);

  using ActionsSuggestions::EmbedAndFlattenTokens;
  using ActionsSuggestions::EmbedTokensPerMessage;

 protected:
  // EmbeddingExecutor that always returns features based on
  // the id of the sparse features.
  class FakeEmbeddingExecutor : public EmbeddingExecutor {
   public:
    bool AddEmbedding(const TensorView<int>& sparse_features, float* dest,
                      const int dest_size) const override {
      TC3_CHECK_GE(dest_size, 1);
      EXPECT_EQ(sparse_features.size(), 1);
      dest[0] = sparse_features.data()[0];
      return true;
    }
  };
};

TestingMessageEmbedder::TestingMessageEmbedder(const ActionsModel* model) {
  model_ = model;
  const ActionsTokenFeatureProcessorOptions* options =
      model->feature_processor_options();
  feature_processor_.reset(
      new ActionsFeatureProcessor(options, /*unilib=*/nullptr));
  embedding_executor_.reset(new FakeEmbeddingExecutor());
  EXPECT_TRUE(
      EmbedTokenId(options->padding_token_id(), &embedded_padding_token_));
  EXPECT_TRUE(EmbedTokenId(options->start_token_id(), &embedded_start_token_));
  EXPECT_TRUE(EmbedTokenId(options->end_token_id(), &embedded_end_token_));
  token_embedding_size_ = feature_processor_->GetTokenEmbeddingSize();
  EXPECT_EQ(token_embedding_size_, 1);
}

class EmbeddingTest : public testing::Test {
 protected:
  EmbeddingTest() {
    model_.feature_processor_options.reset(
        new ActionsTokenFeatureProcessorOptionsT);
    options_ = model_.feature_processor_options.get();
    options_->chargram_orders = {1};
    options_->num_buckets = 1000;
    options_->embedding_size = 1;
    options_->start_token_id = 0;
    options_->end_token_id = 1;
    options_->padding_token_id = 2;
    options_->tokenizer_options.reset(new ActionsTokenizerOptionsT);
  }

  TestingMessageEmbedder CreateTestingMessageEmbedder() {
    flatbuffers::FlatBufferBuilder builder;
    FinishActionsModelBuffer(builder, ActionsModel::Pack(builder, &model_));
    buffer_ = builder.ReleaseBufferPointer();
    return TestingMessageEmbedder(
        flatbuffers::GetRoot<ActionsModel>(buffer_.data()));
  }

  flatbuffers::DetachedBuffer buffer_;
  ActionsModelT model_;
  ActionsTokenFeatureProcessorOptionsT* options_;
};

TEST_F(EmbeddingTest, EmbedsTokensPerMessageWithNoBounds) {
  const TestingMessageEmbedder embedder = CreateTestingMessageEmbedder();
  std::vector<std::vector<Token>> tokens = {
      {Token("a", 0, 1), Token("b", 2, 3), Token("c", 4, 5)}};
  std::vector<float> embeddings;
  int max_num_tokens_per_message = 0;

  EXPECT_TRUE(embedder.EmbedTokensPerMessage(tokens, &embeddings,
                                             &max_num_tokens_per_message));

  EXPECT_EQ(max_num_tokens_per_message, 3);
  EXPECT_EQ(embeddings.size(), 3);
  EXPECT_THAT(embeddings[0],
              testing::FloatEq(tc3farmhash::Fingerprint64("a", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[1],
              testing::FloatEq(tc3farmhash::Fingerprint64("b", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[2],
              testing::FloatEq(tc3farmhash::Fingerprint64("c", 1) %
                               options_->num_buckets));
}

TEST_F(EmbeddingTest, EmbedsTokensPerMessageWithPadding) {
  options_->min_num_tokens_per_message = 5;
  const TestingMessageEmbedder embedder = CreateTestingMessageEmbedder();
  std::vector<std::vector<Token>> tokens = {
      {Token("a", 0, 1), Token("b", 2, 3), Token("c", 4, 5)}};
  std::vector<float> embeddings;
  int max_num_tokens_per_message = 0;

  EXPECT_TRUE(embedder.EmbedTokensPerMessage(tokens, &embeddings,
                                             &max_num_tokens_per_message));

  EXPECT_EQ(max_num_tokens_per_message, 5);
  EXPECT_EQ(embeddings.size(), 5);
  EXPECT_THAT(embeddings[0],
              testing::FloatEq(tc3farmhash::Fingerprint64("a", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[1],
              testing::FloatEq(tc3farmhash::Fingerprint64("b", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[2],
              testing::FloatEq(tc3farmhash::Fingerprint64("c", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[3], testing::FloatEq(options_->padding_token_id));
  EXPECT_THAT(embeddings[4], testing::FloatEq(options_->padding_token_id));
}

TEST_F(EmbeddingTest, EmbedsTokensPerMessageDropsAtBeginning) {
  options_->max_num_tokens_per_message = 2;
  const TestingMessageEmbedder embedder = CreateTestingMessageEmbedder();
  std::vector<std::vector<Token>> tokens = {
      {Token("a", 0, 1), Token("b", 2, 3), Token("c", 4, 5)}};
  std::vector<float> embeddings;
  int max_num_tokens_per_message = 0;

  EXPECT_TRUE(embedder.EmbedTokensPerMessage(tokens, &embeddings,
                                             &max_num_tokens_per_message));

  EXPECT_EQ(max_num_tokens_per_message, 2);
  EXPECT_EQ(embeddings.size(), 2);
  EXPECT_THAT(embeddings[0],
              testing::FloatEq(tc3farmhash::Fingerprint64("b", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[1],
              testing::FloatEq(tc3farmhash::Fingerprint64("c", 1) %
                               options_->num_buckets));
}

TEST_F(EmbeddingTest, EmbedsTokensPerMessageWithMultipleMessagesNoBounds) {
  const TestingMessageEmbedder embedder = CreateTestingMessageEmbedder();
  std::vector<std::vector<Token>> tokens = {
      {Token("a", 0, 1), Token("b", 2, 3), Token("c", 4, 5)},
      {Token("d", 0, 1), Token("e", 2, 3)}};
  std::vector<float> embeddings;
  int max_num_tokens_per_message = 0;

  EXPECT_TRUE(embedder.EmbedTokensPerMessage(tokens, &embeddings,
                                             &max_num_tokens_per_message));

  EXPECT_EQ(max_num_tokens_per_message, 3);
  EXPECT_THAT(embeddings[0],
              testing::FloatEq(tc3farmhash::Fingerprint64("a", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[1],
              testing::FloatEq(tc3farmhash::Fingerprint64("b", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[2],
              testing::FloatEq(tc3farmhash::Fingerprint64("c", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[3],
              testing::FloatEq(tc3farmhash::Fingerprint64("d", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[4],
              testing::FloatEq(tc3farmhash::Fingerprint64("e", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[5], testing::FloatEq(options_->padding_token_id));
}

TEST_F(EmbeddingTest, EmbedsFlattenedTokensWithNoBounds) {
  const TestingMessageEmbedder embedder = CreateTestingMessageEmbedder();
  std::vector<std::vector<Token>> tokens = {
      {Token("a", 0, 1), Token("b", 2, 3), Token("c", 4, 5)}};
  std::vector<float> embeddings;
  int total_token_count = 0;

  EXPECT_TRUE(
      embedder.EmbedAndFlattenTokens(tokens, &embeddings, &total_token_count));

  EXPECT_EQ(total_token_count, 5);
  EXPECT_EQ(embeddings.size(), 5);
  EXPECT_THAT(embeddings[0], testing::FloatEq(options_->start_token_id));
  EXPECT_THAT(embeddings[1],
              testing::FloatEq(tc3farmhash::Fingerprint64("a", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[2],
              testing::FloatEq(tc3farmhash::Fingerprint64("b", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[3],
              testing::FloatEq(tc3farmhash::Fingerprint64("c", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[4], testing::FloatEq(options_->end_token_id));
}

TEST_F(EmbeddingTest, EmbedsFlattenedTokensWithPadding) {
  options_->min_num_total_tokens = 7;
  const TestingMessageEmbedder embedder = CreateTestingMessageEmbedder();
  std::vector<std::vector<Token>> tokens = {
      {Token("a", 0, 1), Token("b", 2, 3), Token("c", 4, 5)}};
  std::vector<float> embeddings;
  int total_token_count = 0;

  EXPECT_TRUE(
      embedder.EmbedAndFlattenTokens(tokens, &embeddings, &total_token_count));

  EXPECT_EQ(total_token_count, 7);
  EXPECT_EQ(embeddings.size(), 7);
  EXPECT_THAT(embeddings[0], testing::FloatEq(options_->start_token_id));
  EXPECT_THAT(embeddings[1],
              testing::FloatEq(tc3farmhash::Fingerprint64("a", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[2],
              testing::FloatEq(tc3farmhash::Fingerprint64("b", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[3],
              testing::FloatEq(tc3farmhash::Fingerprint64("c", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[4], testing::FloatEq(options_->end_token_id));
  EXPECT_THAT(embeddings[5], testing::FloatEq(options_->padding_token_id));
  EXPECT_THAT(embeddings[6], testing::FloatEq(options_->padding_token_id));
}

TEST_F(EmbeddingTest, EmbedsFlattenedTokensDropsAtBeginning) {
  options_->max_num_total_tokens = 3;
  const TestingMessageEmbedder embedder = CreateTestingMessageEmbedder();
  std::vector<std::vector<Token>> tokens = {
      {Token("a", 0, 1), Token("b", 2, 3), Token("c", 4, 5)}};
  std::vector<float> embeddings;
  int total_token_count = 0;

  EXPECT_TRUE(
      embedder.EmbedAndFlattenTokens(tokens, &embeddings, &total_token_count));

  EXPECT_EQ(total_token_count, 3);
  EXPECT_EQ(embeddings.size(), 3);
  EXPECT_THAT(embeddings[0],
              testing::FloatEq(tc3farmhash::Fingerprint64("b", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[1],
              testing::FloatEq(tc3farmhash::Fingerprint64("c", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[2], testing::FloatEq(options_->end_token_id));
}

TEST_F(EmbeddingTest, EmbedsFlattenedTokensWithMultipleMessagesNoBounds) {
  const TestingMessageEmbedder embedder = CreateTestingMessageEmbedder();
  std::vector<std::vector<Token>> tokens = {
      {Token("a", 0, 1), Token("b", 2, 3), Token("c", 4, 5)},
      {Token("d", 0, 1), Token("e", 2, 3)}};
  std::vector<float> embeddings;
  int total_token_count = 0;

  EXPECT_TRUE(
      embedder.EmbedAndFlattenTokens(tokens, &embeddings, &total_token_count));

  EXPECT_EQ(total_token_count, 9);
  EXPECT_EQ(embeddings.size(), 9);
  EXPECT_THAT(embeddings[0], testing::FloatEq(options_->start_token_id));
  EXPECT_THAT(embeddings[1],
              testing::FloatEq(tc3farmhash::Fingerprint64("a", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[2],
              testing::FloatEq(tc3farmhash::Fingerprint64("b", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[3],
              testing::FloatEq(tc3farmhash::Fingerprint64("c", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[4], testing::FloatEq(options_->end_token_id));
  EXPECT_THAT(embeddings[5], testing::FloatEq(options_->start_token_id));
  EXPECT_THAT(embeddings[6],
              testing::FloatEq(tc3farmhash::Fingerprint64("d", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[7],
              testing::FloatEq(tc3farmhash::Fingerprint64("e", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[8], testing::FloatEq(options_->end_token_id));
}

TEST_F(EmbeddingTest,
       EmbedsFlattenedTokensWithMultipleMessagesDropsAtBeginning) {
  options_->max_num_total_tokens = 7;
  const TestingMessageEmbedder embedder = CreateTestingMessageEmbedder();
  std::vector<std::vector<Token>> tokens = {
      {Token("a", 0, 1), Token("b", 2, 3), Token("c", 4, 5)},
      {Token("d", 0, 1), Token("e", 2, 3), Token("f", 4, 5)}};
  std::vector<float> embeddings;
  int total_token_count = 0;

  EXPECT_TRUE(
      embedder.EmbedAndFlattenTokens(tokens, &embeddings, &total_token_count));

  EXPECT_EQ(total_token_count, 7);
  EXPECT_EQ(embeddings.size(), 7);
  EXPECT_THAT(embeddings[0],
              testing::FloatEq(tc3farmhash::Fingerprint64("c", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[1], testing::FloatEq(options_->end_token_id));
  EXPECT_THAT(embeddings[2], testing::FloatEq(options_->start_token_id));
  EXPECT_THAT(embeddings[3],
              testing::FloatEq(tc3farmhash::Fingerprint64("d", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[4],
              testing::FloatEq(tc3farmhash::Fingerprint64("e", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[5],
              testing::FloatEq(tc3farmhash::Fingerprint64("f", 1) %
                               options_->num_buckets));
  EXPECT_THAT(embeddings[6], testing::FloatEq(options_->end_token_id));
}

}  // namespace
}  // namespace libtextclassifier3
