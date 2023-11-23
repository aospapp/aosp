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

import android.graphics.Point
import android.graphics.PointF
import android.hardware.input.InputManager
import android.input.cts.VirtualDisplayActivityScenarioRule.Companion.HEIGHT
import android.input.cts.VirtualDisplayActivityScenarioRule.Companion.ORIENTATION_0
import android.input.cts.VirtualDisplayActivityScenarioRule.Companion.ORIENTATION_180
import android.input.cts.VirtualDisplayActivityScenarioRule.Companion.ORIENTATION_270
import android.input.cts.VirtualDisplayActivityScenarioRule.Companion.ORIENTATION_90
import android.input.cts.VirtualDisplayActivityScenarioRule.Companion.WIDTH
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@MediumTest
@RunWith(AndroidJUnit4::class)
class TouchScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var touchScreen: UinputTouchDevice
    private lateinit var verifier: EventVerifier

    @get:Rule
    val virtualDisplayRule = VirtualDisplayActivityScenarioRule()

    @Before
    fun setUp() {
        touchScreen = UinputTouchDevice(
            instrumentation,
            virtualDisplayRule.virtualDisplay.display,
            Size(WIDTH, HEIGHT)
        )
        verifier = EventVerifier(virtualDisplayRule.activity::getInputEvent)
    }

    @After
    fun tearDown() {
        if (this::touchScreen.isInitialized) {
            touchScreen.close()
        }
    }

    @Test
    fun testHostUsiVersionIsNull() {
        assertNull(
            instrumentation.targetContext.getSystemService(InputManager::class.java)
                .getHostUsiVersion(virtualDisplayRule.virtualDisplay.display))
    }

    @Test
    fun testSingleTouch() {
        val pointer = Point(100, 100)

        // ACTION_DOWN
        touchScreen.sendBtnTouch(true)
        touchScreen.sendDown(0 /*id*/, pointer)
        verifier.assertReceivedDown()

        // ACTION_MOVE
        pointer.offset(1, 1)
        touchScreen.sendMove(0 /*id*/, pointer)
        verifier.assertReceivedMove()

        // ACTION_UP
        touchScreen.sendBtnTouch(false)
        touchScreen.sendUp(0 /*id*/)
        verifier.assertReceivedUp()
    }

    @Test
    fun testMultiTouch() {
        val pointer1 = Point(100, 100)
        val pointer2 = Point(150, 150)

        // ACTION_DOWN
        touchScreen.sendBtnTouch(true)
        touchScreen.sendDown(0 /*id*/, pointer1)
        verifier.assertReceivedDown()

        // ACTION_POINTER_DOWN
        touchScreen.sendDown(1 /*id*/, pointer2)
        verifier.assertReceivedPointerDown(1)

        // ACTION_MOVE
        pointer2.offset(1, 1)
        touchScreen.sendMove(1 /*id*/, pointer2)
        verifier.assertReceivedMove()

        // ACTION_POINTER_UP
        touchScreen.sendUp(0 /*id*/)
        verifier.assertReceivedPointerUp(0)

        // ACTION_UP
        touchScreen.sendBtnTouch(false)
        touchScreen.sendUp(1 /*id*/)
        verifier.assertReceivedUp()
    }

    @Test
    fun testDeviceCancel() {
        val pointer = Point(100, 100)

        // ACTION_DOWN
        touchScreen.sendBtnTouch(true)
        touchScreen.sendDown(0 /*id*/, pointer)
        verifier.assertReceivedDown()

        // ACTION_MOVE
        pointer.offset(1, 1)
        touchScreen.sendMove(0 /*id*/, pointer)
        verifier.assertReceivedMove()

        // ACTION_CANCEL
        touchScreen.sendToolType(0 /*id*/, UinputTouchDevice.MT_TOOL_PALM)
        verifier.assertReceivedCancel()

        // No event
        touchScreen.sendBtnTouch(false)
        touchScreen.sendUp(0 /*id*/)
        virtualDisplayRule.activity.assertNoEvents()
    }

    /**
     * Check that pointer cancel is received by the activity via uinput device.
     */
    @Test
    fun testDevicePointerCancel() {
        val pointer1 = Point(100, 100)
        val pointer2 = Point(150, 150)

        // ACTION_DOWN
        touchScreen.sendBtnTouch(true)
        touchScreen.sendDown(0 /*id*/, pointer1)
        verifier.assertReceivedDown()

        // ACTION_MOVE
        pointer1.offset(1, 1)
        touchScreen.sendMove(0 /*id*/, pointer1)
        verifier.assertReceivedMove()

        // ACTION_POINTER_DOWN(1)
        touchScreen.sendDown(1 /*id*/, pointer2)
        verifier.assertReceivedPointerDown(1)

        // ACTION_POINTER_UP(1) with cancel flag
        touchScreen.sendToolType(1 /*id*/, UinputTouchDevice.MT_TOOL_PALM)
        verifier.assertReceivedPointerCancel(1)

        // ACTION_UP
        touchScreen.sendBtnTouch(false)
        touchScreen.sendUp(0 /*id*/)
        verifier.assertReceivedUp()
    }

    @Test
    fun testTouchScreenPrecisionOrientation0() {
        virtualDisplayRule.runInDisplayOrientation(ORIENTATION_0) {
            verifyTapsOnFourCorners(
                arrayOf(
                    PointF(0f, 0f),
                    PointF(WIDTH - 1f, 0f),
                    PointF(WIDTH - 1f, HEIGHT - 1f),
                    PointF(0f, HEIGHT - 1f),
                )
            )
        }
    }

    @Test
    fun testTouchScreenPrecisionOrientation90() {
        virtualDisplayRule.runInDisplayOrientation(ORIENTATION_90) {
            verifyTapsOnFourCorners(
                arrayOf(
                    PointF(0f, WIDTH - 1f),
                    PointF(0f, 0f),
                    PointF(HEIGHT - 1f, 0f),
                    PointF(HEIGHT - 1f, WIDTH - 1f),
                )
            )
        }
    }

    @Test
    fun testTouchScreenPrecisionOrientation180() {
        virtualDisplayRule.runInDisplayOrientation(ORIENTATION_180) {
            verifyTapsOnFourCorners(
                arrayOf(
                    PointF(WIDTH - 1f, HEIGHT - 1f),
                    PointF(0f, HEIGHT - 1f),
                    PointF(0f, 0f),
                    PointF(WIDTH - 1f, 0f),
                )
            )
        }
    }

    @Test
    fun testTouchScreenPrecisionOrientation270() {
        virtualDisplayRule.runInDisplayOrientation(ORIENTATION_270) {
            verifyTapsOnFourCorners(
                arrayOf(
                    PointF(HEIGHT - 1f, 0f),
                    PointF(HEIGHT - 1f, WIDTH - 1f),
                    PointF(0f, WIDTH - 1f),
                    PointF(0f, 0f),
                )
            )
        }
    }

    @Test
    fun testEventTime() {
        val pointer = Point(100, 100)

       // ACTION_DOWN
        touchScreen.sendBtnTouch(true)
        touchScreen.sendDown(0 /*id*/, pointer)
        verifyEventTime()

        // ACTION_MOVE
        pointer.offset(1, 1)
        touchScreen.sendMove(0 /*id*/, pointer)
        verifyEventTime()

        // ACTION_UP
        touchScreen.sendBtnTouch(false)
        touchScreen.sendUp(0 /*id*/)
        verifyEventTime()
    }

    private fun verifyEventTime() {
        val event = verifier.getMotionEvent()
        assertEquals(event.getEventTimeNanos() / 1_000_000, event.getEventTime())
    }

    // Verifies that each of the four corners of the touch screen (lt, rt, rb, lb) map to the
    // given four points by tapping on the corners in order and asserting the location of the
    // received events match the provided values.
    private fun verifyTapsOnFourCorners(expectedPoints: Array<PointF>) {
        for (i in 0 until 4) {
            touchScreen.sendBtnTouch(true)
            touchScreen.sendDown(0 /*id*/, CORNERS[i])
            verifier.assertReceivedDown(expectedPoints[i])

            touchScreen.sendBtnTouch(false)
            touchScreen.sendUp(0 /*id*/)
            verifier.assertReceivedUp()
        }
    }

    companion object {
        // The four corners of the touchscreen: lt, rt, rb, lb
        val CORNERS = arrayOf(
            Point(0, 0),
            Point(WIDTH - 1, 0),
            Point(WIDTH - 1, HEIGHT - 1),
            Point(0, HEIGHT - 1),
        )
    }
}
