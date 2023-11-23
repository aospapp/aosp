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

package android.platform.test.rule;

import android.os.ParcelFileDescriptor;

import androidx.test.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.runner.Description;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Utilities for producing test artifacts. */
public class ArtifactSaver {
    private static final String TAG = ArtifactSaver.class.getSimpleName();

    // Presubmit tests have a time limit. We are not taking expensive bugreports from presubmits.
    private static boolean sShouldTakeBugreport = !PresubmitRule.runningInPresubmit();

    public static File artifactFile(String fileName) {
        return new File(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),
                fileName);
    }

    static File artifactFile(Description description, String prefix, String ext) {
        return artifactFile(
                prefix
                        + "-"
                        + description.getTestClass().getSimpleName()
                        + "."
                        + description.getMethodName()
                        + "."
                        + ext);
    }

    public static void onError(Description description, Throwable e) {
        final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        final File screenshot = artifactFile(description, "TestScreenshot", "png");
        final File hierarchy = artifactFile(description, "Hierarchy", "zip");

        device.takeScreenshot(screenshot);

        // Dump accessibility hierarchy
        try {
            device.dumpWindowHierarchy(artifactFile(description, "AccessibilityHierarchy", "uix"));
        } catch (Exception ex) {
            android.util.Log.e(TAG, "Failed to save accessibility hierarchy", ex);
        }

        // Dump window hierarchy
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(hierarchy))) {
            out.putNextEntry(new ZipEntry("bugreport.txt"));
            dumpCommandAndOutput("dumpsys window windows", out);
            dumpCommandAndOutput("dumpsys package", out);
            out.closeEntry();

            out.putNextEntry(new ZipEntry("visible_windows.zip"));
            dumpCommandOutput("cmd window dump-visible-window-views", out);
            out.closeEntry();
        } catch (IOException ex) {
        }

        android.util.Log.e(
                TAG,
                "Failed test "
                        + description.getMethodName()
                        + ",\nscreenshot will be saved to "
                        + screenshot
                        + ",\nUI dump at: "
                        + hierarchy
                        + " (use go/web-hv to open the dump file)",
                e);

        // Dump bugreport
        if (sShouldTakeBugreport && FailureWatcher.getSystemAnomalyMessage(device) != null) {
            // Taking bugreport is expensive, we should do this only once.
            sShouldTakeBugreport = false;
            dumpCommandOutput("bugreportz -s", artifactFile(description, "Bugreport", "zip"));
        }
    }

    private static void dumpCommandAndOutput(String cmd, OutputStream out) throws IOException {
        out.write(("\n\n" + cmd + "\n").getBytes());
        dumpCommandOutput(cmd, out);
    }

    public static void dumpCommandOutput(String cmd, File out) {
        try (BufferedOutputStream buffered = new BufferedOutputStream(new FileOutputStream(out))) {
            dumpCommandOutput(cmd, buffered);
        } catch (IOException ex) {
        }
    }

    private static void dumpCommandOutput(String cmd, OutputStream out) throws IOException {
        try (ParcelFileDescriptor.AutoCloseInputStream in =
                new ParcelFileDescriptor.AutoCloseInputStream(
                        InstrumentationRegistry.getInstrumentation()
                                .getUiAutomation()
                                .executeShellCommand(cmd))) {
            android.os.FileUtils.copy(in, out);
        }
    }
}
