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

package com.android.ondevicepersonalization.services;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.ondevicepersonalization.aidl.IExecuteCallback;
import android.ondevicepersonalization.aidl.IOnDevicePersonalizationManagingService;
import android.ondevicepersonalization.aidl.IRequestSurfacePackageCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.PersistableBundle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.ondevicepersonalization.services.request.AppRequestFlow;
import com.android.ondevicepersonalization.services.request.RenderFlow;

import java.util.Objects;

/** Implementation of OnDevicePersonalizationManagingService */
public class OnDevicePersonalizationManagingServiceDelegate
        extends IOnDevicePersonalizationManagingService.Stub {
    @NonNull private final Context mContext;

    @VisibleForTesting
    static class Injector {
        AppRequestFlow getAppRequestFlow(
                String callingPackageName,
                String servicePackageName,
                PersistableBundle params,
                IExecuteCallback callback,
                Context context) {
            return new AppRequestFlow(
                    callingPackageName, servicePackageName, params, callback, context);
        }

        RenderFlow getRenderFlow(
                String slotResultToken,
                IBinder hostToken,
                int displayId,
                int width,
                int height,
                IRequestSurfacePackageCallback callback,
                Context context) {
            return new RenderFlow(
                    slotResultToken, hostToken, displayId, width, height, callback, context);
        }
    }

    @NonNull private final Injector mInjector;

    public OnDevicePersonalizationManagingServiceDelegate(@NonNull Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    public OnDevicePersonalizationManagingServiceDelegate(
            @NonNull Context context,
            @NonNull Injector injector) {
        mContext = Objects.requireNonNull(context);
        mInjector = Objects.requireNonNull(injector);
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public void execute(
            @NonNull String callingPackageName,
            @NonNull String servicePackageName,
            @NonNull PersistableBundle params,
            @NonNull IExecuteCallback callback) {
        Objects.requireNonNull(callingPackageName);
        Objects.requireNonNull(servicePackageName);
        Objects.requireNonNull(params);
        Objects.requireNonNull(callback);

        final int uid = Binder.getCallingUid();
        enforceCallingPackageBelongsToUid(callingPackageName, uid);

        AppRequestFlow flow = mInjector.getAppRequestFlow(
                callingPackageName,
                servicePackageName,
                params,
                callback,
                mContext);
        flow.run();
    }

    @Override
    public void requestSurfacePackage(
            @NonNull String slotResultToken,
            @NonNull IBinder hostToken,
            int displayId,
            int width,
            int height,
            @NonNull IRequestSurfacePackageCallback callback) {
        Objects.requireNonNull(slotResultToken);
        Objects.requireNonNull(hostToken);
        Objects.requireNonNull(callback);
        if (width <= 0) {
            throw new IllegalArgumentException("width must be > 0");
        }

        if (height <= 0) {
            throw new IllegalArgumentException("height must be > 0");
        }

        if (displayId < 0) {
            throw new IllegalArgumentException("displayId must be >= 0");
        }

        RenderFlow flow = mInjector.getRenderFlow(
                slotResultToken,
                hostToken,
                displayId,
                width,
                height,
                callback,
                mContext);
        flow.run();
    }

    private void enforceCallingPackageBelongsToUid(@NonNull String packageName, int uid) {
        int packageUid;
        PackageManager pm = mContext.getPackageManager();
        try {
            packageUid = pm.getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException(packageName + " not found");
        }
        if (packageUid != uid) {
            throw new SecurityException(packageName + " does not belong to uid " + uid);
        }
        //TODO(b/242792629): Handle requests from the SDK sandbox.
    }
}
