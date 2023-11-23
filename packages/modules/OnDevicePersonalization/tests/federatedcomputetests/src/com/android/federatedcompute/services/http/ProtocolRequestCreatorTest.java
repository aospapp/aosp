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

import static com.android.federatedcompute.services.http.HttpClientUtil.API_KEY_HDR;
import static com.android.federatedcompute.services.http.HttpClientUtil.CONTENT_LENGTH_HDR;
import static com.android.federatedcompute.services.http.HttpClientUtil.CONTENT_TYPE_HDR;
import static com.android.federatedcompute.services.http.HttpClientUtil.PROTOBUF_CONTENT_TYPE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.federatedcompute.proto.ForwardingInfo;
import com.android.federatedcompute.services.http.HttpClientUtil.HttpMethod;

import com.google.protobuf.ByteString;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;

@RunWith(AndroidJUnit4.class)
public final class ProtocolRequestCreatorTest {
    private static final String REQUEST_BASE_URI = "https://initial.uri";
    private static final String API_KEY = "apiKey";
    private static final ByteString REQUEST_BODY = ByteString.copyFromUtf8("expectedBody");
    private static final String AGGREGATION_TARGET_URI = "https://aggregation.uri/";

    @Test
    public void testCreateProtobufEncodedRequest() {
        ProtocolRequestCreator requestCreator =
                new ProtocolRequestCreator(
                        REQUEST_BASE_URI, API_KEY, new HashMap<String, String>(), false);

        FederatedComputeHttpRequest request =
                requestCreator.createProtoRequest(
                        "/v1/request", HttpMethod.POST, REQUEST_BODY, true);

        assertThat(request.getUri()).isEqualTo("https://initial.uri/v1/request?%24alt=proto");
        assertThat(request.getHttpMethod()).isEqualTo(HttpMethod.POST);
        assertThat(request.getBody()).isEqualTo(REQUEST_BODY);
        HashMap<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put(API_KEY_HDR, API_KEY);
        expectedHeaders.put(CONTENT_LENGTH_HDR, String.valueOf(12));
        expectedHeaders.put(CONTENT_TYPE_HDR, PROTOBUF_CONTENT_TYPE);
        assertThat(request.getExtraHeaders()).isEqualTo(expectedHeaders);
    }

    @Test
    public void testInvalidForwardingInfo() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ProtocolRequestCreator.create(
                                        API_KEY, ForwardingInfo.getDefaultInstance(), false));

        assertThat(exception)
                .hasMessageThat()
                .isEqualTo("Missing `ForwardingInfo.target_uri_prefix`");
    }

    @Test
    public void testCreateProtocolRequestInvalidSuffix() {
        ProtocolRequestCreator requestCreator =
                new ProtocolRequestCreator(
                        REQUEST_BASE_URI, API_KEY, new HashMap<String, String>(), false);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                requestCreator.createProtoRequest(
                                        "v1/request", HttpMethod.POST, REQUEST_BODY, false));

        assertThat(exception)
                .hasMessageThat()
                .isEqualTo("uri_suffix be empty or must have a leading '/'");
    }

    @Test
    public void testCreateProtoRequest() {
        ProtocolRequestCreator requestCreator =
                new ProtocolRequestCreator(
                        REQUEST_BASE_URI, API_KEY, new HashMap<String, String>(), false);

        FederatedComputeHttpRequest request =
                requestCreator.createProtoRequest(
                        "/v1/request", HttpMethod.POST, REQUEST_BODY, false);

        assertThat(request.getUri()).isEqualTo("https://initial.uri/v1/request?%24alt=proto");
        assertThat(request.getHttpMethod()).isEqualTo(HttpMethod.POST);
        assertThat(request.getBody()).isEqualTo(REQUEST_BODY);
        HashMap<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put(API_KEY_HDR, API_KEY);
        expectedHeaders.put(CONTENT_LENGTH_HDR, String.valueOf(12));
        assertThat(request.getExtraHeaders()).isEqualTo(expectedHeaders);
    }

    @Test
    public void testCreateProtocolRequestWithForwardingInfo() {
        ForwardingInfo forwardingInfo =
                ForwardingInfo.newBuilder().setTargetUriPrefix(AGGREGATION_TARGET_URI).build();
        ProtocolRequestCreator requestCreator =
                ProtocolRequestCreator.create(API_KEY, forwardingInfo, false);

        FederatedComputeHttpRequest request =
                requestCreator.createProtoRequest(
                        "/v1/request", HttpMethod.POST, REQUEST_BODY, false);

        assertThat(request.getUri()).isEqualTo("https://aggregation.uri/v1/request?%24alt=proto");
    }
}
