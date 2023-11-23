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

package com.android.federatedcompute.services.http;

import android.util.Log;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/** Utility class containing http related variable e.g. headers, method. */
public final class HttpClientUtil {
    private static final String TAG = "HttpClientUtil";
    public static final String IDENTITY_ENCODING_HDR = "identity";
    public static final String CONTENT_ENCODING_HDR = "Content-Encoding";
    public static final String CONTENT_LENGTH_HDR = "Content-Length";
    public static final String GZIP_ENCODING_HDR = "gzip";
    public static final String API_KEY_HDR = "x-goog-api-key";
    public static final String CONTENT_TYPE_HDR = "Content-Type";
    public static final String PROTOBUF_CONTENT_TYPE = "application/x-protobuf";
    public static final String OCTET_STREAM = "application/octet-stream";
    public static final String CLIENT_DECODE_GZIP_SUFFIX = "+gzip";
    public static final int HTTP_OK_STATUS = 200;
    public static final String FAKE_API_KEY = "FAKE_API_KEY";
    public static final int DEFAULT_BUFFER_SIZE = 1024;
    public static final byte[] EMPTY_BODY = new byte[0];

    /** The supported http methods. */
    public enum HttpMethod {
        GET,
        POST
    }

    /** Compresses the input data using Gzip. */
    public static ByteString compressWithGzip(ByteString uncompressedData) {
        try (ByteString.Output outputStream = ByteString.newOutput(uncompressedData.size());
                GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
            gzipOutputStream.write(uncompressedData.toByteArray());
            gzipOutputStream.finish();
            return outputStream.toByteString();
        } catch (IOException e) {
            Log.e(TAG, "Failed to compress using Gzip");
            throw new IllegalArgumentException("Failed to compress using Gzip", e);
        }
    }

    private HttpClientUtil() {}
}
