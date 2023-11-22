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

import static android.app.Notification.CATEGORY_CALL;
import static android.app.Notification.FLAG_BUBBLE;
import static android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;
import static android.app.stubs.BubblesTestActivity.BUBBLE_NOTIF_ID;
import static android.app.stubs.BubblesTestService.EXTRA_TEST_CASE;
import static android.app.stubs.BubblesTestService.TEST_NO_BUBBLE_METADATA;
import static android.app.stubs.BubblesTestService.TEST_NO_CATEGORY;
import static android.app.stubs.BubblesTestService.TEST_NO_PERSON;
import static android.app.stubs.BubblesTestService.TEST_SUCCESS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.FEATURE_WATCH;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AutomaticZenRule;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.app.UiAutomation;
import android.app.stubs.AutomaticZenRuleActivity;
import android.app.stubs.BubblesTestActivity;
import android.app.stubs.BubblesTestNotDocumentLaunchModeActivity;
import android.app.stubs.BubblesTestNotEmbeddableActivity;
import android.app.stubs.BubblesTestService;
import android.app.stubs.R;
import android.app.stubs.TestNotificationListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.OperationApplicationException;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.provider.Settings;
import android.provider.Telephony.Threads;
import android.server.wm.ActivityManagerTestBase;
import android.service.notification.Condition;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.test.AndroidTestCase;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.test.filters.FlakyTest;
import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.SystemUtil;

import junit.framework.Assert;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/* This tests NotificationListenerService together with NotificationManager, as you need to have
 * notifications to manipulate in order to test the listener service. */
public class NotificationManagerTest extends AndroidTestCase {
    final String TAG = NotificationManagerTest.class.getSimpleName();
    final boolean DEBUG = false;
    final String NOTIFICATION_CHANNEL_ID = "NotificationManagerTest";

    private static final String DELEGATOR = "com.android.test.notificationdelegator";
    private static final String REVOKE_CLASS = DELEGATOR + ".NotificationRevoker";
    private static final int WAIT_TIME = 2000;

