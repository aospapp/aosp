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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import android.util.Log
import blueberry.HostGrpc.HostImplBase
import blueberry.HostProto.*
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
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
import kotlinx.coroutines.launch

@kotlinx.coroutines.ExperimentalCoroutinesApi
class Host(private val context: Context, private val server: Server) : HostImplBase() {
  private val TAG = "BlueberryHost"

  private val scope: CoroutineScope
  private val flow: Flow<Intent>

  private val bluetoothManager =
    context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
  private val bluetoothAdapter = bluetoothManager.adapter

  init {
    scope = CoroutineScope(Dispatchers.Default)

    // Add all intent actions to be listened.
    val intentFilter = IntentFilter()
    intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
    intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
    intentFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)

    // Creates a shared flow of intents that can be used in all methods in the coroutine scope.
    // This flow is started eagerly to make sure that the broadcast receiver is registered before
    // any function call. This flow is only cancelled when the corresponding scope is cancelled.
    flow = intentFlow(context, intentFilter).shareIn(scope, SharingStarted.Eagerly)
  }

  fun deinit() {
    scope.cancel()
  }

  override fun reset(request: Empty, responseObserver: StreamObserver<Empty>) {
    grpcUnary<Empty>(scope, responseObserver) {
      Log.i(TAG, "reset")

      bluetoothAdapter.clearBluetooth()

      val stateFlow =
        flow.filter { it.getAction() == BluetoothAdapter.ACTION_STATE_CHANGED }.map {
          it.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        }

      if (bluetoothAdapter.isEnabled) {
        bluetoothAdapter.disable()
        stateFlow.filter { it == BluetoothAdapter.STATE_OFF }.first()
      }
      bluetoothAdapter.enable()
      stateFlow.filter { it == BluetoothAdapter.STATE_ON }.first()

      // The last expression is the return value.
      Empty.getDefaultInstance()
    }
      .invokeOnCompletion {
        Log.i(TAG, "Shutdown the gRPC Server")
        server.shutdownNow()
      }
  }

  override fun readLocalAddress(
    request: Empty,
    responseObserver: StreamObserver<ReadLocalAddressResponse>
  ) {
    grpcUnary<ReadLocalAddressResponse>(scope, responseObserver) {
      Log.i(TAG, "readLocalAddress")
      val localMacAddress = MacAddress.fromString(bluetoothAdapter.getAddress())
      ReadLocalAddressResponse.newBuilder()
        .setAddress(ByteString.copyFrom(localMacAddress.toByteArray()))
        .build()
    }
  }

  override fun waitConnection(
    request: WaitConnectionRequest,
    responseObserver: StreamObserver<WaitConnectionResponse>
  ) {
    grpcUnary<WaitConnectionResponse>(scope, responseObserver) {
      val address = MacAddress.fromBytes(request.address.toByteArray()).toString().uppercase()
      Log.i(TAG, "waitConnection: address=$address")

      if (!bluetoothAdapter.isEnabled) {
        Log.e(TAG, "Bluetooth is not enabled, cannot waitConnection")
        throw Status.UNKNOWN.asException()
      }

      // Start a new coroutine that will accept any pairing request from the device.
      val acceptPairingJob =
        scope.launch {
          val pairingRequestIntent =
            flow
              .filter { it.getAction() == BluetoothDevice.ACTION_PAIRING_REQUEST }
              .filter {
                it.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE).address ==
                  address
              }
              .first()

          val bluetoothDevice =
            pairingRequestIntent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
          val pairingVariant =
            pairingRequestIntent.getIntExtra(
              BluetoothDevice.EXTRA_PAIRING_VARIANT,
              BluetoothDevice.ERROR
            )

          if (pairingVariant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION ||
              pairingVariant == BluetoothDevice.PAIRING_VARIANT_CONSENT ||
              pairingVariant == BluetoothDevice.PAIRING_VARIANT_PIN
          ) {
            bluetoothDevice.setPairingConfirmation(true)
          }
        }

      // We only wait for bonding to be completed since we only need the ACL connection to be
      // established with the peer device (on Android state connected is sent when all profiles
      // have been connected).
      flow
        .filter { it.getAction() == BluetoothDevice.ACTION_BOND_STATE_CHANGED }
        .filter {
          it.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE).address == address
        }
        .map { it.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothAdapter.ERROR) }
        .filter { it == BluetoothDevice.BOND_BONDED }
        .first()

      // Cancel the accept pairing coroutine if still active.
      if (acceptPairingJob.isActive) {
        acceptPairingJob.cancel()
      }

      WaitConnectionResponse.newBuilder()
        .setConnection(Connection.newBuilder().setCookie(ByteString.copyFromUtf8(address)).build())
        .build()
    }
  }

  override fun disconnect(
    request: DisconnectRequest,
    responseObserver: StreamObserver<DisconnectResponse>
  ) {
    grpcUnary<DisconnectResponse>(scope, responseObserver) {
      val address = request.connection.cookie.toByteArray().decodeToString()
      Log.i(TAG, "disconnect: address=$address")

      val bluetoothDevice = bluetoothAdapter.getRemoteDevice(address)

      if (!bluetoothDevice.isConnected()) {
        Log.e(TAG, "Device is not connected, cannot disconnect")
        throw Status.UNKNOWN.asException()
      }

      val connectionStateChangedFlow =
        flow
          .filter { it.getAction() == BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED }
          .filter {
            it.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE).address == address
          }
          .map { it.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, BluetoothAdapter.ERROR) }

      bluetoothDevice.disconnect()
      connectionStateChangedFlow.filter { it == BluetoothAdapter.STATE_DISCONNECTED }.first()

      DisconnectResponse.getDefaultInstance()
    }
  }
}
