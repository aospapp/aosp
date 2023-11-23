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

package android.telephony.cts.util;

import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static org.junit.Assert.assertTrue;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;
import android.telephony.TelephonyManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Assume;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

public class DefaultSmsAppHelper {
    public static void ensureDefaultSmsApp() {
        if (!hasTelephony() || !hasSms()) {
            return;
        }

        Context context = ApplicationProvider.getApplicationContext();

        String packageName = context.getPackageName();
        RoleManager roleManager = context.getSystemService(RoleManager.class);
        Executor executor = context.getMainExecutor();
        UserHandle user = Process.myUserHandle();

        CountDownLatch latch = new CountDownLatch(1);
        boolean[] success = new boolean[1];

        runWithShellPermissionIdentity(() -> {
            roleManager.addRoleHolderAsUser(
                    RoleManager.ROLE_SMS,
                    packageName,
                    RoleManager.MANAGE_HOLDERS_FLAG_DONT_KILL_APP,
                    user,
                    executor,
                    successful -> {
                        success[0] = successful;
                        latch.countDown();
                    });
        });

        try {
            latch.await();
            assertTrue(success[0]);
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex.getMessage());
        }
    }

    public static void stopBeingDefaultSmsApp() {
        if (!hasSms()) {
            return;
        }
        Context context = ApplicationProvider.getApplicationContext();

        String packageName = context.getPackageName();
        RoleManager roleManager = context.getSystemService(RoleManager.class);
        Executor executor = context.getMainExecutor();
        UserHandle user = Process.myUserHandle();

        CountDownLatch latch = new CountDownLatch(1);
        boolean[] success = new boolean[1];

        runWithShellPermissionIdentity(() -> {
            roleManager.removeRoleHolderAsUser(
                    RoleManager.ROLE_SMS,
                    packageName,
                    RoleManager.MANAGE_HOLDERS_FLAG_DONT_KILL_APP,
                    user,
                    executor,
                    successful -> {
                        success[0] = successful;
                        latch.countDown();
                    });
        });

        try {
            latch.await();
            assertTrue(success[0]);
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex.getMessage());
        }
    }

    public static void assumeTelephony() {
        Assume.assumeTrue(hasTelephony());
    }

    public static void assumeMessaging() {
        Assume.assumeTrue(hasSms());
    }

    public static boolean hasTelephony() {
        Context context = ApplicationProvider.getApplicationContext();
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    public static boolean hasSms() {
        TelephonyManager telephonyManager = (TelephonyManager)
                ApplicationProvider.getApplicationContext().getSystemService(
                        Context.TELEPHONY_SERVICE);
        return telephonyManager.isSmsCapable();
    }
}
