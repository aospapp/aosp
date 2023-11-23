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

package android.permission3.cts

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import androidx.test.uiautomator.By
import com.android.modules.utils.build.SdkLevel
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class LocationAccuracyTest : BaseUsePermissionTest() {

    companion object {
        private const val LOCATION_ACCURACY_PRECISE_RADIO_BUTTON =
            "com.android.permissioncontroller:id/permission_location_accuracy_radio_fine"
        private const val LOCATION_ACCURACY_COARSE_RADIO_BUTTON =
            "com.android.permissioncontroller:id/permission_location_accuracy_radio_coarse"
        private const val LOCATION_ACCURACY_PRECISE_ONLY_VIEW =
            "com.android.permissioncontroller:id/permission_location_accuracy_fine_only"
        private const val LOCATION_ACCURACY_COARSE_ONLY_VIEW =
            "com.android.permissioncontroller:id/permission_location_accuracy_coarse_only"
    }

    @Before
    fun setup() {
        assumeTrue("Location Accuracy is only available on S+", SdkLevel.isAtLeastS())
        assumeFalse(isAutomotive)
        assumeFalse(isTv)
        assumeFalse(isWatch)
    }

    @Test
    fun testCoarsePermissionIsGranted() {
        installPackage(APP_APK_PATH_31)

        assertAppHasPermission(ACCESS_FINE_LOCATION, false)
        assertAppHasPermission(ACCESS_COARSE_LOCATION, false)
        assertAppHasPermission(ACCESS_BACKGROUND_LOCATION, false)

        requestAppPermissionsAndAssertResult(
                ACCESS_FINE_LOCATION to false,
                ACCESS_COARSE_LOCATION to true
        ) {
            clickCoarseLocationRadioButton()
            clickPreciseLocationRadioButton()
            clickCoarseLocationRadioButton()
            clickPermissionRequestAllowForegroundButton()
        }
    }

    @Test
    fun testPrecisePermissionIsGranted() {
        installPackage(APP_APK_PATH_31)

        assertAppHasPermission(ACCESS_FINE_LOCATION, false)
        assertAppHasPermission(ACCESS_COARSE_LOCATION, false)
        assertAppHasPermission(ACCESS_BACKGROUND_LOCATION, false)

        requestAppPermissionsAndAssertResult(
                ACCESS_FINE_LOCATION to true,
                ACCESS_COARSE_LOCATION to true
        ) {
            clickPreciseLocationRadioButton()
            clickCoarseLocationRadioButton()
            clickPreciseLocationRadioButton()
            clickPermissionRequestAllowForegroundButton()
        }
    }

    @Test
    fun testPermissionUpgradeFlow() {
        installPackage(APP_APK_PATH_31)

        assertAppHasPermission(ACCESS_FINE_LOCATION, false)
        assertAppHasPermission(ACCESS_COARSE_LOCATION, false)
        assertAppHasPermission(ACCESS_BACKGROUND_LOCATION, false)

        requestAppPermissionsAndAssertResult(
                ACCESS_FINE_LOCATION to false,
                ACCESS_COARSE_LOCATION to true
        ) {
            clickCoarseLocationRadioButton()
            clickPreciseLocationRadioButton()
            clickCoarseLocationRadioButton()
            clickPermissionRequestAllowForegroundButton()
        }

        // now request again to change to precise location
        requestAppPermissionsAndAssertResult(
                ACCESS_FINE_LOCATION to true,
                ACCESS_COARSE_LOCATION to true
        ) {
            clickPreciseLocationOnlyView()
            clickPermissionRequestAllowForegroundButton()
        }
    }

    @Test
    fun testCoarseRequestAndGrant() {
        installPackage(APP_APK_PATH_31)

        assertAppHasPermission(ACCESS_FINE_LOCATION, false)
        assertAppHasPermission(ACCESS_COARSE_LOCATION, false)
        assertAppHasPermission(ACCESS_BACKGROUND_LOCATION, false)

        requestAppPermissionsAndAssertResult(
                ACCESS_COARSE_LOCATION to true
        ) {
            clickCoarseLocationOnlyView()
            clickPermissionRequestAllowForegroundButton()
        }

        assertAppHasPermission(ACCESS_FINE_LOCATION, false)
        assertAppHasPermission(ACCESS_BACKGROUND_LOCATION, false)
    }

    private fun clickPreciseLocationRadioButton() {
        click(By.res(LOCATION_ACCURACY_PRECISE_RADIO_BUTTON))
    }

    private fun clickCoarseLocationRadioButton() {
        click(By.res(LOCATION_ACCURACY_COARSE_RADIO_BUTTON))
    }

    private fun clickPreciseLocationOnlyView() {
        click(By.res(LOCATION_ACCURACY_PRECISE_ONLY_VIEW))
    }

    private fun clickCoarseLocationOnlyView() {
        click(By.res(LOCATION_ACCURACY_COARSE_ONLY_VIEW))
    }
}
