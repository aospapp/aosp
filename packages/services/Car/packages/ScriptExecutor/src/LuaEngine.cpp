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

#include "LuaEngine.h"

#include "BundleWrapper.h"

#include <android-base/logging.h>

#include <sstream>
#include <string>
#include <utility>
#include <vector>

extern "C" {
#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"
}

namespace com {
namespace android {
namespace car {
namespace scriptexecutor {

using ::android::base::Error;
using ::android::base::Result;

namespace {

enum LuaNumReturnedResults {
    ZERO_RETURNED_RESULTS = 0,
};

// Prefix for logging messages coming from lua script.
const char kLuaLogTag[] = "LUA: ";

// TODO(b/199415783): Revisit the topic of limits to potentially move it to standalone file.
constexpr int MAX_ARRAY_SIZE = 1000;

// Helper method that goes over Lua table fields one by one and populates PersistableBundle
// object wrapped in BundleWrapper.
// It is assumed that Lua table is located on top of the Lua stack. There could be other
// items in the stack, this function will not touch them.
//
// Returns false if the conversion encountered unrecoverable error.
// Otherwise, returns true for success.
//
// If the function succeeds, the stack should be unchanged.
// In case of an error, there is no need to pop elements or clean the stack. When Lua calls C,
// the stack used to pass data between Lua and C is private for each call. According to
// https://www.lua.org/pil/26.1.html, after C function returns back to Lua, Lua
// removes everything that is in the stack below the returned results.
// TODO(b/200849134): Refactor this function.
Result<void> convertLuaTableToBundle(lua_State* lua, BundleWrapper* bundleWrapper) {
    // Iterate over Lua table which is expected to be at the top of Lua stack.
    // lua_next call pops the key from the top of the stack and finds the next
    // key-value pair. It returns 0 if the next pair was not found.
    // More on lua_next in: https://www.lua.org/manual/5.3/manual.html#lua_next
    lua_pushnil(lua);  // First key is a null value, at index -1
    while (lua_next(lua, /* table index = */ -2) != 0) {
        //  'key' is at index -2 and 'value' is at index -1
        // -1 index is the top of the stack.
        // remove 'value' and keep 'key' for next iteration
        // Process each key-value depending on a type and push it to Java PersistableBundle.
        // TODO(b/199531928): Consider putting limits on key sizes as well.
        const char* key = lua_tostring(lua, /* index = */ -2);
        Result<void> bundleInsertionResult;
        if (lua_isboolean(lua, /* index = */ -1)) {
            bundleInsertionResult =
                    bundleWrapper->putBoolean(key,
                                              static_cast<bool>(
                                                      lua_toboolean(lua, /* index = */ -1)));
        } else if (lua_isinteger(lua, /* index = */ -1)) {
            bundleInsertionResult =
                    bundleWrapper->putLong(key,
                                           static_cast<int64_t>(
                                                   lua_tointeger(lua, /* index = */ -1)));
        } else if (lua_isnumber(lua, /* index = */ -1)) {
            bundleInsertionResult =
                    bundleWrapper->putDouble(key,
                                             static_cast<double>(
                                                     lua_tonumber(lua, /* index = */ -1)));
        } else if (lua_isstring(lua, /* index = */ -1)) {
            // TODO(b/199415783): We need to have a limit on how long these strings could be.
            bundleInsertionResult =
                    bundleWrapper->putString(key, lua_tostring(lua, /* index = */ -1));
        } else if (lua_istable(lua, /* index =*/-1)) {
            // Lua uses tables to represent an array.

            // TODO(b/199438375): Document to users that we expect tables to be either only indexed
            // or keyed but not both. If the table contains consecutively indexed values starting
            // from 1, we will treat it as an array. lua_rawlen call returns the size of the indexed
            // part. We copy this part into an array, but any keyed values in this table are
            // ignored. There is a test that documents this current behavior. If a user wants a
            // nested table to be represented by a PersistableBundle object, they must make sure
            // that the nested table does not contain indexed data, including no key=1.
            const auto kTableLength = lua_rawlen(lua, -1);
            if (kTableLength > MAX_ARRAY_SIZE) {
                return Error()
                        << "Returned table " << key << " exceeds maximum allowed size of "
                        << MAX_ARRAY_SIZE
                        << " elements. This key-value cannot be unpacked successfully. This error "
                           "is unrecoverable.";
            }
            if (kTableLength <= 0) {
                return Error() << "A value with key=" << key
                               << " appears to be a nested table that does not represent an array "
                                  "of data. "
                                  "Such nested tables are not supported yet. This script error is "
                                  "unrecoverable.";
            }

            std::vector<int64_t> longArray;
            std::vector<std::string> stringArray;
            int originalLuaType = LUA_TNIL;
            for (int i = 0; i < kTableLength; i++) {
                lua_rawgeti(lua, -1, i + 1);
                // Lua allows arrays to have values of varying type. We need to force all Lua
                // arrays to stick to single type within the same array. We use the first value
                // in the array to determine the type of all values in the array that follow
                // after. If the second, third, etc element of the array does not match the type
                // of the first element we stop the extraction and return an error via a
                // callback.
                if (i == 0) {
                    originalLuaType = lua_type(lua, /* index = */ -1);
                }
                int currentType = lua_type(lua, /* index= */ -1);
                if (currentType != originalLuaType) {
                    return Error()
                            << "Returned Lua arrays must have elements of the same type. Returned "
                               "table with key="
                            << key << " has the first element of type=" << originalLuaType
                            << ", but the element at index=" << i + 1 << " has type=" << currentType
                            << ". Integer type codes are defined in lua.h file. This error is "
                               "unrecoverable.";
                }
                switch (currentType) {
                    case LUA_TNUMBER:
                        if (!lua_isinteger(lua, /* index = */ -1)) {
                            return Error() << "Returned value for key=" << key
                                           << " contains a floating number array, which is not "
                                              "supported yet.";
                        } else {
                            longArray.push_back(lua_tointeger(lua, /* index = */ -1));
                        }
                        break;
                    case LUA_TSTRING:
                        // TODO(b/200833728): Investigate optimizations to minimize string
                        // copying. For example, populate JNI object array one element at a
                        // time, as we go.
                        stringArray.push_back(lua_tostring(lua, /* index = */ -1));
                        break;
                    default:
                        return Error() << "Returned value for key=" << key
                                       << " is an array with values of type="
                                       << lua_typename(lua, lua_type(lua, /* index = */ -1))
                                       << ", which is not supported yet.";
                }
                lua_pop(lua, 1);
            }
            switch (originalLuaType) {
                case LUA_TNUMBER:
                    bundleInsertionResult = bundleWrapper->putLongArray(key, longArray);
                    break;
                case LUA_TSTRING:
                    bundleInsertionResult = bundleWrapper->putStringArray(key, stringArray);
                    break;
            }
        } else {
            return Error() << "key=" << key << " has a Lua type="
                           << lua_typename(lua, lua_type(lua, /* index = */ -1))
                           << ", which is not supported yet.";
        }
        // Pop value from the stack, keep the key for the next iteration.
        lua_pop(lua, 1);
        // The key is at index -1, the table is at index -2 now.

        // Check if insertion of the current key-value into the bundle was successful. If not,
        // fail-fast out of this extraction routine.
        if (!bundleInsertionResult.ok()) {
            return bundleInsertionResult;
        }
    }
    return {};  // ok result
}

}  // namespace

ScriptExecutorListener* LuaEngine::sListener = nullptr;

LuaEngine::LuaEngine() {
    // Instantiate Lua environment
    mLuaState = luaL_newstate();
    luaL_openlibs(mLuaState);
}

LuaEngine::~LuaEngine() {
    lua_close(mLuaState);
}

lua_State* LuaEngine::getLuaState() {
    return mLuaState;
}

void LuaEngine::resetListener(ScriptExecutorListener* listener) {
    if (sListener != nullptr) {
        delete sListener;
    }
    sListener = listener;
}

int LuaEngine::loadScript(const char* scriptBody) {
    // As the first step in Lua script execution we want to load
    // the body of the script into Lua stack and have it processed by Lua
    // to catch any errors.
    // More on luaL_dostring: https://www.lua.org/manual/5.3/manual.html#lual_dostring
    // If error, pushes the error object into the stack.
    const auto status = luaL_dostring(mLuaState, scriptBody);
    if (status) {
        // Removes error object from the stack.
        // Lua stack must be properly maintained due to its limited size,
        // ~20 elements and its critical function because all interaction with
        // Lua happens via the stack.
        // Starting read about Lua stack: https://www.lua.org/pil/24.2.html
        const char* error = lua_tostring(mLuaState, -1);
        lua_pop(mLuaState, 1);
        std::ostringstream out;
        out << "Error encountered while loading the script. A possible cause could be syntax "
               "errors in the script. Error: "
            << error;
        sListener->onError(ERROR_TYPE_LUA_RUNTIME_ERROR, out.str().c_str(), "");
        return status;
    }

    // Register limited set of reserved methods for Lua to call native side.
    lua_register(mLuaState, "log", LuaEngine::scriptLog);
    lua_register(mLuaState, "on_success", LuaEngine::onSuccess);
    lua_register(mLuaState, "on_script_finished", LuaEngine::onScriptFinished);
    lua_register(mLuaState, "on_error", LuaEngine::onError);
    lua_register(mLuaState, "on_metrics_report", LuaEngine::onMetricsReport);
    return status;
}

int LuaEngine::pushFunction(const char* functionName) {
    // Interaction between native code and Lua happens via Lua stack.
    // In such model, a caller first pushes the name of the function
    // that needs to be called, followed by the function's input
    // arguments, one input value pushed at a time.
    // More info: https://www.lua.org/pil/24.2.html
    lua_getglobal(mLuaState, functionName);
    const auto status = lua_isfunction(mLuaState, /*idx= */ -1);
    if (status == 0) {
        lua_pop(mLuaState, 1);
        std::ostringstream out;
        out << "Wrong function name. Provided functionName=" << functionName
            << " does not correspond to any function in the provided script";
        sListener->onError(ERROR_TYPE_LUA_RUNTIME_ERROR, out.str().c_str(), "");
    }
    return status;
}

int LuaEngine::run() {
    // Performs blocking call of the provided Lua function. Assumes all
    // input arguments are in the Lua stack as well in proper order.
    // On how to call Lua functions: https://www.lua.org/pil/25.2.html
    // Doc on lua_pcall: https://www.lua.org/manual/5.3/manual.html#lua_pcall
    int status = lua_pcall(mLuaState, /* nargs= */ 2, /* nresults= */ 0, /*errfunc= */ 0);
    if (status) {
        const char* error = lua_tostring(mLuaState, -1);
        lua_pop(mLuaState, 1);  // pop the error object from the stack.
        std::ostringstream out;
        out << "Error encountered while running the script. The returned error code=" << status
            << ". Refer to lua.h file of Lua C API library for error code definitions. Error: "
            << error;
        sListener->onError(ERROR_TYPE_LUA_RUNTIME_ERROR, out.str().c_str(), "");
    }
    return status;
}

int LuaEngine::scriptLog(lua_State* lua) {
    const auto n = lua_gettop(lua);
    // Loop through each argument, Lua table indices range from [1 .. N] instead of [0 .. N-1].
    // Negative indexes are stack positions and positive indexes are argument positions.
    for (int i = 1; i <= n; i++) {
        const char* message = lua_tostring(lua, i);
        LOG(INFO) << kLuaLogTag << message;
    }
    return ZERO_RETURNED_RESULTS;
}

int LuaEngine::onSuccess(lua_State* lua) {
    // Any script we run can call on_success only with a single argument of Lua table type.
    if (lua_gettop(lua) != 1 || !lua_istable(lua, /* index =*/-1)) {
        sListener->onError(ERROR_TYPE_LUA_SCRIPT_ERROR,
                           "on_success can push only a single parameter from Lua - a Lua table",
                           "");
        return ZERO_RETURNED_RESULTS;
    }

    // Helper object to create and populate Java PersistableBundle object.
    BundleWrapper bundleWrapper(sListener->getCurrentJNIEnv());
    const auto status = convertLuaTableToBundle(lua, &bundleWrapper);
    if (!status.ok()) {
        sListener->onError(ERROR_TYPE_LUA_SCRIPT_ERROR, status.error().message().c_str(), "");
        // We explicitly must tell Lua how many results we return, which is 0 in this case.
        // More on the topic: https://www.lua.org/manual/5.3/manual.html#lua_CFunction
        return ZERO_RETURNED_RESULTS;
    }

    // Forward the populated Bundle object to Java callback.
    sListener->onSuccess(bundleWrapper.getBundle());

    // We explicitly must tell Lua how many results we return, which is 0 in this case.
    // More on the topic: https://www.lua.org/manual/5.3/manual.html#lua_CFunction
    return ZERO_RETURNED_RESULTS;
}

int LuaEngine::onScriptFinished(lua_State* lua) {
    // Any script we run can call on_success only with a single argument of Lua table type.
    if (lua_gettop(lua) != 1 || !lua_istable(lua, /* index =*/-1)) {
        sListener->onError(ERROR_TYPE_LUA_SCRIPT_ERROR,
                           "on_script_finished can push only a single parameter from Lua - a Lua "
                           "table",
                           "");
        return ZERO_RETURNED_RESULTS;
    }

    // Helper object to create and populate Java PersistableBundle object.
    BundleWrapper bundleWrapper(sListener->getCurrentJNIEnv());
    const auto status = convertLuaTableToBundle(lua, &bundleWrapper);
    if (!status.ok()) {
        sListener->onError(ERROR_TYPE_LUA_SCRIPT_ERROR, status.error().message().c_str(), "");
        // We explicitly must tell Lua how many results we return, which is 0 in this case.
        // More on the topic: https://www.lua.org/manual/5.3/manual.html#lua_CFunction
        return ZERO_RETURNED_RESULTS;
    }

    // Forward the populated Bundle object to Java callback.
    sListener->onScriptFinished(bundleWrapper.getBundle());

    // We explicitly must tell Lua how many results we return, which is 0 in this case.
    // More on the topic: https://www.lua.org/manual/5.3/manual.html#lua_CFunction
    return ZERO_RETURNED_RESULTS;
}

int LuaEngine::onError(lua_State* lua) {
    // Any script we run can call on_error only with a single argument of Lua string type.
    if (lua_gettop(lua) != 1 || !lua_isstring(lua, /* index = */ -1)) {
        sListener->onError(ERROR_TYPE_LUA_SCRIPT_ERROR,
                           "on_error can push only a single string parameter from Lua", "");
        return ZERO_RETURNED_RESULTS;
    }
    sListener->onError(ERROR_TYPE_LUA_SCRIPT_ERROR, lua_tostring(lua, /* index = */ -1),
                       /* stackTrace =*/"");
    return ZERO_RETURNED_RESULTS;
}

int LuaEngine::onMetricsReport(lua_State* lua) {
    // Any script we run can call on_metrics_report with at most 2 arguments of Lua table type.
    if (lua_gettop(lua) > 2 || !lua_istable(lua, /* index =*/-1)) {
        sListener->onError(ERROR_TYPE_LUA_SCRIPT_ERROR,
                           "on_metrics_report should push 1 to 2 parameters of Lua table type. "
                           "The first table is a metrics report and the second is an optional "
                           "state to save",
                           "");
        return ZERO_RETURNED_RESULTS;
    }

    // stack with 2 items:                      stack with 1 item:
    //     index -1: state_to_persist               index -1: report
    //     index -2: report
    // If the stack has 2 items, top of the stack is the state.
    // If the stack only has one item, top of the stack is the report.

    // Process the top of the stack. Create helper object and populate Java PersistableBundle
    // object.
    BundleWrapper topBundleWrapper(sListener->getCurrentJNIEnv());
    // If the helper function succeeds, it should not change the stack
    const auto status = convertLuaTableToBundle(lua, &topBundleWrapper);
    if (!status.ok()) {
        sListener->onError(ERROR_TYPE_LUA_SCRIPT_ERROR, status.error().message().c_str(), "");
        // We explicitly must tell Lua how many results we return, which is 0 in this case.
        // More on the topic: https://www.lua.org/manual/5.3/manual.html#lua_CFunction
        return ZERO_RETURNED_RESULTS;
    }

    // If the script provided 1 argument, return now
    if (lua_gettop(lua) == 1) {
        sListener->onMetricsReport(topBundleWrapper.getBundle(), nullptr);
        return ZERO_RETURNED_RESULTS;
    }

    // Otherwise the script provided a report and a state
    // pop the state_to_persist because it has already been processed in topBundleWrapper
    lua_pop(lua, 1);

    // check that the second argument is also a table
    if (!lua_istable(lua, /* index =*/-1)) {
        sListener->onError(ERROR_TYPE_LUA_SCRIPT_ERROR,
                           "on_metrics_report should push 1 to 2 parameters of Lua table type. "
                           "The first table is a metrics report and the second is an optional "
                           "state to save",
                           "");
        return ZERO_RETURNED_RESULTS;
    }

    // process the report
    BundleWrapper bottomBundleWrapper(sListener->getCurrentJNIEnv());
    const auto statusBottom = convertLuaTableToBundle(lua, &bottomBundleWrapper);
    if (!statusBottom.ok()) {
        sListener->onError(ERROR_TYPE_LUA_SCRIPT_ERROR, statusBottom.error().message().c_str(), "");
        // We explicitly must tell Lua how many results we return, which is 0 in this case.
        // More on the topic: https://www.lua.org/manual/5.3/manual.html#lua_CFunction
        return ZERO_RETURNED_RESULTS;
    }

    // Top of the stack = state, bottom of the stack = report
    sListener->onMetricsReport(bottomBundleWrapper.getBundle(), topBundleWrapper.getBundle());

    // We explicitly must tell Lua how many results we return, which is 0 in this case.
    // More on the topic: https://www.lua.org/manual/5.3/manual.html#lua_CFunction
    return ZERO_RETURNED_RESULTS;
}

}  // namespace scriptexecutor
}  // namespace car
}  // namespace android
}  // namespace com
