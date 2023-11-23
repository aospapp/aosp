/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_STARTING;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_STOPPED;
import static android.content.Intent.ACTION_USER_REMOVED;

import static com.android.car.CarLog.TAG_WATCHDOG;

import android.annotation.NonNull;
import android.automotive.watchdog.internal.GarageMode;
import android.automotive.watchdog.internal.ICarWatchdogServiceForSystem;
import android.automotive.watchdog.internal.PackageInfo;
import android.automotive.watchdog.internal.PackageIoOveruseStats;
import android.automotive.watchdog.internal.PowerCycle;
import android.automotive.watchdog.internal.StateType;
import android.automotive.watchdog.internal.UserPackageIoUsageStats;
import android.automotive.watchdog.internal.UserState;
import android.car.Car;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.car.hardware.power.CarPowerPolicy;
import android.car.hardware.power.CarPowerPolicyFilter;
import android.car.hardware.power.ICarPowerPolicyListener;
import android.car.hardware.power.ICarPowerStateListener;
import android.car.hardware.power.PowerComponent;
import android.car.watchdog.CarWatchdogManager;
import android.car.watchdog.ICarWatchdogService;
import android.car.watchdog.ICarWatchdogServiceCallback;
import android.car.watchdog.IResourceOveruseListener;
import android.car.watchdog.PackageKillableState;
import android.car.watchdog.ResourceOveruseConfiguration;
import android.car.watchdog.ResourceOveruseStats;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;

import com.android.car.CarLocalServices;
import com.android.car.CarLog;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.ICarImpl;
import com.android.car.power.CarPowerManagementService;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.user.CarUserService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.utils.Slogf;

import java.io.File;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.List;

/**
 * Service to implement CarWatchdogManager API.
 */
public final class CarWatchdogService extends ICarWatchdogService.Stub implements CarServiceBase {
    static final boolean DEBUG = false; // STOPSHIP if true
    static final String TAG = CarLog.tagFor(CarWatchdogService.class);
    static final String ACTION_GARAGE_MODE_ON =
            "com.android.server.jobscheduler.GARAGE_MODE_ON";
    static final String ACTION_GARAGE_MODE_OFF =
            "com.android.server.jobscheduler.GARAGE_MODE_OFF";
    static final String ACTION_LAUNCH_APP_SETTINGS =
            "com.android.car.watchdog.ACTION_LAUNCH_APP_SETTINGS";
    static final String ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION =
            "com.android.car.watchdog.ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION";
    static final String ACTION_RESOURCE_OVERUSE_DISABLE_APP =
            "com.android.car.watchdog.ACTION_RESOURCE_OVERUSE_DISABLE_APP";

    @VisibleForTesting
    static final int MISSING_ARG_VALUE = -1;

    private static final String FALLBACK_DATA_SYSTEM_CAR_DIR_PATH = "/data/system/car";
    private static final String WATCHDOG_DIR_NAME = "watchdog";

    private static final TimeSource SYSTEM_INSTANCE = new TimeSource() {
        @Override
        public Instant now() {
            return Instant.now();
        }

        @Override
        public String toString() {
            return "System time instance";
        }
    };

