/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.hardware.input.InputManager
import android.os.ParcelFileDescriptor
import android.platform.helpers.uinput.UInputDevice
import android.platform.uiautomator_helpers.DeviceHelpers
import android.view.InputDevice
import androidx.core.content.getSystemService
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.runner.Description

/**
 * This rule allows end-to-end tests to add input devices through Uinput more easily. Additionally
 * it will wait for registration to complete and unregister devices after the test is complete.
 *
 * Sample usage:
 * ```
 * class InputDeviceTest {
 *     @get:Rule
 *     val inputDeviceRule = InputDeviceRule()
 *
 *     @Test
 *     fun testWithInputDevice() {
 *         inputDeviceRule.registerDevice(UinputKeyboard())
 *         // Continue test with input device added
 *     }
 * }
 * ```
 */
class InputDeviceRule : TestWatcher() {

    private val inputManager = DeviceHelpers.context.getSystemService<InputManager>()!!
    private val deviceAddedMap = mutableMapOf<UInputDevice, CountDownLatch>()
    private val inputManagerDevices = mutableMapOf<DeviceId, UInputDevice>()
    private val closeablesToCloseOnFinish: MutableList<Closeable> = mutableListOf()

    private val inputDeviceListenerDelegate =
        object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = updateInputDevice(DeviceId(deviceId))

            override fun onInputDeviceChanged(deviceId: Int) = updateInputDevice(DeviceId(deviceId))

            override fun onInputDeviceRemoved(deviceId: Int) {
                val deviceIdWrapped = DeviceId(deviceId)
                inputManagerDevices[deviceIdWrapped]?.let {
                    deviceAddedMap.remove(it)
                    inputManagerDevices.remove(deviceIdWrapped)
                }
            }
        }

    override fun starting(description: Description?) {
        super.starting(description)
        inputManager.registerInputDeviceListener(
            inputDeviceListenerDelegate,
            DeviceHelpers.context.mainThreadHandler
        )
    }

    override fun finished(description: Description?) {
        closeablesToCloseOnFinish.forEach { it.close() }
        inputManager.unregisterInputDeviceListener(inputDeviceListenerDelegate)
        deviceAddedMap.clear()
        inputManagerDevices.clear()
    }

    /**
     * Registers the provided device with Uinput. This call waits for
     * [InputManager.InputDeviceListener.onInputDeviceAdded] to be called before returning
     *
     * @throws RuntimeException if the device did not register successfully.
     */
    fun registerDevice(device: UInputDevice) {
        val parcelFileDescriptors =
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommandRw("uinput -")
        val (stdOut, stdIn) = parcelFileDescriptors
        ParcelFileDescriptor.AutoCloseInputStream(stdOut).also { closeablesToCloseOnFinish.add(it) }
        val outputStream =
            ParcelFileDescriptor.AutoCloseOutputStream(stdIn).also {
                closeablesToCloseOnFinish.add(it)
            }

        deviceAddedMap.putIfAbsent(device, CountDownLatch(1))

        writeCommand(device.getRegisterCommand(), outputStream)

        deviceAddedMap[device]!!.let { latch ->
            latch.await(20, TimeUnit.SECONDS)
            if (latch.count != 0L) {
                throw RuntimeException(
                    "Did not receive added notification for device ${device.name}"
                )
            }
        }
    }

    private fun writeCommand(command: String, outputStream: OutputStream) {
        outputStream.write(command.toByteArray())
        outputStream.flush()
    }

    private fun updateInputDevice(deviceId: DeviceId) {
        val device: InputDevice = inputManager.getInputDevice(deviceId.deviceId) ?: return
        val uinputDevice = deviceAddedMap.keys.firstOrNull { it.isInputDevice(device) } ?: return

        inputManagerDevices[deviceId] = uinputDevice
        deviceAddedMap[uinputDevice]!!.countDown()
    }

    @JvmInline value class DeviceId(val deviceId: Int)
}
