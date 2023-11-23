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

package com.android.server.connectivity.mdns

import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

private const val SERVICE_NAME_1 = "service-instance-1"
private const val SERVICE_NAME_2 = "service-instance-2"
private const val SERVICE_TYPE_1 = "_test1._tcp.local"
private const val SERVICE_TYPE_2 = "_test2._tcp.local"
private const val INTERFACE_INDEX = 999
private const val DEFAULT_TIMEOUT_MS = 2000L

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsServiceCacheTest {
    private val network = mock(Network::class.java)
    private val thread = HandlerThread(MdnsServiceCacheTest::class.simpleName)
    private val handler by lazy {
        Handler(thread.looper)
    }
    private val serviceCache by lazy {
        MdnsServiceCache(thread.looper)
    }

    @Before
    fun setUp() {
        thread.start()
    }

    @After
    fun tearDown() {
        thread.quitSafely()
    }

    private fun <T> runningOnHandlerAndReturn(functor: (() -> T)): T {
        val future = CompletableFuture<T>()
        handler.post {
            future.complete(functor())
        }
        return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun addOrUpdateService(serviceType: String, network: Network, service: MdnsResponse):
            Unit = runningOnHandlerAndReturn {
        serviceCache.addOrUpdateService(serviceType, network, service) }

    private fun removeService(serviceName: String, serviceType: String, network: Network):
            Unit = runningOnHandlerAndReturn {
        serviceCache.removeService(serviceName, serviceType, network) }

    private fun getService(serviceName: String, serviceType: String, network: Network):
            MdnsResponse? = runningOnHandlerAndReturn {
        serviceCache.getCachedService(serviceName, serviceType, network) }

    private fun getServices(serviceType: String, network: Network): List<MdnsResponse> =
        runningOnHandlerAndReturn { serviceCache.getCachedServices(serviceType, network) }

    @Test
    fun testAddAndRemoveService() {
        addOrUpdateService(SERVICE_TYPE_1, network, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        var response = getService(SERVICE_NAME_1, SERVICE_TYPE_1, network)
        assertNotNull(response)
        assertEquals(SERVICE_NAME_1, response.serviceInstanceName)
        removeService(SERVICE_NAME_1, SERVICE_TYPE_1, network)
        response = getService(SERVICE_NAME_1, SERVICE_TYPE_1, network)
        assertNull(response)
    }

    @Test
    fun testGetCachedServices_multipleServiceTypes() {
        addOrUpdateService(SERVICE_TYPE_1, network, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        addOrUpdateService(SERVICE_TYPE_1, network, createResponse(SERVICE_NAME_2, SERVICE_TYPE_1))
        addOrUpdateService(SERVICE_TYPE_2, network, createResponse(SERVICE_NAME_2, SERVICE_TYPE_2))

        val responses1 = getServices(SERVICE_TYPE_1, network)
        assertEquals(2, responses1.size)
        assertTrue(responses1.stream().anyMatch { response ->
            response.serviceInstanceName == SERVICE_NAME_1
        })
        assertTrue(responses1.any { response ->
            response.serviceInstanceName == SERVICE_NAME_2
        })
        val responses2 = getServices(SERVICE_TYPE_2, network)
        assertEquals(1, responses2.size)
        assertTrue(responses2.any { response ->
            response.serviceInstanceName == SERVICE_NAME_2
        })

        removeService(SERVICE_NAME_2, SERVICE_TYPE_1, network)
        val responses3 = getServices(SERVICE_TYPE_1, network)
        assertEquals(1, responses3.size)
        assertTrue(responses3.any { response ->
            response.serviceInstanceName == SERVICE_NAME_1
        })
        val responses4 = getServices(SERVICE_TYPE_2, network)
        assertEquals(1, responses4.size)
        assertTrue(responses4.any { response ->
            response.serviceInstanceName == SERVICE_NAME_2
        })
    }

    private fun createResponse(serviceInstanceName: String, serviceType: String) = MdnsResponse(
        0 /* now */, "$serviceInstanceName.$serviceType".split(".").toTypedArray(),
            INTERFACE_INDEX, network)
}
