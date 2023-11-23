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

import android.app.Instrumentation
import android.content.Context
import android.graphics.Point
import android.hardware.input.InputManager
import android.util.Size
import android.view.Display
import android.view.InputDevice
import com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity
import com.android.cts.input.UinputDevice
import org.json.JSONArray
import org.json.JSONObject

class UinputTouchDevice(
    instrumentation: Instrumentation,
    display: Display,
    size: Size,
    private val rawResource: Int = R.raw.test_touchscreen_register,
    private val source: Int = InputDevice.SOURCE_TOUCHSCREEN,
) :
    AutoCloseable {

    private val uinputDevice: UinputDevice
    private lateinit var port: String
    private val inputManager: InputManager

    init {
        uinputDevice = createDevice(instrumentation, size)
        inputManager = instrumentation.targetContext.getSystemService(InputManager::class.java)!!
        associateWith(display)
    }

    private fun injectEvent(events: IntArray) {
        uinputDevice.injectEvents(events.joinToString(prefix = "[", postfix = "]",
                separator = ","))
    }

    fun sendBtnTouch(isDown: Boolean) {
        injectEvent(intArrayOf(EV_KEY, BTN_TOUCH, if (isDown) 1 else 0))
    }

    fun sendBtn(btnCode: Int, isDown: Boolean) {
        injectEvent(intArrayOf(EV_KEY, btnCode, if (isDown) 1 else 0))
    }

    fun sendDown(id: Int, location: Point, toolType: Int? = null) {
        injectEvent(intArrayOf(EV_ABS, ABS_MT_SLOT, id))
        injectEvent(intArrayOf(EV_ABS, ABS_MT_TRACKING_ID, id))
        if (toolType != null) injectEvent(intArrayOf(EV_ABS, ABS_MT_TOOL_TYPE, toolType))
        injectEvent(intArrayOf(EV_ABS, ABS_MT_POSITION_X, location.x))
        injectEvent(intArrayOf(EV_ABS, ABS_MT_POSITION_Y, location.y))
        injectEvent(intArrayOf(EV_SYN, SYN_REPORT, 0))
    }

    fun sendMove(id: Int, location: Point) {
        // Use same events of down.
        sendDown(id, location)
    }

    fun sendUp(id: Int) {
        injectEvent(intArrayOf(EV_ABS, ABS_MT_SLOT, id))
        injectEvent(intArrayOf(EV_ABS, ABS_MT_TRACKING_ID, INVALID_TRACKING_ID))
        injectEvent(intArrayOf(EV_SYN, SYN_REPORT, 0))
    }

    fun sendToolType(id: Int, toolType: Int) {
        injectEvent(intArrayOf(EV_ABS, ABS_MT_SLOT, id))
        injectEvent(intArrayOf(EV_ABS, ABS_MT_TOOL_TYPE, toolType))
        injectEvent(intArrayOf(EV_SYN, SYN_REPORT, 0))
    }

    private fun readRawResource(context: Context): String {
        return context.resources
            .openRawResource(rawResource)
            .bufferedReader().use { it.readText() }
    }

    private fun createDevice(instrumentation: Instrumentation, size: Size): UinputDevice {
        val json = JSONObject(readRawResource(instrumentation.targetContext))
        val resourceDeviceId: Int = json.getInt("id")
        val vendorId = json.getInt("vid")
        val productId = json.getInt("pid")
        port = json.getString("port")

        // Use the display size to set maximum values
        val absInfo: JSONArray = json.getJSONArray("abs_info")
        for (i in 0 until absInfo.length()) {
            val item = absInfo.getJSONObject(i)
            if (item.get("code") == ABS_MT_POSITION_X) {
                item.getJSONObject("info").put("maximum", size.getWidth() - 1)
            }
            if (item.get("code") == ABS_MT_POSITION_Y) {
                item.getJSONObject("info").put("maximum", size.getHeight() - 1)
            }
        }

        // Create the uinput device.
        val registerCommand = json.toString()
        return UinputDevice(instrumentation, resourceDeviceId,
                vendorId, productId, source, registerCommand)
    }

    private fun associateWith(display: Display) {
        runWithShellPermissionIdentity(
                { inputManager.addUniqueIdAssociation(port, display.uniqueId!!) },
                "android.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY")
    }

    override fun close() {
        runWithShellPermissionIdentity(
                { inputManager.removeUniqueIdAssociation(port) },
                "android.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY")
        uinputDevice.close()
    }

    companion object {
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TOOL_TYPE = 0x37
        const val ABS_MT_TRACKING_ID = 0x39
        const val BTN_TOUCH = 0x14a
        const val SYN_REPORT = 0
        const val MT_TOOL_FINGER = 0
        const val MT_TOOL_PEN = 1
        const val MT_TOOL_PALM = 2
        const val INVALID_TRACKING_ID = -1
    }
}
