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

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Creates a cold flow of intents based on an intent filter. If used multiple times in a same class,
 * this flow should be transformed into a shared flow.
 *
 * @param context context on which to register the broadcast receiver.
 * @param intentFilter intent filter.
 * @return cold flow.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
fun intentFlow(context: Context, intentFilter: IntentFilter) = callbackFlow {
  val broadcastReceiver: BroadcastReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        trySendBlocking(intent)
      }
    }
  context.registerReceiver(broadcastReceiver, intentFilter)

  awaitClose { context.unregisterReceiver(broadcastReceiver) }
}

/**
 * Creates a gRPC coroutine in a given coroutine scope which executes a given suspended function
 * returning a gRPC response and sends it on a given gRPC stream observer.
 *
 * @param T the type of gRPC response.
 * @param scope coroutine scope used to run the coroutine.
 * @param responseObserver the gRPC stream observer on which to send the response.
 * @param block the suspended function to execute to get the response.
 * @return reference to the coroutine as a Job.
 *
 * Example usage:
 * ```
 * override fun grpcMethod(
 *   request: TypeOfRequest,
 *   responseObserver: StreamObserver<TypeOfResponse> {
 *     grpcUnary(scope, responseObserver) {
 *       block
 *     }
 *   }
 * }
 * ```
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
fun <T> grpcUnary(
  scope: CoroutineScope,
  responseObserver: StreamObserver<T>,
  block: suspend () -> T
): Job {
  return scope.launch {
    try {
      val response = block()
      responseObserver.onNext(response)
      responseObserver.onCompleted()
    } catch (e: Throwable) {
      e.printStackTrace()
      responseObserver.onError(e)
    }
  }
}

/**
 * Synchronous method to get a Bluetooth profile proxy.
 *
 * @param T the type of profile proxy (e.g. BluetoothA2dp)
 * @param context context
 * @param bluetoothAdapter local Bluetooth adapter
 * @param profile identifier of the Bluetooth profile (e.g. BluetoothProfile#A2DP)
 * @return T the desired profile proxy
 */
@Suppress("UNCHECKED_CAST")
@kotlinx.coroutines.ExperimentalCoroutinesApi
fun <T> getProfileProxy(context: Context, profile: Int): T {
  var proxy: T
  runBlocking {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter = bluetoothManager.adapter

    val flow = callbackFlow {
      val serviceListener =
        object : BluetoothProfile.ServiceListener {
          override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            trySendBlocking(proxy)
          }
          override fun onServiceDisconnected(profile: Int) {}
        }

      bluetoothAdapter.getProfileProxy(context, serviceListener, profile)

      awaitClose {}
    }
    proxy = flow.first() as T
  }
  return proxy
}
