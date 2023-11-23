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
 * atest CtsAppTestCases:ActivityManagerRunningTaskInfoTest
 */
@RunWith(AndroidJUnit4.class)
public class ActivityManagerRunningTaskInfoTest extends ActivityManagerBaseTaskInfoTest {

    private ActivityManager.RunningTaskInfo mRunningTaskInfo;

    @Before
    public void setUp() throws Exception {
        mRunningTaskInfo = new ActivityManager.RunningTaskInfo();
        mRunningTaskInfo.id = 1;
    }

    @Test
    public void testConstructor() {
        new ActivityManager.RunningTaskInfo();
    }

    @Test
    public void testDescribeContents() {
        assertEquals(0, mRunningTaskInfo.describeContents());
    }

    @Test
    public void testWriteToParcel() {
        fillTaskInfo(mRunningTaskInfo);
        Parcel parcel = Parcel.obtain();
        mRunningTaskInfo.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        ActivityManager.RunningTaskInfo info = ActivityManager.RunningTaskInfo.CREATOR
                .createFromParcel(parcel);
        verifyTaskInfo(info, mRunningTaskInfo);
        assertEquals(1, info.id);
    }

    @Test
    public void testReadFromParcel() {
        fillTaskInfo(mRunningTaskInfo);
        Parcel parcel = Parcel.obtain();
        mRunningTaskInfo.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        ActivityManager.RunningTaskInfo info = new ActivityManager.RunningTaskInfo();
        info.readFromParcel(parcel);
        verifyTaskInfo(info, mRunningTaskInfo);
        assertEquals(1, info.id);
    }
}
