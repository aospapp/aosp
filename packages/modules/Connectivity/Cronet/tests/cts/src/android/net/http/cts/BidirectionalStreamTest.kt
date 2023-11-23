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

package android.net.http.cts

import android.content.Context
import android.net.http.BidirectionalStream
import android.net.http.HttpEngine
import android.net.http.cts.util.TestBidirectionalStreamCallback
import android.net.http.cts.util.TestBidirectionalStreamCallback.ResponseStep
import android.net.http.cts.util.assumeOKStatusCode
import android.net.http.cts.util.skipIfNoInternetConnection
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith

private const val URL = "https://source.android.com"

/**
 * This tests uses a non-hermetic server. Instead of asserting, assume the next callback. This way,
 * if the request were to fail, the test would just be skipped instead of failing.
 */
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class BidirectionalStreamTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val callback = TestBidirectionalStreamCallback()
    private val httpEngine = HttpEngine.Builder(context).build()
    private var stream: BidirectionalStream? = null

    @Before
    fun setUp() {
        skipIfNoInternetConnection(context)
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        // cancel active requests to enable engine shutdown.
        stream?.let {
            it.cancel()
            callback.blockForDone()
        }
        httpEngine.shutdown()
    }

    private fun createBidirectionalStreamBuilder(url: String): BidirectionalStream.Builder {
        return httpEngine.newBidirectionalStreamBuilder(url, callback.executor, callback)
    }

    @Test
    @Throws(Exception::class)
    fun testBidirectionalStream_GetStream_CompletesSuccessfully() {
        stream = createBidirectionalStreamBuilder(URL).setHttpMethod("GET").build()
        stream!!.start()
        callback.assumeCallback(ResponseStep.ON_SUCCEEDED)
        val info = callback.mResponseInfo
        assumeOKStatusCode(info)
        MatcherAssert.assertThat(
            "Received byte count must be > 0", info.receivedByteCount, Matchers.greaterThan(0L))
        assertEquals("h2", info.negotiatedProtocol)
    }
}
