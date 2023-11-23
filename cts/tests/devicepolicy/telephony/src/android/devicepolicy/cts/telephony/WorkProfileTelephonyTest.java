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

package android.devicepolicy.cts.telephony;

import static android.Manifest.permission.CALL_PHONE;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.READ_CALL_LOG;
import static android.Manifest.permission.READ_PHONE_NUMBERS;
import static android.Manifest.permission.READ_PHONE_STATE;
import static android.Manifest.permission.READ_SMS;
import static android.Manifest.permission.WRITE_CALL_LOG;
import static android.app.role.RoleManager.MANAGE_HOLDERS_FLAG_DONT_KILL_APP;
import static android.app.role.RoleManager.ROLE_SMS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.FEATURE_TELEPHONY;

import static com.android.bedstead.harrier.UserType.WORK_PROFILE;
import static com.android.bedstead.nene.appops.CommonAppOps.OPSTR_CALL_PHONE;
import static com.android.bedstead.nene.types.OptionalBoolean.TRUE;
import static com.android.eventlib.truth.EventLogsSubject.assertThat;
import static com.android.queryable.queries.ActivityQuery.activity;
import static com.android.queryable.queries.IntentFilterQuery.intentFilter;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.admin.ManagedSubscriptionsPolicy;
import android.app.admin.RemoteDevicePolicyManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.CallLog;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.Call;
import android.telecom.InCallService;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.activitycontext.ActivityContext;
import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.EnsureGlobalSettingSet;
import com.android.bedstead.harrier.annotations.EnsureHasWorkProfile;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.RequireFeature;
import com.android.bedstead.harrier.annotations.RequireRunOnInitialUser;
import com.android.bedstead.harrier.annotations.RequireRunOnWorkProfile;
import com.android.bedstead.nene.DefaultDialerContext;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.packages.ComponentReference;
import com.android.bedstead.nene.permissions.PermissionContext;
import com.android.bedstead.nene.users.UserReference;
import com.android.bedstead.nene.utils.Poll;
import com.android.bedstead.testapp.TestApp;
import com.android.bedstead.testapp.TestAppActivityReference;
import com.android.bedstead.testapp.TestAppInstance;
import com.android.bedstead.testapp.TestInCallService;
import com.android.compatibility.common.util.CddTest;
import com.android.compatibility.common.util.SystemUtil;
import com.android.eventlib.events.CustomEvent;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@RequireFeature(FEATURE_TELEPHONY)
@RunWith(BedsteadJUnit4.class)
public final class WorkProfileTelephonyTest {

    @ClassRule @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final TestApp sSmsApp =
            sDeviceState.testApps().query().whereActivities().contains(
                    activity().where().intentFilters().contains(
                            intentFilter().where().actions().contains(Intent.ACTION_SENDTO))).get();
    private static final TestApp sDialerApp =
            sDeviceState.testApps().query().whereActivities().contains(
                    activity().where().intentFilters().contains(
                            intentFilter().where().actions().contains(Intent.ACTION_DIAL))).get();

    private static final ComponentReference INTENT_FORWARDER_COMPONENT =
            TestApis.packages().component(new ComponentName(
                    "android", "com.android.internal.app.IntentForwarderActivity"));
    private static final String SMS_SENT_INTENT_ACTION = "TEST_SMS_SENT_ACTION";
    private static final Context sContext = TestApis.context().instrumentedContext();
    private static final String ENABLE_WORK_PROFILE_TELEPHONY_FLAG =
            "enable_work_profile_telephony";
    private static final String ENABLE_SWITCH_TO_MANAGED_PROFILE_FLAG =
            "enable_switch_to_managed_profile_dialog";

    private RoleManager mRoleManager;
    private TelephonyManager mTelephonyManager;
    private String mDestinationNumber;

