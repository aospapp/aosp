/*
 * Copyright 2022 The Android Open Source Project
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

package android.media.router.cts;

import static android.media.cts.MediaRouterTestConstants.DEVICE_SIDE_TEST_CLASS;
import static android.media.cts.MediaRouterTestConstants.MEDIA_ROUTER_PROVIDER_1_APK;
import static android.media.cts.MediaRouterTestConstants.MEDIA_ROUTER_PROVIDER_1_PACKAGE;
import static android.media.cts.MediaRouterTestConstants.MEDIA_ROUTER_PROVIDER_2_APK;
import static android.media.cts.MediaRouterTestConstants.MEDIA_ROUTER_PROVIDER_2_PACKAGE;
import static android.media.cts.MediaRouterTestConstants.MEDIA_ROUTER_PROVIDER_3_APK;
import static android.media.cts.MediaRouterTestConstants.MEDIA_ROUTER_PROVIDER_3_PACKAGE;
import static android.media.cts.MediaRouterTestConstants.MEDIA_ROUTER_PROVIDER_SELF_SCAN_ONLY_APK;
import static android.media.cts.MediaRouterTestConstants.MEDIA_ROUTER_TEST_APK;
import static android.media.cts.MediaRouterTestConstants.MEDIA_ROUTER_TEST_PACKAGE;

import static com.google.common.truth.Truth.assertWithMessage;

import android.platform.test.annotations.AppModeFull;
import android.platform.test.annotations.RequiresDevice;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.compatibility.common.util.ApiTest;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.testtype.junit4.BeforeClassWithInfo;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileNotFoundException;

/** Installs route provider apps and runs tests in {@link MediaRouter2DeviceTest}. */
@RunWith(DeviceJUnit4ClassRunner.class)
public class MediaRouter2HostSideTest extends BaseHostJUnit4Test {

    @BeforeClassWithInfo
    public static void installApps(TestInformation testInfo)
            throws DeviceNotAvailableException, FileNotFoundException {
        installTestApp(testInfo, MEDIA_ROUTER_PROVIDER_1_APK);
        installTestApp(testInfo, MEDIA_ROUTER_PROVIDER_2_APK);
        installTestApp(testInfo, MEDIA_ROUTER_PROVIDER_3_APK);
        installTestApp(testInfo, MEDIA_ROUTER_PROVIDER_SELF_SCAN_ONLY_APK);
        installTestApp(testInfo, MEDIA_ROUTER_TEST_APK);
    }

