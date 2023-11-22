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

#ifndef LIBTEXTCLASSIFIER_UTILS_LUA_UTILS_H_
#define LIBTEXTCLASSIFIER_UTILS_LUA_UTILS_H_

#include <functional>
#include <vector>

#include "utils/flatbuffers.h"
#include "utils/strings/stringpiece.h"
#include "utils/variant.h"
#include "flatbuffers/reflection_generated.h"

#ifdef __cplusplus
extern "C" {
#endif
#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"
#ifdef __cplusplus
}
#endif

namespace libtextclassifier3 {

static constexpr const char *kLengthKey = "__len";
static constexpr const char *kPairsKey = "__pairs";
static constexpr const char *kIndexKey = "__index";

// Casts to the lua user data type.
template <typename T>
void *AsUserData(const T *value) {
  return static_cast<void *>(const_cast<T *>(value));
}
template <typename T>
void *AsUserData(const T value) {
  return reinterpret_cast<void *>(value);
}

// Retrieves up-values.
template <typename T>
T FromUpValue(const int index, lua_State *state) {
  return static_cast<T>(lua_touserdata(state, lua_upvalueindex(index)));
}

class LuaEnvironment {
 public:
  // Wrapper for handling an iterator.
  class Iterator {
   public:
    virtual ~Iterator() {}
    static int NextCallback(lua_State *state);
    static int LengthCallback(lua_State *state);
    static int ItemCallback(lua_State *state);
    static int IteritemsCallback(lua_State *state);

    // Called when the next element of an iterator is fetched.
    virtual int Next(lua_State *state) const = 0;

    // Called when the length of the iterator is queried.
    virtual int Length(lua_State *state) const = 0;

    // Called when an item is queried.
    virtual int Item(lua_State *state) const = 0;

    // Called when a new iterator is started.
    virtual int Iteritems(lua_State *state) const = 0;

   protected:
    static constexpr int kIteratorArgId = 1;
  };

  template <typename T>
  class ItemIterator : public Iterator {
   public:
    void NewIterator(StringPiece name, const T *items, lua_State *state) const {
      lua_newtable(state);
      luaL_newmetatable(state, name.data());
      lua_pushlightuserdata(state, AsUserData(this));
      lua_pushlightuserdata(state, AsUserData(items));
      lua_pushcclosure(state, &Iterator::ItemCallback, 2);
      lua_setfield(state, -2, kIndexKey);
      lua_pushlightuserdata(state, AsUserData(this));
      lua_pushlightuserdata(state, AsUserData(items));
      lua_pushcclosure(state, &Iterator::LengthCallback, 2);
      lua_setfield(state, -2, kLengthKey);
      lua_pushlightuserdata(state, AsUserData(this));
      lua_pushlightuserdata(state, AsUserData(items));
      lua_pushcclosure(state, &Iterator::IteritemsCallback, 2);
      lua_setfield(state, -2, kPairsKey);
      lua_setmetatable(state, -2);
    }

    int Iteritems(lua_State *state) const override {
      lua_pushlightuserdata(state, AsUserData(this));
      lua_pushlightuserdata(
          state, lua_touserdata(state, lua_upvalueindex(kItemsArgId)));
      lua_pushnumber(state, 0);
      lua_pushcclosure(state, &Iterator::NextCallback, 3);
      return /*num results=*/1;
    }

    int Length(lua_State *state) const override {
      lua_pushinteger(state, FromUpValue<T *>(kItemsArgId, state)->size());
      return /*num results=*/1;
    }

    int Next(lua_State *state) const override {
      return Next(FromUpValue<T *>(kItemsArgId, state),
                  lua_tointeger(state, lua_upvalueindex(kIterValueArgId)),
                  state);
    }

    int Next(const T *items, const int64 pos, lua_State *state) const {
      if (pos >= items->size()) {
        return 0;
      }

      // Update iterator value.
      lua_pushnumber(state, pos + 1);
      lua_replace(state, lua_upvalueindex(3));

      // Push key.
      lua_pushinteger(state, pos + 1);

      // Push item.
      return 1 + Item(items, pos, state);
    }

