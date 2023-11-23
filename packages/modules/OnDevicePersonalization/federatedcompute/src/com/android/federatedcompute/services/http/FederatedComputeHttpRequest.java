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

import static com.android.federatedcompute.services.http.HttpClientUtil.CONTENT_ENCODING_HDR;
import static com.android.federatedcompute.services.http.HttpClientUtil.CONTENT_LENGTH_HDR;
import static com.android.federatedcompute.services.http.HttpClientUtil.GZIP_ENCODING_HDR;

import com.android.federatedcompute.services.http.HttpClientUtil.HttpMethod;

import com.google.protobuf.ByteString;

import java.util.HashMap;

/** Class to hold FederatedCompute http request. */
public final class FederatedComputeHttpRequest {
    private static final String TAG = "FCPHttpRequest";
    private static final String HTTPS_SCHEMA = "https://";
    private static final String LOCAL_HOST_URI = "http://localhost:";

    private String mUri;
    private HttpMethod mHttpMethod;
    private HashMap<String, String> mExtraHeaders;
    private ByteString mBody;

    private FederatedComputeHttpRequest(
            String uri,
            HttpMethod httpMethod,
            HashMap<String, String> extraHeaders,
            ByteString body) {
        this.mUri = uri;
        this.mHttpMethod = httpMethod;
        this.mExtraHeaders = extraHeaders;
        this.mBody = body;
    }

    /** Creates a {@link FederatedComputeHttpRequest} based on given inputs. */
    public static FederatedComputeHttpRequest create(
            String uri,
            HttpMethod httpMethod,
            HashMap<String, String> extraHeaders,
            ByteString body,
            boolean useCompression) {
        if (!uri.startsWith(HTTPS_SCHEMA) && !uri.startsWith(LOCAL_HOST_URI)) {
            throw new IllegalArgumentException("Non-HTTPS URIs are not supported: " + uri);
        }
        if (useCompression) {
            body = HttpClientUtil.compressWithGzip(body);
            extraHeaders.put(CONTENT_ENCODING_HDR, GZIP_ENCODING_HDR);
        }
        if (extraHeaders.containsKey(CONTENT_LENGTH_HDR)) {
            throw new IllegalArgumentException("Content-Length header should not be provided!");
        }
        if (!body.isEmpty()) {
            if (httpMethod != HttpMethod.POST) {
                throw new IllegalArgumentException(
                        "Request method does not allow request mBody: " + httpMethod);
            }
            extraHeaders.put(CONTENT_LENGTH_HDR, String.valueOf(body.size()));
        }

        return new FederatedComputeHttpRequest(uri, httpMethod, extraHeaders, body);
    }

    public String getUri() {
        return mUri;
    }

    public ByteString getBody() {
        return mBody;
    }

    public HttpMethod getHttpMethod() {
        return mHttpMethod;
    }

    public HashMap<String, String> getExtraHeaders() {
        return mExtraHeaders;
    }
}
