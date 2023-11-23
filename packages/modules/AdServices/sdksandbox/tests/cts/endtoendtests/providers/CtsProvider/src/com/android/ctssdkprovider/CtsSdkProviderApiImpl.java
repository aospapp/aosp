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

package com.android.ctssdkprovider;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.sdksandbox.sdkprovider.SdkSandboxActivityHandler;
import android.app.sdksandbox.sdkprovider.SdkSandboxController;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;

import com.android.sdksandbox.SdkSandboxServiceImpl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class CtsSdkProviderApiImpl extends ICtsSdkProviderApi.Stub {
    private Context mContext;
    private static final String CLIENT_PACKAGE_NAME = "com.android.tests.sdksandbox.endtoend";
    private static final String SDK_NAME = "com.android.ctssdkprovider";
    private static final String CURRENT_USER_ID =
            String.valueOf(Process.myUserHandle().getUserId(Process.myUid()));

    private static final String STRING_RESOURCE = "Test String";
    private static final int INTEGER_RESOURCE = 1234;
    private static final String STRING_ASSET = "This is a test asset";
    private static final String ASSET_FILE = "test-asset.txt";

    CtsSdkProviderApiImpl(Context context) {
        mContext = context;
    }

    @Override
    public void checkClassloaders() {
        final ClassLoader ownClassloader = getClass().getClassLoader();
        if (ownClassloader == null) {
            throw new IllegalStateException("SdkProvider loaded in top-level classloader");
        }

        final ClassLoader contextClassloader = mContext.getClassLoader();
        if (!ownClassloader.equals(contextClassloader)) {
            throw new IllegalStateException("Different SdkProvider and Context classloaders");
        }

        try {
            Class<?> loadedClazz = ownClassloader.loadClass(SdkSandboxServiceImpl.class.getName());
            if (!ownClassloader.equals(loadedClazz.getClassLoader())) {
                throw new IllegalStateException(
                        "SdkSandboxServiceImpl loaded with wrong classloader");
            }
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Couldn't find class bundled with SdkProvider", ex);
        }
    }

    @Override
    public void checkResourcesAndAssets() {
        Resources resources = mContext.getResources();
        String stringRes = resources.getString(R.string.test_string);
        int integerRes = resources.getInteger(R.integer.test_integer);
        if (!stringRes.equals(STRING_RESOURCE)) {
            throw new IllegalStateException(createErrorMessage(STRING_RESOURCE, stringRes));
        }
        if (integerRes != INTEGER_RESOURCE) {
            throw new IllegalStateException(
                    createErrorMessage(
                            String.valueOf(INTEGER_RESOURCE), String.valueOf(integerRes)));
        }

        AssetManager assets = mContext.getAssets();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(assets.open(ASSET_FILE)))) {
            String readAsset = reader.readLine();
            if (!readAsset.equals(STRING_ASSET)) {
                throw new IllegalStateException(createErrorMessage(STRING_ASSET, readAsset));
            }
        } catch (IOException e) {
            throw new IllegalStateException("File not found: " + ASSET_FILE);
        }
    }

    @Override
    public boolean isPermissionGranted(String permissionName, boolean useApplicationContext) {
        final Context cut = useApplicationContext ? mContext.getApplicationContext() : mContext;
        return cut.checkSelfPermission(permissionName) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int getContextHashCode(boolean useApplicationContext) {
        final Context cut = useApplicationContext ? mContext.getApplicationContext() : mContext;
        return cut.hashCode();
    }

    @Override
    public void testStoragePaths() {
        // Verify CE data directories
        {
            final String sdkPathPrefix = getSdkPathPrefix(/*dataDirectoryType*/ "ce");
            final String dataDir = mContext.getDataDir().getPath();
            if (!dataDir.startsWith(sdkPathPrefix)) {
                throw new IllegalStateException("Data dir for CE is wrong: " + dataDir);
            }

            final String fileDir = mContext.getFilesDir().getPath();
            if (!fileDir.startsWith(sdkPathPrefix)) {
                throw new IllegalStateException("File dir for CE is wrong: " + fileDir);
            }
        }

        // Verify DE data directories
        {
            final Context cut = mContext.createDeviceProtectedStorageContext();
            final String sdkPathPrefix = getSdkPathPrefix(/*dataDirectoryType*/ "de");
            final String dataDir = cut.getDataDir().getPath();
            if (!dataDir.startsWith(sdkPathPrefix)) {
                throw new IllegalStateException("Data dir for DE is wrong: " + dataDir);
            }

            final String fileDir = cut.getFilesDir().getPath();
            if (!fileDir.startsWith(sdkPathPrefix)) {
                throw new IllegalStateException("File dir for DE is wrong: " + fileDir);
            }
        }
    }

    @Override
    public int getProcessImportance() {
        ActivityManager.RunningAppProcessInfo processInfo =
                new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(processInfo);
        return processInfo.importance;
    }

    @Override
    public void startActivity(com.android.ctssdkprovider.IActivityStarter iActivityStarter)
            throws RemoteException {
        SdkSandboxController controller = mContext.getSystemService(SdkSandboxController.class);
        IBinder token =
                controller.registerSdkSandboxActivityHandler(
                        new SdkSandboxActivityHandler() {
                            @Override
                            public void onActivityCreated(@NonNull Activity activity) {
                                try {
                                    iActivityStarter.activityStartedSuccessfully();
                                } catch (RemoteException e) {
                                    throw new IllegalStateException("Exception");
                                }
                            }
                        });
        iActivityStarter.startActivity(token);
    }

    @Override
    public String getOpPackageName() {
        return mContext.getOpPackageName();
    }

    @Override
    public void startActivityAfterUnregisterHandler(
            com.android.ctssdkprovider.IActivityStarter iActivityStarter) throws RemoteException {
        SdkSandboxController controller = mContext.getSystemService(SdkSandboxController.class);
        SdkSandboxActivityHandler activityHandler =
                activity -> {
                    try {
                        iActivityStarter.activityStartedSuccessfully();
                    } catch (RemoteException e) {
                        throw new IllegalStateException(
                                "Exception occurred while updating the client");
                    }
                };
        IBinder token = controller.registerSdkSandboxActivityHandler(activityHandler);
        controller.unregisterSdkSandboxActivityHandler(activityHandler);
        iActivityStarter.startActivity(token);
    }

    /* Sends an error if the expected resource/asset does not match the read value. */
    private String createErrorMessage(String expected, String actual) {
        return new String("Expected " + expected + ", actual " + actual);
    }

    private String getSdkPathPrefix(String dataDirectoryType) {
        final String storageDirectory = "/data/misc_" + dataDirectoryType + "/";
        return new String(
                storageDirectory
                        + CURRENT_USER_ID
                        + "/sdksandbox/"
                        + CLIENT_PACKAGE_NAME
                        + "/"
                        + SDK_NAME
                        + "@");
    }
}
