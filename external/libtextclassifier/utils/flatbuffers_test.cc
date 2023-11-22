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

#include <fstream>
#include <map>
#include <memory>
#include <string>

#include "utils/flatbuffers.h"
#include "utils/flatbuffers_generated.h"
#include "utils/flatbuffers_test_generated.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "flatbuffers/flatbuffers.h"
#include "flatbuffers/reflection.h"
#include "flatbuffers/reflection_generated.h"

namespace libtextclassifier3 {
namespace {

std::string GetTestMetadataPath() {
  return "flatbuffers_test.bfbs";
}

std::string LoadTestMetadata() {
  std::ifstream test_config_stream(GetTestMetadataPath());
  return std::string((std::istreambuf_iterator<char>(test_config_stream)),
                     (std::istreambuf_iterator<char>()));
}

TEST(FlatbuffersTest, PrimitiveFieldsAreCorrectlySet) {
  std::string metadata_buffer = LoadTestMetadata();
  ReflectiveFlatbufferBuilder reflective_builder(
      flatbuffers::GetRoot<reflection::Schema>(metadata_buffer.data()));

  std::unique_ptr<ReflectiveFlatbuffer> buffer = reflective_builder.NewRoot();
  EXPECT_TRUE(buffer != nullptr);
  EXPECT_TRUE(buffer->Set("an_int_field", 42));
  EXPECT_TRUE(buffer->Set("a_long_field", 84ll));
  EXPECT_TRUE(buffer->Set("a_bool_field", true));
  EXPECT_TRUE(buffer->Set("a_float_field", 1.f));
  EXPECT_TRUE(buffer->Set("a_double_field", 1.0));

  // Try to parse with the generated code.
  std::string serialized_entity_data = buffer->Serialize();
  std::unique_ptr<test::EntityDataT> entity_data =
      LoadAndVerifyMutableFlatbuffer<test::EntityData>(
          serialized_entity_data.data(), serialized_entity_data.size());
  EXPECT_TRUE(entity_data != nullptr);
  EXPECT_EQ(entity_data->an_int_field, 42);
  EXPECT_EQ(entity_data->a_long_field, 84);
  EXPECT_EQ(entity_data->a_bool_field, true);
  EXPECT_NEAR(entity_data->a_float_field, 1.f, 1e-4);
  EXPECT_NEAR(entity_data->a_double_field, 1.f, 1e-4);
}

TEST(FlatbuffersTest, HandlesUnknownFields) {
  std::string metadata_buffer = LoadTestMetadata();
  const reflection::Schema* schema =
      flatbuffers::GetRoot<reflection::Schema>(metadata_buffer.data());
  ReflectiveFlatbufferBuilder reflective_builder(schema);

  std::unique_ptr<ReflectiveFlatbuffer> buffer = reflective_builder.NewRoot();
  EXPECT_TRUE(buffer != nullptr);

  // Add a field that is not known to the (statically generated) code.
  EXPECT_TRUE(buffer->Set("mystic", "this is an unknown field."));

  flatbuffers::FlatBufferBuilder builder;
  builder.Finish(flatbuffers::Offset<void>(buffer->Serialize(&builder)));

  // Try to read the field again.
  const flatbuffers::Table* extra =
      flatbuffers::GetAnyRoot(builder.GetBufferPointer());
  EXPECT_EQ(extra
                ->GetPointer<const flatbuffers::String*>(
                    buffer->GetFieldOrNull("mystic")->offset())
                ->str(),
            "this is an unknown field.");
}

TEST(FlatbuffersTest, HandlesNestedFields) {
  std::string metadata_buffer = LoadTestMetadata();
  const reflection::Schema* schema =
      flatbuffers::GetRoot<reflection::Schema>(metadata_buffer.data());
  ReflectiveFlatbufferBuilder reflective_builder(schema);

  FlatbufferFieldPathT path;
  path.field.emplace_back(new FlatbufferFieldT);
  path.field.back()->field_name = "flight_number";
  path.field.emplace_back(new FlatbufferFieldT);
  path.field.back()->field_name = "carrier_code";
  flatbuffers::FlatBufferBuilder path_builder;
  path_builder.Finish(FlatbufferFieldPath::Pack(path_builder, &path));

  std::unique_ptr<ReflectiveFlatbuffer> buffer = reflective_builder.NewRoot();

  ReflectiveFlatbuffer* parent = nullptr;
  reflection::Field const* field = nullptr;
  EXPECT_TRUE(
      buffer->GetFieldWithParent(flatbuffers::GetRoot<FlatbufferFieldPath>(
                                     path_builder.GetBufferPointer()),
                                 &parent, &field));
  EXPECT_EQ(parent, buffer->Mutable("flight_number"));
  EXPECT_EQ(field,
            buffer->Mutable("flight_number")->GetFieldOrNull("carrier_code"));
}

TEST(FlatbuffersTest, HandlesMultipleNestedFields) {
  std::string metadata_buffer = LoadTestMetadata();
  ReflectiveFlatbufferBuilder reflective_builder(
      flatbuffers::GetRoot<reflection::Schema>(metadata_buffer.data()));

  std::unique_ptr<ReflectiveFlatbuffer> buffer = reflective_builder.NewRoot();
  ReflectiveFlatbuffer* flight_info = buffer->Mutable("flight_number");
  flight_info->Set("carrier_code", "LX");
  flight_info->Set("flight_code", 38);

  ReflectiveFlatbuffer* contact_info = buffer->Mutable("contact_info");
  EXPECT_TRUE(contact_info->Set("first_name", "Barack"));
  EXPECT_TRUE(contact_info->Set("last_name", "Obama"));
  EXPECT_TRUE(contact_info->Set("phone_number", "1-800-TEST"));
  EXPECT_TRUE(contact_info->Set("score", 1.f));

  // Try to parse with the generated code.
  std::string serialized_entity_data = buffer->Serialize();
  std::unique_ptr<test::EntityDataT> entity_data =
      LoadAndVerifyMutableFlatbuffer<test::EntityData>(
          serialized_entity_data.data(), serialized_entity_data.size());
  EXPECT_TRUE(entity_data != nullptr);
  EXPECT_EQ(entity_data->flight_number->carrier_code, "LX");
  EXPECT_EQ(entity_data->flight_number->flight_code, 38);
  EXPECT_EQ(entity_data->contact_info->first_name, "Barack");
  EXPECT_EQ(entity_data->contact_info->last_name, "Obama");
  EXPECT_EQ(entity_data->contact_info->phone_number, "1-800-TEST");
  EXPECT_NEAR(entity_data->contact_info->score, 1.f, 1e-4);
}

TEST(FlatbuffersTest, HandlesFieldsSetWithNamePath) {
  std::string metadata_buffer = LoadTestMetadata();
  ReflectiveFlatbufferBuilder reflective_builder(
      flatbuffers::GetRoot<reflection::Schema>(metadata_buffer.data()));

  FlatbufferFieldPathT path;
  path.field.emplace_back(new FlatbufferFieldT);
  path.field.back()->field_name = "flight_number";
  path.field.emplace_back(new FlatbufferFieldT);
  path.field.back()->field_name = "carrier_code";
  flatbuffers::FlatBufferBuilder path_builder;
  path_builder.Finish(FlatbufferFieldPath::Pack(path_builder, &path));

  std::unique_ptr<ReflectiveFlatbuffer> buffer = reflective_builder.NewRoot();
  // Test setting value using Set function.
  buffer->Mutable("flight_number")->Set("flight_code", 38);
  // Test setting value using FlatbufferFieldPath.
  buffer->Set(flatbuffers::GetRoot<FlatbufferFieldPath>(
                  path_builder.GetBufferPointer()),
              "LX");

  // Try to parse with the generated code.
  std::string serialized_entity_data = buffer->Serialize();
  std::unique_ptr<test::EntityDataT> entity_data =
      LoadAndVerifyMutableFlatbuffer<test::EntityData>(
          serialized_entity_data.data(), serialized_entity_data.size());
  EXPECT_TRUE(entity_data != nullptr);
  EXPECT_EQ(entity_data->flight_number->carrier_code, "LX");
  EXPECT_EQ(entity_data->flight_number->flight_code, 38);
}

TEST(FlatbuffersTest, HandlesFieldsSetWithOffsetPath) {
  std::string metadata_buffer = LoadTestMetadata();
  ReflectiveFlatbufferBuilder reflective_builder(
      flatbuffers::GetRoot<reflection::Schema>(metadata_buffer.data()));

  FlatbufferFieldPathT path;
  path.field.emplace_back(new FlatbufferFieldT);
  path.field.back()->field_offset = 14;
  path.field.emplace_back(new FlatbufferFieldT);
  path.field.back()->field_offset = 4;
  flatbuffers::FlatBufferBuilder path_builder;
  path_builder.Finish(FlatbufferFieldPath::Pack(path_builder, &path));

  std::unique_ptr<ReflectiveFlatbuffer> buffer = reflective_builder.NewRoot();
  // Test setting value using Set function.
  buffer->Mutable("flight_number")->Set("flight_code", 38);
  // Test setting value using FlatbufferFieldPath.
  buffer->Set(flatbuffers::GetRoot<FlatbufferFieldPath>(
                  path_builder.GetBufferPointer()),
              "LX");

  // Try to parse with the generated code.
  std::string serialized_entity_data = buffer->Serialize();
  std::unique_ptr<test::EntityDataT> entity_data =
      LoadAndVerifyMutableFlatbuffer<test::EntityData>(
          serialized_entity_data.data(), serialized_entity_data.size());
  EXPECT_TRUE(entity_data != nullptr);
  EXPECT_EQ(entity_data->flight_number->carrier_code, "LX");
  EXPECT_EQ(entity_data->flight_number->flight_code, 38);
}

TEST(FlatbuffersTest, PartialBuffersAreCorrectlyMerged) {
  std::string metadata_buffer = LoadTestMetadata();
  ReflectiveFlatbufferBuilder reflective_builder(
      flatbuffers::GetRoot<reflection::Schema>(metadata_buffer.data()));
  std::unique_ptr<ReflectiveFlatbuffer> buffer = reflective_builder.NewRoot();
  buffer->Set("an_int_field", 42);
  buffer->Set("a_long_field", 84ll);
  ReflectiveFlatbuffer* flight_info = buffer->Mutable("flight_number");
  flight_info->Set("carrier_code", "LX");
  flight_info->Set("flight_code", 38);

  // Create message to merge.
  test::EntityDataT additional_entity_data;
  additional_entity_data.an_int_field = 43;
  additional_entity_data.flight_number.reset(new test::FlightNumberInfoT);
  additional_entity_data.flight_number->flight_code = 39;
  additional_entity_data.contact_info.reset(new test::ContactInfoT);
  additional_entity_data.contact_info->first_name = "Barack";
  flatbuffers::FlatBufferBuilder to_merge_builder;
  to_merge_builder.Finish(
      test::EntityData::Pack(to_merge_builder, &additional_entity_data));

  // Merge it.
  EXPECT_TRUE(buffer->MergeFrom(
      flatbuffers::GetAnyRoot(to_merge_builder.GetBufferPointer())));

  // Try to parse it with the generated code.
  std::string serialized_entity_data = buffer->Serialize();
  std::unique_ptr<test::EntityDataT> entity_data =
      LoadAndVerifyMutableFlatbuffer<test::EntityData>(
          serialized_entity_data.data(), serialized_entity_data.size());
  EXPECT_TRUE(entity_data != nullptr);
  EXPECT_EQ(entity_data->an_int_field, 43);
  EXPECT_EQ(entity_data->a_long_field, 84);
  EXPECT_EQ(entity_data->flight_number->carrier_code, "LX");
  EXPECT_EQ(entity_data->flight_number->flight_code, 39);
  EXPECT_EQ(entity_data->contact_info->first_name, "Barack");
}

TEST(FlatbuffersTest, PrimitiveAndNestedFieldsAreCorrectlyFlattened) {
  std::string metadata_buffer = LoadTestMetadata();
  ReflectiveFlatbufferBuilder reflective_builder(
      flatbuffers::GetRoot<reflection::Schema>(metadata_buffer.data()));
  std::unique_ptr<ReflectiveFlatbuffer> buffer = reflective_builder.NewRoot();
  buffer->Set("an_int_field", 42);
  buffer->Set("a_long_field", 84ll);
  ReflectiveFlatbuffer* flight_info = buffer->Mutable("flight_number");
  flight_info->Set("carrier_code", "LX");
  flight_info->Set("flight_code", 38);

  std::map<std::string, Variant> entity_data_map = buffer->AsFlatMap();
  EXPECT_EQ(4, entity_data_map.size());
  EXPECT_EQ(42, entity_data_map["an_int_field"].IntValue());
  EXPECT_EQ(84, entity_data_map["a_long_field"].Int64Value());
  EXPECT_EQ("LX", entity_data_map["flight_number.carrier_code"].StringValue());
  EXPECT_EQ(38, entity_data_map["flight_number.flight_code"].IntValue());
}

TEST(FlatbuffersTest, RepeatedFieldSetThroughReflectionCanBeRead) {
  std::string metadata_buffer = LoadTestMetadata();
  const reflection::Schema* schema =
      flatbuffers::GetRoot<reflection::Schema>(metadata_buffer.data());
  ReflectiveFlatbufferBuilder reflective_builder(schema);
  std::unique_ptr<ReflectiveFlatbuffer> buffer = reflective_builder.NewRoot();

  auto reminders = buffer->Repeated<ReflectiveFlatbuffer>("reminders");
  {
    auto reminder = reminders->Add();
    reminder->Set("title", "test reminder");
    auto notes = reminder->Repeated<std::string>("notes");
    notes->Add("note A");
    notes->Add("note B");
  }
  {
    auto reminder = reminders->Add();
    reminder->Set("title", "test reminder 2");
    auto notes = reminder->Repeated<std::string>("notes");
    notes->Add("note i");
    notes->Add("note ii");
    notes->Add("note iii");
  }
  const std::string serialized_entity_data = buffer->Serialize();

  std::unique_ptr<test::EntityDataT> entity_data =
      LoadAndVerifyMutableFlatbuffer<test::EntityData>(
          serialized_entity_data.data(), serialized_entity_data.size());
  EXPECT_TRUE(entity_data != nullptr);
  EXPECT_EQ(2, entity_data->reminders.size());
  EXPECT_EQ("test reminder", entity_data->reminders[0]->title);
  EXPECT_THAT(entity_data->reminders[0]->notes,
              testing::ElementsAreArray({"note A", "note B"}));
  EXPECT_EQ("test reminder 2", entity_data->reminders[1]->title);
  EXPECT_THAT(entity_data->reminders[1]->notes,
              testing::ElementsAreArray({"note i", "note ii", "note iii"}));
}

}  // namespace
}  // namespace libtextclassifier3
