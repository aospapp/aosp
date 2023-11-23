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

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A monitor associated with the Tradefed instance in progress. This monitor is a singleton that
 * monitors ALL invocations.
 *
 * <p>TODO: Complete tracking
 */
public final class InvocationsMonitor {

    public static final String INVOC_MONITOR_TYPE = "invocation_monitor";

    private static InvocationsMonitor sInstance;

    /** Track each invocation by its ID */
    private Map<String, InvocationTracker> mInvocationInfo;

    /** Package-protected Ctor to avoid multi instantiation. */
    @VisibleForTesting
    InvocationsMonitor() {
        mInvocationInfo = Collections.synchronizedMap(new LinkedHashMap<>());
    }

    /** Returns the instance of {@link InvocationsMonitor} for the current running Tradefed. */
    public static InvocationsMonitor getInstance() {
        if (sInstance == null) {
            sInstance = new InvocationsMonitor();
        }
        return sInstance;
    }

    // Invocation level tracking

    /**
     * Start tracking an invocation by its id. Sharded invocation have the same id but different
     * invocation context, so we track each individual context for each shard.
     *
     * @param context The {@link IInvocationContext} of the invocation that finished.
     */
    public synchronized void trackInvocation(IInvocationContext context) {
        String invocationId = context.getInvocationId();
        if (mInvocationInfo.containsKey(invocationId)) {
            InvocationTracker info = mInvocationInfo.get(invocationId);
            info.addInvocationTracking(context);
        } else {
            mInvocationInfo.put(invocationId, new InvocationTracker(context));
        }
    }

    /**
     * Notify of an invocation starting local sharding. This is used to get an expectation of how
     * many invocations we should see.
     *
     * @param id The current invocation id.
     * @param shardCount The shard count the invocation is getting sharded into.
     */
    public synchronized void notifyLocalSharding(String id, int shardCount) {
        if (!mInvocationInfo.containsKey(id)) {
            CLog.e("Untracked invocation reached notifyLocalSharding.");
        }
        InvocationTracker info = mInvocationInfo.get(id);
        info.notifySharding(shardCount);
    }

    /**
     * Stop tracking a particular invocation id. It must be done with all its shard at that point to
     * be released.
     *
     * @param context The {@link IInvocationContext} of the invocation that finished.
     */
    public synchronized void untrackInvocation(IInvocationContext context) {
        String invocationId = context.getInvocationId();
        if (mInvocationInfo.containsKey(invocationId)) {
            InvocationTracker info = mInvocationInfo.get(invocationId);
            if (info.hasInvocation(context)) {
                info.invocationDone(context);
                if (info.isAllDone()) {
                    mInvocationInfo.remove(invocationId);
                }
            } else {
                CLog.e("Invocation id '%s' was not tracking this context.", invocationId);
            }
        } else {
            CLog.e("Untracked invocation reached the end.");
        }
    }

    /** Returns the Set of all the currently running invocation id. */
    public synchronized Set<String> getInvocations() {
        return mInvocationInfo.keySet();
    }
}
