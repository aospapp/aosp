/*
 * Copyright (C) 2009 The Android Open Source Project
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

import static androidx.test.InstrumentationRegistry.getContext;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Looper;
import android.telephony.CellInfo;
import android.telephony.CellLocation;
import android.telephony.PhoneStateListener;
import android.telephony.PreciseCallState;
import android.telephony.PreciseDataConnectionState;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.net.ConnectivityManager;
import android.telephony.ims.ImsReasonInfo;
import android.util.Log;

import com.android.compatibility.common.util.ShellIdentityUtils;
import com.android.compatibility.common.util.TestThread;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PhoneStateListenerTest {

    public static final long WAIT_TIME = 1000;

    private boolean mOnActiveDataSubscriptionIdChanged;
    private boolean mOnCallForwardingIndicatorChangedCalled;
    private boolean mOnCallStateChangedCalled;
    private boolean mOnCellLocationChangedCalled;
    private boolean mOnUserMobileDataStateChanged;
    private boolean mOnDataActivityCalled;
    private boolean mOnDataConnectionStateChangedCalled;
    private boolean mOnDataConnectionStateChangedWithNetworkTypeCalled;
    private boolean mOnMessageWaitingIndicatorChangedCalled;
    private boolean mOnCellInfoChangedCalled;
    private boolean mOnServiceStateChangedCalled;
    private boolean mOnSignalStrengthChangedCalled;
    private boolean mOnPreciseCallStateChangedCalled;
    private boolean mOnCallDisconnectCauseChangedCalled;
    private boolean mOnImsCallDisconnectCauseChangedCalled;
    private boolean mOnPreciseDataConnectionStateChanged;
    private boolean mOnRadioPowerStateChangedCalled;
    private boolean mVoiceActivationStateChangedCalled;
    private boolean mSrvccStateChangedCalled;
    @TelephonyManager.RadioPowerState private int mRadioPowerState;
    @TelephonyManager.SimActivationState private int mVoiceActivationState;
    private PreciseDataConnectionState mPreciseDataConnectionState;
    private PreciseCallState mPreciseCallState;
    private SignalStrength mSignalStrength;
    private TelephonyManager mTelephonyManager;
    private PhoneStateListener mListener;
    private final Object mLock = new Object();
    private static final String TAG = "android.telephony.cts.PhoneStateListenerTest";
    private static ConnectivityManager mCm;
    private static final List<Integer> DATA_CONNECTION_STATE = Arrays.asList(
            TelephonyManager.DATA_CONNECTED,
            TelephonyManager.DATA_DISCONNECTED,
            TelephonyManager.DATA_CONNECTING,
            TelephonyManager.DATA_UNKNOWN,
            TelephonyManager.DATA_SUSPENDED
    );
    private static final List<Integer> PRECISE_CALL_STATE = Arrays.asList(
            PreciseCallState.PRECISE_CALL_STATE_ACTIVE,
            PreciseCallState.PRECISE_CALL_STATE_ALERTING,
            PreciseCallState.PRECISE_CALL_STATE_DIALING,
            PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED,
            PreciseCallState.PRECISE_CALL_STATE_DISCONNECTING,
            PreciseCallState.PRECISE_CALL_STATE_HOLDING,
            PreciseCallState.PRECISE_CALL_STATE_IDLE,
            PreciseCallState.PRECISE_CALL_STATE_INCOMING,
            PreciseCallState.PRECISE_CALL_STATE_NOT_VALID,
            PreciseCallState.PRECISE_CALL_STATE_WAITING
    );
    private Executor mSimpleExecutor = new Executor() {
        @Override
        public void execute(Runnable r) {
            r.run();
        }
    };

    @Before
    public void setUp() throws Exception {
        mTelephonyManager =
                (TelephonyManager)getContext().getSystemService(Context.TELEPHONY_SERVICE);
        mCm = (ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @After
    public void tearDown() throws Exception {
        if (mListener != null) {
            // unregister the listener
            mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_NONE);
        }
    }

    @Test
    public void testPhoneStateListener() {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        Looper.prepare();
        new PhoneStateListener();
    }

    /*
     * The tests below rely on the framework to immediately call the installed listener upon
     * registration. There is no simple way to emulate state changes for testing the listeners.
     */

    @Test
    public void testOnServiceStateChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onServiceStateChanged(ServiceState serviceState) {
                        synchronized(mLock) {
                            mOnServiceStateChangedCalled = true;
                            mLock.notify();
                        }
                    }
                };
                mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_SERVICE_STATE);

                Looper.loop();
            }
        });

        assertFalse(mOnServiceStateChangedCalled);
        t.start();

        synchronized (mLock) {
            if (!mOnServiceStateChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mOnServiceStateChangedCalled);
    }

    @Test
    public void testOnUnRegisterFollowedByRegister() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onServiceStateChanged(ServiceState serviceState) {
                        synchronized(mLock) {
                            mOnServiceStateChangedCalled = true;
                            mLock.notify();
                        }
                    }
                };
                mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_SERVICE_STATE);

                Looper.loop();
            }
        });

        assertFalse(mOnServiceStateChangedCalled);
        t.start();

        synchronized (mLock) {
            if (!mOnServiceStateChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mOnServiceStateChangedCalled);

        // reset and un-register
        mOnServiceStateChangedCalled = false;
        if (mListener != null) {
            // un-register the listener
            mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_NONE);
        }
        synchronized (mLock) {
            if (!mOnServiceStateChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        assertFalse(mOnServiceStateChangedCalled);

        // re-register the listener
        mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_SERVICE_STATE);
        synchronized (mLock) {
            if (!mOnServiceStateChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mOnServiceStateChangedCalled);
    }

    @Test
    public void testOnSignalStrengthChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onSignalStrengthChanged(int asu) {
                        synchronized(mLock) {
                            mOnSignalStrengthChangedCalled = true;
                            mLock.notify();
                        }
                    }
                };
                mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTH);

                Looper.loop();
            }
        });

        assertFalse(mOnSignalStrengthChangedCalled);
        t.start();

        synchronized (mLock) {
            if (!mOnSignalStrengthChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mOnSignalStrengthChangedCalled);
    }

    @Test
    public void testOnSignalStrengthsChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                        synchronized(mLock) {
                            mSignalStrength = signalStrength;
                            mLock.notify();
                        }
                    }
                };
                mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);

                Looper.loop();
            }
        });

        assertTrue(mSignalStrength == null);
        t.start();

        synchronized (mLock) {
            if (mSignalStrength == null) {
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mSignalStrength != null);

        // Call SignalStrength methods to make sure they do not throw any exceptions
        mSignalStrength.getCdmaDbm();
        mSignalStrength.getCdmaEcio();
        mSignalStrength.getEvdoDbm();
        mSignalStrength.getEvdoEcio();
        mSignalStrength.getEvdoSnr();
        mSignalStrength.getGsmBitErrorRate();
        mSignalStrength.getGsmSignalStrength();
        mSignalStrength.isGsm();
        mSignalStrength.getLevel();
    }

    @Test
    public void testOnMessageWaitingIndicatorChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onMessageWaitingIndicatorChanged(boolean mwi) {
                        synchronized(mLock) {
                            mOnMessageWaitingIndicatorChangedCalled = true;
                            mLock.notify();
                        }
                    }
                };
                mTelephonyManager.listen(
                        mListener, PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR);

                Looper.loop();
            }
        });

        assertFalse(mOnMessageWaitingIndicatorChangedCalled);
        t.start();

        synchronized (mLock) {
            if (!mOnMessageWaitingIndicatorChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mOnMessageWaitingIndicatorChangedCalled);
    }

    /*
     * The tests below rely on the framework to immediately call the installed listener upon
     * registration. There is no simple way to emulate state changes for testing the listeners.
     */
    @Test
    public void testOnPreciseCallStateChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onPreciseCallStateChanged(PreciseCallState preciseCallState) {
                        synchronized (mLock) {
                            mOnPreciseCallStateChangedCalled = true;
                            mPreciseCallState = preciseCallState;
                            mLock.notify();
                        }
                    }
                };
                ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
                        (tm) -> tm.listen(mListener, PhoneStateListener.LISTEN_PRECISE_CALL_STATE));
                Looper.loop();
            }
        });

        assertThat(mOnPreciseCallStateChangedCalled).isFalse();
        t.start();

        synchronized (mLock) {
            if (!mOnPreciseCallStateChangedCalled) {
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        Log.d(TAG, "testOnPreciseCallStateChanged: " + mOnPreciseCallStateChangedCalled);
        assertThat(mOnPreciseCallStateChangedCalled).isTrue();
        assertThat(mPreciseCallState.getForegroundCallState()).isIn(PRECISE_CALL_STATE);
        assertThat(mPreciseCallState.getBackgroundCallState()).isIn(PRECISE_CALL_STATE);
        assertThat(mPreciseCallState.getRingingCallState()).isIn(PRECISE_CALL_STATE);
    }

    /*
     * The tests below rely on the framework to immediately call the installed listener upon
     * registration. There is no simple way to emulate state changes for testing the listeners.
     */
    @Test
    public void testOnCallDisconnectCauseChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onCallDisconnectCauseChanged(int disconnectCause,
                                                             int preciseDisconnectCause) {
                        synchronized (mLock) {
                            mOnCallDisconnectCauseChangedCalled = true;
                            mLock.notify();
                        }
                    }
                };
                ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
                            (tm) -> tm.listen(mListener,
                                    PhoneStateListener.LISTEN_CALL_DISCONNECT_CAUSES));
                Looper.loop();
            }
        });

        assertThat(mOnCallDisconnectCauseChangedCalled).isFalse();
        t.start();

        synchronized (mLock) {
            if (!mOnCallDisconnectCauseChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertThat(mOnCallDisconnectCauseChangedCalled).isTrue();
    }

    /*
     * The tests below rely on the framework to immediately call the installed listener upon
     * registration. There is no simple way to emulate state changes for testing the listeners.
     */
    @Test
    public void testOnImsCallDisconnectCauseChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onImsCallDisconnectCauseChanged(ImsReasonInfo imsReason) {
                        synchronized (mLock) {
                            mOnImsCallDisconnectCauseChangedCalled = true;
                            mLock.notify();
                        }
                    }
                };
                ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
                        (tm) -> tm.listen(mListener,
                                PhoneStateListener.LISTEN_IMS_CALL_DISCONNECT_CAUSES));
                Looper.loop();
            }
        });

        assertThat(mOnImsCallDisconnectCauseChangedCalled).isFalse();
        t.start();

        synchronized (mLock) {
            if (!mOnImsCallDisconnectCauseChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertThat(mOnImsCallDisconnectCauseChangedCalled).isTrue();
    }

    @Test
    public void testOnPhoneStateListenerExecutorWithSrvccChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }
        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener(mSimpleExecutor) {
                    @Override
                    public void onSrvccStateChanged(int state) {
                        synchronized (mLock) {
                            mSrvccStateChangedCalled = true;
                            mLock.notify();
                        }
                    }
                };
                ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
                        (tm) -> tm.listen(mListener,
                                PhoneStateListener.LISTEN_SRVCC_STATE_CHANGED));
                Looper.loop();
            }
        });

        assertThat(mSrvccStateChangedCalled).isFalse();
        t.start();

        synchronized (mLock) {
            if (!mSrvccStateChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        assertThat(mSrvccStateChangedCalled).isTrue();
        t.checkException();
        Log.d(TAG, "testOnPhoneStateListenerExecutorWithSrvccChanged");
    }

    /*
    * The tests below rely on the framework to immediately call the installed listener upon
    * registration. There is no simple way to emulate state changes for testing the listeners.
    */
    @Test
    public void testOnRadioPowerStateChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }
        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onRadioPowerStateChanged(int state) {
                        synchronized(mLock) {
                            mRadioPowerState = state;
                            mOnRadioPowerStateChangedCalled = true;
                            mLock.notify();
                        }
                    }
                };
                ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
                        (tm) -> tm.listen(mListener,
                                PhoneStateListener.LISTEN_RADIO_POWER_STATE_CHANGED));
                Looper.loop();
            }
        });
        assertThat(mOnRadioPowerStateChangedCalled).isFalse();
        t.start();

        synchronized (mLock) {
            if (!mOnRadioPowerStateChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        Log.d(TAG, "testOnRadioPowerStateChanged: " + mRadioPowerState);
        assertThat(mTelephonyManager.getRadioPowerState()).isEqualTo(mRadioPowerState);
    }

    /*
     * The tests below rely on the framework to immediately call the installed listener upon
     * registration. There is no simple way to emulate state changes for testing the listeners.
     */
    @Test
    public void testOnVoiceActivationStateChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }
        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onVoiceActivationStateChanged(int state) {
                        synchronized(mLock) {
                            mVoiceActivationState = state;
                            mVoiceActivationStateChangedCalled = true;
                            mLock.notify();
                        }
                    }
                };
                ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
                        (tm) -> tm.listen(mListener,
                                PhoneStateListener.LISTEN_VOICE_ACTIVATION_STATE));
                Looper.loop();
            }
        });
        assertThat(mVoiceActivationStateChangedCalled).isFalse();
        t.start();

        synchronized (mLock) {
            if (!mVoiceActivationStateChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        Log.d(TAG, "onVoiceActivationStateChanged: " + mVoiceActivationState);
        int state = ShellIdentityUtils.invokeMethodWithShellPermissions(mTelephonyManager,
                (tm) -> tm.getVoiceActivationState());
        assertEquals(state, mVoiceActivationState);
    }

    /*
    * The tests below rely on the framework to immediately call the installed listener upon
    * registration. There is no simple way to emulate state changes for testing the listeners.
    */
    @Test
    public void testOnPreciseDataConnectionStateChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onPreciseDataConnectionStateChanged(
                            PreciseDataConnectionState state) {
                        synchronized(mLock) {
                            mOnPreciseDataConnectionStateChanged = true;
                            mPreciseDataConnectionState = state;
                            mLock.notify();
                        }
                    }
                };
                ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
                        (tm) -> tm.listen(mListener,
                                PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE));
                Looper.loop();
            }
        });

        assertThat(mOnCallDisconnectCauseChangedCalled).isFalse();
        t.start();

        synchronized (mLock) {
            if (!mOnPreciseDataConnectionStateChanged){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertThat(mOnPreciseDataConnectionStateChanged).isTrue();
        assertThat(mPreciseDataConnectionState.getDataConnectionState())
                .isIn(DATA_CONNECTION_STATE);
        // basic test to verify there is no exception thrown.
        mPreciseDataConnectionState.getDataConnectionApnTypeBitMask();
        mPreciseDataConnectionState.getDataConnectionApn();
        mPreciseDataConnectionState.getDataConnectionFailCause();
    }

    @Test
    public void testOnCallForwardingIndicatorChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onCallForwardingIndicatorChanged(boolean cfi) {
                        synchronized(mLock) {
                            mOnCallForwardingIndicatorChangedCalled = true;
                            mLock.notify();
                        }
                    }
                };
                mTelephonyManager.listen(
                        mListener, PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR);

                Looper.loop();
            }
        });

        assertFalse(mOnCallForwardingIndicatorChangedCalled);
        t.start();

        synchronized (mLock) {
            if (!mOnCallForwardingIndicatorChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mOnCallForwardingIndicatorChangedCalled);
    }

    @Test
    public void testOnCellLocationChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TelephonyManagerTest.grantLocationPermissions();

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onCellLocationChanged(CellLocation location) {
                        synchronized(mLock) {
                            mOnCellLocationChangedCalled = true;
                            mLock.notify();
                        }
                    }
                };
                mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_CELL_LOCATION);

                Looper.loop();
            }
        });

        assertFalse(mOnCellLocationChangedCalled);
        t.start();

        synchronized (mLock) {
            if (!mOnCellLocationChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mOnCellLocationChangedCalled);
    }

    @Test
    public void testOnCallStateChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String incomingNumber) {
                        synchronized(mLock) {
                            mOnCallStateChangedCalled = true;
                            mLock.notify();
                        }
                    }
                };
                mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_CALL_STATE);

                Looper.loop();
            }
        });

        assertFalse(mOnCallStateChangedCalled);
        t.start();

        synchronized (mLock) {
            if (!mOnCallStateChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mOnCallStateChangedCalled);
    }

    @Test
    public void testOnDataConnectionStateChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onDataConnectionStateChanged(int state) {
                        synchronized(mLock) {
                            mOnDataConnectionStateChangedCalled = true;
                            if (mOnDataConnectionStateChangedCalled
                                    && mOnDataConnectionStateChangedWithNetworkTypeCalled) {
                                mLock.notify();
                            }
                        }
                    }
                    @Override
                    public void onDataConnectionStateChanged(int state, int networkType) {
                        synchronized(mLock) {
                            mOnDataConnectionStateChangedWithNetworkTypeCalled = true;
                            if (mOnDataConnectionStateChangedCalled
                                    && mOnDataConnectionStateChangedWithNetworkTypeCalled) {
                                mLock.notify();
                            }
                        }
                    }
                };
                mTelephonyManager.listen(
                        mListener, PhoneStateListener.LISTEN_DATA_CONNECTION_STATE);

                Looper.loop();
            }
        });

        assertFalse(mOnDataConnectionStateChangedCalled);
        assertFalse(mOnDataConnectionStateChangedWithNetworkTypeCalled);
        t.start();

        synchronized (mLock) {
            if (!mOnDataConnectionStateChangedCalled ||
                    !mOnDataConnectionStateChangedWithNetworkTypeCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mOnDataConnectionStateChangedCalled);
        assertTrue(mOnDataConnectionStateChangedWithNetworkTypeCalled);
    }

    @Test
    public void testOnDataActivity() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onDataActivity(int direction) {
                        synchronized(mLock) {
                            mOnDataActivityCalled = true;
                            mLock.notify();
                        }
                    }
                };
                mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_DATA_ACTIVITY);

                Looper.loop();
            }
        });

        assertFalse(mOnDataActivityCalled);
        t.start();

        synchronized (mLock) {
            if (!mOnDataActivityCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mOnDataActivityCalled);
    }

    @Test
    public void testOnCellInfoChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TelephonyManagerTest.grantLocationPermissions();

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onCellInfoChanged(List<CellInfo> cellInfo) {
                        synchronized(mLock) {
                            mOnCellInfoChangedCalled = true;
                            mLock.notify();
                        }
                    }
                };
                mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_CELL_INFO);

                Looper.loop();
            }
        });

        assertFalse(mOnDataActivityCalled);
        t.start();

        synchronized (mLock) {
            if (!mOnCellInfoChangedCalled){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mOnCellInfoChangedCalled);
    }

    @Test
    public void testOnUserMobileDataStateChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onUserMobileDataStateChanged(boolean state) {
                        synchronized(mLock) {
                            mOnUserMobileDataStateChanged = true;
                            mLock.notify();
                        }
                    }
                };
                mTelephonyManager.listen(
                        mListener, PhoneStateListener.LISTEN_USER_MOBILE_DATA_STATE);

                Looper.loop();
            }
        });

        assertFalse(mOnUserMobileDataStateChanged);
        t.start();

        synchronized (mLock) {
            if (!mOnUserMobileDataStateChanged){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mOnUserMobileDataStateChanged);
    }

    @Test
    public void testOnActiveDataSubscriptionIdChanged() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onActiveDataSubscriptionIdChanged(int subId) {
                        synchronized(mLock) {
                            mOnActiveDataSubscriptionIdChanged = true;
                            mLock.notify();
                        }
                    }
                };
                mTelephonyManager.listen(
                        mListener, PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE);

                Looper.loop();
            }
        });

        assertFalse(mOnActiveDataSubscriptionIdChanged);
        t.start();

        synchronized (mLock) {
            if (!mOnActiveDataSubscriptionIdChanged){
                mLock.wait(WAIT_TIME);
            }
        }
        t.checkException();
        assertTrue(mOnActiveDataSubscriptionIdChanged);
    }
}
