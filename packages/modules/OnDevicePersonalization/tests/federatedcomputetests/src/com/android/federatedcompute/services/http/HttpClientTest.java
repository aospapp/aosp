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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import static java.nio.charset.StandardCharsets.UTF_8;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.federatedcompute.services.http.HttpClientUtil.HttpMethod;

import com.google.protobuf.ByteString;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public final class HttpClientTest {
    @Spy private HttpClient mHttpClient = new HttpClient();
    @Rule public MockitoRule rule = MockitoJUnit.rule();
    @Mock private HttpURLConnection mMockHttpURLConnection;

    @Test
    public void testUnableToOpenconnection_returnFailure() throws Exception {
        FederatedComputeHttpRequest request =
                FederatedComputeHttpRequest.create(
                        "https://google.com",
                        HttpMethod.POST,
                        new HashMap<>(),
                        ByteString.EMPTY,
                        false);
        doThrow(new IOException()).when(mHttpClient).setup(ArgumentMatchers.any());

        assertThrows(IllegalArgumentException.class, () -> mHttpClient.performRequest(request));
    }

    @Test
    public void testPerformGetRequestSuccess() throws Exception {
        FederatedComputeHttpRequest request =
                FederatedComputeHttpRequest.create(
                        "https://google.com",
                        HttpMethod.GET,
                        new HashMap<>(),
                        ByteString.EMPTY,
                        false);
        String successMessage = "Success!";
        InputStream mockStream = new ByteArrayInputStream(successMessage.getBytes(UTF_8));
        Map<String, List<String>> mockHeaders = new HashMap<>();
        mockHeaders.put("Header1", Arrays.asList("Value1"));
        when(mMockHttpURLConnection.getInputStream()).thenReturn(mockStream);
        when(mMockHttpURLConnection.getResponseCode()).thenReturn(200);
        when(mMockHttpURLConnection.getHeaderFields()).thenReturn(mockHeaders);
        doReturn(mMockHttpURLConnection).when(mHttpClient).setup(ArgumentMatchers.any());
        when(mMockHttpURLConnection.getContentLengthLong())
                .thenReturn((long) successMessage.length());

        FederatedComputeHttpResponse response = mHttpClient.performRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getHeaders()).isEqualTo(mockHeaders);
        assertThat(response.getPayload()).isEqualTo(successMessage.getBytes(UTF_8));
    }

    @Test
    public void testPerformGetRequestFails() throws Exception {
        String failureMessage = "FAIL!";
        FederatedComputeHttpRequest request =
                FederatedComputeHttpRequest.create(
                        "https://google.com",
                        HttpMethod.GET,
                        new HashMap<>(),
                        ByteString.EMPTY,
                        false);
        InputStream mockStream = new ByteArrayInputStream(failureMessage.getBytes(UTF_8));
        when(mMockHttpURLConnection.getErrorStream()).thenReturn(mockStream);
        when(mMockHttpURLConnection.getResponseCode()).thenReturn(503);
        when(mMockHttpURLConnection.getHeaderFields()).thenReturn(new HashMap<>());
        doReturn(mMockHttpURLConnection).when(mHttpClient).setup(ArgumentMatchers.any());
        when(mMockHttpURLConnection.getContentLengthLong())
                .thenReturn((long) failureMessage.length());

        FederatedComputeHttpResponse response = mHttpClient.performRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(503);
        assertTrue(response.getHeaders().isEmpty());
        assertThat(response.getPayload()).isEqualTo(failureMessage.getBytes(UTF_8));
    }

    @Test
    public void testPerformPostRequestSuccess() throws Exception {
        FederatedComputeHttpRequest request =
                FederatedComputeHttpRequest.create(
                        "https://google.com",
                        HttpMethod.POST,
                        new HashMap<>(),
                        ByteString.copyFromUtf8("payload"),
                        false);
        Map<String, List<String>> mockHeaders = new HashMap<>();
        mockHeaders.put("Header1", Arrays.asList("Value1"));
        when(mMockHttpURLConnection.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(mMockHttpURLConnection.getResponseCode()).thenReturn(200);
        when(mMockHttpURLConnection.getHeaderFields()).thenReturn(mockHeaders);
        doReturn(mMockHttpURLConnection).when(mHttpClient).setup(ArgumentMatchers.any());

        FederatedComputeHttpResponse response = mHttpClient.performRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getHeaders()).isEqualTo(mockHeaders);
    }
}
