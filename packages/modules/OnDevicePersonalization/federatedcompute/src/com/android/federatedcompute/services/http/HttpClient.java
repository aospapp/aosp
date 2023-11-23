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

import static com.android.federatedcompute.services.http.HttpClientUtil.HTTP_OK_STATUS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;

import com.google.common.annotations.VisibleForTesting;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The HTTP client to be used by the FederatedCompute to communicate with remote federated servers.
 */
public class HttpClient {
    private static final String TAG = "HttpClient";
    private static final int NETWORK_CONNECT_TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(5);
    private static final int NETWORK_READ_TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(30);

    public HttpClient() {}

    @NonNull
    @VisibleForTesting
    URLConnection setup(@NonNull URL url) throws IOException {
        Objects.requireNonNull(url);
        URLConnection urlConnection = url.openConnection();
        urlConnection.setConnectTimeout(NETWORK_CONNECT_TIMEOUT_MS);
        urlConnection.setReadTimeout(NETWORK_READ_TIMEOUT_MS);
        return urlConnection;
    }

    /** Perform HTTP requests based on given information. */
    @NonNull
    @VisibleForTesting
    public FederatedComputeHttpResponse performRequest(FederatedComputeHttpRequest request)
            throws IOException {
        if (request.getUri() == null || request.getHttpMethod() == null) {
            Log.e(TAG, "Endpoint or http method is empty");
            throw new IllegalArgumentException("Endpoint or http method is empty");
        }

        URL url;
        try {
            url = new URL(request.getUri());
        } catch (MalformedURLException e) {
            Log.e(TAG, "Malformed registration target URL", e);
            throw new IllegalArgumentException("Malformed registration target URL", e);
        }

        HttpURLConnection urlConnection;
        try {
            urlConnection = (HttpURLConnection) setup(url);
        } catch (IOException e) {
            Log.e(TAG, "Failed to open target URL", e);
            throw new IllegalArgumentException("Failed to open target URL", e);
        }

        try {
            urlConnection.setRequestMethod(request.getHttpMethod().name());
            urlConnection.setInstanceFollowRedirects(true);

            if (request.getExtraHeaders() != null && !request.getExtraHeaders().isEmpty()) {
                for (Map.Entry<String, String> entry : request.getExtraHeaders().entrySet()) {
                    urlConnection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            if (request.getBody() != null && !request.getBody().isEmpty()) {
                urlConnection.setDoOutput(true);
                try (BufferedOutputStream out =
                        new BufferedOutputStream(urlConnection.getOutputStream())) {
                    request.getBody().writeTo(out);
                }
            }

            int responseCode = urlConnection.getResponseCode();
            if (responseCode == HTTP_OK_STATUS) {
                return new FederatedComputeHttpResponse.Builder()
                        .setPayload(
                                getByteArray(
                                        urlConnection.getInputStream(),
                                        urlConnection.getContentLengthLong()))
                        .setHeaders(urlConnection.getHeaderFields())
                        .setStatusCode(responseCode)
                        .build();
            } else {
                return new FederatedComputeHttpResponse.Builder()
                        .setPayload(
                                getByteArray(
                                        urlConnection.getErrorStream(),
                                        urlConnection.getContentLengthLong()))
                        .setHeaders(urlConnection.getHeaderFields())
                        .setStatusCode(responseCode)
                        .build();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to get registration response", e);
            throw new IOException("Failed to get registration response", e);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private byte[] getByteArray(@Nullable InputStream in, long contentLength) throws IOException {
        if (contentLength == 0) {
            return HttpClientUtil.EMPTY_BODY;
        }

        try {
            byte[] buffer = new byte[HttpClientUtil.DEFAULT_BUFFER_SIZE];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
