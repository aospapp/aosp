/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.device.cloud;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IDevice.DeviceState;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.IDeviceMonitor;
import com.android.tradefed.device.IDeviceStateMonitor;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.RemoteAndroidDevice;
import com.android.tradefed.device.RemoteAvdIDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.device.TestDeviceOptions.InstanceType;
import com.android.tradefed.device.cloud.GceAvdInfo.GceStatus;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestLoggerReceiver;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HostAndPort;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Extends {@link RemoteAndroidDevice} behavior for a full stack android device running in the
 * Google Compute Engine (Gce). Assume the device serial will be in the format
 * <hostname>:<portnumber> in adb.
 */
public class RemoteAndroidVirtualDevice extends RemoteAndroidDevice implements ITestLoggerReceiver {

    /** The directory where to find debug logs for a nested remote instance. */
    public static final String NESTED_REMOTE_LOG_DIR = "${HOME}/../vsoc-01/cuttlefish_runtime/";
    /** The directory where to find debug logs for an emulator instance. */
    public static final String EMULATOR_REMOTE_LOG_DIR = "/home/vsoc-01/log/";

    private String mInitialSerial;
    private GceAvdInfo mGceAvd;
    private ITestLogger mTestLogger;

    private GceManager mGceHandler = null;
    private GceSshTunnelMonitor mGceSshMonitor;

    private static final long WAIT_FOR_TUNNEL_ONLINE = 2 * 60 * 1000;
    private static final long WAIT_AFTER_REBOOT = 60 * 1000;
    private static final long WAIT_FOR_TUNNEL_OFFLINE = 5 * 1000;
    private static final int WAIT_TIME_DIVISION = 4;

    /**
     * Creates a {@link RemoteAndroidVirtualDevice}.
     *
     * @param device the associated {@link IDevice}
     * @param stateMonitor the {@link IDeviceStateMonitor} mechanism to use
     * @param allocationMonitor the {@link IDeviceMonitor} to inform of allocation state changes.
     */
    public RemoteAndroidVirtualDevice(
            IDevice device, IDeviceStateMonitor stateMonitor, IDeviceMonitor allocationMonitor) {
        super(device, stateMonitor, allocationMonitor);
    }

    /** {@inheritDoc} */
    @Override
    public void preInvocationSetup(IBuildInfo info)
            throws TargetSetupError, DeviceNotAvailableException {
        preInvocationSetup(info, null);
    }

