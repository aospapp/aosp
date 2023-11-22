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

import static org.junit.Assert.assertEquals;

import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InvocationsMonitor}. */
@RunWith(JUnit4.class)
public class InvocationsMonitorTest {

    private InvocationsMonitor mMonitor;

    @Before
    public void setUp() {
        mMonitor = new InvocationsMonitor();
    }

    @Test
    public void testTrackInvocation() {
        assertEquals(0, mMonitor.getInvocations().size());
        IInvocationContext initialContext = new InvocationContext();
        initialContext.addInvocationAttribute(IInvocationContext.INVOCATION_ID, "123");
        mMonitor.trackInvocation(initialContext);
        assertEquals(1, mMonitor.getInvocations().size());
        mMonitor.untrackInvocation(initialContext);
        assertEquals(0, mMonitor.getInvocations().size());
    }

    @Test
    public void testTrackInvocation_sharding() {
        assertEquals(0, mMonitor.getInvocations().size());
        IInvocationContext initialContext = new InvocationContext();
        initialContext.addInvocationAttribute(IInvocationContext.INVOCATION_ID, "123");
        mMonitor.trackInvocation(initialContext);
        assertEquals(1, mMonitor.getInvocations().size());
        // Invocation get sharded
        mMonitor.notifyLocalSharding("123", 2);

        IInvocationContext shard1 = new InvocationContext();
        shard1.addInvocationAttribute(IInvocationContext.INVOCATION_ID, "123");
        mMonitor.trackInvocation(shard1);
        IInvocationContext shard2 = new InvocationContext();
        shard2.addInvocationAttribute(IInvocationContext.INVOCATION_ID, "123");
        mMonitor.trackInvocation(shard2);

        mMonitor.untrackInvocation(initialContext);
        // Shards are running so we don't stop tracking the invocation yet.
        assertEquals(1, mMonitor.getInvocations().size());

        mMonitor.untrackInvocation(shard1);
        assertEquals(1, mMonitor.getInvocations().size());
        // When last shard is done, then we stop tracking the invocation
        mMonitor.untrackInvocation(shard2);
        assertEquals(0, mMonitor.getInvocations().size());
    }
}
