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
package android.telephony.cts;

import static android.telephony.PreciseDisconnectCause.NO_DISCONNECT_CAUSE_AVAILABLE;
import static android.telephony.PreciseDisconnectCause.TEMPORARY_FAILURE;
import static android.telephony.mockmodem.MockSimService.MOCK_SIM_PROFILE_ID_TWN_CHT;
import static android.telephony.mockmodem.MockSimService.MOCK_SIM_PROFILE_ID_TWN_FET;

import static com.android.internal.telephony.RILConstants.RIL_REQUEST_RADIO_POWER;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.hardware.radio.network.Domain;
import android.hardware.radio.sim.Carrier;
import android.hardware.radio.sim.CarrierRestrictions;
import android.hardware.radio.voice.LastCallFailCause;
import android.hardware.radio.voice.UusInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.cts.util.TelephonyUtils;
import android.telephony.mockmodem.MockCallControlInfo;
import android.telephony.mockmodem.MockModemConfigInterface;
import android.telephony.mockmodem.MockModemManager;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.ShellIdentityUtils;
import com.android.internal.telephony.uicc.IccUtils;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Test MockModemService interfaces. */
public class TelephonyManagerTestOnMockModem {
    private static final String TAG = "TelephonyManagerTestOnMockModem";
    private static final long WAIT_TIME_MS = 5000;
    private static MockModemManager sMockModemManager;
    private static TelephonyManager sTelephonyManager;
    private static final String ALLOW_MOCK_MODEM_PROPERTY = "persist.radio.allow_mock_modem";
    private static final String BOOT_ALLOW_MOCK_MODEM_PROPERTY = "ro.boot.radio.allow_mock_modem";
    private static final boolean DEBUG = !"user".equals(Build.TYPE);
    private static final String RESOURCE_PACKAGE_NAME = "android";
    private static boolean sIsMultiSimDevice;
    private static HandlerThread sServiceStateChangeCallbackHandlerThread;
    private static Handler sServiceStateChangeCallbackHandler;
    private static HandlerThread sCallDisconnectCauseCallbackHandlerThread;
    private static Handler sCallDisconnectCauseCallbackHandler;
    private static HandlerThread sCallStateChangeCallbackHandlerThread;
    private static Handler sCallStateChangeCallbackHandler;
    private static ServiceStateListener sServiceStateCallback;
    private static CallDisconnectCauseListener sCallDisconnectCauseCallback;
    private static CallStateListener sCallStateCallback;
    private int mPreciseCallDisconnectCause;
    private int mCallState;
    private final Object mServiceStateChangeLock = new Object();
    private final Object mCallDisconnectCauseLock = new Object();
    private final Object mCallStateChangeLock = new Object();
    private final Executor mServiceStateChangeExecutor = Runnable::run;
    private final Executor mCallDisconnectCauseExecutor = Runnable::run;
    private final Executor mCallStateChangeExecutor = Runnable::run;
    private boolean mResetCarrierStatusInfo;
    private static String mShaId;
    private final int TIMEOUT_IN_SEC_FOR_MODEM_CB = 5;
    @BeforeClass
    public static void beforeAllTests() throws Exception {
        Log.d(TAG, "TelephonyManagerTestOnMockModem#beforeAllTests()");

        if (!hasTelephonyFeature()) {
            return;
        }

        enforceMockModemDeveloperSetting();
        sTelephonyManager =
                (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);

        sIsMultiSimDevice = isMultiSim(sTelephonyManager);

        sMockModemManager = new MockModemManager();
        assertNotNull(sMockModemManager);
        assertTrue(sMockModemManager.connectMockModemService());

        sServiceStateChangeCallbackHandlerThread =
                new HandlerThread("TelephonyManagerServiceStateChangeCallbackTest");
        sServiceStateChangeCallbackHandlerThread.start();
        sServiceStateChangeCallbackHandler =
                new Handler(sServiceStateChangeCallbackHandlerThread.getLooper());

        sCallDisconnectCauseCallbackHandlerThread =
                new HandlerThread("TelephonyManagerCallDisconnectCauseCallbackTest");
        sCallDisconnectCauseCallbackHandlerThread.start();
        sCallDisconnectCauseCallbackHandler =
                new Handler(sCallDisconnectCauseCallbackHandlerThread.getLooper());

        sCallStateChangeCallbackHandlerThread =
                new HandlerThread("TelephonyManagerCallStateChangeCallbackTest");
        sCallStateChangeCallbackHandlerThread.start();
        sCallStateChangeCallbackHandler =
                new Handler(sCallStateChangeCallbackHandlerThread.getLooper());
        mShaId = getShaId(TelephonyUtils.CTS_APP_PACKAGE);
    }

