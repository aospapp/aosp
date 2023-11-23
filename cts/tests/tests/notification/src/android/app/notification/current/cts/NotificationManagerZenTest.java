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

package android.app.notification.current.cts;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.Manifest.permission.REVOKE_POST_NOTIFICATIONS_WITHOUT_KILL;
import static android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS;
import static android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS;
import static android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
import static android.app.NotificationManager.INTERRUPTION_FILTER_NONE;
import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_ANYONE;
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_NONE;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_CALLS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;

import android.Manifest;
import android.app.AutomaticZenRule;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.stubs.shared.NotificationHelper.SEARCH_TYPE;
import android.app.stubs.AutomaticZenRuleActivity;
import android.app.stubs.GetResultActivity;
import android.app.stubs.R;
import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteException;
import android.permission.PermissionManager;
import android.permission.cts.PermissionUtils;
import android.provider.ContactsContract;
import android.service.notification.Condition;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenPolicy;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.android.compatibility.common.util.CddTest;
import com.android.compatibility.common.util.SystemUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Tests zen/dnd related logic in NotificationManager.
 */
public class NotificationManagerZenTest extends BaseNotificationManagerTest {

    private static final String TAG = NotificationManagerZenTest.class.getSimpleName();

    private static final String NOTIFICATION_CHANNEL_ID = TAG;
    private static final String NOTIFICATION_CHANNEL_ID_NOISY = TAG + "/noisy";
    private static final String NOTIFICATION_CHANNEL_ID_MEDIA = TAG + "/media";
    private static final String NOTIFICATION_CHANNEL_ID_GAME = TAG + "/game";
    private static final String ALICE = "Alice";
    private static final String ALICE_PHONE = "+16175551212";
    private static final String ALICE_EMAIL = "alice@_foo._bar";
    private static final String BOB = "Bob";
    private static final String BOB_PHONE = "+16505551212";;
    private static final String BOB_EMAIL = "bob@_foo._bar";
    private static final String CHARLIE = "Charlie";
    private static final String CHARLIE_PHONE = "+13305551212";
    private static final String CHARLIE_EMAIL = "charlie@_foo._bar";
    private static final int MODE_NONE = 0;
    private static final int MODE_URI = 1;
    private static final int MODE_PHONE = 2;
    private static final int MODE_EMAIL = 3;
    private static final int SEND_A = 0x1;
    private static final int SEND_B = 0x2;
    private static final int SEND_C = 0x4;
    private static final int SEND_ALL = SEND_A | SEND_B | SEND_C;
    private static final int MATCHES_CALL_FILTER_NOT_PERMITTED = 0;
    private static final int MATCHES_CALL_FILTER_PERMITTED = 1;
    private static final String MATCHES_CALL_FILTER_CLASS =
            TEST_APP + ".MatchesCallFilterTestActivity";
    private static final String MINIMAL_LISTENER_CLASS = TEST_APP + ".TestNotificationListener";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PermissionUtils.grantPermission(mContext.getPackageName(), POST_NOTIFICATIONS);

        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        createChannels();
    }

    @Override
    protected void tearDown() throws Exception {
        // Use test API to prevent PermissionManager from killing the test process when revoking
        // permission.
        SystemUtil.runWithShellPermissionIdentity(
                () -> mContext.getSystemService(PermissionManager.class)
                        .revokePostNotificationPermissionWithoutKillForTest(
                                mContext.getPackageName(),
                                android.os.Process.myUserHandle().getIdentifier()),
                REVOKE_POST_NOTIFICATIONS_WITHOUT_KILL,
                REVOKE_RUNTIME_PERMISSIONS);

        mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALL);

        final ArrayList<ContentProviderOperation> operationList = new ArrayList<>();
        Uri aliceUri = lookupContact(ALICE_PHONE);
        if (aliceUri != null) {
            operationList.add(ContentProviderOperation.newDelete(aliceUri).build());
        }
        Uri bobUri = lookupContact(BOB_PHONE);
        if (bobUri != null) {
            operationList.add(ContentProviderOperation.newDelete(bobUri).build());
        }
        Uri charlieUri = lookupContact(CHARLIE_PHONE);
        if (charlieUri != null) {
            operationList.add(ContentProviderOperation.newDelete(charlieUri).build());
        }
        if (aliceUri != null || bobUri != null || charlieUri != null) {
            try {
                mContext.getContentResolver().applyBatch(ContactsContract.AUTHORITY, operationList);
            } catch (RemoteException e) {
                Log.e(TAG, String.format("%s: %s", e, e.getMessage()));
            } catch (OperationApplicationException e) {
                Log.e(TAG, String.format("%s: %s", e, e.getMessage()));
            }
        }

        mListener.resetData();
        mNotificationHelper.disableListener(STUB_PACKAGE_NAME);

        deleteChannels();

        super.tearDown();
    }

    private void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }
    }
    // usePriorities true: B, C, A
    // usePriorities false:
    //   MODE_NONE: C, B, A
    //   otherwise: A, B ,C
    private void sendNotifications(int annotationMode, boolean uriMode, boolean noisy) {
        sendNotifications(SEND_ALL, annotationMode, uriMode, noisy);
    }

    private void sendNotifications(int which, int uriMode, boolean usePriorities, boolean noisy) {
        // C, B, A when sorted by time.  Times must be in the past
        long whenA = System.currentTimeMillis() - 4000000L;
        long whenB = System.currentTimeMillis() - 2000000L;
        long whenC = System.currentTimeMillis() - 1000000L;

        // B, C, A when sorted by priorities
        int priorityA = usePriorities ? Notification.PRIORITY_MIN : Notification.PRIORITY_DEFAULT;
        int priorityB = usePriorities ? Notification.PRIORITY_MAX : Notification.PRIORITY_DEFAULT;
        int priorityC = usePriorities ? Notification.PRIORITY_LOW : Notification.PRIORITY_DEFAULT;

        final String channelId = noisy ? NOTIFICATION_CHANNEL_ID_NOISY : NOTIFICATION_CHANNEL_ID;

        Uri aliceUri = lookupContact(ALICE_PHONE);
        Uri bobUri = lookupContact(BOB_PHONE);
        Uri charlieUri = lookupContact(CHARLIE_PHONE);
        if ((which & SEND_B) != 0) {
            Notification.Builder bob = new Notification.Builder(mContext, channelId)
                    .setContentTitle(BOB)
                    .setContentText(BOB)
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setPriority(priorityB)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setWhen(whenB);
            addPerson(uriMode, bob, bobUri, BOB_PHONE, BOB_EMAIL);
            mNotificationManager.notify(BOB, 2, bob.build());
        }
        if ((which & SEND_C) != 0) {
            Notification.Builder charlie =
                    new Notification.Builder(mContext, channelId)
                            .setContentTitle(CHARLIE)
                            .setContentText(CHARLIE)
                            .setSmallIcon(android.R.drawable.sym_def_app_icon)
                            .setPriority(priorityC)
                            .setCategory(Notification.CATEGORY_MESSAGE)
                            .setWhen(whenC);
            addPerson(uriMode, charlie, charlieUri, CHARLIE_PHONE, CHARLIE_EMAIL);
            mNotificationManager.notify(CHARLIE, 3, charlie.build());
        }
        if ((which & SEND_A) != 0) {
            Notification.Builder alice = new Notification.Builder(mContext, channelId)
                    .setContentTitle(ALICE)
                    .setContentText(ALICE)
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setPriority(priorityA)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setWhen(whenA);
            addPerson(uriMode, alice, aliceUri, ALICE_PHONE, ALICE_EMAIL);
            mNotificationManager.notify(ALICE, 1, alice.build());
        }

        mNotificationHelper.findPostedNotification(ALICE, 1, SEARCH_TYPE.POSTED);
        mNotificationHelper.findPostedNotification(BOB, 2, SEARCH_TYPE.POSTED);
        mNotificationHelper.findPostedNotification(CHARLIE, 3, SEARCH_TYPE.POSTED);
    }

    private void sendEventAlarmReminderNotifications(int which) {
        long when = System.currentTimeMillis() - 4000000L;
        final String channelId = NOTIFICATION_CHANNEL_ID;

        // Event notification to Alice
        if ((which & SEND_A) != 0) {
            Notification.Builder alice = new Notification.Builder(mContext, channelId)
                    .setContentTitle(ALICE)
                    .setContentText(ALICE)
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setCategory(Notification.CATEGORY_EVENT)
                    .setWhen(when);
            mNotificationManager.notify(ALICE, 4, alice.build());
        }

        // Alarm notification to Bob
        if ((which & SEND_B) != 0) {
            Notification.Builder bob = new Notification.Builder(mContext, channelId)
                    .setContentTitle(BOB)
                    .setContentText(BOB)
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setWhen(when);
            mNotificationManager.notify(BOB, 5, bob.build());
        }

        // Reminder notification to Charlie
        if ((which & SEND_C) != 0) {
            Notification.Builder charlie =
                    new Notification.Builder(mContext, channelId)
                            .setContentTitle(CHARLIE)
                            .setContentText(CHARLIE)
                            .setSmallIcon(android.R.drawable.sym_def_app_icon)
                            .setCategory(Notification.CATEGORY_REMINDER)
                            .setWhen(when);
            mNotificationManager.notify(CHARLIE, 6, charlie.build());
        }

        mNotificationHelper.findPostedNotification(ALICE, 4, SEARCH_TYPE.POSTED);
        mNotificationHelper.findPostedNotification(BOB, 5, SEARCH_TYPE.POSTED);
        mNotificationHelper.findPostedNotification(CHARLIE, 6, SEARCH_TYPE.POSTED);
    }

    private void sendAlarmOtherMediaNotifications(int which) {
        long when = System.currentTimeMillis() - 4000000L;
        final String channelId = NOTIFICATION_CHANNEL_ID;

        // Alarm notification to Alice
        if ((which & SEND_A) != 0) {
            Notification.Builder alice = new Notification.Builder(mContext, channelId)
                    .setContentTitle(ALICE)
                    .setContentText(ALICE)
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setWhen(when);
            mNotificationManager.notify(ALICE, 7, alice.build());
        }

        // "Other" notification to Bob
        if ((which & SEND_B) != 0) {
            Notification.Builder bob = new Notification.Builder(mContext,
                    NOTIFICATION_CHANNEL_ID_GAME)
                    .setContentTitle(BOB)
                    .setContentText(BOB)
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setWhen(when);
            mNotificationManager.notify(BOB, 8, bob.build());
        }

        // Media notification to Charlie
        if ((which & SEND_C) != 0) {
            Notification.Builder charlie =
                    new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID_MEDIA)
                            .setContentTitle(CHARLIE)
                            .setContentText(CHARLIE)
                            .setSmallIcon(android.R.drawable.sym_def_app_icon)
                            .setWhen(when);
            mNotificationManager.notify(CHARLIE, 9, charlie.build());
        }

        mNotificationHelper.findPostedNotification(ALICE, 7, SEARCH_TYPE.POSTED);
        mNotificationHelper.findPostedNotification(BOB, 8, SEARCH_TYPE.POSTED);
        mNotificationHelper.findPostedNotification(CHARLIE, 9, SEARCH_TYPE.POSTED);
    }

    private boolean hasReadContactsPermission(String pkgName) {
        return mPackageManager.checkPermission(Manifest.permission.READ_CONTACTS, pkgName)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void toggleReadContactsPermission(String pkgName, boolean on) {
        SystemUtil.runWithShellPermissionIdentity(() -> {
            if (on) {
                mInstrumentation.getUiAutomation().grantRuntimePermission(pkgName,
                        Manifest.permission.READ_CONTACTS);
            } else {
                mInstrumentation.getUiAutomation().revokeRuntimePermission(pkgName,
                        Manifest.permission.READ_CONTACTS);
            }
        });
    }

    // Creates a GetResultActivity into which one can call startActivityForResult with
    // in order to test the outcome of an activity that returns a result code.
    private GetResultActivity setUpGetResultActivity() {
        final Intent intent = new Intent(mContext, GetResultActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        GetResultActivity activity = (GetResultActivity) mInstrumentation.startActivitySync(intent);
        mInstrumentation.waitForIdleSync();
        activity.clearResult();
        return activity;
    }

    private void addPerson(int mode, Notification.Builder note,
            Uri uri, String phone, String email) {
        if (mode == MODE_URI && uri != null) {
            note.addPerson(uri.toString());
        } else if (mode == MODE_PHONE) {
            note.addPerson(Uri.fromParts("tel", phone, null).toString());
        } else if (mode == MODE_EMAIL) {
            note.addPerson(Uri.fromParts("mailto", email, null).toString());
        }
    }

    private void insertSingleContact(String name, String phone, String email, boolean starred) {
        final ArrayList<ContentProviderOperation> operationList =
                new ArrayList<ContentProviderOperation>();
        ContentProviderOperation.Builder builder =
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI);
        builder.withValue(ContactsContract.RawContacts.STARRED, starred ? 1 : 0);
        operationList.add(builder.build());

        builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
        builder.withValueBackReference(
                ContactsContract.CommonDataKinds.StructuredName.RAW_CONTACT_ID, 0);
        builder.withValue(ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);
        builder.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name);
        operationList.add(builder.build());

        if (phone != null) {
            builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
            builder.withValueBackReference(
                    ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID, 0);
            builder.withValue(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
            builder.withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE);
            builder.withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone);
            builder.withValue(ContactsContract.Data.IS_PRIMARY, 1);
            operationList.add(builder.build());
        }
        if (email != null) {
            builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
            builder.withValueBackReference(
                    ContactsContract.CommonDataKinds.Email.RAW_CONTACT_ID, 0);
            builder.withValue(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE);
            builder.withValue(ContactsContract.CommonDataKinds.Email.TYPE,
                    ContactsContract.CommonDataKinds.Email.TYPE_HOME);
            builder.withValue(ContactsContract.CommonDataKinds.Email.DATA, email);
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

    private boolean isStarred(Uri uri) {
        Cursor c = null;
        boolean starred = false;
        try {
            String[] projection = new String[] { ContactsContract.Contacts.STARRED };
            c = mContext.getContentResolver().query(uri, projection, null, null, null);
            if (c != null && c.getCount() > 0) {
                int starredIdx = c.getColumnIndex(ContactsContract.Contacts.STARRED);
                while (c.moveToNext()) {
                    starred |= c.getInt(starredIdx) == 1;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Problem getting content resolver or performing contacts query.", t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return starred;
    }

    private void createChannels() {
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_ID, NotificationManager.IMPORTANCE_MIN);
        mNotificationManager.createNotificationChannel(channel);
        NotificationChannel noisyChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID_NOISY,
                NOTIFICATION_CHANNEL_ID_NOISY, NotificationManager.IMPORTANCE_HIGH);
        noisyChannel.enableVibration(true);
        mNotificationManager.createNotificationChannel(noisyChannel);
        NotificationChannel mediaChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID_MEDIA,
                NOTIFICATION_CHANNEL_ID_MEDIA, NotificationManager.IMPORTANCE_HIGH);
        AudioAttributes.Builder aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA);
        mediaChannel.setSound(null, aa.build());
        mNotificationManager.createNotificationChannel(mediaChannel);
        NotificationChannel gameChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID_GAME,
                NOTIFICATION_CHANNEL_ID_GAME, NotificationManager.IMPORTANCE_HIGH);
        AudioAttributes.Builder aa2 = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        gameChannel.setSound(null, aa2.build());
        mNotificationManager.createNotificationChannel(gameChannel);
    }

    private void deleteChannels() {
        mNotificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_ID);
        mNotificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_ID_NOISY);
        mNotificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_ID_MEDIA);
        mNotificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_ID_GAME);
    }

    private void deleteSingleContact(Uri uri) {
        final ArrayList<ContentProviderOperation> operationList =
                new ArrayList<ContentProviderOperation>();
        operationList.add(ContentProviderOperation.newDelete(uri).build());
        try {
            mContext.getContentResolver().applyBatch(ContactsContract.AUTHORITY, operationList);
        } catch (RemoteException e) {
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        } catch (OperationApplicationException e) {
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        }
    }

    private int findTagInKeys(String tag, List<String> orderedKeys) {
        for (int i = 0; i < orderedKeys.size(); i++) {
            if (orderedKeys.get(i).contains(tag)) {
                return i;
            }
        }
        return -1;
    }

    // Simple helper function to take a phone number's string representation and make a tel: uri
    private Uri makePhoneUri(String phone) {
        return new Uri.Builder()
                .scheme("tel")
                .encodedOpaquePart(phone)  // don't re-encode anything passed in
                .build();
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

    private AutomaticZenRule createRule(String name, int filter) {
        return new AutomaticZenRule(name, null,
                new ComponentName(mContext, AutomaticZenRuleActivity.class),
                new Uri.Builder().scheme("scheme")
                        .appendPath("path")
                        .appendQueryParameter("fake_rule", "fake_value")
                        .build(), null, filter, true);
    }

    private AutomaticZenRule createRule(String name) {
        return createRule(name, INTERRUPTION_FILTER_PRIORITY);
    }

    // TESTS START

    public void testNotificationPolicyVisualEffectsEqual() {
        NotificationManager.Policy policy = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_ON);
        NotificationManager.Policy policy2 = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_PEEK);
        assertTrue(policy.equals(policy2));
        assertTrue(policy2.equals(policy));

        policy = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_ON);
        policy2 = new NotificationManager.Policy(0, 0, 0,
                0);
        assertFalse(policy.equals(policy2));
        assertFalse(policy2.equals(policy));

        policy = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_OFF);
        policy2 = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_FULL_SCREEN_INTENT | SUPPRESSED_EFFECT_AMBIENT
                        | SUPPRESSED_EFFECT_LIGHTS);
        assertTrue(policy.equals(policy2));
        assertTrue(policy2.equals(policy));

        policy = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_OFF);
        policy2 = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_LIGHTS);
        assertFalse(policy.equals(policy2));
        assertFalse(policy2.equals(policy));
    }

    public void testGetSuppressedVisualEffectsOff_ranking() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
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
        final int originalFilter = mNotificationManager.getCurrentInterruptionFilter();
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        try {
            mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
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
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);

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
            mNotificationManager.setNotificationPolicy(origPolicy);
        }

    }

    public void testConsolidatedNotificationPolicy() throws Exception {
        final int originalFilter = mNotificationManager.getCurrentInterruptionFilter();
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        try {
            toggleNotificationPolicyAccess(mContext.getPackageName(),
                    InstrumentationRegistry.getInstrumentation(), true);

            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                    PRIORITY_CATEGORY_ALARMS | PRIORITY_CATEGORY_MEDIA,
                    0, 0));
            // turn on manual DND
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);
            assertExpectedDndState(INTERRUPTION_FILTER_PRIORITY);

            // no custom ZenPolicy, so consolidatedPolicy should equal the default notif policy
            assertEquals(mNotificationManager.getConsolidatedNotificationPolicy(),
                    mNotificationManager.getNotificationPolicy());

            // turn off manual DND
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALL);
            assertExpectedDndState(INTERRUPTION_FILTER_ALL);

            // setup custom ZenPolicy for an automatic rule
            AutomaticZenRule rule = createRule("test_consolidated_policy",
                    INTERRUPTION_FILTER_PRIORITY);
            rule.setZenPolicy(new ZenPolicy.Builder()
                    .allowReminders(true)
                    .build());
            String id = mNotificationManager.addAutomaticZenRule(rule);
            mRuleIds.add(id);
            // set condition of the automatic rule to TRUE
            Condition condition = new Condition(rule.getConditionId(), "summary",
                    Condition.STATE_TRUE);
            mNotificationManager.setAutomaticZenRuleState(id, condition);
            assertExpectedDndState(INTERRUPTION_FILTER_PRIORITY);

            NotificationManager.Policy consolidatedPolicy =
                    mNotificationManager.getConsolidatedNotificationPolicy();

            // alarms and media are allowed from default notification policy
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_ALARMS) != 0);
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_MEDIA) != 0);

            // reminders is allowed from the automatic rule's custom ZenPolicy
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_REMINDERS) != 0);

            // other sounds aren't allowed
            assertTrue((consolidatedPolicy.priorityCategories
                    & PRIORITY_CATEGORY_CONVERSATIONS) == 0);
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_CALLS) == 0);
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_MESSAGES) == 0);
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_SYSTEM) == 0);
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_EVENTS) == 0);
        } finally {
            mNotificationManager.setInterruptionFilter(originalFilter);
            mNotificationManager.setNotificationPolicy(origPolicy);
        }
    }

    public void testConsolidatedNotificationPolicyMultiRules() throws Exception {
        final int originalFilter = mNotificationManager.getCurrentInterruptionFilter();
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        try {
            toggleNotificationPolicyAccess(mContext.getPackageName(),
                    InstrumentationRegistry.getInstrumentation(), true);

            // default allows no sounds
            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                    PRIORITY_CATEGORY_ALARMS, 0, 0));

            // setup custom ZenPolicy for two automatic rules
            AutomaticZenRule rule1 = createRule("test_consolidated_policyq",
                    INTERRUPTION_FILTER_PRIORITY);
            rule1.setZenPolicy(new ZenPolicy.Builder()
                    .allowReminders(false)
                    .allowAlarms(false)
                    .allowSystem(true)
                    .build());
            AutomaticZenRule rule2 = createRule("test_consolidated_policy2",
                    INTERRUPTION_FILTER_PRIORITY);
            rule2.setZenPolicy(new ZenPolicy.Builder()
                    .allowReminders(true)
                    .allowMedia(true)
                    .build());
            String id1 = mNotificationManager.addAutomaticZenRule(rule1);
            String id2 = mNotificationManager.addAutomaticZenRule(rule2);
            Condition onCondition1 = new Condition(rule1.getConditionId(), "summary",
                    Condition.STATE_TRUE);
            Condition onCondition2 = new Condition(rule2.getConditionId(), "summary",
                    Condition.STATE_TRUE);
            mNotificationManager.setAutomaticZenRuleState(id1, onCondition1);
            mNotificationManager.setAutomaticZenRuleState(id2, onCondition2);

            Thread.sleep(300); // wait for rules to be applied - it's done asynchronously

            mRuleIds.add(id1);
            mRuleIds.add(id2);
            assertExpectedDndState(INTERRUPTION_FILTER_PRIORITY);

            NotificationManager.Policy consolidatedPolicy =
                    mNotificationManager.getConsolidatedNotificationPolicy();

            // reminders aren't allowed from rule1 overriding rule2
            // (not allowed takes precedence over allowed)
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_REMINDERS) == 0);

            // alarms aren't allowed from rule1
            // (rule's custom zenPolicy overrides default policy)
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_ALARMS) == 0);

            // system is allowed from rule1, media is allowed from rule2
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_SYSTEM) != 0);
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_MEDIA) != 0);

            // other sounds aren't allowed (from default policy)
            assertTrue((consolidatedPolicy.priorityCategories
                    & PRIORITY_CATEGORY_CONVERSATIONS) == 0);
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_CALLS) == 0);
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_MESSAGES) == 0);
            assertTrue((consolidatedPolicy.priorityCategories & PRIORITY_CATEGORY_EVENTS) == 0);
        } finally {
            mNotificationManager.setInterruptionFilter(originalFilter);
            mNotificationManager.setNotificationPolicy(origPolicy);
        }
    }

    public void testPostPCanToggleAlarmsMediaSystemTest() throws Exception {
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);

        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        try {
            if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.P) {
                // Post-P can toggle alarms, media, system
                // toggle on alarms, media, system:
                mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                        PRIORITY_CATEGORY_ALARMS
                                | PRIORITY_CATEGORY_MEDIA
                                | PRIORITY_CATEGORY_SYSTEM, 0, 0));
                NotificationManager.Policy policy = mNotificationManager.getNotificationPolicy();
                assertTrue((policy.priorityCategories & PRIORITY_CATEGORY_ALARMS) != 0);
                assertTrue((policy.priorityCategories & PRIORITY_CATEGORY_MEDIA) != 0);
                assertTrue((policy.priorityCategories & PRIORITY_CATEGORY_SYSTEM) != 0);

                // toggle off alarms, media, system
                mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(0, 0, 0));
                policy = mNotificationManager.getNotificationPolicy();
                assertTrue((policy.priorityCategories & PRIORITY_CATEGORY_ALARMS) == 0);
                assertTrue((policy.priorityCategories & PRIORITY_CATEGORY_MEDIA) == 0);
                assertTrue((policy.priorityCategories & PRIORITY_CATEGORY_SYSTEM) == 0);
            }
        } finally {
            mNotificationManager.setNotificationPolicy(origPolicy);
        }
    }

    public void testPostRCanToggleConversationsTest() throws Exception {
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);

        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();

        try {
            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                    0, 0, 0, 0));
            NotificationManager.Policy policy = mNotificationManager.getNotificationPolicy();
            assertEquals(0, (policy.priorityCategories & PRIORITY_CATEGORY_CONVERSATIONS));
            assertEquals(CONVERSATION_SENDERS_NONE, policy.priorityConversationSenders);

            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                    PRIORITY_CATEGORY_CONVERSATIONS, 0, 0, 0, CONVERSATION_SENDERS_ANYONE));
            policy = mNotificationManager.getNotificationPolicy();
            assertTrue((policy.priorityCategories & PRIORITY_CATEGORY_CONVERSATIONS) != 0);
            assertEquals(CONVERSATION_SENDERS_ANYONE, policy.priorityConversationSenders);

        } finally {
            mNotificationManager.setNotificationPolicy(origPolicy);
        }
    }

    public void testTotalSilenceOnlyMuteStreams() throws Exception {
        final int originalFilter = mNotificationManager.getCurrentInterruptionFilter();
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        try {
            toggleNotificationPolicyAccess(mContext.getPackageName(),
                    InstrumentationRegistry.getInstrumentation(), true);

            // ensure volume is not muted/0 to start test
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0);
            // exception for presidential alert
            //mAudioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0);
            mAudioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 1, 0);
            mAudioManager.setStreamVolume(AudioManager.STREAM_RING, 1, 0);

            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                    PRIORITY_CATEGORY_ALARMS | PRIORITY_CATEGORY_MEDIA, 0, 0));
            AutomaticZenRule rule = createRule("test_total_silence", INTERRUPTION_FILTER_NONE);
            String id = mNotificationManager.addAutomaticZenRule(rule);
            mRuleIds.add(id);
            Condition condition =
                    new Condition(rule.getConditionId(), "summary", Condition.STATE_TRUE);
            mNotificationManager.setAutomaticZenRuleState(id, condition);
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);

            // delay for streams to get into correct mute states
            Thread.sleep(1000);
            assertTrue("Music (media) stream should be muted",
                    mAudioManager.isStreamMute(AudioManager.STREAM_MUSIC));
            assertTrue("System stream should be muted",
                    mAudioManager.isStreamMute(AudioManager.STREAM_SYSTEM));
            // exception for presidential alert
            //assertTrue("Alarm stream should be muted",
            //        mAudioManager.isStreamMute(AudioManager.STREAM_ALARM));

            // Test requires that the phone's default state has no channels that can bypass dnd
            // which we can't currently guarantee (b/169267379)
            // assertTrue("Ringer stream should be muted",
            //        mAudioManager.isStreamMute(AudioManager.STREAM_RING));
        } finally {
            mNotificationManager.setInterruptionFilter(originalFilter);
            mNotificationManager.setNotificationPolicy(origPolicy);
        }
    }

    public void testAlarmsOnlyMuteStreams() throws Exception {
        final int originalFilter = mNotificationManager.getCurrentInterruptionFilter();
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        try {
            toggleNotificationPolicyAccess(mContext.getPackageName(),
                    InstrumentationRegistry.getInstrumentation(), true);

            // ensure volume is not muted/0 to start test
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0);
            mAudioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0);
            mAudioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 1, 0);
            mAudioManager.setStreamVolume(AudioManager.STREAM_RING, 1, 0);

            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                    PRIORITY_CATEGORY_ALARMS | PRIORITY_CATEGORY_MEDIA, 0, 0));
            AutomaticZenRule rule = createRule("test_alarms", INTERRUPTION_FILTER_ALARMS);
            String id = mNotificationManager.addAutomaticZenRule(rule);
            mRuleIds.add(id);
            Condition condition =
                    new Condition(rule.getConditionId(), "summary", Condition.STATE_TRUE);
            mNotificationManager.setAutomaticZenRuleState(id, condition);
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);

            // delay for streams to get into correct mute states
            Thread.sleep(1000);
            assertFalse("Music (media) stream should not be muted",
                    mAudioManager.isStreamMute(AudioManager.STREAM_MUSIC));
            assertTrue("System stream should be muted",
                    mAudioManager.isStreamMute(AudioManager.STREAM_SYSTEM));
            assertFalse("Alarm stream should not be muted",
                    mAudioManager.isStreamMute(AudioManager.STREAM_ALARM));

            // Test requires that the phone's default state has no channels that can bypass dnd
            // which we can't currently guarantee (b/169267379)
            // assertTrue("Ringer stream should be muted",
            //  mAudioManager.isStreamMute(AudioManager.STREAM_RING));
        } finally {
            mNotificationManager.setInterruptionFilter(originalFilter);
            mNotificationManager.setNotificationPolicy(origPolicy);
        }
    }

    public void testAddAutomaticZenRule_configActivity() throws Exception {
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);

        AutomaticZenRule ruleToCreate = createRule("Rule");
        String id = mNotificationManager.addAutomaticZenRule(ruleToCreate);

        assertNotNull(id);
        mRuleIds.add(id);
        assertTrue(areRulesSame(ruleToCreate, mNotificationManager.getAutomaticZenRule(id)));
    }

    public void testUpdateAutomaticZenRule_configActivity() throws Exception {
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

    public void testSetAutomaticZenRuleState_multipleRules() throws Exception {
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);

        AutomaticZenRule ruleToCreate = createRule("Rule");
        String id = mNotificationManager.addAutomaticZenRule(ruleToCreate);
        mRuleIds.add(id);

        AutomaticZenRule secondRuleToCreate = createRule("Rule 2");
        secondRuleToCreate.setInterruptionFilter(INTERRUPTION_FILTER_NONE);
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
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        try {
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
        } finally {
            mNotificationManager.setNotificationPolicy(origPolicy);
        }
    }

    public void testSetNotificationPolicy_P_setNewFields() throws Exception {
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        try {
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
        } finally {
            mNotificationManager.setNotificationPolicy(origPolicy);
        }
    }

    public void testSetNotificationPolicy_P_setOldNewFields() throws Exception {
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        try {
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
        } finally {
            mNotificationManager.setNotificationPolicy(origPolicy);
        }
    }


    public void testMatchesCallFilter_noPermissions() {
        // make sure we definitely don't have contacts access
        boolean hadReadPerm = hasReadContactsPermission(TEST_APP);
        try {
            toggleReadContactsPermission(TEST_APP, false);

            // start an activity that has no permissions, which will run matchesCallFilter on
            // a meaningless uri. The result code indicates whether or not the method call was
            // permitted.
            final Intent mcfIntent = new Intent(Intent.ACTION_MAIN);
            mcfIntent.setClassName(TEST_APP, MATCHES_CALL_FILTER_CLASS);
            GetResultActivity grActivity = setUpGetResultActivity();
            grActivity.startActivityForResult(mcfIntent, REQUEST_CODE);
            UiDevice.getInstance(mInstrumentation).waitForIdle();

            // with no permissions, this call should not have been permitted
            GetResultActivity.Result result = grActivity.getResult();
            assertEquals(REQUEST_CODE, result.requestCode);
            assertEquals(MATCHES_CALL_FILTER_NOT_PERMITTED, result.resultCode);
            grActivity.finishActivity(REQUEST_CODE);
        } finally {
            toggleReadContactsPermission(TEST_APP, hadReadPerm);
        }
    }

    public void testMatchesCallFilter_listenerPermissionOnly() throws Exception {
        boolean hadReadPerm = hasReadContactsPermission(TEST_APP);
        // minimal listener service so that it can be given listener permissions
        final ComponentName listenerComponent =
                new ComponentName(TEST_APP, MINIMAL_LISTENER_CLASS);
        try {
            // make surethat we don't for some reason have contacts access
            toggleReadContactsPermission(TEST_APP, false);

            // grant the notification app package notification listener access;
            // give it time to succeed
            toggleExternalListenerAccess(listenerComponent, true);
            Thread.sleep(500);

            // set up & run intent
            final Intent mcfIntent = new Intent(Intent.ACTION_MAIN);
            mcfIntent.setClassName(TEST_APP, MATCHES_CALL_FILTER_CLASS);
            GetResultActivity grActivity = setUpGetResultActivity();
            grActivity.startActivityForResult(mcfIntent, REQUEST_CODE);
            UiDevice.getInstance(mInstrumentation).waitForIdle();

            // with just listener permissions, this call should have been permitted
            GetResultActivity.Result result = grActivity.getResult();
            assertEquals(REQUEST_CODE, result.requestCode);
            assertEquals(MATCHES_CALL_FILTER_PERMITTED, result.resultCode);
            grActivity.finishActivity(REQUEST_CODE);
        } finally {
            // clean up listener access, reset read contacts access
            toggleExternalListenerAccess(listenerComponent, false);
            toggleReadContactsPermission(TEST_APP, hadReadPerm);
        }
    }

    public void testMatchesCallFilter_contactsPermissionOnly() throws Exception {
        // grant the notification app package contacts read access
        boolean hadReadPerm = hasReadContactsPermission(TEST_APP);
        try {
            toggleReadContactsPermission(TEST_APP, true);

            // set up & run intent
            final Intent mcfIntent = new Intent(Intent.ACTION_MAIN);
            mcfIntent.setClassName(TEST_APP, MATCHES_CALL_FILTER_CLASS);
            GetResultActivity grActivity = setUpGetResultActivity();
            grActivity.startActivityForResult(mcfIntent, REQUEST_CODE);
            UiDevice.getInstance(mInstrumentation).waitForIdle();

            // with just contacts read permissions, this call should have been permitted
            GetResultActivity.Result result = grActivity.getResult();
            assertEquals(REQUEST_CODE, result.requestCode);
            assertEquals(MATCHES_CALL_FILTER_PERMITTED, result.resultCode);
            grActivity.finishActivity(REQUEST_CODE);
        } finally {
            // clean up contacts access
            toggleReadContactsPermission(TEST_APP, hadReadPerm);
        }
    }

    public void testMatchesCallFilter_zenOff() throws Exception {
        // zen mode is not on so nothing is filtered; matchesCallFilter should always pass
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        int origFilter = mNotificationManager.getCurrentInterruptionFilter();
        try {
            // allowed from anyone: nothing is filtered, and make sure change went through
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALL);
            assertExpectedDndState(INTERRUPTION_FILTER_ALL);

            // create a phone URI from which to receive a call
            Uri phoneUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode("+16175551212"));
            assertTrue(mNotificationManager.matchesCallFilter(phoneUri));
        } finally {
            mNotificationManager.setInterruptionFilter(origFilter);
        }
    }

    public void testMatchesCallFilter_noCallInterruptions() throws Exception {
        // when no call interruptions are allowed at all, or only alarms, matchesCallFilter
        // should always fail
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        int origFilter = mNotificationManager.getCurrentInterruptionFilter();
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        try {
            // create a phone URI from which to receive a call
            Uri phoneUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode("+16175551212"));

            // no interruptions allowed at all
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_NONE);
            assertExpectedDndState(INTERRUPTION_FILTER_NONE);
            assertFalse(mNotificationManager.matchesCallFilter(phoneUri));

            // only alarms
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALARMS);
            assertExpectedDndState(INTERRUPTION_FILTER_ALARMS);
            assertFalse(mNotificationManager.matchesCallFilter(phoneUri));

            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                    PRIORITY_CATEGORY_MESSAGES, 0, 0));
            // turn on manual DND
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);
            assertExpectedDndState(INTERRUPTION_FILTER_PRIORITY);
            assertFalse(mNotificationManager.matchesCallFilter(phoneUri));
        } finally {
            mNotificationManager.setInterruptionFilter(origFilter);
            mNotificationManager.setNotificationPolicy(origPolicy);
        }
    }

    public void testMatchesCallFilter_someCallers() throws Exception {
        // zen mode is active; check various configurations where some calls, but not all calls,
        // are allowed
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        int origFilter = mNotificationManager.getCurrentInterruptionFilter();
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();

        // for storing lookup URIs for deleting the contacts afterwards
        Uri aliceUri = null;
        Uri bobUri = null;
        try {
            // set up phone numbers: one starred, one regular, one unknown number
            // starred contact from whom to receive a call
            insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, true);
            aliceUri = lookupContact(ALICE_PHONE);
            Uri alicePhoneUri = makePhoneUri(ALICE_PHONE);

            // non-starred contact from whom to also receive a call
            insertSingleContact(BOB, BOB_PHONE, BOB_EMAIL, false);
            bobUri = lookupContact(BOB_PHONE);
            Uri bobPhoneUri = makePhoneUri(BOB_PHONE);

            // non-contact phone URI
            Uri phoneUri = makePhoneUri("+16175555656");

            // set up: any contacts are allowed to call.
            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                    PRIORITY_CATEGORY_CALLS,
                    NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS, 0));

            // turn on manual DND
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);
            assertExpectedDndState(INTERRUPTION_FILTER_PRIORITY);

            // in this case Alice and Bob should get through but not the unknown number.
            assertTrue(mNotificationManager.matchesCallFilter(alicePhoneUri));
            assertTrue(mNotificationManager.matchesCallFilter(bobPhoneUri));
            assertFalse(mNotificationManager.matchesCallFilter(phoneUri));

            // set up: only starred contacts are allowed to call.
            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                    PRIORITY_CATEGORY_CALLS,
                    NotificationManager.Policy.PRIORITY_SENDERS_STARRED, 0));
            assertExpectedDndState(INTERRUPTION_FILTER_PRIORITY);

            // now only Alice should be allowed to get through
            assertTrue(mNotificationManager.matchesCallFilter(alicePhoneUri));
            assertFalse(mNotificationManager.matchesCallFilter(bobPhoneUri));
            assertFalse(mNotificationManager.matchesCallFilter(phoneUri));
        } finally {
            mNotificationManager.setInterruptionFilter(origFilter);
            mNotificationManager.setNotificationPolicy(origPolicy);
            if (aliceUri != null) {
                // delete the contact
                deleteSingleContact(aliceUri);
            }
            if (bobUri != null) {
                deleteSingleContact(bobUri);
            }
        }
    }

    public void testMatchesCallFilter_repeatCallers() throws Exception {
        // if repeat callers are allowed, an unknown number calling twice should go through
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        int origFilter = mNotificationManager.getCurrentInterruptionFilter();
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        long startTime = System.currentTimeMillis();
        try {
            // create phone URIs from which to receive a call; one US, one non-US,
            // both fully specified
            Uri phoneUri = makePhoneUri("+16175551212");
            Uri phoneUri2 = makePhoneUri("+81 75 350 6006");

            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                    PRIORITY_CATEGORY_REPEAT_CALLERS, 0, 0));
            // turn on manual DND
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);
            assertExpectedDndState(INTERRUPTION_FILTER_PRIORITY);

            // not repeat callers yet, so it shouldn't be allowed
            assertFalse(mNotificationManager.matchesCallFilter(phoneUri));
            assertFalse(mNotificationManager.matchesCallFilter(phoneUri2));

            // register a call from number 1, then cancel the notification, which is when
            // a call is actually recorded.
            sendNotification(1, null, R.drawable.blue, true, phoneUri);
            cancelAndPoll(1);

            // now this number should count as a repeat caller
            assertTrue(mNotificationManager.matchesCallFilter(phoneUri));
            assertFalse(mNotificationManager.matchesCallFilter(phoneUri2));

            // also, any other variants of this phone number should also count as a repeat caller
            Uri[] variants = { makePhoneUri(Uri.encode("+1-617-555-1212")),
                    makePhoneUri("+1 (617) 555-1212") };
            for (int i = 0; i < variants.length; i++) {
                assertTrue("phone variant " + variants[i] + " should still match",
                        mNotificationManager.matchesCallFilter(variants[i]));
            }

            // register call 2
            sendNotification(2, null, R.drawable.blue, true, phoneUri2);
            cancelAndPoll(2);

            // now this should be a repeat caller
            assertTrue(mNotificationManager.matchesCallFilter(phoneUri2));

            Uri[] variants2 = { makePhoneUri(Uri.encode("+81 75 350 6006")),
                    makePhoneUri("+81753506006")};
            for (int j = 0; j < variants2.length; j++) {
                assertTrue("phone variant " + variants2[j] + " should still match",
                        mNotificationManager.matchesCallFilter(variants2[j]));
            }
        } finally {
            mNotificationManager.setInterruptionFilter(origFilter);
            mNotificationManager.setNotificationPolicy(origPolicy);

            // make sure we clean up the recent call, otherwise future runs of this will fail
            // and we'll have a fake call still kicking around somewhere.
            SystemUtil.runWithShellPermissionIdentity(() ->
                    mNotificationManager.cleanUpCallersAfter(startTime));
        }
    }

    public void testMatchesCallFilter_repeatCallers_fromContact() throws Exception {
        // set up such that only repeat callers (and not any individuals) are allowed; make sure
        // that a call registered with a contact's lookup URI will return the correct info
        // when matchesCallFilter is called with their phone number
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        int origFilter = mNotificationManager.getCurrentInterruptionFilter();
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        Uri aliceUri = null;
        long startTime = System.currentTimeMillis();
        try {
            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                    PRIORITY_CATEGORY_REPEAT_CALLERS, 0, 0));
            // turn on manual DND
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);
            assertExpectedDndState(INTERRUPTION_FILTER_PRIORITY);

            insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, false);
            aliceUri = lookupContact(ALICE_PHONE);
            Uri alicePhoneUri = makePhoneUri(ALICE_PHONE);

            // no one has called; matchesCallFilter should return false for both URIs
            assertFalse(mNotificationManager.matchesCallFilter(aliceUri));
            assertFalse(mNotificationManager.matchesCallFilter(alicePhoneUri));

            assertTrue(aliceUri.toString()
                    .startsWith(ContactsContract.Contacts.CONTENT_LOOKUP_URI.toString()));

            // register a call from Alice via the contact lookup URI, then cancel so the call is
            // recorded accordingly.
            sendNotification(1, null, R.drawable.blue, true, aliceUri);
            // wait for contact lookup of number to finish; this can take a while because it runs
            // in the background, so give it a fair bit of time
            Thread.sleep(3000);
            cancelAndPoll(1);

            // now a phone call from Alice's phone number should match the repeat callers list
            assertTrue(mNotificationManager.matchesCallFilter(alicePhoneUri));
        } finally {
            mNotificationManager.setInterruptionFilter(origFilter);
            mNotificationManager.setNotificationPolicy(origPolicy);
            if (aliceUri != null) {
                // delete the contact
                deleteSingleContact(aliceUri);
            }

            // clean up the recorded calls
            SystemUtil.runWithShellPermissionIdentity(() ->
                    mNotificationManager.cleanUpCallersAfter(startTime));
        }
    }

    public void testRepeatCallers_repeatCallNotIntercepted_contactAfterPhone() throws Exception {
        mListener = mNotificationHelper.enableListener(STUB_PACKAGE_NAME);
        assertNotNull(mListener);

        // if a call is recorded with just phone number info (not a contact's uri), which may
        // happen when the same contact calls across multiple apps (or if the contact uri provided
        // is otherwise inconsistent), check for the contact's phone number
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        int origFilter = mNotificationManager.getCurrentInterruptionFilter();
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        Uri aliceUri = null;
        long startTime = System.currentTimeMillis();
        try {
            mNotificationManager.setNotificationPolicy(new NotificationManager.Policy(
                    PRIORITY_CATEGORY_REPEAT_CALLERS, 0, 0));
            // turn on manual DND
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);
            assertExpectedDndState(INTERRUPTION_FILTER_PRIORITY);

            insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, false);
            aliceUri = lookupContact(ALICE_PHONE);
            Uri alicePhoneUri = makePhoneUri(ALICE_PHONE);

            // no one has called; matchesCallFilter should return false for both URIs
            assertFalse(mNotificationManager.matchesCallFilter(aliceUri));
            assertFalse(mNotificationManager.matchesCallFilter(alicePhoneUri));

            // register a call from Alice via just the phone number
            sendNotification(1, null, R.drawable.blue, true, alicePhoneUri);
            Thread.sleep(1000); // give the listener some time to receive info

            // check that the first notification is intercepted
            StatusBarNotification sbn = mNotificationHelper.findPostedNotification(null, 1,
                    SEARCH_TYPE.POSTED);
            assertNotNull(sbn);
            assertTrue(mListener.mIntercepted.containsKey(sbn.getKey()));
            assertTrue(mListener.mIntercepted.get(sbn.getKey()));  // should be intercepted

            // cancel first notification
            cancelAndPoll(1);

            // now send a call with only Alice's contact Uri as the info
            // Note that this is a test of the repeat caller check, not matchesCallFilter itself
            sendNotification(2, null, R.drawable.blue, true, aliceUri);
            // wait for contact lookup, which may take a while
            Thread.sleep(3000);

            // now check that the second notification is not intercepted
            StatusBarNotification sbn2 = mNotificationHelper.findPostedNotification(null, 2,
                    SEARCH_TYPE.POSTED);
            assertTrue(mListener.mIntercepted.containsKey(sbn2.getKey()));
            assertFalse(mListener.mIntercepted.get(sbn2.getKey()));  // should not be intercepted

            // cancel second notification
            cancelAndPoll(2);
        } finally {
            mNotificationManager.setInterruptionFilter(origFilter);
            mNotificationManager.setNotificationPolicy(origPolicy);
            if (aliceUri != null) {
                // delete the contact
                deleteSingleContact(aliceUri);
            }

            // clean up the recorded calls
            SystemUtil.runWithShellPermissionIdentity(() ->
                    mNotificationManager.cleanUpCallersAfter(startTime));
        }
    }

    public void testMatchesCallFilter_allCallers() throws Exception {
        // allow all callers
        toggleNotificationPolicyAccess(mContext.getPackageName(),
                InstrumentationRegistry.getInstrumentation(), true);
        int origFilter = mNotificationManager.getCurrentInterruptionFilter();
        NotificationManager.Policy origPolicy = mNotificationManager.getNotificationPolicy();
        Uri aliceUri = null;  // for deletion after the test is done
        try {
            NotificationManager.Policy currPolicy = mNotificationManager.getNotificationPolicy();
            NotificationManager.Policy newPolicy = new NotificationManager.Policy(
                    NotificationManager.Policy.PRIORITY_CATEGORY_CALLS
                            | PRIORITY_CATEGORY_REPEAT_CALLERS,
                    NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                    currPolicy.priorityMessageSenders,
                    currPolicy.suppressedVisualEffects);
            mNotificationManager.setNotificationPolicy(newPolicy);
            mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);
            assertExpectedDndState(INTERRUPTION_FILTER_PRIORITY);

            insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, false);
            aliceUri = lookupContact(ALICE_PHONE);

            Uri alicePhoneUri = makePhoneUri(ALICE_PHONE);
            assertTrue(mNotificationManager.matchesCallFilter(alicePhoneUri));
        } finally {
            mNotificationManager.setInterruptionFilter(origFilter);
            mNotificationManager.setNotificationPolicy(origPolicy);
            if (aliceUri != null) {
                // delete the contact
                deleteSingleContact(aliceUri);
            }
        }
    }

    public void testInterruptionFilterNoneInterceptsMessages() throws Exception {
        insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, true);
        insertSingleContact(BOB, BOB_PHONE, BOB_EMAIL, false);
        // Not Charlie

        mNotificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
        sendNotifications(MODE_URI, false, false);

        StatusBarNotification alice = mNotificationHelper.findPostedNotification(ALICE, 1,
                SEARCH_TYPE.POSTED);
        StatusBarNotification bob = mNotificationHelper.findPostedNotification(BOB, 2,
                SEARCH_TYPE.POSTED);
        StatusBarNotification charlie = mNotificationHelper.findPostedNotification(CHARLIE, 3,
                SEARCH_TYPE.POSTED);

        assertTrue(mListener.mIntercepted.get(alice.getKey()));
        assertTrue(mListener.mIntercepted.get(bob.getKey()));
        assertTrue(mListener.mIntercepted.get(charlie.getKey()));
    }

    public void testInterruptionFilterNoneInterceptsEventAlarmReminder() throws Exception {
        insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, true);
        insertSingleContact(BOB, BOB_PHONE, BOB_EMAIL, false);
        // Not Charlie

        mNotificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
        sendEventAlarmReminderNotifications(SEND_ALL);

        StatusBarNotification alice = mNotificationHelper.findPostedNotification(ALICE, 4,
                SEARCH_TYPE.POSTED);
        StatusBarNotification bob = mNotificationHelper.findPostedNotification(BOB, 5,
                SEARCH_TYPE.POSTED);
        StatusBarNotification charlie = mNotificationHelper.findPostedNotification(CHARLIE, 6,
                SEARCH_TYPE.POSTED);

        assertTrue(mListener.mIntercepted.get(alice.getKey()));
        assertTrue(mListener.mIntercepted.get(bob.getKey()));
        assertTrue(mListener.mIntercepted.get(charlie.getKey()));
    }

    public void testInterruptionFilterAllInterceptsNothing() throws Exception {
        insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, true);
        insertSingleContact(BOB, BOB_PHONE, BOB_EMAIL, false);
        // Not Charlie

        mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALL);
        sendNotifications(MODE_URI, false, false);
        sendEventAlarmReminderNotifications(SEND_ALL);
        sendAlarmOtherMediaNotifications(SEND_ALL);

        StatusBarNotification alice = mNotificationHelper.findPostedNotification(ALICE, 1,
                SEARCH_TYPE.POSTED);
        StatusBarNotification bob = mNotificationHelper.findPostedNotification(BOB, 2,
                SEARCH_TYPE.POSTED);
        StatusBarNotification charlie = mNotificationHelper.findPostedNotification(CHARLIE, 3,
                SEARCH_TYPE.POSTED);

        StatusBarNotification aliceEvent = mNotificationHelper.findPostedNotification(ALICE, 4,
                SEARCH_TYPE.POSTED);
        StatusBarNotification bobAlarm = mNotificationHelper.findPostedNotification(BOB, 5,
                SEARCH_TYPE.POSTED);
        StatusBarNotification charlieReminder = mNotificationHelper.findPostedNotification(CHARLIE, 6,
                SEARCH_TYPE.POSTED);

        StatusBarNotification aliceAlarm = mNotificationHelper.findPostedNotification(ALICE, 7,
                SEARCH_TYPE.POSTED);
        StatusBarNotification bobOther = mNotificationHelper.findPostedNotification(BOB, 8,
                SEARCH_TYPE.POSTED);
        StatusBarNotification charlieMedia = mNotificationHelper.findPostedNotification(CHARLIE, 9,
                SEARCH_TYPE.POSTED);

        assertFalse(mListener.mIntercepted.get(alice.getKey()));
        assertFalse(mListener.mIntercepted.get(bob.getKey()));
        assertFalse(mListener.mIntercepted.get(charlie.getKey()));
        assertFalse(mListener.mIntercepted.get(aliceEvent.getKey()));
        assertFalse(mListener.mIntercepted.get(bobAlarm.getKey()));
        assertFalse(mListener.mIntercepted.get(charlieReminder.getKey()));
        assertFalse(mListener.mIntercepted.get(aliceAlarm.getKey()));
        assertFalse(mListener.mIntercepted.get(bobOther.getKey()));
        assertFalse(mListener.mIntercepted.get(charlieMedia.getKey()));
    }

    public void testPriorityStarredMessages() throws Exception {
        insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, true);
        assertTrue(isStarred(lookupContact(ALICE_PHONE)));
        insertSingleContact(BOB, BOB_PHONE, BOB_EMAIL, false);
        // Not Charlie

        mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);
        NotificationManager.Policy policy = mNotificationManager.getNotificationPolicy();
        policy = new NotificationManager.Policy(
                NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES,
                policy.priorityCallSenders,
                NotificationManager.Policy.PRIORITY_SENDERS_STARRED);
        mNotificationManager.setNotificationPolicy(policy);
        sendNotifications(MODE_URI, false, false);

        StatusBarNotification alice = mNotificationHelper.findPostedNotification(ALICE, 1,
                SEARCH_TYPE.POSTED);
        StatusBarNotification bob = mNotificationHelper.findPostedNotification(BOB, 2,
                SEARCH_TYPE.POSTED);
        StatusBarNotification charlie = mNotificationHelper.findPostedNotification(CHARLIE, 3,
                SEARCH_TYPE.POSTED);

        // wait for contacts lookup in NMS to finish so we have the correct interception info
        boolean isAliceIntercepted = true;
        for (int i = 0; i < 6; i++) {
            isAliceIntercepted = mListener.mIntercepted.get(alice.getKey());
            if (!isAliceIntercepted) {
                break;
            }
            sleep();
        }
        assertFalse(isAliceIntercepted);
        assertTrue(mListener.mIntercepted.get(bob.getKey()));
        assertTrue(mListener.mIntercepted.get(charlie.getKey()));
    }

    public void testPriorityInterceptsAlarms() throws Exception {
        insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, true);
        insertSingleContact(BOB, BOB_PHONE, BOB_EMAIL, false);
        // Not Charlie

        mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);
        NotificationManager.Policy policy = mNotificationManager.getNotificationPolicy();
        policy = new NotificationManager.Policy(
                NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS,
                policy.priorityCallSenders,
                policy.priorityMessageSenders);
        mNotificationManager.setNotificationPolicy(policy);
        sendEventAlarmReminderNotifications(SEND_ALL);

        StatusBarNotification alice = mNotificationHelper.findPostedNotification(ALICE, 4,
                SEARCH_TYPE.POSTED);
        StatusBarNotification bob = mNotificationHelper.findPostedNotification(BOB, 5,
                SEARCH_TYPE.POSTED);
        StatusBarNotification charlie = mNotificationHelper.findPostedNotification(CHARLIE, 6,
                SEARCH_TYPE.POSTED);

        boolean isBobIntercepted = true;
        for (int i = 0; i < 6; i++) {
            isBobIntercepted = mListener.mIntercepted.get(bob.getKey());
            if (!isBobIntercepted) {
                break;
            }
            sleep();
        }
        assertFalse(isBobIntercepted);

        assertTrue(mListener.mIntercepted.get(alice.getKey()));
        assertTrue(mListener.mIntercepted.get(charlie.getKey()));
    }

    public void testPriorityInterceptsMediaSystemOther() throws Exception {
        insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, true);
        insertSingleContact(BOB, BOB_PHONE, BOB_EMAIL, false);
        // Not Charlie

        mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);
        NotificationManager.Policy policy = mNotificationManager.getNotificationPolicy();
        policy = new NotificationManager.Policy(
                NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA,
                policy.priorityCallSenders,
                policy.priorityMessageSenders);
        mNotificationManager.setNotificationPolicy(policy);
        sendAlarmOtherMediaNotifications(SEND_ALL);

        StatusBarNotification alice = mNotificationHelper.findPostedNotification(ALICE, 7,
                SEARCH_TYPE.POSTED);
        StatusBarNotification bob = mNotificationHelper.findPostedNotification(BOB, 8,
                SEARCH_TYPE.POSTED);
        StatusBarNotification charlie = mNotificationHelper.findPostedNotification(CHARLIE, 9,
                SEARCH_TYPE.POSTED);

        boolean isBobIntercepted = true;
        for (int i = 0; i < 6; i++) {
            isBobIntercepted = mListener.mIntercepted.get(bob.getKey());
            if (!isBobIntercepted) {
                break;
            }
            sleep();
        }
        assertFalse(isBobIntercepted);

        boolean isCharlieIntercepted = true;
        for (int i = 0; i < 6; i++) {
            isCharlieIntercepted = mListener.mIntercepted.get(charlie.getKey());
            if (!isCharlieIntercepted) {
                break;
            }
            sleep();
        }
        assertFalse(isCharlieIntercepted);

        assertTrue(mListener.mIntercepted.get(alice.getKey()));
    }

    @CddTest(requirements = {"2.2.3/3.8.4/H-1-1"})
    public void testContactAffinityByPhoneOrder() throws Exception {
        insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, true);
        insertSingleContact(BOB, BOB_PHONE, BOB_EMAIL, false);
        // Not Charlie

        mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALL);
        sendNotifications(MODE_PHONE, false, false);

        int rankA= 0, rankB = 0, rankC = 0;
        for (int i = 0; i < 6; i++) {
            List<String> orderedKeys = new ArrayList<>(
                    Arrays.asList(mListener.mRankingMap.getOrderedKeys()));
            rankA = findTagInKeys(ALICE, orderedKeys);
            rankB = findTagInKeys(BOB, orderedKeys);
            rankC = findTagInKeys(CHARLIE, orderedKeys);
            // ordered by contact affinity: A, B, C
            if (rankA < rankB && rankB < rankC) {
                // yay
                break;
            }
            sleep();
        }
        // ordered by contact affinity: A, B, C
        if (rankA < rankB && rankB < rankC) {
            // yay
        } else {
            fail("Notifications out of order. Actual order: Alice: " + rankA + " Bob: " + rankB
                    + " Charlie: " + rankC);
        }
    }

    @CddTest(requirements = {"2.2.3/3.8.4/H-1-1"})
    public void testContactUriByUriOrder() throws Exception {
        insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, true);
        insertSingleContact(BOB, BOB_PHONE, BOB_EMAIL, false);
        // Not Charlie

        mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALL);
        sendNotifications(MODE_URI, false, false);

        int rankA= 0, rankB = 0, rankC = 0;
        for (int i = 0; i < 6; i++) {
            List<String> orderedKeys = new ArrayList<>(
                    Arrays.asList(mListener.mRankingMap.getOrderedKeys()));
            rankA = findTagInKeys(ALICE, orderedKeys);
            rankB = findTagInKeys(BOB, orderedKeys);
            rankC = findTagInKeys(CHARLIE, orderedKeys);
            // ordered by contact affinity: A, B, C
            if (rankA < rankB && rankB < rankC) {
                // yay
                break;
            }
            sleep();
        }
        // ordered by contact affinity: A, B, C
        if (rankA < rankB && rankB < rankC) {
            // yay
        } else {
            fail("Notifications out of order. Actual order: Alice: " + rankA + " Bob: " + rankB
                    + " Charlie: " + rankC);
        }
    }

    @CddTest(requirements = {"2.2.3/3.8.4/H-1-1"})
    public void testContactUriByEmailOrder() throws Exception {
        insertSingleContact(ALICE, ALICE_PHONE, ALICE_EMAIL, true);
        insertSingleContact(BOB, BOB_PHONE, BOB_EMAIL, false);
        // Not Charlie

        mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALL);
        sendNotifications(MODE_EMAIL, false, false);

        int rankA= 0, rankB = 0, rankC = 0;
        for (int i = 0; i < 6; i++) {
            List<String> orderedKeys = new ArrayList<>(
                    Arrays.asList(mListener.mRankingMap.getOrderedKeys()));
            rankA = findTagInKeys(ALICE, orderedKeys);
            rankB = findTagInKeys(BOB, orderedKeys);
            rankC = findTagInKeys(CHARLIE, orderedKeys);
            // ordered by contact affinity: A, B, C
            if (rankA < rankB && rankB < rankC) {
                // yay
                break;
            }
            sleep();
        }
        // ordered by contact affinity: A, B, C
        if (rankA < rankB && rankB < rankC) {
            // yay
        } else {
            fail("Notifications out of order. Actual order: Alice: " + rankA + " Bob: " + rankB
                    + " Charlie: " + rankC);
        }
    }
}