    @BeforeClassWithInfo
    public static void uninstallApps(TestInformation testInfo) throws DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        device.uninstallPackage(MEDIA_ROUTER_PROVIDER_1_PACKAGE);
        device.uninstallPackage(MEDIA_ROUTER_PROVIDER_2_PACKAGE);
        device.uninstallPackage(MEDIA_ROUTER_PROVIDER_3_PACKAGE);
        device.uninstallPackage(MEDIA_ROUTER_PROVIDER_SELF_SCAN_ONLY_APK);
        device.uninstallPackage(MEDIA_ROUTER_TEST_PACKAGE);
    }

    @ApiTest(apis = {"android.media.RouteDiscoveryPreference, android.media.MediaRouter2"})
    @AppModeFull
    @RequiresDevice
    @Test
    public void testDeduplicationIds_propagateAcrossApps() throws Exception {
        runDeviceTests(
                MEDIA_ROUTER_TEST_PACKAGE,
                DEVICE_SIDE_TEST_CLASS,
                "deduplicationIds_propagateAcrossApps");
    }

    @ApiTest(apis = {"android.media.RouteDiscoveryPreference, android.media.MediaRouter2"})
    @AppModeFull
    @RequiresDevice
    @Test
    public void testDeviceType_propagatesAcrossApps() throws Exception {
        runDeviceTests(
                MEDIA_ROUTER_TEST_PACKAGE,
                DEVICE_SIDE_TEST_CLASS,
                "deviceType_propagatesAcrossApps");
    }

    @ApiTest(apis = {"android.media.RouteListingPreference, android.media.MediaRouter2"})
    @AppModeFull
    @RequiresDevice
    @Test
    public void testSetRouteListingPreference_propagatesToManager() throws Exception {
        runDeviceTests(
                MEDIA_ROUTER_TEST_PACKAGE,
                DEVICE_SIDE_TEST_CLASS,
                "setRouteListingPreference_propagatesToManager");
    }

    @AppModeFull
    @RequiresDevice
    @Test
    public void testSetInstance_findsExternalPackage() throws Exception {
        runDeviceTests(
                MEDIA_ROUTER_TEST_PACKAGE,
                DEVICE_SIDE_TEST_CLASS,
                "getInstance_findsExternalPackage");
    }

    @ApiTest(apis = {"android.media.RouteDiscoveryPreference, android.media.MediaRouter2"})
    @AppModeFull
    @RequiresDevice
    @Test
    public void testVisibilityAndAllowedPackages_propagateAcrossApps() throws Exception {
        runDeviceTests(
                MEDIA_ROUTER_TEST_PACKAGE,
                DEVICE_SIDE_TEST_CLASS,
                "visibilityAndAllowedPackages_propagateAcrossApps");
    }

    @ApiTest(apis = {"android.media.RouteDiscoveryPreference, android.media.MediaRouter2"})
    @AppModeFull
    @RequiresDevice
    @Test
    public void setRouteListingPreference_withCustomDisableReason_propagatesCorrectly()
            throws Exception {
        runDeviceTests(
                MEDIA_ROUTER_TEST_PACKAGE,
                DEVICE_SIDE_TEST_CLASS,
                "setRouteListingPreference_withCustomDisableReason_propagatesCorrectly");
    }

    @ApiTest(apis = {"android.media.RouteDiscoveryPreference, android.media.MediaRouter2"})
    @AppModeFull
    @RequiresDevice
    @Test
    public void newRouteListingPreference_withInvalidCustomSubtext_throws() throws Exception {
        runDeviceTests(
                MEDIA_ROUTER_TEST_PACKAGE,
                DEVICE_SIDE_TEST_CLASS,
                "newRouteListingPreference_withInvalidCustomSubtext_throws");
    }

    @ApiTest(apis = {"android.media.RouteDiscoveryPreference, android.media.MediaRouter2"})
    @AppModeFull
    @RequiresDevice
    @Test
    public void getRoutes_dependingOnPermissions_returnsExpectedSystemRoutes() throws Exception {
        // Bluetooth permissions must be manually granted.
        setPermissionEnabled(
                MEDIA_ROUTER_TEST_PACKAGE,
                "android.permission.BLUETOOTH_SCAN",
                /* enabled= */ true);
        setPermissionEnabled(
                MEDIA_ROUTER_TEST_PACKAGE,
                "android.permission.BLUETOOTH_CONNECT",
                /* enabled= */ true);
        runDeviceTests(
                MEDIA_ROUTER_TEST_PACKAGE, DEVICE_SIDE_TEST_CLASS, "getRoutes_returnDeviceRoute");
        setPermissionEnabled(
                MEDIA_ROUTER_TEST_PACKAGE,
                "android.permission.BLUETOOTH_SCAN",
                /* enabled= */ false);
        setPermissionEnabled(
                MEDIA_ROUTER_TEST_PACKAGE,
                "android.permission.BLUETOOTH_CONNECT",
                /* enabled= */ false);
        runDeviceTests(
                MEDIA_ROUTER_TEST_PACKAGE,
                DEVICE_SIDE_TEST_CLASS,
                "getRoutes_returnsDefaultDevice");
    }

    @ApiTest(apis = {"android.media.MediaRouter2"})
    @AppModeFull
    @RequiresDevice
    @Test
    public void selfScanOnlyProvider_notScannedByAnotherApp() throws Exception {
        runDeviceTests(
                MEDIA_ROUTER_TEST_PACKAGE,
                DEVICE_SIDE_TEST_CLASS,
                "selfScanOnlyProvider_notScannedByAnotherApp");
    }

    private void setPermissionEnabled(String packageName, String permission, boolean enabled)
            throws DeviceNotAvailableException {
        String action = enabled ? "grant" : "revoke";
        getDevice().executeShellCommand("pm %s %s %s".formatted(action, packageName, permission));
    }

    private static void installTestApp(TestInformation testInfo, String apkName)
            throws FileNotFoundException, DeviceNotAvailableException {
        LogUtil.CLog.d("Installing app " + apkName);
        CompatibilityBuildHelper buildHelper = new CompatibilityBuildHelper(
                testInfo.getBuildInfo());
        final String result = testInfo.getDevice().installPackage(
                buildHelper.getTestFile(apkName), /*reinstall=*/true, /*grantPermissions=*/true,
                /*allow test apps*/"-t");
        assertWithMessage("Failed to install " + apkName + ": " + result).that(result).isNull();
    }
}
