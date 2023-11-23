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

package android.photopicker.cts.util;

import static android.provider.MediaStore.ACTION_PICK_IMAGES;

import android.Manifest;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class GetContentActivityAliasUtils {

    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final long POLLING_SLEEP_MILLIS = 100;

    private static ComponentName sComponentName = new ComponentName(
            getPhotoPickerPackageName(),
            "com.android.providers.media.photopicker.PhotoPickerGetContentActivity");

    public static int enableAndGetOldState() throws Exception {
        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        final PackageManager packageManager = inst.getContext().getPackageManager();
        if (isComponentEnabledSetAsExpected(packageManager,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) {
            return PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        }

        final int currentState = packageManager.getComponentEnabledSetting(sComponentName);

        updateComponentEnabledSetting(packageManager,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

        return currentState;
    }

    public static void restoreState(int oldState) throws Exception {
        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        updateComponentEnabledSetting(inst.getContext().getPackageManager(), oldState);
    }

    /**
     * Clears the package data.
     */
    public static void clearPackageData(String packageName) throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("pm clear " + packageName);

        // We should ideally be listening to an effective measure to know if package data was
        // cleared, like listening to a broadcasts or checking a value. But that information is
        // very package private and not available.
        Thread.sleep(500);
    }

    public static String getDocumentsUiPackageName() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        return getActivityPackageNameFromIntent(intent);
    }

    private static void updateComponentEnabledSetting(PackageManager packageManager,
            int state) throws Exception {
        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        inst.getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
        try {
            packageManager.setComponentEnabledSetting(sComponentName, state,
                    PackageManager.DONT_KILL_APP);
        } finally {
            inst.getUiAutomation().dropShellPermissionIdentity();
        }
        waitForComponentToBeInExpectedState(packageManager, state);
    }

    private static void waitForComponentToBeInExpectedState(PackageManager packageManager,
            int state) throws Exception {
        pollForCondition(() -> isComponentEnabledSetAsExpected(packageManager, state),
                "Timed out while waiting for component to be enabled");
    }

    private static void pollForCondition(Supplier<Boolean> condition, String errorMessage)
            throws Exception {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        throw new TimeoutException(errorMessage);
    }

    private static boolean isComponentEnabledSetAsExpected(PackageManager packageManager,
            int state) {
        return packageManager.getComponentEnabledSetting(sComponentName) == state;
    }

    @NonNull
    private static String getPhotoPickerPackageName() {
        return getActivityPackageNameFromIntent(new Intent(ACTION_PICK_IMAGES));
    }

    @NonNull
    private static String getActivityPackageNameFromIntent(@NonNull Intent intent) {
        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        final ResolveInfo ri = inst.getContext().getPackageManager().resolveActivity(intent, 0);
        return ri.activityInfo.packageName;
    }
}