    /** {@inheritDoc} */
    @Override
    public void preInvocationSetup(IBuildInfo info, List<IBuildInfo> testResourceBuildInfos)
            throws TargetSetupError, DeviceNotAvailableException {
        try {
            mGceAvd = null;
            mGceSshMonitor = null;
            // We create a brand new GceManager each time to ensure clean state.
            mGceHandler =
                    new GceManager(
                            getDeviceDescriptor(), getOptions(), info, testResourceBuildInfos);
            getGceHandler().logStableHostImageInfos(info);
            mInitialSerial = getSerialNumber();
            setFastbootEnabled(false);

            // Launch GCE helper script.
            long startTime = getCurrentTime();
            launchGce(info);
            long remainingTime = getOptions().getGceCmdTimeout() - (getCurrentTime() - startTime);
            if (remainingTime < 0) {
                throw new DeviceNotAvailableException(
                        String.format(
                                "Failed to launch GCE after %sms", getOptions().getGceCmdTimeout()),
                        getSerialNumber());
            }
            CLog.d("%sms left before timeout after GCE launch returned", remainingTime);
            // Wait for device to be ready.
            RecoveryMode previousMode = getRecoveryMode();
            setRecoveryMode(RecoveryMode.NONE);
            try {
                for (int i = 0; i < WAIT_TIME_DIVISION; i++) {
                    // We don't have a way to bail out of waitForDeviceAvailable if the Gce Avd
                    // boot up and then fail some other setup so we check to make sure the monitor
                    // thread is alive and we have an opportunity to abort and avoid wasting time.
                    if (getMonitor().waitForDeviceAvailable(remainingTime / WAIT_TIME_DIVISION)
                            != null) {
                        break;
                    }
                    waitForTunnelOnline(WAIT_FOR_TUNNEL_ONLINE);
                    waitForAdbConnect(WAIT_FOR_ADB_CONNECT);
                }
            } finally {
                setRecoveryMode(previousMode);
            }
            if (!DeviceState.ONLINE.equals(getIDevice().getState())) {
                if (mGceAvd != null && GceStatus.SUCCESS.equals(mGceAvd.getStatus())) {
                    // Update status to reflect that we were not able to connect to it.
                    mGceAvd.setStatus(GceStatus.DEVICE_OFFLINE);
                }
                throw new DeviceNotAvailableException(
                        String.format(
                                "AVD device booted but was in %s state", getIDevice().getState()),
                        getSerialNumber());
            }
            enableAdbRoot();
        } catch (DeviceNotAvailableException | TargetSetupError e) {
            throw e;
        }
        // make sure we start logcat directly, device is up.
        setLogStartDelay(0);
        // For virtual device we only start logcat collection after we are sure it's online.
        if (getOptions().isLogcatCaptureEnabled()) {
            startLogcat();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void postInvocationTearDown() {
        try {
            CLog.i("Invocation tear down for device %s", getSerialNumber());
            // Log the last part of the logcat from the tear down.
            if (!(getIDevice() instanceof StubDevice)) {
                try (InputStreamSource logcatSource = getLogcat()) {
                    clearLogcat();
                    String name = "device_logcat_teardown_gce";
                    mTestLogger.testLog(name, LogDataType.LOGCAT, logcatSource);
                }
            }
            stopLogcat();
            // Terminate SSH tunnel process.
            if (getGceSshMonitor() != null) {
                getGceSshMonitor().logSshTunnelLogs(mTestLogger);
                getGceSshMonitor().shutdown();
                try {
                    getGceSshMonitor().joinMonitor();
                } catch (InterruptedException e1) {
                    CLog.i("Interrupted while waiting for GCE SSH monitor to shutdown.");
                }
                // We are done with the monitor, clean it to prevent re-entry.
                mGceSshMonitor = null;
            }
            if (!waitForDeviceNotAvailable(DEFAULT_SHORT_CMD_TIMEOUT)) {
                CLog.w("Device %s still available after timeout.", getSerialNumber());
            }

            if (mGceAvd != null) {
                // attempt to get a bugreport if Gce Avd is a failure
                if (!GceStatus.SUCCESS.equals(mGceAvd.getStatus())) {
                    // Get a bugreport via ssh
                    getSshBugreport();
                }
                // Log the serial output of the instance.
                getGceHandler().logSerialOutput(mGceAvd, mTestLogger);

                // Fetch remote files
                CommonLogRemoteFileUtil.fetchCommonFiles(
                        mTestLogger, mGceAvd, getOptions(), getRunUtil());

                // Cleanup GCE first to make sure ssh tunnel has nowhere to go.
                if (!getOptions().shouldSkipTearDown()) {
                    getGceHandler().shutdownGce();
                }
                // We are done with the gce related information, clean it to prevent re-entry.
                mGceAvd = null;
            }

            if (mInitialSerial != null) {
                setIDevice(new RemoteAvdIDevice(mInitialSerial));
            }
            setFastbootEnabled(false);

            if (getGceHandler() != null) {
                getGceHandler().cleanUp();
            }
        } finally {
            // Ensure parent postInvocationTearDown is always called.
            super.postInvocationTearDown();
        }
    }

    /** Capture a remote bugreport by ssh-ing into the device directly. */
    private void getSshBugreport() {
        InstanceType type = getOptions().getInstanceType();
        File bugreportFile = null;
        try {
            if (InstanceType.GCE.equals(type) || InstanceType.REMOTE_AVD.equals(type)) {
                bugreportFile =
                        GceManager.getBugreportzWithSsh(mGceAvd, getOptions(), getRunUtil());
            } else {
                bugreportFile =
                        GceManager.getNestedDeviceSshBugreportz(
                                mGceAvd, getOptions(), getRunUtil());
            }
            if (bugreportFile != null) {
                InputStreamSource bugreport = new FileInputStreamSource(bugreportFile);
                mTestLogger.testLog("bugreportz-ssh", LogDataType.BUGREPORTZ, bugreport);
                StreamUtil.cancel(bugreport);
            }
        } catch (IOException e) {
            CLog.e(e);
        } finally {
            FileUtil.deleteFile(bugreportFile);
        }
    }

    /** Launch the actual gce device based on the build info. */
    protected void launchGce(IBuildInfo buildInfo) throws TargetSetupError {
        TargetSetupError exception = null;
        for (int attempt = 0; attempt < getOptions().getGceMaxAttempt(); attempt++) {
            try {
                mGceAvd = getGceHandler().startGce();
                if (mGceAvd != null) break;
            } catch (TargetSetupError tse) {
                CLog.w(
                        "Failed to start Gce with attempt: %s out of %s. With Exception: %s",
                        attempt + 1, getOptions().getGceMaxAttempt(), tse);
                exception = tse;
            }
        }
        if (mGceAvd == null) {
            throw exception;
        } else {
            CLog.i("GCE AVD has been started: %s", mGceAvd);
            if (GceAvdInfo.GceStatus.BOOT_FAIL.equals(mGceAvd.getStatus())) {
                throw new TargetSetupError(mGceAvd.getErrors(), getDeviceDescriptor());
            }
        }
        createGceSshMonitor(this, buildInfo, mGceAvd.hostAndPort(), this.getOptions());
    }

    /** Create an ssh tunnel, connect to it, and keep the connection alive. */
    void createGceSshMonitor(
            ITestDevice device,
            IBuildInfo buildInfo,
            HostAndPort hostAndPort,
            TestDeviceOptions deviceOptions) {
        mGceSshMonitor = new GceSshTunnelMonitor(device, buildInfo, hostAndPort, deviceOptions);
        mGceSshMonitor.start();
    }

    @Override
    public void setTestLogger(ITestLogger testLogger) {
        mTestLogger = testLogger;
    }

    /** {@inherit} */
    @Override
    public void postBootSetup() throws DeviceNotAvailableException {
        // After reboot, restart the tunnel
        if (!getOptions().shouldDisableReboot()) {
            CLog.v("Performing post boot setup for GCE AVD %s", getSerialNumber());
            getRunUtil().sleep(WAIT_FOR_TUNNEL_OFFLINE);
            if (!getGceSshMonitor().isTunnelAlive()) {
                getGceSshMonitor().closeConnection();
                getRunUtil().sleep(WAIT_FOR_TUNNEL_OFFLINE);
                waitForTunnelOnline(WAIT_FOR_TUNNEL_ONLINE);
            }
            waitForAdbConnect(WAIT_FOR_ADB_CONNECT);
        }
        super.postBootSetup();
    }

    /** Check if the tunnel monitor is running. */
    protected void waitForTunnelOnline(final long waitTime) throws DeviceNotAvailableException {
        CLog.i("Waiting %d ms for tunnel to be restarted", waitTime);
        long startTime = getCurrentTime();
        while (getCurrentTime() - startTime < waitTime) {
            if (getGceSshMonitor() == null) {
                CLog.e("Tunnel Thread terminated, something went wrong with the device.");
                break;
            }
            if (getGceSshMonitor().isTunnelAlive()) {
                CLog.d("Tunnel online again, resuming.");
                return;
            }
            getRunUtil().sleep(RETRY_INTERVAL_MS);
        }
        throw new DeviceNotAvailableException(
                String.format("Tunnel did not come back online after %sms", waitTime),
                getSerialNumber());
    }

    @Override
    public void recoverDevice() throws DeviceNotAvailableException {
        // Re-init tunnel when attempting recovery
        CLog.i("Attempting recovery on GCE AVD %s", getSerialNumber());
        getGceSshMonitor().closeConnection();
        getRunUtil().sleep(WAIT_FOR_TUNNEL_OFFLINE);
        waitForTunnelOnline(WAIT_FOR_TUNNEL_ONLINE);
        waitForAdbConnect(WAIT_FOR_ADB_CONNECT);
        // Then attempt regular recovery
        super.recoverDevice();
    }

    @Override
    protected void doAdbReboot(String into) throws DeviceNotAvailableException {
        // We catch that adb reboot is called to expect it from the tunnel.
        getGceSshMonitor().isAdbRebootCalled(true);
        super.doAdbReboot(into);
        // We allow a little time for instance to reboot and be reachable.
        getRunUtil().sleep(WAIT_AFTER_REBOOT);
        // after the reboot we wait for tunnel to be online and device to be reconnected
        getRunUtil().sleep(WAIT_FOR_TUNNEL_OFFLINE);
        waitForTunnelOnline(WAIT_FOR_TUNNEL_ONLINE);
        waitForAdbConnect(WAIT_FOR_ADB_CONNECT);
    }

    /**
     * Returns the {@link com.android.tradefed.device.cloud.GceSshTunnelMonitor} of the device.
     * Exposed for testing.
     */
    protected GceSshTunnelMonitor getGceSshMonitor() {
        return mGceSshMonitor;
    }

    /** Returns the current system time. Exposed for testing. */
    protected long getCurrentTime() {
        return System.currentTimeMillis();
    }

    /** Returns the instance of the {@link com.android.tradefed.device.cloud.GceManager}. */
    @VisibleForTesting
    GceManager getGceHandler() {
        return mGceHandler;
    }
}
