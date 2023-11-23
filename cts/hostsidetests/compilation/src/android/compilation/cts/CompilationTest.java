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

package android.compilation.cts;

import static com.google.common.truth.Truth.assertThat;

import android.compilation.cts.annotation.CtsTestCase;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.testtype.junit4.DeviceTestRunOptions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Pattern;

/**
 * Compilation tests that don't require root access.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
@CtsTestCase
public class CompilationTest extends BaseHostJUnit4Test {
    private static final String STATUS_CHECKER_PKG = "android.compilation.cts.statuscheckerapp";
    private static final String STATUS_CHECKER_CLASS =
            "android.compilation.cts.statuscheckerapp.StatusCheckerAppTest";
    private static final String TEST_APP_PKG = "android.compilation.cts";
    private static final String TEST_APP_APK_RES = "/CtsCompilationApp.apk";
    private static final String TEST_APP_DM_RES = "/CtsCompilationApp.dm";

    private Utils mUtils;

    @Before
    public void setUp() throws Exception {
        mUtils = new Utils(getTestInformation());
    }

    @After
    public void tearDown() throws Exception {
        getDevice().uninstallPackage(TEST_APP_PKG);
    }

    @Test
    public void testCompile() throws Exception {
        var options = new DeviceTestRunOptions(STATUS_CHECKER_PKG)
                              .setTestClassName(STATUS_CHECKER_CLASS)
                              .setTestMethodName("checkStatus")
                              .setDisableHiddenApiCheck(true);

        mUtils.assertCommandSucceeds("pm compile -m speed -f " + STATUS_CHECKER_PKG);
        options.addInstrumentationArg("compiler-filter", "speed")
                .addInstrumentationArg("compilation-reason", "cmdline")
                .addInstrumentationArg("is-verified", "true")
                .addInstrumentationArg("is-optimized", "true")
                .addInstrumentationArg("is-fully-compiled", "true");
        assertThat(runDeviceTests(options)).isTrue();

        mUtils.assertCommandSucceeds("pm compile -m verify -f " + STATUS_CHECKER_PKG);
        options.addInstrumentationArg("compiler-filter", "verify")
                .addInstrumentationArg("compilation-reason", "cmdline")
                .addInstrumentationArg("is-verified", "true")
                .addInstrumentationArg("is-optimized", "false")
                .addInstrumentationArg("is-fully-compiled", "false");
        assertThat(runDeviceTests(options)).isTrue();

        mUtils.assertCommandSucceeds("pm delete-dexopt " + STATUS_CHECKER_PKG);
        options.addInstrumentationArg("compiler-filter", "run-from-apk")
                .addInstrumentationArg("compilation-reason", "unknown")
                .addInstrumentationArg("is-verified", "false")
                .addInstrumentationArg("is-optimized", "false")
                .addInstrumentationArg("is-fully-compiled", "false");
        assertThat(runDeviceTests(options)).isTrue();
    }

    // TODO(b/258223472): Remove this test once ART Service is the only dexopt implementation.
    @Test
    public void testArtService() throws Exception {
        assertThat(getDevice().getProperty("dalvik.vm.useartservice")).isEqualTo("true");

        mUtils.installFromResources(getAbi(), TEST_APP_APK_RES, TEST_APP_DM_RES);

        // Clear all user data, including the profile.
        mUtils.assertCommandSucceeds("pm clear " + TEST_APP_PKG);

        // Overwrite the artifacts compiled with the profile.
        mUtils.assertCommandSucceeds("pm compile -m verify -f " + TEST_APP_PKG);

        String dump = mUtils.assertCommandSucceeds("pm art dump " + TEST_APP_PKG);
        assertThat(dump).contains("[status=verify]");
        assertThat(dump).doesNotContain("[status=speed-profile]");

        dump = mUtils.assertCommandSucceeds("dumpsys package " + TEST_APP_PKG);
        assertThat(dump).contains("[status=verify]");
        assertThat(dump).doesNotContain("[status=speed-profile]");

        // Compile the app. It should use the profile in the DM file.
        mUtils.assertCommandSucceeds("pm compile -m speed-profile " + TEST_APP_PKG);

        dump = mUtils.assertCommandSucceeds("pm art dump " + TEST_APP_PKG);
        assertThat(dump).doesNotContain("[status=verify]");
        assertThat(dump).contains("[status=speed-profile]");

        dump = mUtils.assertCommandSucceeds("dumpsys package " + TEST_APP_PKG);
        assertThat(dump).doesNotContain("[status=verify]");
        assertThat(dump).contains("[status=speed-profile]");
    }

    @Test
    public void testCompileSecondaryDex() throws Exception {
        var options = new DeviceTestRunOptions(STATUS_CHECKER_PKG)
                              .setTestClassName(STATUS_CHECKER_CLASS)
                              .setTestMethodName("createAndLoadSecondaryDex");
        assertThat(runDeviceTests(options)).isTrue();

        // Verify that the secondary dex file is recorded.
        String dump = mUtils.assertCommandSucceeds("dumpsys package " + STATUS_CHECKER_PKG);
        checkDexoptStatus(dump, "secondary\\.jar", ".*");

        mUtils.assertCommandSucceeds(
                "pm compile --secondary-dex -m speed -f " + STATUS_CHECKER_PKG);
        dump = mUtils.assertCommandSucceeds("dumpsys package " + STATUS_CHECKER_PKG);
        checkDexoptStatus(dump, "secondary\\.jar", "speed");

        mUtils.assertCommandSucceeds(
                "pm compile --secondary-dex -m verify -f " + STATUS_CHECKER_PKG);
        dump = mUtils.assertCommandSucceeds("dumpsys package " + STATUS_CHECKER_PKG);
        checkDexoptStatus(dump, "secondary\\.jar", "verify");

        mUtils.assertCommandSucceeds("pm delete-dexopt " + STATUS_CHECKER_PKG);
        dump = mUtils.assertCommandSucceeds("dumpsys package " + STATUS_CHECKER_PKG);
        checkDexoptStatus(dump, "secondary\\.jar", "run-from-apk");
    }

    private void checkDexoptStatus(String dump, String dexfilePattern, String statusPattern) {
        // Matches the dump output typically being:
        //     /data/user/0/android.compilation.cts.statuscheckerapp/secondary.jar
        //       x86_64: [status=speed] [reason=cmdline] [primary-abi]
        // The pattern is intentionally minimized to be as forward compatible as possible.
        // TODO(b/283447251): Use a machine-readable format.
        assertThat(dump).containsMatch(Pattern.compile(String.format(
                "\\b(%s)\\b[^\\n]*\\n[^\\n]*\\[status=(%s)\\]", dexfilePattern, statusPattern)));
    }
}
