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

package android.telephony.satellite.cts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.telephony.Rlog;
import android.telephony.cts.util.TelephonyUtils;
import android.telephony.satellite.stub.PointingInfo;
import android.telephony.satellite.stub.SatelliteDatagram;
import android.telephony.satellite.stub.SatelliteError;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * This class is responsible for connecting Telephony framework and CTS to the MockSatelliteService.
 */
class MockSatelliteServiceManager {
    private static final String TAG = "MockSatelliteServiceManager";
    private static final String PACKAGE = "android.telephony.cts";
    private static final String SET_SATELLITE_SERVICE_PACKAGE_NAME_CMD =
            "cmd phone set-satellite-service-package-name -s ";
    private static final String SET_SATELLITE_GATEWAY_SERVICE_PACKAGE_NAME_CMD =
            "cmd phone set-satellite-gateway-service-package-name -s ";
    private static final String SET_SATELLITE_LISTENING_TIMEOUT_DURATION_CMD =
            "cmd phone set-satellite-listening-timeout-duration -t ";
    private static final String SET_SATELLITE_POINTING_UI_CLASS_NAME_CMD =
            "cmd phone set-satellite-pointing-ui-class-name";
    private static final String SET_SATELLITE_DEVICE_ALIGN_TIMEOUT_DURATION_CMD =
            "cmd phone set-satellite-device-aligned-timeout-duration -t ";
    private static final long TIMEOUT = 5000;

    private MockSatelliteService mSatelliteService;
    private TestSatelliteServiceConnection mSatelliteServiceConn;
    private CountDownLatch mRemoteServiceConnectedLatch;
    private MockSatelliteGatewayService mSatelliteGatewayService;
    private TestSatelliteGatewayServiceConnection mSatelliteGatewayServiceConn;
    private final Semaphore mRemoteGatewayServiceConnectedSemaphore = new Semaphore(0);
    private final Semaphore mRemoteGatewayServiceDisconnectedSemaphore = new Semaphore(0);
    private Instrumentation mInstrumentation;
    private final Semaphore mStartSendingPointingInfoSemaphore = new Semaphore(0);
    private final Semaphore mStopSendingPointingInfoSemaphore = new Semaphore(0);
    private final Semaphore mPollPendingDatagramsSemaphore = new Semaphore(0);
    private final Semaphore mSendDatagramsSemaphore = new Semaphore(0);
    private List<SatelliteDatagram> mSentSatelliteDatagrams = new ArrayList<>();
    private List<Boolean> mSentIsEmergencyList = new ArrayList<>();
    private final Object mSendDatagramLock = new Object();
    private final Semaphore mListeningEnabledSemaphore = new Semaphore(0);
    private List<Boolean> mListeningEnabledList = new ArrayList<>();
    private final Object mListeningEnabledLock = new Object();
    private final Semaphore mMockPointingUiActivitySemaphore = new Semaphore(0);
    private final MockPointingUiActivityStatusReceiver mMockPointingUiActivityStatusReceiver =
            new MockPointingUiActivityStatusReceiver(mMockPointingUiActivitySemaphore);

    @NonNull
    private final ILocalSatelliteListener mSatelliteListener =
            new ILocalSatelliteListener.Stub() {
                @Override
                public void onRemoteServiceConnected() {
                    logd("onRemoteServiceConnected");
                    mRemoteServiceConnectedLatch.countDown();
                }

                @Override
                public void onStartSendingSatellitePointingInfo() {
                    logd("onStartSendingSatellitePointingInfo");
                    try {
                        mStartSendingPointingInfoSemaphore.release();
                    } catch (Exception ex) {
                        logd("onStartSendingSatellitePointingInfo: Got exception, ex=" + ex);
                    }
                }

                @Override
                public void onStopSendingSatellitePointingInfo() {
                    logd("onStopSendingSatellitePointingInfo");
                    try {
                        mStopSendingPointingInfoSemaphore.release();
                    } catch (Exception ex) {
                        logd("onStopSendingSatellitePointingInfo: Got exception, ex=" + ex);
                    }
                }

                @Override
                public void onPollPendingSatelliteDatagrams() {
                    logd("onPollPendingSatelliteDatagrams");
                    try {
                        mPollPendingDatagramsSemaphore.release();
                    } catch (Exception ex) {
                        logd("onPollPendingSatelliteDatagrams: Got exception, ex=" + ex);
                    }
                }

                @Override
                public void onSendSatelliteDatagram(
                        SatelliteDatagram datagram, boolean isEmergency) {
                    logd("onSendSatelliteDatagram");
                    synchronized (mSendDatagramLock) {
                        mSentSatelliteDatagrams.add(datagram);
                        mSentIsEmergencyList.add(isEmergency);
                    }
                    try {
                        mSendDatagramsSemaphore.release();
                    } catch (Exception ex) {
                        logd("onSendSatelliteDatagram: Got exception, ex=" + ex);
                    }
                }

                @Override
                public void onSatelliteListeningEnabled(boolean enabled) {
                    logd("onSatelliteListeningEnabled: enabled=" + enabled);
                    synchronized (mListeningEnabledLock) {
                        mListeningEnabledList.add(enabled);
                    }
                    try {
                        mListeningEnabledSemaphore.release();
                    } catch (Exception ex) {
                        logd("onSatelliteListeningEnabled: Got exception, ex=" + ex);
                    }
                }
            };

