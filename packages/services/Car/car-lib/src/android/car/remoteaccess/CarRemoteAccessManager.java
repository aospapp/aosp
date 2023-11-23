/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.car.remoteaccess;

import static com.android.car.internal.util.VersionUtils.assertPlatformVersionAtLeastU;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.annotation.ApiRequirements;
import android.car.annotation.ApiRequirements.CarVersion;
import android.car.annotation.ApiRequirements.PlatformVersion;
import android.car.builtin.util.Slogf;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.Executor;

/**
 * CarRemoteAccessManager allows applications to listen to remote task requests even while Android
 * System is not running.
 *
 * <p>The remote task client registers to {@link CarRemoteAccessManager} to listen to remote access
 * events. At {@link RemoteTaskClientCallback#onClientRegistered} it is required to share
 * {@code serviceId}, {@code deviceId} and {@code clientId} with the cloud service which will use
 * the IDs to wake the vehicle. At {@link RemoteTaskClientCallback#onRemoteTaskRequested}, it starts
 * executing the given task. It is supposed to call {@link #reportRemoteTaskDone(String)} when it
 * finishes the given task. Once the task completion is reported or the timeout expires, Android
 * System goes back to either the previous power state or the specified power state.
 */
public final class CarRemoteAccessManager extends CarManagerBase {

    private static final String TAG = CarRemoteAccessManager.class.getSimpleName();

    /**
     * The system remains ON after completing the remote tasks.
     *
     * @hide
     */
    @SystemApi
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int NEXT_POWER_STATE_ON = 1;

    /**
     * The system shuts down to power off after completing the remote tasks.
     *
     * @hide
     */
    @SystemApi
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int NEXT_POWER_STATE_OFF = 2;

    /**
     * The system goes into deep sleep after completing the remote tasks.
     *
     * @hide
     */
    @SystemApi
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int NEXT_POWER_STATE_SUSPEND_TO_RAM = 3;