    @AfterClass
    public static void afterAllTests() throws Exception {
        Log.d(TAG, "TelephonyManagerTestOnMockModem#afterAllTests()");

        if (!hasTelephonyFeature()) {
            return;
        }

        if (sServiceStateChangeCallbackHandlerThread != null) {
            sServiceStateChangeCallbackHandlerThread.quitSafely();
            sServiceStateChangeCallbackHandlerThread = null;
        }

        if (sCallDisconnectCauseCallbackHandlerThread != null) {
            sCallDisconnectCauseCallbackHandlerThread.quitSafely();
            sCallDisconnectCauseCallbackHandlerThread = null;
        }

        if (sCallStateChangeCallbackHandlerThread != null) {
            sCallStateChangeCallbackHandlerThread.quitSafely();
            sCallStateChangeCallbackHandlerThread = null;
        }

        if (sServiceStateCallback != null) {
            sTelephonyManager.unregisterTelephonyCallback(sServiceStateCallback);
            sServiceStateCallback = null;
        }

        if (sCallDisconnectCauseCallback != null) {
            sTelephonyManager.unregisterTelephonyCallback(sCallDisconnectCauseCallback);
            sCallDisconnectCauseCallback = null;
        }

        if (sCallStateCallback != null) {
            sTelephonyManager.unregisterTelephonyCallback(sCallStateCallback);
            sCallStateCallback = null;
        }

        // Rebind all interfaces which is binding to MockModemService to default.
        assertNotNull(sMockModemManager);
        // Reset the modified error response of RIL_REQUEST_RADIO_POWER to the original behavior
        // and -1 means to disable the modifed mechanism in mock modem
        sMockModemManager.forceErrorResponse(0, RIL_REQUEST_RADIO_POWER, -1);
        assertTrue(sMockModemManager.disconnectMockModemService());
        sMockModemManager = null;
        mShaId = null;
    }

    @Before
    public void beforeTest() {
        assumeTrue(hasTelephonyFeature());
        try {
            sTelephonyManager.getHalVersion(TelephonyManager.HAL_SERVICE_RADIO);
        } catch (IllegalStateException e) {
            assumeNoException("Skipping tests because Telephony service is null", e);
        }
    }

    @After
    public void afterTest() {
        if (mResetCarrierStatusInfo) {
            try {
                TelephonyUtils.resetCarrierRestrictionStatusAllowList(
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation());
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                mResetCarrierStatusInfo = false;
            }
        }
    }

    private static Context getContext() {
        return InstrumentationRegistry.getInstrumentation().getContext();
    }

    private static String getShaId(String packageName) {
        try {
            final PackageManager packageManager = getContext().getPackageManager();
            MessageDigest sha1MDigest = MessageDigest.getInstance("SHA1");
            final PackageInfo packageInfo = packageManager.getPackageInfo(packageName,
                    PackageManager.GET_SIGNATURES);
            for (Signature signature : packageInfo.signatures) {
                final byte[] signatureSha1 = sha1MDigest.digest(signature.toByteArray());
                return IccUtils.bytesToHexString(signatureSha1);
            }
        } catch (NoSuchAlgorithmException | PackageManager.NameNotFoundException ex) {
            ex.printStackTrace();
        }
        return null;
    }

