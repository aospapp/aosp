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

#include "utils/flatbuffers.h"

#include <vector>
#include "utils/strings/numbers.h"
#include "utils/variant.h"

namespace libtextclassifier3 {
namespace {
bool CreateRepeatedField(
    const reflection::Schema* schema, const reflection::Type* type,
    std::unique_ptr<ReflectiveFlatbuffer::RepeatedField>* repeated_field) {
  switch (type->element()) {
    case reflection::Bool:
      repeated_field->reset(new ReflectiveFlatbuffer::TypedRepeatedField<bool>);
      return true;
    case reflection::Int:
      repeated_field->reset(new ReflectiveFlatbuffer::TypedRepeatedField<int>);
      return true;
    case reflection::Long:
      repeated_field->reset(
          new ReflectiveFlatbuffer::TypedRepeatedField<int64>);
      return true;
    case reflection::Float:
      repeated_field->reset(
          new ReflectiveFlatbuffer::TypedRepeatedField<float>);
      return true;
    case reflection::Double:
      repeated_field->reset(
          new ReflectiveFlatbuffer::TypedRepeatedField<double>);
      return true;
    case reflection::String:
      repeated_field->reset(
          new ReflectiveFlatbuffer::TypedRepeatedField<std::string>);
      return true;
    case reflection::Obj:
      repeated_field->reset(
          new ReflectiveFlatbuffer::TypedRepeatedField<ReflectiveFlatbuffer>(
              schema, type));
      return true;
    default:
      TC3_LOG(ERROR) << "Unsupported type: " << type->element();
      return false;
  }
}
}  // namespace

template <>
const char* FlatbufferFileIdentifier<Model>() {
  return ModelIdentifier();
}

std::unique_ptr<ReflectiveFlatbuffer> ReflectiveFlatbufferBuilder::NewRoot()
    const {
  if (!schema_->root_table()) {
    TC3_LOG(ERROR) << "No root table specified.";
    return nullptr;
  }
  return std::unique_ptr<ReflectiveFlatbuffer>(
      new ReflectiveFlatbuffer(schema_, schema_->root_table()));
}

std::unique_ptr<ReflectiveFlatbuffer> ReflectiveFlatbufferBuilder::NewTable(
    StringPiece table_name) const {
  for (const reflection::Object* object : *schema_->objects()) {
    if (table_name.Equals(object->name()->str())) {
      return std::unique_ptr<ReflectiveFlatbuffer>(
          new ReflectiveFlatbuffer(schema_, object));
    }
  }
  return nullptr;
}

const reflection::Field* ReflectiveFlatbuffer::GetFieldOrNull(
    const StringPiece field_name) const {
  return type_->fields()->LookupByKey(field_name.data());
}

const reflection::Field* ReflectiveFlatbuffer::GetFieldOrNull(
    const FlatbufferField* field) const {
  // Lookup by name might be faster as the fields are sorted by name in the
  // schema data, so try that first.
  if (field->field_name() != nullptr) {
    return GetFieldOrNull(field->field_name()->str());
  }
  return GetFieldByOffsetOrNull(field->field_offset());
}

bool ReflectiveFlatbuffer::GetFieldWithParent(
    const FlatbufferFieldPath* field_path, ReflectiveFlatbuffer** parent,
    reflection::Field const** field) {
  const auto* path = field_path->field();
  if (path == nullptr || path->size() == 0) {
    return false;
  }

  for (int i = 0; i < path->size(); i++) {
    *parent = (i == 0 ? this : (*parent)->Mutable(*field));
    if (*parent == nullptr) {
      return false;
    }
    *field = (*parent)->GetFieldOrNull(path->Get(i));
    if (*field == nullptr) {
      return false;
    }
  }

  return true;
}

const reflection::Field* ReflectiveFlatbuffer::GetFieldByOffsetOrNull(
    const int field_offset) const {
  if (type_->fields() == nullptr) {
    return nullptr;
  }
  for (const reflection::Field* field : *type_->fields()) {
    if (field->offset() == field_offset) {
      return field;
    }
  }
  return nullptr;
}

bool ReflectiveFlatbuffer::IsMatchingType(const reflection::Field* field,
                                          const Variant& value) const {
  switch (field->type()->base_type()) {
    case reflection::Bool:
      return value.HasBool();
    case reflection::Int:
      return value.HasInt();
    case reflection::Long:
      return value.HasInt64();
    case reflection::Float:
      return value.HasFloat();
    case reflection::Double:
      return value.HasDouble();
    case reflection::String:
      return value.HasString();
    default:
      return false;
  }
}

bool ReflectiveFlatbuffer::ParseAndSet(const reflection::Field* field,
                                       const std::string& value) {
  switch (field->type()->base_type()) {
    case reflection::String:
      return Set(field, value);
    case reflection::Int: {
      int32 int_value;
      if (!ParseInt32(value.data(), &int_value)) {
        TC3_LOG(ERROR) << "Could not parse '" << value << "' as int32.";
        return false;
      }
      return Set(field, int_value);
    }
    case reflection::Long: {
      int64 int_value;
      if (!ParseInt64(value.data(), &int_value)) {
        TC3_LOG(ERROR) << "Could not parse '" << value << "' as int64.";
        return false;
      }
      return Set(field, int_value);
    }
    case reflection::Float: {
      double double_value;
      if (!ParseDouble(value.data(), &double_value)) {
        TC3_LOG(ERROR) << "Could not parse '" << value << "' as float.";
        return false;
      }
      return Set(field, static_cast<float>(double_value));
    }
    case reflection::Double: {
      double double_value;
      if (!ParseDouble(value.data(), &double_value)) {
        TC3_LOG(ERROR) << "Could not parse '" << value << "' as double.";
        return false;
      }
      return Set(field, double_value);
    }
    default:
      TC3_LOG(ERROR) << "Unhandled field type: " << field->type()->base_type();
      return false;
  }
}

bool ReflectiveFlatbuffer::ParseAndSet(const FlatbufferFieldPath* path,
                                       const std::string& value) {
  ReflectiveFlatbuffer* parent;
  const reflection::Field* field;
  if (!GetFieldWithParent(path, &parent, &field)) {
    return false;
  }
  return parent->ParseAndSet(field, value);
}

ReflectiveFlatbuffer* ReflectiveFlatbuffer::Mutable(
    const StringPiece field_name) {
  if (const reflection::Field* field = GetFieldOrNull(field_name)) {
    return Mutable(field);
  }
  TC3_LOG(ERROR) << "Unknown field: " << field_name.ToString();
  return nullptr;
}

ReflectiveFlatbuffer* ReflectiveFlatbuffer::Mutable(
    const reflection::Field* field) {
  if (field->type()->base_type() != reflection::Obj) {
    TC3_LOG(ERROR) << "Field is not of type Object.";
    return nullptr;
  }
  const auto entry = children_.find(field);
  if (entry != children_.end()) {
    return entry->second.get();
  }
  const auto it = children_.insert(
      /*hint=*/entry,
      std::make_pair(
          field,
          std::unique_ptr<ReflectiveFlatbuffer>(new ReflectiveFlatbuffer(
              schema_, schema_->objects()->Get(field->type()->index())))));
  return it->second.get();
}

ReflectiveFlatbuffer::RepeatedField* ReflectiveFlatbuffer::Repeated(
    StringPiece field_name) {
  if (const reflection::Field* field = GetFieldOrNull(field_name)) {
    return Repeated(field);
  }
  TC3_LOG(ERROR) << "Unknown field: " << field_name.ToString();
  return nullptr;
}

ReflectiveFlatbuffer::RepeatedField* ReflectiveFlatbuffer::Repeated(
    const reflection::Field* field) {
  if (field->type()->base_type() != reflection::Vector) {
    TC3_LOG(ERROR) << "Field is not of type Vector.";
    return nullptr;
  }

  // If the repeated field was already set, return its instance.
  const auto entry = repeated_fields_.find(field);
  if (entry != repeated_fields_.end()) {
    return entry->second.get();
  }

  // Otherwise, create a new instance and store it.
  std::unique_ptr<RepeatedField> repeated_field;
  if (!CreateRepeatedField(schema_, field->type(), &repeated_field)) {
    TC3_LOG(ERROR) << "Could not create repeated field.";
    return nullptr;
  }
  const auto it = repeated_fields_.insert(
      /*hint=*/entry, std::make_pair(field, std::move(repeated_field)));
  return it->second.get();
}

flatbuffers::uoffset_t ReflectiveFlatbuffer::Serialize(
    flatbuffers::FlatBufferBuilder* builder) const {
  // Build all children before we can start with this table.
  std::vector<
      std::pair</* field vtable offset */ int,
                /* field data offset in buffer */ flatbuffers::uoffset_t>>
      offsets;
  offsets.reserve(children_.size() + repeated_fields_.size());
  for (const auto& it : children_) {
    offsets.push_back({it.first->offset(), it.second->Serialize(builder)});
  }

  // Create strings.
  for (const auto& it : fields_) {
    if (it.second.HasString()) {
      offsets.push_back({it.first->offset(),
                         builder->CreateString(it.second.StringValue()).o});
    }
  }

  // Build the repeated fields.
  for (const auto& it : repeated_fields_) {
    offsets.push_back({it.first->offset(), it.second->Serialize(builder)});
  }

  // Build the table now.
  const flatbuffers::uoffset_t table_start = builder->StartTable();

  // Add scalar fields.
  for (const auto& it : fields_) {
    switch (it.second.GetType()) {
      case Variant::TYPE_BOOL_VALUE:
        builder->AddElement<uint8_t>(
            it.first->offset(), static_cast<uint8_t>(it.second.BoolValue()),
            static_cast<uint8_t>(it.first->default_integer()));
        continue;
      case Variant::TYPE_INT_VALUE:
        builder->AddElement<int32>(
            it.first->offset(), it.second.IntValue(),
            static_cast<int32>(it.first->default_integer()));
        continue;
      case Variant::TYPE_INT64_VALUE:
        builder->AddElement<int64>(it.first->offset(), it.second.Int64Value(),
                                   it.first->default_integer());
        continue;
      case Variant::TYPE_FLOAT_VALUE:
        builder->AddElement<float>(
            it.first->offset(), it.second.FloatValue(),
            static_cast<float>(it.first->default_real()));
        continue;
      case Variant::TYPE_DOUBLE_VALUE:
        builder->AddElement<double>(it.first->offset(), it.second.DoubleValue(),
                                    it.first->default_real());
        continue;
      default:
        continue;
    }
  }

  // Add strings, subtables and repeated fields.
  for (const auto& it : offsets) {
    builder->AddOffset(it.first, flatbuffers::Offset<void>(it.second));
  }

  return builder->EndTable(table_start);
}

std::string ReflectiveFlatbuffer::Serialize() const {
  flatbuffers::FlatBufferBuilder builder;
  builder.Finish(flatbuffers::Offset<void>(Serialize(&builder)));
  return std::string(reinterpret_cast<const char*>(builder.GetBufferPointer()),
                     builder.GetSize());
}

bool ReflectiveFlatbuffer::MergeFrom(const flatbuffers::Table* from) {
  // No fields to set.
  if (type_->fields() == nullptr) {
    return true;
  }

  for (const reflection::Field* field : *type_->fields()) {
    // Skip fields that are not explicitly set.
    if (!from->CheckField(field->offset())) {
      continue;
    }
    const reflection::BaseType type = field->type()->base_type();
    switch (type) {
      case reflection::Bool:
        Set<bool>(field, from->GetField<uint8_t>(field->offset(),
                                                 field->default_integer()));
        break;
      case reflection::Int:
        Set<int32>(field, from->GetField<int32>(field->offset(),
                                                field->default_integer()));
        break;
      case reflection::Long:
        Set<int64>(field, from->GetField<int64>(field->offset(),
                                                field->default_integer()));
        break;
      case reflection::Float:
        Set<float>(field, from->GetField<float>(field->offset(),
                                                field->default_real()));
        break;
      case reflection::Double:
        Set<double>(field, from->GetField<double>(field->offset(),
                                                  field->default_real()));
        break;
      case reflection::String:
        Set<std::string>(
            field, from->GetPointer<const flatbuffers::String*>(field->offset())
                       ->str());
        break;
      case reflection::Obj:
        if (!Mutable(field)->MergeFrom(
                from->GetPointer<const flatbuffers::Table* const>(
                    field->offset()))) {
          return false;
        }
        break;
      default:
        TC3_LOG(ERROR) << "Unsupported type: " << type;
        return false;
    }
  }
  return true;
}

bool ReflectiveFlatbuffer::MergeFromSerializedFlatbuffer(StringPiece from) {
  return MergeFrom(flatbuffers::GetAnyRoot(
      reinterpret_cast<const unsigned char*>(from.data())));
}

void ReflectiveFlatbuffer::AsFlatMap(
    const std::string& key_separator, const std::string& key_prefix,
    std::map<std::string, Variant>* result) const {
  // Add direct fields.
  for (auto it : fields_) {
    (*result)[key_prefix + it.first->name()->str()] = it.second;
  }

  // Add nested messages.
  for (auto& it : children_) {
    it.second->AsFlatMap(key_separator,
                         key_prefix + it.first->name()->str() + key_separator,
                         result);
  }
}

}  // namespace libtextclassifier3