    @NonNull
    private final ILocalSatelliteGatewayListener mSatelliteGatewayListener =
            new ILocalSatelliteGatewayListener.Stub() {
                @Override
                public void onRemoteServiceConnected() {
                    logd("ILocalSatelliteGatewayListener: onRemoteServiceConnected");
                    mRemoteGatewayServiceConnectedSemaphore.release();
                }

                @Override
                public void onRemoteServiceDisconnected() {
                    logd("ILocalSatelliteGatewayListener: onRemoteServiceDisconnected");
                    mRemoteGatewayServiceDisconnectedSemaphore.release();
                }
            };

    private class TestSatelliteServiceConnection implements ServiceConnection {
        private final CountDownLatch mLatch;

        TestSatelliteServiceConnection(CountDownLatch latch) {
            mLatch = latch;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            logd("onServiceConnected");
            mSatelliteService = ((MockSatelliteService.LocalBinder) service).getService();
            mRemoteServiceConnectedLatch = new CountDownLatch(1);
            mSatelliteService.setLocalSatelliteListener(mSatelliteListener);
            mLatch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            logd("onServiceDisconnected");
            mSatelliteService = null;
        }
    }

    private class TestSatelliteGatewayServiceConnection implements ServiceConnection {
        private final CountDownLatch mLatch;

        TestSatelliteGatewayServiceConnection(CountDownLatch latch) {
            mLatch = latch;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            logd("GatewayService: onServiceConnected");
            mSatelliteGatewayService =
                    ((MockSatelliteGatewayService.LocalBinder) service).getService();
            mSatelliteGatewayService.setLocalSatelliteListener(mSatelliteGatewayListener);
            mLatch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            logd("GatewayService: onServiceDisconnected");
            mSatelliteGatewayService = null;
        }
    }

    MockSatelliteServiceManager(Instrumentation instrumentation) {
        mInstrumentation = instrumentation;
    }