    @Before
    public void setUp() {
        mTelephonyManager = sContext.getSystemService(TelephonyManager.class);
        mRoleManager = sContext.getSystemService(RoleManager.class);

        try (PermissionContext p = TestApis.permissions().withPermission(READ_PHONE_NUMBERS)) {
            SubscriptionManager subscriptionManager = sContext.getSystemService(
                    SubscriptionManager.class);
            mDestinationNumber = PhoneNumberUtils.formatNumberToE164(
                    subscriptionManager.getPhoneNumber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID),
                    mTelephonyManager.getSimCountryIso());
        }
    }

    @EnsureGlobalSettingSet(key =
            Settings.Global.ALLOW_WORK_PROFILE_TELEPHONY_FOR_NON_DPM_ROLE_HOLDERS, value = "1")
    @RequireRunOnWorkProfile(isOrganizationOwned = true)
    @Postsubmit(reason = "new test")
    @Test
    @CddTest(requirements = {"7.4.1.4/C-3-1"})
    public void sendTextMessage_fromWorkProfile_allManagedSubscriptions_smsSentSuccessfully() {
        assumeSmsCapableDevice();
        assertValidSimCardPresent();
        String previousDefaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(sContext);
        RemoteDevicePolicyManager dpm = sDeviceState.profileOwner(
                WORK_PROFILE).devicePolicyManager();
        UserReference workProfileUser = sDeviceState.workProfile();
        try (TestAppInstance smsApp = sSmsApp.install(workProfileUser)) {
            dpm.setManagedSubscriptionsPolicy(new ManagedSubscriptionsPolicy(
                    ManagedSubscriptionsPolicy.TYPE_ALL_MANAGED_SUBSCRIPTIONS));
            dpm.setDefaultSmsApplication(sDeviceState.profileOwner(WORK_PROFILE).componentName(),
                    smsApp.packageName());
            Intent sentIntent = new Intent(SMS_SENT_INTENT_ACTION).setPackage(smsApp.packageName());
            PendingIntent sentPendingIntent = PendingIntent.getBroadcast(
                    TestApis.context().instrumentedContext(), 0, sentIntent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE_UNAUDITED);
            IntentFilter sentIntentFilter = new IntentFilter(SMS_SENT_INTENT_ACTION);
            smsApp.registerReceiver(sentIntentFilter, Context.RECEIVER_EXPORTED_UNAUDITED);

            smsApp.smsManager().sendTextMessage(mDestinationNumber, null, "test", sentPendingIntent,
                    null);

            assertThat(smsApp.events().broadcastReceived().whereIntent().action().isEqualTo(
                    SMS_SENT_INTENT_ACTION).whereResultCode().isEqualTo(
                    Activity.RESULT_OK)).eventOccurred();
        } finally {
            dpm.setDefaultSmsApplication(sDeviceState.profileOwner(WORK_PROFILE).componentName(),
                    previousDefaultSmsPackage);
            sDeviceState.profileOwner(
                    WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                    new ManagedSubscriptionsPolicy(
                            ManagedSubscriptionsPolicy.TYPE_ALL_PERSONAL_SUBSCRIPTIONS));
        }
    }

    @EnsureGlobalSettingSet(key =
            Settings.Global.ALLOW_WORK_PROFILE_TELEPHONY_FOR_NON_DPM_ROLE_HOLDERS, value = "1")
    @EnsureHasWorkProfile(isOrganizationOwned = true)
    @RequireRunOnInitialUser
    @Postsubmit(reason = "new test")
    @Test
    @CddTest(requirements = {"7.4.1.4/C-1-1", "7.4.1.4/C-3-2"})
    public void sendTextMessage_fromPersonalProfile_allManagedSubscriptions_errorUserNotAllowed()
            throws ExecutionException, InterruptedException {
        assumeSmsCapableDevice();
        assertValidSimCardPresent();
        String previousDefaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(sContext);
        RemoteDevicePolicyManager dpm = sDeviceState.profileOwner(
                WORK_PROFILE).devicePolicyManager();
        UserReference primaryUser = sDeviceState.primaryUser();
        try (TestAppInstance smsApp = sSmsApp.install(primaryUser)) {
            setPackageAsSmsRoleHolderForUser(smsApp.packageName(), primaryUser.userHandle());
            dpm.setManagedSubscriptionsPolicy(new ManagedSubscriptionsPolicy(
                    ManagedSubscriptionsPolicy.TYPE_ALL_MANAGED_SUBSCRIPTIONS));
            Intent sentIntent = new Intent(SMS_SENT_INTENT_ACTION).setPackage(smsApp.packageName());
            PendingIntent sentPendingIntent = PendingIntent.getBroadcast(
                    TestApis.context().instrumentedContext(), 1, sentIntent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE_UNAUDITED);
            smsApp.registerReceiver(new IntentFilter(SMS_SENT_INTENT_ACTION),
                    Context.RECEIVER_EXPORTED_UNAUDITED);
            TestAppActivityReference activityReference =
                    smsApp.activities().query().whereActivity().exported().isTrue().get();
            // Launch an activity here to bring the default sms app to foreground, we only show the
            // switch to managed profile dialog for sms, when sms app is foreground.
            ActivityContext.runWithContext(activity -> {
                Intent intent = new Intent().addFlags(FLAG_ACTIVITY_NEW_TASK).setComponent(
                        activityReference.component().componentName());
                activity.startActivity(intent, new Bundle());
            });

            smsApp.smsManager().sendTextMessage(mDestinationNumber, null, "test", sentPendingIntent,
                    null);

            assertThat(smsApp.events().broadcastReceived().whereIntent().action().isEqualTo(
                    SMS_SENT_INTENT_ACTION).whereResultCode().isEqualTo(
                    SmsManager.RESULT_USER_NOT_ALLOWED)).eventOccurred();
            Poll.forValue("Foreground activity", () -> TestApis.activities().foregroundActivity())
                    .toBeEqualTo(INTENT_FORWARDER_COMPONENT).errorOnFail().await();
        } finally {
            sDeviceState.profileOwner(
                    WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                    new ManagedSubscriptionsPolicy(
                            ManagedSubscriptionsPolicy.TYPE_ALL_PERSONAL_SUBSCRIPTIONS));
            setPackageAsSmsRoleHolderForUser(previousDefaultSmsPackage, primaryUser.userHandle());
        }
    }

    @EnsureGlobalSettingSet(key =
            Settings.Global.ALLOW_WORK_PROFILE_TELEPHONY_FOR_NON_DPM_ROLE_HOLDERS, value = "1")
    @EnsureHasWorkProfile(isOrganizationOwned = true)
    @RequireRunOnInitialUser
    @Postsubmit(reason = "new test")
    @Test
    @CddTest(requirements = {"7.4.1.4/C-3-1"})
    public void allManagedSubscriptions_accessWorkMessageFromPersonalProfile_fails() {
        assumeSmsCapableDevice();
        assertValidSimCardPresent();
        String previousDefaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(sContext);
        RemoteDevicePolicyManager dpm = sDeviceState.profileOwner(
                WORK_PROFILE).devicePolicyManager();
        UserReference workProfileUser = sDeviceState.workProfile();
        try (TestAppInstance smsApp = sSmsApp.install(workProfileUser)) {
            dpm.setManagedSubscriptionsPolicy(new ManagedSubscriptionsPolicy(
                    ManagedSubscriptionsPolicy.TYPE_ALL_MANAGED_SUBSCRIPTIONS));
            dpm.setDefaultSmsApplication(sDeviceState.profileOwner(WORK_PROFILE).componentName(),
                    smsApp.packageName());
            ContentValues smsValues = new ContentValues();
            smsValues.put(Telephony.Sms.ADDRESS, mDestinationNumber);
            smsValues.put(Telephony.Sms.BODY, "This is a test message.");
            Uri insertedSmsUri = null;
            try {
                insertedSmsUri = smsApp.context().getContentResolver().insert(
                        Telephony.Sms.CONTENT_URI, smsValues);

                Cursor cursor = null;
                try (PermissionContext p = TestApis.permissions().withPermission(READ_SMS)) {
                    cursor = TestApis.context().instrumentedContext().getContentResolver().query(
                            insertedSmsUri, null, null, null, null);
                }

                assertThat(cursor).isNotNull();
                assertThat(cursor.getCount()).isEqualTo(0);
            } finally {
                if (insertedSmsUri != null) {
                    smsApp.context().getContentResolver().delete(insertedSmsUri, null, null);
                }
            }
        } finally {
            dpm.setDefaultSmsApplication(sDeviceState.profileOwner(WORK_PROFILE).componentName(),
                    previousDefaultSmsPackage);
            sDeviceState.profileOwner(
                    WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                    new ManagedSubscriptionsPolicy(
                            ManagedSubscriptionsPolicy.TYPE_ALL_PERSONAL_SUBSCRIPTIONS));
        }
    }

    @EnsureGlobalSettingSet(key =
            Settings.Global.ALLOW_WORK_PROFILE_TELEPHONY_FOR_NON_DPM_ROLE_HOLDERS, value = "1")
    @RequireRunOnWorkProfile(isOrganizationOwned = true)
    @Postsubmit(reason = "new test")
    @Test
    @CddTest(requirements = {"7.4.1.4/C-3-1"})
    public void allManagedSubscriptions_accessWorkMessageFromWorkProfile_works() {
        assumeSmsCapableDevice();
        String previousDefaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(sContext);
        RemoteDevicePolicyManager dpm = sDeviceState.profileOwner(
                WORK_PROFILE).devicePolicyManager();
        UserReference workProfileUser = sDeviceState.workProfile();
        try (TestAppInstance smsApp = sSmsApp.install(workProfileUser)) {
            dpm.setManagedSubscriptionsPolicy(new ManagedSubscriptionsPolicy(
                    ManagedSubscriptionsPolicy.TYPE_ALL_MANAGED_SUBSCRIPTIONS));
            dpm.setDefaultSmsApplication(sDeviceState.profileOwner(WORK_PROFILE).componentName(),
                    smsApp.packageName());
            String insertMessageBody =
                    "This is a test message with timestamp : " + System.currentTimeMillis();
            ContentValues smsValues = new ContentValues();
            smsValues.put(Telephony.Sms.ADDRESS, mDestinationNumber);
            smsValues.put(Telephony.Sms.BODY, insertMessageBody);
            Uri insertedSmsUri = null;
            try {
                insertedSmsUri = smsApp.context().getContentResolver().insert(
                        Telephony.Sms.CONTENT_URI, smsValues);

                Cursor cursor = null;
                try (PermissionContext p = TestApis.permissions().withPermission(READ_SMS)) {
                    cursor = TestApis.context().instrumentedContext().getContentResolver().query(
                            insertedSmsUri, null, null, null, null);
                }

                assertThat(cursor).isNotNull();
                assertThat(cursor.getCount()).isNotEqualTo(0);
                cursor.moveToFirst();
                String actualSmsBody = cursor.getString(cursor.getColumnIndex(Telephony.Sms.BODY));
                assertThat(actualSmsBody).isEqualTo(insertMessageBody);
            } finally {
                if (insertedSmsUri != null) {
                    smsApp.context().getContentResolver().delete(insertedSmsUri, null, null);
                }
            }
        } finally {
            dpm.setDefaultSmsApplication(sDeviceState.profileOwner(WORK_PROFILE).componentName(),
                    previousDefaultSmsPackage);
            sDeviceState.profileOwner(
                    WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                    new ManagedSubscriptionsPolicy(
                            ManagedSubscriptionsPolicy.TYPE_ALL_PERSONAL_SUBSCRIPTIONS));
        }
    }

    @EnsureGlobalSettingSet(key =
            Settings.Global.ALLOW_WORK_PROFILE_TELEPHONY_FOR_NON_DPM_ROLE_HOLDERS, value = "1")
    @EnsureHasWorkProfile(isOrganizationOwned = true)
    @Postsubmit(reason = "new test")
    @Test
    public void placeCall_fromWorkProfile_allManagedSubscriptions_works() throws Exception {
        assumeCallCapableDevice();
        assertValidSimCardPresent();
        sDeviceState.profileOwner(WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                new ManagedSubscriptionsPolicy(
                        ManagedSubscriptionsPolicy.TYPE_ALL_MANAGED_SUBSCRIPTIONS));
        UserReference workProfileUser = sDeviceState.workProfile();
        try (TestAppInstance dialerApp = sDialerApp.install(workProfileUser);
             DefaultDialerContext dc = TestApis.telecom().setDefaultDialerForAllUsers(
                     dialerApp.packageName());
             PermissionContext p = dialerApp.permissions().withPermission(CALL_PHONE).withAppOp(
                     OPSTR_CALL_PHONE)) {

            dialerApp.telecomManager().placeCall(Uri.fromParts("tel", mDestinationNumber, null),
                    null);
            customEvents(dialerApp.packageName(), dialerApp.user()).whereTag().isEqualTo(
                    TestInCallService.TAG).whereData().isEqualTo(
                    "onStateChanged:" + Call.STATE_DISCONNECTED).poll();

            assertThat(dialerApp.events().serviceBound().whereIntent().action().isEqualTo(
                    InCallService.SERVICE_INTERFACE)).eventOccurred();
        } finally {
            sDeviceState.profileOwner(
                    WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                    new ManagedSubscriptionsPolicy(
                            ManagedSubscriptionsPolicy.TYPE_ALL_PERSONAL_SUBSCRIPTIONS));
        }
    }

    @EnsureGlobalSettingSet(key =
            Settings.Global.ALLOW_WORK_PROFILE_TELEPHONY_FOR_NON_DPM_ROLE_HOLDERS, value = "1")
    @EnsureHasWorkProfile(isOrganizationOwned = true)
    @Postsubmit(reason = "new test")
    @Test
    @CddTest(requirements = {"7.4.1.4/C-1-1", "7.4.1.4/C-3-2"})
    public void placeCall_fromPersonalProfile_allManagedSubscriptions_fails() throws Exception {
        assumeCallCapableDevice();
        assertValidSimCardPresent();
        sDeviceState.profileOwner(WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                new ManagedSubscriptionsPolicy(
                        ManagedSubscriptionsPolicy.TYPE_ALL_MANAGED_SUBSCRIPTIONS));
        String previousDefaultDialerPackage = getDefaultDialerPackage();
        UserReference primaryUser = sDeviceState.primaryUser();
        try (TestAppInstance dialerApp = sDialerApp.install(primaryUser);
             PermissionContext p = dialerApp.permissions().withPermission(CALL_PHONE).withAppOp(
                     OPSTR_CALL_PHONE);
             DefaultDialerContext dc = TestApis.telecom().setDefaultDialerForAllUsers(
                     dialerApp.packageName())) {

            dialerApp.telecomManager().placeCall(Uri.fromParts("tel", mDestinationNumber, null),
                    null);

            Poll.forValue("Foreground activity",
                    () -> TestApis.activities().foregroundActivity()).toBeEqualTo(
                    INTENT_FORWARDER_COMPONENT).errorOnFail().await();
        } finally {
            sDeviceState.profileOwner(
                    WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                    new ManagedSubscriptionsPolicy(
                            ManagedSubscriptionsPolicy.TYPE_ALL_PERSONAL_SUBSCRIPTIONS));
        }
    }

    @EnsureGlobalSettingSet(key =
            Settings.Global.ALLOW_WORK_PROFILE_TELEPHONY_FOR_NON_DPM_ROLE_HOLDERS, value = "1")
    @EnsureHasWorkProfile(isOrganizationOwned = true)
    @Postsubmit(reason = "new test")
    @Test
    @CddTest(requirements = {"7.4.1.4/C-3-3"})
    public void getCallCapablePhoneAccounts_fromWorkProfile_allManagedSubscriptions_notEmpty()
            throws Exception {
        assumeCallCapableDevice();
        assertValidSimCardPresent();
        sDeviceState.profileOwner(WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                new ManagedSubscriptionsPolicy(
                        ManagedSubscriptionsPolicy.TYPE_ALL_MANAGED_SUBSCRIPTIONS));
        UserReference workProfileUser = sDeviceState.workProfile();
        try (TestAppInstance dialerApp = sDialerApp.install(workProfileUser);
             PermissionContext p = dialerApp.permissions().withPermission(READ_PHONE_STATE);
             DefaultDialerContext dc = TestApis.telecom().setDefaultDialerForAllUsers(
                     dialerApp.packageName())) {

            List<PhoneAccountHandle> callCapableAccounts =
                    dialerApp.telecomManager().getCallCapablePhoneAccounts();

            assertThat(callCapableAccounts).isNotEmpty();
        } finally {
            sDeviceState.profileOwner(
                    WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                    new ManagedSubscriptionsPolicy(
                            ManagedSubscriptionsPolicy.TYPE_ALL_PERSONAL_SUBSCRIPTIONS));
        }
    }

    @EnsureGlobalSettingSet(key =
            Settings.Global.ALLOW_WORK_PROFILE_TELEPHONY_FOR_NON_DPM_ROLE_HOLDERS, value = "1")
    @EnsureHasWorkProfile(isOrganizationOwned = true)
    @Postsubmit(reason = "new test")
    @Test
    @CddTest(requirements = {"7.4.1.4/C-3-3"})
    public void getCallCapablePhoneAccounts_fromPersonalProfile_allManagedSubscriptions_emptyList()
            throws Exception {
        assumeCallCapableDevice();
        assertValidSimCardPresent();
        sDeviceState.profileOwner(WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                new ManagedSubscriptionsPolicy(
                        ManagedSubscriptionsPolicy.TYPE_ALL_MANAGED_SUBSCRIPTIONS));
        UserReference primaryUser = sDeviceState.primaryUser();
        try (TestAppInstance dialerApp = sDialerApp.install(primaryUser);
             PermissionContext p = dialerApp.permissions().withPermission(READ_PHONE_STATE);
             DefaultDialerContext dc = TestApis.telecom().setDefaultDialerForAllUsers(
                     dialerApp.packageName())) {

            List<PhoneAccountHandle> callCapableAccounts =
                    dialerApp.telecomManager().getCallCapablePhoneAccounts();

            assertThat(callCapableAccounts).isEmpty();
        } finally {
            sDeviceState.profileOwner(
                    WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                    new ManagedSubscriptionsPolicy(
                            ManagedSubscriptionsPolicy.TYPE_ALL_PERSONAL_SUBSCRIPTIONS));
        }
    }

    @EnsureGlobalSettingSet(key =
            Settings.Global.ALLOW_WORK_PROFILE_TELEPHONY_FOR_NON_DPM_ROLE_HOLDERS, value = "1")
    @RequireRunOnWorkProfile(isOrganizationOwned = true)
    @Postsubmit(reason = "new test")
    @Test
    @CddTest(requirements = {"7.4.1.4/C-2-1"})
    public void allManagedSubscriptions_accessWorkCallLogFromWorkProfile_works() throws Exception {
        assumeCallCapableDevice();
        assertValidSimCardPresent();
        sDeviceState.profileOwner(WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                new ManagedSubscriptionsPolicy(
                        ManagedSubscriptionsPolicy.TYPE_ALL_MANAGED_SUBSCRIPTIONS));
        try (TestAppInstance dialerApp = sDialerApp.install(sDeviceState.workProfile());
             DefaultDialerContext dc = TestApis.telecom().setDefaultDialerForAllUsers(
                     dialerApp.packageName());
             PermissionContext p = dialerApp.permissions().withPermission(CALL_PHONE).withAppOp(
                     OPSTR_CALL_PHONE)) {
            // This will create a call log in work profile
            dialerApp.telecomManager().placeCall(Uri.fromParts("tel", mDestinationNumber, null),
                    null);
            customEvents(dialerApp.packageName(), dialerApp.user()).whereTag().isEqualTo(
                    TestInCallService.TAG).whereData().isEqualTo(
                    "onStateChanged:" + Call.STATE_DISCONNECTED).poll();

            try (PermissionContext pc = TestApis.permissions().withPermission(READ_CALL_LOG)) {
                Poll.forValue(() -> numCallLogs()).timeout(Duration.ofSeconds(10)).toNotBeEqualTo(
                        0).errorOnFail().await();
            }
        } finally {
            try (PermissionContext pc = TestApis.permissions().withPermission(WRITE_CALL_LOG)) {
                TestApis.context().instrumentedContext().getContentResolver().delete(
                        CallLog.Calls.CONTENT_URI, null, null);
            }
            sDeviceState.profileOwner(
                    WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                    new ManagedSubscriptionsPolicy(
                            ManagedSubscriptionsPolicy.TYPE_ALL_PERSONAL_SUBSCRIPTIONS));
        }
    }

    @EnsureGlobalSettingSet(key =
            Settings.Global.ALLOW_WORK_PROFILE_TELEPHONY_FOR_NON_DPM_ROLE_HOLDERS, value = "1")
    @EnsureHasWorkProfile(isOrganizationOwned = true, installInstrumentedApp = TRUE)
    @RequireRunOnInitialUser
    @Postsubmit(reason = "new test")
    @Test
    @CddTest(requirements = {"7.4.1.4/C-2-1"})
    public void allManagedSubscriptions_accessWorkCallLogFromPersonalProfile_fails()
            throws Exception {
        assumeCallCapableDevice();
        assertValidSimCardPresent();
        sDeviceState.profileOwner(WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                new ManagedSubscriptionsPolicy(
                        ManagedSubscriptionsPolicy.TYPE_ALL_MANAGED_SUBSCRIPTIONS));
        try (TestAppInstance dialerApp = sDialerApp.install(sDeviceState.workProfile());
             DefaultDialerContext dc = TestApis.telecom().setDefaultDialerForAllUsers(
                     dialerApp.packageName());
             PermissionContext p = dialerApp.permissions().withPermission(CALL_PHONE).withAppOp(
                     OPSTR_CALL_PHONE)) {
            // This will create a call log in work profile
            dialerApp.telecomManager().placeCall(Uri.fromParts("tel", mDestinationNumber, null),
                    null);
            customEvents(dialerApp.packageName(), dialerApp.user()).whereTag().isEqualTo(
                    TestInCallService.TAG).whereData().isEqualTo(
                    "onStateChanged:" + Call.STATE_DISCONNECTED).poll();

            try (PermissionContext pc = TestApis.permissions().withPermission(READ_CALL_LOG)) {
                Poll.forValue(() -> numCallLogs()).timeout(Duration.ofSeconds(10)).toNotBeEqualTo(
                        0).await();
                assertThat(numCallLogs()).isEqualTo(0);
            }
        } finally {
            try (PermissionContext p2 = TestApis.permissions().withPermission(
                    WRITE_CALL_LOG).withPermission(INTERACT_ACROSS_USERS_FULL)) {
                TestApis.context().instrumentedContextAsUser(
                        sDeviceState.workProfile()).getContentResolver().delete(
                        CallLog.Calls.CONTENT_URI, null, null);
            }
            sDeviceState.profileOwner(
                    WORK_PROFILE).devicePolicyManager().setManagedSubscriptionsPolicy(
                    new ManagedSubscriptionsPolicy(
                            ManagedSubscriptionsPolicy.TYPE_ALL_PERSONAL_SUBSCRIPTIONS));
        }
    }

    private void setPackageAsSmsRoleHolderForUser(String packageName, UserHandle userHandle)
            throws ExecutionException, InterruptedException {
        CompletableFuture<Boolean> roleUpdateFuture = new CompletableFuture<>();
        SystemUtil.runWithShellPermissionIdentity(() -> {
            mRoleManager.addRoleHolderAsUser(ROLE_SMS, packageName,
                    MANAGE_HOLDERS_FLAG_DONT_KILL_APP, userHandle, sContext.getMainExecutor(),
                    roleUpdateFuture::complete);
        });
        // Wait for the future to complete.
        roleUpdateFuture.get();
    }

    private String getDefaultDialerPackage() {
        return sContext.getSystemService(TelecomManager.class).getDefaultDialerPackage();
    }

    private void assumeSmsCapableDevice() {
        assumeTrue(mTelephonyManager.isSmsCapable() || (mRoleManager != null
                && mRoleManager.isRoleAvailable(RoleManager.ROLE_SMS)));
    }

    private void assumeCallCapableDevice() {
        assumeTrue(mTelephonyManager.isVoiceCapable() || (mRoleManager != null
                && mRoleManager.isRoleAvailable(RoleManager.ROLE_DIALER)));
    }

    private void assertValidSimCardPresent() {
        assertTrue("[RERUN] This test requires SIM card to be present", isSimCardPresent());
        assertFalse("[RERUN] SIM card does not provide phone number. Use a suitable SIM Card.",
                TextUtils.isEmpty(mDestinationNumber));
    }

    private boolean isSimCardPresent() {
        return mTelephonyManager.getSimState() == TelephonyManager.SIM_STATE_READY;
    }

    private CustomEvent.CustomEventQuery customEvents(String packageName, UserReference user) {
        return CustomEvent.queryPackage(packageName).onUser(user);
    }

    private int numCallLogs() {
        Cursor cursor = TestApis.context().instrumentedContext().getContentResolver().query(
                CallLog.Calls.CONTENT_URI, null, null, null, null);
        return cursor.getCount();
    }
}
