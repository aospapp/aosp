/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wifi;

import android.annotation.NonNull;
import android.os.Message;
import android.os.Trace;
import android.util.LocalLog;

import com.android.internal.util.State;

/**
 * RunnerState class is a wrapper based on State class to monitor and track the State enter/exit
 * and message handler execution for taking longer time than the expected threshold.
 * User must extend the RunnerState class instead of State, and provide the implementation of:
 * { @link RunnerState#enterImpl() } { @link RunnerState#exitImpl() }
 * { @link RunnerState#processMessageImpl() }
 * { @link RunnerState#getMessageLogRec() }
 *
 */
public abstract class RunnerState extends State {
    private static final String TAG = "RunnerState";

    /** Message.what value when entering */
    public static final int STATE_ENTER_CMD = -3;

    /** Message.what value when exiting */
    public static final int STATE_EXIT_CMD = -4;

    private final int mRunningTimeThresholdInMilliseconds;
    // TODO: b/246623192 Add Wifi metric for Runner state overruns.
    private final LocalLog mLocalLog;

    /**
     * The Runner state Constructor
     * @param threshold the running time threshold in milliseconds
     */
    RunnerState(int threshold, @NonNull LocalLog localLog) {
        mRunningTimeThresholdInMilliseconds = threshold;
        mLocalLog = localLog;
    }

    @Override
    public boolean processMessage(Message message) {
        Long startTime = System.currentTimeMillis();

        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, getMessageLogRec(message.what));
        boolean ret = processMessageImpl(message);
        Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        Long runTime = System.currentTimeMillis() - startTime;
        if (runTime > mRunningTimeThresholdInMilliseconds) {
            mLocalLog.log(getMessageLogRec(message.what) + " was running for " + runTime + " ms");
        }
        return ret;
    }

    @Override
    public void enter() {
        Long startTime = System.currentTimeMillis();
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, getMessageLogRec(STATE_ENTER_CMD));
        enterImpl();
        Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        Long runTime = System.currentTimeMillis() - startTime;
        if (runTime > mRunningTimeThresholdInMilliseconds) {
            mLocalLog.log(
                    getMessageLogRec(STATE_ENTER_CMD) + " was running for " + runTime + " ms");
        }
    }

    @Override
    public void exit() {
        Long startTime = System.currentTimeMillis();
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, getMessageLogRec(STATE_EXIT_CMD));
        exitImpl();
        Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        Long runTime = System.currentTimeMillis() - startTime;
        if (runTime > mRunningTimeThresholdInMilliseconds) {
            mLocalLog.log(getMessageLogRec(STATE_EXIT_CMD) + " was running for " + runTime + " ms");
        }
    }

    /**
     * Implement this method for State enter process, instead of enter()
     */
    abstract void enterImpl();

    /**
     * Implement this method for State exit process, instead of exit()
     */
    abstract void exitImpl();

    /**
     * Implement this method for State message processing, instead of processMessage()
     */
    abstract boolean processMessageImpl(Message message);

    /**
     * Implement this to translate a message `what` into a readable String
     * @param what message 'what' field
     * @return Readable string
     */
    abstract String getMessageLogRec(int what);
}