    boolean connectSatelliteService() {
        logd("connectSatelliteService starting ...");
        if (!setupLocalSatelliteService()) {
            loge("Failed to set up local satellite service");
            return false;
        }

        try {
            if (!setSatelliteServicePackageName(PACKAGE)) {
                loge("Failed to set satellite service package name");
                return false;
            }
        } catch (Exception ex) {
            loge("connectSatelliteService: Got exception with setSatelliteServicePackageName "
                    + "ex=" + ex);
            return false;
        }

        // Wait for SatelliteModemInterface connecting to MockSatelliteService.
        try {
            boolean result = mRemoteServiceConnectedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS);
            if (!result) {
                loge("connectSatelliteService: latch await failed");
            }
            return result;
        } catch (InterruptedException e) {
            loge("connectSatelliteService: Got InterruptedException e=" + e);
            return false;
        }
    }

    boolean connectSatelliteGatewayService() {
        logd("connectSatelliteGatewayService starting ...");
        if (!setupLocalSatelliteGatewayService()) {
            loge("Failed to set up local satellite gateway service");
            return false;
        }

        try {
            if (!setSatelliteGatewayServicePackageName(PACKAGE)) {
                loge("Failed to set satellite gateway service package name");
                return false;
            }
        } catch (Exception ex) {
            loge("connectSatelliteGatewayService: Got exception with "
                    + "setSatelliteGatewayServicePackageName, ex=" + ex);
            return false;
        }
        return true;
    }

    boolean restoreSatelliteServicePackageName() {
        logd("restoreSatelliteServicePackageName");
        try {
            if (!setSatelliteServicePackageName(null)) {
                loge("Failed to restore satellite service package name");
                return false;
            }
        } catch (Exception ex) {
            loge("restoreSatelliteServicePackageName: Got exception with "
                    + "setSatelliteServicePackageName ex=" + ex);
            return false;
        }
        return true;
    }

    boolean restoreSatelliteGatewayServicePackageName() {
        logd("restoreSatelliteGatewayServicePackageName");
        try {
            if (!setSatelliteGatewayServicePackageName(null)) {
                loge("Failed to restore satellite gateway service package name");
                return false;
            }
        } catch (Exception ex) {
            loge("restoreSatelliteGatewayServicePackageName: Got exception with "
                    + "setSatelliteGatewayServicePackageName ex=" + ex);
            return false;
        }
        return true;
    }

    boolean overrideSatellitePointingUiClassName() {
        logd("overrideSatellitePointingUiClassName");
        try {
            if (!setSatellitePointingUiClassName(
                    PACKAGE, MockPointingUiActivity.class.getName())) {
                loge("Failed to override satellite pointing UI package and class names");
                return false;
            }
        } catch (Exception ex) {
            loge("overrideSatellitePointingUiClassName: Got exception with "
                    + "setSatellitePointingUiClassName ex=" + ex);
            return false;
        }
        registerForMockPointingUiActivityStatus();
        return true;
    }

    boolean restoreSatellitePointingUiClassName() {
        logd("restoreSatellitePointingUiClassName");
        try {
            if (!setSatellitePointingUiClassName(null, null)) {
                loge("Failed to restore satellite pointing UI package and class names");
                return false;
            }
        } catch (Exception ex) {
            loge("restoreSatellitePointingUiClassName: Got exception with "
                    + "setSatellitePointingUiClassName ex=" + ex);
            return false;
        }
        unregisterForMockPointingUiActivityStatus();
        return true;
    }

    Boolean getSentIsEmergency(int index) {
        synchronized (mSendDatagramLock) {
            if (index >= mSentIsEmergencyList.size()) return null;
            return mSentIsEmergencyList.get(index);
        }
    }

    SatelliteDatagram getSentSatelliteDatagram(int index) {
        synchronized (mSendDatagramLock) {
            if (index >= mSentSatelliteDatagrams.size()) return null;
            return mSentSatelliteDatagrams.get(index);
        }
    }

    void clearSentSatelliteDatagramInfo() {
        synchronized (mSendDatagramLock) {
            mSentSatelliteDatagrams.clear();
            mSentIsEmergencyList.clear();
            mSendDatagramsSemaphore.drainPermits();
        }
    }

    int getTotalCountOfSentSatelliteDatagrams() {
        synchronized (mSendDatagramLock) {
            return mSentSatelliteDatagrams.size();
        }
    }

    Boolean getListeningEnabled(int index) {
        synchronized (mListeningEnabledLock) {
            if (index >= mListeningEnabledList.size()) return null;
            return mListeningEnabledList.get(index);
        }
    }

    int getTotalCountOfListeningEnabledList() {
        synchronized (mListeningEnabledLock) {
            return mListeningEnabledList.size();
        }
    }

    void clearListeningEnabledList() {
        synchronized (mListeningEnabledLock) {
            mListeningEnabledList.clear();
            mListeningEnabledSemaphore.drainPermits();
        }
    }

    void clearMockPointingUiActivityStatusChanges() {
        mMockPointingUiActivitySemaphore.drainPermits();
    }

    boolean waitForEventOnStartSendingSatellitePointingInfo(int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!mStartSendingPointingInfoSemaphore.tryAcquire(
                        TIMEOUT, TimeUnit.MILLISECONDS)) {
                    loge("Timeout to receive onStartSendingSatellitePointingInfo");
                    return false;
                }
            } catch (Exception ex) {
                loge("onStartSendingSatellitePointingInfo: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    boolean waitForEventOnStopSendingSatellitePointingInfo(int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!mStopSendingPointingInfoSemaphore.tryAcquire(
                        TIMEOUT, TimeUnit.MILLISECONDS)) {
                    loge("Timeout to receive onStopSendingSatellitePointingInfo");
                    return false;
                }
            } catch (Exception ex) {
                loge("onStopSendingSatellitePointingInfo: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    boolean waitForEventOnPollPendingSatelliteDatagrams(int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!mPollPendingDatagramsSemaphore.tryAcquire(
                        TIMEOUT, TimeUnit.MILLISECONDS)) {
                    loge("Timeout to receive onPollPendingSatelliteDatagrams");
                    return false;
                }
            } catch (Exception ex) {
                loge("onPollPendingSatelliteDatagrams: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    boolean waitForEventOnSendSatelliteDatagram(int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!mSendDatagramsSemaphore.tryAcquire(
                        TIMEOUT, TimeUnit.MILLISECONDS)) {
                    loge("Timeout to receive onSendSatelliteDatagram");
                    return false;
                }
            } catch (Exception ex) {
                loge("onSendSatelliteDatagram: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    boolean waitForEventOnSatelliteListeningEnabled(int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!mListeningEnabledSemaphore.tryAcquire(
                        TIMEOUT, TimeUnit.MILLISECONDS)) {
                    loge("Timeout to receive onSatelliteListeningEnabled");
                    return false;
                }
            } catch (Exception ex) {
                loge("onSatelliteListeningEnabled: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    boolean waitForRemoteSatelliteGatewayServiceConnected(int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!mRemoteGatewayServiceConnectedSemaphore.tryAcquire(
                        TIMEOUT, TimeUnit.MILLISECONDS)) {
                    loge("Timeout to receive RemoteSatelliteGatewayServiceConnected");
                    return false;
                }
            } catch (Exception ex) {
                loge("RemoteSatelliteGatewayServiceConnected: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    boolean waitForRemoteSatelliteGatewayServiceDisconnected(int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!mRemoteGatewayServiceDisconnectedSemaphore.tryAcquire(
                        TIMEOUT, TimeUnit.MILLISECONDS)) {
                    loge("Timeout to receive RemoteGatewayServiceDisconnected");
                    return false;
                }
            } catch (Exception ex) {
                loge("RemoteGatewayServiceDisconnected: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    boolean waitForEventMockPointingUiActivityStarted(int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!mMockPointingUiActivitySemaphore.tryAcquire(
                        TIMEOUT, TimeUnit.MILLISECONDS)) {
                    loge("Timeout to receive MockPointingUiActivityStarted");
                    return false;
                }
            } catch (Exception ex) {
                loge("MockPointingUiActivityStarted: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    void setErrorCode(@SatelliteError int errorCode) {
        logd("setErrorCode: errorCode=" + errorCode);
        if (mSatelliteService == null) {
            loge("setErrorCode: mSatelliteService is null");
            return;
        }
        mSatelliteService.setErrorCode(errorCode);
    }

    void setSatelliteSupport(boolean supported) {
        logd("setSatelliteSupport: supported=" + supported);
        if (mSatelliteService == null) {
            loge("setErrorCode: mSatelliteService is null");
            return;
        }
        mSatelliteService.setSatelliteSupport(supported);
    }

    public void setShouldRespondTelephony(boolean shouldRespondTelephony) {
        logd("setShouldRespondTelephony: shouldRespondTelephony=" + shouldRespondTelephony);
        if (mSatelliteService == null) {
            loge("setShouldRespondTelephony: mSatelliteService is null");
            return;
        }
        mSatelliteService.setShouldRespondTelephony(shouldRespondTelephony);
    }

    void sendOnSatelliteDatagramReceived(SatelliteDatagram datagram, int pendingCount) {
        logd("sendOnSatelliteDatagramReceived");
        if (mSatelliteService == null) {
            loge("setErrorCode: mSatelliteService is null");
            return;
        }
        mSatelliteService.sendOnSatelliteDatagramReceived(datagram, pendingCount);
    }

    void sendOnPendingDatagrams() {
        logd("sendOnPendingDatagrams");
        if (mSatelliteService == null) {
            loge("setErrorCode: mSatelliteService is null");
            return;
        }
        mSatelliteService.sendOnPendingDatagrams();
    }

    void sendOnSatellitePositionChanged(PointingInfo pointingInfo) {
        logd("sendOnSatellitePositionChanged");
        mSatelliteService.sendOnSatellitePositionChanged(pointingInfo);
    }

    boolean setSatelliteListeningTimeoutDuration(long timeoutMillis) {
        try {
            String result =
                    TelephonyUtils.executeShellCommand(mInstrumentation,
                            SET_SATELLITE_LISTENING_TIMEOUT_DURATION_CMD + timeoutMillis);
            logd("setSatelliteListeningTimeoutDuration: result = " + result);
            return "true".equals(result);
        } catch (Exception e) {
            loge("setSatelliteListeningTimeoutDuration: e=" + e);
            return false;
        }
    }

    boolean setSatelliteDeviceAlignedTimeoutDuration(long timeoutMillis) {
        try {
            String result =
                    TelephonyUtils.executeShellCommand(mInstrumentation,
                            SET_SATELLITE_DEVICE_ALIGN_TIMEOUT_DURATION_CMD + timeoutMillis);
            logd("setDeviceAlignedTimeoutDuration: result = " + result);
            return "true".equals(result);
        } catch (Exception e) {
            loge("setDeviceAlignedTimeoutDuration: e=" + e);
            return false;
        }
    }

    void setWaitToSend(boolean wait) {
        logd("setWaitToSend: wait= " + wait);
        if (mSatelliteService == null) {
            loge("setWaitToSend: mSatelliteService is null");
            return;
        }
        mSatelliteService.setWaitToSend(wait);
    }

    boolean sendSavedDatagram() {
        logd("sendSavedDatagram");
        if (mSatelliteService == null) {
            loge("sendSavedDatagram: mSatelliteService is null");
            return false;
        }
        return mSatelliteService.sendSavedDatagram();
    }

    private boolean setupLocalSatelliteService() {
        if (mSatelliteService != null) {
            logd("setupLocalSatelliteService: local service is already set up");
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        mSatelliteServiceConn = new TestSatelliteServiceConnection(latch);
        mInstrumentation.getContext().bindService(new Intent(mInstrumentation.getContext(),
                MockSatelliteService.class), mSatelliteServiceConn, Context.BIND_AUTO_CREATE);
        try {
            return latch.await(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            loge("setupLocalSatelliteService: Got InterruptedException e=" + e);
            return false;
        }
    }

    private boolean setupLocalSatelliteGatewayService() {
        if (mSatelliteGatewayService != null) {
            logd("setupLocalSatelliteGatewayService: local service is already set up");
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        mSatelliteGatewayServiceConn = new TestSatelliteGatewayServiceConnection(latch);
        mInstrumentation.getContext().bindService(new Intent(mInstrumentation.getContext(),
                MockSatelliteGatewayService.class), mSatelliteGatewayServiceConn,
                Context.BIND_AUTO_CREATE);
        try {
            return latch.await(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            loge("setupLocalSatelliteGatewayService: Got InterruptedException e=" + e);
            return false;
        }
    }

    private boolean setSatelliteServicePackageName(@Nullable String packageName) throws Exception {
        String result =
                TelephonyUtils.executeShellCommand(
                        mInstrumentation, SET_SATELLITE_SERVICE_PACKAGE_NAME_CMD + packageName);
        logd("setSatelliteServicePackageName: result = " + result);
        return "true".equals(result);
    }

    private boolean setSatelliteGatewayServicePackageName(
            @Nullable String packageName) throws Exception {
        String result =
                TelephonyUtils.executeShellCommand(mInstrumentation,
                        SET_SATELLITE_GATEWAY_SERVICE_PACKAGE_NAME_CMD + packageName);
        logd("setSatelliteGatewayServicePackageName: result = " + result);
        return "true".equals(result);
    }

    private boolean setSatellitePointingUiClassName(
            @Nullable String packageName, @Nullable String className) throws Exception {
        String result =
                TelephonyUtils.executeShellCommand(mInstrumentation,
                        SET_SATELLITE_POINTING_UI_CLASS_NAME_CMD + " -p " + packageName
                                + " -c " + className);
        logd("setSatellitePointingUiClassName: result = " + result);
        return "true".equals(result);
    }

    private void registerForMockPointingUiActivityStatus() {
        IntentFilter intentFilter = new IntentFilter(
                MockPointingUiActivity.ACTION_MOCK_POINTING_UI_ACTIVITY_STARTED);
        mInstrumentation.getContext().registerReceiver(
                mMockPointingUiActivityStatusReceiver, intentFilter, Context.RECEIVER_EXPORTED);
    }

    private void unregisterForMockPointingUiActivityStatus() {
        mInstrumentation.getContext().unregisterReceiver(mMockPointingUiActivityStatusReceiver);
    }

    private static void logd(@NonNull String log) {
        Rlog.d(TAG, log);
    }

    private static void loge(@NonNull String log) {
        Rlog.e(TAG, log);
    }

    private static class MockPointingUiActivityStatusReceiver extends BroadcastReceiver {
        private Semaphore mSemaphore;

        MockPointingUiActivityStatusReceiver(Semaphore semaphore) {
            mSemaphore = semaphore;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (MockPointingUiActivity.ACTION_MOCK_POINTING_UI_ACTIVITY_STARTED.equals(
                    intent.getAction())) {
                logd("MockPointingUiActivityStatusReceiver: onReceive");
                try {
                    mSemaphore.release();
                } catch (Exception ex) {
                    loge("MockPointingUiActivityStatusReceiver: Got exception, ex=" + ex);
                }
            }
        }
    }
}
