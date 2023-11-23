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
package com.android.tradefed.invoker.monitor;

import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Information stored for a particular invocation (parent and all its shards). */
public final class InvocationTracker {

    /** IInvocationContext and if they are done or not. */
    private Map<IInvocationContext, Boolean> mInvocationContext;

    private int mExpectedInvocation = 1;

    private final InvocationData mInvocationData;

    /**
     * Ctor for the initial creation of the information holder.
     *
     * @param context
     */
    public InvocationTracker(IInvocationContext context) {
        mInvocationData = new InvocationData();
        mInvocationContext =
                Collections.synchronizedMap(new LinkedHashMap<IInvocationContext, Boolean>());
        mInvocationContext.put(context, false);
        mInvocationData.mInvocationId = context.getInvocationId();
    }

    /**
     * Whether or not the {@link InvocationTracker} is tracking a particular {@link
     * IInvocationContext} of an invocation.
     *
     * @param context The {@link IInvocationContext} that we are checking
     * @return True if the {@link IInvocationContext} is tracked.
     */
    public boolean hasInvocation(IInvocationContext context) {
        return mInvocationContext.containsKey(context);
    }

    /** Start tracking an {@link IInvocationContext}. */
    public void addInvocationTracking(IInvocationContext context) {
        mInvocationContext.put(context, false);
    }

    /** Returns the invocation id tracked by the {@link InvocationTracker}. */
    public String getInvocationId() {
        return mInvocationData.mInvocationId;
    }

    /** Notify that an invocation associated with a {@link IInvocationContext} is done running. */
    public synchronized void invocationDone(IInvocationContext context) {
        if (mInvocationContext.containsKey(context)) {
            mInvocationContext.put(context, true);
        } else {
            CLog.e("InvocationInformation wasn't tracking the context as part of the invocation.");
        }
    }

    /**
     * Notify that the invocation tracked is being sharded.
     *
     * @param shardCount The number of shards an invocation is expected to be sharded into.
     */
    public void notifySharding(int shardCount) {
        mExpectedInvocation += shardCount;
    }

    /**
     * Returns True if all the tracked invocation are done (parent and potential shards). returns
     * False otherwise.
     */
    public boolean isAllDone() {
        // If the parent invocation has finished and we haven't got the shard child yet, don't
        // report done.
        if (mInvocationContext.size() < mExpectedInvocation) {
            CLog.d("Not all expected invocation have started yet.");
            return false;
        }
        for (Boolean bool : mInvocationContext.values()) {
            if (!bool) {
                return false;
            }
        }
        return true;
    }
}
