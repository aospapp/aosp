/*
 * Copyright 2023 The Android Open Source Project
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

package android.ondevicepersonalization;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import android.ondevicepersonalization.aidl.IDataAccessService;
import android.ondevicepersonalization.aidl.IDataAccessServiceCallback;
import android.os.Bundle;
import android.os.RemoteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit Tests of LocalDataImpl API.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocalDataTest {
    MutableMap mLocalData;

    @Before
    public void setup() {
        mLocalData = new LocalDataImpl(
                IDataAccessService.Stub.asInterface(
                        new LocalDataService()));
    }

    @Test
    public void testLookupSuccess() throws Exception {
        assertArrayEquals(new byte[] {1, 2, 3}, mLocalData.get("a"));
        assertArrayEquals(new byte[] {7, 8, 9}, mLocalData.get("c"));
        assertNull(mLocalData.get("e"));
    }

    @Test
    public void testLookupError() {
        // Triggers an expected error in the mock service.
        assertThrows(OnDevicePersonalizationException.class, () -> mLocalData.get("z"));
    }

    @Test
    public void testLookupTimeout() {
        // Triggers an expected error in the mock service.
        assertThrows(OnDevicePersonalizationException.class, () -> mLocalData.get("timeout"));
    }

    @Test
    public void testKeysetSuccess() throws OnDevicePersonalizationException {
        Set<String> expectedResult = new HashSet<>();
        expectedResult.add("a");
        expectedResult.add("b");
        expectedResult.add("c");

        assertEquals(expectedResult, mLocalData.keySet());
    }

    @Test
    public void testPutSuccess() throws Exception {
        assertArrayEquals(new byte[] {1, 2, 3}, mLocalData.put("a", new byte[10]));
        assertNull(mLocalData.put("e", new byte[] {1, 2, 3}));
        assertArrayEquals(new byte[] {1, 2, 3}, mLocalData.get("e"));
    }

    @Test
    public void testPutError() {
        // Triggers an expected error in the mock service.
        assertThrows(OnDevicePersonalizationException.class, () -> mLocalData.put("z",
                new byte[10]));
    }

    @Test
    public void testPutTimeout() {
        // Triggers an expected error in the mock service.
        assertThrows(OnDevicePersonalizationException.class, () -> mLocalData.put("timeout",
                new byte[10]));
    }

    @Test
    public void testRemoveSuccess() throws Exception {
        assertArrayEquals(new byte[] {1, 2, 3}, mLocalData.remove("a"));
        assertNull(mLocalData.remove("e"));
        assertNull(mLocalData.get("a"));
    }

    @Test
    public void testRemoveError() {
        // Triggers an expected error in the mock service.
        assertThrows(OnDevicePersonalizationException.class, () -> mLocalData.remove("z"));
    }

    @Test
    public void testRemoveTimeout() {
        // Triggers an expected error in the mock service.
        assertThrows(OnDevicePersonalizationException.class, () -> mLocalData.remove("timeout"));
    }


    public static class LocalDataService extends IDataAccessService.Stub {
        HashMap<String, byte[]> mContents = new HashMap<String, byte[]>();

        public LocalDataService() {
            mContents.put("a", new byte[] {1, 2, 3});
            mContents.put("b", new byte[] {4, 5, 6});
            mContents.put("c", new byte[] {7, 8, 9});
        }

        @Override
        public void onRequest(
                int operation,
                Bundle params,
                IDataAccessServiceCallback callback) {

            if (operation == Constants.DATA_ACCESS_OP_LOCAL_DATA_KEYSET) {
                Bundle result = new Bundle();
                result.putSerializable(Constants.EXTRA_RESULT,
                        new HashSet<>(mContents.keySet()));
                try {
                    callback.onSuccess(result);
                } catch (RemoteException e) {
                    // Ignored.
                }
                return;
            }

            String[] keys = params.getStringArray(Constants.EXTRA_LOOKUP_KEYS);
            byte[] value = params.getByteArray(Constants.EXTRA_VALUE);
            if (keys.length == 1 && keys[0].equals("z")) {
                // Raise expected error.
                try {
                    callback.onError(Constants.STATUS_INTERNAL_ERROR);
                } catch (RemoteException e) {
                    // Ignored.
                }
                return;
            }

            if (keys.length == 1 && keys[0].equals("timeout")) {
                // Force timeout by sleeping.
                try {
                    Thread.sleep(2000);
                } catch (Exception e) {
                    // Ignored
                }
                return;
            }

            if (operation == Constants.DATA_ACCESS_OP_LOCAL_DATA_LOOKUP
                    || operation == Constants.DATA_ACCESS_OP_LOCAL_DATA_PUT
                    || operation == Constants.DATA_ACCESS_OP_LOCAL_DATA_REMOVE) {
                HashMap<String, byte[]> dict = new HashMap<String, byte[]>();
                for (int i = 0; i < keys.length; ++i) {
                    if (mContents.containsKey(keys[i])) {
                        dict.put(keys[i], mContents.get(keys[i]));
                    }
                }
                if (operation == Constants.DATA_ACCESS_OP_LOCAL_DATA_REMOVE) {
                    mContents.remove(keys[0]);
                }
                if (operation == Constants.DATA_ACCESS_OP_LOCAL_DATA_PUT) {
                    mContents.put(keys[0], value);
                }
                Bundle result = new Bundle();
                result.putSerializable(Constants.EXTRA_RESULT, dict);
                try {
                    callback.onSuccess(result);
                } catch (RemoteException e) {
                    // Ignored.
                }
            }
        }
    }
}
