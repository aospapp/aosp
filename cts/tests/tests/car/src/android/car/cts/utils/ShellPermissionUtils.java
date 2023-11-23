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

package android.car.cts.utils;

import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.content.pm.PackageManager;
import android.support.v4.content.ContextCompat;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.ThrowingRunnable;

import org.junit.AssumptionViolatedException;

/**
 * Class contains static methods to adopt all or a subset of the shell's permissions while invoking
 * a {@link ThrowingRunnable}. Also, if an {@link AssumptionViolatedException} is thrown while
 * executing {@link ThrowingRunnable}, it will pass it through to the test infrastructure.
 *
 * TODO(b/250108245): Replace class with PermissionCheckerRule annotations.
 */
public final class ShellPermissionUtils {
    private ShellPermissionUtils() {
    }

    /**
     * Run {@code throwingRunnable} with all the shell's permissions adopted.
     */
    public static void runWithShellPermissionIdentity(ThrowingRunnable throwingRunnable) {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity();
        run(throwingRunnable, uiAutomation);
    }

    /**
     * Run {@code throwingRunnable} with a subset, {@code permissions}, of the shell's permissions
     * adopted.
     */
    public static void runWithShellPermissionIdentity(ThrowingRunnable throwingRunnable,
            String... permissions) {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity(permissions);
        for (String permission : permissions) {
            assumeTrue("Unable to adopt shell permission: " + permission,
                    ContextCompat.checkSelfPermission(
                            InstrumentationRegistry.getInstrumentation().getTargetContext(),
                            permission) == PackageManager.PERMISSION_GRANTED);
        }
        run(throwingRunnable, uiAutomation);
    }

    private static void run(ThrowingRunnable throwingRunnable, UiAutomation uiAutomation) {
        try {
            throwingRunnable.run();
        } catch (AssumptionViolatedException e) {
            // Make sure we allow AssumptionViolatedExceptions through.
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Caught exception", e);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }
}
