/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tradefed.invoker;

import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.result.ITestInvocationListener;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;

/** Unit tests for {@link ShardListener}. */
@RunWith(JUnit4.class)
public class ShardListenerTest {
    private ShardListener mShardListener;
    private ITestInvocationListener mMockListener;
    private IInvocationContext mContext;
    private ITestDevice mMockDevice;

    @Before
    public void setUp() {
        mMockListener = EasyMock.createMock(ITestInvocationListener.class);
        mShardListener = new ShardListener(mMockListener);
        mMockDevice = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(mMockDevice.getSerialNumber()).andStubReturn("serial");
        mContext = new InvocationContext();
        mContext.addDeviceBuildInfo("default", new BuildInfo());
        mContext.addAllocatedDevice("default", mMockDevice);
    }

    /** Ensure that all the events given to the shardlistener are replayed on invocationEnded. */
    @Test
    public void testBufferAndReplay() {
        mMockListener.invocationStarted(mContext);
        mMockListener.testRunStarted("run1", 1);
        TestIdentifier tid = new TestIdentifier("class1", "name1");
        mMockListener.testStarted(tid, 0l);
        mMockListener.testEnded(tid, 0l, Collections.emptyMap());
        mMockListener.testRunEnded(0l, Collections.emptyMap());
        mMockListener.invocationEnded(0l);

        EasyMock.replay(mMockListener, mMockDevice);
        mShardListener.invocationStarted(mContext);
        mShardListener.testRunStarted("run1", 1);
        mShardListener.testStarted(tid, 0l);
        mShardListener.testEnded(tid, 0l, Collections.emptyMap());
        mShardListener.testRunEnded(0l, Collections.emptyMap());
        mShardListener.invocationEnded(0l);
        EasyMock.verify(mMockListener, mMockDevice);
    }

    /** Test that the buffering of events is properly done in respect to the modules too. */
    @Test
    public void testBufferAndReplay_withModule() {
        IInvocationContext module1 = new InvocationContext();
        IInvocationContext module2 = new InvocationContext();
        mMockListener.invocationStarted(mContext);
        mMockListener.testModuleStarted(module1);
        mMockListener.testRunStarted("run1", 1);
        TestIdentifier tid = new TestIdentifier("class1", "name1");
        mMockListener.testStarted(tid, 0l);
        mMockListener.testEnded(tid, 0l, Collections.emptyMap());
        mMockListener.testRunEnded(0l, Collections.emptyMap());
        mMockListener.testRunStarted("run2", 1);
        mMockListener.testStarted(tid, 0l);
        mMockListener.testEnded(tid, 0l, Collections.emptyMap());
        mMockListener.testRunEnded(0l, Collections.emptyMap());
        mMockListener.testModuleEnded();
        // expectation on second module
        mMockListener.testModuleStarted(module2);
        mMockListener.testRunStarted("run3", 1);
        mMockListener.testStarted(tid, 0l);
        mMockListener.testEnded(tid, 0l, Collections.emptyMap());
        mMockListener.testRunEnded(0l, Collections.emptyMap());
        mMockListener.testModuleEnded();
        mMockListener.invocationEnded(0l);

        EasyMock.replay(mMockListener, mMockDevice);
        mShardListener.invocationStarted(mContext);
        // 1st module
        mShardListener.testModuleStarted(module1);
        mShardListener.testRunStarted("run1", 1);
        mShardListener.testStarted(tid, 0l);
        mShardListener.testEnded(tid, 0l, Collections.emptyMap());
        mShardListener.testRunEnded(0l, Collections.emptyMap());
        mShardListener.testRunStarted("run2", 1);
        mShardListener.testStarted(tid, 0l);
        mShardListener.testEnded(tid, 0l, Collections.emptyMap());
        mShardListener.testRunEnded(0l, Collections.emptyMap());
        mShardListener.testModuleEnded();
        // 2nd module
        mShardListener.testModuleStarted(module2);
        mShardListener.testRunStarted("run3", 1);
        mShardListener.testStarted(tid, 0l);
        mShardListener.testEnded(tid, 0l, Collections.emptyMap());
        mShardListener.testRunEnded(0l, Collections.emptyMap());
        mShardListener.testModuleEnded();

        mShardListener.invocationEnded(0l);
        EasyMock.verify(mMockListener, mMockDevice);
    }
}
