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

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.List;
import java.util.Map;

/** Class to hold FederatedCompute http response. */
public class FederatedComputeHttpResponse {
    private Integer mStatusCode;
    private Map<String, List<String>> mHeaders;
    private byte[] mPayload;

    private FederatedComputeHttpResponse() {}

    @NonNull
    public int getStatusCode() {
        return mStatusCode;
    }

    @NonNull
    public Map<String, List<String>> getHeaders() {
        return mHeaders;
    }

    @Nullable
    public byte[] getPayload() {
        return mPayload;
    }

    /** Builder for FederatedComputeHttpResponse. */
    public static final class Builder {
        private final FederatedComputeHttpResponse mHttpResponse;

        /** Default constructor of {@link FederatedComputeHttpResponse}. */
        public Builder() {
            mHttpResponse = new FederatedComputeHttpResponse();
        }

        /** Set the status code of http response. */
        public Builder setStatusCode(int statusCode) {
            mHttpResponse.mStatusCode = statusCode;
            return this;
        }

        /** Set headers of http response. */
        public Builder setHeaders(Map<String, List<String>> headers) {
            mHttpResponse.mHeaders = headers;
            return this;
        }

        /** Set payload of http response. */
        public Builder setPayload(byte[] payload) {
            mHttpResponse.mPayload = payload;
            return this;
        }
        /** Build {@link FederatedComputeHttpResponse}. */
        public FederatedComputeHttpResponse build() {
            if (mHttpResponse.mStatusCode == null) {
                throw new IllegalArgumentException("Empty status code.");
            }
            return mHttpResponse;
        }
    }
}
