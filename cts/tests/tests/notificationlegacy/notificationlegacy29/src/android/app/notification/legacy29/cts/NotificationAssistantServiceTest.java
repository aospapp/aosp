/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.notification.legacy29.cts;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertNotNull;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.Telephony;
import android.service.notification.Adjustment;
import android.service.notification.NotificationAssistantService;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

@RunWith(AndroidJUnit4.class)
public class NotificationAssistantServiceTest {

    final String TAG = "NotAsstServiceTest";
    final String NOTIFICATION_CHANNEL_ID = "NotificationAssistantServiceTest";
    final int ICON_ID = android.R.drawable.sym_def_app_icon;
    final long SLEEP_TIME = 500; // milliseconds

    private TestNotificationAssistant mNotificationAssistantService;
    private TestNotificationListener mNotificationListenerService;
    private NotificationManager mNotificationManager;
    private ActivityManager mActivityManager;
    private Context mContext;
    private UiAutomation mUi;

    @Before
    public void setUp() throws IOException {
        mUi = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mContext = InstrumentationRegistry.getContext();
        mNotificationManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        mNotificationManager.createNotificationChannel(new NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "name", NotificationManager.IMPORTANCE_DEFAULT));
        mActivityManager = mContext.getSystemService(ActivityManager.class);
    }

    @After
    public void tearDown() throws IOException {
        if (mNotificationListenerService != null) mNotificationListenerService.resetData();
        if (!mActivityManager.isLowRamDevice()) {
            toggleListenerAccess(false);
            toggleAssistantAccess(false);
        }
        mUi.dropShellPermissionIdentity();
    }

    @Test
    public void testOnNotificationEnqueued() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        toggleListenerAccess(true);
        Thread.sleep(SLEEP_TIME);

        mUi.adoptShellPermissionIdentity("android.permission.STATUS_BAR_SERVICE");
        mNotificationManager.allowAssistantAdjustment(Adjustment.KEY_USER_SENTIMENT);
        mUi.dropShellPermissionIdentity();

        mNotificationListenerService = TestNotificationListener.getInstance();

        sendNotification(1, ICON_ID);
        StatusBarNotification sbn = getFirstNotificationFromPackage(TestNotificationListener.PKG);
        NotificationListenerService.Ranking out = new NotificationListenerService.Ranking();
        mNotificationListenerService.mRankingMap.getRanking(sbn.getKey(), out);

        // No modification because the Notification Assistant is not enabled
        assertEquals(NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL,
                out.getUserSentiment());
        mNotificationListenerService.resetData();

        toggleAssistantAccess(true);
        Thread.sleep(SLEEP_TIME); // wait for listener and assistant to be allowed
        mNotificationAssistantService = TestNotificationAssistant.getInstance();

        sendNotification(1, ICON_ID);
        sbn = getFirstNotificationFromPackage(TestNotificationListener.PKG);
        mNotificationListenerService.mRankingMap.getRanking(sbn.getKey(), out);

        // Assistant modifies notification
        assertEquals(NotificationListenerService.Ranking.USER_SENTIMENT_POSITIVE,
                out.getUserSentiment());
    }

    @Test
    public void testAdjustNotification_userSentimentKey() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        setUpListeners();

        mUi.adoptShellPermissionIdentity("android.permission.STATUS_BAR_SERVICE");
        mNotificationManager.allowAssistantAdjustment(Adjustment.KEY_USER_SENTIMENT);
        mUi.dropShellPermissionIdentity();

        sendNotification(1, ICON_ID);
        StatusBarNotification sbn = getFirstNotificationFromPackage(TestNotificationListener.PKG);
        NotificationListenerService.Ranking out = new NotificationListenerService.Ranking();
        mNotificationListenerService.mRankingMap.getRanking(sbn.getKey(), out);

        assertEquals(NotificationListenerService.Ranking.USER_SENTIMENT_POSITIVE,
                out.getUserSentiment());

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT,
                NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE);
        Adjustment adjustment = new Adjustment(sbn.getPackageName(), sbn.getKey(), signals, "",
                sbn.getUser());

        mNotificationAssistantService.adjustNotification(adjustment);
        Thread.sleep(SLEEP_TIME); // wait for adjustment to be processed

        mNotificationListenerService.mRankingMap.getRanking(sbn.getKey(), out);

        assertEquals(NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE,
                out.getUserSentiment());
    }

    @Test
    public void testAdjustNotification_importanceKey() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        setUpListeners();

        mUi.adoptShellPermissionIdentity("android.permission.STATUS_BAR_SERVICE");
        mNotificationManager.allowAssistantAdjustment(Adjustment.KEY_IMPORTANCE);
        mUi.dropShellPermissionIdentity();

        sendNotification(1, ICON_ID);
        StatusBarNotification sbn = getFirstNotificationFromPackage(TestNotificationListener.PKG);
        NotificationListenerService.Ranking out = new NotificationListenerService.Ranking();
        mNotificationListenerService.mRankingMap.getRanking(sbn.getKey(), out);

        int currentImportance = out.getImportance();
        int newImportance = currentImportance == NotificationManager.IMPORTANCE_DEFAULT
                ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT;

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_IMPORTANCE, newImportance);
        Adjustment adjustment = new Adjustment(sbn.getPackageName(), sbn.getKey(), signals, "",
                sbn.getUser());

        mNotificationAssistantService.adjustNotification(adjustment);
        Thread.sleep(SLEEP_TIME); // wait for adjustment to be processed

        mNotificationListenerService.mRankingMap.getRanking(sbn.getKey(), out);

        assertEquals(newImportance, out.getImportance());
    }

    @Test
    public void testAdjustNotification_smartActionKey() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        setUpListeners();

        mUi.adoptShellPermissionIdentity("android.permission.STATUS_BAR_SERVICE");
        mNotificationManager.allowAssistantAdjustment(Adjustment.KEY_CONTEXTUAL_ACTIONS);
        mUi.dropShellPermissionIdentity();

        PendingIntent sendIntent = PendingIntent.getActivity(mContext, 0,
                new Intent(Intent.ACTION_SEND), 0);
        Notification.Action sendAction = new Notification.Action.Builder(ICON_ID, "SEND",
                sendIntent).build();

        sendNotification(1, ICON_ID);
        StatusBarNotification sbn = getFirstNotificationFromPackage(TestNotificationListener.PKG);
        NotificationListenerService.Ranking out = new NotificationListenerService.Ranking();
        mNotificationListenerService.mRankingMap.getRanking(sbn.getKey(), out);

        List<Notification.Action> smartActions = out.getSmartActions();
        if (smartActions != null) {
            for (int i = 0; i < smartActions.size(); i++) {
                Notification.Action action = smartActions.get(i);
                assertNotEquals(sendIntent, action.actionIntent);
            }
        }

        ArrayList<Notification.Action> extraAction = new ArrayList<>();
        extraAction.add(sendAction);
        Bundle signals = new Bundle();
        signals.putParcelableArrayList(Adjustment.KEY_CONTEXTUAL_ACTIONS, extraAction);
        Adjustment adjustment = new Adjustment(sbn.getPackageName(), sbn.getKey(), signals, "",
                sbn.getUser());

        mNotificationAssistantService.adjustNotification(adjustment);
        Thread.sleep(SLEEP_TIME); //wait for adjustment to be processed

        mNotificationListenerService.mRankingMap.getRanking(sbn.getKey(), out);

        boolean actionFound = false;
        smartActions = out.getSmartActions();
        for (int i = 0; i < smartActions.size(); i++) {
            Notification.Action action = smartActions.get(i);
            actionFound = actionFound || action.actionIntent.equals(sendIntent);
        }
        assertTrue(actionFound);
    }

    @Test
    public void testAdjustNotification_smartReplyKey() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        setUpListeners();
        CharSequence smartReply = "Smart Reply!";

        mUi.adoptShellPermissionIdentity("android.permission.STATUS_BAR_SERVICE");
        mNotificationManager.allowAssistantAdjustment(Adjustment.KEY_TEXT_REPLIES);
        mUi.dropShellPermissionIdentity();

        sendNotification(1, ICON_ID);
        StatusBarNotification sbn = getFirstNotificationFromPackage(TestNotificationListener.PKG);
        NotificationListenerService.Ranking out = new NotificationListenerService.Ranking();
        mNotificationListenerService.mRankingMap.getRanking(sbn.getKey(), out);

        List<CharSequence> smartReplies = out.getSmartReplies();
        if (smartReplies != null) {
            for (int i = 0; i < smartReplies.size(); i++) {
                CharSequence reply = smartReplies.get(i);
                assertNotEquals(smartReply, reply);
            }
        }

        ArrayList<CharSequence> extraReply = new ArrayList<>();
        extraReply.add(smartReply);
        Bundle signals = new Bundle();
        signals.putCharSequenceArrayList(Adjustment.KEY_TEXT_REPLIES, extraReply);
        Adjustment adjustment = new Adjustment(sbn.getPackageName(), sbn.getKey(), signals, "",
                sbn.getUser());

        mNotificationAssistantService.adjustNotification(adjustment);
        Thread.sleep(SLEEP_TIME); //wait for adjustment to be processed

        mNotificationListenerService.mRankingMap.getRanking(sbn.getKey(), out);

        boolean replyFound = false;
        smartReplies = out.getSmartReplies();
        for (int i = 0; i < smartReplies.size(); i++) {
            CharSequence reply = smartReplies.get(i);
            replyFound = replyFound || reply.equals(smartReply);
        }
        assertTrue(replyFound);
    }

    @Test
    public void testAdjustNotification_importanceKey_notAllowed() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        setUpListeners();

        mUi.adoptShellPermissionIdentity("android.permission.STATUS_BAR_SERVICE");
        mNotificationManager.disallowAssistantAdjustment(Adjustment.KEY_IMPORTANCE);
        mUi.dropShellPermissionIdentity();

        sendNotification(1, ICON_ID);
        StatusBarNotification sbn = getFirstNotificationFromPackage(
                TestNotificationListener.PKG);
        NotificationListenerService.Ranking out = new NotificationListenerService.Ranking();
        mNotificationListenerService.mRankingMap.getRanking(sbn.getKey(), out);

        int currentImportance = out.getImportance();
        int newImportance = currentImportance == NotificationManager.IMPORTANCE_DEFAULT
                ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT;

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_IMPORTANCE, newImportance);
        Adjustment adjustment = new Adjustment(sbn.getPackageName(), sbn.getKey(), signals, "",
                sbn.getUser());

        mNotificationAssistantService.adjustNotification(adjustment);
        Thread.sleep(SLEEP_TIME); // wait for adjustment to be processed

        mNotificationListenerService.mRankingMap.getRanking(sbn.getKey(), out);

        assertEquals(currentImportance, out.getImportance());

    }

    @Test
    public void testGetAllowedAssistantCapabilities_permission() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        toggleAssistantAccess(false);

        try {
            mNotificationManager.getAllowedAssistantAdjustments();
            fail(" Non assistants cannot call this method");
        } catch (SecurityException e) {
            //pass
        }
    }

    @Test
    public void testGetAllowedAssistantCapabilities() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        toggleAssistantAccess(true);
        Thread.sleep(SLEEP_TIME); // wait for assistant to be allowed
        mNotificationAssistantService = TestNotificationAssistant.getInstance();
        mNotificationAssistantService.onAllowedAdjustmentsChanged();
        assertNotNull(mNotificationAssistantService.currentCapabilities);

        mUi.adoptShellPermissionIdentity("android.permission.STATUS_BAR_SERVICE");
        mNotificationManager.allowAssistantAdjustment(Adjustment.KEY_SNOOZE_CRITERIA);

        Thread.sleep(SLEEP_TIME);
        assertTrue(mNotificationAssistantService.currentCapabilities.contains(
                Adjustment.KEY_SNOOZE_CRITERIA));

        mNotificationManager.disallowAssistantAdjustment(Adjustment.KEY_SNOOZE_CRITERIA);
        Thread.sleep(SLEEP_TIME);
        assertFalse(mNotificationAssistantService.currentCapabilities.contains(
                Adjustment.KEY_SNOOZE_CRITERIA));

        // just in case KEY_SNOOZE_CRITERIA was included in the original set, test adding again
        mNotificationManager.allowAssistantAdjustment(Adjustment.KEY_SNOOZE_CRITERIA);
        Thread.sleep(SLEEP_TIME);
        assertTrue(mNotificationAssistantService.currentCapabilities.contains(
                Adjustment.KEY_SNOOZE_CRITERIA));

        mUi.dropShellPermissionIdentity();
    }

    @Test
    public void testOnActionInvoked_methodExists() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        setUpListeners();
        final Intent intent = new Intent(Intent.ACTION_MAIN, Telephony.Threads.CONTENT_URI);

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.setAction(Intent.ACTION_MAIN);

        final PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, intent, 0);
        Notification.Action action = new Notification.Action.Builder(null, "",
                pendingIntent).build();
        // This method has to exist and the call cannot fail
        mNotificationAssistantService.onActionInvoked("", action,
                NotificationAssistantService.SOURCE_FROM_APP);
    }

    @Test
    public void testOnNotificationDirectReplied_methodExists() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        setUpListeners();
        // This method has to exist and the call cannot fail
        mNotificationAssistantService.onNotificationDirectReplied("");
    }

    @Test
    public void testOnNotificationExpansionChanged_methodExists() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        setUpListeners();
        // This method has to exist and the call cannot fail
        mNotificationAssistantService.onNotificationExpansionChanged("", true, true);
    }

    @Test
    public void testOnNotificationsSeen_methodExists() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        setUpListeners();
        // This method has to exist and the call cannot fail
        mNotificationAssistantService.onNotificationsSeen(new ArrayList<>());
    }

    @Test
    public void testOnSuggestedReplySent_methodExists() throws Exception {
        if (mActivityManager.isLowRamDevice()) {
            return;
        }
        setUpListeners();
        // This method has to exist and the call cannot fail
        mNotificationAssistantService.onSuggestedReplySent("", "",
                NotificationAssistantService.SOURCE_FROM_APP);
    }

    private StatusBarNotification getFirstNotificationFromPackage(String PKG)
            throws InterruptedException {
        StatusBarNotification sbn = mNotificationListenerService.mPosted.poll(SLEEP_TIME,
                TimeUnit.MILLISECONDS);
        assertNotNull(sbn);
        while (!sbn.getPackageName().equals(PKG)) {
            sbn = mNotificationListenerService.mPosted.poll(SLEEP_TIME, TimeUnit.MILLISECONDS);
        }
        assertNotNull(sbn);
        return sbn;
    }

    private void setUpListeners() throws Exception {
        toggleListenerAccess(true);
        toggleAssistantAccess(true);
        Thread.sleep(2 * SLEEP_TIME); // wait for listener and assistant to be allowed

        mNotificationListenerService = TestNotificationListener.getInstance();
        mNotificationAssistantService = TestNotificationAssistant.getInstance();

        assertNotNull(mNotificationListenerService);
        assertNotNull(mNotificationAssistantService);
    }

    private void sendNotification(final int id, final int icon) throws Exception {
        sendNotification(id, null, icon);
    }

    private void sendNotification(final int id, String groupKey, final int icon) throws Exception {
        final Intent intent = new Intent(Intent.ACTION_MAIN, Telephony.Threads.CONTENT_URI);

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
    }

    private void toggleListenerAccess(boolean on) throws IOException {

        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        String componentName = TestNotificationListener.getId();

        String command = " cmd notification " + (on ? "allow_listener " : "disallow_listener ")
                + componentName;

        runCommand(command, instrumentation);

        final ComponentName listenerComponent = TestNotificationListener.getComponentName();
        final NotificationManager nm = mContext.getSystemService(NotificationManager.class);
        Assert.assertTrue(listenerComponent + " has not been " + (on ? "allowed" : "disallowed"),
                nm.isNotificationListenerAccessGranted(listenerComponent) == on);
    }

    private void toggleAssistantAccess(boolean on) {
        final ComponentName assistantComponent = TestNotificationAssistant.getComponentName();

        mUi.adoptShellPermissionIdentity("android.permission.STATUS_BAR_SERVICE",
                "android.permission.REQUEST_NOTIFICATION_ASSISTANT_SERVICE");
        mNotificationManager.setNotificationAssistantAccessGranted(assistantComponent, on);

        assertTrue(assistantComponent + " has not been " + (on ? "allowed" : "disallowed"),
                mNotificationManager.isNotificationAssistantAccessGranted(assistantComponent)
                        == on);
        if (on) {
            assertEquals(assistantComponent,
                    mNotificationManager.getAllowedNotificationAssistant());
        } else {
            assertNotEquals(assistantComponent,
                    mNotificationManager.getAllowedNotificationAssistant());
        }

        mUi.dropShellPermissionIdentity();
    }

    private void runCommand(String command, Instrumentation instrumentation) throws IOException {
        UiAutomation uiAutomation = instrumentation.getUiAutomation();
        // Execute command
        try (ParcelFileDescriptor fd = uiAutomation.executeShellCommand(command)) {
            assertNotNull("Failed to execute shell command: " + command, fd);
            // Wait for the command to finish by reading until EOF
            try (InputStream in = new FileInputStream(fd.getFileDescriptor())) {
                byte[] buffer = new byte[4096];
                while (in.read(buffer) > 0) {
                }
            } catch (IOException e) {
                throw new IOException("Could not read stdout of command: " + command, e);
            }
        }
    }
}