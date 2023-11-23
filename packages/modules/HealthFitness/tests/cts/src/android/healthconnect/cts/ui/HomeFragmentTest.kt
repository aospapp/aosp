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
package android.healthconnect.cts.ui

import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.BasalMetabolicRateRecord
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.StepsRecord
import android.healthconnect.cts.TestUtils.verifyDeleteRecords
import android.healthconnect.cts.lib.ActivityLauncher.launchMainActivity
import android.healthconnect.cts.lib.TestUtils.insertRecordAs
import android.healthconnect.cts.lib.UiTestUtils.clickOnText
import android.healthconnect.cts.lib.UiTestUtils.waitDisplayed
import androidx.test.uiautomator.By
import com.android.cts.install.lib.TestApp
import java.time.Instant
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

/** CTS test for HealthConnect Home screen. */
class HomeFragmentTest : HealthConnectBaseTest() {

    companion object {

        private const val TAG = "HomeFragmentTest"

        private const val VERSION_CODE: Long = 1

        private val APP_A_WITH_READ_WRITE_PERMS: TestApp =
            TestApp(
                "TestAppA",
                "android.healthconnect.cts.testapp.readWritePerms.A",
                VERSION_CODE,
                false,
                "CtsHealthConnectTestAppA.apk")

        @JvmStatic
        @BeforeClass
        fun setup() {
            insertRecordAs(APP_A_WITH_READ_WRITE_PERMS)
        }

        @JvmStatic
        @AfterClass
        fun teardown() {
            verifyDeleteRecords(
                StepsRecord::class.java,
                TimeInstantRangeFilter.Builder()
                    .setStartTime(Instant.EPOCH)
                    .setEndTime(Instant.now())
                    .build())
            verifyDeleteRecords(
                HeartRateRecord::class.java,
                TimeInstantRangeFilter.Builder()
                    .setStartTime(Instant.EPOCH)
                    .setEndTime(Instant.now())
                    .build())
            verifyDeleteRecords(
                BasalMetabolicRateRecord::class.java,
                TimeInstantRangeFilter.Builder()
                    .setStartTime(Instant.EPOCH)
                    .setEndTime(Instant.now())
                    .build())
        }
    }

    @Test
    fun homeFragment_openAppPermissions() {
        context.launchMainActivity {
            clickOnText("App permissions")

            waitDisplayed(By.text("Allowed access"))
            // TODO(b/265789268): Fix flaky "DNot allowed access" not found.
            // waitDisplayed(By.text("Not allowed access"))
        }
    }

    @Test
    fun homeFragment_openDataManagement() {
        context.launchMainActivity {
            clickOnText("Data and access")

            waitDisplayed(By.text("Browse data"))
            waitDisplayed(By.text("Manage data"))
            waitDisplayed(By.text("Auto-delete"))

            waitDisplayed(By.text("Delete all data"))
        }
    }

    @Test
    fun homeFragment_recentAccessShownOnHomeScreen() {
        context.launchMainActivity {
            waitDisplayed(By.textContains("CtsHealthConnectTest"))
            waitDisplayed(By.text("See all recent access"))
        }
    }

    @Test
    fun homeFragment_navigateToRecentAccess() {
        context.launchMainActivity {
            clickOnText("See all recent access")

            waitDisplayed(By.text("Today"))
            waitDisplayed(By.textContains("CtsHealthConnectTest"))
        }
    }
}
