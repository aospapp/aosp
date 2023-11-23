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

package android.car.app;


import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.android.car.internal.util.VersionUtils.assertPlatformVersionAtLeastU;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresApi;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.Activity;
import android.car.annotation.ApiRequirements;
import android.car.builtin.app.ActivityManagerHelper;
import android.car.builtin.util.Slogf;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Dumpable;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * This class is used for creating task views & is created on a per activity basis.
 * @hide
 */
@SystemApi
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public final class CarTaskViewController {
    private static final String TAG = CarTaskViewController.class.getSimpleName();
    static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);

    private final ICarSystemUIProxy mService;
    private final Activity mHostActivity;
    private final List<ControlledRemoteCarTaskView> mControlledRemoteCarTaskViews =
            new ArrayList<>();
    private final CarTaskViewInputInterceptor mTaskViewInputInterceptor;
    private boolean mReleased = false;

    /**
     * @param service the binder interface to communicate with the car system UI.
     * @param hostActivity the activity that will be hosting the taskviews.
     * @hide
     */
    CarTaskViewController(@NonNull ICarSystemUIProxy service, @NonNull Activity hostActivity) {
        mService = service;
        mHostActivity = hostActivity;

        mHostActivity.addDumpable(mDumper);
        mTaskViewInputInterceptor = new CarTaskViewInputInterceptor(hostActivity, this);
    }

    /**
     * Creates a new {@link ControlledRemoteCarTaskView}.
     *
     * @param callbackExecutor the executor to get the {@link ControlledRemoteCarTaskViewCallback}
     *                         on.
     * @param controlledRemoteCarTaskViewCallback the callback to monitor the
     *                                             {@link ControlledRemoteCarTaskView} related
     *                                             events.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @RequiresPermission(allOf = {Manifest.permission.INJECT_EVENTS,
            Manifest.permission.INTERNAL_SYSTEM_WINDOW}, conditional = true)
    public void createControlledRemoteCarTaskView(
            @NonNull ControlledRemoteCarTaskViewConfig controlledRemoteCarTaskViewConfig,
            @NonNull Executor callbackExecutor,
            @NonNull ControlledRemoteCarTaskViewCallback controlledRemoteCarTaskViewCallback) {
        assertPlatformVersionAtLeastU();
        if (mReleased) {
            throw new IllegalStateException("CarTaskViewController is already released");
        }
        ControlledRemoteCarTaskView taskViewClient =
                new ControlledRemoteCarTaskView(
                        mHostActivity,
                        controlledRemoteCarTaskViewConfig,
                        callbackExecutor,
                        controlledRemoteCarTaskViewCallback,
                        /* carTaskViewController= */ this,
                        mHostActivity.getSystemService(UserManager.class));

        try {
            ICarTaskViewHost host = mService.createControlledCarTaskView(
                    taskViewClient.mICarTaskViewClient);
            taskViewClient.setRemoteHost(host);
            mControlledRemoteCarTaskViews.add(taskViewClient);

            if (controlledRemoteCarTaskViewConfig.mShouldCaptureGestures
                    || controlledRemoteCarTaskViewConfig.mShouldCaptureLongPress) {
                assertPermission(Manifest.permission.INJECT_EVENTS);
                assertPermission(Manifest.permission.INTERNAL_SYSTEM_WINDOW);
                mTaskViewInputInterceptor.init();
            }
        } catch (RemoteException e) {
            Slogf.e(TAG, "Unable to create task view.", e);
        }
    }

    void onControlledRemoteCarTaskViewReleased(@NonNull ControlledRemoteCarTaskView taskView) {
        if (mReleased) {
            Log.w(TAG, "Failed to remove the taskView as the "
                    + "CarTaskViewController is already released");
            return;
        }
        if (!mControlledRemoteCarTaskViews.contains(taskView)) {
            Log.w(TAG, "This taskView has already been removed");
            return;
        }
        mControlledRemoteCarTaskViews.remove(taskView);
    }

    private void assertPermission(String permission) {
        if (mHostActivity.getApplicationContext().checkCallingOrSelfPermission(permission)
                != PERMISSION_GRANTED) {
            throw new SecurityException("requires " + permission);
        }
    }

    /**
     * Releases all the resources held by the taskviews associated with this controller.
     *
     * <p> Once {@link #release()} is called, the current instance of {@link CarTaskViewController}
     * cannot be used further. A new instance should be requested using
     * {@link CarActivityManager#getCarTaskViewController(Activity, Executor,
     * CarTaskViewControllerCallback)}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void release() {
        assertPlatformVersionAtLeastU();
        if (mReleased) {
            Slogf.w(TAG, "CarTaskViewController is already released");
            return;
        }
        releaseTaskViews();
        mTaskViewInputInterceptor.release();
        mReleased = true;
    }

    void releaseTaskViews() {
        Iterator<ControlledRemoteCarTaskView> iterator = mControlledRemoteCarTaskViews.iterator();
        while (iterator.hasNext()) {
            ControlledRemoteCarTaskView taskView = iterator.next();
            // Remove the task view here itself because release triggers removal again which can
            // result in concurrent modification exception.
            iterator.remove();
            taskView.release();
        }
    }

    /**
     * Brings all the embedded tasks to the front.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void showEmbeddedTasks() {
        assertPlatformVersionAtLeastU();
        if (mReleased) {
            throw new IllegalStateException("CarTaskViewController is already released");
        }
        for (RemoteCarTaskView carTaskView : mControlledRemoteCarTaskViews) {
            // TODO(b/267314188): Add a new method in ICarSystemUI to call
            // showEmbeddedTask in a single WCT for multiple tasks.
            carTaskView.showEmbeddedTask();
        }
    }

    boolean isHostVisible() {
        return ActivityManagerHelper.isVisible(mHostActivity);
    }

    List<ControlledRemoteCarTaskView> getControlledRemoteCarTaskViews() {
        return mControlledRemoteCarTaskViews;
    }

    private final Dumpable mDumper = new Dumpable() {
        private static final String INDENTATION = "  ";

        @NonNull
        @Override
        public String getDumpableName() {
            return TAG;
        }

        @Override
        public void dump(@NonNull PrintWriter writer, @Nullable String[] args) {
            writer.println("ControlledRemoteCarTaskViews: ");
            for (ControlledRemoteCarTaskView taskView : mControlledRemoteCarTaskViews) {
                writer.println(INDENTATION + taskView.toString(/* withBounds= */ true));
            }
        }
    };
}
