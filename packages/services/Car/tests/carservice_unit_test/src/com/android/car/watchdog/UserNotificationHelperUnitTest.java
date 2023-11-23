/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.watchdog;

import static com.android.car.watchdog.CarWatchdogService.ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION;
import static com.android.car.watchdog.CarWatchdogService.ACTION_LAUNCH_APP_SETTINGS;
import static com.android.car.watchdog.WatchdogPerfHandler.INTENT_EXTRA_ID;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiAutomation;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.SparseArray;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.R;
import com.android.car.admin.NotificationHelper;
import com.android.server.utils.Slogf;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.Map;


/**
 * <p>This class contains unit tests for the {@link UserNotificationHelper}.
 */
@RunWith(MockitoJUnitRunner.class)
public final class UserNotificationHelperUnitTest extends AbstractExtendedMockitoTestCase {
    private static final String APP_SUFFIX = ".app";

    @Mock private PackageManager mMockPackageManager;
    @Mock private NotificationManager mMockNotificationManager;

    @Captor private ArgumentCaptor<Notification> mNotificationCaptor;
    @Captor private ArgumentCaptor<Integer> mIntCaptor;

    private final Context mSpiedContext =
            spy(InstrumentationRegistry.getInstrumentation().getContext());
    private final Map<String, ApplicationInfo> mApplicationInfosByUserPackage =
            new ArrayMap<>();

    private final UserNotificationHelper mUserNotificationHelper =
            new UserNotificationHelper(mSpiedContext);

    @Before
    public void setUp() throws Exception {
        when(mSpiedContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mSpiedContext.getSystemService(NotificationManager.class))
                .thenReturn(mMockNotificationManager);
        mockPackageManager();
    }

    @Test
    public void testShowResourceOveruseNotificationsAsUser() throws Exception {
        UserHandle userHandle = UserHandle.of(100);

        injectApplicationInfos(List.of(
                constructApplicationInfo("system_package.A", 10011000, ApplicationInfo.FLAG_SYSTEM),
                constructApplicationInfo("vendor_package.A", 10023000, ApplicationInfo.FLAG_SYSTEM),
                constructApplicationInfo("third_party_package.A", 10054000, /* infoFlags= */ 0)));

        List<UserNotificationHelper.PackageNotificationInfo> infos = List.of(
                new UserNotificationHelper.PackageNotificationInfo("system_package.A",
                        NotificationManager.IMPORTANCE_HIGH, /* notificationId= */ 150),
                new UserNotificationHelper.PackageNotificationInfo("vendor_package.A",
                        NotificationManager.IMPORTANCE_DEFAULT, /* notificationId= */ 151),
                new UserNotificationHelper.PackageNotificationInfo("third_party_package.A",
                        NotificationManager.IMPORTANCE_DEFAULT, /* notificationId= */ 152));

        mUserNotificationHelper.showResourceOveruseNotificationsAsUser(infos, userHandle);

        SparseArray<Notification> expectedNotificationsById = new SparseArray<>();
        expectedNotificationsById.put(150, constructNotification(userHandle, "system_package.A",
                /* notificationId= */ 150, NotificationManager.IMPORTANCE_HIGH,
                /* isBundledApp= */ true));
        expectedNotificationsById.put(151, constructNotification(userHandle, "vendor_package.A",
                /* notificationId= */ 151, NotificationManager.IMPORTANCE_DEFAULT,
                /* isBundledApp= */ true));
        expectedNotificationsById.put(152, constructNotification(userHandle,
                "third_party_package.A", /* notificationId= */ 152,
                NotificationManager.IMPORTANCE_DEFAULT, /* isBundledApp= */ false));

        captureAndVerifyUserNotifications(expectedNotificationsById, userHandle);
    }

