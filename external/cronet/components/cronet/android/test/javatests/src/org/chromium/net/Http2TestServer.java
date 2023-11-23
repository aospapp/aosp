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

package org.chromium.net;

import android.content.Context;

import java.io.File;
import java.util.concurrent.CountDownLatch;


/**
 * This is a STUB AOSP-only version of org.chromium.net.test.Http2TestServer. This will allow us
 * to not have comment out the tests but rather simply @Ignore them till we figure out what the
 * plan for a H2 server is.
 */
public final class Http2TestServer {

    public static boolean shutdownHttp2TestServer() throws Exception {
        return true;
    }

    public static String getServerHost() {
        throw new UnsupportedOperationException("This is a stub class");
    }

    public static int getServerPort() {
        throw new UnsupportedOperationException("This is a stub class");
    }

    public static String getServerUrl() {
        throw new UnsupportedOperationException("This is a stub class");
    }

    public static ReportingCollector getReportingCollector() {
        throw new UnsupportedOperationException("This is a stub class");
    }

    public static String getEchoAllHeadersUrl() {
        throw new UnsupportedOperationException("This is a stub class");
    }

    public static String getEchoHeaderUrl(String headerName) {
        throw new UnsupportedOperationException("This is a stub class");
    }

    public static String getEchoMethodUrl() {
        throw new UnsupportedOperationException("This is a stub class");
    }

    /**
     * When using this you must provide a CountDownLatch in the call to startHttp2TestServer.
     * The request handler will continue to hang until the provided CountDownLatch reaches 0.
     *
     * @return url of the server resource which will hang indefinitely.
     */
    public static String getHangingRequestUrl() {
        throw new UnsupportedOperationException("This is a stub class");
    }

    /**
     * @return url of the server resource which will echo every received stream data frame.
     */
    public static String getEchoStreamUrl() {
        throw new UnsupportedOperationException("This is a stub class");
    }

    /**
     * @return url of the server resource which will echo request headers as response trailers.
     */
    public static String getEchoTrailersUrl() {
        throw new UnsupportedOperationException("This is a stub class");
    }

    /**
     * @return url of a brotli-encoded server resource.
     */
    public static String getServeSimpleBrotliResponse() {
        throw new UnsupportedOperationException("This is a stub class");
    }

    /**
     * @return url of the reporting collector
     */
    public static String getReportingCollectorUrl() {
        throw new UnsupportedOperationException("This is a stub class");
    }

    /**
     * @return url of a resource that includes Reporting and NEL policy headers in its response
     */
    public static String getSuccessWithNELHeadersUrl() {
        throw new UnsupportedOperationException("This is a stub class");
    }

    /**
     * @return url of a resource that sends response headers with the same key
     */
    public static String getCombinedHeadersUrl() {
        throw new UnsupportedOperationException("This is a stub class");
    }

    public static boolean startHttp2TestServer(
            Context context, String certFileName, String keyFileName) throws Exception {
        return true;
    }

    public static boolean startHttp2TestServer(Context context, String certFileName,
            String keyFileName, CountDownLatch hangingUrlLatch) throws Exception {
        return true;
    }

    private Http2TestServer() {
    }

    private static class ReportingCollector {
    }
}