    /**
     * The system goes into hibernation after completing the remote tasks.
     *
     * @hide
     */
    @SystemApi
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public static final int NEXT_POWER_STATE_SUSPEND_TO_DISK = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "NEXT_POWER_STATE_", value = {
            NEXT_POWER_STATE_ON,
            NEXT_POWER_STATE_OFF,
            NEXT_POWER_STATE_SUSPEND_TO_RAM,
            NEXT_POWER_STATE_SUSPEND_TO_DISK,
    })
    @Target({ElementType.TYPE_USE})
    public @interface NextPowerState {}

    private final ICarRemoteAccessService mService;
    private final Object mLock = new Object();

    private final ICarRemoteAccessCallback mCarRemoteAccessCallback =
            new ICarRemoteAccessCallback.Stub() {
        @Override
        public void onClientRegistrationUpdated(RemoteTaskClientRegistrationInfo registrationInfo) {
            RemoteTaskClientCallback callback;
            Executor executor;
            synchronized (mLock) {
                if (mRemoteTaskClientCallback == null || mExecutor == null) {
                    Slogf.w(TAG, "Cannot call onRegistrationUpdated because no remote task client "
                            + "is registered");
                    return;
                }
                mCurrentClientId = registrationInfo.getClientId();
                callback = mRemoteTaskClientCallback;
                executor = mExecutor;
            }
            executor.execute(() -> callback.onRegistrationUpdated(registrationInfo));
        }

        @Override
        public void onClientRegistrationFailed() {
            RemoteTaskClientCallback callback;
            Executor executor;
            synchronized (mLock) {
                if (mRemoteTaskClientCallback == null || mExecutor == null) {
                    Slogf.w(TAG, "Cannot call onRegistrationFailed because no remote task client "
                            + "is registered");
                    return;
                }
                callback = mRemoteTaskClientCallback;
                executor = mExecutor;
            }
            executor.execute(() -> callback.onRegistrationFailed());
        }

        @Override
        public void onRemoteTaskRequested(String clientId, String taskId, byte[] data,
                int taskMaxDurationInSec) {
            RemoteTaskClientCallback callback;
            Executor executor;
            synchronized (mLock) {
                if (mCurrentClientId == null || !mCurrentClientId.equals(clientId)) {
                    Slogf.w(TAG, "Received a task for a mismatched client ID(%s): the current "
                            + "client ID = %s", clientId, mCurrentClientId);
                    return;
                }
                callback = mRemoteTaskClientCallback;
                executor = mExecutor;
            }
            if (callback == null || executor == null) {
                Slogf.w(TAG, "Cannot call onRemoteTaskRequested because no remote task client is "
                        + "registered");
                return;
            }
            executor.execute(() -> callback.onRemoteTaskRequested(taskId, data,
                    taskMaxDurationInSec));
        }

        @Override
        public void onShutdownStarting() {
            String clientId;
            RemoteTaskClientCallback callback;
            Executor executor;
            synchronized (mLock) {
                clientId = mCurrentClientId;
                callback = mRemoteTaskClientCallback;
                executor = mExecutor;
            }
            if (clientId == null || callback == null || executor == null) {
                Slogf.w(TAG, "Cannot call onShutdownStarting because no remote task client is "
                        + "registered");
                return;
            }
            executor.execute(() ->
                    callback.onShutdownStarting(new MyCompletableRemoteTaskFuture(clientId)));
        }
    };

    @GuardedBy("mLock")
    private RemoteTaskClientCallback mRemoteTaskClientCallback;
    @GuardedBy("mLock")
    private Executor mExecutor;
    @GuardedBy("mLock")
    private String mCurrentClientId;

    /**
     * An interface passed from {@link RemoteTaskClientCallback}.
     *
     * <p>The remote task client uses this interface to tell {@link CarRemoteAccessManager} that it
     * finalized the pending remote tasks.
     */
    public interface CompletableRemoteTaskFuture {
        /**
         * Tells {@link CarRemoteAccessManager} that the remote task client finalized the pending
         * remoate tasks.
         */
        @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
        void complete();
    }

    private final class MyCompletableRemoteTaskFuture implements CompletableRemoteTaskFuture {
        private final String mClientIdToComplete;

        MyCompletableRemoteTaskFuture(String clientId) {
            mClientIdToComplete = clientId;
        }

        @Override
        public void complete() {
            try {
                mService.confirmReadyForShutdown(mClientIdToComplete);
            } catch (RemoteException e) {
                handleRemoteExceptionFromCarService(e);
            }
        }
    }

    /**
     * Listener for remote task events.
     */
    public interface RemoteTaskClientCallback {
        /**
         * This is called when the remote task client is successfully registered or the client ID is
         * updated by AAOS.
         *
         * @param info {@link RemoteTaskClientRegistrationIfno} which contains wake-up service ID,
         *             vehicle ID, processor ID and client ID.
         */
        @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
        void onRegistrationUpdated(@NonNull RemoteTaskClientRegistrationInfo info);

        /**
         * This is called when registering the remote task client fails.
         */
        @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
        void onRegistrationFailed();

        /**
         * This is called when a wake-up request is received/processed.
         *
         * @param taskId ID of the task that is requested by the remote task server.
         * @param data Extra data passed along with the wake-up request.
         * @param taskMaxDurationInSec The timeout before AAOS goes back to the previous power
         *                             state.
         */
        @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
        void onRemoteTaskRequested(@NonNull String taskId, @Nullable byte[] data,
                int taskMaxDurationInSec);

        /**
         * This is called when the device is about to shutdown.
         *
         * <p>The remote task client should finalize the ongoing tasks, if any, and complete the
         * given future within 5 seconds. After the given timeout, the Android system will shutdown,
         * anyway.
         *
         * @param future {@link CompletableRemoteTaskFuture} used by the remote task client to
         *               notify CarRemoteAccessManager that all pending remote tasks are finalized.
         */
        @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
        void onShutdownStarting(@NonNull CompletableRemoteTaskFuture future);
    }

    /** @hide */
    public CarRemoteAccessManager(Car car, IBinder service) {
        super(car);
        mService = ICarRemoteAccessService.Stub.asInterface(service);
    }

    /** @hide */
    @Override
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void onCarDisconnected() {
        // Nothing to do.
    }

    /**
     * Sets the remote task client represented as {@link RemoteTaskClientCallback}.
     *
     * @param executor Executor on which {@code callback} is executed.
     * @param callback {@link RemoteTaskClientCallback} that listens to remote task events.
     * @throws IllegalStateException When a remote task client is already set.
     * @throws IllegalArgumentException When the given callback or the executor is {@code null}.
     */
    @RequiresPermission(Car.PERMISSION_USE_REMOTE_ACCESS)
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void setRemoteTaskClient(@NonNull @CallbackExecutor Executor executor,
            @NonNull RemoteTaskClientCallback callback) {
        assertPlatformVersionAtLeastU();
        Preconditions.checkArgument(executor != null, "Executor cannot be null");
        Preconditions.checkArgument(callback != null, "Callback cannot be null");

        synchronized (mLock) {
            if (mRemoteTaskClientCallback != null) {
                throw new IllegalStateException("Remote task client must be cleared first");
            }
            mRemoteTaskClientCallback = callback;
            mExecutor = executor;
        }

        try {
            mService.addCarRemoteTaskClient(mCarRemoteAccessCallback);
        } catch (RemoteException e) {
            synchronized (mLock) {
                mRemoteTaskClientCallback = null;
                mExecutor = null;
            }
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Clears the remote task client previously set via {@link #setRemoteTaskClient(Executor,
     * RemoteTaskClientCallback)}.
     *
     * <p>After the remote task client is cleared, all tasks associated with the previous client
     * will not be delivered and the client must not call {@code reportRemoteTaskDone} with the
     * task ID associated with the previous client ID.
     *
     * @throws IllegalStateException if {@code callback} is not registered.
     */
    @RequiresPermission(Car.PERMISSION_USE_REMOTE_ACCESS)
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void clearRemoteTaskClient() {
        assertPlatformVersionAtLeastU();
        synchronized (mLock) {
            if (mRemoteTaskClientCallback == null) {
                Slogf.w(TAG, "No registered remote task client to clear");
                return;
            }
            mRemoteTaskClientCallback = null;
            mExecutor = null;
            mCurrentClientId = null;
        }
        try {
            mService.removeCarRemoteTaskClient(mCarRemoteAccessCallback);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Reports that remote tast execution is completed, so that the vehicle will go back to the
     * power state before the wake-up.
     *
     * @param taskId ID of the remote task which has been completed.
     * @throws IllegalArgumentException If {@code taskId} is null.
     * @throws IllegalStateException If the remote task client is not registered or not woken up.
     */
    @RequiresPermission(Car.PERMISSION_USE_REMOTE_ACCESS)
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void reportRemoteTaskDone(@NonNull String taskId) {
        assertPlatformVersionAtLeastU();
        Preconditions.checkArgument(taskId != null, "Task ID cannot be null");

        String currentClientId;
        synchronized (mLock) {
            if (mCurrentClientId == null) {
                Slogf.w(TAG, "Failed to report remote task completion: no remote task client is "
                        + "registered");
                throw new IllegalStateException("No remote task client is registered");
            }
            currentClientId = mCurrentClientId;
        }
        try {
            mService.reportRemoteTaskDone(currentClientId, taskId);
        } catch (IllegalStateException e) {
            Slogf.w(TAG, "Task ID(%s) is not valid: %s", taskId, e);
            throw e;
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Sets the power state after all the remote tasks are completed.
     *
     * <p>By default, the system returns to the previous power state from which the system woke up.
     * If the given power state is {@code NEXT_POWER_STATE_ON}, Garage Mode is not executed.
     *
     * @param nextPowerState The next power state after the remote task is completed.
     * @param runGarageMode Whether to run Garage Mode when switching to the next power state.
     * @throws IllegalArgumentException If {@code nextPowerState} is not valid.
     * @throws IllegalStateException If the remote task client is not registered or not woken up.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CONTROL_REMOTE_ACCESS)
    @ApiRequirements(minCarVersion = CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void setPowerStatePostTaskExecution(@NextPowerState int nextPowerState,
            boolean runGarageMode) {
        assertPlatformVersionAtLeastU();
        try {
            mService.setPowerStatePostTaskExecution(nextPowerState, runGarageMode);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }
}
