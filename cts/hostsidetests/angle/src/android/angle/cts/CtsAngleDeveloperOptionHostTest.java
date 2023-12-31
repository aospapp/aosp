/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.angle.cts;

import com.android.tradefed.util.RunUtil;
import static android.angle.cts.CtsAngleCommon.*;

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests ANGLE Developer Option Opt-In/Out functionality.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class CtsAngleDeveloperOptionHostTest extends BaseHostJUnit4Test {

    private static final String TAG = CtsAngleDeveloperOptionHostTest.class.getSimpleName();

    private void setAndValidateAngleDevOptionPkgDriver(String pkgName, String driverValue)
            throws Exception {
        CLog.logAndDisplay(LogLevel.INFO, "Updating Global.Settings: pkgName = '" +
                pkgName + "', driverValue = '" + driverValue + "'");

        setGlobalSetting(getDevice(), SETTINGS_GLOBAL_DRIVER_PKGS, pkgName);
        setGlobalSetting(getDevice(), SETTINGS_GLOBAL_DRIVER_VALUES, driverValue);

        String devOption = getGlobalSetting(getDevice(), SETTINGS_GLOBAL_DRIVER_PKGS);
        Assert.assertEquals(
                "Developer option '" + SETTINGS_GLOBAL_DRIVER_PKGS +
                        "' was not set correctly: '" + devOption + "'",
                pkgName, devOption);

        devOption = getGlobalSetting(getDevice(), SETTINGS_GLOBAL_DRIVER_VALUES);
        Assert.assertEquals(
                "Developer option '" + SETTINGS_GLOBAL_DRIVER_VALUES +
                        "' was not set correctly: '" + devOption + "'",
                driverValue, devOption);
    }

    private void setAndValidatePkgDriver(String pkgName, OpenGlDriverChoice driver)
            throws Exception {
        stopPackage(getDevice(), pkgName);

        setAndValidateAngleDevOptionPkgDriver(pkgName, sDriverGlobalSettingMap.get(driver));

        CLog.logAndDisplay(LogLevel.INFO, "Validating driver selection (" +
                driver + ") with method '" + sDriverTestMethodMap.get(driver) + "'");

        runDeviceTests(pkgName, pkgName + "." + ANGLE_DRIVER_TEST_CLASS,
                sDriverTestMethodMap.get(driver));
    }

    private void installApp(String appName) throws Exception {
        for (int i = 0; i < NUM_ATTEMPTS; i++) {
            try {
                installPackage(appName);
                return;
            } catch (Exception e) {
                RunUtil.getDefault().sleep(REATTEMPT_SLEEP_MSEC);
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        clearSettings(getDevice());

        stopPackage(getDevice(), ANGLE_DRIVER_TEST_PKG);
        stopPackage(getDevice(), ANGLE_DRIVER_TEST_SEC_PKG);
        stopPackage(getDevice(), ANGLE_GAME_DRIVER_TEST_PKG);
    }

    @After
    public void tearDown() throws Exception {
        clearSettings(getDevice());
    }

    /**
     * Test ANGLE is loaded when the 'Use ANGLE for all' Developer Option is enabled.
     */
    @Test
    public void testEnableAngleForAll() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));

        installApp(ANGLE_DRIVER_TEST_APP);
        installApp(ANGLE_DRIVER_TEST_SEC_APP);

        setAndValidateAngleDevOptionPkgDriver(ANGLE_DRIVER_TEST_PKG,
                sDriverGlobalSettingMap.get(OpenGlDriverChoice.DEFAULT));
        setAndValidateAngleDevOptionPkgDriver(ANGLE_DRIVER_TEST_SEC_PKG,
                sDriverGlobalSettingMap.get(OpenGlDriverChoice.DEFAULT));

        setGlobalSetting(getDevice(), SETTINGS_GLOBAL_ALL_USE_ANGLE, "1");

        runDeviceTests(ANGLE_DRIVER_TEST_PKG,
                ANGLE_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                ANGLE_DRIVER_TEST_ANGLE_METHOD);
        runDeviceTests(ANGLE_DRIVER_TEST_SEC_PKG,
                ANGLE_DRIVER_TEST_SEC_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                ANGLE_DRIVER_TEST_ANGLE_METHOD);
    }

    /**
     * Test that the default/system driver is loaded when the Developer Option is set to 'default'.
     */
    @Test
    public void testUseDefaultDriver() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));
        final String testMethod = getTestMethod(getDevice());

        installApp(ANGLE_DRIVER_TEST_APP);

        setAndValidateAngleDevOptionPkgDriver(ANGLE_DRIVER_TEST_PKG,
                sDriverGlobalSettingMap.get(OpenGlDriverChoice.DEFAULT));

        runDeviceTests(ANGLE_DRIVER_TEST_PKG, ANGLE_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                testMethod);
    }

    /**
     * Test ANGLE is loaded when the Developer Option is set to 'angle'.
     */
    @Test
    public void testUseAngleDriver() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));

        installApp(ANGLE_DRIVER_TEST_APP);

        setAndValidateAngleDevOptionPkgDriver(ANGLE_DRIVER_TEST_PKG,
                sDriverGlobalSettingMap.get(OpenGlDriverChoice.ANGLE));

        runDeviceTests(ANGLE_DRIVER_TEST_PKG,
                ANGLE_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                ANGLE_DRIVER_TEST_ANGLE_METHOD);
    }

    /**
     * Test ANGLE is not loaded when the Developer Option is set to 'native'.
     */
    @Test
    public void testUseNativeDriver() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));
        Assume.assumeFalse(isAngleOnlySystem(getDevice()));

        installApp(ANGLE_DRIVER_TEST_APP);

        setAndValidateAngleDevOptionPkgDriver(ANGLE_DRIVER_TEST_PKG,
                sDriverGlobalSettingMap.get(OpenGlDriverChoice.NATIVE));

        runDeviceTests(ANGLE_DRIVER_TEST_PKG,
                ANGLE_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                ANGLE_DRIVER_TEST_NATIVE_METHOD);
    }

    /**
     * Test that the default/system driver is loaded when the Developer Option list lengths
     * mismatch.
     */
    @Test
    public void testSettingsLengthMismatch() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));
        final String testMethod = getTestMethod(getDevice());

        installApp(ANGLE_DRIVER_TEST_APP);
        installApp(ANGLE_DRIVER_TEST_SEC_APP);

        setAndValidateAngleDevOptionPkgDriver(ANGLE_DRIVER_TEST_PKG + "," +
                        ANGLE_DRIVER_TEST_SEC_PKG,
                sDriverGlobalSettingMap.get(OpenGlDriverChoice.ANGLE));

        runDeviceTests(ANGLE_DRIVER_TEST_PKG, ANGLE_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                testMethod);

        runDeviceTests(ANGLE_DRIVER_TEST_SEC_PKG,
                ANGLE_DRIVER_TEST_SEC_PKG + "." + ANGLE_DRIVER_TEST_CLASS, testMethod);
    }

    /**
     * Test that the default/system driver is loaded when the Developer Option is invalid.
     */
    @Test
    public void testUseInvalidDriver() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));
        final String testMethod = getTestMethod(getDevice());

        installApp(ANGLE_DRIVER_TEST_APP);

        setAndValidateAngleDevOptionPkgDriver(ANGLE_DRIVER_TEST_PKG, "timtim");

        runDeviceTests(ANGLE_DRIVER_TEST_PKG, ANGLE_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                testMethod);
    }

    /**
     * Test the Developer Options can be updated to/from each combination.
     */
    @Test
    public void testUpdateDriverValues() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));
        Assume.assumeFalse(isAngleOnlySystem(getDevice()));

        installApp(ANGLE_DRIVER_TEST_APP);

        for (OpenGlDriverChoice firstDriver : OpenGlDriverChoice.values()) {
            if (skipOverDefault(firstDriver)) {
                continue;
            }
            for (OpenGlDriverChoice secondDriver : OpenGlDriverChoice.values()) {
                if (skipOverDefault(secondDriver)) {
                    continue;
                }
                CLog.logAndDisplay(LogLevel.INFO, "Testing updating Global.Settings from '" +
                        firstDriver + "' to '" + secondDriver + "'");

                setAndValidatePkgDriver(ANGLE_DRIVER_TEST_PKG, firstDriver);
                setAndValidatePkgDriver(ANGLE_DRIVER_TEST_PKG, secondDriver);
            }
        }
    }

    /**
     * Test different PKGs can have different developer option values.
     * Primary: ANGLE
     * Secondary: Native
     */
    @Test
    public void testMultipleDevOptionsAngleNative() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));
        Assume.assumeFalse(isAngleOnlySystem(getDevice()));

        installApp(ANGLE_DRIVER_TEST_APP);
        installApp(ANGLE_DRIVER_TEST_SEC_APP);

        setAndValidateAngleDevOptionPkgDriver(ANGLE_DRIVER_TEST_PKG + "," +
                        ANGLE_DRIVER_TEST_SEC_PKG,
                sDriverGlobalSettingMap.get(OpenGlDriverChoice.ANGLE) + "," +
                        sDriverGlobalSettingMap.get(OpenGlDriverChoice.NATIVE));

        runDeviceTests(ANGLE_DRIVER_TEST_PKG,
                ANGLE_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                ANGLE_DRIVER_TEST_ANGLE_METHOD);

        runDeviceTests(ANGLE_DRIVER_TEST_SEC_PKG,
                ANGLE_DRIVER_TEST_SEC_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                ANGLE_DRIVER_TEST_NATIVE_METHOD);
    }

    /**
     * Test the Developer Options for a second PKG can be updated to/from each combination.
     */
    @Test
    public void testMultipleUpdateDriverValues() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));
        Assume.assumeFalse(isAngleOnlySystem(getDevice()));

        installApp(ANGLE_DRIVER_TEST_APP);
        installApp(ANGLE_DRIVER_TEST_SEC_APP);

        // Set the first PKG to always use ANGLE
        setAndValidatePkgDriver(ANGLE_DRIVER_TEST_PKG, OpenGlDriverChoice.ANGLE);

        for (OpenGlDriverChoice firstDriver : OpenGlDriverChoice.values()) {
            if (skipOverDefault(firstDriver)) {
                continue;
            }
            for (OpenGlDriverChoice secondDriver : OpenGlDriverChoice.values()) {
                if (skipOverDefault(secondDriver)) {
                    continue;
                }
                CLog.logAndDisplay(LogLevel.INFO, "Testing updating Global.Settings from '" +
                        firstDriver + "' to '" + secondDriver + "'");

                setAndValidateAngleDevOptionPkgDriver(
                        ANGLE_DRIVER_TEST_PKG + "," + ANGLE_DRIVER_TEST_SEC_PKG,
                        sDriverGlobalSettingMap.get(OpenGlDriverChoice.ANGLE) + "," +
                                sDriverGlobalSettingMap.get(firstDriver));

                CLog.logAndDisplay(LogLevel.INFO, "Validating driver selection (" +
                        firstDriver + ") with method '" + sDriverTestMethodMap.get(firstDriver)
                        + "'");

                runDeviceTests(ANGLE_DRIVER_TEST_SEC_PKG,
                        ANGLE_DRIVER_TEST_SEC_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                        sDriverTestMethodMap.get(firstDriver));

                setAndValidateAngleDevOptionPkgDriver(
                        ANGLE_DRIVER_TEST_PKG + "," + ANGLE_DRIVER_TEST_SEC_PKG,
                        sDriverGlobalSettingMap.get(OpenGlDriverChoice.ANGLE) + "," +
                                sDriverGlobalSettingMap.get(secondDriver));

                CLog.logAndDisplay(LogLevel.INFO, "Validating driver selection (" +
                        secondDriver + ") with method '" + sDriverTestMethodMap.get(secondDriver)
                        + "'");

                runDeviceTests(ANGLE_DRIVER_TEST_SEC_PKG,
                        ANGLE_DRIVER_TEST_SEC_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                        sDriverTestMethodMap.get(secondDriver));

                String devOptionPkg = getGlobalSetting(getDevice(), SETTINGS_GLOBAL_DRIVER_PKGS);
                String devOptionValue = getGlobalSetting(getDevice(),
                        SETTINGS_GLOBAL_DRIVER_VALUES);
                CLog.logAndDisplay(LogLevel.INFO, "Validating: PKG name = '" +
                        devOptionPkg + "', driver value = '" + devOptionValue + "'");

                runDeviceTests(ANGLE_DRIVER_TEST_PKG,
                        ANGLE_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                        ANGLE_DRIVER_TEST_ANGLE_METHOD);
            }
        }
    }

    /**
     * Test that the "ANGLE In Use" dialog box can be enabled when ANGLE is used.
     *
     * We can't actually make sure the dialog box shows up, just that enabling it and attempting to
     * show it doesn't cause a crash or prevent ANGLE from being enabled.
     */
    @Test
    public void testAngleInUseDialogBoxWithAngle() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));

        setGlobalSetting(getDevice(), SETTINGS_GLOBAL_ANGLE_IN_USE_DIALOG_BOX, "1");

        testUseAngleDriver();
    }

    /**
     * Test that the "ANGLE In Use" dialog box can be enabled when Native is used.
     *
     * We can't actually make sure the dialog box shows up, just that enabling it and attempting to
     * show it doesn't cause a crash.
     */
    @Test
    public void testAngleInUseDialogBoxWithNative() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));
        Assume.assumeFalse(isAngleOnlySystem(getDevice()));

        setGlobalSetting(getDevice(), SETTINGS_GLOBAL_ANGLE_IN_USE_DIALOG_BOX, "1");

        testUseNativeDriver();
    }

    /**
     * Test ANGLE is loaded when the Battery Game Mode includes 'useAngle=true'.
     */
    @Test
    public void testGameModeBatteryUseAngleDriver() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));

        installApp(ANGLE_GAME_DRIVER_TEST_APP);

        setGameModeBatteryConfig(getDevice(), ANGLE_GAME_DRIVER_TEST_PKG, true);
        setGameModeBattery(getDevice(), ANGLE_GAME_DRIVER_TEST_PKG);

        runDeviceTests(ANGLE_GAME_DRIVER_TEST_PKG,
                ANGLE_GAME_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                ANGLE_DRIVER_TEST_ANGLE_METHOD);
    }

    /**
     * Test ANGLE is loaded when the Standard Game Mode includes 'useAngle=true'.
     */
    @Test
    public void testGameModeStandardUseAngleDriver() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));

        installApp(ANGLE_GAME_DRIVER_TEST_APP);

        setGameModeStandardConfig(getDevice(), ANGLE_GAME_DRIVER_TEST_PKG, true);
        setGameModeStandard(getDevice(), ANGLE_GAME_DRIVER_TEST_PKG);

        runDeviceTests(ANGLE_GAME_DRIVER_TEST_PKG,
                ANGLE_GAME_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                ANGLE_DRIVER_TEST_ANGLE_METHOD);
    }

    /**
     * Test setting the Game Mode to use ANGLE ('useAngle=true') and then overriding that to use the
     * native driver with the Global.Settings loads the native driver.
     */
    @Test
    public void testGameModeBatteryUseAngleOverrideWithNative() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));
        Assume.assumeFalse(isAngleOnlySystem(getDevice()));

        installApp(ANGLE_GAME_DRIVER_TEST_APP);

        // Set Game Mode to use ANGLE and verify ANGLE is loaded.
        setGameModeBatteryConfig(getDevice(), ANGLE_GAME_DRIVER_TEST_PKG, true);
        setGameModeBattery(getDevice(), ANGLE_GAME_DRIVER_TEST_PKG);

        runDeviceTests(ANGLE_GAME_DRIVER_TEST_PKG,
                ANGLE_GAME_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                ANGLE_DRIVER_TEST_ANGLE_METHOD);

        // Set Global.Settings to use the native driver and verify the native driver is loaded.
        setAndValidateAngleDevOptionPkgDriver(ANGLE_GAME_DRIVER_TEST_PKG,
                sDriverGlobalSettingMap.get(OpenGlDriverChoice.NATIVE));

        runDeviceTests(ANGLE_GAME_DRIVER_TEST_PKG,
                ANGLE_GAME_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                ANGLE_DRIVER_TEST_NATIVE_METHOD);
    }

    /**
     * Test setting the Game Mode to not use ANGLE ('useAngle=false') and then overriding that to
     * use ANGLE with the Global.Settings loads ANGLE.
     */
    @Test
    public void testGameModeBatteryDontUseAngleOverrideWithAngle() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));
        final String testMethod = getTestMethod(getDevice());

        installApp(ANGLE_GAME_DRIVER_TEST_APP);

        // Set Game Mode to *not* use ANGLE and verify the native driver is loaded when ANGLE is not
        // the system driver.
        setGameModeBatteryConfig(getDevice(), ANGLE_GAME_DRIVER_TEST_PKG, false);
        setGameModeBattery(getDevice(), ANGLE_GAME_DRIVER_TEST_PKG);

        runDeviceTests(ANGLE_GAME_DRIVER_TEST_PKG,
                ANGLE_GAME_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS, testMethod);

        // Set Global.Settings to use ANGLE and verify ANGLE is loaded.
        setAndValidateAngleDevOptionPkgDriver(ANGLE_GAME_DRIVER_TEST_PKG,
                sDriverGlobalSettingMap.get(OpenGlDriverChoice.ANGLE));

        runDeviceTests(ANGLE_GAME_DRIVER_TEST_PKG,
                ANGLE_GAME_DRIVER_TEST_PKG + "." + ANGLE_DRIVER_TEST_CLASS,
                ANGLE_DRIVER_TEST_ANGLE_METHOD);
    }

    /**
     * Test that the `dumpsys gpu` correctly indicates `angleInUse = 1` when ANGLE is enabled.
     */
    @Test
    public void testDumpsysAngleInWhenAngleEnabled() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));
        // NOTE: This test will sometimes fail to start the `dumpsys gpu` activity, which results in
        // flaky failures.  To avoid that, only let this test run when ANGLE is NOT the system
        // driver.
        Assume.assumeFalse(isNativeDriverAngle(getDevice()));

        installApp(ANGLE_DUMPSYS_GPU_TEST_APP);

        setAndValidateAngleDevOptionPkgDriver(ANGLE_DUMPSYS_GPU_TEST_PKG,
                sDriverGlobalSettingMap.get(OpenGlDriverChoice.ANGLE));

        startActivity(getDevice(), ANGLE_DUMPSYS_GPU_TEST_PKG, ANGLE_DUMPSYS_GPU_TEST_CLASS);

        int angleInUse = getDumpsysGpuAngleInUse(getDevice(), ANGLE_DUMPSYS_GPU_TEST_PKG);
        Assert.assertEquals(
                "'dumpsys gpu' for package '" + ANGLE_DUMPSYS_GPU_TEST_PKG
                        + "' failed to indicate angle is in use: angleInUse = " + angleInUse,
                1, angleInUse);
    }

    /**
     * Test that the `dumpsys gpu` correctly indicates `angleInUse = 0` when ANGLE is disabled.
     */
    @Test
    public void testDumpsysAngleInWhenAngleDisabled() throws Exception {
        Assume.assumeTrue(isAngleInstalled(getDevice()));
        // NOTE: This test will sometimes fail to start the `dumpsys gpu` activity, which results in
        // flaky failures.  To avoid that, only let this test run when ANGLE is NOT the system
        // driver.
        Assume.assumeFalse(isNativeDriverAngle(getDevice()));

        installApp(ANGLE_DUMPSYS_GPU_TEST_APP);

        setAndValidateAngleDevOptionPkgDriver(ANGLE_DUMPSYS_GPU_TEST_PKG,
                sDriverGlobalSettingMap.get(OpenGlDriverChoice.NATIVE));

        startActivity(getDevice(), ANGLE_DUMPSYS_GPU_TEST_PKG, ANGLE_DUMPSYS_GPU_TEST_CLASS);

        int angleInUse = getDumpsysGpuAngleInUse(getDevice(), ANGLE_DUMPSYS_GPU_TEST_PKG);
        Assert.assertEquals(
                "'dumpsys gpu' for package '" + ANGLE_DUMPSYS_GPU_TEST_PKG
                        + "' failed to indicate angle is not in use: angleInUse = " + angleInUse,
                0, angleInUse);
    }
}