    int Item(lua_State *state) const override {
      const T *items = FromUpValue<T *>(kItemsArgId, state);
      switch (lua_type(state, -1)) {
        case LUA_TNUMBER: {
          // Lua is one based, so adjust the index here.
          const int64 index =
              static_cast<int64>(lua_tonumber(state, /*idx=*/-1)) - 1;
          if (index < 0 || index >= items->size()) {
            TC3_LOG(ERROR) << "Invalid index: " << index;
            lua_error(state);
            return 0;
          }
          return Item(items, index, state);
        }
        case LUA_TSTRING: {
          size_t key_length = 0;
          const char *key = lua_tolstring(state, /*idx=*/-1, &key_length);
          return Item(items, StringPiece(key, key_length), state);
        }
        default:
          TC3_LOG(ERROR) << "Unexpected access type: " << lua_type(state, -1);
          lua_error(state);
          return 0;
      }
    }

    virtual int Item(const T *items, const int64 pos,
                     lua_State *state) const = 0;

    virtual int Item(const T *items, StringPiece key, lua_State *state) const {
      TC3_LOG(ERROR) << "Unexpected key access: " << key.ToString();
      lua_error(state);
      return 0;
    }

   protected:
    static constexpr int kItemsArgId = 2;
    static constexpr int kIterValueArgId = 3;
  };

  virtual ~LuaEnvironment();
  LuaEnvironment();

  // Compile a lua snippet into binary bytecode.
  // NOTE: The compiled bytecode might not be compatible across Lua versions
  // and platforms.
  bool Compile(StringPiece snippet, std::string *bytecode);

  typedef int (*CallbackHandler)(lua_State *);

  // Loads default libraries.
  void LoadDefaultLibraries();

  // Provides a callback to Lua.
  template <typename T, int (T::*handler)()>
  void Bind() {
    lua_pushlightuserdata(state_, static_cast<void *>(this));
    lua_pushcclosure(state_, &Dispatch<T, handler>, 1);
  }

  // Setup a named table that callsback whenever a member is accessed.
  // This allows to lazily provide required information to the script.
  template <typename T, int (T::*handler)()>
  void BindTable(const char *name) {
    lua_newtable(state_);
    luaL_newmetatable(state_, name);
    lua_pushlightuserdata(state_, static_cast<void *>(this));
    lua_pushcclosure(state_, &Dispatch<T, handler>, 1);
    lua_setfield(state_, -2, kIndexKey);
    lua_setmetatable(state_, -2);
  }

  void PushValue(const Variant &value);

  // Reads a string from the stack.
  StringPiece ReadString(const int index) const;

  // Pushes a string to the stack.
  void PushString(const StringPiece str);

  // Pushes a flatbuffer to the stack.
  void PushFlatbuffer(const reflection::Schema *schema,
                      const flatbuffers::Table *table);

  // Reads a flatbuffer from the stack.
  int ReadFlatbuffer(ReflectiveFlatbuffer *buffer);

  // Runs a closure in protected mode.
  // `func`: closure to run in protected mode.
  // `num_lua_args`: number of arguments from the lua stack to process.
  // `num_results`: number of result values pushed on the stack.
  int RunProtected(const std::function<int()> &func, const int num_args = 0,
                   const int num_results = 0);

  lua_State *state() const { return state_; }

 protected:
  lua_State *state_;

 private:
  // Auxiliary methods to expose (reflective) flatbuffer based data to Lua.
  static void PushFlatbuffer(const char *name, const reflection::Schema *schema,
                             const reflection::Object *type,
                             const flatbuffers::Table *table, lua_State *state);
  static int GetFieldCallback(lua_State *state);
  static int GetField(const reflection::Schema *schema,
                      const reflection::Object *type,
                      const flatbuffers::Table *table, lua_State *state);

  template <typename T, int (T::*handler)()>
  static int Dispatch(lua_State *state) {
    T *env = FromUpValue<T *>(1, state);
    return ((*env).*handler)();
  }
};

bool Compile(StringPiece snippet, std::string *bytecode);

}  // namespace libtextclassifier3

#endif  // LIBTEXTCLASSIFIER_UTILS_LUA_UTILS_H_
