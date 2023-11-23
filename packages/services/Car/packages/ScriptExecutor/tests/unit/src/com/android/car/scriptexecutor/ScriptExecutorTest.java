/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.scriptexecutor;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.UserHandle;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.telemetry.scriptexecutorinterface.IScriptExecutor;
import com.android.car.telemetry.scriptexecutorinterface.IScriptExecutorListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public final class ScriptExecutorTest {

    private IScriptExecutor mScriptExecutor;
    private ScriptExecutor mInstance;
    private Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();


    private static final class ScriptExecutorListener extends IScriptExecutorListener.Stub {
        public PersistableBundle mSavedBundle;
        public PersistableBundle mFinalResult;
        public int mErrorType;
        public String mMessage;
        public String mStackTrace;
        public final CountDownLatch mResponseLatch = new CountDownLatch(1);

        @Override
        public void onScriptFinished(PersistableBundle result) {
            mFinalResult = result;
            mResponseLatch.countDown();
        }

        @Override
        public void onSuccess(PersistableBundle stateToPersist) {
            mSavedBundle = stateToPersist;
            mResponseLatch.countDown();
        }

        @Override
        public void onError(int errorType, String message, String stackTrace) {
            mErrorType = errorType;
            mMessage = message;
            mStackTrace = stackTrace;
            mResponseLatch.countDown();
        }
    }

    private final ScriptExecutorListener mFakeScriptExecutorListener =
            new ScriptExecutorListener();

    private final PersistableBundle mPublishedData = new PersistableBundle();
    private final PersistableBundle mSavedState = new PersistableBundle();

    private final CountDownLatch mBindLatch = new CountDownLatch(1);

    private static final int BIND_SERVICE_TIMEOUT_SEC = 5;
    private static final int SCRIPT_PROCESSING_TIMEOUT_SEC = 10;


    private final ServiceConnection mScriptExecutorConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName className, IBinder service) {
                    mScriptExecutor = IScriptExecutor.Stub.asInterface(service);
                    mBindLatch.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName className) {
                    fail("Service unexpectedly disconnected");
                }
            };

    // Helper method to invoke the script and wait for it to complete and return a response.
    private void runScriptAndWaitForResponse(String script, String function,
            PersistableBundle publishedData, PersistableBundle previousState)
            throws RemoteException {
        mScriptExecutor.invokeScript(script, function, publishedData, previousState,
                mFakeScriptExecutorListener);
        try {
            if (!mFakeScriptExecutorListener.mResponseLatch.await(SCRIPT_PROCESSING_TIMEOUT_SEC,
                    TimeUnit.SECONDS)) {
                fail("Failed to get the callback method called by the script on time");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    private void runScriptAndWaitForError(String script, String function) throws RemoteException {
        runScriptAndWaitForResponse(script, function, new PersistableBundle(),
                new PersistableBundle());
    }

    @Before
    public void setUp() throws InterruptedException {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.android.car.scriptexecutor",
                "com.android.car.scriptexecutor.ScriptExecutor"));
        mContext.bindServiceAsUser(intent, mScriptExecutorConnection, Context.BIND_AUTO_CREATE,
                UserHandle.SYSTEM);
        if (!mBindLatch.await(BIND_SERVICE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            fail("Failed to bind to ScriptExecutor service");
        }
    }

    @Test
    public void invokeScript_returnsResult() throws RemoteException {
        String returnResultScript =
                "function hello(data, state)\n"
                        + "    result = {hello=\"world\"}\n"
                        + "    on_success(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(returnResultScript, "hello", mPublishedData, mSavedState);

        // Expect to get back a bundle with a single string key: string value pair:
        // {"hello": "world"}
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getString("hello")).isEqualTo(
                "world");
    }

    @Test
    public void invokeScript_allSupportedPrimitiveTypes() throws RemoteException {
        String script =
                "function knows(data, state)\n"
                        + "    result = {string=\"hello\", boolean=true, integer=1, number=1.1}\n"
                        + "    on_success(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "knows", mPublishedData, mSavedState);

        // Expect to get back a bundle with 4 keys, each corresponding to a distinct supported type.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(4);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getString("string")).isEqualTo(
                "hello");
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getBoolean("boolean")).isEqualTo(true);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getLong("integer")).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getDouble("number")).isEqualTo(1.1);
    }

    @Test
    public void invokeScript_skipsUnsupportedNestedTables() throws RemoteException {
        String script =
                "function nested(data, state)\n"
                        + "    result = {string=\"hello\", boolean=true, integer=1, number=1.1}\n"
                        + "    result.nested_table = {x=0, y=0}\n"
                        + "    on_success(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "nested", mPublishedData, mSavedState);

        // Verify that expected error is received.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).contains(
                "nested tables are not supported");
    }

    @Test
    public void invokeScript_emptyBundle() throws RemoteException {
        String script =
                "function empty(data, state)\n"
                        + "    result = {}\n"
                        + "    on_success(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "empty", mPublishedData, mSavedState);

        // If a script returns empty table as the result, we get an empty bundle.
        assertThat(mFakeScriptExecutorListener.mSavedBundle).isNotNull();
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(0);
    }

    @Test
    public void invokeScript_processPreviousStateAndReturnResult() throws RemoteException {
        // Here we verify that the script actually processes provided state from a previous run
        // and makes calculation based on that and returns the result.
        String script =
                "function update(data, state)\n"
                        + "    result = {y = state.x+1}\n"
                        + "    on_success(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        previousState.putInt("x", 1);

        runScriptAndWaitForResponse(script, "update", mPublishedData, previousState);

        // Verify that y = 2, because y = x + 1 and x = 1.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getLong("y")).isEqualTo(2);
    }

    @Test
    public void invokeScript_allSupportedPrimitiveTypesWorkRoundTripWithKeyNamesPreserved()
            throws RemoteException {
        // Here we verify that all supported primitive types in supplied previous state Bundle
        // are interpreted by the script as expected.
        String script =
                "function update_all(data, state)\n"
                        + "    result = {}\n"
                        + "    result.integer = state.integer + 1\n"
                        + "    result.number = state.number + 0.1\n"
                        + "    result.boolean = not state.boolean\n"
                        + "    result.string = state.string .. \"CADABRA\"\n"
                        + "    on_success(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        previousState.putInt("integer", 1);
        previousState.putDouble("number", 0.1);
        previousState.putBoolean("boolean", false);
        previousState.putString("string", "ABRA");

        runScriptAndWaitForResponse(script, "update_all", mPublishedData, previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(4);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getLong("integer")).isEqualTo(2);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getDouble("number")).isEqualTo(0.2);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getBoolean("boolean")).isEqualTo(true);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getString("string")).isEqualTo(
                "ABRACADABRA");
    }

    @Test
    public void invokeScript_allSupportedArrayTypesWorkRoundTripWithKeyNamesPreserved()
            throws RemoteException {
        // Here we verify that all supported array types in supplied previous state Bundle are
        // interpreted by the script as expected.
        String script =
                "function arrays(data, state)\n"
                        + "    result = {}\n"
                        + "    result.int_array = state.int_array\n"
                        + "    result.long_array = state.long_array\n"
                        + "    result.string_array = state.string_array\n"
                        + "    on_success(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        int[] int_array = new int[]{1, 2};
        long[] int_array_in_long = new long[]{1, 2};
        long[] long_array = new long[]{1, 2, 3};
        String[] string_array = new String[]{"one", "two", "three"};
        previousState.putIntArray("int_array", int_array);
        previousState.putLongArray("long_array", long_array);
        previousState.putStringArray("string_array", string_array);

        runScriptAndWaitForResponse(script, "arrays", mPublishedData, previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(3);
        // Lua has only one lua_Integer. Here Java long is used to represent it when data is
        // transferred from Lua to CarTelemetryService.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getLongArray("int_array")).isEqualTo(
                int_array_in_long);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getLongArray("long_array")).isEqualTo(
                long_array);
        assertThat(
                mFakeScriptExecutorListener.mSavedBundle.getStringArray("string_array")).isEqualTo(
                string_array);
    }

    @Test
    public void invokeScript_modifiesArray()
            throws RemoteException {
        // Verify that an array modified by a script is properly sent back by the callback.
        String script =
                "function modify_array(data, state)\n"
                        + "    result = {}\n"
                        + "    result.long_array = state.long_array\n"
                        + "    result.long_array[2] = 100\n"
                        + "    on_success(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        long[] long_array = new long[]{1, 2, 3};
        previousState.putLongArray("long_array", long_array);
        long[] expected_array = new long[]{1, 100, 3};

        runScriptAndWaitForResponse(script, "modify_array", mPublishedData, previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getLongArray("long_array")).isEqualTo(
                expected_array);
    }

    @Test
    public void invokeScript_processesStringArray()
            throws RemoteException {
        // Verify that an array modified by a script is properly sent back by the callback.
        String script =
                "function process_string_array(data, state)\n"
                        + "    result = {}\n"
                        + "    result.answer = state.string_array[1] .. state.string_array[2]\n"
                        + "    on_success(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        String[] string_array = new String[]{"Hello ", "world!"};
        previousState.putStringArray("string_array", string_array);

        runScriptAndWaitForResponse(script, "process_string_array", mPublishedData, previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getString("answer")).isEqualTo(
                "Hello world!");
    }

    @Test
    public void invokeScript_arraysWithLengthAboveLimitCauseError()
            throws RemoteException {
        // Verifies that arrays pushed by Lua that have their size over the limit cause error.
        String script =
                "function size_limit(data, state)\n"
                        + "    result = {}\n"
                        + "    result.huge_array = {}\n"
                        + "    for i=1, 10000 do\n"
                        + "        result.huge_array[i]=i\n"
                        + "    end\n"
                        + "    on_success(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "size_limit", mPublishedData, mSavedState);

        // Verify that expected error is received.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "Returned table huge_array exceeds maximum allowed size of 1000 "
                        + "elements. This key-value cannot be unpacked successfully. This error "
                        + "is unrecoverable.");
    }

    @Test
    public void invokeScript_arrayContainingVaryingTypesCausesError()
            throws RemoteException {
        // Verifies that values in returned array must be the same integer type.
        // For example string values in a Lua array are not allowed.
        String script =
                "function table_with_numbers_and_strings(data, state)\n"
                        + "    result = {}\n"
                        + "    result.mixed_array = state.long_array\n"
                        + "    result.mixed_array[2] = 'a'\n"
                        + "    on_success(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        long[] long_array = new long[]{1, 2, 3};
        previousState.putLongArray("long_array", long_array);

        runScriptAndWaitForResponse(script, "table_with_numbers_and_strings", mPublishedData,
                previousState);

        // Verify that expected error is received.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).contains(
                "Returned Lua arrays must have elements of the same type.");
    }

    @Test
    public void invokeScript_InTablesWithBothKeysAndIndicesCopiesOnlyIndexedData()
            throws RemoteException {
        // Documents the current behavior that copies only indexed values in a Lua table that
        // contains both keyed and indexed data.
        String script =
                "function keys_and_indices(data, state)\n"
                        + "    result = {}\n"
                        + "    result.mixed_array = state.long_array\n"
                        + "    result.mixed_array['a'] = 130\n"
                        + "    on_success(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        long[] long_array = new long[]{1, 2, 3};
        previousState.putLongArray("long_array", long_array);

        runScriptAndWaitForResponse(script, "keys_and_indices", mPublishedData, previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getLongArray("mixed_array")).isEqualTo(
                long_array);
    }

    @Test
    public void invokeScript_noLuaBufferOverflowForLargeInputArrays() throws RemoteException {
        // Tests that arrays with length that exceed internal Lua buffer size of 20 elements
        // do not cause buffer overflow and are handled properly.
        String script =
                "function large_input_array(data, state)\n"
                        + "    sum = 0\n"
                        + "    for _, val in ipairs(state.long_array) do\n"
                        + "        sum = sum + val\n"
                        + "    end\n"
                        + "    result = {total = sum}\n"
                        + "    on_success(result)\n"
                        + "end\n";

        PersistableBundle previousState = new PersistableBundle();
        int n = 10000;
        long[] longArray = new long[n];
        for (int i = 0; i < n; i++) {
            longArray[i] = i;
        }
        previousState.putLongArray("long_array", longArray);
        long expected_sum =
                (longArray[0] + longArray[n - 1]) * n / 2; // sum of an arithmetic sequence.

        runScriptAndWaitForResponse(script, "large_input_array", mPublishedData, previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getLong("total")).isEqualTo(
                expected_sum);
    }

    @Test
    public void invokeScript_scriptCallsOnError() throws RemoteException {
        String script =
                "function calls_on_error()\n"
                        + "    if 1 ~= 2 then\n"
                        + "        on_error(\"one is not equal to two\")\n"
                        + "        return\n"
                        + "    end\n"
                        + "end\n";

        runScriptAndWaitForError(script, "calls_on_error");

        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo("one is not equal to two");
    }

    @Test
    public void invokeScript_tooManyParametersInOnError() throws RemoteException {
        String script =
                "function too_many_params_in_on_error()\n"
                        + "    if 1 ~= 2 then\n"
                        + "        on_error(\"param1\", \"param2\")\n"
                        + "        return\n"
                        + "    end\n"
                        + "end\n";

        runScriptAndWaitForError(script, "too_many_params_in_on_error");

        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "on_error can push only a single string parameter from Lua");
    }

    @Test
    public void invokeScript_onErrorOnlyAcceptsString() throws RemoteException {
        String script =
                "function only_string()\n"
                        + "    if 1 ~= 2 then\n"
                        + "        on_error(false)\n"
                        + "        return\n"
                        + "    end\n"
                        + "end\n";

        runScriptAndWaitForError(script, "only_string");

        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "on_error can push only a single string parameter from Lua");
    }

    @Test
    public void invokeScript_returnsFinalResult() throws RemoteException {
        String returnFinalResultScript =
                "function script_finishes(data, state)\n"
                        + "    result = {data = state.input + 1}\n"
                        + "    on_script_finished(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        previousState.putInt("input", 1);

        runScriptAndWaitForResponse(returnFinalResultScript, "script_finishes", mPublishedData,
                previousState);

        // Expect to get back a bundle with a single key-value pair {"data": 2}
        // because data = state.input + 1 as in the script body above.
        assertThat(mFakeScriptExecutorListener.mFinalResult.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getLong("data")).isEqualTo(2);
    }

    @Test
    public void invokeScript_emptyStringValueIsValidValue() throws RemoteException {
        // Verify that an empty string value is a valid value to be returned from a script.
        String returnFinalResultScript =
                "function empty_string(data, state)\n"
                        + "    result = {data = \"\"}\n"
                        + "    on_script_finished(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(returnFinalResultScript, "empty_string", mPublishedData,
                new PersistableBundle());

        // Expect to get back a bundle with a single key-value pair {"data": ""}
        assertThat(mFakeScriptExecutorListener.mFinalResult.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getString("data")).isEqualTo("");
    }

    @Test
    public void invokeScript_allPrimitiveSupportedTypesForReturningFinalResult()
            throws RemoteException {
        // Here we verify that all supported primitive types are present in the returned final
        // result bundle are present.
        String script =
                "function finalize_all(data, state)\n"
                        + "    result = {}\n"
                        + "    result.integer = state.integer + 1\n"
                        + "    result.number = state.number + 0.1\n"
                        + "    result.boolean = not state.boolean\n"
                        + "    result.string = state.string .. \"CADABRA\"\n"
                        + "    on_script_finished(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        previousState.putInt("integer", 1);
        previousState.putDouble("number", 0.1);
        previousState.putBoolean("boolean", false);
        previousState.putString("string", "ABRA");

        runScriptAndWaitForResponse(script, "finalize_all", mPublishedData, previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mFinalResult.size()).isEqualTo(4);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getLong("integer")).isEqualTo(2);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getDouble("number")).isEqualTo(0.2);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getBoolean("boolean")).isEqualTo(true);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getString("string")).isEqualTo(
                "ABRACADABRA");
    }

    @Test
    public void invokeScript_emptyFinalResultBundle() throws RemoteException {
        String script =
                "function empty_final_result(data, state)\n"
                        + "    result = {}\n"
                        + "    on_script_finished(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "empty_final_result", mPublishedData, mSavedState);

        // If a script returns empty table as the final result, we get an empty bundle.
        assertThat(mFakeScriptExecutorListener.mFinalResult).isNotNull();
        assertThat(mFakeScriptExecutorListener.mFinalResult.size()).isEqualTo(0);
    }

    @Test
    public void invokeScript_wrongNumberOfCallbackInputsInOnScriptFinished()
            throws RemoteException {
        String script =
                "function wrong_number_of_outputs_in_on_script_finished(data, state)\n"
                        + "    result = {}\n"
                        + "    extra = 1\n"
                        + "    on_script_finished(result, extra)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "wrong_number_of_outputs_in_on_script_finished",
                mPublishedData, mSavedState);

        // We expect to get an error here because we expect only 1 input parameter in
        // on_script_finished.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "on_script_finished can push only a single parameter from Lua - a Lua table");
    }

    @Test
    public void invokeScript_wrongNumberOfCallbackInputsInOnSuccess() throws RemoteException {
        String script =
                "function wrong_number_of_outputs_in_on_success(data, state)\n"
                        + "    result = {}\n"
                        + "    extra = 1\n"
                        + "    on_success(result, extra)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "wrong_number_of_outputs_in_on_success",
                mPublishedData, mSavedState);

        // We expect to get an error here because we expect only 1 input parameter in on_success.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "on_success can push only a single parameter from Lua - a Lua table");
    }

    @Test
    public void invokeScript_wrongTypeInOnSuccess() throws RemoteException {
        String script =
                "function wrong_type_in_on_success(data, state)\n"
                        + "    result = 1\n"
                        + "    on_success(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "wrong_type_in_on_success",
                mPublishedData, mSavedState);

        // We expect to get an error here because the type of the input parameter for on_success
        // must be a Lua table.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "on_success can push only a single parameter from Lua - a Lua table");
    }

    @Test
    public void invokeScript_wrongTypeInOnScriptFinished() throws RemoteException {
        String script =
                "function wrong_type_in_on_script_finished(data, state)\n"
                        + "    result = 1\n"
                        + "    on_success(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "wrong_type_in_on_script_finished",
                mPublishedData, mSavedState);

        // We expect to get an error here because the type of the input parameter for
        // on_script_finished must be a Lua table.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "on_success can push only a single parameter from Lua - a Lua table");
    }

    @Test
    public void invokeScriptLargeInput_largePublishedData() throws Exception {
        // Verifies that large input does not overwhelm Binder's buffer because pipes are used
        // instead.
        String script =
                "function large_published_data(data, state)\n"
                        + "    sum = 0\n"
                        + "    for _, val in ipairs(data.array) do\n"
                        + "        sum = sum + val\n"
                        + "    end\n"
                        + "    result = {total = sum}\n"
                        + "    on_script_finished(result)\n"
                        + "end\n";

        ParcelFileDescriptor[] fds = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor writeFd = fds[1];
        ParcelFileDescriptor readFd = fds[0];

        PersistableBundle bundle = new PersistableBundle();
        int n = 1 << 20; // 1024 * 1024 values, roughly 1 Million.
        long[] array8Mb = new long[n];
        for (int i = 0; i < n; i++) {
            array8Mb[i] = i;
        }
        bundle.putLongArray("array", array8Mb);
        long expectedSum =
                (array8Mb[0] + array8Mb[n - 1]) * n / 2; // sum of an arithmetic sequence.

        mScriptExecutor.invokeScriptForLargeInput(script, "large_published_data", readFd,
                mSavedState,
                mFakeScriptExecutorListener);

        readFd.close();
        try (OutputStream outputStream = new ParcelFileDescriptor.AutoCloseOutputStream(writeFd)) {
            bundle.writeToStream(outputStream);
        }

        boolean gotResponse = mFakeScriptExecutorListener.mResponseLatch.await(
                SCRIPT_PROCESSING_TIMEOUT_SEC,
                TimeUnit.SECONDS);

        assertWithMessage("Failed to get the callback method called by the script on time")
                .that(gotResponse).isTrue();
        assertThat(mFakeScriptExecutorListener.mFinalResult.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getLong("total"))
                .isEqualTo(expectedSum);
    }

    @Test
    public void invokeScript_bothPublishedDataAndPreviousStateAreProvided() throws RemoteException {
        // Verifies that both published data and previous state PersistableBundles
        // are piped into script.
        String script =
                "function data_and_state(data, state)\n"
                        + "    result = {answer = data.a .. data.b .. state.c .. state.d}\n"
                        + "    on_script_finished(result)\n"
                        + "end\n";

        PersistableBundle publishedData = new PersistableBundle();
        publishedData.putString("a", "A");
        publishedData.putString("b", "B");

        PersistableBundle previousState = new PersistableBundle();
        previousState.putString("c", "C");
        previousState.putString("d", "D");

        runScriptAndWaitForResponse(script, "data_and_state", publishedData, previousState);

        // Lua script combines both input published data and previous state into a single result.
        assertThat(mFakeScriptExecutorListener.mFinalResult).isNotNull();
        assertThat(mFakeScriptExecutorListener.mFinalResult.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getString("answer")).isEqualTo(
                "ABCD");
    }

    @Test
    public void invokeScript_outputIntAndLongAreTreatedAsLong() throws RemoteException {
        // Verifies that we treat output both integer and long as long integer type although we
        // distinguish between int and long in the script input.
        String script =
                "function int_and_long_are_output_long(data, state)\n"
                        + "    result = {int = data.int, long = state.long}\n"
                        + "    on_script_finished(result)\n"
                        + "end\n";

        PersistableBundle publishedData = new PersistableBundle();
        publishedData.putInt("int", 100);

        PersistableBundle previousState = new PersistableBundle();
        previousState.putLong("long", 200);

        runScriptAndWaitForResponse(script, "int_and_long_are_output_long",
                publishedData, previousState);

        // If a script returns empty table as the final result, we get an empty bundle.
        assertThat(mFakeScriptExecutorListener.mFinalResult).isNotNull();
        assertThat(mFakeScriptExecutorListener.mFinalResult.size()).isEqualTo(2);
        // getInt should always return "empty" value (zero) because all integer types are treated
        // as Java long.
        assertThat(mFakeScriptExecutorListener.mFinalResult.getInt("int")).isEqualTo(0);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getInt("long")).isEqualTo(0);
        // Instead all expected integer values are successfully retrieved using getLong method
        // from the output bundle.
        assertThat(mFakeScriptExecutorListener.mFinalResult.getLong("int")).isEqualTo(100);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getLong("long")).isEqualTo(200);
    }

    @Test
    public void invokeScript_nonUTFCharactersDoNotCauseErrors() throws RemoteException {
        // Tries to create an output string value that does not conform to Modified UTF-8.
        // JNI gracefully handles it by parsing on the string as is.
        String script =
                "function non_utf_key_string(data, state)\n"
                        + "    result = {answer = \"i\0np\200\200ut\"}\n"
                        + "    on_script_finished(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "non_utf_key_string", new PersistableBundle(),
                new PersistableBundle());

        // The output will still have all characters, including those that do not conform to
        // Modified UTF-8.
        assertThat(mFakeScriptExecutorListener.mFinalResult).isNotNull();
        assertThat(mFakeScriptExecutorListener.mFinalResult.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getString("answer")).isEqualTo(
                "i\0np\200\200ut");
    }

    @Test
    public void invokeScript_wrongFunctionNameProvided() throws RemoteException {
        // Verifies that not specifying function name correctly is handled through error callback.
        String script =
                "function correct_function(data, state)\n"
                        + "end\n";

        runScriptAndWaitForError(script, "wrong_function");

        // Verify that the expected error is received.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_RUNTIME_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).contains(
                "Wrong function name");
    }

    @Test
    public void invokeScript_runtimeErrorDueToSyntax() throws RemoteException {
        // Verifies that syntax errors during script loading are handled gracefully.
        String script =
                "function wrong_syntax(data, state)\n"
                        + "    x == 1\n"
                        + "end\n";

        runScriptAndWaitForError(script, "wrong_syntax");

        // Verify that the expected error is received.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_RUNTIME_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).contains(
                "Error encountered while loading the script");
    }

    @Test
    public void invokeScript_runtimeErrorDueToUndefinedMethod() throws RemoteException {
        // Verifies that runtime errors encountered during Lua script execution trigger an error
        // returned via a callback.
        String script =
                "function runtime_error(data, state)\n"
                        + "    on_problem(data, state)\n"
                        + "end\n";

        runScriptAndWaitForError(script, "runtime_error");

        // Verify that the expected error is received.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_RUNTIME_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).contains(
                "Error encountered while running the script");
    }

    @Test
    public void invokeScript_returnedValuesOfUnsupportedTypesReturnError() throws RemoteException {
        // Verifies that if we try to return a value of unsupported type, we get an error instead.
        // In this case, the unsupported type is LUA_TFUNCTION type.
        String script =
                "function function_type(data, state)\n"
                        + "    result = {fn = function_type}\n"
                        + "    on_success(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "function_type", mPublishedData, mSavedState);

        // Verify that the expected error is received.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).contains(
                "has a Lua type=function, which is not supported yet");
    }

    @Test
    public void invokeScript_returnedFloatingArraysNotSupported() throws RemoteException {
        // Verifies that we do not support return values that contain floating number arrays.
        String script =
                "function floating_point_arrays(data, state)\n"
                        + "    array = {}\n"
                        + "    array[0] = 1.1\n"
                        + "    array[1] = 1.2\n"
                        + "    result = {data = array}\n"
                        + "    on_success(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "floating_point_arrays", mPublishedData, mSavedState);

        // Verify that the expected error is received.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).contains(
                "a floating number array, which is not supported yet");
    }

    @Test
    public void invokeScript_returnedBooleanArraysNotSupported() throws RemoteException {
        // Verifies that we do not yet support return values that contain boolean arrays.
        String script =
                "function array_of_booleans(data, state)\n"
                        + "    array = {}\n"
                        + "    array[0] = false\n"
                        + "    array[1] = true\n"
                        + "    result = {data = array}\n"
                        + "    on_success(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "array_of_booleans", mPublishedData, mSavedState);

        // Verify that the expected error is received.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorListener.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).contains(
                "is an array with values of type=boolean, which is not supported yet");
    }
}