    private Notification constructNotification(UserHandle userHandle, String packageName,
            int notificationId, int importance, boolean isBundledApp) {
        CharSequence title = TextUtils.expandTemplate(
                mSpiedContext.getText(R.string.resource_overuse_notification_title),
                packageName + APP_SUFFIX);
        String actionTextPrioritizeApp = mSpiedContext.getString(
                R.string.resource_overuse_notification_button_prioritize_app);
        PendingIntent positiveActionIntent = UserNotificationHelper.getCarServicePendingIntent(
                mSpiedContext, ACTION_LAUNCH_APP_SETTINGS, userHandle, packageName, notificationId);
        PendingIntent deleteIntent =
                UserNotificationHelper.getCarServicePendingIntent(mSpiedContext,
                        ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION, userHandle, packageName,
                        notificationId);
        String text;
        String negativeActionText;
        PendingIntent negativeActionIntent;
        if (isBundledApp) {
            text = mSpiedContext.getString(R.string.resource_overuse_notification_text_disable_app);
            negativeActionText = mSpiedContext.getString(
                    R.string.resource_overuse_notification_button_disable_app);
            negativeActionIntent = UserNotificationHelper.getCarServicePendingIntent(mSpiedContext,
                    CarWatchdogService.ACTION_RESOURCE_OVERUSE_DISABLE_APP, userHandle, packageName,
                    notificationId);
        } else {
            text = mSpiedContext.getString(
                    R.string.resource_overuse_notification_text_uninstall_app);
            negativeActionText = mSpiedContext.getString(
                    R.string.resource_overuse_notification_button_uninstall_app);
            negativeActionIntent = positiveActionIntent;
        }
        text += " " + mSpiedContext.getString(
                R.string.resource_overuse_notification_text_prioritize_app);

        return NotificationHelper.newNotificationBuilder(mSpiedContext, importance)
                .setSmallIcon(R.drawable.car_ic_warning)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_CAR_WARNING)
                .addAction(new Notification.Action.Builder(/* icon= */ null,
                        actionTextPrioritizeApp, positiveActionIntent).build())
                .addAction(new Notification.Action.Builder(/* icon= */ null,
                        negativeActionText, negativeActionIntent).build())
                .setDeleteIntent(deleteIntent)
                .build();
    }

    private void injectApplicationInfos(
            List<ApplicationInfo> applicationInfos) {
        for (ApplicationInfo applicationInfo : applicationInfos) {
            int userId = UserHandle.getUserId(applicationInfo.uid);
            String userPackageId = userId + ":" + applicationInfo.packageName;
            assertWithMessage("Duplicate application infos provided for user package id: %s",
                    userPackageId).that(mApplicationInfosByUserPackage.containsKey(userPackageId))
                    .isFalse();
            mApplicationInfosByUserPackage.put(userPackageId, applicationInfo);
        }
    }

    private void mockPackageManager() throws Exception {
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), any()))
                .thenAnswer(args -> {
                    Slogf.e("UserNotificationHelperUnitTest",
                            "getApplicationInfoAsUser: mApplicationInfosByUserPackage -> %s",
                            mApplicationInfosByUserPackage);
                    int userId = ((UserHandle) args.getArgument(2)).getIdentifier();
                    String userPackageId = userId + ":" + args.getArgument(0);
                    ApplicationInfo applicationInfo =
                            mApplicationInfosByUserPackage.get(userPackageId);
                    if (applicationInfo == null) {
                        throw new PackageManager.NameNotFoundException(
                                "User package id '" + userPackageId + "' not found");
                    }
                    return applicationInfo;
                });
    }

    private static ApplicationInfo constructApplicationInfo(String pkgName, int pkgUid,
            int infoFlags) {
        return new ApplicationInfo() {{
                name = pkgName + APP_SUFFIX;
                packageName = pkgName;
                uid = pkgUid;
                flags = infoFlags;
            }};
    }

    private void captureAndVerifyUserNotifications(
            SparseArray<Notification> expectedNotificationsById, UserHandle expectedUser)
            throws Exception {
        Slogf.e("UserNotificationHelperUnitTest", "Calling verify on mMockNotificationManager %s",
                mMockNotificationManager);
        verify(mMockNotificationManager, times(expectedNotificationsById.size()))
                .notifyAsUser(eq(CarWatchdogService.TAG), mIntCaptor.capture(),
                        mNotificationCaptor.capture(), eq(expectedUser));

        List<Notification> actualNotifications = mNotificationCaptor.getAllValues();
        List<Integer> actualNotificationIds = mIntCaptor.getAllValues();

        for (int i = 0; i < actualNotifications.size(); ++i) {
            Notification actual = actualNotifications.get(i);
            int notificationId = actualNotificationIds.get(i);
            isNotificationEqualTo(actual, expectedNotificationsById.get(notificationId),
                    notificationId);
        }
    }

    private void isNotificationEqualTo(Notification actual, Notification expected,
            int notificationId) throws Exception {
        assertWithMessage("Notification for id %s", notificationId).that(actual).isNotNull();
        assertWithMessage("Notification.actions.length for id %s", notificationId)
                .that(actual.actions.length).isEqualTo(expected.actions.length);
        assertWithMessage("Notification.getChannelId() for id %s", notificationId)
                .that(actual.getChannelId()).isEqualTo(expected.getChannelId());
        for (int i = 0; i < actual.actions.length; ++i) {
            isNotificationActionEqualTo(actual.actions[i], expected.actions[i], notificationId);
        }
        assertWithMessage("Notification.getSmallIcon().getResId() for id %s", notificationId)
                .that(actual.getSmallIcon().getResId())
                .isEqualTo(expected.getSmallIcon().getResId());
        isBundleEqualTo(actual.extras, expected.extras, notificationId, Notification.EXTRA_TITLE,
                Notification.EXTRA_TEXT);
    }

    private void isNotificationActionEqualTo(Notification.Action actual,
            Notification.Action expected, int notificationId) throws Exception {
        assertWithMessage("Action.title for id %s", notificationId).that(actual.title)
                .isEqualTo(expected.title);
        assertWithMessage("Action.actionIntent.isImmutable() for id %s", notificationId)
                .that(actual.actionIntent.isImmutable())
                .isEqualTo(expected.actionIntent.isImmutable());
        assertWithMessage("Action.actionIntent for id %s", notificationId).that(actual.actionIntent)
                .isEqualTo(expected.actionIntent);
        isIntentEqualTo(getActionIntent(actual), getActionIntent(expected), notificationId);
    }

    private void isIntentEqualTo(Intent actual, Intent expected, int notificationId)
            throws Exception {
        assertWithMessage("Intent.getAction() for id %s", notificationId).that(actual.getAction())
                .isEqualTo(expected.getAction());
        assertWithMessage("Intent.getPackage() for id %s", notificationId).that(actual.getPackage())
                .isEqualTo(expected.getPackage());
        assertWithMessage("Intent.getFlags() for id %s", notificationId).that(actual.getFlags())
                .isEqualTo(expected.getFlags());
        isBundleEqualTo(actual.getExtras(), expected.getExtras(), notificationId,
                Intent.EXTRA_PACKAGE_NAME, Intent.EXTRA_USER, INTENT_EXTRA_ID);
    }

    private void isBundleEqualTo(Bundle actual, Bundle expected, int notificationId,
            String... extraNames) throws Exception {
        assertWithMessage("Bundle.getSize() for notification %s", notificationId)
                .that(actual.getSize()).isEqualTo(expected.getSize());
        for (int i = 0; i < extraNames.length; ++i) {
            switch (extraNames[i]) {
                case Intent.EXTRA_USER:
                    assertWithMessage(extraNames[i] + " for id %s", notificationId)
                            .that((UserHandle) actual.getParcelable(extraNames[i]))
                            .isEqualTo(expected.getParcelable(extraNames[i]));
                    break;
                case Notification.EXTRA_TITLE:
                case Notification.EXTRA_TEXT:
                    assertWithMessage(extraNames[i] + " for id %s", notificationId)
                            .that(actual.getCharSequence(extraNames[i]).toString())
                            .isEqualTo(expected.getCharSequence(extraNames[i]).toString());
                    break;
                default:
                    assertWithMessage(extraNames[i] + " for id %s", notificationId)
                            .that(actual.getString(extraNames[i]))
                            .isEqualTo(expected.getString(extraNames[i]));
            }
        }
    }

    private Intent getActionIntent(Notification.Action action) {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity();
        try {
            return action.actionIntent.getIntent();
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }
}
