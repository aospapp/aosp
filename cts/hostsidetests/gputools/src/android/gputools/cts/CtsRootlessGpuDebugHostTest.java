/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.gputools.cts;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import java.util.Scanner;

import org.junit.After;
import org.junit.Before;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests that exercise Rootless GPU Debug functionality supported by the loader.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class CtsRootlessGpuDebugHostTest extends BaseHostJUnit4Test implements IDeviceTest {

    public static final String TAG = "RootlessGpuDebugDeviceActivity";

    // This test ensures that the Vulkan and GLES loaders can use Settings to load layers
    // from the base directory of debuggable applications.  Is also tests several
    // positive and negative scenarios we want to cover (listed below).
    //
    // There are three APKs; DEBUG and RELEASE are practically identical with one
    // being flagged as debuggable.  The LAYERS APK is mainly a conduit for getting
    // layers onto the device without affecting the other APKs.
    //
    // The RELEASE APK does contain one layer to ensure using Settings to enable
    // layers does not interfere with legacy methods using system properties.
    //
    // The layers themselves are practically null, only enough functionality to
    // satisfy loader enumerating and loading.  They don't actually chain together.
    //
    // Positive Vulkan tests
    // - Ensure we can toggle the Enable Setting on and off (testDebugLayerLoadVulkan)
    // - Ensure we can set the debuggable app (testDebugLayerLoadVulkan)
    // - Ensure we can set the layer list (testDebugLayerLoadVulkan)
    // - Ensure we can push a layer to debuggable app (testDebugLayerLoadVulkan)
    // - Ensure we can specify the app to load layers (testDebugLayerLoadVulkan)
    // - Ensure we can load a layer from app's data directory (testDebugLayerLoadVulkan)
    // - Ensure we can load multiple layers, in order, from app's data directory (testDebugLayerLoadVulkan)
    // - Ensure we can still use system properties if no layers loaded via Settings (testSystemPropertyEnableVulkan)
    // - Ensure we can find layers in separate specified app (testDebugLayerLoadExternalVulkan)
    // Negative Vulkan tests
    // - Ensure we cannot push a layer to non-debuggable app (testReleaseLayerLoadVulkan)
    // - Ensure non-debuggable app ignores the new Settings (testReleaseLayerLoadVulkan)
    // - Ensure we cannot enumerate layers from debuggable app's data directory if Setting not specified (testDebugNoEnumerateVulkan)
    // - Ensure we cannot enumerate layers without specifying the debuggable app (testDebugNoEnumerateVulkan)
    // - Ensure we cannot use system properties when layer is found via Settings with debuggable app (testSystemPropertyIgnoreVulkan)
    //
    // Positive GLES tests
    // - Ensure we can toggle the Enable Setting on and off (testDebugLayerLoadGLES)
    // - Ensure we can set the debuggable app (testDebugLayerLoadGLES)
    // - Ensure we can set the layer list (testDebugLayerLoadGLES)
    // - Ensure we can push a layer to debuggable app (testDebugLayerLoadGLES)
    // - Ensure we can specify the app to load layers (testDebugLayerLoadGLES)
    // - Ensure we can load a layer from app's data directory (testDebugLayerLoadGLES)
    // - Ensure we can load multiple layers, in order, from app's data directory (testDebugLayerLoadGLES)
    // - Ensure we can find layers in separate specified app (testDebugLayerLoadExternalGLES)
    // Negative GLES tests
    // - Ensure we cannot push a layer to non-debuggable app (testReleaseLayerLoadGLES)
    // - Ensure non-debuggable app ignores the new Settings (testReleaseLayerLoadGLES)
    // - Ensure we cannot enumerate layers from debuggable app's data directory if Setting not specified (testDebugNoEnumerateGLES)
    // - Ensure we cannot enumerate layers without specifying the debuggable app (testDebugNoEnumerateGLES)
    //
    // Positive combined tests
    // - Ensure we can load Vulkan and GLES layers at the same time, from multiple external apps (testMultipleExternalApps)



    private static final String CLASS = "RootlessGpuDebugDeviceActivity";
    private static final String ACTIVITY = "android.rootlessgpudebug.app.RootlessGpuDebugDeviceActivity";
    private static final String LAYER_A = "nullLayerA";
    private static final String LAYER_B = "nullLayerB";
    private static final String LAYER_C = "nullLayerC";
    private static final String LAYER_A_LIB = "libVkLayer_" + LAYER_A + ".so";
    private static final String LAYER_B_LIB = "libVkLayer_" + LAYER_B + ".so";
    private static final String LAYER_C_LIB = "libVkLayer_" + LAYER_C + ".so";
    private static final String LAYER_A_NAME = "VK_LAYER_ANDROID_" + LAYER_A;
    private static final String LAYER_B_NAME = "VK_LAYER_ANDROID_" + LAYER_B;
    private static final String LAYER_C_NAME = "VK_LAYER_ANDROID_" + LAYER_C;
    private static final String DEBUG_APP = "android.rootlessgpudebug.DEBUG.app";
    private static final String RELEASE_APP = "android.rootlessgpudebug.RELEASE.app";
    private static final String LAYERS_APP = "android.rootlessgpudebug.LAYERS.app";
    private static final String GLES_LAYERS_APP = "android.rootlessgpudebug.GLES_LAYERS.app";
    private static final String DEBUG_APK = "CtsGpuToolsRootlessGpuDebugApp-DEBUG.apk";
    private static final String RELEASE_APK = "CtsGpuToolsRootlessGpuDebugApp-RELEASE.apk";
    private static final String LAYERS_APK = "CtsGpuToolsRootlessGpuDebugApp-LAYERS.apk";
    private static final String GLES_LAYERS_APK = "CtsGpuToolsRootlessGpuDebugApp-GLES_LAYERS.apk";
    private static final String GLES_LAYER_A = "glesLayerA";
    private static final String GLES_LAYER_B = "glesLayerB";
    private static final String GLES_LAYER_C = "glesLayerC";
    private static final String GLES_LAYER_A_LIB = "libGLES_" + GLES_LAYER_A + ".so";
    private static final String GLES_LAYER_B_LIB = "libGLES_" + GLES_LAYER_B + ".so";
    private static final String GLES_LAYER_C_LIB = "libGLES_" + GLES_LAYER_C + ".so";

    private static boolean initialized = false;

    // This is how long we'll scan the log for a result before giving up. This limit will only
    // be reached if something has gone wrong
    private static final long LOG_SEARCH_TIMEOUT_MS = 5000;

    private static final long SETTING_APPLY_TIMEOUT_MS = 5000;

    private String removeWhitespace(String input) {
        return input.replaceAll(System.getProperty("line.separator"), "").trim();
    }

    /**
     * Return current timestamp in format accepted by logcat
     */
    private String getTime() throws Exception {
        // logcat will accept "MM-DD hh:mm:ss.mmm"
        return getDevice().executeShellCommand("date +\"%m-%d %H:%M:%S.%3N\"");
    }

    /**
     * Apply a setting and ensure it sticks before continuing
     */
    private void applySetting(String setting, String value) throws Exception {
        getDevice().executeShellCommand("settings put global " + setting + " " + value);

        long hostStartTime = System.currentTimeMillis();
        while (((System.currentTimeMillis() - hostStartTime) < SETTING_APPLY_TIMEOUT_MS)) {

            // Give the setting a chance to apply
            Thread.sleep(1000);

            // Read it back, make sure it has applied
            String returnedValue = getDevice().executeShellCommand("settings get global " + setting);
            if ((returnedValue != null) && (returnedValue.trim().equals(value))) {
                return;
            }
        }

        // If this assert fires, try increasing the timeout
        Assert.fail("Unable to set global setting (" + setting + ") to (" + value + ") before timout (" +
                SETTING_APPLY_TIMEOUT_MS + "ms)");
    }

    /**
     * Delete a setting and ensure it goes away before continuing
     */
    private void deleteSetting(String setting) throws Exception {
        getDevice().executeShellCommand("shell settings delete global " + setting);

        long hostStartTime = System.currentTimeMillis();
        while (((System.currentTimeMillis() - hostStartTime) < SETTING_APPLY_TIMEOUT_MS)) {

            // Give the setting a chance to apply
            Thread.sleep(1000);

            // Read it back, make sure it is gone
            String returnedValue = getDevice().executeShellCommand("settings get global " + setting);
            if ((returnedValue == null) ||
                (returnedValue.trim().isEmpty()) ||
                (returnedValue.trim().equals("null"))) {
                return;
            }
        }

        // If this assert fires, try increasing the timeout
        Assert.fail("Unable to delete global setting (" + setting + ") before timout (" +
                SETTING_APPLY_TIMEOUT_MS + "ms)");
    }

    /**
     * Extract the requested layer from APK and copy to tmp
     */
    private void setupLayer(String layer, String layerApp) throws Exception {

        // We use the LAYERS apk to facilitate getting layers onto the device for mixing and matching
        String libPath = getDevice().executeAdbCommand("shell", "pm", "path", layerApp);
        libPath = libPath.replaceAll("package:", "");
        libPath = libPath.replaceAll("base.apk", "");
        libPath = removeWhitespace(libPath);
        libPath += "lib/";

        // Use find to get the .so so we can ignore ABI
        String layerPath = getDevice().executeAdbCommand("shell", "find", libPath + " -name " + layer);
        layerPath = removeWhitespace(layerPath);
        getDevice().executeAdbCommand("shell", "cp", layerPath + " /data/local/tmp");
    }

    /**
     * Simple helper class for returning multiple results
     */
    public class LogScanResult {
        public boolean found;
        public int lineNumber;
    }

    private LogScanResult scanLog(String tag, String searchString, String appStartTime) throws Exception {
        return scanLog(tag, searchString, "", appStartTime);
    }

    /**
     * Scan the logcat for requested layer tag, returning if found and which line
     */
    private LogScanResult scanLog(String tag, String searchString, String endString, String appStartTime) throws Exception {

        LogScanResult result = new LogScanResult();
        result.found = false;
        result.lineNumber = -1;

        // Scan until output from app is found
        boolean scanComplete= false;

        // Let the test run a reasonable amount of time before moving on
        long hostStartTime = System.currentTimeMillis();

        while (!scanComplete && ((System.currentTimeMillis() - hostStartTime) < LOG_SEARCH_TIMEOUT_MS)) {

            // Give our activity a chance to run and fill the log
            Thread.sleep(1000);

            // Pull the logcat since the app started, filter for tags
            // This command should look something like this:
            // adb logcat -d -t '03-27 21:35:05.392' -s "RootlessGpuDebugDeviceActivity,nullLayerC"
            String logcat = getDevice().executeShellCommand(
                    "logcat -d " +
                    "-t '" + removeWhitespace(appStartTime) + "' " +
                    "-s \"" + tag + "\"");
            int lineNumber = 0;
            Scanner apkIn = new Scanner(logcat);
            while (apkIn.hasNextLine()) {
                lineNumber++;
                String line = apkIn.nextLine();
                if (line.contains(searchString) && line.endsWith(endString)) {
                    result.found = true;
                    result.lineNumber = lineNumber;
                }
                if (line.contains("RootlessGpuDebug activity complete")) {
                    // Once we've got output from the app, we've collected what we need
                    scanComplete= true;
                }
            }
            apkIn.close();
        }

        // If this assert fires , try increasing the timeout
        Assert.assertTrue("Log scanning did not complete before timout (" +
                LOG_SEARCH_TIMEOUT_MS + "ms)", scanComplete);

        return result;
    }

    /**
     * Remove any temporary files on the device, clear any settings, kill the apps after each test
     */
    @After
    public void cleanup() throws Exception {
        getDevice().executeAdbCommand("shell", "am", "force-stop", DEBUG_APP);
        getDevice().executeAdbCommand("shell", "am", "force-stop", RELEASE_APP);
        getDevice().executeAdbCommand("shell", "rm", "-f", "/data/local/tmp/" + LAYER_A_LIB);
        getDevice().executeAdbCommand("shell", "rm", "-f", "/data/local/tmp/" + LAYER_B_LIB);
        getDevice().executeAdbCommand("shell", "rm", "-f", "/data/local/tmp/" + LAYER_C_LIB);
        getDevice().executeAdbCommand("shell", "rm", "-f", "/data/local/tmp/" + GLES_LAYER_A_LIB);
        getDevice().executeAdbCommand("shell", "rm", "-f", "/data/local/tmp/" + GLES_LAYER_B_LIB);
        getDevice().executeAdbCommand("shell", "rm", "-f", "/data/local/tmp/" + GLES_LAYER_C_LIB);
        getDevice().executeAdbCommand("shell", "settings", "delete", "global", "enable_gpu_debug_layers");
        getDevice().executeAdbCommand("shell", "settings", "delete", "global", "gpu_debug_app");
        getDevice().executeAdbCommand("shell", "settings", "delete", "global", "gpu_debug_layers");
        getDevice().executeAdbCommand("shell", "settings", "delete", "global", "gpu_debug_layers_gles");
        getDevice().executeAdbCommand("shell", "settings", "delete", "global", "gpu_debug_layer_app");
        getDevice().executeAdbCommand("shell", "setprop", "debug.vulkan.layers", "\'\'");
        getDevice().executeAdbCommand("shell", "setprop", "debug.gles.layers", "\'\'");
    }

    /**
     * Clean up before starting any tests, and ensure supporting packages are installed
     */
    @Before
    public void init() throws Exception {
        installPackage(DEBUG_APK);
        installPackage(RELEASE_APK);
        installPackage(LAYERS_APK);
        installPackage(GLES_LAYERS_APK);
        if (!initialized) {
            cleanup();
            initialized = true;
        }
    }

    /**
     * This is the primary test of the feature. It pushes layers to our debuggable app and ensures they are
     * loaded in the correct order.
     */
    @Test
    public void testDebugLayerLoadVulkan() throws Exception {

        // Set up layers to be loaded
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", DEBUG_APP);
        applySetting("gpu_debug_layers", LAYER_A_NAME + ":" + LAYER_B_NAME);

        // Copy the layers from our LAYERS APK to tmp
        setupLayer(LAYER_A_LIB, LAYERS_APP);
        setupLayer(LAYER_B_LIB, LAYERS_APP);


        // Copy them over to our DEBUG app
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + LAYER_A_LIB, "|",
                "run-as", DEBUG_APP, "--user", Integer.toString(getDevice().getCurrentUser()),
                "sh", "-c", "\'cat", ">", LAYER_A_LIB, ";", "chmod", "700", LAYER_A_LIB + "\'");
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + LAYER_B_LIB, "|",
                "run-as", DEBUG_APP, "--user", Integer.toString(getDevice().getCurrentUser()),
                "sh", "-c", "\'cat", ">", LAYER_B_LIB, ";", "chmod", "700", LAYER_B_LIB + "\'");


        // Kick off our DEBUG app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", DEBUG_APP + "/" + ACTIVITY);

        // Check that both layers were loaded, in the correct order
        String searchStringA = "nullCreateInstance called in " + LAYER_A;
        LogScanResult resultA = scanLog(TAG + "," + LAYER_A + "," + LAYER_B, searchStringA, appStartTime);
        Assert.assertTrue("LayerA was not loaded", resultA.found);

        String searchStringB = "nullCreateInstance called in " + LAYER_B;
        LogScanResult resultB = scanLog(TAG + "," + LAYER_A + "," + LAYER_B, searchStringB, appStartTime);
        Assert.assertTrue("LayerB was not loaded", resultB.found);

        Assert.assertTrue("LayerA should be loaded before LayerB", resultA.lineNumber < resultB.lineNumber);
    }

    /**
     * This test ensures that we cannot push a layer to a non-debuggable app
     * It also ensures non-debuggable apps ignore Settings and don't enumerate layers in the base directory.
     */
    @Test
    public void testReleaseLayerLoadVulkan() throws Exception {

        // Set up a layers to be loaded for RELEASE app
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", RELEASE_APP);
        applySetting("gpu_debug_layers", LAYER_A_NAME + ":" + LAYER_B_NAME);

        // Copy a layer from our LAYERS APK to tmp
        setupLayer(LAYER_A_LIB, LAYERS_APP);

        // Attempt to copy them over to our RELEASE app (this should fail)
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + LAYER_A_LIB, "|",
                "run-as", RELEASE_APP, "--user", Integer.toString(getDevice().getCurrentUser()),
                "sh", "-c", "\'cat", ">", LAYER_A_LIB, ";", "chmod", "700", LAYER_A_LIB + "\'", "||", "echo", "run-as", "failed");

        // Kick off our RELEASE app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", RELEASE_APP + "/" + ACTIVITY);

        // Ensure we don't load the layer in base dir
        String searchStringA = LAYER_A_NAME + "loaded";
        LogScanResult resultA = scanLog(TAG + "," + LAYER_A, searchStringA, appStartTime);
        Assert.assertFalse("LayerA was enumerated", resultA.found);
    }

    /**
     * This test ensures debuggable apps do not enumerate layers in base
     * directory if enable_gpu_debug_layers is not enabled.
     */
    @Test
    public void testDebugNotEnabledVulkan() throws Exception {

        // Ensure the global layer enable settings is NOT enabled
        applySetting("enable_gpu_debug_layers", "0");
        applySetting("gpu_debug_app", DEBUG_APP);
        applySetting("gpu_debug_layers", LAYER_A_NAME);

        // Copy a layer from our LAYERS APK to tmp
        setupLayer(LAYER_A_LIB, LAYERS_APP);

        // Copy it over to our DEBUG app
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + LAYER_A_LIB, "|",
                "run-as", DEBUG_APP, "--user", Integer.toString(getDevice().getCurrentUser()),
                "sh", "-c", "\'cat", ">", LAYER_A_LIB, ";", "chmod", "700", LAYER_A_LIB + "\'");

        // Kick off our DEBUG app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", DEBUG_APP + "/" + ACTIVITY);

        // Ensure we don't load the layer in base dir
        String searchStringA = LAYER_A_NAME + "loaded";
        LogScanResult resultA = scanLog(TAG + "," + LAYER_A, searchStringA, appStartTime);
        Assert.assertFalse("LayerA was enumerated", resultA.found);
    }

    /**
     * This test ensures debuggable apps do not enumerate layers in base
     * directory if gpu_debug_app does not match.
     */
    @Test
    public void testDebugWrongAppVulkan() throws Exception {

        // Ensure the gpu_debug_app does not match what we launch
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", RELEASE_APP);
        applySetting("gpu_debug_layers", LAYER_A_NAME);

        // Copy a layer from our LAYERS APK to tmp
        setupLayer(LAYER_A_LIB, LAYERS_APP);

        // Copy it over to our DEBUG app
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + LAYER_A_LIB, "|",
                "run-as", DEBUG_APP, "--user", Integer.toString(getDevice().getCurrentUser()),
                "sh", "-c", "\'cat", ">", LAYER_A_LIB, ";", "chmod", "700", LAYER_A_LIB + "\'");

        // Kick off our DEBUG app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", DEBUG_APP + "/" + ACTIVITY);

        // Ensure we don't load the layer in base dir
        String searchStringA = LAYER_A_NAME + "loaded";
        LogScanResult resultA = scanLog(TAG + "," + LAYER_A, searchStringA, appStartTime);
        Assert.assertFalse("LayerA was enumerated", resultA.found);
    }

    /**
     * This test ensures debuggable apps do not enumerate layers in base
     * directory if gpu_debug_layers are not set.
     */
    @Test
    public void testDebugNoLayersEnabledVulkan() throws Exception {

        // Ensure the global layer enable settings is NOT enabled
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", DEBUG_APP);
        applySetting("gpu_debug_layers", "foo");

        // Copy a layer from our LAYERS APK to tmp
        setupLayer(LAYER_A_LIB, LAYERS_APP);

        // Copy it over to our DEBUG app
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + LAYER_A_LIB, "|",
                "run-as", DEBUG_APP, "--user", Integer.toString(getDevice().getCurrentUser()),
                "sh", "-c", "\'cat", ">", LAYER_A_LIB, ";", "chmod", "700", LAYER_A_LIB + "\'");

        // Kick off our DEBUG app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", DEBUG_APP + "/" + ACTIVITY);

        // Ensure layerA is not loaded
        String searchStringA = "nullCreateInstance called in " + LAYER_A;
        LogScanResult resultA = scanLog(TAG + "," + LAYER_A, searchStringA, appStartTime);
        Assert.assertFalse("LayerA was loaded", resultA.found);
    }

    /**
     * This test ensures we can still use properties if no layer specified via Settings
     */
    @Test
    public void testSystemPropertyEnableVulkan() throws Exception {

        // Don't enable any layers via settings
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", RELEASE_APP);
        deleteSetting("gpu_debug_layers");

        // Enable layerC (which is packaged with the RELEASE app) with system properties
        getDevice().executeAdbCommand("shell", "setprop", "debug.vulkan.layers " + LAYER_C_NAME);

        // Kick off our RELEASE app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", RELEASE_APP + "/" + ACTIVITY);

        // Check that only layerC was loaded
        String searchStringA = LAYER_A_NAME + "loaded";
        LogScanResult resultA = scanLog(TAG + "," + LAYER_A, searchStringA, appStartTime);
        Assert.assertFalse("LayerA was enumerated", resultA.found);

        String searchStringC = "nullCreateInstance called in " + LAYER_C;
        LogScanResult resultC = scanLog(TAG + "," + LAYER_C, searchStringC, appStartTime);
        Assert.assertTrue("LayerC was not loaded", resultC.found);
    }

    /**
     * This test ensures system properties are ignored if Settings load a layer
     */
    @Test
    public void testSystemPropertyIgnoreVulkan() throws Exception {

        // Set up layerA to be loaded, but not layerB
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", DEBUG_APP);
        applySetting("gpu_debug_layers", LAYER_A_NAME);

        // Copy the layers from our LAYERS APK
        setupLayer(LAYER_A_LIB, LAYERS_APP);
        setupLayer(LAYER_B_LIB, LAYERS_APP);

        // Copy them over to our DEBUG app
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + LAYER_A_LIB, "|",
                "run-as", DEBUG_APP, "--user", Integer.toString(getDevice().getCurrentUser()),
                "sh", "-c", "\'cat", ">", LAYER_A_LIB, ";", "chmod", "700", LAYER_A_LIB + "\'");
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + LAYER_B_LIB, "|",
                "run-as", DEBUG_APP, "--user", Integer.toString(getDevice().getCurrentUser()),
                "sh", "-c", "\'cat", ">", LAYER_B_LIB, ";", "chmod", "700", LAYER_B_LIB + "\'");

        // Enable layerB with system properties
        getDevice().executeAdbCommand("shell", "setprop", "debug.vulkan.layers " + LAYER_B_NAME);

        // Kick off our DEBUG app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", DEBUG_APP + "/" + ACTIVITY);

        // Ensure only layerA is loaded
        String searchStringA = "nullCreateInstance called in " + LAYER_A;
        LogScanResult resultA = scanLog(TAG + "," + LAYER_A, searchStringA, appStartTime);
        Assert.assertTrue("LayerA was not loaded", resultA.found);

        String searchStringB = "nullCreateInstance called in " + LAYER_B;
        LogScanResult resultB = scanLog(TAG + "," + LAYER_B, searchStringB, appStartTime);
        Assert.assertFalse("LayerB was loaded", resultB.found);
    }

    /**
     *
     */
    @Test
    public void testDebugLayerLoadExternalVulkan() throws Exception {

        // Set up layers to be loaded
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", DEBUG_APP);
        applySetting("gpu_debug_layers", LAYER_C_NAME);

        // Specify the external app that hosts layers
        applySetting("gpu_debug_layer_app", LAYERS_APP);

        // Kick off our DEBUG app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", DEBUG_APP + "/" + ACTIVITY);

        // Check that our external layer was loaded
        String searchStringC = "nullCreateInstance called in " + LAYER_C;
        LogScanResult resultC = scanLog(TAG + "," + LAYER_C, searchStringC, appStartTime);
        Assert.assertTrue("LayerC was not loaded", resultC.found);
    }


    /**
     * This test pushes GLES layers to our debuggable app and ensures they are
     * loaded in the correct order.
     */
    @Test
    public void testDebugLayerLoadGLES() throws Exception {

        // Set up layers to be loaded
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", DEBUG_APP);
        applySetting("gpu_debug_layers_gles", GLES_LAYER_A_LIB + ":" + GLES_LAYER_B_LIB);

        // Copy the layers from our LAYERS APK to tmp
        setupLayer(GLES_LAYER_A_LIB, GLES_LAYERS_APP);
        setupLayer(GLES_LAYER_B_LIB, GLES_LAYERS_APP);

        // Copy them over to our DEBUG app
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + GLES_LAYER_A_LIB, "|",
            "run-as", DEBUG_APP, "--user", Integer.toString(getDevice().getCurrentUser()),
            "sh", "-c", "\'cat", ">", GLES_LAYER_A_LIB, ";", "chmod", "700", GLES_LAYER_A_LIB + "\'");
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + GLES_LAYER_B_LIB, "|",
            "run-as", DEBUG_APP, "--user", Integer.toString(getDevice().getCurrentUser()),
            "sh", "-c", "\'cat", ">", GLES_LAYER_B_LIB, ";", "chmod", "700", GLES_LAYER_B_LIB + "\'");

        // Kick off our DEBUG app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", DEBUG_APP + "/" + ACTIVITY);

        // Check that both layers were loaded, in the correct order
        String searchStringA = "glesLayer_eglChooseConfig called in " + GLES_LAYER_A;
        LogScanResult resultA = scanLog(TAG + "," + GLES_LAYER_A + "," + GLES_LAYER_B, searchStringA, appStartTime);
        Assert.assertTrue(GLES_LAYER_A + " was not loaded", resultA.found);

        String searchStringB = "glesLayer_eglChooseConfig called in " + GLES_LAYER_B;
        LogScanResult resultB = scanLog(TAG + "," + GLES_LAYER_A + "," + GLES_LAYER_B, searchStringB, appStartTime);
        Assert.assertTrue(GLES_LAYER_B + " was not loaded", resultB.found);

        Assert.assertTrue(GLES_LAYER_A + " should be loaded before " + GLES_LAYER_B, resultA.lineNumber < resultB.lineNumber);
    }

    /**
     * This test ensures that we cannot push a layer to a non-debuggable GLES app
     * It also ensures non-debuggable apps ignore Settings and don't enumerate layers in the base directory.
     */
    @Test
    public void testReleaseLayerLoadGLES() throws Exception {

        // Set up a layers to be loaded for RELEASE app
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", RELEASE_APP);
        applySetting("gpu_debug_layers_gles", GLES_LAYER_A_LIB + ":" + GLES_LAYER_B_LIB);
        deleteSetting("gpu_debug_layer_app");

        // Copy a layer from our LAYERS APK to tmp
        setupLayer(GLES_LAYER_A_LIB, GLES_LAYERS_APP);

        // Attempt to copy them over to our RELEASE app (this should fail)
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + GLES_LAYER_A_LIB, "|", "run-as", RELEASE_APP,
                                   "sh", "-c", "\'cat", ">", GLES_LAYER_A_LIB, ";", "chmod", "700", GLES_LAYER_A_LIB + "\'", "||", "echo", "run-as", "failed");

        // Kick off our RELEASE app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", RELEASE_APP + "/" + ACTIVITY);

        // Ensure we don't load the layer in base dir
        String searchStringA = GLES_LAYER_A + " loaded";
        LogScanResult resultA = scanLog(TAG + "," + GLES_LAYER_A, searchStringA, appStartTime);
        Assert.assertFalse(GLES_LAYER_A + " was enumerated", resultA.found);
    }

    /**
     * This test ensures debuggable GLES apps do not enumerate layers in base
     * directory if enable_gpu_debug_layers is not enabled.
     */
    @Test
    public void testDebugNotEnabledGLES() throws Exception {

        // Ensure the global layer enable settings is NOT enabled
        applySetting("enable_gpu_debug_layers", "0");
        applySetting("gpu_debug_app", DEBUG_APP);
        applySetting("gpu_debug_layers_gles", GLES_LAYER_A_LIB);

        // Copy a layer from our LAYERS APK to tmp
        setupLayer(GLES_LAYER_A_LIB, GLES_LAYERS_APP);

        // Copy it over to our DEBUG app
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + GLES_LAYER_A_LIB, "|", "run-as", DEBUG_APP,
                                  "sh", "-c", "\'cat", ">", GLES_LAYER_A_LIB, ";", "chmod", "700", GLES_LAYER_A_LIB + "\'");

        // Kick off our DEBUG app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", DEBUG_APP + "/" + ACTIVITY);

        // Ensure we don't load the layer in base dir
        String searchStringA = GLES_LAYER_A + " loaded";
        LogScanResult resultA = scanLog(TAG + "," + GLES_LAYER_A, searchStringA, appStartTime);
        Assert.assertFalse(GLES_LAYER_A + " was enumerated", resultA.found);
    }

    /**
     * This test ensures debuggable GLES apps do not enumerate layers in base
     * directory if gpu_debug_app does not match.
     */
    @Test
    public void testDebugWrongAppGLES() throws Exception {

        // Ensure the gpu_debug_app does not match what we launch
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", RELEASE_APP);
        applySetting("gpu_debug_layers_gles", GLES_LAYER_A_LIB);

        // Copy a layer from our LAYERS APK to tmp
        setupLayer(GLES_LAYER_A_LIB, GLES_LAYERS_APP);

        // Copy it over to our DEBUG app
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + GLES_LAYER_A_LIB, "|", "run-as", DEBUG_APP,
                                  "sh", "-c", "\'cat", ">", GLES_LAYER_A_LIB, ";", "chmod", "700", GLES_LAYER_A_LIB + "\'");

        // Kick off our DEBUG app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", DEBUG_APP + "/" + ACTIVITY);

        // Ensure we don't load the layer in base dir
        String searchStringA = GLES_LAYER_A + " loaded";
        LogScanResult resultA = scanLog(TAG + "," + GLES_LAYER_A, searchStringA, appStartTime);
        Assert.assertFalse(GLES_LAYER_A + " was enumerated", resultA.found);
    }

    /**
     * This test ensures debuggable GLES apps do not enumerate layers in base
     * directory if gpu_debug_layers are not set.
     */
    @Test
    public void testDebugNoLayersEnabledGLES() throws Exception {

        // Ensure the global layer enable settings is NOT enabled
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", DEBUG_APP);
        applySetting("gpu_debug_layers_gles", "foo");

        // Copy a layer from our LAYERS APK to tmp
        setupLayer(GLES_LAYER_A_LIB, GLES_LAYERS_APP);

        // Copy it over to our DEBUG app
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + GLES_LAYER_A_LIB, "|", "run-as", DEBUG_APP,
                                  "sh", "-c", "\'cat", ">", GLES_LAYER_A_LIB, ";", "chmod", "700", GLES_LAYER_A_LIB + "\'");

        // Kick off our DEBUG app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", DEBUG_APP + "/" + ACTIVITY);

        // Ensure layerA is not loaded
        String searchStringA = "glesLayer_eglChooseConfig called in " + GLES_LAYER_A;
        LogScanResult resultA = scanLog(TAG + "," + GLES_LAYER_A, searchStringA, appStartTime);
        Assert.assertFalse(GLES_LAYER_A + " was loaded", resultA.found);
    }

    /**
     * This test ensures we can still use properties if no GLES layers are specified
     */
    @Test
    public void testSystemPropertyEnableGLES() throws Exception {

        // Set up layerA to be loaded, but not layerB or layerC
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", RELEASE_APP);
        deleteSetting("gpu_debug_layers_gles");

        // Enable layerC (which is packaged with the RELEASE app) with system properties
        getDevice().executeAdbCommand("shell", "setprop", "debug.gles.layers " + GLES_LAYER_C_LIB);

        // Kick off our RELEASE app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", RELEASE_APP + "/" + ACTIVITY);

        // Check that both layers were loaded, in the correct order
        String searchStringA = GLES_LAYER_A + "loaded";
        LogScanResult resultA = scanLog(TAG + "," + GLES_LAYER_A, searchStringA, appStartTime);
        Assert.assertFalse(GLES_LAYER_A + " was enumerated", resultA.found);

        String searchStringC = "glesLayer_eglChooseConfig called in " + GLES_LAYER_C;
        LogScanResult resultC = scanLog(TAG + "," + GLES_LAYER_C, searchStringC, appStartTime);
        Assert.assertTrue(GLES_LAYER_C + " was not loaded", resultC.found);
    }

    /**
     * This test ensures system properties are ignored if Settings load a GLES layer
     */
    @Test
    public void testSystemPropertyIgnoreGLES() throws Exception {

        // Set up layerA to be loaded, but not layerB
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", DEBUG_APP);
        applySetting("gpu_debug_layers_gles", GLES_LAYER_A_LIB);

        // Copy the layers from our LAYERS APK
        setupLayer(GLES_LAYER_A_LIB, GLES_LAYERS_APP);
        setupLayer(GLES_LAYER_B_LIB, GLES_LAYERS_APP);

        // Copy them over to our DEBUG app
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + GLES_LAYER_A_LIB, "|",
            "run-as", DEBUG_APP, "--user", Integer.toString(getDevice().getCurrentUser()),
            "sh", "-c", "\'cat", ">", GLES_LAYER_A_LIB, ";", "chmod", "700", GLES_LAYER_A_LIB + "\'");
        getDevice().executeAdbCommand("shell", "cat", "/data/local/tmp/" + GLES_LAYER_B_LIB, "|",
            "run-as", DEBUG_APP, "--user", Integer.toString(getDevice().getCurrentUser()),
            "sh", "-c", "\'cat", ">", GLES_LAYER_B_LIB, ";", "chmod", "700", GLES_LAYER_B_LIB + "\'");

        // Enable layerB with system properties
        getDevice().executeAdbCommand("shell", "setprop", "debug.gles.layers " + GLES_LAYER_B_LIB);

        // Kick off our DEBUG app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", DEBUG_APP + "/" + ACTIVITY);

        // Ensure only layerA is loaded
        String searchStringA = "glesLayer_eglChooseConfig called in " + GLES_LAYER_A;
        LogScanResult resultA = scanLog(TAG + "," + GLES_LAYER_A, searchStringA, appStartTime);
        Assert.assertTrue(GLES_LAYER_A + " was not loaded", resultA.found);

        String searchStringB = "glesLayer_eglChooseConfig called in " + GLES_LAYER_B;
        LogScanResult resultB = scanLog(TAG + "," + GLES_LAYER_B, searchStringB, appStartTime);
        Assert.assertFalse(GLES_LAYER_B + " was loaded", resultB.found);
    }

    /**
     *
     */
    @Test
    public void testDebugLayerLoadExternalGLES() throws Exception {

        // Set up layers to be loaded
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", DEBUG_APP);
        applySetting("gpu_debug_layers_gles", GLES_LAYER_C_LIB);

        // Specify the external app that hosts layers
        applySetting("gpu_debug_layer_app", GLES_LAYERS_APP);

        // Kick off our DEBUG app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", DEBUG_APP + "/" + ACTIVITY);

        // Check that our external layer was loaded
        String searchStringC = "glesLayer_eglChooseConfig called in " + GLES_LAYER_C;
        LogScanResult resultC = scanLog(TAG + "," + GLES_LAYER_C, searchStringC, appStartTime);
        Assert.assertTrue(GLES_LAYER_C + " was not loaded", resultC.found);
    }

    /**
     *
     */
    @Test
    public void testMultipleExternalApps() throws Exception {

        // Set up layers to be loaded
        applySetting("enable_gpu_debug_layers", "1");
        applySetting("gpu_debug_app", DEBUG_APP);
        applySetting("gpu_debug_layers", LAYER_C_NAME);
        applySetting("gpu_debug_layers_gles", GLES_LAYER_C_LIB);

        // Specify multple external apps that host layers
        applySetting("gpu_debug_layer_app", LAYERS_APP + ":" + GLES_LAYERS_APP);

        // Kick off our DEBUG app
        String appStartTime = getTime();
        getDevice().executeAdbCommand("shell", "am", "start", "-n", DEBUG_APP + "/" + ACTIVITY);

        // Check that external layers were loaded from both apps
        String vulkanString = "nullCreateInstance called in " + LAYER_C;
        LogScanResult vulkanResult = scanLog(TAG + "," + LAYER_C, vulkanString, appStartTime);
        Assert.assertTrue(LAYER_C + " was not loaded", vulkanResult.found);

        String glesString = "glesLayer_eglChooseConfig called in " + GLES_LAYER_C;
        LogScanResult glesResult = scanLog(TAG + "," + GLES_LAYER_C, glesString, appStartTime);
        Assert.assertTrue(GLES_LAYER_C + " was not loaded", glesResult.found);
    }
}
