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

package android.input.cts

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.test.uiautomator.UiDevice
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.View
import android.view.ViewTreeObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.android.compatibility.common.util.PollingCheck
import com.android.compatibility.common.util.SystemUtil
import com.android.compatibility.common.util.WindowUtil
import com.google.common.truth.Truth.assertThat
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TOUCH_MODE_PROPAGATION_TIMEOUT_MILLIS: Long = 5000 // 5 sec

@MediumTest
@RunWith(AndroidJUnit4::class)
class TouchModeTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiDevice: UiDevice = UiDevice.getInstance(instrumentation)
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    @get:Rule
    val activityRule = ActivityScenarioRule<Activity>(Activity::class.java)
    private lateinit var activity: Activity
    private lateinit var targetContext: Context
    private lateinit var displayManager: DisplayManager
    private var secondScenario: ActivityScenario<Activity>? = null

    @Rule
    fun permissionsRule() = AdoptShellPermissionsRule(
            instrumentation.getUiAutomation(), ADD_TRUSTED_DISPLAY_PERMISSION
    )

    @Before
    fun setUp() {
        targetContext = instrumentation.targetContext
        displayManager = targetContext.getSystemService(DisplayManager::class.java)
        activityRule.scenario.onActivity {
            activity = it
        }
        WindowUtil.waitForFocus(activity)
        instrumentation.setInTouchMode(false)
    }

    @After
    fun tearDown() {
        val scenario = secondScenario
        if (scenario != null) {
            scenario.close()
        }
        val display = virtualDisplay
        if (display != null) {
            display.release()
        }
        val reader = imageReader
        if (reader != null) {
            reader.close()
        }
    }

    fun isInTouchMode(): Boolean {
        return activity.window.decorView.isInTouchMode
    }

    fun isRunningActivitiesOnSecondaryDisplaysSupported(): Boolean {
        return instrumentation.context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)
    }

    @Test
    fun testFocusedWindowOwnerCanChangeTouchMode() {
        instrumentation.setInTouchMode(true)
        PollingCheck.waitFor { isInTouchMode() }
        assertThat(isInTouchMode()).isTrue()
    }

    @Test
    fun testOnTouchModeChangeNotification() {
        val touchModeChangeListener = OnTouchModeChangeListenerImpl()
        var observer = activity.window.decorView.rootView.viewTreeObserver
        observer.addOnTouchModeChangeListener(touchModeChangeListener)
        val newTouchMode = !isInTouchMode()

        instrumentation.setInTouchMode(newTouchMode)
        try {
            assertThat(touchModeChangeListener.countDownLatch.await(
                    TOUCH_MODE_PROPAGATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }

        assertThat(touchModeChangeListener.isInTouchMode).isEqualTo(newTouchMode)
    }

    private class OnTouchModeChangeListenerImpl : ViewTreeObserver.OnTouchModeChangeListener {
        val countDownLatch = CountDownLatch(1)
        var isInTouchMode = false

        override fun onTouchModeChanged(mode: Boolean) {
            isInTouchMode = mode
            countDownLatch.countDown()
        }
    }

    @Test
    fun testNonFocusedWindowOwnerCannotChangeTouchMode() {
        // It takes 400-500 milliseconds in average for DecorView to receive the touch mode changed
        // event on 2021 hardware, so we set the timeout to 10x that. It's still possible that a
        // test would fail, but we don't have a better way to check that an event does not occur.
        // Due to the 2 expected touch mode events to occur, this test may take few seconds to run.
        uiDevice.pressHome()
        PollingCheck.waitFor { !activity.hasWindowFocus() }

        instrumentation.setInTouchMode(true)

        SystemClock.sleep(TOUCH_MODE_PROPAGATION_TIMEOUT_MILLIS)
        assertThat(isInTouchMode()).isFalse()
    }

    @Test
    fun testDetachedViewReturnsDefaultTouchMode() {
        val context = instrumentation.targetContext
        val defaultInTouchMode = context.resources.getBoolean(context.resources
                .getIdentifier("config_defaultInTouchMode", "bool", "android"))

        val detachedView = View(activity)

        // Detached view (view with mAttachInfo null) will just return the default touch mode value
        assertThat(detachedView.isInTouchMode()).isEqualTo(defaultInTouchMode)
    }

    /**
     * When per-display focus is disabled ({@code config_perDisplayFocusEnabled} is set to false),
     * touch mode changes affect all displays.
     *
     * In this test, we tap the main display, and ensure that touch mode becomes
     * true on both the main display and the secondary display
     */
    @Test
    fun testTouchModeUpdate_PerDisplayFocusDisabled() {
        assumeTrue(isRunningActivitiesOnSecondaryDisplaysSupported())
        assumeFalse("This test requires config_perDisplayFocusEnabled to be false",
                targetContext.resources.getBoolean(targetContext.resources.getIdentifier(
                        "config_perDisplayFocusEnabled", "bool", "android")))

        var secondaryDisplayId = findOrCreateSecondaryDisplay()

        injectMotionEventOnMainDisplay()
        assertThat(isInTouchMode()).isTrue()
        assertSecondaryDisplayTouchModeState(secondaryDisplayId, /* inTouch= */ true)
    }

    /**
     * When per-display focus is enabled ({@code config_perDisplayFocusEnabled} is set to true),
     * touch mode changes does not affect all displays.
     *
     * In this test, we tap the main display, and ensure that touch mode becomes
     * true on main display only. Touch mode on secondary display must remain false.
     */
    @Test
    fun testTouchModeUpdate_PerDisplayFocusEnabled() {
        assumeTrue(isRunningActivitiesOnSecondaryDisplaysSupported())
        assumeTrue("This test requires config_perDisplayFocusEnabled to be true",
                targetContext.resources.getBoolean(targetContext.resources.getIdentifier(
                        "config_perDisplayFocusEnabled", "bool", "android")))

        var secondaryDisplayId = findOrCreateSecondaryDisplay()

        injectMotionEventOnMainDisplay()
        assertThat(isInTouchMode()).isTrue()
        assertSecondaryDisplayTouchModeState(secondaryDisplayId, /* isInTouch= */ false,
                /* delayBeforeChecking= */ true)
    }

    /**
     * Regardless of the {@code config_perDisplayFocusEnabled} value,
     * touch mode changes does not affect displays with own focus.
     *
     * In this test, we tap the main display, and ensure that touch mode becomes
     * true n main display only. Touch mode on secondary display must remain false because it
     * maintains its own focus and touch mode.
     */
    @Test
    fun testTouchModeUpdate_DisplayHasOwnFocus() {
        assumeTrue(isRunningActivitiesOnSecondaryDisplaysSupported())
        var secondaryDisplayId = createVirtualDisplay(
                VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or VIRTUAL_DISPLAY_FLAG_TRUSTED)
        injectMotionEventOnMainDisplay()

        assertThat(isInTouchMode()).isTrue()
        assertSecondaryDisplayTouchModeState(secondaryDisplayId, /* isInTouch= */ false,
                /* delayBeforeChecking= */ true)
    }

    private fun findOrCreateSecondaryDisplay(): Int {
        // Pick a random secondary external display if there is any.
        // A virtual display is only created if the device only has a single (default) display.
        var display = Arrays.stream(displayManager.displays).filter { d ->
            d.displayId != Display.DEFAULT_DISPLAY && d.type == Display.TYPE_EXTERNAL
        }.findFirst()
        if (display.isEmpty) {
            return createVirtualDisplay(/*flags=*/ 0)
        }
        return display.get().displayId
    }

    private fun assertSecondaryDisplayTouchModeState(
            displayId: Int,
            isInTouch: Boolean,
            delayBeforeChecking: Boolean = false
    ) {
        if (delayBeforeChecking) {
            SystemClock.sleep(TOUCH_MODE_PROPAGATION_TIMEOUT_MILLIS)
        }
        PollingCheck.waitFor(TOUCH_MODE_PROPAGATION_TIMEOUT_MILLIS) {
            isSecondaryDisplayInTouchMode(displayId) == isInTouch
        }
        assertThat(isSecondaryDisplayInTouchMode(displayId)).isEqualTo(isInTouch)
    }

    private fun isSecondaryDisplayInTouchMode(displayId: Int): Boolean {
        if (secondScenario == null) {
            launchSecondScenarioActivity(displayId)
        }
        val scenario = secondScenario
        var inTouch: Boolean? = null
        if (scenario != null) {
            scenario.onActivity {
                inTouch = it.window.decorView.isInTouchMode
            }
        } else {
            fail("Fail to launch secondScenario")
        }
        return inTouch == true
    }

    private fun launchSecondScenarioActivity(displayId: Int) {
        // Launch activity on the picked display
        val bundle = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        SystemUtil.runWithShellPermissionIdentity({
            secondScenario = ActivityScenario.launch(Activity::class.java, bundle)
        }, Manifest.permission.INTERNAL_SYSTEM_WINDOW)
    }

    private fun injectMotionEventOnMainDisplay() {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = downTime
        val event = MotionEvent.obtain(downTime, eventTime, ACTION_DOWN,
                /* x= */ 100f, /* y= */ 100f, /* metaState= */ 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.uiAutomation.injectInputEvent(event, /* sync= */ true)
    }

    private fun createVirtualDisplay(flags: Int): Int {
        val displayCreated = CountDownLatch(1)
        displayManager.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                displayCreated.countDown()
                displayManager.unregisterDisplayListener(this)
            }
        }, Handler(Looper.getMainLooper()))
        imageReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
        val reader = imageReader
        virtualDisplay = displayManager.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME, WIDTH, HEIGHT, DENSITY, reader!!.surface, flags)

        assertThat(displayCreated.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(virtualDisplay).isNotNull()
        instrumentation.setInTouchMode(false)
        return virtualDisplay!!.display.displayId
    }

    companion object {
        const val VIRTUAL_DISPLAY_NAME = "CtsVirtualDisplay"
        const val WIDTH = 480
        const val HEIGHT = 800
        const val DENSITY = 160

        /** See [DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS].  */
        const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14

        /** See [DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED].  */
        const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
    }
}

private val ADD_TRUSTED_DISPLAY_PERMISSION: String = android.Manifest.permission.ADD_TRUSTED_DISPLAY
