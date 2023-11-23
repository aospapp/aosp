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

package com.android.blueberry

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.util.Log
import blueberry.A2DPGrpc.A2DPImplBase
import blueberry.A2dpProto.*
import io.grpc.Status
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

@kotlinx.coroutines.ExperimentalCoroutinesApi
class A2dp(val context: Context) : A2DPImplBase() {
  private val TAG = "BlueberryA2dp"

  private val scope: CoroutineScope
  private val flow: Flow<Intent>

  private val audioManager: AudioManager =
    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private val bluetoothManager =
    context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
  private val bluetoothAdapter = bluetoothManager.adapter
  private val bluetoothA2dp = getProfileProxy<BluetoothA2dp>(context, BluetoothProfile.A2DP)

  private val audioTrack: AudioTrack =
    AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(44100)
          .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
          .build()
      )
      .setTransferMode(AudioTrack.MODE_STREAM)
      .setBufferSizeInBytes(44100 * 2 * 2)
      .build()

  init {
    scope = CoroutineScope(Dispatchers.Default)
    val intentFilter = IntentFilter()
    intentFilter.addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
    intentFilter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)

    flow = intentFlow(context, intentFilter).shareIn(scope, SharingStarted.Eagerly)
  }

  fun deinit() {
    bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp)
    scope.cancel()
  }

  override fun openSource(
    request: OpenSourceRequest,
    responseObserver: StreamObserver<OpenSourceResponse>
  ) {
    grpcUnary<OpenSourceResponse>(scope, responseObserver) {
      val address = request.connection.cookie.toByteArray().decodeToString()
      val device = bluetoothAdapter.getRemoteDevice(address)
      Log.i(TAG, "openSource: address=$address")

      if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
        Log.e(TAG, "Device is not bonded, cannot openSource")
        throw Status.UNKNOWN.asException()
      }

      if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
        bluetoothA2dp.connect(device)
        val state =
          flow
            .filter { it.getAction() == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED }
            .map { it.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR) }
            .filter {
              it == BluetoothProfile.STATE_CONNECTED || it == BluetoothProfile.STATE_DISCONNECTED
            }
            .first()

        if (state == BluetoothProfile.STATE_DISCONNECTED) {
          Log.e(TAG, "openSource failed, A2DP has been disconnected")
          throw Status.UNKNOWN.asException()
        }
      }
      val source = Source.newBuilder().setCookie(request.connection.cookie).build()
      OpenSourceResponse.newBuilder().setSource(source).build()
    }
  }

  override fun waitSource(
    request: WaitSourceRequest,
    responseObserver: StreamObserver<WaitSourceResponse>
  ) {
    grpcUnary<WaitSourceResponse>(scope, responseObserver) {
      val address = request.connection.cookie.toByteArray().decodeToString()
      val device = bluetoothAdapter.getRemoteDevice(address)
      Log.i(TAG, "waitSource: address=$address")

      if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
        Log.e(TAG, "Device is not bonded, cannot openSource")
        throw Status.UNKNOWN.asException()
      }

      if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
        val state =
          flow
            .filter { it.getAction() == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED }
            .map { it.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR) }
            .filter {
              it == BluetoothProfile.STATE_CONNECTED || it == BluetoothProfile.STATE_DISCONNECTED
            }
            .first()

        if (state == BluetoothProfile.STATE_DISCONNECTED) {
          Log.e(TAG, "waitSource failed, A2DP has been disconnected")
          throw Status.UNKNOWN.asException()
        }
      }
      val source = Source.newBuilder().setCookie(request.connection.cookie).build()
      WaitSourceResponse.newBuilder().setSource(source).build()
    }
  }

  override fun start(request: StartRequest, responseObserver: StreamObserver<StartResponse>) {
    grpcUnary<StartResponse>(scope, responseObserver) {
      val address = request.source.cookie.toByteArray().decodeToString()
      val device = bluetoothAdapter.getRemoteDevice(address)
      Log.i(TAG, "start: address=$address")

      if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
        Log.e(TAG, "Device is not connected, cannot start")
        throw Status.UNKNOWN.asException()
      }

      audioTrack.play()

      // If A2dp is not already playing, wait for it
      if (!bluetoothA2dp.isA2dpPlaying(device)) {
        flow
          .filter { it.getAction() == BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED }
          .filter {
            it.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE).address == address
          }
          .map { it.getIntExtra(BluetoothA2dp.EXTRA_STATE, BluetoothAdapter.ERROR) }
          .filter { it == BluetoothA2dp.STATE_PLAYING }
          .first()
      }
      StartResponse.getDefaultInstance()
    }
  }

  override fun suspend(request: SuspendRequest, responseObserver: StreamObserver<SuspendResponse>) {
    grpcUnary<SuspendResponse>(scope, responseObserver) {
      val address = request.source.cookie.toByteArray().decodeToString()
      val device = bluetoothAdapter.getRemoteDevice(address)
      Log.i(TAG, "suspend: address=$address")

      if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
        Log.e(TAG, "Device is not connected, cannot suspend")
        throw Status.UNKNOWN.asException()
      }

      if (!bluetoothA2dp.isA2dpPlaying(device)) {
        Log.e(TAG, "Device is already suspended, cannot suspend")
        throw Status.UNKNOWN.asException()
      }

      val a2dpPlayingStateFlow =
        flow
          .filter { it.getAction() == BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED }
          .filter {
            it.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE).address == address
          }
          .map { it.getIntExtra(BluetoothA2dp.EXTRA_STATE, BluetoothAdapter.ERROR) }

      audioTrack.pause()
      a2dpPlayingStateFlow.filter { it == BluetoothA2dp.STATE_NOT_PLAYING }.first()
      SuspendResponse.getDefaultInstance()
    }
  }

  override fun isSuspended(
    request: IsSuspendedRequest,
    responseObserver: StreamObserver<IsSuspendedResponse>
  ) {
    grpcUnary<IsSuspendedResponse>(scope, responseObserver) {
      val address = request.source.cookie.toByteArray().decodeToString()
      val device = bluetoothAdapter.getRemoteDevice(address)
      Log.i(TAG, "isSuspended: address=$address")

      if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
        Log.e(TAG, "Device is not connected, cannot get suspend state")
        throw Status.UNKNOWN.asException()
      }

      val isSuspended = bluetoothA2dp.isA2dpPlaying(device)
      IsSuspendedResponse.newBuilder().setIsSuspended(isSuspended).build()
    }
  }

  override fun close(request: CloseRequest, responseObserver: StreamObserver<CloseResponse>) {
    grpcUnary<CloseResponse>(scope, responseObserver) {
      val address = request.source.cookie.toByteArray().decodeToString()
      val device = bluetoothAdapter.getRemoteDevice(address)
      Log.i(TAG, "close: address=$address")

      if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
        Log.e(TAG, "Device is not connected, cannot close")
        throw Status.UNKNOWN.asException()
      }

      val a2dpConnectionStateChangedFlow =
        flow
          .filter { it.getAction() == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED }
          .filter {
            it.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE).address == address
          }
          .map { it.getIntExtra(BluetoothA2dp.EXTRA_STATE, BluetoothAdapter.ERROR) }

      bluetoothA2dp.disconnect(device)
      a2dpConnectionStateChangedFlow.filter { it == BluetoothA2dp.STATE_DISCONNECTED }.first()

      CloseResponse.getDefaultInstance()
    }
  }

  override fun playbackAudio(
    responseObserver: StreamObserver<PlaybackAudioResponse>
  ): StreamObserver<PlaybackAudioRequest> {
    Log.i(TAG, "playbackAudio")

    if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
      responseObserver.onError(Status.UNKNOWN.withDescription("AudioTrack is not started").asException())
    }

    // Volume is maxed out to avoid any amplitude modification of the provided audio data,
    // enabling the test runner to do comparisons between input and output audio signal.
    // Any volume modification should be done before providing the audio data.
    if (audioManager.isVolumeFixed) {
      Log.w(TAG, "Volume is fixed, cannot max out the volume")
    } else {
      val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
      if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < maxVolume) {
        audioManager.setStreamVolume(
          AudioManager.STREAM_MUSIC,
          maxVolume,
          AudioManager.FLAG_SHOW_UI
        )
      }
    }

    return object : StreamObserver<PlaybackAudioRequest> {
      override fun onNext(request: PlaybackAudioRequest) {
        val data = request.data.toByteArray()
        val written = audioTrack.write(data, 0, data.size)
        if (written != data.size) {
          responseObserver.onError(
            Status.UNKNOWN.withDescription("AudioTrack write failed").asException()
          )
        }
      }
      override fun onError(t: Throwable?) {
        Log.e(TAG, t.toString())
        responseObserver.onError(t)
      }
      override fun onCompleted() {
        responseObserver.onNext(PlaybackAudioResponse.getDefaultInstance())
        responseObserver.onCompleted()
      }
    }
  }

  override fun getAudioEncoding(
    request: GetAudioEncodingRequest,
    responseObserver: StreamObserver<GetAudioEncodingResponse>
  ) {
    grpcUnary<GetAudioEncodingResponse>(scope, responseObserver) {
      val address = request.source.cookie.toByteArray().decodeToString()
      val device = bluetoothAdapter.getRemoteDevice(address)
      Log.i(TAG, "getAudioEncoding: address=$address")

      if (bluetoothA2dp.getConnectionState(device) != BluetoothA2dp.STATE_CONNECTED) {
        Log.e(TAG, "Device is not connected, cannot getAudioEncoding")
        throw Status.UNKNOWN.asException()
      }

      // For now, we only support 44100 kHz sampling rate.
      GetAudioEncodingResponse.newBuilder()
        .setEncoding(AudioEncoding.PCM_S16_LE_44K1_STEREO)
        .build()
    }
  }

  // TODO: Remove reflection and import framework bluetooth library when it will be available
  // on AOSP.
  fun BluetoothA2dp.connect(device: BluetoothDevice) =
    this.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(this, device)

  fun BluetoothA2dp.disconnect(device: BluetoothDevice) =
    this.javaClass.getMethod("disconnect", BluetoothDevice::class.java).invoke(this, device)
}