    private static boolean hasTelephonyFeature() {
        final PackageManager pm = getContext().getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            Log.d(TAG, "Skipping test that requires FEATURE_TELEPHONY");
            return false;
        }
        return true;
    }

    private static boolean isMultiSim(TelephonyManager tm) {
        return tm != null && tm.getPhoneCount() > 1;
    }

    private static boolean isSimHotSwapCapable() {
        boolean isSimHotSwapCapable = false;
        int resourceId =
                getContext()
                        .getResources()
                        .getIdentifier("config_hotswapCapable", "bool", RESOURCE_PACKAGE_NAME);

        if (resourceId > 0) {
            isSimHotSwapCapable = getContext().getResources().getBoolean(resourceId);
        } else {
            Log.d(TAG, "Fail to get the resource Id, using default.");
        }

        Log.d(TAG, "isSimHotSwapCapable = " + (isSimHotSwapCapable ? "true" : "false"));

        return isSimHotSwapCapable;
    }

    private static void enforceMockModemDeveloperSetting() throws Exception {
        boolean isAllowed = SystemProperties.getBoolean(ALLOW_MOCK_MODEM_PROPERTY, false);
        boolean isAllowedForBoot =
                SystemProperties.getBoolean(BOOT_ALLOW_MOCK_MODEM_PROPERTY, false);
        // Check for developer settings for user build. Always allow for debug builds
        if (!(isAllowed || isAllowedForBoot) && !DEBUG) {
            throw new IllegalStateException(
                    "!! Enable Mock Modem before running this test !! "
                            + "Developer options => Allow Mock Modem");
        }
    }

    private int getActiveSubId(int phoneId) {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity("android.permission.READ_PRIVILEGED_PHONE_STATE");

        int[] allSubs =
                getContext()
                        .getSystemService(SubscriptionManager.class)
                        .getActiveSubscriptionIdList();
        int subsLength = allSubs.length;
        Log.d(TAG, " Active Sub length is " + subsLength);

        assertTrue(phoneId <= (subsLength - 1));

        return allSubs[phoneId];
    }

    private int getRegState(int domain, int subId) {
        int reg;

        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity("android.permission.READ_PHONE_STATE");

        ServiceState ss = sTelephonyManager.createForSubscriptionId(subId).getServiceState();
        assertNotNull(ss);

        NetworkRegistrationInfo nri =
                ss.getNetworkRegistrationInfo(domain, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        assertNotNull(nri);

        reg = nri.getRegistrationState();
        Log.d(TAG, "SS: " + nri.registrationStateToString(reg));

        return reg;
    }

    @Test
    public void testSimStateChange() throws Throwable {
        Log.d(TAG, "TelephonyManagerTestOnMockModem#testSimStateChange");

        assumeTrue(isSimHotSwapCapable());

        int slotId = 0;

        // Remove the SIM for initial state
        sMockModemManager.removeSimCard(slotId);

        int simCardState = sTelephonyManager.getSimCardState();
        Log.d(TAG, "Current SIM card state: " + simCardState);

        assertTrue(
                Arrays.asList(TelephonyManager.SIM_STATE_UNKNOWN, TelephonyManager.SIM_STATE_ABSENT)
                        .contains(simCardState));

        // Insert a SIM
        assertTrue(sMockModemManager.insertSimCard(slotId, MOCK_SIM_PROFILE_ID_TWN_CHT));
        simCardState = sTelephonyManager.getSimCardState();
        assertEquals(TelephonyManager.SIM_STATE_PRESENT, simCardState);

        // Check SIM state ready
        simCardState = sTelephonyManager.getSimState();
        assertEquals(TelephonyManager.SIM_STATE_READY, simCardState);

        // Remove the SIM
        assertTrue(sMockModemManager.removeSimCard(slotId));
        simCardState = sTelephonyManager.getSimCardState();
        assertEquals(TelephonyManager.SIM_STATE_ABSENT, simCardState);
    }

    @Test
    public void testServiceStateChange() throws Throwable {
        Log.d(TAG, "TelephonyManagerTestOnMockModem#testServiceStateChange");

        assumeTrue(isSimHotSwapCapable());

        int slotId = 0;
        int subId;

        // Insert a SIM
        sMockModemManager.insertSimCard(slotId, MOCK_SIM_PROFILE_ID_TWN_CHT);

        // Leave Service
        Log.d(TAG, "testServiceStateChange: Leave Service");
        sMockModemManager.changeNetworkService(slotId, MOCK_SIM_PROFILE_ID_TWN_CHT, false);

        // Expect: Seaching State
        TimeUnit.SECONDS.sleep(2);
        subId = getActiveSubId(slotId);
        assertEquals(
                getRegState(NetworkRegistrationInfo.DOMAIN_CS, subId),
                NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_SEARCHING);

        // Enter Service
        Log.d(TAG, "testServiceStateChange: Enter Service");
        sMockModemManager.changeNetworkService(slotId, MOCK_SIM_PROFILE_ID_TWN_CHT, true);

        // Expect: Home State
        TimeUnit.SECONDS.sleep(2);
        assertEquals(
                getRegState(NetworkRegistrationInfo.DOMAIN_CS, subId),
                NetworkRegistrationInfo.REGISTRATION_STATE_HOME);

        // Leave Service
        Log.d(TAG, "testServiceStateChange: Leave Service");
        sMockModemManager.changeNetworkService(slotId, MOCK_SIM_PROFILE_ID_TWN_CHT, false);

        // Expect: Seaching State
        TimeUnit.SECONDS.sleep(2);
        assertEquals(
                getRegState(NetworkRegistrationInfo.DOMAIN_CS, subId),
                NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_SEARCHING);

        // Remove the SIM
        sMockModemManager.removeSimCard(slotId);
    }

    private class ServiceStateListener extends TelephonyCallback
            implements TelephonyCallback.ServiceStateListener {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            if (serviceState.getVoiceRegState() == ServiceState.STATE_IN_SERVICE) {
                mServiceStateChangeLock.notify();
            }
        }
    }

    private class CallDisconnectCauseListener extends TelephonyCallback
            implements TelephonyCallback.CallDisconnectCauseListener {
        @Override
        public void onCallDisconnectCauseChanged(int disconnectCause, int preciseDisconnectCause) {
            synchronized (mCallDisconnectCauseLock) {
                mPreciseCallDisconnectCause = preciseDisconnectCause;
                Log.d(TAG, "Callback: call disconnect cause = " + mPreciseCallDisconnectCause);
                mCallDisconnectCauseLock.notify();
            }
        }
    }

    private class CallStateListener extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            synchronized (mCallStateChangeLock) {
                mCallState = state;
                mCallStateChangeLock.notify();
            }
        }
    }

    @Test
    public void testVoiceCallState() throws Throwable {
        Log.d(TAG, "TelephonyManagerTestOnMockModem#testVoiceCallState");

        assumeTrue(isSimHotSwapCapable());

        int slotId = 0;
        int subId;
        TelecomManager telecomManager =
                (TelecomManager) getContext().getSystemService(Context.TELECOM_SERVICE);

        // Insert a SIM
        Log.d(TAG, "Start to insert a SIM");
        sMockModemManager.insertSimCard(slotId, MOCK_SIM_PROFILE_ID_TWN_FET);
        TimeUnit.SECONDS.sleep(1);

        // Register service state change callback
        sServiceStateChangeCallbackHandler.post(
                () -> {
                    sServiceStateCallback = new ServiceStateListener();
                    ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(
                            sTelephonyManager,
                            (tm) ->
                                    tm.registerTelephonyCallback(
                                            mServiceStateChangeExecutor, sServiceStateCallback));
                });

        // In service
        Log.d(TAG, "Start to register CS only network");
        sMockModemManager.changeNetworkService(
                slotId, MOCK_SIM_PROFILE_ID_TWN_FET, true, Domain.CS);

        // Verify service state
        synchronized (mServiceStateChangeLock) {
            Log.d(TAG, "Wait for service state change to in service");
            mServiceStateChangeLock.wait(WAIT_TIME_MS);
        }

        subId = getActiveSubId(slotId);
        assertEquals(
                getRegState(NetworkRegistrationInfo.DOMAIN_PS, subId),
                NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_SEARCHING);

        assertEquals(
                getRegState(NetworkRegistrationInfo.DOMAIN_CS, subId),
                NetworkRegistrationInfo.REGISTRATION_STATE_HOME);

        // Register call state change callback
        mCallState = TelephonyManager.CALL_STATE_IDLE;
        sCallStateChangeCallbackHandler.post(
                () -> {
                    sCallStateCallback = new CallStateListener();
                    ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(
                            sTelephonyManager,
                            (tm) ->
                                    tm.registerTelephonyCallback(
                                            mCallStateChangeExecutor, sCallStateCallback));
                });

        //Disable Ims to make sure the call would go thorugh with a CS call
        ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(
                sTelephonyManager,  (tm) -> tm.disableIms(slotId));

        // Dial a CS voice call
        Log.d(TAG, "Start dialing call");
        String phoneNumber = "+886987654321";
        final Uri address = Uri.fromParts(PhoneAccount.SCHEME_TEL, phoneNumber, null);

        // Place outgoing call
        telecomManager.placeCall(address, null);
        TimeUnit.SECONDS.sleep(1);

        // Verify call state
        synchronized (mCallStateChangeLock) {
            if (mCallState == TelephonyManager.CALL_STATE_IDLE) {
                Log.d(TAG, "Wait for call state change to offhook");
                mCallStateChangeLock.wait(WAIT_TIME_MS);
            }
            assertEquals(TelephonyManager.CALL_STATE_OFFHOOK, mCallState);
        }

        // Verify the call is a CS call
        assertTrue(sMockModemManager.getNumberOfOngoingCSCalls(slotId) > 0);

        // Hang up the call
        Log.d(TAG, "Hangup call");
        telecomManager.endCall();
        TimeUnit.SECONDS.sleep(1);

        // Verify call state
        synchronized (mCallStateChangeLock) {
            if (mCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
                Log.d(TAG, "Wait for call state change to idle");
                mCallStateChangeLock.wait(WAIT_TIME_MS);
            }
            assertEquals(TelephonyManager.CALL_STATE_IDLE, mCallState);
        }

        // Register call disconnect cause callback
        mPreciseCallDisconnectCause = NO_DISCONNECT_CAUSE_AVAILABLE;

        sCallDisconnectCauseCallbackHandler.post(
                () -> {
                    sCallDisconnectCauseCallback = new CallDisconnectCauseListener();
                    ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(
                            sTelephonyManager,
                            (tm) ->
                                    tm.registerTelephonyCallback(
                                            mCallDisconnectCauseExecutor,
                                            sCallDisconnectCauseCallback));
                });

        // Trigger an incoming call
        Log.d(TAG, "Trigger an incoming call.");
        UusInfo[] uusInfo = new UusInfo[0];
        MockCallControlInfo callControlInfo = new MockCallControlInfo();
        callControlInfo.setActiveDurationInMs(1000);
        callControlInfo.setCallEndInfo(
                LastCallFailCause.TEMPORARY_FAILURE, "cts-test-call-failure");
        sMockModemManager.triggerIncomingVoiceCall(
                slotId, phoneNumber, uusInfo, null, callControlInfo);

        // Verify call state
        synchronized (mCallStateChangeLock) {
            if (mCallState == TelephonyManager.CALL_STATE_IDLE) {
                Log.d(TAG, "Wait for call state change to ringing");
                mCallStateChangeLock.wait(WAIT_TIME_MS);
            }
            assertEquals(TelephonyManager.CALL_STATE_RINGING, mCallState);
        }

        Log.d(TAG, "Answer the call");
        telecomManager.acceptRingingCall();

        // Verify call state
        synchronized (mCallStateChangeLock) {
            if (mCallState == TelephonyManager.CALL_STATE_RINGING) {
                Log.d(TAG, "Wait for call state change to offhook");
                mCallStateChangeLock.wait(WAIT_TIME_MS);
            }
            assertEquals(TelephonyManager.CALL_STATE_OFFHOOK, mCallState);
        }

        synchronized (mCallDisconnectCauseLock) {
            if (mPreciseCallDisconnectCause == NO_DISCONNECT_CAUSE_AVAILABLE) {
                Log.d(TAG, "Wait for disconnect cause to TEMPORARY_FAILURE");
                mCallDisconnectCauseLock.wait(WAIT_TIME_MS);
            }
            assertEquals(TEMPORARY_FAILURE, mPreciseCallDisconnectCause);
        }

        // Unregister service state change callback
        sTelephonyManager.unregisterTelephonyCallback(sServiceStateCallback);
        sServiceStateCallback = null;

        // Unregister call disconnect cause callback
        sTelephonyManager.unregisterTelephonyCallback(sCallDisconnectCauseCallback);
        sCallDisconnectCauseCallback = null;

        // Unregister call state change callback
        sTelephonyManager.unregisterTelephonyCallback(sCallStateCallback);
        sCallStateCallback = null;

        // Out of service
        sMockModemManager.changeNetworkService(
                slotId, MOCK_SIM_PROFILE_ID_TWN_FET, false, Domain.CS);

        // Remove the SIM
        sMockModemManager.removeSimCard(slotId);
    }

    @Test
    public void testDsdsServiceStateChange() throws Throwable {
        Log.d(TAG, "TelephonyManagerTestOnMockModem#testDsdsServiceStateChange");
        assumeTrue("Skip test: Not test on single SIM device", sIsMultiSimDevice);

        int slotId_0 = 0;
        int slotId_1 = 1;
        int subId_0;
        int subId_1;

        // Insert a SIM
        sMockModemManager.insertSimCard(slotId_0, MOCK_SIM_PROFILE_ID_TWN_CHT);
        sMockModemManager.insertSimCard(slotId_1, MOCK_SIM_PROFILE_ID_TWN_FET);

        // Expect: Seaching State
        TimeUnit.SECONDS.sleep(2);
        subId_0 = getActiveSubId(slotId_0);
        assertEquals(
                getRegState(NetworkRegistrationInfo.DOMAIN_CS, subId_0),
                NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_SEARCHING);

        subId_1 = getActiveSubId(slotId_1);
        assertEquals(
                getRegState(NetworkRegistrationInfo.DOMAIN_CS, subId_1),
                NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_SEARCHING);

        // Enter Service
        Log.d(TAG, "testDsdsServiceStateChange: Enter Service");
        sMockModemManager.changeNetworkService(slotId_0, MOCK_SIM_PROFILE_ID_TWN_CHT, true);
        sMockModemManager.changeNetworkService(slotId_1, MOCK_SIM_PROFILE_ID_TWN_FET, true);

        // Expect: Home State
        TimeUnit.SECONDS.sleep(2);
        assertEquals(
                getRegState(NetworkRegistrationInfo.DOMAIN_CS, subId_0),
                NetworkRegistrationInfo.REGISTRATION_STATE_HOME);
        assertEquals(
                getRegState(NetworkRegistrationInfo.DOMAIN_CS, subId_1),
                NetworkRegistrationInfo.REGISTRATION_STATE_HOME);

        TimeUnit.SECONDS.sleep(2);

        // Leave Service
        Log.d(TAG, "testDsdsServiceStateChange: Leave Service");
        sMockModemManager.changeNetworkService(slotId_0, MOCK_SIM_PROFILE_ID_TWN_CHT, false);
        sMockModemManager.changeNetworkService(slotId_1, MOCK_SIM_PROFILE_ID_TWN_FET, false);

        // Expect: Seaching State
        TimeUnit.SECONDS.sleep(2);
        assertEquals(
                getRegState(NetworkRegistrationInfo.DOMAIN_CS, subId_0),
                NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_SEARCHING);
        assertEquals(
                getRegState(NetworkRegistrationInfo.DOMAIN_CS, subId_1),
                NetworkRegistrationInfo.REGISTRATION_STATE_NOT_REGISTERED_SEARCHING);

        // Remove the SIM
        sMockModemManager.removeSimCard(slotId_0);
        sMockModemManager.removeSimCard(slotId_1);
    }

    /**
     * Verify the NotRestricted status of the device with READ_PHONE_STATE permission granted.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    public void getCarrierRestrictionStatus_ReadPhoneState_NotRestricted() throws Exception {
        LinkedBlockingQueue<Integer> carrierRestrictionStatusResult = new LinkedBlockingQueue<>(1);
        try {
            sMockModemManager.updateCarrierRestrictionInfo(null,
                    CarrierRestrictions.CarrierRestrictionStatus.NOT_RESTRICTED);
            TelephonyUtils.addCarrierRestrictionStatusAllowList(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
                    TelephonyUtils.CTS_APP_PACKAGE, 10110, mShaId);
            mResetCarrierStatusInfo = true;
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(sTelephonyManager,
                    tm -> tm.getCarrierRestrictionStatus(getContext().getMainExecutor(),
                            carrierRestrictionStatusResult::offer),
                    Manifest.permission.READ_PHONE_STATE);
        } catch (SecurityException ex) {
            fail();
        }
        Integer value = carrierRestrictionStatusResult.poll(TIMEOUT_IN_SEC_FOR_MODEM_CB,
                TimeUnit.SECONDS);
        assertNotNull(value);
        assertEquals(TelephonyManager.CARRIER_RESTRICTION_STATUS_NOT_RESTRICTED, value.intValue());
    }

    /**
     * Verify the Restricted status of the device with READ_PHONE_STATE permission granted.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    public void getCarrierRestrictionStatus_ReadPhoneState_Restricted() throws Exception {
        LinkedBlockingQueue<Integer> carrierRestrictionStatusResult = new LinkedBlockingQueue<>(1);
        try {
            sMockModemManager.updateCarrierRestrictionInfo(getCarrierList(false),
                    CarrierRestrictions.CarrierRestrictionStatus.RESTRICTED);
            TelephonyUtils.addCarrierRestrictionStatusAllowList(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
                    TelephonyUtils.CTS_APP_PACKAGE, 10110, mShaId);
            mResetCarrierStatusInfo = true;
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(sTelephonyManager,
                    tm -> tm.getCarrierRestrictionStatus(getContext().getMainExecutor(),
                            carrierRestrictionStatusResult::offer),
                    Manifest.permission.READ_PHONE_STATE);
        } catch (SecurityException ex) {
            fail();
        }
        Integer value = carrierRestrictionStatusResult.poll(TIMEOUT_IN_SEC_FOR_MODEM_CB,
                TimeUnit.SECONDS);
        assertNotNull(value);
        assertEquals(TelephonyManager.CARRIER_RESTRICTION_STATUS_RESTRICTED, value.intValue());
    }

    /**
     * Verify the Restricted To Caller status of the device with READ_PHONE_STATE permission
     * granted.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    public void getCarrierRestrictionStatus_ReadPhoneState_RestrictedToCaller_MNO() throws Exception {
        LinkedBlockingQueue<Integer> carrierRestrictionStatusResult = new LinkedBlockingQueue<>(1);
        try {
            sMockModemManager.updateCarrierRestrictionInfo(getCarrierList(false),
                    CarrierRestrictions.CarrierRestrictionStatus.RESTRICTED);
            TelephonyUtils.addCarrierRestrictionStatusAllowList(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
                    TelephonyUtils.CTS_APP_PACKAGE, 1839, mShaId);
            mResetCarrierStatusInfo = true;
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(sTelephonyManager,
                    tm -> tm.getCarrierRestrictionStatus(getContext().getMainExecutor(),
                            carrierRestrictionStatusResult::offer),
                    Manifest.permission.READ_PHONE_STATE);
        } catch (SecurityException ex) {
            fail();
        }
        Integer value = carrierRestrictionStatusResult.poll(TIMEOUT_IN_SEC_FOR_MODEM_CB,
                TimeUnit.SECONDS);
        assertNotNull(value);
        assertEquals(TelephonyManager.CARRIER_RESTRICTION_STATUS_RESTRICTED_TO_CALLER,
                value.intValue());
    }

    /**
     * Verify the Restricted status of the device with READ_PHONE_STATE permission granted.
     * MVNO operator reference without GID
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    public void getCarrierRestrictionStatus_ReadPhoneState_RestrictedToCaller_MNO1() throws Exception {
        LinkedBlockingQueue<Integer> carrierRestrictionStatusResult = new LinkedBlockingQueue<>(1);
        try {
            sMockModemManager.updateCarrierRestrictionInfo(getCarrierList(false),
                    CarrierRestrictions.CarrierRestrictionStatus.RESTRICTED);
            TelephonyUtils.addCarrierRestrictionStatusAllowList(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
                    TelephonyUtils.CTS_APP_PACKAGE, 2032, mShaId);
            mResetCarrierStatusInfo = true;
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(sTelephonyManager,
                    tm -> tm.getCarrierRestrictionStatus(getContext().getMainExecutor(),
                            carrierRestrictionStatusResult::offer),
                    Manifest.permission.READ_PHONE_STATE);
        } catch (SecurityException ex) {
            fail();
        }
        Integer value = carrierRestrictionStatusResult.poll(TIMEOUT_IN_SEC_FOR_MODEM_CB,
                TimeUnit.SECONDS);
        assertNotNull(value);
        assertEquals(TelephonyManager.CARRIER_RESTRICTION_STATUS_RESTRICTED,
                value.intValue());
    }

    /**
     * Verify the Restricted To Caller status of the device with READ_PHONE_STATE permission
     * granted.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    public void getCarrierRestrictionStatus_ReadPhoneState_RestrictedToCaller_MVNO() throws Exception {
        LinkedBlockingQueue<Integer> carrierRestrictionStatusResult = new LinkedBlockingQueue<>(1);
        try {
            sMockModemManager.updateCarrierRestrictionInfo(getCarrierList(true),
                    CarrierRestrictions.CarrierRestrictionStatus.RESTRICTED);
            TelephonyUtils.addCarrierRestrictionStatusAllowList(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
                    TelephonyUtils.CTS_APP_PACKAGE, 2032, mShaId);
            mResetCarrierStatusInfo = true;
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(sTelephonyManager,
                    tm -> tm.getCarrierRestrictionStatus(getContext().getMainExecutor(),
                            carrierRestrictionStatusResult::offer),
                    Manifest.permission.READ_PHONE_STATE);
        } catch (SecurityException ex) {
            fail();
        }
        Integer value = carrierRestrictionStatusResult.poll(TIMEOUT_IN_SEC_FOR_MODEM_CB,
                TimeUnit.SECONDS);
        assertNotNull(value);
        assertEquals(TelephonyManager.CARRIER_RESTRICTION_STATUS_RESTRICTED_TO_CALLER,
                value.intValue());
    }

    /**
     * Verify the Unknown status of the device with READ_PHONE_STATE permission granted.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    public void getCarrierRestrictionStatus_ReadPhoneState_Unknown() throws Exception {
        LinkedBlockingQueue<Integer> carrierRestrictionStatusResult = new LinkedBlockingQueue<>(1);
        try {
            sMockModemManager.updateCarrierRestrictionInfo(null,
                    CarrierRestrictions.CarrierRestrictionStatus.UNKNOWN);
            TelephonyUtils.addCarrierRestrictionStatusAllowList(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
                    TelephonyUtils.CTS_APP_PACKAGE, 10110, mShaId);
            mResetCarrierStatusInfo = true;
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(sTelephonyManager,
                    tm -> tm.getCarrierRestrictionStatus(getContext().getMainExecutor(),
                            carrierRestrictionStatusResult::offer),
                    Manifest.permission.READ_PHONE_STATE);
        } catch (SecurityException ex) {
            fail();
        }
        Integer value = carrierRestrictionStatusResult.poll(TIMEOUT_IN_SEC_FOR_MODEM_CB,
                TimeUnit.SECONDS);
        assertNotNull(value);
        assertEquals(TelephonyManager.CARRIER_RESTRICTION_STATUS_UNKNOWN, value.intValue());
    }

    private Carrier[] getCarrierList(boolean isGidRequired) {
        android.hardware.radio.sim.Carrier carrier
                = new android.hardware.radio.sim.Carrier();
        carrier.mcc = "310";
        carrier.mnc = "599";
        if (isGidRequired) {
            carrier.matchType = Carrier.MATCH_TYPE_GID1;
            carrier.matchData = "BA01450000000000";
        }
        Carrier[] carrierList = new Carrier[1];
        carrierList[0] = carrier;
        return carrierList;
    }

    /**
     * Test for primaryImei will return the IMEI that is set through mockModem
     */
    @Test
    public void testGetPrimaryImei() {
        assumeTrue(sTelephonyManager.getActiveModemCount() > 0);
        String primaryImei = ShellIdentityUtils.invokeMethodWithShellPermissions(sTelephonyManager,
                (tm) -> tm.getPrimaryImei());
        assertNotNull(primaryImei);
        assertEquals(MockModemConfigInterface.DEFAULT_PHONE1_IMEI, primaryImei);
    }
}
