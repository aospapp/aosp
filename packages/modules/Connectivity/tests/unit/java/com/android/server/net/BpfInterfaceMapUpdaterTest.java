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

package com.android.server.net;

import static android.system.OsConstants.EPERM;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.INetd;
import android.net.MacAddress;
import android.os.Build;
import android.os.Handler;
import android.os.test.TestLooper;
import android.system.ErrnoException;
import android.util.IndentingPrintWriter;

import androidx.test.filters.SmallTest;

import com.android.net.module.util.BaseNetdUnsolicitedEventListener;
import com.android.net.module.util.IBpfMap;
import com.android.net.module.util.InterfaceParams;
import com.android.net.module.util.Struct.S32;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.TestBpfMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.PrintWriter;
import java.io.StringWriter;

@SmallTest
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public final class BpfInterfaceMapUpdaterTest {
    private static final int TEST_INDEX = 1;
    private static final int TEST_INDEX2 = 2;
    private static final String TEST_INTERFACE_NAME = "test1";
    private static final String TEST_INTERFACE_NAME2 = "test2";

    private final TestLooper mLooper = new TestLooper();
    private BaseNetdUnsolicitedEventListener mListener;
    private BpfInterfaceMapUpdater mUpdater;
    private IBpfMap<S32, InterfaceMapValue> mBpfMap =
            spy(new TestBpfMap<>(S32.class, InterfaceMapValue.class));
    @Mock private INetd mNetd;
    @Mock private Context mContext;

    private class TestDependencies extends BpfInterfaceMapUpdater.Dependencies {
        @Override
        public IBpfMap<S32, InterfaceMapValue> getInterfaceMap() {
            return mBpfMap;
        }

        @Override
        public InterfaceParams getInterfaceParams(String ifaceName) {
            if (ifaceName.equals(TEST_INTERFACE_NAME)) {
                return new InterfaceParams(TEST_INTERFACE_NAME, TEST_INDEX,
                        MacAddress.ALL_ZEROS_ADDRESS);
            } else if (ifaceName.equals(TEST_INTERFACE_NAME2)) {
                return new InterfaceParams(TEST_INTERFACE_NAME2, TEST_INDEX2,
                        MacAddress.ALL_ZEROS_ADDRESS);
            }

            return null;
        }

        @Override
        public INetd getINetd(Context ctx) {
            return mNetd;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mNetd.interfaceGetList()).thenReturn(new String[] {TEST_INTERFACE_NAME});
        mUpdater = new BpfInterfaceMapUpdater(mContext, new Handler(mLooper.getLooper()),
                new TestDependencies());
    }

    private void verifyStartUpdater() throws Exception {
        mUpdater.start();
        mLooper.dispatchAll();
        final ArgumentCaptor<BaseNetdUnsolicitedEventListener> listenerCaptor =
                ArgumentCaptor.forClass(BaseNetdUnsolicitedEventListener.class);
        verify(mNetd).registerUnsolicitedEventListener(listenerCaptor.capture());
        mListener = listenerCaptor.getValue();
        verify(mBpfMap).updateEntry(eq(new S32(TEST_INDEX)),
                eq(new InterfaceMapValue(TEST_INTERFACE_NAME)));
    }

    @Test
    public void testUpdateInterfaceMap() throws Exception {
        verifyStartUpdater();

        mListener.onInterfaceAdded(TEST_INTERFACE_NAME2);
        mLooper.dispatchAll();
        verify(mBpfMap).updateEntry(eq(new S32(TEST_INDEX2)),
                eq(new InterfaceMapValue(TEST_INTERFACE_NAME2)));

        // Check that when onInterfaceRemoved is called, nothing happens.
        mListener.onInterfaceRemoved(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();
        verifyNoMoreInteractions(mBpfMap);
    }

    @Test
    public void testGetIfNameByIndex() throws Exception {
        mBpfMap.updateEntry(new S32(TEST_INDEX), new InterfaceMapValue(TEST_INTERFACE_NAME));
        assertEquals(TEST_INTERFACE_NAME, mUpdater.getIfNameByIndex(TEST_INDEX));
    }

    @Test
    public void testGetIfNameByIndexNoEntry() {
        assertNull(mUpdater.getIfNameByIndex(TEST_INDEX));
    }

    @Test
    public void testGetIfNameByIndexException() throws Exception {
        doThrow(new ErrnoException("", EPERM)).when(mBpfMap).getValue(new S32(TEST_INDEX));
        assertNull(mUpdater.getIfNameByIndex(TEST_INDEX));
    }

    private void assertDumpContains(final String dump, final String message) {
        assertTrue(String.format("dump(%s) does not contain '%s'", dump, message),
                dump.contains(message));
    }

    private String getDump() {
        final StringWriter sw = new StringWriter();
        mUpdater.dump(new IndentingPrintWriter(new PrintWriter(sw), " "));
        return sw.toString();
    }

    @Test
    public void testDump() throws ErrnoException {
        mBpfMap.updateEntry(new S32(TEST_INDEX), new InterfaceMapValue(TEST_INTERFACE_NAME));
        mBpfMap.updateEntry(new S32(TEST_INDEX2), new InterfaceMapValue(TEST_INTERFACE_NAME2));

        final String dump = getDump();
        assertDumpContains(dump, "IfaceIndexNameMap: OK");
        assertDumpContains(dump, "ifaceIndex=1 ifaceName=test1");
        assertDumpContains(dump, "ifaceIndex=2 ifaceName=test2");
    }
}
