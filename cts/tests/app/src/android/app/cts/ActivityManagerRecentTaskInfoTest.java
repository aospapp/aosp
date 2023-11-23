/*
 * Copyright (C) 2008 The Android Open Source Project
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
package android.app.cts;

import static org.junit.Assert.assertEquals;

import android.app.ActivityManager;
import android.os.Parcel;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * atest CtsAppTestCases:ActivityManagerRecentTaskInfoTest
 */
@RunWith(AndroidJUnit4.class)
public class ActivityManagerRecentTaskInfoTest extends ActivityManagerBaseTaskInfoTest {

    private ActivityManager.RecentTaskInfo mRecentTaskInfo;

    @Before
    public void setUp() throws Exception {
        mRecentTaskInfo = new ActivityManager.RecentTaskInfo();
        mRecentTaskInfo.id = 1;
        mRecentTaskInfo.persistentId = 1;
    }

    @Test
    public void testConstructor() {
        new ActivityManager.RecentTaskInfo();
    }

    @Test
    public void testDescribeContents() {
        assertEquals(0, mRecentTaskInfo.describeContents());
    }

    @Test
    public void testWriteToParcel() {
        fillTaskInfo(mRecentTaskInfo);
        Parcel parcel = Parcel.obtain();
        mRecentTaskInfo.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        ActivityManager.RecentTaskInfo info = ActivityManager.RecentTaskInfo.CREATOR
                .createFromParcel(parcel);
        verifyTaskInfo(info, mRecentTaskInfo);
        assertEquals(1, info.id);
        assertEquals(1, info.persistentId);
    }

    @Test
    public void testReadFromParcel() {
        fillTaskInfo(mRecentTaskInfo);
        Parcel parcel = Parcel.obtain();
        mRecentTaskInfo.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        ActivityManager.RecentTaskInfo info = new ActivityManager.RecentTaskInfo();
        info.readFromParcel(parcel);
        verifyTaskInfo(info, mRecentTaskInfo);
        assertEquals(1, info.id);
        assertEquals(1, info.persistentId);
    }
}
