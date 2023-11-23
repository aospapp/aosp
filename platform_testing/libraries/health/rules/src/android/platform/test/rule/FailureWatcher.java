/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import android.os.FileUtils;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.runner.Description;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A rule that generates debug artifact files for failed tests. */
public class FailureWatcher extends TestWatcher {
    private static final String TAG = "FailureWatcher";
    private final UiDevice mDevice;

    public FailureWatcher() {
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    @Override
    protected void failed(Throwable e, Description description) {
        onError(mDevice, description, e);
    }

    public static File diagFile(Description description, String prefix, String ext) {
        return new File(
                getInstrumentation().getTargetContext().getFilesDir(),
                prefix
                        + "-"
                        + description.getTestClass().getSimpleName()
                        + "."
                        + description.getMethodName()
                        + "."
                        + ext);
    }

    public static void onError(UiDevice device, Description description, Throwable e) {
        if (device == null) return;
        final File sceenshot = diagFile(description, "TestScreenshot", "png");
        final File hierarchy = diagFile(description, "Hierarchy", "zip");

        // Dump window hierarchy
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(hierarchy))) {
            out.putNextEntry(new ZipEntry("bugreport.txt"));
            dumpStringCommand("dumpsys window windows", out);
            dumpStringCommand("dumpsys package", out);
            out.closeEntry();

            out.putNextEntry(new ZipEntry("visible_windows.zip"));
            dumpCommand("cmd window dump-visible-window-views", out);
            out.closeEntry();
        } catch (IOException ex) {
        }

        Log.e(
                TAG,
                "Failed test "
                        + description.getMethodName()
                        + ",\nscreenshot will be saved to "
                        + sceenshot
                        + ",\nUI dump at: "
                        + hierarchy
                        + " (use go/web-hv to open the dump file)",
                e);
        device.takeScreenshot(sceenshot);

        // Dump accessibility hierarchy
        try {
            device.dumpWindowHierarchy(diagFile(description, "AccessibilityHierarchy", "uix"));
        } catch (IOException ex) {
            Log.e(TAG, "Failed to save accessibility hierarchy", ex);
        }
    }

    private static void dumpStringCommand(String cmd, OutputStream out) throws IOException {
        out.write(("\n\n" + cmd + "\n").getBytes());
        dumpCommand(cmd, out);
    }

    private static void dumpCommand(String cmd, OutputStream out) throws IOException {
        try (AutoCloseInputStream in =
                new AutoCloseInputStream(
                        getInstrumentation().getUiAutomation().executeShellCommand(cmd))) {
            FileUtils.copy(in, out);
        }
    }
}
