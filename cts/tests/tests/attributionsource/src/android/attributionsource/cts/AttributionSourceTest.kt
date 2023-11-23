/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package android.attributionsource.cts

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class AttributionSourceTest {
    @Test
    @Throws(Exception::class)
    public fun testRemoteProcessActivityPidCheck() {
        val context: Context = ApplicationProvider.getApplicationContext()

        val activityIntent = Intent(context, AttributionSourceActivity::class.java)
        activityIntent.putExtra(ATTRIBUTION_SOURCE_KEY, context.getAttributionSource())

        // Launch activity from adjacent thread (cannot be launched from main thread)
        val thread = LaunchActivityThread(activityIntent)

        thread.start()
        thread.join()

        assertEquals("Test activity did not return RESULT_SECURITY_EXCEPTION",
                AttributionSourceActivity.RESULT_SECURITY_EXCEPTION, thread.getResultCode())
    }

    companion object {
        private final val TAG: String = "AttributionSourceTest"

        public final val ATTRIBUTION_SOURCE_KEY: String = "attributionSource"

        private class LaunchActivityThread(activityIntent: Intent) : Thread() {
            private val mActivityIntent = activityIntent
            private var mResultCode: Int = Activity.RESULT_OK

            public override fun run() {
                val scenario: ActivityScenario<AttributionSourceActivity> =
                        ActivityScenario.launchActivityForResult(mActivityIntent)
                val result: ActivityResult = scenario.getResult()
                mResultCode = result.getResultCode()
            }

            public fun getResultCode(): Int {
                return mResultCode
            }
        }
    }
}