    private final Context mContext;
    private final ICarWatchdogServiceForSystemImpl mWatchdogServiceForSystem;
    private final PackageInfoHandler mPackageInfoHandler;
    private final WatchdogStorage mWatchdogStorage;
    private final WatchdogProcessHandler mWatchdogProcessHandler;
    private final WatchdogPerfHandler mWatchdogPerfHandler;
    private final CarWatchdogDaemonHelper mCarWatchdogDaemonHelper;
    private final CarWatchdogDaemonHelper.OnConnectionChangeListener mConnectionListener;
    /*
     * TODO(b/192481350): Listen for GarageMode change notification rather than depending on the
     *  system_server broadcast when the CarService internal API for listening GarageMode change is
     *  implemented.
     */
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION:
                case ACTION_LAUNCH_APP_SETTINGS:
                case ACTION_RESOURCE_OVERUSE_DISABLE_APP:
                    mWatchdogPerfHandler.handleIntent(context, intent);
                    break;
                case ACTION_GARAGE_MODE_ON:
                case ACTION_GARAGE_MODE_OFF:
                    int garageMode;
                    synchronized (mLock) {
                        garageMode = mCurrentGarageMode = action.equals(ACTION_GARAGE_MODE_ON)
                                ? GarageMode.GARAGE_MODE_ON : GarageMode.GARAGE_MODE_OFF;
                    }
                    mWatchdogPerfHandler.onGarageModeChange(garageMode);
                    if (garageMode == GarageMode.GARAGE_MODE_ON) {
                        mWatchdogStorage.shrinkDatabase();
                    }
                    notifyGarageModeChange(garageMode);
                    return;
                case ACTION_USER_REMOVED:
                    UserHandle userHandle = intent.getParcelableExtra(Intent.EXTRA_USER);
                    int userId = userHandle.getIdentifier();
                    try {
                        mCarWatchdogDaemonHelper.notifySystemStateChange(StateType.USER_STATE,
                                userId, UserState.USER_STATE_REMOVED);
                        if (DEBUG) {
                            Slogf.d(TAG, "Notified car watchdog daemon of removed user %d",
                                    userId);
                        }
                    } catch (RemoteException e) {
                        Slogf.w(TAG, e, "Failed to notify car watchdog daemon of removed user %d",
                                userId);
                    }
                    mWatchdogPerfHandler.deleteUser(userId);
                    return;
            }
        }
    };

    private final ICarPowerStateListener mCarPowerStateListener =
            new ICarPowerStateListener.Stub() {
        @Override
        public void onStateChanged(int state) {
            CarPowerManagementService powerService =
                    CarLocalServices.getService(CarPowerManagementService.class);
            if (powerService == null) {
                return;
            }
            int powerCycle = carPowerStateToPowerCycle(powerService.getPowerState());
            switch (powerCycle) {
                case PowerCycle.POWER_CYCLE_SHUTDOWN_PREPARE:
                    // Perform time consuming disk I/O operation during shutdown prepare to avoid
                    // incomplete I/O.
                    mWatchdogPerfHandler.writeMetadataFile();
                    break;
                case PowerCycle.POWER_CYCLE_SHUTDOWN_ENTER:
                    // Watchdog service and daemon performs garage mode monitoring so delay writing
                    // to database until after shutdown enter.
                    mWatchdogPerfHandler.writeToDatabase();
                    break;
                // ON covers resume.
                case PowerCycle.POWER_CYCLE_RESUME:
                    // There might be outdated & incorrect info. We should reset them before
                    // starting to do health check.
                    mWatchdogProcessHandler.prepareHealthCheck();
                    break;
                default:
                    return;
            }
            notifyPowerCycleChange(powerCycle);
        }
    };

    private final ICarPowerPolicyListener mCarDisplayPowerPolicyListener =
            new ICarPowerPolicyListener.Stub() {
                @Override
                public void onPolicyChanged(CarPowerPolicy appliedPolicy,
                        CarPowerPolicy accumulatedPolicy) {
                    boolean isDisplayEnabled =
                            appliedPolicy.isComponentEnabled(PowerComponent.DISPLAY);
                    boolean didStateChange = false;
                    synchronized (mLock) {
                        didStateChange = mIsDisplayEnabled != isDisplayEnabled;
                        mIsDisplayEnabled = isDisplayEnabled;
                    }
                    if (didStateChange) {
                        mWatchdogPerfHandler.onDisplayStateChanged(isDisplayEnabled);
                    }
                }
            };

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private boolean mReadyToRespond;
    @GuardedBy("mLock")
    private boolean mIsConnected;
    @GuardedBy("mLock")
    private @GarageMode int mCurrentGarageMode;
    @GuardedBy("mLock")
    private boolean mIsDisplayEnabled;

    public CarWatchdogService(Context context) {
        this(context, new WatchdogStorage(context, SYSTEM_INSTANCE),
                new UserNotificationHelper(context), SYSTEM_INSTANCE);
    }

    @VisibleForTesting
    CarWatchdogService(Context context, WatchdogStorage watchdogStorage,
            UserNotificationHelper userNotificationHelper, TimeSource timeSource) {
        mContext = context;
        mWatchdogStorage = watchdogStorage;
        mPackageInfoHandler = new PackageInfoHandler(mContext.getPackageManager());
        mCarWatchdogDaemonHelper = new CarWatchdogDaemonHelper(TAG_WATCHDOG);
        mWatchdogServiceForSystem = new ICarWatchdogServiceForSystemImpl(this);
        mWatchdogProcessHandler = new WatchdogProcessHandler(mWatchdogServiceForSystem,
                mCarWatchdogDaemonHelper);
        mWatchdogPerfHandler = new WatchdogPerfHandler(mContext, mCarWatchdogDaemonHelper,
                mPackageInfoHandler, mWatchdogStorage, userNotificationHelper, timeSource);
        mConnectionListener = (isConnected) -> {
            mWatchdogPerfHandler.onDaemonConnectionChange(isConnected);
            synchronized (mLock) {
                mIsConnected = isConnected;
            }
            registerToDaemon();
        };
        mCurrentGarageMode = GarageMode.GARAGE_MODE_OFF;
        mIsDisplayEnabled = true;
    }

    @Override
    public void init() {
        mWatchdogProcessHandler.init();
        mWatchdogPerfHandler.init();
        subscribePowerManagementService();
        subscribeUserStateChange();
        subscribeBroadcastReceiver();
        mCarWatchdogDaemonHelper.addOnConnectionChangeListener(mConnectionListener);
        mCarWatchdogDaemonHelper.connect();
        // To make sure the main handler is ready for responding to car watchdog daemon, registering
        // to the daemon is done through the main handler. Once the registration is completed, we
        // can assume that the main handler is not too busy handling other stuffs.
        postRegisterToDaemonMessage();
        if (DEBUG) {
            Slogf.d(TAG, "CarWatchdogService is initialized");
        }
    }

    @Override
    public void release() {
        mContext.unregisterReceiver(mBroadcastReceiver);
        unsubscribePowerManagementService();
        mWatchdogPerfHandler.release();
        mWatchdogStorage.release();
        unregisterFromDaemon();
        mCarWatchdogDaemonHelper.disconnect();
    }

    @Override
    public void dump(IndentingPrintWriter writer) {
        writer.println("*" + getClass().getSimpleName() + "*");
        writer.increaseIndent();
        synchronized (mLock) {
            writer.println("Current garage mode: " + toGarageModeString(mCurrentGarageMode));
        }
        mWatchdogProcessHandler.dump(writer);
        mWatchdogPerfHandler.dump(writer);
        writer.decreaseIndent();
    }

    /**
     * Registers {@link android.car.watchdog.ICarWatchdogServiceCallback} to
     * {@link CarWatchdogService}.
     */
    @Override
    public void registerClient(ICarWatchdogServiceCallback client, int timeout) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_USE_CAR_WATCHDOG);
        mWatchdogProcessHandler.registerClient(client, timeout);
    }

    /**
     * Unregisters {@link android.car.watchdog.ICarWatchdogServiceCallback} from
     * {@link CarWatchdogService}.
     */
    @Override
    public void unregisterClient(ICarWatchdogServiceCallback client) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_USE_CAR_WATCHDOG);
        mWatchdogProcessHandler.unregisterClient(client);
    }

    /**
     * Tells {@link CarWatchdogService} that the client is alive.
     */
    @Override
    public void tellClientAlive(ICarWatchdogServiceCallback client, int sessionId) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_USE_CAR_WATCHDOG);
        mWatchdogProcessHandler.tellClientAlive(client, sessionId);
    }

    /** Returns {@link android.car.watchdog.ResourceOveruseStats} for the calling package. */
    @Override
    @NonNull
    public ResourceOveruseStats getResourceOveruseStats(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        return mWatchdogPerfHandler.getResourceOveruseStats(resourceOveruseFlag, maxStatsPeriod);
    }

    /**
      *  Returns {@link android.car.watchdog.ResourceOveruseStats} for all packages for the maximum
      *  specified period, and the specified resource types with stats greater than or equal to the
      *  minimum specified stats.
      */
    @Override
    @NonNull
    public List<ResourceOveruseStats> getAllResourceOveruseStats(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.MinimumStatsFlag int minimumStatsFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_COLLECT_CAR_WATCHDOG_METRICS);
        return mWatchdogPerfHandler.getAllResourceOveruseStats(resourceOveruseFlag,
                minimumStatsFlag, maxStatsPeriod);
    }

    /** Returns {@link android.car.watchdog.ResourceOveruseStats} for the specified user package. */
    @Override
    @NonNull
    public ResourceOveruseStats getResourceOveruseStatsForUserPackage(
            @NonNull String packageName, @NonNull UserHandle userHandle,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_COLLECT_CAR_WATCHDOG_METRICS);
        return mWatchdogPerfHandler.getResourceOveruseStatsForUserPackage(packageName, userHandle,
                resourceOveruseFlag, maxStatsPeriod);
    }

    /**
     * Adds {@link android.car.watchdog.IResourceOveruseListener} for the calling package's resource
     * overuse notifications.
     */
    @Override
    public void addResourceOveruseListener(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull IResourceOveruseListener listener) {
        mWatchdogPerfHandler.addResourceOveruseListener(resourceOveruseFlag, listener);
    }

    /**
     * Removes the previously added {@link android.car.watchdog.IResourceOveruseListener} for the
     * calling package's resource overuse notifications.
     */
    @Override
    public void removeResourceOveruseListener(@NonNull IResourceOveruseListener listener) {
        mWatchdogPerfHandler.removeResourceOveruseListener(listener);
    }

    /**
     * Adds {@link android.car.watchdog.IResourceOveruseListener} for all packages' resource overuse
     * notifications.
     */
    @Override
    public void addResourceOveruseListenerForSystem(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull IResourceOveruseListener listener) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_COLLECT_CAR_WATCHDOG_METRICS);
        mWatchdogPerfHandler.addResourceOveruseListenerForSystem(resourceOveruseFlag, listener);
    }

    /**
     * Removes the previously added {@link android.car.watchdog.IResourceOveruseListener} for all
     * packages' resource overuse notifications.
     */
    @Override
    public void removeResourceOveruseListenerForSystem(@NonNull IResourceOveruseListener listener) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_COLLECT_CAR_WATCHDOG_METRICS);
        mWatchdogPerfHandler.removeResourceOveruseListenerForSystem(listener);
    }

    /** Sets whether or not a user package is killable on resource overuse. */
    @Override
    public void setKillablePackageAsUser(String packageName, UserHandle userHandle,
            boolean isKillable) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CONTROL_CAR_WATCHDOG_CONFIG);
        mWatchdogPerfHandler.setKillablePackageAsUser(packageName, userHandle, isKillable);
    }

    /**
     * Returns all {@link android.car.watchdog.PackageKillableState} on resource overuse for
     * the specified user.
     */
    @Override
    @NonNull
    public List<PackageKillableState> getPackageKillableStatesAsUser(UserHandle userHandle) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CONTROL_CAR_WATCHDOG_CONFIG);
        return mWatchdogPerfHandler.getPackageKillableStatesAsUser(userHandle);
    }

    /**
     * Sets {@link android.car.watchdog.ResourceOveruseConfiguration} for the specified resources.
     */
    @Override
    @CarWatchdogManager.ReturnCode
    public int setResourceOveruseConfigurations(
            List<ResourceOveruseConfiguration> configurations,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag)
            throws RemoteException {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_CONTROL_CAR_WATCHDOG_CONFIG);
        return mWatchdogPerfHandler.setResourceOveruseConfigurations(configurations,
                resourceOveruseFlag);
    }

    /** Returns the available {@link android.car.watchdog.ResourceOveruseConfiguration}. */
    @Override
    @NonNull
    public List<ResourceOveruseConfiguration> getResourceOveruseConfigurations(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag) {
        ICarImpl.assertAnyPermission(mContext, Car.PERMISSION_CONTROL_CAR_WATCHDOG_CONFIG,
                Car.PERMISSION_COLLECT_CAR_WATCHDOG_METRICS);
        return mWatchdogPerfHandler.getResourceOveruseConfigurations(resourceOveruseFlag);
    }

    /**
     * Enables/disables the watchdog daemon client health check process.
     */
    public void controlProcessHealthCheck(boolean disable) {
        ICarImpl.assertPermission(mContext, Car.PERMISSION_USE_CAR_WATCHDOG);
        mWatchdogProcessHandler.controlProcessHealthCheck(disable);
    }

    @VisibleForTesting
    int getClientCount(int timeout) {
        return mWatchdogProcessHandler.getClientCount(timeout);
    }

    @VisibleForTesting
    void setOveruseHandlingDelay(long millis) {
        mWatchdogPerfHandler.setOveruseHandlingDelay(millis);
    }

    @VisibleForTesting
    void setUidIoUsageSummaryTopCount(int uidIoUsageSummaryTopCount) {
        mWatchdogPerfHandler.setUidIoUsageSummaryTopCount(uidIoUsageSummaryTopCount);
    }

    static File getWatchdogDirFile() {
        SystemInterface systemInterface = CarLocalServices.getService(SystemInterface.class);
        String systemCarDirPath = systemInterface == null ? FALLBACK_DATA_SYSTEM_CAR_DIR_PATH
                : systemInterface.getSystemCarDir().getAbsolutePath();
        return new File(systemCarDirPath, WATCHDOG_DIR_NAME);
    }

    private void notifyAllUserStates() {
        UserManager userManager = UserManager.get(mContext);
        List<UserInfo> users = userManager.getUsers();
        try {
            // TODO(b/152780162): reduce the number of RPC calls(isUserRunning).
            for (int i = 0; i < users.size(); ++i) {
                UserInfo info = users.get(i);
                int userState = userManager.isUserRunning(info.id)
                        ? UserState.USER_STATE_STARTED
                        : UserState.USER_STATE_STOPPED;
                mCarWatchdogDaemonHelper.notifySystemStateChange(StateType.USER_STATE, info.id,
                        userState);
                mWatchdogProcessHandler.updateUserState(info.id,
                        userState == UserState.USER_STATE_STOPPED);
            }
            if (DEBUG) {
                Slogf.d(TAG, "Notified car watchdog daemon of user states");
            }
        } catch (RemoteException | RuntimeException e) {
            // When car watchdog daemon is not connected, the {@link mCarWatchdogDaemonHelper}
            // throws IllegalStateException. Catch the exception to avoid crashing the process.
            Slogf.w(TAG, e, "Notifying latest user states failed");
        }
    }

    private void notifyPowerCycleChange(@PowerCycle int powerCycle) {
        if (powerCycle == PowerCycle.NUM_POWER_CYLES) {
            Slogf.e(TAG, "Skipping notifying invalid power cycle (%d)", powerCycle);
            return;
        }
        try {
            mCarWatchdogDaemonHelper.notifySystemStateChange(
                    StateType.POWER_CYCLE, powerCycle, MISSING_ARG_VALUE);
            if (DEBUG) {
                Slogf.d(TAG, "Notified car watchdog daemon of power cycle(%d)", powerCycle);
            }
        } catch (RemoteException | RuntimeException e) {
            // When car watchdog daemon is not connected, the {@link mCarWatchdogDaemonHelper}
            // throws IllegalStateException. Catch the exception to avoid crashing the process.
            Slogf.w(TAG, e, "Notifying power cycle change to %d failed", powerCycle);
        }
    }

    private void notifyGarageModeChange(@GarageMode int garageMode) {
        try {
            mCarWatchdogDaemonHelper.notifySystemStateChange(
                    StateType.GARAGE_MODE, garageMode, MISSING_ARG_VALUE);
            if (DEBUG) {
                Slogf.d(TAG, "Notified car watchdog daemon of garage mode(%d)", garageMode);
            }
        } catch (RemoteException | RuntimeException e) {
            // When car watchdog daemon is not connected, the {@link mCarWatchdogDaemonHelper}
            // throws IllegalStateException. Catch the exception to avoid crashing the process.
            Slogf.w(TAG, e, "Notifying garage mode change to %d failed", garageMode);
        }
    }

    private void postRegisterToDaemonMessage() {
        CarServiceUtils.runOnMain(() -> {
            synchronized (mLock) {
                mReadyToRespond = true;
            }
            registerToDaemon();
        });
    }

    private void registerToDaemon() {
        synchronized (mLock) {
            if (!mIsConnected || !mReadyToRespond) {
                return;
            }
        }
        try {
            mCarWatchdogDaemonHelper.registerCarWatchdogService(mWatchdogServiceForSystem);
            if (DEBUG) {
                Slogf.d(TAG, "CarWatchdogService registers to car watchdog daemon");
            }
        } catch (RemoteException | RuntimeException e) {
            // When car watchdog daemon is not connected, the {@link mCarWatchdogDaemonHelper}
            // throws IllegalStateException. Catch the exception to avoid crashing the process.
            Slogf.w(TAG, e, "Cannot register to car watchdog daemon");
        }
        notifyAllUserStates();
        CarPowerManagementService powerService =
                CarLocalServices.getService(CarPowerManagementService.class);
        if (powerService != null) {
            int powerState = powerService.getPowerState();
            int powerCycle = carPowerStateToPowerCycle(powerState);
            if (powerCycle != PowerCycle.NUM_POWER_CYLES) {
                notifyPowerCycleChange(powerCycle);
            } else {
                Slogf.i(TAG, "Skipping notifying %d power state", powerState);
            }
        }
        int garageMode;
        synchronized (mLock) {
            // To avoid race condition, fetch {@link mCurrentGarageMode} just before
            // the {@link notifyGarageModeChange} call. For instance, if {@code mCurrentGarageMode}
            // changes before the above {@link notifyPowerCycleChange} call returns,
            // the {@link garageMode}'s value will be out of date.
            garageMode = mCurrentGarageMode;
        }
        notifyGarageModeChange(garageMode);
    }

    private void unregisterFromDaemon() {
        try {
            mCarWatchdogDaemonHelper.unregisterCarWatchdogService(mWatchdogServiceForSystem);
            if (DEBUG) {
                Slogf.d(TAG, "CarWatchdogService unregisters from car watchdog daemon");
            }
        } catch (RemoteException | RuntimeException e) {
            // When car watchdog daemon is not connected, the {@link mCarWatchdogDaemonHelper}
            // throws IllegalStateException. Catch the exception to avoid crashing the process.
            Slogf.w(TAG, e, "Cannot unregister from car watchdog daemon");
        }
    }

    private void subscribePowerManagementService() {
        CarPowerManagementService powerService =
                CarLocalServices.getService(CarPowerManagementService.class);
        if (powerService == null) {
            Slogf.w(TAG, "Cannot get CarPowerManagementService");
            return;
        }
        powerService.registerListener(mCarPowerStateListener);
        powerService.addPowerPolicyListener(
                new CarPowerPolicyFilter.Builder().setComponents(PowerComponent.DISPLAY).build(),
                mCarDisplayPowerPolicyListener);
    }

    private void unsubscribePowerManagementService() {
        CarPowerManagementService powerService =
                CarLocalServices.getService(CarPowerManagementService.class);
        if (powerService == null) {
            Slogf.w(TAG, "Cannot get CarPowerManagementService");
            return;
        }
        powerService.unregisterListener(mCarPowerStateListener);
        powerService.removePowerPolicyListener(mCarDisplayPowerPolicyListener);
    }

    private void subscribeUserStateChange() {
        CarUserService userService = CarLocalServices.getService(CarUserService.class);
        if (userService == null) {
            Slogf.w(TAG, "Cannot get CarUserService");
            return;
        }
        userService.addUserLifecycleListener((event) -> {
            int userId = event.getUserHandle().getIdentifier();
            int userState;
            String userStateDesc;
            switch (event.getEventType()) {
                case USER_LIFECYCLE_EVENT_TYPE_STARTING:
                    mWatchdogProcessHandler.updateUserState(userId, /*isStopped=*/ false);
                    userState = UserState.USER_STATE_STARTED;
                    userStateDesc = "STARTING";
                    break;
                case USER_LIFECYCLE_EVENT_TYPE_STOPPED:
                    mWatchdogProcessHandler.updateUserState(userId, /*isStopped=*/ true);
                    userState = UserState.USER_STATE_STOPPED;
                    userStateDesc = "STOPPING";
                    break;
                default:
                    return;
            }
            try {
                mCarWatchdogDaemonHelper.notifySystemStateChange(StateType.USER_STATE, userId,
                        userState);
                if (DEBUG) {
                    Slogf.d(TAG, "Notified car watchdog daemon user %d's user state, %s",
                            userId, userStateDesc);
                }
            } catch (RemoteException | RuntimeException e) {
                // When car watchdog daemon is not connected, the {@link mCarWatchdogDaemonHelper}
                // throws IllegalStateException. Catch the exception to avoid crashing the process.
                Slogf.w(TAG, e, "Notifying user state change failed");
            }
        });
    }

    private void subscribeBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION);
        filter.addAction(ACTION_GARAGE_MODE_ON);
        filter.addAction(ACTION_GARAGE_MODE_OFF);
        filter.addAction(ACTION_LAUNCH_APP_SETTINGS);
        filter.addAction(ACTION_RESOURCE_OVERUSE_DISABLE_APP);
        filter.addAction(ACTION_USER_REMOVED);

        mContext.registerReceiverForAllUsers(mBroadcastReceiver, filter,
                Car.PERMISSION_CONTROL_CAR_WATCHDOG_CONFIG, /* scheduler= */ null);
    }

    private static @PowerCycle int carPowerStateToPowerCycle(int powerState) {
        switch (powerState) {
            // SHUTDOWN_PREPARE covers suspend and shutdown.
            case CarPowerStateListener.SHUTDOWN_PREPARE:
                return PowerCycle.POWER_CYCLE_SHUTDOWN_PREPARE;
            case CarPowerStateListener.SHUTDOWN_ENTER:
            case CarPowerStateListener.SUSPEND_ENTER:
                return PowerCycle.POWER_CYCLE_SHUTDOWN_ENTER;
            // ON covers resume.
            case CarPowerStateListener.ON:
                return PowerCycle.POWER_CYCLE_RESUME;
        }
        return PowerCycle.NUM_POWER_CYLES;
    }

    private static String toGarageModeString(@GarageMode int garageMode) {
        switch (garageMode) {
            case GarageMode.GARAGE_MODE_OFF:
                return "GARAGE_MODE_OFF";
            case GarageMode.GARAGE_MODE_ON:
                return "GARAGE_MODE_ON";
        }
        return "INVALID";
    }

    private static final class ICarWatchdogServiceForSystemImpl
            extends ICarWatchdogServiceForSystem.Stub {
        private final WeakReference<CarWatchdogService> mService;

        ICarWatchdogServiceForSystemImpl(CarWatchdogService service) {
            mService = new WeakReference<>(service);
        }

        @Override
        public void checkIfAlive(int sessionId, int timeout) {
            CarWatchdogService service = mService.get();
            if (service == null) {
                Slogf.w(TAG, "CarWatchdogService is not available");
                return;
            }
            service.mWatchdogProcessHandler.postHealthCheckMessage(sessionId);
        }

        @Override
        public void prepareProcessTermination() {
            Slogf.w(TAG, "CarWatchdogService is about to be killed by car watchdog daemon");
        }

        @Override
        public List<PackageInfo> getPackageInfosForUids(
                int[] uids, List<String> vendorPackagePrefixes) {
            if (ArrayUtils.isEmpty(uids)) {
                Slogf.w(TAG, "UID list is empty");
                return null;
            }
            CarWatchdogService service = mService.get();
            if (service == null) {
                Slogf.w(TAG, "CarWatchdogService is not available");
                return null;
            }
            return service.mPackageInfoHandler.getPackageInfosForUids(uids, vendorPackagePrefixes);
        }

        @Override
        public void latestIoOveruseStats(List<PackageIoOveruseStats> packageIoOveruseStats) {
            if (packageIoOveruseStats.isEmpty()) {
                Slogf.w(TAG, "Latest I/O overuse stats is empty");
                return;
            }
            CarWatchdogService service = mService.get();
            if (service == null) {
                Slogf.w(TAG, "CarWatchdogService is not available");
                return;
            }
            service.mWatchdogPerfHandler.latestIoOveruseStats(packageIoOveruseStats);
        }

        @Override
        public void resetResourceOveruseStats(List<String> packageNames) {
            if (packageNames.isEmpty()) {
                Slogf.w(TAG, "Provided an empty package name to reset resource overuse stats");
                return;
            }
            CarWatchdogService service = mService.get();
            if (service == null) {
                Slogf.w(TAG, "CarWatchdogService is not available");
                return;
            }
            service.mWatchdogPerfHandler.resetResourceOveruseStats(new ArraySet<>(packageNames));
        }

        @Override
        public List<UserPackageIoUsageStats> getTodayIoUsageStats() {
            CarWatchdogService service = mService.get();
            if (service == null) {
                Slogf.w(TAG, "CarWatchdogService is not available");
                return null;
            }
            return service.mWatchdogPerfHandler.getTodayIoUsageStats();
        }
    }
}
