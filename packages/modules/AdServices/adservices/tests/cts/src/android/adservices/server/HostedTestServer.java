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

package android.adservices.server;

import android.net.Uri;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.util.Map;

/**
 * A HostedTestServer instance is an interface to a dynamically configurable test server hosted
 * online.
 *
 * <p>FLEDGE is a Privacy Preserving API (PPAPI) proposal part of Privacy Sandbox. FLEDGE has an
 * architecture which enables custom audiences (such as for remarketing). On the Android side, the
 * current implementation of FLEDGE has a hard requirement on live servers, as these servers require
 * enrollment by Google.
 *
 * <p>This dependency on live test servers via HTTPS is a blocker for creating a more comprehensive
 * CTS suite for the adservices module, so a set of test servers are required. Example usage:
 *
 * <pre>{@code
 * import static com.google.common.truth.assertThat;
 *
 * @Test
 * public void myMethod_testsCorrectly() {
 *    String adTechDomain = "com.example.ads";
 *    String deviceId = "123456";
 *    String baseDomain = String.format("%s.%s.hostedtestserver.example.com",
 *        deviceId, adTechDomain);
 *
 *    HostedMockServer server = HostedMockServer
 *      .atBaseAddress(String.format("https://%s", baseDomain))
 *      .withSessionId("123")
 *      .withSecret("456") // Store object for multiple setups.
 *      .onRequest(
 *        MatchingRequest
 *          .newBuilder()
 *          .withPath("/buyer/daily/com.example.audience")
 *          .withMethod("POST")
 *          .withQueryParam("someSignal", "123")
 *          .build())
 *      .respondWith(
 *        MockResponse
 *          .newBuilder()
 *          .setCode(FakeWebServer.HttpCodes.STATUS_OK)
 *          .setBody("() => console.log('Hello, world!');")
 *          .setMimetype("text/html")
 *          .setDelay(Duration.ofMillis(600))
 *          .build())
 *      .doSetup(); // Throws Exception on fail.
 *
 *   // Run CTS test against server living at base address.
 *   AdManager.runAdAuction(AdTechIdentifier.of(baseDomain));
 *   // ...
 *
 *   // ImmutableList<Pair<CapturedRequest, CapturedResponse>>
 *   assertThat(server.getRequestList()).isExactly(1);
 *   server.reset(); // Optional. After every request.
 * }
 * }</pre>
 *
 * <p>Mocks should be matched in the order they are set, however that is a server-side
 * implementation detail and different versions could vary.
 */
public class HostedTestServer implements AutoCloseable {
    private static final String TAG = "HostedTestServer";

    private Uri mBaseUri;
    private String mSessionId;
    private String mSecret;
    private Multimap<MatchingHttpRequest, MockHttpResponse> mHttpMocks;
    private HostedTestServerClient mClient;
    protected boolean mHasChangedSinceLastSync;

    /**
     * Set the base address (host and protocol) for the server.
     *
     * @param baseUri base host and protocol for FLEDGE test server. A valid server must serve from
     *     the root path over a supported protocol.
     * @return current instance of {@link HostedTestServer}.
     */
    public static HostedTestServer atBaseUri(Uri baseUri) {
        HostedTestServer server = new HostedTestServer();
        Preconditions.checkNotNull(baseUri);
        if (isValidBaseUri(baseUri)) {
            server.mBaseUri = baseUri;
            server.mHasChangedSinceLastSync = true;
    }
        return server;
    }

    /**
     * Set the session ID for the instance.
     *
     * @param sessionId identifier for the user's session. Should be unique per test harness
     *     instance.
     * @return current instance of {@link HostedTestServer}.
     */
    public HostedTestServer withSessionId(String sessionId) {
        if (isValidSessionId(sessionId)) {
            this.mSessionId = sessionId;
            this.mHasChangedSinceLastSync = true;
    }
        return this;
    }

    /**
     * Set the secret for the server
     *
     * @param secret used to authorize the client with the FLEDGE test server. This secret should
     *     come from the server host (Google for the public instance).
     * @return current instance of {@link HostedTestServer}.
     */
    public HostedTestServer withSecret(String secret) {
        if (isValidSecret(secret)) {
            this.mSecret = secret;
            this.mHasChangedSinceLastSync = true;
    }
        return this;
    }

