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

package com.android.pandora

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore.Images.Media
import android.provider.MediaStore.MediaColumns
import android.provider.Telephony.*
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.test.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import pandora.AndroidGrpc.AndroidImplBase
import pandora.AndroidProto.*

private const val TAG = "PandoraAndroidInternal"

@kotlinx.coroutines.ExperimentalCoroutinesApi
class AndroidInternal(val context: Context) : AndroidImplBase(), Closeable {

  private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
  private val INCOMING_FILE_ACCEPT_BTN = "ACCEPT"
  private val INCOMING_FILE_TITLE = "Incoming file"
  private val INCOMING_FILE_WAIT_TIMEOUT = 2000L

  private val BT_DEVICE_SELECT_WAIT_TIMEOUT = 3000L
  private val IMAGE_FILE_NAME = "OPP_TEST_IMAGE.bmp"

  private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)!!
  private val bluetoothAdapter = bluetoothManager.adapter
  private var telephonyManager = context.getSystemService(TelephonyManager::class.java)
  private val DEFAULT_MESSAGE_LEN = 130
  private var device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

  init {
    createImageFile()
  }

  override fun close() {
    scope.cancel()

    val file =
      File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        IMAGE_FILE_NAME
      )

    if (file.exists()) {
      file.delete()
    }
  }

  override fun log(request: LogRequest, responseObserver: StreamObserver<LogResponse>) {
    grpcUnary(scope, responseObserver) {
      Log.i(TAG, request.text)
      LogResponse.getDefaultInstance()
    }
  }

  override fun setAccessPermission(
    request: SetAccessPermissionRequest,
    responseObserver: StreamObserver<Empty>
  ) {
    grpcUnary<Empty>(scope, responseObserver) {
      val bluetoothDevice = request.address.toBluetoothDevice(bluetoothAdapter)
      when (request.accessType!!) {
        AccessType.ACCESS_MESSAGE ->
          bluetoothDevice.setMessageAccessPermission(BluetoothDevice.ACCESS_ALLOWED)
        AccessType.ACCESS_PHONEBOOK ->
          bluetoothDevice.setPhonebookAccessPermission(BluetoothDevice.ACCESS_ALLOWED)
        AccessType.ACCESS_SIM ->
          bluetoothDevice.setSimAccessPermission(BluetoothDevice.ACCESS_ALLOWED)
        else -> {}
      }
      Empty.getDefaultInstance()
    }
  }

  override fun sendSMS(request: Empty, responseObserver: StreamObserver<Empty>) {
    grpcUnary<Empty>(scope, responseObserver) {
      val smsManager = SmsManager.getDefault()
      val defaultSmsSub = SubscriptionManager.getDefaultSmsSubscriptionId()
      telephonyManager = telephonyManager.createForSubscriptionId(defaultSmsSub)
      val avdPhoneNumber = telephonyManager.getLine1Number()

      smsManager.sendTextMessage(
        avdPhoneNumber,
        avdPhoneNumber,
        generateAlphanumericString(DEFAULT_MESSAGE_LEN),
        null,
        null
      )
      Empty.getDefaultInstance()
    }
  }

  override fun acceptIncomingFile(request: Empty, responseObserver: StreamObserver<Empty>) {
    grpcUnary<Empty>(scope, responseObserver) {
      device
        .wait(Until.findObject(By.text(INCOMING_FILE_TITLE)), INCOMING_FILE_WAIT_TIMEOUT)
        .click()
      device
        .wait(Until.findObject(By.text(INCOMING_FILE_ACCEPT_BTN)), INCOMING_FILE_WAIT_TIMEOUT)
        .click()
      Empty.getDefaultInstance()
    }
  }

  override fun sendFile(request: Empty, responseObserver: StreamObserver<Empty>) {
    grpcUnary<Empty>(scope, responseObserver) {
      initiateSendFile(getImageId(IMAGE_FILE_NAME), "image/bmp")
      waitAndSelectBluetoothDevice()
      Empty.getDefaultInstance()
    }
  }

  suspend private fun waitAndSelectBluetoothDevice() {
    var selectJob =
      scope.async {
        device
          .wait(Until.findObject(By.textContains("Cuttlefish")), BT_DEVICE_SELECT_WAIT_TIMEOUT)
          .click()
      }
    selectJob.await()
  }

  private fun initiateSendFile(imageId: Long, type: String) {
    val contentUri = ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, imageId)

    try {
      var sendingIntent = Intent(Intent.ACTION_SEND)
      sendingIntent.setType(type)
      val activity =
        context.packageManager!!
          .queryIntentActivities(
            sendingIntent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
          )
          .filter { it!!.loadLabel(context.packageManager) == "Bluetooth" }
          .first()
          .activityInfo
      sendingIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      sendingIntent.setComponent(ComponentName(activity.applicationInfo.packageName, activity.name))
      sendingIntent.putExtra(Intent.EXTRA_STREAM, contentUri)
      context.startActivity(sendingIntent)
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  private fun getImageId(fileName: String): Long {
    val selection = MediaColumns.DISPLAY_NAME + "=?"
    val selectionArgs = arrayOf(fileName)
    val cursor =
      context
        .getContentResolver()
        .query(Media.EXTERNAL_CONTENT_URI, null, selection, selectionArgs, null)

    cursor?.use {
      it.let {
        it.moveToFirst()
        return it.getLong(it.getColumnIndexOrThrow(Media._ID))
      }
    }
    return 0L
  }

  private fun createImageFile() {
    val bitmapImage = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
    val file =
      File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        IMAGE_FILE_NAME
      )
    var fileOutputStream: FileOutputStream? = null

    if (file.exists()) {
      file.delete()
    }
    file.createNewFile()
    try {
      fileOutputStream = FileOutputStream(file)
      bitmapImage.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)
      fileOutputStream.flush()
    } catch (e: Exception) {
      e.printStackTrace()
    } finally {
      try {
        if (fileOutputStream != null) {
          fileOutputStream.close()
        }
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }
}
