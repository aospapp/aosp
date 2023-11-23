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
package android.platform.test.rule

import android.graphics.Rect
import android.os.RemoteException
import android.platform.test.rule.Orientation.LANDSCAPE
import android.platform.test.rule.Orientation.PORTRAIT
import android.platform.test.util.HealthTestingUtils
import androidx.test.uiautomator.By
import com.android.launcher3.tapl.LauncherInstrumentation
import org.junit.runner.Description

/** Locks the orientation in Landscape before starting the test, and goes back to natural after. */
class LandscapeOrientationRule : BaseOrientationRule(LANDSCAPE)

/** Locks the orientation in Portrait before starting the test, and goes back to natural after. */
class PortraitOrientationRule : BaseOrientationRule(PORTRAIT)

private enum class Orientation {
    LANDSCAPE,
    PORTRAIT,
}

private val Rect.orientation: Orientation
    get() = if (width() > height()) { LANDSCAPE } else { PORTRAIT }

/** Uses launcher rect to decide which rotation to apply to match [expectedOrientation]. */
sealed class BaseOrientationRule private constructor(private val expectedOrientation: Orientation) :
    TestWatcher() {

    private val mLauncher = LauncherInstrumentation()

    override fun starting(description: Description) {
        try {
            uiDevice.pressHome()
            mLauncher.setEnableRotation(true)
            if (launcherVisibleBounds?.orientation == expectedOrientation) {
                // In the case of tablets, the default orientation might be landscape already.
                return
            }
            uiDevice.setOrientationLeft()
            HealthTestingUtils.waitForNullDiag {
                when (launcherVisibleBounds?.orientation) {
                    expectedOrientation -> null // No error == success.
                    null -> "Launcher is not found"
                    else -> "Visible orientation is not ${expectedOrientation.name}"
                }
            }
        } catch (e: RemoteException) {
            throw RuntimeException(
                "RemoteException when forcing ${expectedOrientation.name} rotation on the device",
                e)
        }
    }

    override fun finished(description: Description) {
        try {
            uiDevice.setOrientationNatural()
            mLauncher.setEnableRotation(false)
            uiDevice.unfreezeRotation()
        } catch (e: RemoteException) {
            val message = "RemoteException when restoring natural rotation of the device"
            throw RuntimeException(message, e)
        }
    }

    private val launcherVisibleBounds: Rect?
        get() {
            val launcher =
                uiDevice.findObject(By.res("android", "content").pkg(uiDevice.launcherPackageName))
            return launcher?.visibleBounds
        }
}