    private PackageManager mPackageManager;
    private NotificationManager mNotificationManager;
    private ActivityManager mActivityManager;
    private String mId;
    private TestNotificationListener mListener;
    private List<String> mRuleIds;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // This will leave a set of channels on the device with each test run.
        mId = UUID.randomUUID().toString();
        mNotificationManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        // clear the deck so that our getActiveNotifications results are predictable
        mNotificationManager.cancelAll();
        mNotificationManager.createNotificationChannel(new NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "name", NotificationManager.IMPORTANCE_DEFAULT));
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mPackageManager = mContext.getPackageManager();
        mRuleIds = new ArrayList<>();

        // delay between tests so notifications aren't dropped by the rate limiter
        try {
            Thread.sleep(500);
        } catch(InterruptedException e) {}
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mNotificationManager.cancelAll();

        for (String id : mRuleIds) {
            mNotificationManager.removeAutomaticZenRule(id);
        }

        assertExpectedDndState(INTERRUPTION_FILTER_ALL);

        List<NotificationChannel> channels = mNotificationManager.getNotificationChannels();
        // Delete all channels.
        for (NotificationChannel nc : channels) {
            if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(nc.getId())) {
                continue;
            }
            mNotificationManager.deleteNotificationChannel(nc.getId());
        }

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), false);
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), false);

        List<NotificationChannelGroup> groups = mNotificationManager.getNotificationChannelGroups();
        // Delete all groups.
        for (NotificationChannelGroup ncg : groups) {
            mNotificationManager.deleteNotificationChannelGroup(ncg.getId());
        }
    }

    private void toggleBubbleSetting(boolean enabled) throws InterruptedException {
        SystemUtil.runWithShellPermissionIdentity(() ->
                Settings.Secure.putInt(mContext.getContentResolver(),
                        Settings.Secure.NOTIFICATION_BUBBLES, enabled ? 1 : 0));
        Thread.sleep(500); // wait for ranking update

    }

    private void insertSingleContact(String name, String phone, String email, boolean starred) {
        final ArrayList<ContentProviderOperation> operationList =
                new ArrayList<ContentProviderOperation>();
        ContentProviderOperation.Builder builder =
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI);
        builder.withValue(ContactsContract.RawContacts.STARRED, starred ? 1 : 0);
        operationList.add(builder.build());

        builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
        builder.withValueBackReference(StructuredName.RAW_CONTACT_ID, 0);
        builder.withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
        builder.withValue(StructuredName.DISPLAY_NAME, name);
        operationList.add(builder.build());

        if (phone != null) {
            builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
            builder.withValueBackReference(Phone.RAW_CONTACT_ID, 0);
            builder.withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
            builder.withValue(Phone.TYPE, Phone.TYPE_MOBILE);
            builder.withValue(Phone.NUMBER, phone);
            builder.withValue(Data.IS_PRIMARY, 1);
            operationList.add(builder.build());
        }
        if (email != null) {
            builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
            builder.withValueBackReference(Email.RAW_CONTACT_ID, 0);
            builder.withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE);
            builder.withValue(Email.TYPE, Email.TYPE_HOME);
            builder.withValue(Email.DATA, email);
            operationList.add(builder.build());
        }

        try {
            mContext.getContentResolver().applyBatch(ContactsContract.AUTHORITY, operationList);
        } catch (RemoteException e) {
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        } catch (OperationApplicationException e) {
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        }
    }

    private Uri lookupContact(String phone) {
        Cursor c = null;
        try {
            Uri phoneUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(phone));
            String[] projection = new String[] { ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.LOOKUP_KEY };
            c = mContext.getContentResolver().query(phoneUri, projection, null, null, null);
            if (c != null && c.getCount() > 0) {
                c.moveToFirst();
                int lookupIdx = c.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY);
                int idIdx = c.getColumnIndex(ContactsContract.Contacts._ID);
                String lookupKey = c.getString(lookupIdx);
                long contactId = c.getLong(idIdx);
                return ContactsContract.Contacts.getLookupUri(contactId, lookupKey);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Problem getting content resolver or performing contacts query.", t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }


    private StatusBarNotification findPostedNotification(int id) {
        // notification is a bit asynchronous so it may take a few ms to appear in
        // getActiveNotifications()
        // we will check for it for up to 300ms before giving up
        StatusBarNotification n = null;
        for (int tries = 3; tries-- > 0; ) {
            final StatusBarNotification[] sbns = mNotificationManager.getActiveNotifications();
            for (StatusBarNotification sbn : sbns) {
                Log.d(TAG, "Found " + sbn.getKey());
                if (sbn.getId() == id) {
                    n = sbn;
                    break;
                }
            }
            if (n != null) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                // pass
            }
        }
        return n;
    }

    private PendingIntent getPendingIntent() {
        return PendingIntent.getActivity(
                getContext(), 0, new Intent(getContext(), this.getClass()), 0);
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

    private void cancelAndPoll(int id) {
        mNotificationManager.cancel(id);

        if (!checkNotificationExistence(id, /*shouldExist=*/ false)) {
            fail("canceled notification was still alive, id=" + id);
        }
    }

    private void sendNotification(final int id, final int icon) throws Exception {
        sendNotification(id, null, icon);
    }

    private void sendNotification(final int id, String groupKey, final int icon) throws Exception {
        final Intent intent = new Intent(Intent.ACTION_MAIN, Threads.CONTENT_URI);

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.setAction(Intent.ACTION_MAIN);

        final PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, intent, 0);
        final Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(icon)
                        .setWhen(System.currentTimeMillis())
                        .setContentTitle("notify#" + id)
                        .setContentText("This is #" + id + "notification  ")
                        .setContentIntent(pendingIntent)
                        .setGroup(groupKey)
                        .build();
        mNotificationManager.notify(id, notification);

        if (!checkNotificationExistence(id, /*shouldExist=*/ true)) {
            fail("couldn't find posted notification id=" + id);
        }
    }

    private void sendAndVerifyBubble(final int id, Notification.Builder builder,
            Notification.BubbleMetadata data, boolean shouldBeBubble) {
        final Intent intent = new Intent(mContext, BubblesTestActivity.class);

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.setAction(Intent.ACTION_MAIN);
        final PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

        if (data == null) {
            data = new Notification.BubbleMetadata.Builder()
                    .setIcon(Icon.createWithResource(mContext, R.drawable.black))
                    .setIntent(pendingIntent)
                    .build();
        }
        if (builder == null) {
            builder = new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                            .setSmallIcon(R.drawable.black)
                            .setWhen(System.currentTimeMillis())
                            .setContentTitle("notify#" + id)
                            .setContentText("This is #" + id + "notification  ")
                            .setContentIntent(pendingIntent);
        }
        builder.setBubbleMetadata(data);

        Notification notif = builder.build();
        mNotificationManager.notify(id, notif);

        if (!checkNotificationExistence(id, /*shouldExist=*/ true, shouldBeBubble)) {
            fail("couldn't find posted notification bubble with id=" + id);
        }
    }

    private boolean checkNotificationExistence(int id, boolean shouldExist) {
        return checkNotificationExistence(id, shouldExist, false /* shouldBeBubble */);
    }

    private boolean checkNotificationExistence(int id, boolean shouldExist,
            boolean shouldBeBubble) {
        // notification is a bit asynchronous so it may take a few ms to appear in
        // getActiveNotifications()
        // we will check for it for up to 300ms before giving up
        boolean found = false;
        boolean isBubble = false;
        for (int tries = 3; tries--> 0;) {
            // Need reset flag.
            found = false;
            final StatusBarNotification[] sbns = mNotificationManager.getActiveNotifications();
            for (StatusBarNotification sbn : sbns) {
                isBubble = (sbn.getNotification().flags & FLAG_BUBBLE) != 0;
                Log.d(TAG, "Found " + sbn.getKey() + " Bubble? " + isBubble);
                if (sbn.getId() == id) {
                    found = true;
                    break;
                }
            }
            if (found == shouldExist) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                // pass
            }
        }
        return (found == shouldExist) && (isBubble == shouldBeBubble);
    }

    private void assertNotificationCount(int expectedCount) {
        // notification is a bit asynchronous so it may take a few ms to appear in
        // getActiveNotifications()
        // we will check for it for up to 400ms before giving up
        int lastCount = 0;
        for (int tries = 4; tries-- > 0;) {
            final StatusBarNotification[] sbns = mNotificationManager.getActiveNotifications();
            lastCount = sbns.length;
            if (expectedCount == lastCount) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                // pass
            }
        }
        fail("Expected " + expectedCount + " posted notifications, were " +  lastCount);
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
    }

    private void toggleNotificationPolicyAccess(String packageName,
            Instrumentation instrumentation, boolean on) throws IOException {

        String command = " cmd notification " + (on ? "allow_dnd " : "disallow_dnd ") + packageName;

        runCommand(command, instrumentation);

        NotificationManager nm = mContext.getSystemService(NotificationManager.class);
        Assert.assertEquals("Notification Policy Access Grant is " +
                        nm.isNotificationPolicyAccessGranted() + " not " + on, on,
                nm.isNotificationPolicyAccessGranted());
    }

    private void suspendPackage(String packageName,
            Instrumentation instrumentation, boolean suspend) throws IOException {
        int userId = mContext.getUserId();
        String command = " cmd package " + (suspend ? "suspend " : "unsuspend ")
                + "--user " + userId + " " + packageName;

        runCommand(command, instrumentation);
    }

    private void toggleListenerAccess(String componentName, Instrumentation instrumentation,
            boolean on) throws IOException {

        String command = " cmd notification " + (on ? "allow_listener " : "disallow_listener ")
                + componentName;

        runCommand(command, instrumentation);

        final NotificationManager nm = mContext.getSystemService(NotificationManager.class);
        final ComponentName listenerComponent = TestNotificationListener.getComponentName();
        assertTrue(listenerComponent + " has not been granted access",
                nm.isNotificationListenerAccessGranted(listenerComponent) == on);
    }

    private void runCommand(String command, Instrumentation instrumentation) throws IOException {
        UiAutomation uiAutomation = instrumentation.getUiAutomation();
        // Execute command
        try (ParcelFileDescriptor fd = uiAutomation.executeShellCommand(command)) {
            Assert.assertNotNull("Failed to execute shell command: " + command, fd);
            // Wait for the command to finish by reading until EOF
            try (InputStream in = new FileInputStream(fd.getFileDescriptor())) {
                byte[] buffer = new byte[4096];
                while (in.read(buffer) > 0) {}
            } catch (IOException e) {
                throw new IOException("Could not read stdout of command: " + command, e);
            }
        } finally {
            uiAutomation.destroy();
        }
    }

    private boolean areRulesSame(AutomaticZenRule a, AutomaticZenRule b) {
        return a.isEnabled() == b.isEnabled()
                && Objects.equals(a.getName(), b.getName())
                && a.getInterruptionFilter() == b.getInterruptionFilter()
                && Objects.equals(a.getConditionId(), b.getConditionId())
                && Objects.equals(a.getOwner(), b.getOwner())
                && Objects.equals(a.getZenPolicy(), b.getZenPolicy())
                && Objects.equals(a.getConfigurationActivity(), b.getConfigurationActivity());
    }

    private AutomaticZenRule createRule(String name) {
        return new AutomaticZenRule(name, null,
                new ComponentName(mContext, AutomaticZenRuleActivity.class),
                new Uri.Builder().scheme("scheme")
                        .appendPath("path")
                        .appendQueryParameter("fake_rule", "fake_value")
                        .build(), null, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
    }

    private void assertExpectedDndState(int expectedState) {
        int tries = 3;
        for (int i = tries; i >=0; i--) {
            if (expectedState ==
                    mNotificationManager.getCurrentInterruptionFilter()) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        assertEquals(expectedState, mNotificationManager.getCurrentInterruptionFilter());
    }

    private Activity launchSendBubbleActivity() {
        Class clazz = BubblesTestActivity.class;

        Instrumentation.ActivityResult result =
                new Instrumentation.ActivityResult(0, new Intent());
        Instrumentation.ActivityMonitor monitor =
                new Instrumentation.ActivityMonitor(clazz.getName(), result, false);
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor);

        Intent i = new Intent(mContext, BubblesTestActivity.class);
        i.setFlags(FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        return monitor.waitForActivity();
    }

    private class HomeHelper extends ActivityManagerTestBase implements AutoCloseable {

        HomeHelper() throws Exception {
            setUp();
        }

        public void goHome() {
            launchHomeActivity();
        }

        @Override
        public void close() throws Exception {
            tearDown();
        }
    }

    public void testPostPCanToggleAlarmsMediaSystemTest() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }

        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);

        if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.P) {
            // Post-P can toggle alarms, media, system
            // toggle on alarms, media, system:
            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                    NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
                            | NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
                            | NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM, 0, 0));
            NotificationManager.Policy policy = mNotificationManager.getNotificationPolicy();
            assertTrue((policy.priorityCategories
                    & NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0);
            assertTrue((policy.priorityCategories
                    & NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA) != 0);
            assertTrue((policy.priorityCategories
                    & NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM) != 0);

            // toggle off alarms, media, system
            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(0, 0, 0));
            policy = mNotificationManager.getNotificationPolicy();
            assertTrue((policy.priorityCategories
                    & NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0);
            assertTrue((policy.priorityCategories &
                    NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA) == 0);
            assertTrue((policy.priorityCategories &
                    NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM) == 0);
        }
    }

    public void testCreateChannelGroup() throws Exception {
        final NotificationChannelGroup ncg = new NotificationChannelGroup("a group", "a label");
        final NotificationChannel channel =
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setGroup(ncg.getId());
        mNotificationManager.createNotificationChannelGroup(ncg);
        final NotificationChannel ungrouped =
                new NotificationChannel(mId + "!", "name", NotificationManager.IMPORTANCE_DEFAULT);
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
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
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
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
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
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setGroup(ncg.getId());
        mNotificationManager.createNotificationChannelGroup(ncg);
        mNotificationManager.createNotificationChannel(channel);

        mNotificationManager.deleteNotificationChannelGroup(ncg.getId());

        assertNull(mNotificationManager.getNotificationChannel(channel.getId()));
        assertEquals(0, mNotificationManager.getNotificationChannelGroups().size());
    }

    public void testCreateChannel() throws Exception {
        final NotificationChannel channel =
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("bananas");
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[] {5, 8, 2, 1});
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
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
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
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
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
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
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
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
        mNotificationManager.createNotificationChannel(channel);
        final NotificationChannel channelDupe =
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_HIGH);
        mNotificationManager.createNotificationChannel(channelDupe);
        final NotificationChannel createdChannel =
                mNotificationManager.getNotificationChannel(mId);
        compareChannels(channel, createdChannel);
    }

    public void testCreateChannelAlreadyExistsNoOp() throws Exception {
        NotificationChannel channel =
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
        mNotificationManager.createNotificationChannel(channel);
        NotificationChannel channelDupe =
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_HIGH);
        mNotificationManager.createNotificationChannel(channelDupe);
        compareChannels(channel, mNotificationManager.getNotificationChannel(channel.getId()));
    }

    public void testCreateChannelWithGroup() throws Exception {
        NotificationChannelGroup ncg = new NotificationChannelGroup("g", "n");
        mNotificationManager.createNotificationChannelGroup(ncg);
        try {
            NotificationChannel channel =
                    new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setGroup(ncg.getId());
            mNotificationManager.createNotificationChannel(channel);
            compareChannels(channel, mNotificationManager.getNotificationChannel(channel.getId()));
        } finally {
            mNotificationManager.deleteNotificationChannelGroup(ncg.getId());
        }
    }

    public void testCreateChannelWithBadGroup() throws Exception {
        NotificationChannel channel =
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setGroup("garbage");
        try {
            mNotificationManager.createNotificationChannel(channel);
            fail("Created notification with bad group");
        } catch (IllegalArgumentException e) {}
    }

    public void testCreateChannelInvalidImportance() throws Exception {
        NotificationChannel channel =
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_UNSPECIFIED);
        try {
            mNotificationManager.createNotificationChannel(channel);
        } catch (IllegalArgumentException e) {
            //success
        }
    }

    public void testDeleteChannel() throws Exception {
        NotificationChannel channel =
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_LOW);
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
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
        NotificationChannel channel2 =
                new NotificationChannel(
                        UUID.randomUUID().toString(), "name2", NotificationManager.IMPORTANCE_HIGH);
        NotificationChannel channel3 =
                new NotificationChannel(
                        UUID.randomUUID().toString(), "name3", NotificationManager.IMPORTANCE_LOW);
        NotificationChannel channel4 =
                new NotificationChannel(
                        UUID.randomUUID().toString(), "name4", NotificationManager.IMPORTANCE_MIN);
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
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
        NotificationChannel channel2 =
                new NotificationChannel(
                        UUID.randomUUID().toString(), "name2", NotificationManager.IMPORTANCE_HIGH);
        NotificationChannel channel3 =
                new NotificationChannel(
                        UUID.randomUUID().toString(), "name3", NotificationManager.IMPORTANCE_LOW);
        NotificationChannel channel4 =
                new NotificationChannel(
                        UUID.randomUUID().toString(), "name4", NotificationManager.IMPORTANCE_MIN);

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
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setShowBadge(true);
        NotificationChannel newChannel = new NotificationChannel(
                channel.getId(), channel.getName(), NotificationManager.IMPORTANCE_HIGH);
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

    public void testSuspendPackage() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        Thread.sleep(500); // wait for listener to be allowed

        mListener = TestNotificationListener.getInstance();
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
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        Thread.sleep(500); // wait for listener to be allowed

        mListener = TestNotificationListener.getInstance();
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

    public void testCanBubble_ranking() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        // turn on bubbles globally
        toggleBubbleSetting(true);

        assertEquals(1, Settings.Secure.getInt(
                mContext.getContentResolver(), Settings.Secure.NOTIFICATION_BUBBLES));

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        Thread.sleep(500); // wait for listener to be allowed

        mListener = TestNotificationListener.getInstance();
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
                    // by default everything can bubble
                    assertTrue(outRanking.canBubble());
                }
            }

            // turn off bubbles globally
            toggleBubbleSetting(false);

            rankingMap = mListener.mRankingMap;
            outRanking = new NotificationListenerService.Ranking();
            for (String key : rankingMap.getOrderedKeys()) {
                if (key.contains(mListener.getPackageName())) {
                    rankingMap.getRanking(key, outRanking);
                    assertFalse(outRanking.canBubble());
                }
            }

            mListener.resetData();
        } finally {
            // turn off bubbles globally
            toggleBubbleSetting(false);
        }
    }

    public void testShowBadging_ranking() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        final int originalBadging = Settings.Secure.getInt(
                mContext.getContentResolver(), Settings.Secure.NOTIFICATION_BADGING);

        SystemUtil.runWithShellPermissionIdentity(() ->
                Settings.Secure.putInt(mContext.getContentResolver(),
                        Settings.Secure.NOTIFICATION_BADGING, 1));
        assertEquals(1, Settings.Secure.getInt(
                mContext.getContentResolver(), Settings.Secure.NOTIFICATION_BADGING));

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        Thread.sleep(500); // wait for listener to be allowed

        mListener = TestNotificationListener.getInstance();
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

    public void testGetSuppressedVisualEffectsOff_ranking() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        Thread.sleep(500); // wait for listener to be allowed

        mListener = TestNotificationListener.getInstance();
        assertNotNull(mListener);

        final int notificationId = 1;
        sendNotification(notificationId, R.drawable.black);
        Thread.sleep(500); // wait for notification listener to receive notification

        NotificationListenerService.RankingMap rankingMap = mListener.mRankingMap;
        NotificationListenerService.Ranking outRanking =
                new NotificationListenerService.Ranking();

        for (String key : rankingMap.getOrderedKeys()) {
            if (key.contains(mListener.getPackageName())) {
                rankingMap.getRanking(key, outRanking);

                // check notification key match
                assertEquals(0, outRanking.getSuppressedVisualEffects());
            }
        }
    }

    public void testGetSuppressedVisualEffects_ranking() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        final int originalFilter = mNotificationManager.getCurrentInterruptionFilter();
        try {
            toggleListenerAccess(TestNotificationListener.getId(),
                    InstrumentationRegistry.getInstrumentation(), true);
            Thread.sleep(500); // wait for listener to be allowed

            mListener = TestNotificationListener.getInstance();
            assertNotNull(mListener);

            toggleNotificationPolicyAccess(mContext.getPackageName(),
                    InstrumentationRegistry.getInstrumentation(), true);
            if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.P) {
                mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(0, 0, 0,
                        SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_PEEK));
            } else {
                mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(0, 0, 0,
                        SUPPRESSED_EFFECT_SCREEN_ON));
            }
            mNotificationManager.setInterruptionFilter(
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY);

            final int notificationId = 1;
            // update notification
            sendNotification(notificationId, R.drawable.black);
            Thread.sleep(500); // wait for notification listener to receive notification

            NotificationListenerService.RankingMap rankingMap = mListener.mRankingMap;
            NotificationListenerService.Ranking outRanking =
                    new NotificationListenerService.Ranking();

            for (String key : rankingMap.getOrderedKeys()) {
                if (key.contains(mListener.getPackageName())) {
                    rankingMap.getRanking(key, outRanking);

                    if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.P) {
                        assertEquals(SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_PEEK,
                                outRanking.getSuppressedVisualEffects());
                    } else {
                        assertEquals(SUPPRESSED_EFFECT_SCREEN_ON,
                                outRanking.getSuppressedVisualEffects());
                    }
                }
            }
        } finally {
            // reset notification policy
            mNotificationManager.setInterruptionFilter(originalFilter);
        }

    }

    public void testKeyChannelGroupOverrideImportanceExplanation_ranking() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        Thread.sleep(500); // wait for listener to be allowed

        mListener = TestNotificationListener.getInstance();
        assertNotNull(mListener);

        final int notificationId = 1;
        sendNotification(notificationId, R.drawable.black);
        Thread.sleep(500); // wait for notification listener to receive notification

        NotificationListenerService.RankingMap rankingMap = mListener.mRankingMap;
        NotificationListenerService.Ranking outRanking =
                new NotificationListenerService.Ranking();

        StatusBarNotification sbn = findPostedNotification(notificationId);

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
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_NONE);
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

        if (!checkNotificationExistence(id, /*shouldExist=*/ false)) {
            fail("found unexpected notification id=" + id);
        }
    }

    public void testNotify_blockedChannelGroup() throws Exception {
        mNotificationManager.cancelAll();

        NotificationChannelGroup group = new NotificationChannelGroup(mId, "group name");
        group.setBlocked(true);
        mNotificationManager.createNotificationChannelGroup(group);
        NotificationChannel channel =
                new NotificationChannel(mId, "name", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setGroup(mId);
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

        if (!checkNotificationExistence(id, /*shouldExist=*/ false)) {
            fail("found unexpected notification id=" + id);
        }
    }

    public void testCancel() throws Exception {
        final int id = 9;
        sendNotification(id, R.drawable.black);
        mNotificationManager.cancel(id);

        if (!checkNotificationExistence(id, /*shouldExist=*/ false)) {
            fail("canceled notification was still alive, id=" + id);
        }
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
            if (!checkNotificationExistence(id, /*shouldExist=*/ false)) {
                fail("Failed to cancel notification id=" + id);
            }
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

        if (!checkNotificationExistence(id, /*shouldExist=*/ true)) {
            fail("couldn't find posted notification id=" + id);
        }

        try {
            Thread.sleep(timeout);
        } catch (InterruptedException ex) {
            // pass
        }
        checkNotificationExistence(id, false);
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

    public void testMediaStyle_empty() throws Exception {
        Notification.MediaStyle style = new Notification.MediaStyle();
        assertNotNull(style);
    }

    public void testMediaStyle() throws Exception {
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

        if (!checkNotificationExistence(id, /*shouldExist=*/ true)) {
            fail("couldn't find posted notification id=" + id);
        }
    }

    public void testInboxStyle() throws Exception {
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

        if (!checkNotificationExistence(id, /*shouldExist=*/ true)) {
            fail("couldn't find posted notification id=" + id);
        }
    }

    public void testBigTextStyle() throws Exception {
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

        if (!checkNotificationExistence(id, /*shouldExist=*/ true)) {
            fail("couldn't find posted notification id=" + id);
        }
    }

    public void testBigPictureStyle() throws Exception {
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
                        .bigLargeIcon(Icon.createWithResource(getContext(), R.drawable.icon_blue))
                        .setSummaryText("summary"))
                        .build();
        mNotificationManager.notify(id, notification);

        if (!checkNotificationExistence(id, /*shouldExist=*/ true)) {
            fail("couldn't find posted notification id=" + id);
        }
    }

    public void testAutogrouping() throws Exception {
        sendNotification(1, R.drawable.black);
        sendNotification(2, R.drawable.blue);
        sendNotification(3, R.drawable.yellow);
        sendNotification(4, R.drawable.yellow);

        assertNotificationCount(5);
        assertAllPostedNotificationsAutogrouped();
    }

    public void testAutogrouping_autogroupStaysUntilAllNotificationsCanceled() throws Exception {
        sendNotification(1, R.drawable.black);
        sendNotification(2, R.drawable.blue);
        sendNotification(3, R.drawable.yellow);
        sendNotification(4, R.drawable.yellow);

        assertNotificationCount(5);
        assertAllPostedNotificationsAutogrouped();

        // Assert all notis stay in the same autogroup until all children are canceled
        for (int i = 4; i > 1; i--) {
            cancelAndPoll(i);
            assertNotificationCount(i);
            assertAllPostedNotificationsAutogrouped();
        }
        cancelAndPoll(1);
        assertNotificationCount(0);
    }

    public void testAutogrouping_autogroupStaysUntilAllNotificationsAddedToGroup()
            throws Exception {
        String newGroup = "new!";
        sendNotification(1, R.drawable.black);
        sendNotification(2, R.drawable.blue);
        sendNotification(3, R.drawable.yellow);
        sendNotification(4, R.drawable.yellow);

        List<Integer> postedIds = new ArrayList<>();
        postedIds.add(1);
        postedIds.add(2);
        postedIds.add(3);
        postedIds.add(4);

        assertNotificationCount(5);
        assertAllPostedNotificationsAutogrouped();

        // Assert all notis stay in the same autogroup until all children are canceled
        for (int i = 4; i > 1; i--) {
            sendNotification(i, newGroup, R.drawable.blue);
            postedIds.remove(postedIds.size() - 1);
            assertNotificationCount(5);
            assertOnlySomeNotificationsAutogrouped(postedIds);
        }
        sendNotification(1, newGroup, R.drawable.blue);
        assertNotificationCount(4); // no more autogroup summary
        postedIds.remove(0);
        assertOnlySomeNotificationsAutogrouped(postedIds);
    }

    public void testNewNotificationsAddedToAutogroup_ifOriginalNotificationsCanceled()
        throws Exception {
        String newGroup = "new!";
        sendNotification(10, R.drawable.black);
        sendNotification(20, R.drawable.blue);
        sendNotification(30, R.drawable.yellow);
        sendNotification(40, R.drawable.yellow);

        List<Integer> postedIds = new ArrayList<>();
        postedIds.add(10);
        postedIds.add(20);
        postedIds.add(30);
        postedIds.add(40);

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
        sendNotification(50, R.drawable.blue);
        postedIds.add(50);
        try {
            Thread.sleep(200);
        } catch (InterruptedException ex) {
            // pass
        }
        assertOnlySomeNotificationsAutogrouped(postedIds);
    }

    public void testAddAutomaticZenRule_configActivity() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }

        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);

        AutomaticZenRule ruleToCreate = createRule("Rule");
        String id = mNotificationManager.addAutomaticZenRule(ruleToCreate);

        assertNotNull(id);
        mRuleIds.add(id);
        assertTrue(areRulesSame(ruleToCreate, mNotificationManager.getAutomaticZenRule(id)));
    }

    public void testUpdateAutomaticZenRule_configActivity() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }

        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);

        AutomaticZenRule ruleToCreate = createRule("Rule");
        String id = mNotificationManager.addAutomaticZenRule(ruleToCreate);
        ruleToCreate.setEnabled(false);
        mNotificationManager.updateAutomaticZenRule(id, ruleToCreate);

        assertNotNull(id);
        mRuleIds.add(id);
        assertTrue(areRulesSame(ruleToCreate, mNotificationManager.getAutomaticZenRule(id)));
    }

    public void testRemoveAutomaticZenRule_configActivity() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }

        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);

        AutomaticZenRule ruleToCreate = createRule("Rule");
        String id = mNotificationManager.addAutomaticZenRule(ruleToCreate);

        assertNotNull(id);
        mRuleIds.add(id);
        mNotificationManager.removeAutomaticZenRule(id);

        assertNull(mNotificationManager.getAutomaticZenRule(id));
        assertEquals(0, mNotificationManager.getAutomaticZenRules().size());
    }

    public void testSetAutomaticZenRuleState() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }

        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);

        AutomaticZenRule ruleToCreate = createRule("Rule");
        String id = mNotificationManager.addAutomaticZenRule(ruleToCreate);
        mRuleIds.add(id);

        // make sure DND is off
        assertExpectedDndState(INTERRUPTION_FILTER_ALL);

        // enable DND
        Condition condition =
                new Condition(ruleToCreate.getConditionId(), "summary", Condition.STATE_TRUE);
        mNotificationManager.setAutomaticZenRuleState(id, condition);

        assertExpectedDndState(ruleToCreate.getInterruptionFilter());
    }

    public void testSetAutomaticZenRuleState_turnOff() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }

        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);

        AutomaticZenRule ruleToCreate = createRule("Rule");
        String id = mNotificationManager.addAutomaticZenRule(ruleToCreate);
        mRuleIds.add(id);

        // make sure DND is off
        // make sure DND is off
        assertExpectedDndState(INTERRUPTION_FILTER_ALL);

        // enable DND
        Condition condition =
                new Condition(ruleToCreate.getConditionId(), "on", Condition.STATE_TRUE);
        mNotificationManager.setAutomaticZenRuleState(id, condition);

        assertExpectedDndState(ruleToCreate.getInterruptionFilter());

        // disable DND
        condition = new Condition(ruleToCreate.getConditionId(), "off", Condition.STATE_FALSE);

        mNotificationManager.setAutomaticZenRuleState(id, condition);

        // make sure DND is off
        assertExpectedDndState(INTERRUPTION_FILTER_ALL);
    }

    public void testSetAutomaticZenRuleState_deletedRule() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }

        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);

        AutomaticZenRule ruleToCreate = createRule("Rule");
        String id = mNotificationManager.addAutomaticZenRule(ruleToCreate);
        mRuleIds.add(id);

        // make sure DND is off
        assertExpectedDndState(INTERRUPTION_FILTER_ALL);

        // enable DND
        Condition condition =
                new Condition(ruleToCreate.getConditionId(), "summary", Condition.STATE_TRUE);
        mNotificationManager.setAutomaticZenRuleState(id, condition);

        assertExpectedDndState(ruleToCreate.getInterruptionFilter());

        mNotificationManager.removeAutomaticZenRule(id);

        // make sure DND is off
        assertExpectedDndState(INTERRUPTION_FILTER_ALL);
    }

    @FlakyTest
    public void testSetAutomaticZenRuleState_multipleRules() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }

        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);

        AutomaticZenRule ruleToCreate = createRule("Rule");
        String id = mNotificationManager.addAutomaticZenRule(ruleToCreate);
        mRuleIds.add(id);

        AutomaticZenRule secondRuleToCreate = createRule("Rule 2");
        secondRuleToCreate.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
        String secondId = mNotificationManager.addAutomaticZenRule(secondRuleToCreate);
        mRuleIds.add(secondId);

        // make sure DND is off
        assertExpectedDndState(INTERRUPTION_FILTER_ALL);

        // enable DND
        Condition condition =
                new Condition(ruleToCreate.getConditionId(), "summary", Condition.STATE_TRUE);
        mNotificationManager.setAutomaticZenRuleState(id, condition);
        Condition secondCondition =
                new Condition(secondRuleToCreate.getConditionId(), "summary", Condition.STATE_TRUE);
        mNotificationManager.setAutomaticZenRuleState(secondId, secondCondition);

        // the second rule has a 'more silent' DND filter, so the system wide DND should be
        // using its filter
        assertExpectedDndState(secondRuleToCreate.getInterruptionFilter());

        // remove intense rule, system should fallback to other rule
        mNotificationManager.removeAutomaticZenRule(secondId);
        assertExpectedDndState(ruleToCreate.getInterruptionFilter());
    }

    public void testSetNotificationPolicy_P_setOldFields() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.P) {
            NotificationManager.Policy appPolicy = new NotificationManager.Policy(0, 0, 0,
                    SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_SCREEN_OFF);
            mNotificationManager.setNotificationPolicy(appPolicy);

            int expected = SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_SCREEN_OFF
                    | SUPPRESSED_EFFECT_PEEK | SUPPRESSED_EFFECT_AMBIENT
                    | SUPPRESSED_EFFECT_LIGHTS | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;

            assertEquals(expected,
                    mNotificationManager.getNotificationPolicy().suppressedVisualEffects);
        }
    }

    public void testSetNotificationPolicy_P_setNewFields() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.P) {
            NotificationManager.Policy appPolicy = new NotificationManager.Policy(0, 0, 0,
                    SUPPRESSED_EFFECT_NOTIFICATION_LIST | SUPPRESSED_EFFECT_AMBIENT
                            | SUPPRESSED_EFFECT_LIGHTS | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT);
            mNotificationManager.setNotificationPolicy(appPolicy);

            int expected = SUPPRESSED_EFFECT_NOTIFICATION_LIST | SUPPRESSED_EFFECT_SCREEN_OFF
                    | SUPPRESSED_EFFECT_AMBIENT | SUPPRESSED_EFFECT_LIGHTS
                    | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
            assertEquals(expected,
                    mNotificationManager.getNotificationPolicy().suppressedVisualEffects);
        }
    }

    public void testSetNotificationPolicy_P_setOldNewFields() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.P) {

            NotificationManager.Policy appPolicy = new NotificationManager.Policy(0, 0, 0,
                    SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_STATUS_BAR);
            mNotificationManager.setNotificationPolicy(appPolicy);

            int expected = SUPPRESSED_EFFECT_STATUS_BAR;
            assertEquals(expected,
                    mNotificationManager.getNotificationPolicy().suppressedVisualEffects);

            appPolicy = new NotificationManager.Policy(0, 0, 0,
                    SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_AMBIENT
                            | SUPPRESSED_EFFECT_LIGHTS | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT);
            mNotificationManager.setNotificationPolicy(appPolicy);

            expected = SUPPRESSED_EFFECT_SCREEN_OFF | SUPPRESSED_EFFECT_AMBIENT
                    | SUPPRESSED_EFFECT_LIGHTS | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
            assertEquals(expected,
                    mNotificationManager.getNotificationPolicy().suppressedVisualEffects);
        }
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

        StatusBarNotification n = findPostedNotification(id);
        assertNotNull(n);
        assertEquals(notification.fullScreenIntent, n.getNotification().fullScreenIntent);
    }

    public void testNotificationPolicyVisualEffectsEqual() {
        NotificationManager.Policy policy = new NotificationManager.Policy(0,0 ,0 ,
                SUPPRESSED_EFFECT_SCREEN_ON);
        NotificationManager.Policy policy2 = new NotificationManager.Policy(0,0 ,0 ,
                SUPPRESSED_EFFECT_PEEK);
        assertTrue(policy.equals(policy2));
        assertTrue(policy2.equals(policy));

        policy = new NotificationManager.Policy(0,0 ,0 ,
                SUPPRESSED_EFFECT_SCREEN_ON);
        policy2 = new NotificationManager.Policy(0,0 ,0 ,
                0);
        assertFalse(policy.equals(policy2));
        assertFalse(policy2.equals(policy));

        policy = new NotificationManager.Policy(0,0 ,0 ,
                SUPPRESSED_EFFECT_SCREEN_OFF);
        policy2 = new NotificationManager.Policy(0,0 ,0 ,
                SUPPRESSED_EFFECT_FULL_SCREEN_INTENT | SUPPRESSED_EFFECT_AMBIENT
                        | SUPPRESSED_EFFECT_LIGHTS);
        assertTrue(policy.equals(policy2));
        assertTrue(policy2.equals(policy));

        policy = new NotificationManager.Policy(0,0 ,0 ,
                SUPPRESSED_EFFECT_SCREEN_OFF);
        policy2 = new NotificationManager.Policy(0,0 ,0 ,
                SUPPRESSED_EFFECT_LIGHTS);
        assertFalse(policy.equals(policy2));
        assertFalse(policy2.equals(policy));
    }

    public void testNotificationDelegate_grantAndPost() throws Exception {
        // grant this test permission to post
        final Intent activityIntent = new Intent();
        activityIntent.setPackage(DELEGATOR);
        activityIntent.setAction(Intent.ACTION_MAIN);
        activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // wait for the activity to launch and finish
        mContext.startActivity(activityIntent);
        Thread.sleep(1000);

        // send notification
        Notification n = new Notification.Builder(mContext, "channel")
                .setSmallIcon(android.R.id.icon)
                .build();
        mNotificationManager.notifyAsPackage(DELEGATOR, "tag", 0, n);

        findPostedNotification(0);

        final Intent revokeIntent = new Intent();
        revokeIntent.setClassName(DELEGATOR, REVOKE_CLASS);
        revokeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(revokeIntent);
        Thread.sleep(1000);
    }

    public void testNotificationDelegate_grantAndReadChannels() throws Exception {
        // grant this test permission to post
        final Intent activityIntent = new Intent();
        activityIntent.setPackage(DELEGATOR);
        activityIntent.setAction(Intent.ACTION_MAIN);
        activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // wait for the activity to launch and finish
        mContext.startActivity(activityIntent);
        Thread.sleep(500);

        List<NotificationChannel> channels =
                mContext.createPackageContextAsUser(DELEGATOR, /* flags= */ 0, mContext.getUser())
                        .getSystemService(NotificationManager.class)
                        .getNotificationChannels();

        assertNotNull(channels);

        final Intent revokeIntent = new Intent();
        revokeIntent.setClassName(DELEGATOR, REVOKE_CLASS);
        revokeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(revokeIntent);
        Thread.sleep(500);
    }

    public void testNotificationDelegate_grantAndReadChannel() throws Exception {
        // grant this test permission to post
        final Intent activityIntent = new Intent();
        activityIntent.setPackage(DELEGATOR);
        activityIntent.setAction(Intent.ACTION_MAIN);
        activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // wait for the activity to launch and finish
        mContext.startActivity(activityIntent);
        Thread.sleep(500);

        NotificationChannel channel =
                mContext.createPackageContextAsUser(DELEGATOR, /* flags= */ 0, mContext.getUser())
                        .getSystemService(NotificationManager.class)
                        .getNotificationChannel("channel");

        assertNotNull(channel);

        final Intent revokeIntent = new Intent();
        revokeIntent.setClassName(DELEGATOR, REVOKE_CLASS);
        revokeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(revokeIntent);
        Thread.sleep(500);
    }

    public void testNotificationDelegate_grantAndRevoke() throws Exception {
        // grant this test permission to post
        final Intent activityIntent = new Intent();
        activityIntent.setPackage(DELEGATOR);
        activityIntent.setAction(Intent.ACTION_MAIN);
        activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        mContext.startActivity(activityIntent);
        Thread.sleep(500);

        assertTrue(mNotificationManager.canNotifyAsPackage(DELEGATOR));

        final Intent revokeIntent = new Intent();
        revokeIntent.setClassName(DELEGATOR, REVOKE_CLASS);
        revokeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(revokeIntent);
        Thread.sleep(500);

        try {
            // send notification
            Notification n = new Notification.Builder(mContext, "channel")
                    .setSmallIcon(android.R.id.icon)
                    .build();
            mNotificationManager.notifyAsPackage(DELEGATOR, "tag", 0, n);
            fail("Should not be able to post as a delegate when permission revoked");
        } catch (SecurityException e) {
            // yay
        }
    }

    public void testAreBubblesAllowed() {
        assertTrue(mNotificationManager.areBubblesAllowed());
    }

    public void testNotificationIcon() {
        int id = 6000;

        Notification notification =
                new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(android.R.id.icon)
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

        StatusBarNotification n = findPostedNotification(id);
        assertNotNull(n);
    }

    public void testShouldHideSilentStatusIcons() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        try {
            mNotificationManager.shouldHideSilentStatusBarIcons();
            fail("Non-privileged apps should not get this information");
        } catch (SecurityException e) {
            // pass
        }

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        // no exception this time
        mNotificationManager.shouldHideSilentStatusBarIcons();
    }

    public void testMatchesCallFilter() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }

        // allow all callers
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        NotificationManager.Policy currPolicy = mNotificationManager.getNotificationPolicy();
        NotificationManager.Policy newPolicy = new NotificationManager.Policy(
                NotificationManager.Policy.PRIORITY_CATEGORY_CALLS
                        | NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS,
                NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                currPolicy.priorityMessageSenders,
                currPolicy.suppressedVisualEffects);
        mNotificationManager.setNotificationPolicy(newPolicy);

        // add a contact
        String ALICE = "Alice";
        String ALICE_PHONE = "+16175551212";
        String ALICE_EMAIL = "alice@_foo._bar";

        insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, false);

        final Bundle peopleExtras = new Bundle();
        ArrayList<Person> personList = new ArrayList<>();
        personList.add(new Person.Builder().setUri(lookupContact(ALICE_PHONE).toString()).build());
        peopleExtras.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, personList);
        SystemUtil.runWithShellPermissionIdentity(() ->
                assertTrue(mNotificationManager.matchesCallFilter(peopleExtras)));
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

    public void testNotificationListener_setNotificationsShown() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        Thread.sleep(500); // wait for listener to be allowed

        mListener = TestNotificationListener.getInstance();
        assertNotNull(mListener);
        final int notificationId1 = 1;
        final int notificationId2 = 2;

        sendNotification(notificationId1, R.drawable.black);
        sendNotification(notificationId2, R.drawable.black);
        Thread.sleep(500); // wait for notification listener to receive notification

        StatusBarNotification sbn1 = findPostedNotification(notificationId1);
        StatusBarNotification sbn2 = findPostedNotification(notificationId2);
        mListener.setNotificationsShown(new String[]{ sbn1.getKey() });

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), false);
        Thread.sleep(500); // wait for listener to be disallowed
        try {
            mListener.setNotificationsShown(new String[]{ sbn2.getKey() });
            fail("Should not be able to set shown if listener access isn't granted");
        } catch (SecurityException e) {
            // expected
        }
    }

    public void testNotificationListener_getNotificationChannels() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        Thread.sleep(500); // wait for listener to be allowed

        mListener = TestNotificationListener.getInstance();
        assertNotNull(mListener);

        try {
            mListener.getNotificationChannels(mContext.getPackageName(), UserHandle.CURRENT);
            fail("Shouldn't be able get channels without CompanionDeviceManager#getAssociations()");
        } catch (SecurityException e) {
            // expected
        }
    }

    public void testNotificationListener_getNotificationChannelGroups() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        Thread.sleep(500); // wait for listener to be allowed

        mListener = TestNotificationListener.getInstance();
        assertNotNull(mListener);
        try {
            mListener.getNotificationChannelGroups(mContext.getPackageName(), UserHandle.CURRENT);
            fail("Should not be able get groups without CompanionDeviceManager#getAssociations()");
        } catch (SecurityException e) {
            // expected
        }
    }

    public void testNotificationListener_updateNotificationChannel() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        Thread.sleep(500); // wait for listener to be allowed

        mListener = TestNotificationListener.getInstance();
        assertNotNull(mListener);

        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "name", NotificationManager.IMPORTANCE_DEFAULT);
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
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        Thread.sleep(500); // wait for listener to be allowed

        mListener = TestNotificationListener.getInstance();
        assertNotNull(mListener);
        final int notificationId1 = 1;
        final int notificationId2 = 2;

        sendNotification(notificationId1, R.drawable.black);
        sendNotification(notificationId2, R.drawable.black);
        Thread.sleep(500); // wait for notification listener to receive notification

        StatusBarNotification sbn1 = findPostedNotification(notificationId1);
        StatusBarNotification sbn2 = findPostedNotification(notificationId2);
        StatusBarNotification[] notifs =
                mListener.getActiveNotifications(new String[]{ sbn2.getKey(), sbn1.getKey() });
        assertEquals(sbn2.getKey(), notifs[0].getKey());
        assertEquals(sbn2.getId(), notifs[0].getId());
        assertEquals(sbn2.getPackageName(), notifs[0].getPackageName());

        assertEquals(sbn1.getKey(), notifs[1].getKey());
        assertEquals(sbn1.getId(), notifs[1].getId());
        assertEquals(sbn1.getPackageName(), notifs[1].getPackageName());
    }


    public void testNotificationListener_getCurrentRanking() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        Thread.sleep(500); // wait for listener to be allowed

        mListener = TestNotificationListener.getInstance();
        assertNotNull(mListener);

        sendNotification(1, R.drawable.black);
        Thread.sleep(500); // wait for notification listener to receive notification

        assertEquals(mListener.mRankingMap, mListener.getCurrentRanking());
    }

    public void testNotificationListener_cancelNotifications() throws Exception {
        if (mActivityManager.isLowRamDevice() && !mPackageManager.hasSystemFeature(FEATURE_WATCH)) {
            return;
        }

        toggleListenerAccess(TestNotificationListener.getId(),
                InstrumentationRegistry.getInstrumentation(), true);
        Thread.sleep(500); // wait for listener to be allowed

        mListener = TestNotificationListener.getInstance();
        assertNotNull(mListener);
        final int notificationId = 1;

        sendNotification(notificationId, R.drawable.black);
        Thread.sleep(500); // wait for notification listener to receive notification

        StatusBarNotification sbn = findPostedNotification(notificationId);

        mListener.cancelNotification(sbn.getPackageName(), sbn.getTag(), sbn.getId());
        if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.LOLLIPOP) {
            if (!checkNotificationExistence(notificationId, /*shouldExist=*/ true)) {
                fail("Notification shouldn't have been cancelled. "
                        + "cancelNotification(String, String, int) shouldn't cancel notif for L+");
            }
        } else {
            // Tested in LegacyNotificationManager20Test
            if (checkNotificationExistence(notificationId, /*shouldExist=*/ true)) {
                fail("Notification should have been cancelled for targetSdk below L.  targetSdk="
                    + mContext.getApplicationInfo().targetSdkVersion);
            }
        }

        mListener.cancelNotifications(new String[]{ sbn.getKey() });
        if (!checkNotificationExistence(notificationId, /*shouldExist=*/ false)) {
            fail("Failed to cancel notification id=" + notificationId);
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
        assertNotNull("priorityCategories with a non-relevant int returns a string", oneString);
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

    public void testNotificationManagerBubblePolicy_flagForMessage_failsNoRemoteInput()
            throws InterruptedException {
        try {
            // turn on bubbles globally
            toggleBubbleSetting(true);

            Person person = new Person.Builder()
                    .setName("bubblebot")
                    .build();
            Notification.Builder nb = new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("foo")
                    .setStyle(new Notification.MessagingStyle(person)
                            .setConversationTitle("Bubble Chat")
                            .addMessage("Hello?",
                                    SystemClock.currentThreadTimeMillis() - 300000, person)
                            .addMessage("Is it me you're looking for?",
                                    SystemClock.currentThreadTimeMillis(), person)
                    )
                    .setSmallIcon(android.R.drawable.sym_def_app_icon);
            sendAndVerifyBubble(1, nb, null /* use default metadata */, false);
        } finally {
            // turn off bubbles globally
            toggleBubbleSetting(false);
        }
    }

    public void testNotificationManagerBubblePolicy_flagForMessage_succeeds()
            throws InterruptedException {
        try {
            // turn on bubbles globally
            toggleBubbleSetting(true);

            Person person = new Person.Builder()
                    .setName("bubblebot")
                    .build();

            RemoteInput remoteInput = new RemoteInput.Builder("reply_key").setLabel(
                    "reply").build();
            PendingIntent inputIntent = PendingIntent.getActivity(mContext, 0, new Intent(), 0);
            Icon icon = Icon.createWithResource(mContext, android.R.drawable.sym_def_app_icon);
            Notification.Action replyAction = new Notification.Action.Builder(icon, "Reply",
                    inputIntent).addRemoteInput(remoteInput)
                    .build();

            Notification.Builder nb = new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("foo")
                    .setStyle(new Notification.MessagingStyle(person)
                            .setConversationTitle("Bubble Chat")
                            .addMessage("Hello?",
                                    SystemClock.currentThreadTimeMillis() - 300000, person)
                            .addMessage("Is it me you're looking for?",
                                    SystemClock.currentThreadTimeMillis(), person)
                    )
                    .setActions(replyAction)
                    .setSmallIcon(android.R.drawable.sym_def_app_icon);

            boolean shouldBeBubble = !mActivityManager.isLowRamDevice();
            sendAndVerifyBubble(1, nb, null /* use default metadata */, shouldBeBubble);
        } finally {
            // turn off bubbles globally
            toggleBubbleSetting(false);
        }
    }

    public void testNotificationManagerBubblePolicy_flagForPhonecall() throws InterruptedException {
        Intent serviceIntent = new Intent(mContext, BubblesTestService.class);
        serviceIntent.putExtra(EXTRA_TEST_CASE, TEST_SUCCESS);

        try {
            // turn on bubbles globally
            toggleBubbleSetting(true);
            mContext.startService(serviceIntent);

            boolean shouldBeBubble = !mActivityManager.isLowRamDevice();
            if (!checkNotificationExistence(BUBBLE_NOTIF_ID,
                    true /* shouldExist */, shouldBeBubble)) {
                fail("couldn't find posted notification bubble with id=" + BUBBLE_NOTIF_ID);
            }

        } finally {
            mContext.stopService(serviceIntent);
            // turn off bubbles globally
            toggleBubbleSetting(false);
        }
    }

    public void testNotificationManagerBubblePolicy_flagForPhonecallFailsNoPerson()
            throws InterruptedException {
        Intent serviceIntent = new Intent(mContext, BubblesTestService.class);
        serviceIntent.putExtra(EXTRA_TEST_CASE, TEST_NO_PERSON);

        try {
            // turn on bubbles globally
            toggleBubbleSetting(true);
            mContext.startService(serviceIntent);

            if (!checkNotificationExistence(BUBBLE_NOTIF_ID,
                    true /* shouldExist */, false /* shouldBeBubble */)) {
                fail("couldn't find posted notification with id=" + BUBBLE_NOTIF_ID
                        + " or it was a bubble when it shouldn't be");
            }
        } finally {
            mContext.stopService(serviceIntent);
            // turn off bubbles globally
            toggleBubbleSetting(false);
        }
    }

    public void testNotificationManagerBubblePolicy_flagForPhonecallFailsNoForeground()
            throws InterruptedException {
        try {
            // turn on bubbles globally
            toggleBubbleSetting(true);

            Person person = new Person.Builder()
                    .setName("bubblebot")
                    .build();
            Notification.Builder nb = new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("foo")
                    .setCategory(CATEGORY_CALL)
                    .addPerson(person)
                    .setSmallIcon(android.R.drawable.sym_def_app_icon);
            sendAndVerifyBubble(1, nb, null /* use default metadata */, false /* shouldBeBubble */);

        } finally {
            // turn off bubbles globally
            toggleBubbleSetting(false);
        }
    }

    public void testNotificationManagerBubblePolicy_flagForPhonecallFailsNoCategory()
            throws InterruptedException {
        Intent serviceIntent = new Intent(mContext, BubblesTestService.class);
        serviceIntent.putExtra(EXTRA_TEST_CASE, TEST_NO_CATEGORY);

        try {
            // turn on bubbles globally
            toggleBubbleSetting(true);
            mContext.startService(serviceIntent);

            if (!checkNotificationExistence(BUBBLE_NOTIF_ID,
                    true /* shouldExist */, false /* shouldBeBubble */)) {
                fail("couldn't find posted notification with id=" + BUBBLE_NOTIF_ID
                        + " or it was a bubble when it shouldn't be");
            }

        } finally {
            mContext.stopService(serviceIntent);
            // turn off bubbles globally
            toggleBubbleSetting(false);
        }

    }

    public void testNotificationManagerBubblePolicy_flagForPhonecallFailsNoMetadata()
            throws InterruptedException {
        Intent serviceIntent = new Intent(mContext, BubblesTestService.class);
        serviceIntent.putExtra(EXTRA_TEST_CASE, TEST_NO_BUBBLE_METADATA);

        try {
            // turn on bubbles globally
            toggleBubbleSetting(true);
            mContext.startService(serviceIntent);

            if (!checkNotificationExistence(BUBBLE_NOTIF_ID,
                    true /* shouldExist */, false /* shouldBeBubble */)) {
                fail("couldn't find posted notification with id=" + BUBBLE_NOTIF_ID
                        + " or it was a bubble when it shouldn't be");
            }
        } finally {
            mContext.stopService(serviceIntent);
            // turn off bubbles globally
            toggleBubbleSetting(false);
        }
    }

    public void testNotificationManagerBubblePolicy_noFlagForAppNotForeground()
            throws InterruptedException {
        try {
            // turn on bubbles globally
            toggleBubbleSetting(true);

            sendAndVerifyBubble(1, null /* use default notif */, null /* use default metadata */,
                    false /* shouldBeBubble */);
        } finally {
            // turn off bubbles globally
            toggleBubbleSetting(false);
        }
    }

    public void testNotificationManagerBubblePolicy_flagForAppForeground() throws Exception {
        try {
            // turn on bubbles globally
            toggleBubbleSetting(true);

            final CountDownLatch latch = new CountDownLatch(2);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    latch.countDown();
                }
            };
            IntentFilter filter = new IntentFilter(BubblesTestActivity.BUBBLE_ACTIVITY_OPENED);
            mContext.registerReceiver(receiver, filter);

            // Start & get the activity
            BubblesTestActivity a = (BubblesTestActivity) launchSendBubbleActivity();

            // Make sure device is unlocked
            KeyguardManager keyguardManager =
                    (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
            keyguardManager.requestDismissKeyguard(a, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    latch.countDown();
                }
            });
            try {
                latch.await(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // Should be foreground now
            a.sendBubble(1);

            if (!checkNotificationExistence(BUBBLE_NOTIF_ID,
                    true /* shouldExist */, true /* shouldBeBubble */)) {
                fail("couldn't find posted notification bubble with id=" + BUBBLE_NOTIF_ID);
            }

            // Make ourselves not foreground
            HomeHelper homeHelper = new HomeHelper();
            homeHelper.goHome();

            // The notif should be allowed to update as a bubble
            a.sendBubble(2);

            boolean shouldBeBubble = !mActivityManager.isLowRamDevice();

            if (!checkNotificationExistence(BUBBLE_NOTIF_ID,
                    true /* shouldExist */, shouldBeBubble)) {
                fail("couldn't find posted notification bubble with id=" + BUBBLE_NOTIF_ID);
            }

            // Cancel the notif
            cancelAndPoll(BUBBLE_NOTIF_ID);

            // Send it again when not foreground, this should not be a bubble & just be a notif
            a.sendBubble(3);
            if (!checkNotificationExistence(BUBBLE_NOTIF_ID,
                    true /* shouldExist */, false /* shouldBeBubble */)) {
                fail("couldn't find posted notification with id=" + BUBBLE_NOTIF_ID
                        + " or it was a bubble when it shouldn't be");
            }

            mContext.unregisterReceiver(receiver);
            homeHelper.close();
        } finally {
            // turn off bubbles globally
            toggleBubbleSetting(false);
        }
    }

    public void testNotificationManagerBubblePolicy_noFlag_notEmbeddable() throws Exception {
        Person person = new Person.Builder()
                .setName("bubblebot")
                .build();

        RemoteInput remoteInput = new RemoteInput.Builder("reply_key").setLabel("reply").build();
        PendingIntent inputIntent = PendingIntent.getActivity(mContext, 0, new Intent(), 0);
        Icon icon = Icon.createWithResource(mContext, android.R.drawable.sym_def_app_icon);
        Notification.Action replyAction = new Notification.Action.Builder(icon, "Reply",
                inputIntent).addRemoteInput(remoteInput)
                .build();

        Notification.Builder nb = new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("foo")
                .setStyle(new Notification.MessagingStyle(person)
                        .setConversationTitle("Bubble Chat")
                        .addMessage("Hello?",
                                SystemClock.currentThreadTimeMillis() - 300000, person)
                        .addMessage("Is it me you're looking for?",
                                SystemClock.currentThreadTimeMillis(), person)
                )
                .setActions(replyAction)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        final Intent intent = new Intent(mContext, BubblesTestNotEmbeddableActivity.class);
        final PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, intent, 0);

        Notification.BubbleMetadata.Builder metadataBuilder =
                new Notification.BubbleMetadata.Builder()
                        .setIntent(pendingIntent)
                        .setIcon(Icon.createWithResource(mContext, R.drawable.black));

        sendAndVerifyBubble(1, nb, metadataBuilder.build(), false);
    }

    public void testNotificationManagerBubblePolicy_noFlag_notDocumentLaunchModeAlways() throws Exception {
        Person person = new Person.Builder()
                .setName("bubblebot")
                .build();

        RemoteInput remoteInput = new RemoteInput.Builder("reply_key").setLabel("reply").build();
        PendingIntent inputIntent = PendingIntent.getActivity(mContext, 0, new Intent(), 0);
        Icon icon = Icon.createWithResource(mContext, android.R.drawable.sym_def_app_icon);
        Notification.Action replyAction = new Notification.Action.Builder(icon, "Reply",
                inputIntent).addRemoteInput(remoteInput)
                .build();

        Notification.Builder nb = new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("foo")
                .setStyle(new Notification.MessagingStyle(person)
                        .setConversationTitle("Bubble Chat")
                        .addMessage("Hello?",
                                SystemClock.currentThreadTimeMillis() - 300000, person)
                        .addMessage("Is it me you're looking for?",
                                SystemClock.currentThreadTimeMillis(), person)
                )
                .setActions(replyAction)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        final Intent intent = new Intent(mContext, BubblesTestNotDocumentLaunchModeActivity.class);
        final PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, intent, 0);

        Notification.BubbleMetadata.Builder metadataBuilder =
                new Notification.BubbleMetadata.Builder()
                        .setIntent(pendingIntent)
                        .setIcon(Icon.createWithResource(mContext, R.drawable.black));

        sendAndVerifyBubble(1, nb, metadataBuilder.build(), false);
    }
}
