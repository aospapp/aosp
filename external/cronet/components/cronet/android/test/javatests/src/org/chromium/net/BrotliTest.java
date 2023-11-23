// Copyright 2017 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static org.chromium.net.CronetTestRule.SERVER_CERT_PEM;
import static org.chromium.net.CronetTestRule.SERVER_KEY_PKCS8_PEM;
import static org.chromium.net.CronetTestRule.getContext;

import android.net.http.HttpEngine;
import android.net.http.ExperimentalHttpEngine;
import android.net.http.UrlRequest;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.net.CronetTestRule.OnlyRunNativeCronet;
import org.chromium.net.CronetTestRule.RequiresMinApi;

/**
 * Simple test for Brotli support.
 */
@RunWith(AndroidJUnit4.class)
@RequiresMinApi(5) // Brotli support added in API version 5: crrev.com/465216
public class BrotliTest {
    @Rule
    public final CronetTestRule mTestRule = new CronetTestRule();

    private HttpEngine mCronetEngine;

    @Before
    public void setUp() throws Exception {
        TestFilesInstaller.installIfNeeded(getContext());
        assertTrue(NativeTestServer.startNativeTestServer(getContext()));
    }

    @After
    public void tearDown() throws Exception {
        NativeTestServer.shutdownNativeTestServer();
        if (mCronetEngine != null) {
            mCronetEngine.shutdown();
        }
    }

    @Test
    @SmallTest
    @OnlyRunNativeCronet
    public void testBrotliAdvertised() throws Exception {
        ExperimentalHttpEngine.Builder builder =
                new ExperimentalHttpEngine.Builder(getContext());
        builder.setEnableBrotli(true);
        mCronetEngine = builder.build();
        String url = NativeTestServer.getEchoAllHeadersURL();
        TestUrlRequestCallback callback = startAndWaitForComplete(url);
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertTrue(
                "[" + callback.mResponseAsString + "] should reference Brotli",
                callback.mResponseAsString.matches("(?is).*accept-encoding: gzip, deflate, br.*"));
    }

    @Test
    @SmallTest
    @OnlyRunNativeCronet
    public void testBrotliNotAdvertised() throws Exception {
        ExperimentalHttpEngine.Builder builder =
                new ExperimentalHttpEngine.Builder(getContext());
        mCronetEngine = builder.build();
        String url = NativeTestServer.getEchoAllHeadersURL();
        TestUrlRequestCallback callback = startAndWaitForComplete(url);
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertFalse(callback.mResponseAsString.contains("br"));
    }

    @Test
    @SmallTest
    @OnlyRunNativeCronet
    @Ignore // TODO(danstahr): Add test server support for setting the Brotli header
    public void testBrotliDecoded() throws Exception {
        ExperimentalHttpEngine.Builder builder =
                new ExperimentalHttpEngine.Builder(getContext());
        builder.setEnableBrotli(true);
        mCronetEngine = builder.build();
        String url = Http2TestServer.getServeSimpleBrotliResponse();
        TestUrlRequestCallback callback = startAndWaitForComplete(url);
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        String expectedResponse = "The quick brown fox jumps over the lazy dog";
        assertEquals(expectedResponse, callback.mResponseAsString);
        assertEquals(
                callback.mResponseInfo.getHeaders().getAsMap().get("content-encoding").get(0),
                "br");
    }

    private TestUrlRequestCallback startAndWaitForComplete(String url) {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                mCronetEngine.newUrlRequestBuilder(url, callback, callback.getExecutor());
        builder.build().start();
        callback.blockForDone();
        return callback;
    }
}
