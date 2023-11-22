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

#include "utils/lua-utils.h"

// lua_dump takes an extra argument "strip" in 5.3, but not in 5.2.
#ifndef TC3_AOSP
#define lua_dump(L, w, d, s) lua_dump((L), (w), (d))
#endif

namespace libtextclassifier3 {
namespace {
// Upvalue indices for the flatbuffer callback.
static constexpr int kSchemaArgId = 1;
static constexpr int kTypeArgId = 2;
static constexpr int kTableArgId = 3;

static constexpr luaL_Reg defaultlibs[] = {{"_G", luaopen_base},
                                           {LUA_TABLIBNAME, luaopen_table},
                                           {LUA_STRLIBNAME, luaopen_string},
                                           {LUA_BITLIBNAME, luaopen_bit32},
                                           {LUA_MATHLIBNAME, luaopen_math},
                                           {nullptr, nullptr}};

// Implementation of a lua_Writer that appends the data to a string.
int LuaStringWriter(lua_State *state, const void *data, size_t size,
                    void *result) {
  std::string *const result_string = static_cast<std::string *>(result);
  result_string->insert(result_string->size(), static_cast<const char *>(data),
                        size);
  return LUA_OK;
}

}  // namespace

LuaEnvironment::LuaEnvironment() { state_ = luaL_newstate(); }

LuaEnvironment::~LuaEnvironment() {
  if (state_ != nullptr) {
    lua_close(state_);
  }
}

int LuaEnvironment::Iterator::NextCallback(lua_State *state) {
  return FromUpValue<Iterator *>(kIteratorArgId, state)->Next(state);
}

int LuaEnvironment::Iterator::LengthCallback(lua_State *state) {
  return FromUpValue<Iterator *>(kIteratorArgId, state)->Length(state);
}

int LuaEnvironment::Iterator::ItemCallback(lua_State *state) {
  return FromUpValue<Iterator *>(kIteratorArgId, state)->Item(state);
}

int LuaEnvironment::Iterator::IteritemsCallback(lua_State *state) {
  return FromUpValue<Iterator *>(kIteratorArgId, state)->Iteritems(state);
}

void LuaEnvironment::PushFlatbuffer(const char *name,
                                    const reflection::Schema *schema,
                                    const reflection::Object *type,
                                    const flatbuffers::Table *table,
                                    lua_State *state) {
  lua_newtable(state);
  luaL_newmetatable(state, name);
  lua_pushlightuserdata(state, AsUserData(schema));
  lua_pushlightuserdata(state, AsUserData(type));
  lua_pushlightuserdata(state, AsUserData(table));
  lua_pushcclosure(state, &GetFieldCallback, 3);
  lua_setfield(state, -2, kIndexKey);
  lua_setmetatable(state, -2);
}

int LuaEnvironment::GetFieldCallback(lua_State *state) {
  // Fetch the arguments.
  const reflection::Schema *schema =
      FromUpValue<reflection::Schema *>(kSchemaArgId, state);
  const reflection::Object *type =
      FromUpValue<reflection::Object *>(kTypeArgId, state);
  const flatbuffers::Table *table =
      FromUpValue<flatbuffers::Table *>(kTableArgId, state);
  return GetField(schema, type, table, state);
}

int LuaEnvironment::GetField(const reflection::Schema *schema,
                             const reflection::Object *type,
                             const flatbuffers::Table *table,
                             lua_State *state) {
  const char *field_name = lua_tostring(state, -1);
  const reflection::Field *field = type->fields()->LookupByKey(field_name);
  if (field == nullptr) {
    lua_error(state);
    return 0;
  }
  // Provide primitive fields directly.
  const reflection::BaseType field_type = field->type()->base_type();
  switch (field_type) {
    case reflection::Bool:
      lua_pushboolean(state, table->GetField<uint8_t>(
                                 field->offset(), field->default_integer()));
      break;
    case reflection::Int:
      lua_pushinteger(state, table->GetField<int32>(field->offset(),
                                                    field->default_integer()));
      break;
    case reflection::Long:
      lua_pushinteger(state, table->GetField<int64>(field->offset(),
                                                    field->default_integer()));
      break;
    case reflection::Float:
      lua_pushnumber(state, table->GetField<float>(field->offset(),
                                                   field->default_real()));
      break;
    case reflection::Double:
      lua_pushnumber(state, table->GetField<double>(field->offset(),
                                                    field->default_real()));
      break;
    case reflection::String: {
      const flatbuffers::String *string_value =
          table->GetPointer<const flatbuffers::String *>(field->offset());
      if (string_value != nullptr) {
        lua_pushlstring(state, string_value->data(), string_value->Length());
      } else {
        lua_pushlstring(state, "", 0);
      }
      break;
    }
    case reflection::Obj: {
      const flatbuffers::Table *field_table =
          table->GetPointer<const flatbuffers::Table *>(field->offset());
      if (field_table == nullptr) {
        TC3_LOG(ERROR) << "Field was not set in entity data.";
        lua_error(state);
        return 0;
      }
      const reflection::Object *field_type =
          schema->objects()->Get(field->type()->index());
      PushFlatbuffer(field->name()->c_str(), schema, field_type, field_table,
                     state);
      break;
    }
    default:
      TC3_LOG(ERROR) << "Unsupported type: " << field_type;
      lua_error(state);
      return 0;
  }
  return 1;
}

int LuaEnvironment::ReadFlatbuffer(ReflectiveFlatbuffer *buffer) {
  if (lua_type(state_, /*idx=*/-1) != LUA_TTABLE) {
    TC3_LOG(ERROR) << "Expected actions table, got: "
                   << lua_type(state_, /*idx=*/-1);
    lua_error(state_);
    return LUA_ERRRUN;
  }

  lua_pushnil(state_);
  while (lua_next(state_, /*idx=*/-2)) {
    const StringPiece key = ReadString(/*index=*/-2);
    const reflection::Field *field = buffer->GetFieldOrNull(key);
    if (field == nullptr) {
      TC3_LOG(ERROR) << "Unknown field: " << key.ToString();
      lua_error(state_);
      return LUA_ERRRUN;
    }
    switch (field->type()->base_type()) {
      case reflection::Obj:
        return ReadFlatbuffer(buffer->Mutable(field));
      case reflection::Bool:
        buffer->Set(field,
                    static_cast<bool>(lua_toboolean(state_, /*idx=*/-1)));
        break;
      case reflection::Int:
        buffer->Set(field, static_cast<int>(lua_tonumber(state_, /*idx=*/-1)));
        break;
      case reflection::Long:
        buffer->Set(field,
                    static_cast<int64>(lua_tonumber(state_, /*idx=*/-1)));
        break;
      case reflection::Float:
        buffer->Set(field,
                    static_cast<float>(lua_tonumber(state_, /*idx=*/-1)));
        break;
      case reflection::Double:
        buffer->Set(field,
                    static_cast<double>(lua_tonumber(state_, /*idx=*/-1)));
        break;
      case reflection::String: {
        buffer->Set(field, ReadString(/*index=*/-1));
        break;
      }
      default:
        TC3_LOG(ERROR) << "Unsupported type: " << field->type()->base_type();
        lua_error(state_);
        return LUA_ERRRUN;
    }
    lua_pop(state_, 1);
  }
  // lua_pop(state_, /*n=*/1);
  return LUA_OK;
}

void LuaEnvironment::LoadDefaultLibraries() {
  for (const luaL_Reg *lib = defaultlibs; lib->func; lib++) {
    luaL_requiref(state_, lib->name, lib->func, 1);
    lua_pop(state_, 1); /* remove lib */
  }
}

void LuaEnvironment::PushValue(const Variant &value) {
  if (value.HasInt()) {
    lua_pushnumber(state_, value.IntValue());
  } else if (value.HasInt64()) {
    lua_pushnumber(state_, value.Int64Value());
  } else if (value.HasBool()) {
    lua_pushboolean(state_, value.BoolValue());
  } else if (value.HasFloat()) {
    lua_pushnumber(state_, value.FloatValue());
  } else if (value.HasDouble()) {
    lua_pushnumber(state_, value.DoubleValue());
  } else if (value.HasString()) {
    lua_pushlstring(state_, value.StringValue().data(),
                    value.StringValue().size());
  } else {
    TC3_LOG(FATAL) << "Unknown value type.";
  }
}

StringPiece LuaEnvironment::ReadString(const int index) const {
  size_t length = 0;
  const char *data = lua_tolstring(state_, index, &length);
  return StringPiece(data, length);
}

void LuaEnvironment::PushString(const StringPiece str) {
  lua_pushlstring(state_, str.data(), str.size());
}

void LuaEnvironment::PushFlatbuffer(const reflection::Schema *schema,
                                    const flatbuffers::Table *table) {
  PushFlatbuffer(schema->root_table()->name()->c_str(), schema,
                 schema->root_table(), table, state_);
}

int LuaEnvironment::RunProtected(const std::function<int()> &func,
                                 const int num_args, const int num_results) {
  struct ProtectedCall {
    std::function<int()> func;

    static int run(lua_State *state) {
      // Read the pointer to the ProtectedCall struct.
      ProtectedCall *p = static_cast<ProtectedCall *>(
          lua_touserdata(state, lua_upvalueindex(1)));
      return p->func();
    }
  };
  ProtectedCall protected_call = {func};
  lua_pushlightuserdata(state_, &protected_call);
  lua_pushcclosure(state_, &ProtectedCall::run, /*n=*/1);
  // Put the closure before the arguments on the stack.
  if (num_args > 0) {
    lua_insert(state_, -(1 + num_args));
  }
  return lua_pcall(state_, num_args, num_results, /*errorfunc=*/0);
}

bool LuaEnvironment::Compile(StringPiece snippet, std::string *bytecode) {
  if (luaL_loadbuffer(state_, snippet.data(), snippet.size(),
                      /*name=*/nullptr) != LUA_OK) {
    TC3_LOG(ERROR) << "Could not compile lua snippet: "
                   << ReadString(/*index=*/-1).ToString();
    lua_pop(state_, 1);
    return false;
  }
  if (lua_dump(state_, LuaStringWriter, bytecode, /*strip*/ 1) != LUA_OK) {
    TC3_LOG(ERROR) << "Could not dump compiled lua snippet.";
    lua_pop(state_, 1);
    return false;
  }
  lua_pop(state_, 1);
  return true;
}

bool Compile(StringPiece snippet, std::string *bytecode) {
  return LuaEnvironment().Compile(snippet, bytecode);
}

}  // namespace libtextclassifier3
