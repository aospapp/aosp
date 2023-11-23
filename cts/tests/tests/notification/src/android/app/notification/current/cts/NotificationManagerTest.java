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

package android.app.notification.current.cts;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.Manifest.permission.REVOKE_POST_NOTIFICATIONS_WITHOUT_KILL;
import static android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS;
import static android.app.Activity.RESULT_OK;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.MODE_ERRORED;
import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.Notification.FLAG_USER_INITIATED_JOB;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.IMPORTANCE_NONE;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.content.pm.PackageManager.FEATURE_WATCH;
import static android.service.notification.NotificationListenerService.META_DATA_DEFAULT_AUTOBIND;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.junit.Assert.assertThat;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.stubs.shared.FutureServiceConnection;
import android.app.role.RoleManager;
import android.app.stubs.GetResultActivity;
import android.app.stubs.R;
import android.app.stubs.shared.NotificationHelper.SEARCH_TYPE;
import android.app.stubs.shared.TestNotificationListener;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.permission.cts.PermissionUtils;
import android.platform.test.annotations.AsbSecurityTest;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.android.compatibility.common.util.PollingCheck;
import com.android.compatibility.common.util.SystemUtil;
import com.android.compatibility.common.util.ThrowingSupplier;
import com.android.test.notificationlistener.INLSControlService;
import com.android.test.notificationlistener.INotificationUriAccessService;

import com.google.common.base.Preconditions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/* This tests NotificationListenerService together with NotificationManager, as you need to have
 * notifications to manipulate in order to test the listener service. */
public class NotificationManagerTest extends BaseNotificationManagerTest {
    public static final String NOTIFICATIONPROVIDER = "com.android.test.notificationprovider";
    public static final String RICH_NOTIFICATION_ACTIVITY =
            "com.android.test.notificationprovider.RichNotificationActivity";
    final String TAG = NotificationManagerTest.class.getSimpleName();
    final boolean DEBUG = false;

    private static final String DELEGATE_POST_CLASS = TEST_APP + ".NotificationDelegateAndPost";
    private static final String REVOKE_CLASS = TEST_APP + ".NotificationRevoker";

    private static final String TRAMPOLINE_APP =
            "com.android.test.notificationtrampoline.current";
    private static final String TRAMPOLINE_APP_API_30 =
            "com.android.test.notificationtrampoline.api30";
    private static final String TRAMPOLINE_APP_API_32 =
            "com.android.test.notificationtrampoline.api32";
    private static final ComponentName TRAMPOLINE_SERVICE =
            new ComponentName(TRAMPOLINE_APP,
                    "com.android.test.notificationtrampoline.NotificationTrampolineTestService");
    private static final ComponentName TRAMPOLINE_SERVICE_API_30 =
            new ComponentName(TRAMPOLINE_APP_API_30,
                    "com.android.test.notificationtrampoline.NotificationTrampolineTestService");
    private static final ComponentName TRAMPOLINE_SERVICE_API_32 =
            new ComponentName(TRAMPOLINE_APP_API_32,
                    "com.android.test.notificationtrampoline.NotificationTrampolineTestService");

    private static final ComponentName URI_ACCESS_SERVICE = new ComponentName(
            "com.android.test.notificationlistener",
            "com.android.test.notificationlistener.NotificationUriAccessService");

    private static final ComponentName NLS_CONTROL_SERVICE = new ComponentName(
            "com.android.test.notificationlistener",
            "com.android.test.notificationlistener.NLSControlService");

    private static final ComponentName NO_AUTOBIND_NLS = new ComponentName(
            "com.android.test.notificationlistener",
            "com.android.test.notificationlistener.TestNotificationListenerNoAutobind");

    private static final String STUB_PACKAGE_NAME = "android.app.stubs";

    private static final long TIMEOUT_LONG_MS = 10000;
    private static final long TIMEOUT_MS = 4000;
    private static final int MESSAGE_BROADCAST_NOTIFICATION = 1;
    private static final int MESSAGE_SERVICE_NOTIFICATION = 2;
    private static final int MESSAGE_CLICK_NOTIFICATION = 3;

    private String mId;
    private INotificationUriAccessService mNotificationUriAccessService;
    private INLSControlService mNLSControlService;
    private FutureServiceConnection mTrampolineConnection;

    @Nullable
    private List<String> mPreviousDefaultBrowser;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PermissionUtils.grantPermission(mContext.getPackageName(), POST_NOTIFICATIONS);
        PermissionUtils.grantPermission(STUB_PACKAGE_NAME, POST_NOTIFICATIONS);
        PermissionUtils.grantPermission(TEST_APP, POST_NOTIFICATIONS);
        PermissionUtils.grantPermission(TRAMPOLINE_APP, POST_NOTIFICATIONS);
        PermissionUtils.grantPermission(TRAMPOLINE_APP_API_30, POST_NOTIFICATIONS);
        PermissionUtils.grantPermission(TRAMPOLINE_APP_API_32, POST_NOTIFICATIONS);
        PermissionUtils.grantPermission(NOTIFICATIONPROVIDER, POST_NOTIFICATIONS);
        // This will leave a set of channels on the device with each test run.
        mId = UUID.randomUUID().toString();

