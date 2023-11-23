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

package com.android.server.nearby.fastpair.notification;

import static com.android.server.nearby.fastpair.notification.FastPairNotificationBuilder.NOTIFICATION_OVERRIDE_NAME_EXTRA;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.nearby.halfsheet.R;
import com.android.server.nearby.fastpair.HalfSheetResources;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

public class FastPairNotificationBuilderTest {

    private static final String STRING_DEVICE = "Devices";
    private static final String STRING_NEARBY = "Nearby";

    @Mock private Context mContext;
    @Mock private PackageManager mPackageManager;
    @Mock private Resources mResources;

    private ResolveInfo mResolveInfo;
    private List<ResolveInfo> mResolveInfoList;
    private ApplicationInfo mApplicationInfo;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        HalfSheetResources.setResourcesContextForTest(mContext);

        mResolveInfo = new ResolveInfo();
        mResolveInfoList = new ArrayList<>();
        mResolveInfo.activityInfo = new ActivityInfo();
        mApplicationInfo = new ApplicationInfo();

        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getApplicationInfo())
                .thenReturn(InstrumentationRegistry
                        .getInstrumentation().getContext().getApplicationInfo());
        when(mContext.getContentResolver()).thenReturn(
                InstrumentationRegistry.getInstrumentation().getContext().getContentResolver());
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(mResolveInfoList);
        when(mPackageManager.canRequestPackageInstalls()).thenReturn(false);
        mApplicationInfo.sourceDir = "/apex/com.android.nearby";
        mApplicationInfo.packageName = "test.package";
        mResolveInfo.activityInfo.applicationInfo = mApplicationInfo;
        mResolveInfoList.add(mResolveInfo);

        when(mResources.getString(eq(R.string.common_devices))).thenReturn(STRING_DEVICE);
        when(mResources.getString(eq(R.string.common_nearby_title))).thenReturn(STRING_NEARBY);
    }

    @Test
    public void setIsDevice_true() {
        Notification notification =
                new FastPairNotificationBuilder(mContext, "channelId")
                        .setIsDevice(true).build();
        assertThat(notification.extras.getString(NOTIFICATION_OVERRIDE_NAME_EXTRA))
                .isEqualTo(STRING_DEVICE);
    }

    @Test
    public void setIsDevice_false() {
        Notification notification =
                new FastPairNotificationBuilder(mContext, "channelId")
                        .setIsDevice(false).build();
        assertThat(notification.extras.getString(NOTIFICATION_OVERRIDE_NAME_EXTRA))
                .isEqualTo(STRING_NEARBY);
    }
}