    /**
     * Begin mocking a particular path.
     *
     * @param request matched request.
     * @return partial mock object, which needs to be completed to finalize the mock.
     */
    public PartialMock onRequest(MatchingHttpRequest request) {
        return new PartialMock().onRequest(request);
    }

    /**
     * Sync desired state with the FLEDGE test server.
     *
     * <p>This method syncs the desired state of all {@link onRequest} calls. As this is a batch
     * method, the operation is expensive and should be minimised to one call per test, if possible.
     *
     * @return current instance of {@link HostedTestServer}.
     */
    public HostedTestServer syncToServer() throws IOException {
        if (mClient == null) {
            mClient = new HostedTestServerClient(mBaseUri, mSessionId);
    }

        if (!isValid()) {
            // throw new UnsupportedOperationException("HostedTestServer is not in a valid state.");
        }

        if (mHasChangedSinceLastSync) {
            try {
                mClient.clearAllMocks();
                for (Map.Entry<MatchingHttpRequest, MockHttpResponse> entry :
                        mHttpMocks.entries()) {
                    if (entry.getValue() == null) {
                        continue;
                    }
                    mClient.setMock(entry.getKey(), entry.getValue());
        }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
    }

        return this;
    }

    /**
     * Get the current validity of this client instance.
     *
     * <p>A client is considered valid if all required properties (base URI, session ID, auth
     * secret) have been set, and the client is ready to send RPCs to the server.
     *
     * @return true if this server is ready for serving queries.
     */
    public boolean isValid() {
        return isValidBaseUri(mBaseUri)
                && isValidSessionId(mSessionId)
                && isValidSecret(mSecret)
                && hasValidMocks(mHttpMocks)
                && mClient != null
                && mClient.isValid();
    }

    @Override
    public void close() throws Exception {
        clearMocks();
        syncToServer();
    }

    /** Get captured request and response pairs. */
    public ImmutableMultimap<CapturedHttpRequest, CapturedHttpResponse>
            getRequestAndResponseList() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    protected void addMock(MatchingHttpRequest request, MockHttpResponse response) {
        mHasChangedSinceLastSync = true;
        if (mHttpMocks == null) {
            mHttpMocks = ArrayListMultimap.create();
    }
        mHttpMocks.put(request, response);
    }

    protected void clearMocks() {
        mHasChangedSinceLastSync = true;
        if (mHttpMocks != null) {
            mHttpMocks.clear();
    }
    }

    private static boolean hasValidMocks(Multimap<MatchingHttpRequest, MockHttpResponse> mocks) {
        return mocks.size() > 0;
    }

    private static boolean isValidSessionId(String sessionId) {
        return !Strings.isNullOrEmpty(sessionId);
    }

    private static boolean isValidSecret(String secret) {
        return !Strings.isNullOrEmpty(secret);
    }

    private static boolean isValidBaseUri(Uri baseUri) {
        return isSupportedProtocol(baseUri.getScheme())
                && Strings.isNullOrEmpty(baseUri.getPath())
                && !Strings.isNullOrEmpty(baseUri.getHost());
    }

    private static boolean isSupportedProtocol(String protocol) {
        return protocol.equals("http") || protocol.equals("https");
    }

    /** A partial mock that has either just a request or response. */
    public class PartialMock {
        private MatchingHttpRequest mRequest;
        private MockHttpResponse mResponse;

        private PartialMock() {}

        public PartialMock onRequest(MatchingHttpRequest request) {
            mRequest = request;
            updateServerIfValid();
            return this;
        }

        public HostedTestServer respondWith(MockHttpResponse response) {
            Preconditions.checkNotNull(mRequest);
            mResponse = response;
            updateServerIfValid();
            return HostedTestServer.this;
        }

        private void updateServerIfValid() {
            if (mRequest != null) {
                HostedTestServer.this.addMock(mRequest, mResponse);
            }
    }
    }
}