        // delay between tests so notifications aren't dropped by the rate limiter
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
        }
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        // For trampoline tests
        if (mTrampolineConnection != null) {
            mContext.unbindService(mTrampolineConnection);
            mTrampolineConnection = null;
        }
        if (mListener != null) {
            mListener.removeTestPackage(TRAMPOLINE_APP_API_30);
            mListener.removeTestPackage(TRAMPOLINE_APP);
        }
        if (mPreviousDefaultBrowser != null) {
            restoreDefaultBrowser();
        }

        // Use test API to prevent PermissionManager from killing the test process when revoking
        // permission.
        SystemUtil.runWithShellPermissionIdentity(
                () -> mContext.getSystemService(PermissionManager.class)
                        .revokePostNotificationPermissionWithoutKillForTest(
                                mContext.getPackageName(),
                                android.os.Process.myUserHandle().getIdentifier()),
                REVOKE_POST_NOTIFICATIONS_WITHOUT_KILL,
                REVOKE_RUNTIME_PERMISSIONS);
        PermissionUtils.revokePermission(STUB_PACKAGE_NAME, POST_NOTIFICATIONS);
        PermissionUtils.revokePermission(TEST_APP, POST_NOTIFICATIONS);
        PermissionUtils.revokePermission(TRAMPOLINE_APP, POST_NOTIFICATIONS);
        PermissionUtils.revokePermission(NOTIFICATIONPROVIDER, POST_NOTIFICATIONS);
    }

    private PendingIntent getPendingIntent() {
        return PendingIntent.getActivity(
                getContext(), 0, new Intent(getContext(), this.getClass()),
                PendingIntent.FLAG_MUTABLE_UNAUDITED);
    }

    private boolean isGroupSummary(Notification n) {
        return n.getGroup() != null && (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }

    private void assertOnlySomeNotificationsAutogrouped(List<Integer> autoGroupedIds) {
        String expectedGroupKey = null;
        try {
            // Posting can take ~100 ms
            Thread.sleep(150);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        StatusBarNotification[] sbns = mNotificationManager.getActiveNotifications();
        for (StatusBarNotification sbn : sbns) {
            if (isGroupSummary(sbn.getNotification())
                    || autoGroupedIds.contains(sbn.getId())) {
                assertTrue(sbn.getKey() + " is unexpectedly not autogrouped",
                        sbn.getOverrideGroupKey() != null);
                if (expectedGroupKey == null) {
                    expectedGroupKey = sbn.getGroupKey();
                }
                assertEquals(expectedGroupKey, sbn.getGroupKey());
            } else {
                assertTrue(sbn.isGroup());
                assertTrue(sbn.getKey() + " is unexpectedly autogrouped,",
                        sbn.getOverrideGroupKey() == null);
                assertTrue(sbn.getKey() + " has an unusual group key",
                        sbn.getGroupKey() != expectedGroupKey);
            }
        }
    }

    private void assertAllPostedNotificationsAutogrouped() {
        String expectedGroupKey = null;
        try {
            // Posting can take ~100 ms
            Thread.sleep(150);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        StatusBarNotification[] sbns = mNotificationManager.getActiveNotifications();
        for (StatusBarNotification sbn : sbns) {
            // all notis should be in a group determined by autogrouping
            assertTrue(sbn.getOverrideGroupKey() != null);
            if (expectedGroupKey == null) {
                expectedGroupKey = sbn.getGroupKey();
            }
            // all notis should be in the same group
            assertEquals(expectedGroupKey, sbn.getGroupKey());
        }
    }

    private int getCancellationReason(String key) {
        for (int tries = 3; tries-- > 0; ) {
            if (mListener.mRemoved.containsKey(key)) {
                return mListener.mRemoved.get(key);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                // pass
            }
        }
        return -1;
    }

    private int getAssistantCancellationReason(String key) {
        for (int tries = 3; tries-- > 0; ) {
            if (mAssistant.mRemoved.containsKey(key)) {
                return mAssistant.mRemoved.get(key);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                // pass
            }
        }
        return -1;
    }

    private void assertNotificationCount(int expectedCount) {
        // notification is a bit asynchronous so it may take a few ms to appear in
        // getActiveNotifications()
        // we will check for it for up to 400ms before giving up
        int lastCount = 0;
        for (int tries = 4; tries-- > 0; ) {
            final StatusBarNotification[] sbns = mNotificationManager.getActiveNotifications();
            lastCount = sbns.length;
            if (expectedCount == lastCount) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                // pass
            }
        }
        fail("Expected " + expectedCount + " posted notifications, were " + lastCount);
    }

    private void compareChannels(NotificationChannel expected, NotificationChannel actual) {
        if (actual == null) {
            fail("actual channel is null");
            return;
        }
        if (expected == null) {
            fail("expected channel is null");
            return;
        }
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getDescription(), actual.getDescription());
        assertEquals(expected.shouldVibrate(), actual.shouldVibrate());
        assertEquals(expected.shouldShowLights(), actual.shouldShowLights());
        assertEquals(expected.getLightColor(), actual.getLightColor());
        assertEquals(expected.getImportance(), actual.getImportance());
        if (expected.getSound() == null) {
            assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, actual.getSound());
            assertEquals(Notification.AUDIO_ATTRIBUTES_DEFAULT, actual.getAudioAttributes());
        } else {
            assertEquals(expected.getSound(), actual.getSound());
            assertEquals(expected.getAudioAttributes(), actual.getAudioAttributes());
        }
        assertTrue(Arrays.equals(expected.getVibrationPattern(), actual.getVibrationPattern()));
        assertEquals(expected.getGroup(), actual.getGroup());
        assertEquals(expected.getConversationId(), actual.getConversationId());
        assertEquals(expected.getParentChannelId(), actual.getParentChannelId());
        assertEquals(expected.isDemoted(), actual.isDemoted());
    }

    private void sendTrampolineMessage(ComponentName component, int message,
            int notificationId, Handler callback) throws Exception {
        if (mTrampolineConnection == null) {
            Intent intent = new Intent();
            intent.setComponent(component);
            mTrampolineConnection = new FutureServiceConnection();
            assertTrue(
                    mContext.bindService(intent, mTrampolineConnection, Context.BIND_AUTO_CREATE));
        }
        Messenger service = new Messenger(mTrampolineConnection.get(TIMEOUT_MS));
        service.send(Message.obtain(null, message, notificationId, -1, new Messenger(callback)));
    }

    private void setDefaultBrowser(String packageName) throws Exception {
        UserHandle user = android.os.Process.myUserHandle();
        mPreviousDefaultBrowser = SystemUtil.callWithShellPermissionIdentity(
                () -> mRoleManager.getRoleHoldersAsUser(RoleManager.ROLE_BROWSER, user));
        CompletableFuture<Boolean> set = new CompletableFuture<>();
        SystemUtil.runWithShellPermissionIdentity(
                () -> mRoleManager.addRoleHolderAsUser(RoleManager.ROLE_BROWSER, packageName, 0,
                        user, mContext.getMainExecutor(), set::complete));
        assertTrue("Failed to set " + packageName + " as default browser",
                set.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private void restoreDefaultBrowser() throws Exception {
        Preconditions.checkState(mPreviousDefaultBrowser != null);
        UserHandle user = android.os.Process.myUserHandle();
        Executor executor = mContext.getMainExecutor();
        CompletableFuture<Boolean> restored = new CompletableFuture<>();
        SystemUtil.runWithShellPermissionIdentity(() -> {
            mRoleManager.clearRoleHoldersAsUser(RoleManager.ROLE_BROWSER, 0, user, executor,
                    restored::complete);
            for (String packageName : mPreviousDefaultBrowser) {
                mRoleManager.addRoleHolderAsUser(RoleManager.ROLE_BROWSER, packageName,
                        0, user, executor, restored::complete);
            }
        });
        assertTrue("Failed to restore default browser",
                restored.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    /**
     * Previous tests could have started activities within the grace period, so go home to avoid
     * allowing background activity starts due to this exemption.
     */
    private void deactivateGracePeriod() {
        UiDevice.getInstance(mInstrumentation).pressHome();
    }

    private void verifyCanUseFullScreenIntent(int appOpState, boolean canSend) throws Exception {
        final int previousState = PermissionUtils.getAppOp(STUB_PACKAGE_NAME,
                Manifest.permission.USE_FULL_SCREEN_INTENT);
        try {
            PermissionUtils.setAppOp(STUB_PACKAGE_NAME,
                    Manifest.permission.USE_FULL_SCREEN_INTENT,
                    appOpState);

            if (canSend) {
                assertTrue(mNotificationManager.canUseFullScreenIntent());
            } else {
                assertFalse(mNotificationManager.canUseFullScreenIntent());
            }

        } finally {
            // Clean up by setting to app op to previous state.
            PermissionUtils.setAppOp(STUB_PACKAGE_NAME,
                    Manifest.permission.USE_FULL_SCREEN_INTENT,
                    previousState);
        }
    }

    public void testCanSendFullScreenIntent_modeDefault_returnsIsPermissionGranted()
            throws Exception {
        final boolean isPermissionGranted = PermissionUtils.isPermissionGranted(STUB_PACKAGE_NAME,
                Manifest.permission.USE_FULL_SCREEN_INTENT);
        verifyCanUseFullScreenIntent(MODE_DEFAULT, /*canSend=*/ isPermissionGranted);
    }

    public void testCanSendFullScreenIntent_modeAllowed_returnsTrue() throws Exception {
        verifyCanUseFullScreenIntent(MODE_ALLOWED, /*canSend=*/ true);
    }

    public void testCanSendFullScreenIntent_modeErrored_returnsFalse() throws Exception {
        verifyCanUseFullScreenIntent(MODE_ERRORED, /*canSend=*/ false);
    }

    public void testCreateChannelGroup() throws Exception {
        final NotificationChannelGroup ncg = new NotificationChannelGroup("a group", "a label");
        final NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        channel.setGroup(ncg.getId());
        mNotificationManager.createNotificationChannelGroup(ncg);
        final NotificationChannel ungrouped =
                new NotificationChannel(mId + "!", "name", IMPORTANCE_DEFAULT);
        try {
            mNotificationManager.createNotificationChannel(channel);
            mNotificationManager.createNotificationChannel(ungrouped);

            List<NotificationChannelGroup> ncgs =
                    mNotificationManager.getNotificationChannelGroups();
            assertEquals(1, ncgs.size());
            assertEquals(ncg.getName(), ncgs.get(0).getName());
            assertEquals(ncg.getDescription(), ncgs.get(0).getDescription());
            assertEquals(channel.getId(), ncgs.get(0).getChannels().get(0).getId());
        } finally {
            mNotificationManager.deleteNotificationChannelGroup(ncg.getId());
        }
    }

    public void testGetChannelGroup() throws Exception {
        final NotificationChannelGroup ncg = new NotificationChannelGroup("a group", "a label");
        ncg.setDescription("bananas");
        final NotificationChannelGroup ncg2 = new NotificationChannelGroup("group 2", "label 2");
        final NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        channel.setGroup(ncg.getId());

        mNotificationManager.createNotificationChannelGroup(ncg);
        mNotificationManager.createNotificationChannelGroup(ncg2);
        mNotificationManager.createNotificationChannel(channel);

        NotificationChannelGroup actual =
                mNotificationManager.getNotificationChannelGroup(ncg.getId());
        assertEquals(ncg.getId(), actual.getId());
        assertEquals(ncg.getName(), actual.getName());
        assertEquals(ncg.getDescription(), actual.getDescription());
        assertEquals(channel.getId(), actual.getChannels().get(0).getId());
    }

    public void testGetChannelGroups() throws Exception {
        final NotificationChannelGroup ncg = new NotificationChannelGroup("a group", "a label");
        ncg.setDescription("bananas");
        final NotificationChannelGroup ncg2 = new NotificationChannelGroup("group 2", "label 2");
        final NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        channel.setGroup(ncg2.getId());

        mNotificationManager.createNotificationChannelGroup(ncg);
        mNotificationManager.createNotificationChannelGroup(ncg2);
        mNotificationManager.createNotificationChannel(channel);

        List<NotificationChannelGroup> actual =
                mNotificationManager.getNotificationChannelGroups();
        assertEquals(2, actual.size());
        for (NotificationChannelGroup group : actual) {
            if (group.getId().equals(ncg.getId())) {
                assertEquals(group.getName(), ncg.getName());
                assertEquals(group.getDescription(), ncg.getDescription());
                assertEquals(0, group.getChannels().size());
            } else if (group.getId().equals(ncg2.getId())) {
                assertEquals(group.getName(), ncg2.getName());
                assertEquals(group.getDescription(), ncg2.getDescription());
                assertEquals(1, group.getChannels().size());
                assertEquals(channel.getId(), group.getChannels().get(0).getId());
            } else {
                fail("Extra group found " + group.getId());
            }
        }
    }

    public void testDeleteChannelGroup() throws Exception {
        final NotificationChannelGroup ncg = new NotificationChannelGroup("a group", "a label");
        final NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        channel.setGroup(ncg.getId());
        mNotificationManager.createNotificationChannelGroup(ncg);
        mNotificationManager.createNotificationChannel(channel);

        mNotificationManager.deleteNotificationChannelGroup(ncg.getId());

        assertNull(mNotificationManager.getNotificationChannel(channel.getId()));
        assertEquals(0, mNotificationManager.getNotificationChannelGroups().size());
    }

    public void testCreateChannel() throws Exception {
        final NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        channel.setDescription("bananas");
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{5, 8, 2, 1});
        channel.setSound(new Uri.Builder().scheme("test").build(),
                new AudioAttributes.Builder().setUsage(
                        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED).build());
        channel.enableLights(true);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        mNotificationManager.createNotificationChannel(channel);
        final NotificationChannel createdChannel =
                mNotificationManager.getNotificationChannel(mId);
        compareChannels(channel, createdChannel);
        // Lockscreen Visibility and canBypassDnd no longer settable.
        assertTrue(createdChannel.getLockscreenVisibility() != Notification.VISIBILITY_SECRET);
        assertFalse(createdChannel.canBypassDnd());
    }

    public void testCreateChannel_rename() throws Exception {
        NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        mNotificationManager.createNotificationChannel(channel);
        channel.setName("new name");
        mNotificationManager.createNotificationChannel(channel);
        final NotificationChannel createdChannel =
                mNotificationManager.getNotificationChannel(mId);
        compareChannels(channel, createdChannel);

        channel.setImportance(NotificationManager.IMPORTANCE_HIGH);
        mNotificationManager.createNotificationChannel(channel);
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT,
                mNotificationManager.getNotificationChannel(mId).getImportance());
    }

    public void testCreateChannel_addToGroup() throws Exception {
        String oldGroup = null;
        String newGroup = "new group";
        mNotificationManager.createNotificationChannelGroup(
                new NotificationChannelGroup(newGroup, newGroup));

        NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        channel.setGroup(oldGroup);
        mNotificationManager.createNotificationChannel(channel);

        channel.setGroup(newGroup);
        mNotificationManager.createNotificationChannel(channel);

        final NotificationChannel updatedChannel =
                mNotificationManager.getNotificationChannel(mId);
        assertEquals("Failed to add non-grouped channel to a group on update ",
                newGroup, updatedChannel.getGroup());
    }

    public void testCreateChannel_cannotChangeGroup() throws Exception {
        String oldGroup = "old group";
        String newGroup = "new group";
        mNotificationManager.createNotificationChannelGroup(
                new NotificationChannelGroup(oldGroup, oldGroup));
        mNotificationManager.createNotificationChannelGroup(
                new NotificationChannelGroup(newGroup, newGroup));

        NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        channel.setGroup(oldGroup);
        mNotificationManager.createNotificationChannel(channel);
        channel.setGroup(newGroup);
        mNotificationManager.createNotificationChannel(channel);
        final NotificationChannel updatedChannel =
                mNotificationManager.getNotificationChannel(mId);
        assertEquals("Channels should not be allowed to change groups",
                oldGroup, updatedChannel.getGroup());
    }

    public void testCreateSameChannelDoesNotUpdate() throws Exception {
        final NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        mNotificationManager.createNotificationChannel(channel);
        final NotificationChannel channelDupe =
                new NotificationChannel(mId, "name", IMPORTANCE_HIGH);
        mNotificationManager.createNotificationChannel(channelDupe);
        final NotificationChannel createdChannel =
                mNotificationManager.getNotificationChannel(mId);
        compareChannels(channel, createdChannel);
    }

    public void testCreateChannelAlreadyExistsNoOp() throws Exception {
        NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        mNotificationManager.createNotificationChannel(channel);
        NotificationChannel channelDupe =
                new NotificationChannel(mId, "name", IMPORTANCE_HIGH);
        mNotificationManager.createNotificationChannel(channelDupe);
        compareChannels(channel, mNotificationManager.getNotificationChannel(channel.getId()));
    }

    public void testCreateChannelWithGroup() throws Exception {
        NotificationChannelGroup ncg = new NotificationChannelGroup("g", "n");
        mNotificationManager.createNotificationChannelGroup(ncg);
        try {
            NotificationChannel channel =
                    new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
            channel.setGroup(ncg.getId());
            mNotificationManager.createNotificationChannel(channel);
            compareChannels(channel, mNotificationManager.getNotificationChannel(channel.getId()));
        } finally {
            mNotificationManager.deleteNotificationChannelGroup(ncg.getId());
        }
    }

    public void testCreateChannelWithBadGroup() throws Exception {
        NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        channel.setGroup("garbage");
        try {
            mNotificationManager.createNotificationChannel(channel);
            fail("Created notification with bad group");
        } catch (IllegalArgumentException e) {
        }
    }

    public void testCreateChannelInvalidImportance() throws Exception {
        NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_UNSPECIFIED);
        try {
            mNotificationManager.createNotificationChannel(channel);
        } catch (IllegalArgumentException e) {
            //success
        }
    }

    public void testDeleteChannel() throws Exception {
        NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_LOW);
        mNotificationManager.createNotificationChannel(channel);
        compareChannels(channel, mNotificationManager.getNotificationChannel(channel.getId()));
        mNotificationManager.deleteNotificationChannel(channel.getId());
        assertNull(mNotificationManager.getNotificationChannel(channel.getId()));
    }

    public void testCannotDeleteDefaultChannel() throws Exception {
        try {
            mNotificationManager.deleteNotificationChannel(NotificationChannel.DEFAULT_CHANNEL_ID);
            fail("Deleted default channel");
        } catch (IllegalArgumentException e) {
            //success
        }
    }

    public void testGetChannel() throws Exception {
        NotificationChannel channel1 =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        NotificationChannel channel2 =
                new NotificationChannel(
                        UUID.randomUUID().toString(), "name2", IMPORTANCE_HIGH);
        NotificationChannel channel3 =
                new NotificationChannel(
                        UUID.randomUUID().toString(), "name3", IMPORTANCE_LOW);
        NotificationChannel channel4 =
                new NotificationChannel(
                        UUID.randomUUID().toString(), "name4", IMPORTANCE_MIN);
        mNotificationManager.createNotificationChannel(channel1);
        mNotificationManager.createNotificationChannel(channel2);
        mNotificationManager.createNotificationChannel(channel3);
        mNotificationManager.createNotificationChannel(channel4);

        compareChannels(channel2,
                mNotificationManager.getNotificationChannel(channel2.getId()));
        compareChannels(channel3,
                mNotificationManager.getNotificationChannel(channel3.getId()));
        compareChannels(channel1,
                mNotificationManager.getNotificationChannel(channel1.getId()));
        compareChannels(channel4,
                mNotificationManager.getNotificationChannel(channel4.getId()));
    }

    public void testGetChannels() throws Exception {
        NotificationChannel channel1 =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        NotificationChannel channel2 =
                new NotificationChannel(
                        UUID.randomUUID().toString(), "name2", IMPORTANCE_HIGH);
        NotificationChannel channel3 =
                new NotificationChannel(
                        UUID.randomUUID().toString(), "name3", IMPORTANCE_LOW);
        NotificationChannel channel4 =
                new NotificationChannel(
                        UUID.randomUUID().toString(), "name4", IMPORTANCE_MIN);

        Map<String, NotificationChannel> channelMap = new HashMap<>();
        channelMap.put(channel1.getId(), channel1);
        channelMap.put(channel2.getId(), channel2);
        channelMap.put(channel3.getId(), channel3);
        channelMap.put(channel4.getId(), channel4);
        mNotificationManager.createNotificationChannel(channel1);
        mNotificationManager.createNotificationChannel(channel2);
        mNotificationManager.createNotificationChannel(channel3);
        mNotificationManager.createNotificationChannel(channel4);

        mNotificationManager.deleteNotificationChannel(channel3.getId());

        List<NotificationChannel> channels = mNotificationManager.getNotificationChannels();
        for (NotificationChannel nc : channels) {
            if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(nc.getId())) {
                continue;
            }
            if (NOTIFICATION_CHANNEL_ID.equals(nc.getId())) {
                continue;
            }
            assertFalse(channel3.getId().equals(nc.getId()));
            if (!channelMap.containsKey(nc.getId())) {
                // failed cleanup from prior test run; ignore
                continue;
            }
            compareChannels(channelMap.get(nc.getId()), nc);
        }
    }

    public void testRecreateDeletedChannel() throws Exception {
        NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_DEFAULT);
        channel.setShowBadge(true);
        NotificationChannel newChannel = new NotificationChannel(
                channel.getId(), channel.getName(), IMPORTANCE_HIGH);
        mNotificationManager.createNotificationChannel(channel);
        mNotificationManager.deleteNotificationChannel(channel.getId());

        mNotificationManager.createNotificationChannel(newChannel);

        compareChannels(channel,
                mNotificationManager.getNotificationChannel(newChannel.getId()));
    }

    public void testNotify() throws Exception {
        mNotificationManager.cancelAll();

        final int id = 1;
        sendNotification(id, R.drawable.black);
        // test updating the same notification
        sendNotification(id, R.drawable.blue);
        sendNotification(id, R.drawable.yellow);

        // assume that sendNotification tested to make sure individual notifications were present
        StatusBarNotification[] sbns = mNotificationManager.getActiveNotifications();
        for (StatusBarNotification sbn : sbns) {
            if (sbn.getId() != id) {
                fail("we got back other notifications besides the one we posted: "
                        + sbn.getKey());
            }
        }
    }

    public void testSuspendPackage_withoutShellPermission() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        try {
            Process proc = Runtime.getRuntime().exec("cmd notification suspend_package "
                    + mContext.getPackageName());

            // read output of command
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(proc.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line = reader.readLine();
            while (line != null) {
                output.append(line);
                line = reader.readLine();
            }
            reader.close();
            final String outputString = output.toString();

            proc.waitFor();

            // check that the output string had an error / disallowed call since it didn't have
            // shell permission to suspend the package
            assertTrue(outputString, outputString.contains("error"));
            assertTrue(outputString, outputString.contains("permission denied"));
        } catch (InterruptedException e) {
            fail("Unsuccessful shell command");
        }
    }

    public void testSuspendPackage() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        sendNotification(1, R.drawable.black);
        Thread.sleep(500); // wait for notification listener to receive notification
        assertEquals(1, mListener.mPosted.size());

        // suspend package, ranking should be updated with suspended = true
        suspendPackage(mContext.getPackageName(), InstrumentationRegistry.getInstrumentation(),
                true);
        Thread.sleep(500); // wait for notification listener to get response
        NotificationListenerService.RankingMap rankingMap = mListener.mRankingMap;
        NotificationListenerService.Ranking outRanking = new NotificationListenerService.Ranking();
        for (String key : rankingMap.getOrderedKeys()) {
            if (key.contains(mListener.getPackageName())) {
                rankingMap.getRanking(key, outRanking);
                Log.d(TAG, "key=" + key + " suspended=" + outRanking.isSuspended());
                assertTrue(outRanking.isSuspended());
            }
        }

        // unsuspend package, ranking should be updated with suspended = false
        suspendPackage(mContext.getPackageName(), InstrumentationRegistry.getInstrumentation(),
                false);
        Thread.sleep(500); // wait for notification listener to get response
        rankingMap = mListener.mRankingMap;
        for (String key : rankingMap.getOrderedKeys()) {
            if (key.contains(mListener.getPackageName())) {
                rankingMap.getRanking(key, outRanking);
                Log.d(TAG, "key=" + key + " suspended=" + outRanking.isSuspended());
                assertFalse(outRanking.isSuspended());
            }
        }

        mListener.resetData();
    }

    public void testSuspendedPackageSendsNotification() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        // suspend package, post notification while package is suspended, see notification
        // in ranking map with suspended = true
        suspendPackage(mContext.getPackageName(), InstrumentationRegistry.getInstrumentation(),
                true);
        sendNotification(1, R.drawable.black);
        Thread.sleep(500); // wait for notification listener to receive notification
        assertEquals(1, mListener.mPosted.size()); // apps targeting P receive notification
        NotificationListenerService.RankingMap rankingMap = mListener.mRankingMap;
        NotificationListenerService.Ranking outRanking = new NotificationListenerService.Ranking();
        for (String key : rankingMap.getOrderedKeys()) {
            if (key.contains(mListener.getPackageName())) {
                rankingMap.getRanking(key, outRanking);
                Log.d(TAG, "key=" + key + " suspended=" + outRanking.isSuspended());
                assertTrue(outRanking.isSuspended());
            }
        }

        // unsuspend package, ranking should be updated with suspended = false
        suspendPackage(mContext.getPackageName(), InstrumentationRegistry.getInstrumentation(),
                false);
        Thread.sleep(500); // wait for notification listener to get response
        assertEquals(1, mListener.mPosted.size()); // should see previously posted notification
        rankingMap = mListener.mRankingMap;
        for (String key : rankingMap.getOrderedKeys()) {
            if (key.contains(mListener.getPackageName())) {
                rankingMap.getRanking(key, outRanking);
                Log.d(TAG, "key=" + key + " suspended=" + outRanking.isSuspended());
                assertFalse(outRanking.isSuspended());
            }
        }

        mListener.resetData();
    }

    public void testShowBadging_ranking() throws Exception {
        final int originalBadging = Settings.Secure.getInt(
                mContext.getContentResolver(), Settings.Secure.NOTIFICATION_BADGING);

        SystemUtil.runWithShellPermissionIdentity(() ->
                Settings.Secure.putInt(mContext.getContentResolver(),
                        Settings.Secure.NOTIFICATION_BADGING, 1));
        assertEquals(1, Settings.Secure.getInt(
                mContext.getContentResolver(), Settings.Secure.NOTIFICATION_BADGING));

        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);
        try {
            sendNotification(1, R.drawable.black);
            Thread.sleep(500); // wait for notification listener to receive notification
            NotificationListenerService.RankingMap rankingMap = mListener.mRankingMap;
            NotificationListenerService.Ranking outRanking =
                    new NotificationListenerService.Ranking();
            for (String key : rankingMap.getOrderedKeys()) {
                if (key.contains(mListener.getPackageName())) {
                    rankingMap.getRanking(key, outRanking);
                    assertTrue(outRanking.canShowBadge());
                }
            }

            // turn off badging globally
            SystemUtil.runWithShellPermissionIdentity(() ->
                    Settings.Secure.putInt(mContext.getContentResolver(),
                            Settings.Secure.NOTIFICATION_BADGING, 0));

            Thread.sleep(500); // wait for ranking update

            rankingMap = mListener.mRankingMap;
            outRanking = new NotificationListenerService.Ranking();
            for (String key : rankingMap.getOrderedKeys()) {
                if (key.contains(mListener.getPackageName())) {
                    assertFalse(outRanking.canShowBadge());
                }
            }

            mListener.resetData();
        } finally {
            SystemUtil.runWithShellPermissionIdentity(() ->
                    Settings.Secure.putInt(mContext.getContentResolver(),
                            Settings.Secure.NOTIFICATION_BADGING, originalBadging));
        }
    }

    public void testKeyChannelGroupOverrideImportanceExplanation_ranking() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        final int notificationId = 1;
        sendNotification(notificationId, R.drawable.black);
        Thread.sleep(500); // wait for notification listener to receive notification

        NotificationListenerService.RankingMap rankingMap = mListener.mRankingMap;
        NotificationListenerService.Ranking outRanking =
                new NotificationListenerService.Ranking();

        StatusBarNotification sbn = mNotificationHelper.findPostedNotification(null, notificationId,
                SEARCH_TYPE.POSTED);

        // check that the key and channel ids are the same in the ranking as the posted notification
        for (String key : rankingMap.getOrderedKeys()) {
            if (key.contains(mListener.getPackageName())) {
                rankingMap.getRanking(key, outRanking);

                // check notification key match
                assertEquals(sbn.getKey(), outRanking.getKey());

                // check notification channel ids match
                assertEquals(sbn.getNotification().getChannelId(), outRanking.getChannel().getId());

                // check override group key match
                assertEquals(sbn.getOverrideGroupKey(), outRanking.getOverrideGroupKey());

                // check importance explanation isn't null
                assertNotNull(outRanking.getImportanceExplanation());
            }
        }
    }

    public void testNotify_blockedChannel() throws Exception {
        mNotificationManager.cancelAll();

        NotificationChannel channel =
                new NotificationChannel(mId, "name", IMPORTANCE_NONE);
        mNotificationManager.createNotificationChannel(channel);

        int id = 1;
        final Notification notification =
                new Notification.Builder(mContext, mId)
                        .setSmallIcon(R.drawable.black)
                        .setWhen(System.currentTimeMillis())
                        .setContentTitle("notify#" + id)
                        .setContentText("This is #" + id + "notification  ")
                        .build();
        mNotificationManager.notify(id, notification);

        assertTrue(mNotificationHelper.isNotificationGone(id, SEARCH_TYPE.APP));
    }

    public void testCancel() throws Exception {
        final int id = 9;
        sendNotification(id, R.drawable.black);
        // Wait for the notification posted not just enqueued
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
        }
        mNotificationManager.cancel(id);

        assertTrue(mNotificationHelper.isNotificationGone(id, SEARCH_TYPE.APP));
    }

    public void testCancelAll() throws Exception {
        sendNotification(1, R.drawable.black);
        sendNotification(2, R.drawable.blue);
        sendNotification(3, R.drawable.yellow);

        if (DEBUG) {
            Log.d(TAG, "posted 3 notifications, here they are: ");
            StatusBarNotification[] sbns = mNotificationManager.getActiveNotifications();
            for (StatusBarNotification sbn : sbns) {
                Log.d(TAG, "  " + sbn);
            }
            Log.d(TAG, "about to cancel...");
        }
        mNotificationManager.cancelAll();

        for (int id = 1; id <= 3; id++) {
            assertTrue(mNotificationHelper.isNotificationGone(id, SEARCH_TYPE.APP));
        }

    }

    public void testNotifyWithTimeout() throws Exception {
        mNotificationManager.cancelAll();
        final int id = 128;
        final long timeout = 1000;

        final Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.black)
                        .setContentTitle("notify#" + id)
                        .setContentText("This is #" + id + "notification  ")
                        .setTimeoutAfter(timeout)
                        .build();
        mNotificationManager.notify(id, notification);

        assertNotNull(mNotificationHelper.findPostedNotification(null, id, SEARCH_TYPE.APP));

        try {
            Thread.sleep(timeout);
        } catch (InterruptedException ex) {
            // pass
        }
        assertTrue(mNotificationHelper.isNotificationGone(id, SEARCH_TYPE.APP));
    }

    public void testStyle() throws Exception {
        Notification.Style style = new Notification.Style() {
            public boolean areNotificationsVisiblyDifferent(Notification.Style other) {
                return false;
            }
        };

        Notification.Builder builder = new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID);
        style.setBuilder(builder);

        Notification notification = null;
        try {
            notification = style.build();
        } catch (IllegalArgumentException e) {
            fail(e.getMessage());
        }

        assertNotNull(notification);

        Notification builderNotification = builder.build();
        assertEquals(builderNotification, notification);
    }

    public void testStyle_getStandardView() throws Exception {
        Notification.Builder builder = new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID);
        int layoutId = 0;

        TestStyle overrideStyle = new TestStyle();
        overrideStyle.setBuilder(builder);
        RemoteViews result = overrideStyle.testGetStandardView(layoutId);

        assertNotNull(result);
        assertEquals(layoutId, result.getLayoutId());
    }

    private class TestStyle extends Notification.Style {
        public boolean areNotificationsVisiblyDifferent(Notification.Style other) {
            return false;
        }

        public RemoteViews testGetStandardView(int layoutId) {
            // Wrapper method, since getStandardView is protected and otherwise unused in Android
            return getStandardView(layoutId);
        }
    }

    public void testMediaStyle_empty() {
        Notification.MediaStyle style = new Notification.MediaStyle();
        assertNotNull(style);
    }

    public void testMediaStyle() {
        mNotificationManager.cancelAll();
        final int id = 99;
        MediaSession session = new MediaSession(getContext(), "media");

        final Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.black)
                        .setContentTitle("notify#" + id)
                        .setContentText("This is #" + id + "notification  ")
                        .addAction(new Notification.Action.Builder(
                                Icon.createWithResource(getContext(), R.drawable.icon_black),
                                "play", getPendingIntent()).build())
                        .addAction(new Notification.Action.Builder(
                                Icon.createWithResource(getContext(), R.drawable.icon_blue),
                                "pause", getPendingIntent()).build())
                        .setStyle(new Notification.MediaStyle()
                                .setShowActionsInCompactView(0, 1)
                                .setMediaSession(session.getSessionToken()))
                        .build();
        mNotificationManager.notify(id, notification);

        assertNotNull(mNotificationHelper.findPostedNotification(null, id, SEARCH_TYPE.APP));
    }

    public void testInboxStyle() {
        final int id = 100;

        final Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.black)
                        .setContentTitle("notify#" + id)
                        .setContentText("This is #" + id + "notification  ")
                        .addAction(new Notification.Action.Builder(
                                Icon.createWithResource(getContext(), R.drawable.icon_black),
                                "a1", getPendingIntent()).build())
                        .addAction(new Notification.Action.Builder(
                                Icon.createWithResource(getContext(), R.drawable.icon_blue),
                                "a2", getPendingIntent()).build())
                        .setStyle(new Notification.InboxStyle().addLine("line")
                                .setSummaryText("summary"))
                        .build();
        mNotificationManager.notify(id, notification);

        assertNotNull(mNotificationHelper.findPostedNotification(null, id, SEARCH_TYPE.APP));
    }

    public void testBigTextStyle() {
        final int id = 101;

        final Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.black)
                        .setContentTitle("notify#" + id)
                        .setContentText("This is #" + id + "notification  ")
                        .addAction(new Notification.Action.Builder(
                                Icon.createWithResource(getContext(), R.drawable.icon_black),
                                "a1", getPendingIntent()).build())
                        .addAction(new Notification.Action.Builder(
                                Icon.createWithResource(getContext(), R.drawable.icon_blue),
                                "a2", getPendingIntent()).build())
                        .setStyle(new Notification.BigTextStyle()
                                .setBigContentTitle("big title")
                                .bigText("big text")
                                .setSummaryText("summary"))
                        .build();
        mNotificationManager.notify(id, notification);

        assertNotNull(mNotificationHelper.findPostedNotification(null, id, SEARCH_TYPE.APP));
    }

    public void testBigPictureStyle() {
        final int id = 102;

        final Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.black)
                        .setContentTitle("notify#" + id)
                        .setContentText("This is #" + id + "notification  ")
                        .addAction(new Notification.Action.Builder(
                                Icon.createWithResource(getContext(), R.drawable.icon_black),
                                "a1", getPendingIntent()).build())
                        .addAction(new Notification.Action.Builder(
                                Icon.createWithResource(getContext(), R.drawable.icon_blue),
                                "a2", getPendingIntent()).build())
                        .setStyle(new Notification.BigPictureStyle()
                                .setBigContentTitle("title")
                                .bigPicture(Bitmap.createBitmap(100, 100, Bitmap.Config.RGB_565))
                                .bigLargeIcon(
                                        Icon.createWithResource(getContext(), R.drawable.icon_blue))
                                .setSummaryText("summary")
                                .setContentDescription("content description"))
                        .build();
        mNotificationManager.notify(id, notification);

        assertNotNull(mNotificationHelper.findPostedNotification(null, id, SEARCH_TYPE.APP));
    }

    public void testAutogrouping() throws Exception {
        sendNotification(801, R.drawable.black);
        sendNotification(802, R.drawable.blue);
        sendNotification(803, R.drawable.yellow);
        sendNotification(804, R.drawable.yellow);

        assertNotificationCount(5);
        assertAllPostedNotificationsAutogrouped();
    }

    public void testAutogrouping_autogroupStaysUntilAllNotificationsCanceled() throws Exception {
        sendNotification(701, R.drawable.black);
        sendNotification(702, R.drawable.blue);
        sendNotification(703, R.drawable.yellow);
        sendNotification(704, R.drawable.yellow);

        assertNotificationCount(5);
        assertAllPostedNotificationsAutogrouped();

        // Assert all notis stay in the same autogroup until all children are canceled
        for (int i = 704; i > 701; i--) {
            cancelAndPoll(i);
            assertNotificationCount(i - 700);
            assertAllPostedNotificationsAutogrouped();
        }
        cancelAndPoll(701);
        assertNotificationCount(0);
    }

    public void testAutogrouping_autogroupStaysUntilAllNotificationsAddedToGroup()
            throws Exception {
        String newGroup = "new!";
        sendNotification(901, R.drawable.black);
        sendNotification(902, R.drawable.blue);
        sendNotification(903, R.drawable.yellow);
        sendNotification(904, R.drawable.yellow);

        List<Integer> postedIds = new ArrayList<>();
        postedIds.add(901);
        postedIds.add(902);
        postedIds.add(903);
        postedIds.add(904);

        assertNotificationCount(5);
        assertAllPostedNotificationsAutogrouped();

        // Assert all notis stay in the same autogroup until all children are canceled
        for (int i = 904; i > 901; i--) {
            sendNotification(i, newGroup, R.drawable.blue);
            postedIds.remove(postedIds.size() - 1);
            assertNotificationCount(5);
            assertOnlySomeNotificationsAutogrouped(postedIds);
        }
        sendNotification(901, newGroup, R.drawable.blue);
        assertNotificationCount(4); // no more autogroup summary
        postedIds.remove(0);
        assertOnlySomeNotificationsAutogrouped(postedIds);
    }

    public void testNewNotificationsAddedToAutogroup_ifOriginalNotificationsCanceled()
            throws Exception {
        String newGroup = "new!";
        sendNotification(910, R.drawable.black);
        sendNotification(920, R.drawable.blue);
        sendNotification(930, R.drawable.yellow);
        sendNotification(940, R.drawable.yellow);

        List<Integer> postedIds = new ArrayList<>();
        postedIds.add(910);
        postedIds.add(920);
        postedIds.add(930);
        postedIds.add(940);

        assertNotificationCount(5);
        assertAllPostedNotificationsAutogrouped();

        // regroup all but one of the children
        for (int i = postedIds.size() - 1; i > 0; i--) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                // pass
            }
            int id = postedIds.remove(i);
            sendNotification(id, newGroup, R.drawable.blue);
            assertNotificationCount(5);
            assertOnlySomeNotificationsAutogrouped(postedIds);
        }

        // send a new non-grouped notification. since the autogroup summary still exists,
        // the notification should be added to it
        sendNotification(950, R.drawable.blue);
        postedIds.add(950);
        try {
            Thread.sleep(200);
        } catch (InterruptedException ex) {
            // pass
        }
        assertOnlySomeNotificationsAutogrouped(postedIds);
    }

    public void testPostFullScreenIntent_permission() {
        int id = 6000;

        final Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.black)
                        .setWhen(System.currentTimeMillis())
                        .setFullScreenIntent(getPendingIntent(), true)
                        .setContentText("This is #FSI notification")
                        .setContentIntent(getPendingIntent())
                        .build();
        mNotificationManager.notify(id, notification);

        StatusBarNotification n = mNotificationHelper.findPostedNotification(
                null, id, SEARCH_TYPE.APP);
        assertNotNull(n);
        assertEquals(notification.fullScreenIntent, n.getNotification().fullScreenIntent);
    }

    public void testNotificationDelegate_grantAndPost() throws Exception {
        final Intent intent = new Intent(mContext, GetResultActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        GetResultActivity activity = (GetResultActivity) mInstrumentation.startActivitySync(intent);
        mInstrumentation.waitForIdleSync();
        activity.clearResult();

        // grant this test permission to post
        final Intent activityIntent = new Intent();
        activityIntent.setPackage(TEST_APP);
        activityIntent.setAction(Intent.ACTION_MAIN);
        activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        activity.startActivityForResult(activityIntent, REQUEST_CODE);
        assertEquals(RESULT_OK, activity.getResult().resultCode);

        // send notification
        Notification n = new Notification.Builder(mContext, "channel")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
        mNotificationManager.notifyAsPackage(TEST_APP, "tag", 0, n);

        assertNotNull(mNotificationHelper.findPostedNotification("tag", 0, SEARCH_TYPE.APP));
        final Intent revokeIntent = new Intent(Intent.ACTION_MAIN);
        revokeIntent.setClassName(TEST_APP, REVOKE_CLASS);
        activity.startActivityForResult(revokeIntent, REQUEST_CODE);
        assertEquals(RESULT_OK, activity.getResult().resultCode);
    }

    public void testNotificationDelegate_grantAndPostAndCancel() throws Exception {
        final Intent intent = new Intent(mContext, GetResultActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        GetResultActivity activity = (GetResultActivity) mInstrumentation.startActivitySync(intent);
        mInstrumentation.waitForIdleSync();
        activity.clearResult();

        // grant this test permission to post
        final Intent activityIntent = new Intent();
        activityIntent.setPackage(TEST_APP);
        activityIntent.setAction(Intent.ACTION_MAIN);
        activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        activity.startActivityForResult(activityIntent, REQUEST_CODE);
        assertEquals(RESULT_OK, activity.getResult().resultCode);

        // send notification
        Notification n = new Notification.Builder(mContext, "channel")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
        mNotificationManager.notifyAsPackage(TEST_APP, "toBeCanceled", 10000, n);
        assertNotNull(mNotificationHelper.findPostedNotification("toBeCanceled", 10000,
                SEARCH_TYPE.APP));
        mNotificationManager.cancelAsPackage(TEST_APP, "toBeCanceled", 10000);
        assertTrue(mNotificationHelper.isNotificationGone(10000, SEARCH_TYPE.APP));
        final Intent revokeIntent = new Intent(Intent.ACTION_MAIN);
        revokeIntent.setClassName(TEST_APP, REVOKE_CLASS);
        activity.startActivityForResult(revokeIntent, REQUEST_CODE);
        assertEquals(RESULT_OK, activity.getResult().resultCode);
    }

    public void testNotificationDelegate_cannotCancelNotificationsPostedByDelegator()
            throws Exception {
        final Intent intent = new Intent(mContext, GetResultActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        GetResultActivity activity = (GetResultActivity) mInstrumentation.startActivitySync(intent);
        mInstrumentation.waitForIdleSync();
        activity.clearResult();

        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        // grant this test permission to post
        final Intent activityIntent = new Intent(Intent.ACTION_MAIN);
        activityIntent.setClassName(TEST_APP, DELEGATE_POST_CLASS);

        activity.startActivityForResult(activityIntent, REQUEST_CODE);
        assertEquals(RESULT_OK, activity.getResult().resultCode);

        assertNotNull(mNotificationHelper.findPostedNotification(null, 9, SEARCH_TYPE.LISTENER));

        try {
            mNotificationManager.cancelAsPackage(TEST_APP, null, 9);
            fail("Delegate should not be able to cancel notification they did not post");
        } catch (SecurityException e) {
            // yay
        }

        // double check that the notification does still exist
        assertNotNull(mNotificationHelper.findPostedNotification(null, 9, SEARCH_TYPE.LISTENER));

        final Intent revokeIntent = new Intent(Intent.ACTION_MAIN);
        revokeIntent.setClassName(TEST_APP, REVOKE_CLASS);
        activity.startActivityForResult(revokeIntent, REQUEST_CODE);
        assertEquals(RESULT_OK, activity.getResult().resultCode);
    }

    public void testNotificationDelegate_grantAndReadChannels() throws Exception {
        final Intent intent = new Intent(mContext, GetResultActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        GetResultActivity activity = (GetResultActivity) mInstrumentation.startActivitySync(intent);
        mInstrumentation.waitForIdleSync();
        activity.clearResult();

        // grant this test permission to post
        final Intent activityIntent = new Intent();
        activityIntent.setPackage(TEST_APP);
        activityIntent.setAction(Intent.ACTION_MAIN);
        activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        activity.startActivityForResult(activityIntent, REQUEST_CODE);
        assertEquals(RESULT_OK, activity.getResult().resultCode);

        List<NotificationChannel> channels =
                mContext.createPackageContextAsUser(TEST_APP, /* flags= */ 0, mContext.getUser())
                        .getSystemService(NotificationManager.class)
                        .getNotificationChannels();

        assertNotNull(channels);

        final Intent revokeIntent = new Intent(Intent.ACTION_MAIN);
        revokeIntent.setClassName(TEST_APP, REVOKE_CLASS);
        activity.startActivityForResult(revokeIntent, REQUEST_CODE);
        assertEquals(RESULT_OK, activity.getResult().resultCode);
    }

    public void testNotificationDelegate_grantAndReadChannel() throws Exception {
        final Intent intent = new Intent(mContext, GetResultActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        GetResultActivity activity = (GetResultActivity) mInstrumentation.startActivitySync(intent);
        mInstrumentation.waitForIdleSync();
        activity.clearResult();

        // grant this test permission to post
        final Intent activityIntent = new Intent();
        activityIntent.setPackage(TEST_APP);
        activityIntent.setAction(Intent.ACTION_MAIN);
        activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        activity.startActivityForResult(activityIntent, REQUEST_CODE);
        assertEquals(RESULT_OK, activity.getResult().resultCode);

        NotificationChannel channel =
                mContext.createPackageContextAsUser(TEST_APP, /* flags= */ 0, mContext.getUser())
                        .getSystemService(NotificationManager.class)
                        .getNotificationChannel("channel");

        assertNotNull(channel);

        final Intent revokeIntent = new Intent(Intent.ACTION_MAIN);
        revokeIntent.setClassName(TEST_APP, REVOKE_CLASS);
        activity.startActivityForResult(revokeIntent, REQUEST_CODE);
        assertEquals(RESULT_OK, activity.getResult().resultCode);
    }

    public void testNotificationDelegate_grantAndRevoke() throws Exception {
        final Intent intent = new Intent(mContext, GetResultActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        GetResultActivity activity = (GetResultActivity) mInstrumentation.startActivitySync(intent);
        mInstrumentation.waitForIdleSync();
        activity.clearResult();

        // grant this test permission to post
        final Intent activityIntent = new Intent();
        activityIntent.setPackage(TEST_APP);
        activityIntent.setAction(Intent.ACTION_MAIN);
        activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        activity.startActivityForResult(activityIntent, REQUEST_CODE);
        assertEquals(RESULT_OK, activity.getResult().resultCode);

        assertTrue(mNotificationManager.canNotifyAsPackage(TEST_APP));

        final Intent revokeIntent = new Intent(Intent.ACTION_MAIN);
        revokeIntent.setClassName(TEST_APP, REVOKE_CLASS);
        activity.startActivityForResult(revokeIntent, REQUEST_CODE);
        assertEquals(RESULT_OK, activity.getResult().resultCode);

        try {
            // send notification
            Notification n = new Notification.Builder(mContext, "channel")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .build();
            mNotificationManager.notifyAsPackage(TEST_APP, "tag", 0, n);
            fail("Should not be able to post as a delegate when permission revoked");
        } catch (SecurityException e) {
            // yay
        }
    }

    public void testNotificationIcon() {
        int id = 6000;

        Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setWhen(System.currentTimeMillis())
                        .setFullScreenIntent(getPendingIntent(), true)
                        .setContentText("This notification has a resource icon")
                        .setContentIntent(getPendingIntent())
                        .build();
        mNotificationManager.notify(id, notification);

        notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(Icon.createWithResource(mContext, android.R.id.icon))
                        .setWhen(System.currentTimeMillis())
                        .setFullScreenIntent(getPendingIntent(), true)
                        .setContentText("This notification has an Icon icon")
                        .setContentIntent(getPendingIntent())
                        .build();
        mNotificationManager.notify(id, notification);

        StatusBarNotification n = mNotificationHelper.findPostedNotification(
                null, id, SEARCH_TYPE.APP);
        assertNotNull(n);
    }

    public void testShouldHideSilentStatusIcons() throws Exception {
        try {
            mNotificationManager.shouldHideSilentStatusBarIcons();
            fail("Non-privileged apps should not get this information");
        } catch (SecurityException e) {
            // pass
        }

        mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        // no exception this time
        mNotificationManager.shouldHideSilentStatusBarIcons();
    }

    /* Confirm that the optional methods of TestNotificationListener still exist and
     * don't fail. */
    public void testNotificationListenerMethods() {
        NotificationListenerService listener = new TestNotificationListener();
        listener.onListenerConnected();

        listener.onSilentStatusBarIconsVisibilityChanged(false);

        listener.onNotificationPosted(null);
        listener.onNotificationPosted(null, null);

        listener.onNotificationRemoved(null);
        listener.onNotificationRemoved(null, null);

        listener.onNotificationChannelGroupModified("", UserHandle.CURRENT, null,
                NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED);
        listener.onNotificationChannelModified("", UserHandle.CURRENT, null,
                NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED);

        listener.onListenerDisconnected();
    }

    private void performNotificationProviderAction(@NonNull String action) {
        // Create an intent to launch an activity which just posts or cancels notifications
        Intent activityIntent = new Intent(Intent.ACTION_MAIN);
        activityIntent.setClassName(NOTIFICATIONPROVIDER, RICH_NOTIFICATION_ACTIVITY);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activityIntent.putExtra("action", action);
        mContext.startActivity(activityIntent);
    }

    public void testNotificationUriPermissionsGranted() throws Exception {
        Uri background7Uri = Uri.parse(
                "content://com.android.test.notificationprovider.provider/background7.png");
        Uri background8Uri = Uri.parse(
                "content://com.android.test.notificationprovider.provider/background8.png");

        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        try {
            // Post #7
            performNotificationProviderAction("send-7");

            assertEquals(background7Uri, getNotificationBackgroundImageUri(7));
            assertTrue(mNotificationHelper.isNotificationGone(8, SEARCH_TYPE.LISTENER));
            assertAccessible(background7Uri);
            assertInaccessible(background8Uri);

            // Post #8
            performNotificationProviderAction("send-8");

            assertEquals(background7Uri, getNotificationBackgroundImageUri(7));
            assertEquals(background8Uri, getNotificationBackgroundImageUri(8));
            assertAccessible(background7Uri);
            assertAccessible(background8Uri);

            // Cancel #7
            performNotificationProviderAction("cancel-7");

            assertTrue(mNotificationHelper.isNotificationGone(7, SEARCH_TYPE.LISTENER));
            assertEquals(background8Uri, getNotificationBackgroundImageUri(8));
            assertInaccessible(background7Uri);
            assertAccessible(background8Uri);

            // Cancel #8
            performNotificationProviderAction("cancel-8");

            assertTrue(mNotificationHelper.isNotificationGone(7, SEARCH_TYPE.LISTENER));
            assertTrue(mNotificationHelper.isNotificationGone(8, SEARCH_TYPE.LISTENER));
            assertInaccessible(background7Uri);
            assertInaccessible(background8Uri);

        } finally {
            // Clean up -- reset any remaining notifications
            performNotificationProviderAction("reset");
            Thread.sleep(500);
        }
    }

    public void testNotificationUriPermissionsGrantedToNewListeners() throws Exception {
        Uri background7Uri = Uri.parse(
                "content://com.android.test.notificationprovider.provider/background7.png");

        try {
            // Post #7
            performNotificationProviderAction("send-7");
            // Don't have access the notification yet, but we can test the URI
            assertInaccessible(background7Uri);

            mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
            assertNotNull(mListener);

            mNotificationHelper.findPostedNotification(null, 7, SEARCH_TYPE.LISTENER);

            assertEquals(background7Uri, getNotificationBackgroundImageUri(7));
            assertAccessible(background7Uri);

        } finally {
            // Clean Up -- Cancel #7
            performNotificationProviderAction("cancel-7");
            Thread.sleep(500);
        }
    }

    public void testNotificationUriPermissionsRevokedFromRemovedListeners() throws Exception {
        Uri background7Uri = Uri.parse(
                "content://com.android.test.notificationprovider.provider/background7.png");

        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        mListener = TestNotificationListener.getInstance();
        assertNotNull(mListener);

        try {
            // Post #7
            performNotificationProviderAction("send-7");
            mNotificationHelper.findPostedNotification(null, 7, SEARCH_TYPE.POSTED);

            assertEquals(background7Uri, getNotificationBackgroundImageUri(7));
            assertAccessible(background7Uri);

            // Remove the listener to ensure permissions get revoked
            mNotificationHelper.disableListener(STUB_PACKAGE_NAME);
            Thread.sleep(500); // wait for listener to be disabled

            assertInaccessible(background7Uri);

        } finally {
            // Clean Up -- Cancel #7
            performNotificationProviderAction("cancel-7");
            Thread.sleep(500);
        }
    }

    private class NotificationListenerConnection implements ServiceConnection {
        private final Semaphore mSemaphore = new Semaphore(0);

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            if (URI_ACCESS_SERVICE.equals(className)) {
                mNotificationUriAccessService = INotificationUriAccessService.Stub.asInterface(
                        service);
            }
            if (NLS_CONTROL_SERVICE.equals(className)) {
                mNLSControlService = INLSControlService.Stub.asInterface(service);
            }
            mSemaphore.release();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            if (URI_ACCESS_SERVICE.equals(className)) {
                mNotificationUriAccessService = null;
            }
            if (NLS_CONTROL_SERVICE.equals(className)) {
                mNLSControlService = null;
            }
        }

        public void waitForService() {
            try {
                if (mSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
            }
            fail("failed to connec to service");
        }
    }

    public void testNotificationUriPermissionsRevokedOnlyFromRemovedListeners() throws Exception {
        Uri background7Uri = Uri.parse(
                "content://com.android.test.notificationprovider.provider/background7.png");

        // Connect to a service in the NotificationListener app which allows us to validate URI
        // permissions granted to a second app, so that we show that permissions aren't being
        // revoked too broadly.
        final Intent intent = new Intent();
        intent.setComponent(URI_ACCESS_SERVICE);
        NotificationListenerConnection connection = new NotificationListenerConnection();
        mContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        connection.waitForService();

        // Before starting the test, make sure the service works, that there is no listener, and
        // that the URI starts inaccessible to that process.
        mNotificationUriAccessService.ensureNotificationListenerServiceConnected(false);
        assertFalse(mNotificationUriAccessService.isFileUriAccessible(background7Uri));

        // Give the NotificationListener app access to notifications, and validate that.
        toggleExternalListenerAccess(new ComponentName("com.android.test.notificationlistener",
                "com.android.test.notificationlistener.TestNotificationListener"), true);
        Thread.sleep(500);
        mNotificationUriAccessService.ensureNotificationListenerServiceConnected(true);
        assertFalse(mNotificationUriAccessService.isFileUriAccessible(background7Uri));

        // Give the test app access to notifications, and get that listener
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        try {
            try {
                // Post #7
                performNotificationProviderAction("send-7");

                // Check that both the test app (this code) and the external app have URI access.
                assertEquals(background7Uri, getNotificationBackgroundImageUri(7));
                assertAccessible(background7Uri);
                assertTrue(mNotificationUriAccessService.isFileUriAccessible(background7Uri));

                // Remove the listener to ensure permissions get revoked
                mNotificationHelper.disableListener(STUB_PACKAGE_NAME);
                Thread.sleep(500); // wait for listener to be disabled

                // Ensure that revoking listener access to this one app does not effect the other.
                assertInaccessible(background7Uri);
                assertTrue(mNotificationUriAccessService.isFileUriAccessible(background7Uri));

            } finally {
                // Clean Up -- Cancel #7
                performNotificationProviderAction("cancel-7");
                Thread.sleep(500);
            }

            // Finally, cancelling the permission must still revoke those other permissions.
            assertFalse(mNotificationUriAccessService.isFileUriAccessible(background7Uri));

        } finally {
            // Clean Up -- Make sure the external listener is has access revoked
            toggleExternalListenerAccess(new ComponentName("com.android.test.notificationlistener",
                    "com.android.test.notificationlistener.TestNotificationListener"), false);
        }
    }

    public void testNotificationListenerRequestUnbind() throws Exception {
        final Intent intent = new Intent();
        intent.setComponent(NLS_CONTROL_SERVICE);
        NotificationListenerConnection connection = new NotificationListenerConnection();
        mContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        connection.waitForService();

        // Give the NotificationListener app access to notifications, and validate that.
        toggleExternalListenerAccess(NO_AUTOBIND_NLS, true);
        Thread.sleep(500);

        // Give the test app access to notifications, and get that listener
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        try {
            // Check that the listener service is not auto-bound (manifest meta-data)
            assertFalse(mNLSControlService.isNotificationListenerConnected());

            // Request bind NLS
            mNLSControlService.requestRebindComponent();
            Thread.sleep(500);
            assertTrue(mNLSControlService.isNotificationListenerConnected());

            // Request unbind NLS
            mNLSControlService.requestUnbindComponent();
            Thread.sleep(500);
            assertFalse(mNLSControlService.isNotificationListenerConnected());
        } finally {
            // Clean Up -- Make sure the external listener is has access revoked
            toggleExternalListenerAccess(NO_AUTOBIND_NLS, false);
        }
    }

    public void testNotificationListenerAutobindMetaData() throws Exception {
        final ServiceInfo info = mPackageManager.getServiceInfo(NO_AUTOBIND_NLS,
                PackageManager.GET_META_DATA
                        | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);

        assertNotNull(info);
        assertTrue(info.metaData.containsKey(META_DATA_DEFAULT_AUTOBIND));
        assertFalse(info.metaData.getBoolean(META_DATA_DEFAULT_AUTOBIND, true));
    }

    private void assertAccessible(Uri uri)
            throws IOException {
        ContentResolver contentResolver = mContext.getContentResolver();
        for (int tries = 3; tries-- > 0; ) {
            try (AssetFileDescriptor fd = contentResolver.openAssetFile(uri, "r", null)) {
                if (fd != null) {
                    return;
                }
            } catch (SecurityException e) {
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
            }
        }
        fail("Uri " + uri + "is not accessible");
    }

    private void assertInaccessible(Uri uri)
            throws IOException {
        ContentResolver contentResolver = mContext.getContentResolver();
        for (int tries = 3; tries-- > 0; ) {
            try (AssetFileDescriptor fd = contentResolver.openAssetFile(uri, "r", null)) {
            } catch (SecurityException e) {
               return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
            }
        }
        fail("Uri " + uri + "is still accessible");
    }

    @NonNull
    private Uri getNotificationBackgroundImageUri(int notificationId) {
        StatusBarNotification sbn = mNotificationHelper.findPostedNotification(null, notificationId,
                SEARCH_TYPE.LISTENER);
        assertNotNull(sbn);
        String imageUriString = sbn.getNotification().extras
                .getString(Notification.EXTRA_BACKGROUND_IMAGE_URI);
        assertNotNull(imageUriString);
        return Uri.parse(imageUriString);
    }

    private <T> T uncheck(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    public void testNotificationListener_setNotificationsShown() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);
        final int notificationId1 = 1003;
        final int notificationId2 = 1004;

        sendNotification(notificationId1, R.drawable.black);
        sendNotification(notificationId2, R.drawable.black);
        Thread.sleep(500); // wait for notification listener to receive notification

        StatusBarNotification sbn1 = mNotificationHelper.findPostedNotification(
                null, notificationId1, SEARCH_TYPE.LISTENER);
        StatusBarNotification sbn2 = mNotificationHelper.findPostedNotification(
                null, notificationId2, SEARCH_TYPE.LISTENER);
        mListener.setNotificationsShown(new String[]{sbn1.getKey()});

        mNotificationHelper.disableListener(STUB_PACKAGE_NAME);
        Thread.sleep(500); // wait for listener to be disallowed
        try {
            mListener.setNotificationsShown(new String[]{sbn2.getKey()});
            fail("Should not be able to set shown if listener access isn't granted");
        } catch (SecurityException e) {
            // expected
        }
    }

    public void testNotificationListener_getNotificationChannels() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        try {
            mListener.getNotificationChannels(mContext.getPackageName(), UserHandle.CURRENT);
            fail("Shouldn't be able get channels without CompanionDeviceManager#getAssociations()");
        } catch (SecurityException e) {
            // expected
        }
    }

    public void testNotificationListener_getNotificationChannelGroups() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);
        try {
            mListener.getNotificationChannelGroups(mContext.getPackageName(), UserHandle.CURRENT);
            fail("Should not be able get groups without CompanionDeviceManager#getAssociations()");
        } catch (SecurityException e) {
            // expected
        }
    }

    public void testNotificationListener_updateNotificationChannel() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "name", IMPORTANCE_DEFAULT);
        try {
            mListener.updateNotificationChannel(mContext.getPackageName(), UserHandle.CURRENT,
                    channel);
            fail("Shouldn't be able to update channel without "
                    + "CompanionDeviceManager#getAssociations()");
        } catch (SecurityException e) {
            // expected
        }
    }

    public void testNotificationListener_getActiveNotifications() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);
        final int notificationId1 = 1001;
        final int notificationId2 = 1002;

        sendNotification(notificationId1, R.drawable.black);
        sendNotification(notificationId2, R.drawable.black);
        Thread.sleep(500); // wait for notification listener to receive notification

        StatusBarNotification sbn1 = mNotificationHelper.findPostedNotification(
                null, notificationId1, SEARCH_TYPE.LISTENER);
        StatusBarNotification sbn2 = mNotificationHelper.findPostedNotification(
                null, notificationId2, SEARCH_TYPE.LISTENER);
        StatusBarNotification[] notifs =
                mListener.getActiveNotifications(new String[]{sbn2.getKey(), sbn1.getKey()});
        assertEquals(sbn2.getKey(), notifs[0].getKey());
        assertEquals(sbn2.getId(), notifs[0].getId());
        assertEquals(sbn2.getPackageName(), notifs[0].getPackageName());

        assertEquals(sbn1.getKey(), notifs[1].getKey());
        assertEquals(sbn1.getId(), notifs[1].getId());
        assertEquals(sbn1.getPackageName(), notifs[1].getPackageName());
    }


    public void testNotificationListener_getCurrentRanking() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        sendNotification(1, R.drawable.black);
        mNotificationHelper.findPostedNotification(null, 1, SEARCH_TYPE.POSTED);

        assertEquals(mListener.mRankingMap, mListener.getCurrentRanking());
    }

    public void testNotificationListener_cancelNotifications() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);
        final int notificationId = 1006;

        sendNotification(notificationId, R.drawable.black);
        Thread.sleep(500); // wait for notification listener to receive notification

        StatusBarNotification sbn = mNotificationHelper.findPostedNotification(null, notificationId,
                SEARCH_TYPE.LISTENER);

        mListener.cancelNotification(sbn.getPackageName(), sbn.getTag(), sbn.getId());
        if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.LOLLIPOP) {
            assertNotNull(mNotificationHelper.findPostedNotification(null, notificationId,
                    SEARCH_TYPE.LISTENER));
        } else {
            // Tested in LegacyNotificationManager20Test
            assertTrue(mNotificationHelper.isNotificationGone(
                    notificationId, SEARCH_TYPE.LISTENER));
        }

        mListener.cancelNotifications(new String[]{sbn.getKey()});
        if (getCancellationReason(sbn.getKey())
                != NotificationListenerService.REASON_LISTENER_CANCEL) {
            fail("Failed to cancel notification id=" + notificationId);
        }
    }

    public void testNotificationAssistant_cancelNotifications() throws Exception {
        mAssistant = mNotificationHelper.enableAssistant(STUB_PACKAGE_NAME);
        assertNotNull(mAssistant);
        final int notificationId = 1006;

        sendNotification(notificationId, R.drawable.black);
        Thread.sleep(500); // wait for notification listener to receive notification

        StatusBarNotification sbn = mNotificationHelper.findPostedNotification(null, notificationId,
                SEARCH_TYPE.APP);

        mAssistant.cancelNotifications(new String[]{sbn.getKey()});
        int gotReason = getAssistantCancellationReason(sbn.getKey());
        if (gotReason != NotificationListenerService.REASON_ASSISTANT_CANCEL) {
            fail("Failed cancellation from assistant, notification id=" + notificationId
                    + "; got reason=" + gotReason);
        }
    }

    public void testNotificationManagerPolicy_priorityCategoriesToString() {
        String zeroString = NotificationManager.Policy.priorityCategoriesToString(0);
        assertEquals("priorityCategories of 0 produces empty string", "", zeroString);

        String oneString = NotificationManager.Policy.priorityCategoriesToString(1);
        assertNotNull("priorityCategories of 1 returns a string", oneString);
        boolean lengthGreaterThanZero = oneString.length() > 0;
        assertTrue("priorityCategories of 1 returns a string with length greater than 0",
                lengthGreaterThanZero);

        String badNumberString = NotificationManager.Policy.priorityCategoriesToString(1234567);
        assertNotNull("priorityCategories with a non-relevant int returns a string",
                badNumberString);
    }

    public void testNotificationManagerPolicy_prioritySendersToString() {
        String zeroString = NotificationManager.Policy.prioritySendersToString(0);
        assertNotNull("prioritySenders of 1 returns a string", zeroString);
        boolean lengthGreaterThanZero = zeroString.length() > 0;
        assertTrue("prioritySenders of 1 returns a string with length greater than 0",
                lengthGreaterThanZero);

        String badNumberString = NotificationManager.Policy.prioritySendersToString(1234567);
        assertNotNull("prioritySenders with a non-relevant int returns a string", badNumberString);
    }

    public void testNotificationManagerPolicy_suppressedEffectsToString() {
        String zeroString = NotificationManager.Policy.suppressedEffectsToString(0);
        assertEquals("suppressedEffects of 0 produces empty string", "", zeroString);

        String oneString = NotificationManager.Policy.suppressedEffectsToString(1);
        assertNotNull("suppressedEffects of 1 returns a string", oneString);
        boolean lengthGreaterThanZero = oneString.length() > 0;
        assertTrue("suppressedEffects of 1 returns a string with length greater than 0",
                lengthGreaterThanZero);

        String badNumberString = NotificationManager.Policy.suppressedEffectsToString(1234567);
        assertNotNull("suppressedEffects with a non-relevant int returns a string",
                badNumberString);
    }

    public void testOriginalChannelImportance() {
        NotificationChannel channel = new NotificationChannel(mId, "my channel", IMPORTANCE_HIGH);

        mNotificationManager.createNotificationChannel(channel);

        NotificationChannel actual = mNotificationManager.getNotificationChannel(channel.getId());
        assertEquals(IMPORTANCE_HIGH, actual.getImportance());
        assertEquals(IMPORTANCE_HIGH, actual.getOriginalImportance());

        // Apps are allowed to downgrade channel importance if the user has not changed any
        // fields on this channel yet.
        channel.setImportance(IMPORTANCE_DEFAULT);
        mNotificationManager.createNotificationChannel(channel);

        actual = mNotificationManager.getNotificationChannel(channel.getId());
        assertEquals(IMPORTANCE_DEFAULT, actual.getImportance());
        assertEquals(IMPORTANCE_HIGH, actual.getOriginalImportance());
    }

    public void testCreateConversationChannel() {
        final NotificationChannel channel =
                new NotificationChannel(mId, "Messages", IMPORTANCE_DEFAULT);

        String conversationId = "person a";

        final NotificationChannel conversationChannel =
                new NotificationChannel(mId + "child",
                        "Messages from " + conversationId, IMPORTANCE_DEFAULT);
        conversationChannel.setConversationId(channel.getId(), conversationId);

        mNotificationManager.createNotificationChannel(channel);
        mNotificationManager.createNotificationChannel(conversationChannel);

        compareChannels(conversationChannel,
                mNotificationManager.getNotificationChannel(channel.getId(), conversationId));
    }

    public void testConversationRankingFields() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        createDynamicShortcut();
        mNotificationManager.notify(177, getConversationNotification().build());

        assertNotNull(mNotificationHelper.findPostedNotification(null, 177, SEARCH_TYPE.LISTENER));
        Thread.sleep(500); // wait for notification listener to receive notification
        assertEquals(1, mListener.mPosted.size());

        NotificationListenerService.RankingMap rankingMap = mListener.mRankingMap;
        NotificationListenerService.Ranking outRanking = new NotificationListenerService.Ranking();
        for (String key : rankingMap.getOrderedKeys()) {
            if (key.contains(mListener.getPackageName())) {
                rankingMap.getRanking(key, outRanking);
                assertTrue(outRanking.isConversation());
                assertEquals(SHARE_SHORTCUT_ID, outRanking.getConversationShortcutInfo().getId());
            }
        }
    }

    public void testDemoteConversationChannel() {
        final NotificationChannel channel =
                new NotificationChannel(mId, "Messages", IMPORTANCE_DEFAULT);

        String conversationId = "person a";

        final NotificationChannel conversationChannel =
                new NotificationChannel(mId + "child",
                        "Messages from " + conversationId, IMPORTANCE_DEFAULT);
        conversationChannel.setConversationId(channel.getId(), conversationId);

        mNotificationManager.createNotificationChannel(channel);
        mNotificationManager.createNotificationChannel(conversationChannel);

        conversationChannel.setDemoted(true);

        SystemUtil.runWithShellPermissionIdentity(() ->
                mNotificationManager.updateNotificationChannel(
                        mContext.getPackageName(), android.os.Process.myUid(), channel));

        assertEquals(false, mNotificationManager.getNotificationChannel(
                channel.getId(), conversationId).isDemoted());
    }

    public void testDeleteConversationChannels() throws Exception {
        setUpNotifListener();

        createDynamicShortcut();

        final NotificationChannel channel =
                new NotificationChannel(mId, "Messages", IMPORTANCE_DEFAULT);

        final NotificationChannel conversationChannel =
                new NotificationChannel(mId + "child",
                        "Messages from " + SHARE_SHORTCUT_ID, IMPORTANCE_DEFAULT);
        conversationChannel.setConversationId(channel.getId(), SHARE_SHORTCUT_ID);

        mNotificationManager.createNotificationChannel(channel);
        mNotificationManager.createNotificationChannel(conversationChannel);

        mNotificationManager.notify(177, getConversationNotification().build());

        assertNotNull(mNotificationHelper.findPostedNotification(null, 177, SEARCH_TYPE.LISTENER));
        Thread.sleep(500); // wait for notification listener to receive notification
        assertEquals(1, mListener.mPosted.size());

        deleteShortcuts();

        Thread.sleep(300); // wait for deletion to propagate

        assertFalse(mNotificationManager.getNotificationChannel(channel.getId(),
                conversationChannel.getConversationId()).isConversation());

    }

    /**
     * This method verifies that an app can't bypass background restrictions by retrieving their own
     * notification and triggering it.
     */
    @AsbSecurityTest(cveBugId = 185388103)
    public void testActivityStartFromRetrievedNotification_isBlocked() throws Exception {
        deactivateGracePeriod();
        EventCallback callback = new EventCallback();
        int notificationId = 6007;

        // Post notification and fire its pending intent
        sendTrampolineMessage(TRAMPOLINE_SERVICE_API_30, MESSAGE_SERVICE_NOTIFICATION,
                notificationId, callback);
        PollingCheck.waitFor(TIMEOUT_MS, () -> uncheck(() -> {
            sendTrampolineMessage(TRAMPOLINE_SERVICE, MESSAGE_CLICK_NOTIFICATION, notificationId,
                    callback);
            // timeoutMs = 1ms below because surrounding waitFor already handles retry & timeout.
            return callback.waitFor(EventCallback.NOTIFICATION_CLICKED, /* timeoutMs */ 1);
        }));

        assertFalse("Activity start should have been blocked",
                callback.waitFor(EventCallback.ACTIVITY_STARTED, TIMEOUT_MS));
    }

    public void testActivityStartOnBroadcastTrampoline_isBlocked() throws Exception {
        deactivateGracePeriod();
        setUpNotifListener();
        mListener.addTestPackage(TRAMPOLINE_APP);
        EventCallback callback = new EventCallback();
        int notificationId = 6001;

        // Post notification and fire its pending intent
        sendTrampolineMessage(TRAMPOLINE_SERVICE, MESSAGE_BROADCAST_NOTIFICATION, notificationId,
                callback);
        StatusBarNotification statusBarNotification = mNotificationHelper.findPostedNotification(
                null, notificationId, SEARCH_TYPE.LISTENER);
        assertNotNull("Notification not posted on time", statusBarNotification);
        statusBarNotification.getNotification().contentIntent.send();

        assertTrue("Broadcast not received on time",
                callback.waitFor(EventCallback.BROADCAST_RECEIVED, TIMEOUT_LONG_MS));
        assertFalse("Activity start should have been blocked",
                callback.waitFor(EventCallback.ACTIVITY_STARTED, TIMEOUT_MS));
    }

    public void testActivityStartOnServiceTrampoline_isBlocked() throws Exception {
        deactivateGracePeriod();
        setUpNotifListener();
        mListener.addTestPackage(TRAMPOLINE_APP);
        EventCallback callback = new EventCallback();
        int notificationId = 6002;

        // Post notification and fire its pending intent
        sendTrampolineMessage(TRAMPOLINE_SERVICE, MESSAGE_SERVICE_NOTIFICATION, notificationId,
                callback);
        StatusBarNotification statusBarNotification = mNotificationHelper.findPostedNotification(
                null, notificationId, SEARCH_TYPE.LISTENER);
        assertNotNull("Notification not posted on time", statusBarNotification);
        statusBarNotification.getNotification().contentIntent.send();

        assertTrue("Service not started on time",
                callback.waitFor(EventCallback.SERVICE_STARTED, TIMEOUT_MS));
        assertFalse("Activity start should have been blocked",
                callback.waitFor(EventCallback.ACTIVITY_STARTED, TIMEOUT_MS));
    }

    public void testActivityStartOnBroadcastTrampoline_whenApi30_isAllowed() throws Exception {
        deactivateGracePeriod();
        setUpNotifListener();
        mListener.addTestPackage(TRAMPOLINE_APP_API_30);
        EventCallback callback = new EventCallback();
        int notificationId = 6003;

        // Post notification and fire its pending intent
        sendTrampolineMessage(TRAMPOLINE_SERVICE_API_30, MESSAGE_BROADCAST_NOTIFICATION,
                notificationId, callback);
        StatusBarNotification statusBarNotification = mNotificationHelper.findPostedNotification(
                null, notificationId, SEARCH_TYPE.LISTENER);
        assertNotNull("Notification not posted on time", statusBarNotification);
        statusBarNotification.getNotification().contentIntent.send();

        assertTrue("Broadcast not received on time",
                callback.waitFor(EventCallback.BROADCAST_RECEIVED, TIMEOUT_LONG_MS));
        assertTrue("Activity not started",
                callback.waitFor(EventCallback.ACTIVITY_STARTED, TIMEOUT_MS));
    }

    public void testActivityStartOnServiceTrampoline_whenApi30_isAllowed() throws Exception {
        deactivateGracePeriod();
        setUpNotifListener();
        mListener.addTestPackage(TRAMPOLINE_APP_API_30);
        EventCallback callback = new EventCallback();
        int notificationId = 6004;

        // Post notification and fire its pending intent
        sendTrampolineMessage(TRAMPOLINE_SERVICE_API_30, MESSAGE_SERVICE_NOTIFICATION,
                notificationId, callback);
        StatusBarNotification statusBarNotification = mNotificationHelper.findPostedNotification(
                null, notificationId, SEARCH_TYPE.LISTENER);
        assertNotNull("Notification not posted on time", statusBarNotification);
        statusBarNotification.getNotification().contentIntent.send();

        assertTrue("Service not started on time",
                callback.waitFor(EventCallback.SERVICE_STARTED, TIMEOUT_MS));
        assertTrue("Activity not started",
                callback.waitFor(EventCallback.ACTIVITY_STARTED, TIMEOUT_MS));
    }

    public void testActivityStartOnBroadcastTrampoline_whenDefaultBrowser_isBlocked()
            throws Exception {
        deactivateGracePeriod();
        setDefaultBrowser(TRAMPOLINE_APP);
        setUpNotifListener();
        mListener.addTestPackage(TRAMPOLINE_APP);
        EventCallback callback = new EventCallback();
        int notificationId = 6005;

        // Post notification and fire its pending intent
        sendTrampolineMessage(TRAMPOLINE_SERVICE, MESSAGE_BROADCAST_NOTIFICATION, notificationId,
                callback);
        StatusBarNotification statusBarNotification = mNotificationHelper.findPostedNotification(
                null, notificationId, SEARCH_TYPE.LISTENER);
        assertNotNull("Notification not posted on time", statusBarNotification);
        statusBarNotification.getNotification().contentIntent.send();

        assertTrue("Broadcast not received on time",
                callback.waitFor(EventCallback.BROADCAST_RECEIVED, TIMEOUT_LONG_MS));
        assertFalse("Activity started",
                callback.waitFor(EventCallback.ACTIVITY_STARTED, TIMEOUT_MS));
    }

    public void testActivityStartOnBroadcastTrampoline_whenDefaultBrowserApi32_isAllowed()
            throws Exception {
        deactivateGracePeriod();
        setDefaultBrowser(TRAMPOLINE_APP_API_32);
        setUpNotifListener();
        mListener.addTestPackage(TRAMPOLINE_APP_API_32);
        EventCallback callback = new EventCallback();
        int notificationId = 6005;

        // Post notification and fire its pending intent
        sendTrampolineMessage(TRAMPOLINE_SERVICE_API_32, MESSAGE_BROADCAST_NOTIFICATION,
                notificationId, callback);
        StatusBarNotification statusBarNotification = mNotificationHelper.findPostedNotification(
                null, notificationId, SEARCH_TYPE.LISTENER);
        assertNotNull("Notification not posted on time", statusBarNotification);
        statusBarNotification.getNotification().contentIntent.send();

        assertTrue("Broadcast not received on time",
                callback.waitFor(EventCallback.BROADCAST_RECEIVED, TIMEOUT_LONG_MS));
        assertTrue("Activity not started",
                callback.waitFor(EventCallback.ACTIVITY_STARTED, TIMEOUT_MS));
    }

    public void testActivityStartOnServiceTrampoline_whenDefaultBrowser_isBlocked()
            throws Exception {
        deactivateGracePeriod();
        setDefaultBrowser(TRAMPOLINE_APP);
        setUpNotifListener();
        mListener.addTestPackage(TRAMPOLINE_APP);
        EventCallback callback = new EventCallback();
        int notificationId = 6006;

        // Post notification and fire its pending intent
        sendTrampolineMessage(TRAMPOLINE_SERVICE, MESSAGE_SERVICE_NOTIFICATION, notificationId,
                callback);
        StatusBarNotification statusBarNotification = mNotificationHelper.findPostedNotification(
                null, notificationId, SEARCH_TYPE.LISTENER);
        assertNotNull("Notification not posted on time", statusBarNotification);
        statusBarNotification.getNotification().contentIntent.send();

        assertTrue("Service not started on time",
                callback.waitFor(EventCallback.SERVICE_STARTED, TIMEOUT_MS));
        assertFalse("Activity started",
                callback.waitFor(EventCallback.ACTIVITY_STARTED, TIMEOUT_MS));
    }

    public void testActivityStartOnServiceTrampoline_whenDefaultBrowserApi32_isAllowed()
            throws Exception {
        deactivateGracePeriod();
        setDefaultBrowser(TRAMPOLINE_APP_API_32);
        setUpNotifListener();
        mListener.addTestPackage(TRAMPOLINE_APP_API_32);
        EventCallback callback = new EventCallback();
        int notificationId = 6006;

        // Post notification and fire its pending intent
        sendTrampolineMessage(TRAMPOLINE_SERVICE_API_32, MESSAGE_SERVICE_NOTIFICATION,
                notificationId, callback);
        StatusBarNotification statusBarNotification = mNotificationHelper.findPostedNotification(
                null, notificationId, SEARCH_TYPE.LISTENER);
        assertNotNull("Notification not posted on time", statusBarNotification);
        statusBarNotification.getNotification().contentIntent.send();

        assertTrue("Service not started on time",
                callback.waitFor(EventCallback.SERVICE_STARTED, TIMEOUT_MS));
        assertTrue("Activity not started",
                callback.waitFor(EventCallback.ACTIVITY_STARTED, TIMEOUT_MS));
    }

    public void testGrantRevokeNotificationManagerApis_works() {
        SystemUtil.runWithShellPermissionIdentity(() -> {
            ComponentName componentName =
                    new ComponentName(STUB_PACKAGE_NAME, TestNotificationListener.class.getName());
            mNotificationManager.setNotificationListenerAccessGranted(
                    componentName, true, true);

            assertThat(
                    mNotificationManager.getEnabledNotificationListeners(),
                    hasItem(componentName));

            mNotificationManager.setNotificationListenerAccessGranted(
                    componentName, false, false);

            assertThat(
                    "Non-user-set changes should not override user-set",
                    mNotificationManager.getEnabledNotificationListeners(),
                    hasItem(componentName));
        });
    }

    public void testGrantRevokeNotificationManagerApis_exclusiveToPermissionController() {
        List<PackageInfo> allPackages = mPackageManager.getInstalledPackages(
                PackageManager.MATCH_DISABLED_COMPONENTS
                        | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS);
        List<String> allowedPackages = Arrays.asList(
                mPackageManager.getPermissionControllerPackageName(),
                "com.android.shell");
        for (PackageInfo pkg : allPackages) {
            if (!pkg.applicationInfo.isSystemApp()
                    && mPackageManager.checkPermission(
                    Manifest.permission.MANAGE_NOTIFICATION_LISTENERS, pkg.packageName)
                    == PackageManager.PERMISSION_GRANTED
                    && !allowedPackages.contains(pkg.packageName)) {
                fail(pkg.packageName + " can't hold "
                        + Manifest.permission.MANAGE_NOTIFICATION_LISTENERS);
            }
        }
    }

    public void testChannelDeletion_cancelReason() throws Exception {
        setUpNotifListener();

        sendNotification(566, R.drawable.black);

        Thread.sleep(500); // wait for notification listener to receive notification
        assertEquals(1, mListener.mPosted.size());
        String key = mListener.mPosted.get(0).getKey();

        mNotificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_ID);

        assertEquals(NotificationListenerService.REASON_CHANNEL_REMOVED,
                getCancellationReason(key));
    }

    public void testMediaStyleRemotePlayback_noPermission() throws Exception {
        int id = 99;
        final String deviceName = "device name";
        final int deviceIcon = 123;
        final PendingIntent deviceIntent = getPendingIntent();
        final Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.black)
                        .setStyle(new Notification.MediaStyle()
                                .setRemotePlaybackInfo(deviceName, deviceIcon, deviceIntent))
                        .build();
        mNotificationManager.notify(id, notification);

        StatusBarNotification sbn = mNotificationHelper.findPostedNotification(null, id,
                SEARCH_TYPE.APP);
        assertNotNull(sbn);

        assertFalse(sbn.getNotification().extras
                .containsKey(Notification.EXTRA_MEDIA_REMOTE_DEVICE));
        assertFalse(sbn.getNotification().extras
                .containsKey(Notification.EXTRA_MEDIA_REMOTE_ICON));
        assertFalse(sbn.getNotification().extras
                .containsKey(Notification.EXTRA_MEDIA_REMOTE_INTENT));
    }

    public void testMediaStyleRemotePlayback_hasPermission() throws Exception {
        int id = 99;
        final String deviceName = "device name";
        final int deviceIcon = 123;
        final PendingIntent deviceIntent = getPendingIntent();
        final Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.black)
                        .setStyle(new Notification.MediaStyle()
                                .setRemotePlaybackInfo(deviceName, deviceIcon, deviceIntent))
                        .build();

        SystemUtil.runWithShellPermissionIdentity(() -> {
            mNotificationManager.notify(id, notification);
        }, android.Manifest.permission.MEDIA_CONTENT_CONTROL);

        StatusBarNotification sbn = mNotificationHelper.findPostedNotification(
                null, id, SEARCH_TYPE.APP);
        assertNotNull(sbn);
        assertEquals(deviceName, sbn.getNotification().extras
                .getString(Notification.EXTRA_MEDIA_REMOTE_DEVICE));
        assertEquals(deviceIcon, sbn.getNotification().extras
                .getInt(Notification.EXTRA_MEDIA_REMOTE_ICON));
        assertEquals(deviceIntent, sbn.getNotification().extras
                .getParcelable(Notification.EXTRA_MEDIA_REMOTE_INTENT));
    }

    public void testCustomMediaStyleRemotePlayback_noPermission() throws Exception {
        int id = 99;
        final String deviceName = "device name";
        final int deviceIcon = 123;
        final PendingIntent deviceIntent = getPendingIntent();
        final Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.black)
                        .setStyle(new Notification.DecoratedMediaCustomViewStyle()
                                .setRemotePlaybackInfo(deviceName, deviceIcon, deviceIntent))
                        .build();
        mNotificationManager.notify(id, notification);

        StatusBarNotification sbn = mNotificationHelper.findPostedNotification(
                null, id, SEARCH_TYPE.APP);
        assertNotNull(sbn);

        assertFalse(sbn.getNotification().extras
                .containsKey(Notification.EXTRA_MEDIA_REMOTE_DEVICE));
        assertFalse(sbn.getNotification().extras
                .containsKey(Notification.EXTRA_MEDIA_REMOTE_ICON));
        assertFalse(sbn.getNotification().extras
                .containsKey(Notification.EXTRA_MEDIA_REMOTE_INTENT));
    }

    public void testCustomMediaStyleRemotePlayback_hasPermission() throws Exception {
        int id = 99;
        final String deviceName = "device name";
        final int deviceIcon = 123;
        final PendingIntent deviceIntent = getPendingIntent();
        final Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.black)
                        .setStyle(new Notification.DecoratedMediaCustomViewStyle()
                                .setRemotePlaybackInfo(deviceName, deviceIcon, deviceIntent))
                        .build();

        SystemUtil.runWithShellPermissionIdentity(() -> {
            mNotificationManager.notify(id, notification);
        }, android.Manifest.permission.MEDIA_CONTENT_CONTROL);

        StatusBarNotification sbn = mNotificationHelper.findPostedNotification(
                null, id, SEARCH_TYPE.APP);
        assertNotNull(sbn);
        assertEquals(deviceName, sbn.getNotification().extras
                .getString(Notification.EXTRA_MEDIA_REMOTE_DEVICE));
        assertEquals(deviceIcon, sbn.getNotification().extras
                .getInt(Notification.EXTRA_MEDIA_REMOTE_ICON));
        assertEquals(deviceIntent, sbn.getNotification().extras
                .getParcelable(Notification.EXTRA_MEDIA_REMOTE_INTENT));
    }

    public void testNoPermission() throws Exception {
        int id = 7;
        SystemUtil.runWithShellPermissionIdentity(
                () -> mContext.getSystemService(PermissionManager.class)
                        .revokePostNotificationPermissionWithoutKillForTest(
                                mContext.getPackageName(),
                                android.os.Process.myUserHandle().getIdentifier()),
                REVOKE_POST_NOTIFICATIONS_WITHOUT_KILL,
                REVOKE_RUNTIME_PERMISSIONS);

        final Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.black)
                        .build();
        mNotificationManager.notify(id, notification);

        assertTrue(mNotificationHelper.isNotificationGone(id, SEARCH_TYPE.APP));
    }

    public void testIsAmbient() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        NotificationChannel lowChannel = new NotificationChannel(
                "testIsAmbientLOW", "testIsAmbientLOW", IMPORTANCE_LOW);
        NotificationChannel minChannel = new NotificationChannel(
                "testIsAmbientMIN", "testIsAmbientMIN", IMPORTANCE_MIN);
        mNotificationManager.createNotificationChannel(lowChannel);
        mNotificationManager.createNotificationChannel(minChannel);

        final Notification lowN =
                new Notification.Builder(mContext, lowChannel.getId())
                        .setSmallIcon(R.drawable.black)
                        .build();
        final Notification minN =
                new Notification.Builder(mContext, minChannel.getId())
                        .setSmallIcon(R.drawable.black)
                        .build();
        mNotificationManager.notify("lowN", 1, lowN);
        mNotificationManager.notify("minN", 1, minN);

        StatusBarNotification lowSbn = mNotificationHelper.findPostedNotification("lowN", 1,
                SEARCH_TYPE.POSTED);
        StatusBarNotification minSbn = mNotificationHelper.findPostedNotification("minN", 1,
                SEARCH_TYPE.POSTED);

        NotificationListenerService.RankingMap rankingMap = mListener.mRankingMap;
        NotificationListenerService.Ranking outRanking = new NotificationListenerService.Ranking();

        rankingMap.getRanking(lowSbn.getKey(), outRanking);
        assertFalse(outRanking.isAmbient());

        rankingMap.getRanking(minSbn.getKey(), outRanking);
        assertEquals(outRanking.getKey(), IMPORTANCE_MIN, outRanking.getChannel().getImportance());
        assertTrue(outRanking.isAmbient());
    }

    public void testFlagForegroundServiceNeedsRealFgs() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        final Notification n =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.black)
                        .setFlag(FLAG_FOREGROUND_SERVICE, true)
                        .build();
        mNotificationManager.notify("testFlagForegroundServiceNeedsRealFgs", 1, n);

        StatusBarNotification sbn = mNotificationHelper.findPostedNotification(
                "testFlagForegroundServiceNeedsRealFgs", 1, SEARCH_TYPE.POSTED);

        assertEquals(0, (sbn.getNotification().flags & FLAG_FOREGROUND_SERVICE));
    }

    public void testFlagUserInitiatedJobNeedsRealUij() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        final Notification n =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.black)
                        .setFlag(FLAG_USER_INITIATED_JOB, true)
                        .build();
        mNotificationManager.notify("testFlagUserInitiatedJobNeedsRealUij", 1, n);

        StatusBarNotification sbn = mNotificationHelper.findPostedNotification(
                "testFlagUserInitiatedJobNeedsRealUij", 1, SEARCH_TYPE.POSTED);

        assertFalse(sbn.getNotification().isUserInitiatedJob());
    }

    private static class EventCallback extends Handler {
        private static final int BROADCAST_RECEIVED = 1;
        private static final int SERVICE_STARTED = 2;
        private static final int ACTIVITY_STARTED = 3;
        private static final int NOTIFICATION_CLICKED = 4;

        private final Map<Integer, CompletableFuture<Integer>> mEvents =
                Collections.synchronizedMap(new ArrayMap<>());

        private EventCallback() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message message) {
            mEvents.computeIfAbsent(message.what, e -> new CompletableFuture<>()).obtrudeValue(
                    message.arg1);
        }

        public boolean waitFor(int event, long timeoutMs) {
            try {
                return mEvents.computeIfAbsent(event, e -> new CompletableFuture<>()).get(timeoutMs,
                        TimeUnit.MILLISECONDS) == 0;
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                return false;
            }
        }
    }
}
