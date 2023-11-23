/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class CarActivityInterceptorUpdatableTest {
    private static final int DEFAULT_CURRENT_USER_ID = 112;
    private static final int PASSENGER_USER_ID = 198;
    private CarActivityInterceptorUpdatableImpl mInterceptor;
    private MockitoSession mMockingSession;
    private WindowContainer.RemoteToken mRootTaskToken1;
    private WindowContainer.RemoteToken mRootTaskToken2;

    @Mock
    private Task mWindowContainer1;
    @Mock
    private Task mWindowContainer2;

    @Mock
    private DisplayContent mDisplayContent;

    @Mock
    private Display mDisplay;

    @Mock
    private TaskDisplayArea mTda;

    private final CarActivityInterceptorInterface mCarActivityInterceptorInterface =
            new CarActivityInterceptorInterface() {
                @Override
                public int getUserAssignedToDisplay(int displayId) {
                    return DEFAULT_CURRENT_USER_ID;
                }

                @Override
                public int getMainDisplayAssignedToUser(int userId) {
                    return 0;
                }
            };

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();

        mTda.mDisplayContent = mDisplayContent;
        when(mDisplayContent.getDisplay()).thenReturn(mDisplay);
        when(mDisplay.getDisplayId()).thenReturn(0);

        mRootTaskToken1 = new WindowContainer.RemoteToken(mWindowContainer1);
        mWindowContainer1.mRemoteToken = mRootTaskToken1;
        when(mWindowContainer1.getTaskDisplayArea()).thenReturn(mTda);

        mRootTaskToken2 = new WindowContainer.RemoteToken(mWindowContainer2);
        when(mWindowContainer2.getTaskDisplayArea()).thenReturn(mTda);
        mWindowContainer2.mRemoteToken = mRootTaskToken2;

        mInterceptor = new CarActivityInterceptorUpdatableImpl(mCarActivityInterceptorInterface);
    }

    @After
    public void tearDown() {
        // If the exception is thrown during the MockingSession setUp, mMockingSession can be null.
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private ActivityInterceptorInfoWrapper createActivityInterceptorInfo(String packageName,
            String activityName, Intent intent, ActivityOptions options, int userId) {
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = packageName;
        activityInfo.name = activityName;
        ActivityInterceptorCallback.ActivityInterceptorInfo.Builder builder =
                new ActivityInterceptorCallback.ActivityInterceptorInfo.Builder(
                        /* callingUId= */ 0, /* callingPid= */ 0, /* realCallingUid= */ 0,
                        /* realCallingPid= */ 0, /* userId= */ userId, intent,
                        new ResolveInfo(), activityInfo);
        builder.setCheckedOptions(options);
        return ActivityInterceptorInfoWrapper.create(builder.build());
    }

    private ActivityInterceptorInfoWrapper createActivityInterceptorInfoWithCustomIntent(
            String packageName, String activityName, Intent intent) {
        return createActivityInterceptorInfo(packageName, activityName, intent,
                ActivityOptions.makeBasic(), DEFAULT_CURRENT_USER_ID);
    }

    private ActivityInterceptorInfoWrapper createActivityInterceptorInfoWithCustomIntent(
            String packageName, String activityName, Intent intent, int userId) {
        return createActivityInterceptorInfo(packageName, activityName, intent,
                ActivityOptions.makeBasic(), userId);
    }

    private ActivityInterceptorInfoWrapper createActivityInterceptorInfoWithMainIntent(
            String packageName, String activityName) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(ComponentName.unflattenFromString(packageName + "/" + activityName));
        return createActivityInterceptorInfoWithCustomIntent(packageName, activityName, intent);
    }

    private ActivityInterceptorInfoWrapper createActivityInterceptorInfoWithMainIntent(
            String packageName, String activityName, int userId) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(ComponentName.unflattenFromString(packageName + "/" + activityName));
        return createActivityInterceptorInfoWithCustomIntent(packageName, activityName, intent,
                userId);
    }

    private ActivityInterceptorInfoWrapper createActivityInterceptorInfoWithMainIntent(
            String packageName, String activityName, ActivityOptions options) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(ComponentName.unflattenFromString(packageName + "/" + activityName));
        return createActivityInterceptorInfo(packageName, activityName, intent, options,
                DEFAULT_CURRENT_USER_ID);
    }

    @Test
    public void interceptActivityLaunch_nullIntent_returnsNull() {
        ActivityInterceptorInfoWrapper info =
                createActivityInterceptorInfoWithCustomIntent("com.example.app3",
                        "com.example.app3.MainActivity", /* intent= */ null);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(info);

        assertThat(result).isNull();
    }

    @Test
    public void interceptActivityLaunch_unknownActivity_returnsNull() {
        List<ComponentName> activities = List.of(
                ComponentName.unflattenFromString("com.example.app/com.example.app.MainActivity"),
                ComponentName.unflattenFromString("com.example.app2/com.example.app2.MainActivity")
        );
        mInterceptor.setPersistentActivityOnRootTask(activities, mRootTaskToken1);
        ActivityInterceptorInfoWrapper info =
                createActivityInterceptorInfoWithMainIntent("com.example.app3",
                        "com.example.app3.MainActivity");

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(info);

        assertThat(result).isNull();
    }

    @Test
    public void interceptActivityLaunch_nullOptions_persistedActivity_setsLaunchRootTask() {
        List<ComponentName> activities = List.of(
                ComponentName.unflattenFromString("com.example.app/com.example.app.MainActivity"),
                ComponentName.unflattenFromString("com.example.app2/com.example.app2.MainActivity")
        );
        mInterceptor.setPersistentActivityOnRootTask(activities, mRootTaskToken1);
        ActivityInterceptorInfoWrapper info =
                createActivityInterceptorInfoWithMainIntent(activities.get(0).getPackageName(),
                        activities.get(0).getClassName(), /* options= */ null);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(info);

        assertThat(result).isNotNull();
        assertThat(result.getInterceptResult().getActivityOptions().getLaunchRootTask())
                .isEqualTo(WindowContainer.fromBinder(mRootTaskToken1)
                        .mRemoteToken.toWindowContainerToken());
    }

    @Test
    public void interceptActivityLaunch_persistedActivity_setsLaunchRootTask() {
        List<ComponentName> activities = List.of(
                ComponentName.unflattenFromString("com.example.app/com.example.app.MainActivity"),
                ComponentName.unflattenFromString("com.example.app2/com.example.app2.MainActivity")
        );
        mInterceptor.setPersistentActivityOnRootTask(activities, mRootTaskToken1);
        ActivityInterceptorInfoWrapper info =
                createActivityInterceptorInfoWithMainIntent(activities.get(0).getPackageName(),
                        activities.get(0).getClassName());

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(info);

        assertThat(result).isNotNull();
        assertThat(result.getInterceptResult().getActivityOptions().getLaunchRootTask())
                .isEqualTo(WindowContainer.fromBinder(mRootTaskToken1)
                        .mRemoteToken.toWindowContainerToken());
    }

    @Test
    public void interceptActivityLaunch_persistedActivity_differentUser_doesNothing() {
        List<ComponentName> activities = List.of(
                ComponentName.unflattenFromString("com.example.app/com.example.app.MainActivity"),
                ComponentName.unflattenFromString("com.example.app2/com.example.app2.MainActivity")
        );
        mInterceptor.setPersistentActivityOnRootTask(activities, mRootTaskToken1);
        ActivityInterceptorInfoWrapper info =
                createActivityInterceptorInfoWithMainIntent(activities.get(0).getPackageName(),
                        activities.get(0).getClassName(), /* userId= */ PASSENGER_USER_ID);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(info);

        assertThat(result).isNull();
    }

    @Test
    public void setPersistentActivity_nullLaunchRootTask_removesAssociation() {
        List<ComponentName> activities1 = List.of(
                ComponentName.unflattenFromString("com.example.app/com.example.app.MainActivity"),
                ComponentName.unflattenFromString("com.example.app2/com.example.app2.MainActivity")
        );
        List<ComponentName> activities2 = List.of(
                ComponentName.unflattenFromString("com.example.app3/com.example.app3.MainActivity"),
                ComponentName.unflattenFromString("com.example.app4/com.example.app4.MainActivity")
        );
        mInterceptor.setPersistentActivityOnRootTask(activities1, mRootTaskToken1);
        mInterceptor.setPersistentActivityOnRootTask(activities2, mRootTaskToken2);
        ActivityInterceptorInfoWrapper info =
                createActivityInterceptorInfoWithMainIntent(activities1.get(0).getPackageName(),
                        activities1.get(0).getClassName());

        mInterceptor.setPersistentActivityOnRootTask(activities1, null);

        ActivityInterceptResultWrapper result = mInterceptor.onInterceptActivityLaunch(info);
        assertThat(result).isNull();
        assertThat(mInterceptor.getActivityToRootTaskMap()).containsExactly(
                activities2.get(0), mRootTaskToken2,
                activities2.get(1), mRootTaskToken2
        );
    }
}
