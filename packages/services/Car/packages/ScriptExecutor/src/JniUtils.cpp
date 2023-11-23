/*
 * Copyright (c) 2021, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "JniUtils.h"

#include "nativehelper/scoped_local_ref.h"

namespace com {
namespace android {
namespace car {
namespace scriptexecutor {

void pushBundleToLuaTable(JNIEnv* env, lua_State* lua, jobject bundle) {
    lua_newtable(lua);
    // null bundle object is allowed. We will treat it as an empty table.
    if (bundle == nullptr) {
        return;
    }

    // TODO(b/188832769): Consider caching some of these JNI references for
    // performance reasons.
    ScopedLocalRef<jclass> persistableBundleClass(env,
                                                  env->FindClass("android/os/PersistableBundle"));
    jmethodID getKeySetMethod =
            env->GetMethodID(persistableBundleClass.get(), "keySet", "()Ljava/util/Set;");
    ScopedLocalRef<jobject> keys(env, env->CallObjectMethod(bundle, getKeySetMethod));
    ScopedLocalRef<jclass> setClass(env, env->FindClass("java/util/Set"));
    jmethodID iteratorMethod =
            env->GetMethodID(setClass.get(), "iterator", "()Ljava/util/Iterator;");
    ScopedLocalRef<jobject> keySetIteratorObject(env,
                                                 env->CallObjectMethod(keys.get(), iteratorMethod));

    ScopedLocalRef<jclass> iteratorClass(env, env->FindClass("java/util/Iterator"));
    jmethodID hasNextMethod = env->GetMethodID(iteratorClass.get(), "hasNext", "()Z");
    jmethodID nextMethod = env->GetMethodID(iteratorClass.get(), "next", "()Ljava/lang/Object;");

    ScopedLocalRef<jclass> booleanClass(env, env->FindClass("java/lang/Boolean"));
    ScopedLocalRef<jclass> integerClass(env, env->FindClass("java/lang/Integer"));
    ScopedLocalRef<jclass> longClass(env, env->FindClass("java/lang/Long"));
    ScopedLocalRef<jclass> numberClass(env, env->FindClass("java/lang/Number"));
    ScopedLocalRef<jclass> stringClass(env, env->FindClass("java/lang/String"));
    ScopedLocalRef<jclass> intArrayClass(env, env->FindClass("[I"));
    ScopedLocalRef<jclass> longArrayClass(env, env->FindClass("[J"));
    ScopedLocalRef<jclass> stringArrayClass(env, env->FindClass("[Ljava/lang/String;"));
    // TODO(b/188816922): Handle more types such as float and integer arrays,
    // and perhaps nested Bundles.

    jmethodID getMethod = env->GetMethodID(persistableBundleClass.get(), "get",
                                           "(Ljava/lang/String;)Ljava/lang/Object;");

    // Iterate over key set of the bundle one key at a time.
    while (env->CallBooleanMethod(keySetIteratorObject.get(), hasNextMethod)) {
        // Read the value object that corresponds to this key.
        ScopedLocalRef<jstring> key(env,
                                    (jstring)env->CallObjectMethod(keySetIteratorObject.get(),
                                                                   nextMethod));
        ScopedLocalRef<jobject> value(env, env->CallObjectMethod(bundle, getMethod, key.get()));

        // Get the value of the type, extract it accordingly from the bundle and
        // push the extracted value and the key to the Lua table.
        if (env->IsInstanceOf(value.get(), booleanClass.get())) {
            jmethodID boolMethod = env->GetMethodID(booleanClass.get(), "booleanValue", "()Z");
            bool boolValue = static_cast<bool>(env->CallBooleanMethod(value.get(), boolMethod));
            lua_pushboolean(lua, boolValue);
        } else if (env->IsInstanceOf(value.get(), integerClass.get())) {
            jmethodID intMethod = env->GetMethodID(integerClass.get(), "intValue", "()I");
            lua_pushinteger(lua, env->CallIntMethod(value.get(), intMethod));
        } else if (env->IsInstanceOf(value.get(), longClass.get())) {
            jmethodID longMethod = env->GetMethodID(longClass.get(), "longValue", "()J");
            lua_pushinteger(lua, env->CallLongMethod(value.get(), longMethod));
        } else if (env->IsInstanceOf(value.get(), numberClass.get())) {
            // Condense other numeric types using one class. Because lua supports only
            // integer or double, and we handled integer in previous if clause.
            jmethodID numberMethod = env->GetMethodID(numberClass.get(), "doubleValue", "()D");
            /* Pushes a double onto the stack */
            lua_pushnumber(lua, env->CallDoubleMethod(value.get(), numberMethod));
        } else if (env->IsInstanceOf(value.get(), stringClass.get())) {
            // Produces a string in Modified UTF-8 encoding. Any null character
            // inside the original string is converted into two-byte encoding.
            // This way we can directly use the output of GetStringUTFChars in C API that
            // expects a null-terminated string.
            const char* rawStringValue =
                    env->GetStringUTFChars(static_cast<jstring>(value.get()), nullptr);
            lua_pushstring(lua, rawStringValue);
            env->ReleaseStringUTFChars(static_cast<jstring>(value.get()), rawStringValue);
        } else if (env->IsInstanceOf(value.get(), intArrayClass.get())) {
            jintArray intArray = static_cast<jintArray>(value.get());
            const auto kLength = env->GetArrayLength(intArray);
            // Arrays are represented as a table of sequential elements in Lua.
            // We are creating a nested table to represent this array. We specify number of elements
            // in the Java array to preallocate memory accordingly.
            lua_createtable(lua, kLength, 0);
            jint* rawIntArray = env->GetIntArrayElements(intArray, nullptr);
            // Fills in the table at stack idx -2 with key value pairs, where key is a
            // Lua index and value is an integer from the byte array at that index
            for (int i = 0; i < kLength; i++) {
                // Stack at index -1 is rawIntArray[i] after this push.
                lua_pushinteger(lua, rawIntArray[i]);
                lua_rawseti(lua, /* idx= */ -2,
                            i + 1);  // lua index starts from 1
            }
            // JNI_ABORT is used because we do not need to copy back elements.
            env->ReleaseIntArrayElements(intArray, rawIntArray, JNI_ABORT);
        } else if (env->IsInstanceOf(value.get(), longArrayClass.get())) {
            jlongArray longArray = static_cast<jlongArray>(value.get());
            const auto kLength = env->GetArrayLength(longArray);
            // Arrays are represented as a table of sequential elements in Lua.
            // We are creating a nested table to represent this array. We specify number of elements
            // in the Java array to preallocate memory accordingly.
            lua_createtable(lua, kLength, 0);
            jlong* rawLongArray = env->GetLongArrayElements(longArray, nullptr);
            // Fills in the table at stack idx -2 with key value pairs, where key is a
            // Lua index and value is an integer from the byte array at that index
            for (int i = 0; i < kLength; i++) {
                lua_pushinteger(lua, rawLongArray[i]);
                lua_rawseti(lua, /* idx= */ -2,
                            i + 1);  // lua index starts from 1
            }
            // JNI_ABORT is used because we do not need to copy back elements.
            env->ReleaseLongArrayElements(longArray, rawLongArray, JNI_ABORT);
        } else if (env->IsInstanceOf(value.get(), stringArrayClass.get())) {
            jobjectArray stringArray = static_cast<jobjectArray>(value.get());
            const auto kLength = env->GetArrayLength(stringArray);
            // Arrays are represented as a table of sequential elements in Lua.
            // We are creating a nested table to represent this array. We specify number of elements
            // in the Java array to preallocate memory accordingly.
            lua_createtable(lua, kLength, 0);
            // Fills in the table at stack idx -2 with key value pairs, where key is a Lua index and
            // value is an string value extracted from the object array at that index
            for (int i = 0; i < kLength; i++) {
                ScopedLocalRef<jobject> localStringRef(env,
                                                       env->GetObjectArrayElement(stringArray, i));
                jstring element = static_cast<jstring>(localStringRef.get());
                const char* rawStringValue = env->GetStringUTFChars(element, nullptr);
                lua_pushstring(lua, rawStringValue);
                env->ReleaseStringUTFChars(element, rawStringValue);
                // lua index starts from 1
                lua_rawseti(lua, /* idx= */ -2, i + 1);
            }
        } else {
            // Other types are not implemented yet, skipping.
            continue;
        }

        const char* rawKey = env->GetStringUTFChars(key.get(), nullptr);
        // table[rawKey] = value, where value is on top of the stack,
        // and the table is the next element in the stack.
        lua_setfield(lua, /* idx= */ -2, rawKey);
        env->ReleaseStringUTFChars(key.get(), rawKey);
    }
}

}  // namespace scriptexecutor
}  // namespace car
}  // namespace android
}  // namespace com
