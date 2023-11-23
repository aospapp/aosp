// Copyright 2022 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static org.chromium.net.CronetTestRule.SERVER_CERT_PEM;
import static org.chromium.net.CronetTestRule.SERVER_KEY_PKCS8_PEM;
import static org.chromium.net.CronetTestRule.assertContains;
import static org.chromium.net.CronetTestRule.getContext;
import static org.chromium.net.CronetTestRule.getTestStorage;

import android.net.http.BidirectionalStream;
import android.net.http.ConnectionMigrationOptions;
import android.net.http.DnsOptions;
import android.net.http.ExperimentalHttpEngine;
import android.net.http.HttpEngine;
import android.net.http.IHttpEngineBuilder;
import android.net.http.NetworkException;
import android.net.http.QuicOptions;
import android.net.http.UrlRequest;

import androidx.annotation.OptIn;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import org.chromium.base.Log;
import org.chromium.base.PathUtils;
import org.chromium.base.annotations.JNINamespace;
import org.chromium.base.annotations.NativeMethods;
import org.chromium.base.test.util.DisabledTest;
import org.chromium.net.CronetTestRule.OnlyRunNativeCronet;
import android.net.http.DnsOptions.StaleDnsOptions;
import org.chromium.net.impl.CronetUrlRequestContext;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/** Tests for experimental options. */
@RunWith(AndroidJUnit4.class)
@JNINamespace("cronet")
@OptIn(markerClass = {ConnectionMigrationOptions.Experimental.class, DnsOptions.Experimental.class,
               QuicOptions.Experimental.class, QuicOptions.QuichePassthroughOption.class})
public class ExperimentalOptionsTest {
    private static final String EXPECTED_CONNECTION_MIGRATION_ENABLED_STRING =
            "{\"QUIC\":{\"migrate_sessions_on_network_change_v2\":true}}";

    @Rule
    public final CronetTestRule mTestRule = new CronetTestRule();
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private static final String TAG = ExperimentalOptionsTest.class.getSimpleName();
    private ExperimentalHttpEngine.Builder mBuilder;
    private CountDownLatch mHangingUrlLatch;

    @Before
    public void setUp() throws Exception {
        mBuilder = new ExperimentalHttpEngine.Builder(getContext());
        mHangingUrlLatch = new CountDownLatch(1);
        CronetTestUtil.setMockCertVerifierForTesting(
               mBuilder, QuicTestServer.createMockCertVerifier());
        assertTrue(Http2TestServer.startHttp2TestServer(
                getContext(), SERVER_CERT_PEM, SERVER_KEY_PKCS8_PEM, mHangingUrlLatch));
    }

    @After
    public void tearDown() throws Exception {
        mHangingUrlLatch.countDown();
        assertTrue(Http2TestServer.shutdownHttp2TestServer());
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    @Ignore("b/275345637 Needs HTTP2 Server")
    // Tests that NetLog writes effective experimental options to NetLog.
    public void testNetLog() throws Exception {
        File directory = new File(PathUtils.getDataDirectory());
        File logfile = File.createTempFile("cronet", "json", directory);
        JSONObject hostResolverParams = CronetTestUtil.generateHostResolverRules();
        JSONObject experimentalOptions =
                new JSONObject().put("HostResolverRules", hostResolverParams);
        mBuilder.setExperimentalOptions(experimentalOptions.toString());

        HttpEngine cronetEngine = mBuilder.build();
        cronetEngine.startNetLogToFile(logfile.getPath(), false);
        String url = Http2TestServer.getEchoMethodUrl();
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                cronetEngine.newUrlRequestBuilder(url, callback, callback.getExecutor());
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("GET", callback.mResponseAsString);
        cronetEngine.stopNetLog();
        assertFileContainsString(logfile, "HostResolverRules");
        assertTrue(logfile.delete());
        assertFalse(logfile.exists());
        cronetEngine.shutdown();
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    public void testEnableTelemetryTrue() throws Exception {
        JSONObject experimentalOptions = new JSONObject().put("enable_telemetry", true);
        mBuilder.setExperimentalOptions(experimentalOptions.toString());

        HttpEngine cronetEngine = mBuilder.build();
        CronetUrlRequestContext context = (CronetUrlRequestContext) mBuilder.build();
        assertTrue(context.getEnableTelemetryForTesting());
        cronetEngine.shutdown();
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    public void testEnableTelemetryDefault() throws Exception {
        HttpEngine cronetEngine = mBuilder.build();
        CronetUrlRequestContext context = (CronetUrlRequestContext) mBuilder.build();
        assertFalse(context.getEnableTelemetryForTesting());
        cronetEngine.shutdown();
    }

    @DisabledTest(message = "crbug.com/1021941")
    @Ignore("b/275345637 Needs HTTP2 Server")
    @Test
    @MediumTest
    @OnlyRunNativeCronet
    public void testSetSSLKeyLogFile() throws Exception {
        String url = Http2TestServer.getEchoMethodUrl();
        File dir = new File(PathUtils.getDataDirectory());
        File file = File.createTempFile("ssl_key_log_file", "", dir);

        JSONObject experimentalOptions = new JSONObject().put("ssl_key_log_file", file.getPath());
        mBuilder.setExperimentalOptions(experimentalOptions.toString());
        HttpEngine cronetEngine = mBuilder.build();

        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                cronetEngine.newUrlRequestBuilder(url, callback, callback.getExecutor());
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("GET", callback.mResponseAsString);

        assertFileContainsString(file, "CLIENT_RANDOM");
        assertTrue(file.delete());
        assertFalse(file.exists());
        cronetEngine.shutdown();
    }

    // Helper method to assert that file contains content. It retries 5 times
    // with a 100ms interval.
    private void assertFileContainsString(File file, String content) throws Exception {
        boolean contains = false;
        for (int i = 0; i < 5; i++) {
            contains = fileContainsString(file, content);
            if (contains) break;
            Log.i(TAG, "Retrying...");
            Thread.sleep(100);
        }
        assertTrue("file content doesn't match", contains);
    }

    // Returns whether a file contains a particular string.
    private boolean fileContainsString(File file, String content) throws IOException {
        FileInputStream fileInputStream = null;
        Log.i(TAG, "looking for [%s] in %s", content, file.getName());
        try {
            fileInputStream = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fileInputStream.read(data);
            String actual = new String(data, "UTF-8");
            boolean contains = actual.contains(content);
            if (!contains) {
                Log.i(TAG, "file content [%s]", actual);
            }
            return contains;
        } catch (FileNotFoundException e) {
            // Ignored this exception since the file will only be created when updates are
            // flushed to the disk.
            Log.i(TAG, "file not found");
        } finally {
            if (fileInputStream != null) {
                fileInputStream.close();
            }
        }
        return false;
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    // Tests that basic Cronet functionality works when host cache persistence is enabled, and that
    // persistence works.
    public void testHostCachePersistence() throws Exception {
        assertTrue(NativeTestServer.startNativeTestServer(getContext()));

        String realUrl = NativeTestServer.getSuccessURL();
        URL javaUrl = new URL(realUrl);
        String realHost = javaUrl.getHost();
        int realPort = javaUrl.getPort();
        String testHost = "host-cache-test-host";
        String testUrl = new URL("http", testHost, realPort, javaUrl.getPath()).toString();

        mBuilder.setStoragePath(getTestStorage(getContext()))
                .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISK, 0);

        // Set a short delay so the pref gets written quickly.
        JSONObject staleDns = new JSONObject()
                                      .put("enable", true)
                                      .put("delay_ms", 0)
                                      .put("allow_other_network", true)
                                      .put("persist_to_disk", true)
                                      .put("persist_delay_ms", 0);
        JSONObject experimentalOptions = new JSONObject().put("StaleDNS", staleDns);
        mBuilder.setExperimentalOptions(experimentalOptions.toString());
        CronetUrlRequestContext context = (CronetUrlRequestContext) mBuilder.build();

        // Create a HostCache entry for "host-cache-test-host".
        ExperimentalOptionsTestJni.get().writeToHostCache(
                context.getUrlRequestContextAdapter(), realHost);

        // Do a request for the test URL to make sure it's cached.
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                context.newUrlRequestBuilder(testUrl, callback, callback.getExecutor());
        UrlRequest urlRequest = builder.build();
        urlRequest.start();
        callback.blockForDone();
        assertNull(callback.mError);
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());

        // Shut down the context, persisting contents to disk, and build a new one.
        context.shutdown();
        context = (CronetUrlRequestContext) mBuilder.build();

        // Use the test URL without creating a new cache entry first. It should use the persisted
        // one.
        callback = new TestUrlRequestCallback();
        builder = context.newUrlRequestBuilder(testUrl, callback, callback.getExecutor());
        urlRequest = builder.build();
        urlRequest.start();
        callback.blockForDone();
        assertNull(callback.mError);
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        context.shutdown();
        NativeTestServer.shutdownNativeTestServer();
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    @DisabledTest(message = "https://crbug.com/1404719")
    // Experimental options should be specified through a JSON compliant string. When that is not
    // the case building a Cronet engine should fail.
    public void testWrongJsonExperimentalOptions() throws Exception {
        try {
            mBuilder.setExperimentalOptions("Not a serialized JSON object");
            HttpEngine cronetEngine = mBuilder.build();
            fail("Setting invalid JSON should have thrown an exception.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Experimental options parsing failed"));
        }
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    @Ignore("b/275345637 Needs HTTP2 Server")
    public void testDetectBrokenConnection() throws Exception {
        String url = Http2TestServer.getEchoMethodUrl();
        int heartbeatIntervalSecs = 1;
        JSONObject experimentalOptions =
                new JSONObject().put("bidi_stream_detect_broken_connection", heartbeatIntervalSecs);
        mBuilder.setExperimentalOptions(experimentalOptions.toString());
        HttpEngine cronetEngine = mBuilder.build();

        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        BidirectionalStream.Builder builder =
                cronetEngine.newBidirectionalStreamBuilder(url, callback, callback.getExecutor())
                        .setHttpMethod("GET");
        BidirectionalStream stream = builder.build();
        stream.start();
        callback.blockForDone();
        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("GET", callback.mResponseAsString);
        cronetEngine.shutdown();
    }

    @DisabledTest(message = "crbug.com/1320725")
    @Test
    @LargeTest
    @OnlyRunNativeCronet
    @Ignore("b/275345637 Needs HTTP2 Server")
    public void testDetectBrokenConnectionOnNetworkFailure() throws Exception {
        // HangingRequestUrl stops the server from replying until mHangingUrlLatch is opened,
        // simulating a network failure between client and server.
        String hangingUrl = Http2TestServer.getHangingRequestUrl();
        TestBidirectionalStreamCallback callback = new TestBidirectionalStreamCallback();
        TestRequestFinishedListener requestFinishedListener = new TestRequestFinishedListener();
        int heartbeatIntervalSecs = 1;
        JSONObject experimentalOptions =
                new JSONObject().put("bidi_stream_detect_broken_connection", heartbeatIntervalSecs);
        mBuilder.setExperimentalOptions(experimentalOptions.toString());
        ExperimentalHttpEngine cronetEngine = mBuilder.build();
        cronetEngine.addRequestFinishedListener(requestFinishedListener);
        BidirectionalStream.Builder builder =
                cronetEngine
                        .newBidirectionalStreamBuilder(hangingUrl, callback, callback.getExecutor())
                        .setHttpMethod("GET");
        BidirectionalStream stream = builder.build();
        stream.start();
        callback.blockForDone();
        assertTrue(stream.isDone());
        assertTrue(callback.mOnErrorCalled);
        assertContains("Exception in BidirectionalStream: net::ERR_HTTP2_PING_FAILED",
                callback.mError.getMessage());
        assertEquals(NetError.ERR_HTTP2_PING_FAILED,
                ((NetworkException) callback.mError).getInternalErrorCode());
        cronetEngine.shutdown();
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    public void testEnableDefaultNetworkConnectionMigrationApi_noBuilderSupport() {
        MockCronetBuilderImpl mockBuilderImpl = MockCronetBuilderImpl.withoutNativeSetterSupport();
        mBuilder = new ExperimentalHttpEngine.Builder(mockBuilderImpl);

        mBuilder.setConnectionMigrationOptions(
                ConnectionMigrationOptions.builder()
                        .setDefaultNetworkMigration(
                                ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED));
        mBuilder.build();

        assertNull(mockBuilderImpl.mConnectionMigrationOptions);
        assertJsonEquals(EXPECTED_CONNECTION_MIGRATION_ENABLED_STRING,
                mockBuilderImpl.mEffectiveExperimentalOptions);
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    public void enableDefaultNetworkConnectionMigrationApi_builderSupport() {
        MockCronetBuilderImpl mockBuilderImpl = MockCronetBuilderImpl.withNativeSetterSupport();
        mBuilder = new ExperimentalHttpEngine.Builder(mockBuilderImpl);

        mBuilder.setConnectionMigrationOptions(
                ConnectionMigrationOptions.builder()
                        .setDefaultNetworkMigration(
                                ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED));
        mBuilder.build();

        assertEquals(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED,
                mockBuilderImpl.mConnectionMigrationOptions.getDefaultNetworkMigration());
        assertNull(mockBuilderImpl.mEffectiveExperimentalOptions);
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    public void
    testEnableDefaultNetworkConnectionMigrationApi_noBuilderSupport_setterTakesPrecedence() {
        MockCronetBuilderImpl mockBuilderImpl = MockCronetBuilderImpl.withoutNativeSetterSupport();
        mBuilder = new ExperimentalHttpEngine.Builder(mockBuilderImpl);

        mBuilder.setConnectionMigrationOptions(
                ConnectionMigrationOptions.builder()
                        .setDefaultNetworkMigration(
                                ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED));
        mBuilder.setExperimentalOptions(
                "{\"QUIC\": {\"migrate_sessions_on_network_change_v2\": false}}");
        mBuilder.build();

        assertNull(mockBuilderImpl.mConnectionMigrationOptions);
        assertJsonEquals(EXPECTED_CONNECTION_MIGRATION_ENABLED_STRING,
                mockBuilderImpl.mEffectiveExperimentalOptions);
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    public void testEnablePathDegradingConnectionMigration_justNonDefaultNetwork() {
        MockCronetBuilderImpl mockBuilderImpl = MockCronetBuilderImpl.withoutNativeSetterSupport();
        mBuilder = new ExperimentalHttpEngine.Builder(mockBuilderImpl);

        mBuilder.setConnectionMigrationOptions(
                ConnectionMigrationOptions.builder()
                        .setAllowNonDefaultNetworkUsage(
                                ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED));
        mBuilder.build();

        assertNull(mockBuilderImpl.mConnectionMigrationOptions);
        assertJsonEquals("{\"QUIC\":{}}", mockBuilderImpl.mEffectiveExperimentalOptions);
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    public void testEnablePathDegradingConnectionMigration_justPort() {
        MockCronetBuilderImpl mockBuilderImpl = MockCronetBuilderImpl.withoutNativeSetterSupport();
        mBuilder = new ExperimentalHttpEngine.Builder(mockBuilderImpl);

        mBuilder.setConnectionMigrationOptions(
                ConnectionMigrationOptions.builder()
                        .setPathDegradationMigration(
                                ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED));
        mBuilder.build();

        assertNull(mockBuilderImpl.mConnectionMigrationOptions);
        assertJsonEquals("{\"QUIC\":{\"allow_port_migration\":true}}",
                mockBuilderImpl.mEffectiveExperimentalOptions);
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    @Ignore("b/267353182 fix failure")
    public void testEnablePathDegradingConnectionMigration_bothTrue() {
        MockCronetBuilderImpl mockBuilderImpl = MockCronetBuilderImpl.withoutNativeSetterSupport();
        mBuilder = new ExperimentalHttpEngine.Builder(mockBuilderImpl);

        mBuilder.setConnectionMigrationOptions(ConnectionMigrationOptions.builder()
                .setPathDegradationMigration(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
                .setAllowNonDefaultNetworkUsage(
                        ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED));
        mBuilder.build();

        assertNull(mockBuilderImpl.mConnectionMigrationOptions);
        assertJsonEquals("{\"QUIC\":{\"migrate_sessions_early_v2\":true}}",
                mockBuilderImpl.mEffectiveExperimentalOptions);
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    public void testEnablePathDegradingConnectionMigration_trueAndFalse() throws Exception {
        MockCronetBuilderImpl mockBuilderImpl = MockCronetBuilderImpl.withoutNativeSetterSupport();
        mBuilder = new ExperimentalHttpEngine.Builder(mockBuilderImpl);

        mBuilder.setConnectionMigrationOptions(ConnectionMigrationOptions.builder()
                .setPathDegradationMigration(ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
                .setAllowNonDefaultNetworkUsage(
                        ConnectionMigrationOptions.MIGRATION_OPTION_DISABLED));
        mBuilder.build();

        assertNull(mockBuilderImpl.mConnectionMigrationOptions);
        assertJsonEquals(
                "{\"QUIC\":{\"migrate_sessions_early_v2\":false,\"allow_port_migration\":true}}",
                mockBuilderImpl.mEffectiveExperimentalOptions);
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    public void testEnablePathDegradingConnectionMigration_invalid() {
        MockCronetBuilderImpl mockBuilderImpl = MockCronetBuilderImpl.withoutNativeSetterSupport();
        mBuilder = new ExperimentalHttpEngine.Builder(mockBuilderImpl);

        mBuilder.setConnectionMigrationOptions(ConnectionMigrationOptions.builder()
                .setPathDegradationMigration(ConnectionMigrationOptions.MIGRATION_OPTION_DISABLED)
                .setAllowNonDefaultNetworkUsage(
                        ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED));

        try {
            mBuilder.build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(
                    "Unable to turn on non-default network usage without path degradation"
                    + " migration"));
        }
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    public void testExperimentalOptions_allSet_viaExperimentalEngine() throws Exception {
        MockCronetBuilderImpl mockBuilderImpl = MockCronetBuilderImpl.withoutNativeSetterSupport();
        testExperimentalOptionsAllSetImpl(
                new ExperimentalHttpEngine.Builder(mockBuilderImpl), mockBuilderImpl);
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    public void testExperimentalOptions_allSet_viaNonExperimentalEngine() throws Exception {
        MockCronetBuilderImpl mockBuilderImpl = MockCronetBuilderImpl.withoutNativeSetterSupport();
        testExperimentalOptionsAllSetImpl(
                new HttpEngine.Builder(mockBuilderImpl), mockBuilderImpl);
    }

    private static void testExperimentalOptionsAllSetImpl(
            HttpEngine.Builder builder,
            MockCronetBuilderImpl mockBuilderImpl) throws Exception {
        QuicOptions quicOptions =
                QuicOptions.builder()
                        .addAllowedQuicHost("quicHost1.com")
                        .addAllowedQuicHost("quicHost2.com")
                        .addEnabledQuicVersion("quicVersion1")
                        .addEnabledQuicVersion("quicVersion2")
                        .addEnabledQuicVersion("quicVersion1")
                        .addClientConnectionOption("clientConnectionOption1")
                        .addClientConnectionOption("clientConnectionOption2")
                        .addClientConnectionOption("clientConnectionOption1")
                        .addConnectionOption("connectionOption1")
                        .addConnectionOption("connectionOption2")
                        .addConnectionOption("connectionOption1")
                        .addExtraQuicheFlag("extraQuicheFlag1")
                        .addExtraQuicheFlag("extraQuicheFlag2")
                        .addExtraQuicheFlag("extraQuicheFlag1")
                        .setCryptoHandshakeTimeout(Duration.ofSeconds(
                                toTelephoneKeyboardSequence("cryptoHs")))
                        .setIdleConnectionTimeout(
                                Duration.ofSeconds(
                                        toTelephoneKeyboardSequence("idleConTimeout")))
                        .setHandshakeUserAgent("handshakeUserAgent")
                        .setInitialBrokenServicePeriodSeconds(
                                Duration.ofSeconds(
                                        toTelephoneKeyboardSequence(
                                                "initialBrokenServicePeriod")))
                        .setInMemoryServerConfigsCacheSize(
                                toTelephoneKeyboardSequence("inMemoryCacheSize"))
                        .setPreCryptoHandshakeIdleTimeout(
                                Duration.ofSeconds(toTelephoneKeyboardSequence("preCryptoHs")))
                        .setRetransmittableOnWireTimeout(
                                Duration.ofMillis(
                                        toTelephoneKeyboardSequence("retransmitOnWireTo")))
                        .setRetryWithoutAltSvcOnQuicErrors(false)
                        .setEnableTlsZeroRtt(true)
                        .setCloseSessionsOnIpChange(false)
                        .setGoawaySessionsOnIpChange(true)
                        .setDelayJobsWithAvailableSpdySession(false)
                        .setIncreaseBrokenServicePeriodExponentially(true)
                        .build();

        DnsOptions dnsOptions =
                DnsOptions.builder()
                        .setStaleDns(DnsOptions.DNS_OPTION_ENABLED)
                        .setPreestablishConnectionsToStaleDnsResults(DnsOptions.DNS_OPTION_DISABLED)
                        .setPersistHostCache(DnsOptions.DNS_OPTION_ENABLED)
                        .setPersistHostCachePeriod(
                                Duration.ofMillis(
                                        toTelephoneKeyboardSequence("persistDelay")))
                        .setUseHttpStackDnsResolver(DnsOptions.DNS_OPTION_DISABLED)
                        .setStaleDnsOptions(
                                StaleDnsOptions.builder()
                                        .setAllowCrossNetworkUsage(DnsOptions.DNS_OPTION_ENABLED)
                                        .setFreshLookupTimeout(
                                                Duration.ofMillis(
                                                        toTelephoneKeyboardSequence(
                                                                "freshLookup")))
                                        .setMaxExpiredDelay(
                                                Duration.ofMillis(
                                                        toTelephoneKeyboardSequence(
                                                                "maxExpAge")))
                                        .setUseStaleOnNameNotResolved(
                                                DnsOptions.DNS_OPTION_DISABLED))
                        .build();

        ConnectionMigrationOptions connectionMigrationOptions =
                ConnectionMigrationOptions.builder()
                        .setDefaultNetworkMigration(
                                ConnectionMigrationOptions.MIGRATION_OPTION_DISABLED)
                        .setPathDegradationMigration(
                                ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
                        .setAllowServerMigration(false)
                        .setMigrateIdleConnections(true)
                        .setIdleMigrationPeriodSeconds(
                                Duration.ofSeconds(toTelephoneKeyboardSequence("idlePeriod")))
                        .setAllowNonDefaultNetworkUsage(
                                ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED)
                        .setMaxTimeOnNonDefaultNetworkSeconds(
                                Duration.ofSeconds(toTelephoneKeyboardSequence(
                                        "maxTimeNotDefault")))
                        .setMaxWriteErrorNonDefaultNetworkMigrationsCount(
                                toTelephoneKeyboardSequence("writeErr"))
                        .setMaxPathDegradingNonDefaultNetworkMigrationsCount(
                                toTelephoneKeyboardSequence("badPathErr"))
                        .build();

        builder.setDnsOptions(dnsOptions)
                .setConnectionMigrationOptions(connectionMigrationOptions)
                .setQuicOptions(quicOptions)
                .build();

        String formattedJson = "{"
                + "  \"AsyncDNS\": {"
                + "    \"enable\": false"
                + "  },"
                + "  \"StaleDNS\": {"
                + "    \"enable\": true,"
                + "    \"persist_to_disk\": true,"
                + "    \"persist_delay_ms\": 737740529,"
                + "    \"allow_other_network\": true,"
                + "    \"delay_ms\": 373740587,"
                + "    \"use_stale_on_name_not_resolved\": false,"
                + "    \"max_expired_time_ms\": 629397243"
                + "  },"
                + "  \"QUIC\": {"
                + "    \"race_stale_dns_on_connection\": false,"
                + "    \"migrate_sessions_on_network_change_v2\": false,"
                + "    \"allow_server_migration\": false,"
                + "    \"migrate_idle_sessions\": true,"
                + "    \"idle_session_migration_period_seconds\": 435370463,"
                + "    \"max_time_on_non_default_network_seconds\": 629840858,"
                + "    \"max_migrations_to_non_default_network_on_path_degrading\": 223720377,"
                + "    \"max_migrations_to_non_default_network_on_write_error\": 7483377,"
                + "    \"migrate_sessions_early_v2\": true,"
                + "    \"host_whitelist\": \"quicHost1.com,quicHost2.com\","
                + "    \"quic_version\": \"quicVersion1,quicVersion2\","
                + "    \"connection_options\": \"connectionOption1,connectionOption2\","
                + "    \"client_connection_options\": "
                + "        \"clientConnectionOption1,clientConnectionOption2\","
                + "    \"set_quic_flags\": \"extraQuicheFlag1,extraQuicheFlag2\","
                + "    \"max_server_configs_stored_in_properties\": 466360493,"
                + "    \"user_agent_id\": \"handshakeUserAgent\","
                + "    \"retry_without_alt_svc_on_quic_errors\": false,"
                + "    \"disable_tls_zero_rtt\": false,"
                + "    \"max_idle_time_before_crypto_handshake_seconds\": 773270647,"
                + "    \"max_time_before_crypto_handshake_seconds\": 27978647,"
                + "    \"idle_connection_timeout_seconds\": 435320688,"
                + "    \"retransmittable_on_wire_timeout_milliseconds\": 738720386,"
                + "    \"close_sessions_on_ip_change\": false,"
                + "    \"goaway_sessions_on_ip_change\": true,"
                + "    \"initial_delay_for_broken_alternative_service_seconds\": 464840463,"
                + "    \"exponential_backoff_on_initial_delay\": true,"
                + "    \"delay_main_job_with_available_spdy_session\": false"
                + "  }"
                + "}";

        assertJsonEquals(formattedJson, mockBuilderImpl.mEffectiveExperimentalOptions);
    }

    @Test
    @MediumTest
    @OnlyRunNativeCronet
    public void testExperimentalOptions_noneSet() {
        MockCronetBuilderImpl mockBuilderImpl = MockCronetBuilderImpl.withoutNativeSetterSupport();
        mBuilder = new ExperimentalHttpEngine.Builder(mockBuilderImpl);

        mBuilder.setQuicOptions(QuicOptions.builder().build())
                .setConnectionMigrationOptions(ConnectionMigrationOptions.builder().build())
                .setDnsOptions(DnsOptions.builder().build());

        mBuilder.build();
        assertJsonEquals("{\"QUIC\":{},\"AsyncDNS\":{},\"StaleDNS\":{}}",
                mockBuilderImpl.mEffectiveExperimentalOptions);
    }

    private static int toTelephoneKeyboardSequence(String string) {
        int length = string.length();
        if (length > 9) {
            return toTelephoneKeyboardSequence(string.substring(0, 5)) * 10000
                    + toTelephoneKeyboardSequence(string.substring(length - 3, length));
        }

        // This could be optimized a lot but little inefficiency in tests doesn't matter all that
        // much and readability benefits are quite significant.
        Map<String, Integer> charMap = new HashMap<>();
        charMap.put("abc", 2);
        charMap.put("def", 3);
        charMap.put("ghi", 4);
        charMap.put("jkl", 5);
        charMap.put("mno", 6);
        charMap.put("pqrs", 7);
        charMap.put("tuv", 8);
        charMap.put("xyz", 9);

        int result = 0;
        for (int i = 0; i < length; i++) {
            result *= 10;
            for (Map.Entry<String, Integer> mapping : charMap.entrySet()) {
                if (mapping.getKey().contains(
                            string.substring(i, i + 1).toLowerCase(Locale.ROOT))) {
                    result += mapping.getValue();
                    break;
                }
            }
        }
        return result;
    }

    private static void assertJsonEquals(String expected, String actual) {
        try {
            JSONObject expectedJson = new JSONObject(expected);
            JSONObject actualJson = new JSONObject(actual);

            assertJsonEquals(expectedJson, actualJson);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertJsonEquals(JSONObject expected, JSONObject actual)
            throws JSONException {
        assertEquals(jsonKeys(expected), jsonKeys(actual));

        for (String key : jsonKeys(expected)) {
            Object expectedValue = expected.get(key);
            Object actualValue = actual.get(key);
            if (expectedValue == actualValue) {
                continue;
            }
            if (expectedValue instanceof JSONObject) {
                if (actualValue instanceof JSONObject) {
                    assertJsonEquals((JSONObject) expectedValue, (JSONObject) actualValue);
                } else {
                    fail("key [" + key + "]: expected [" + expectedValue + "] but got ["
                            + actualValue + "]");
                }
            } else {
                assertEquals(expectedValue, actualValue);
            }
        }
    }

    private static Set<String> jsonKeys(JSONObject json) throws JSONException {
        Set<String> result = new HashSet<>();

        Iterator<String> keys = json.keys();

        while (keys.hasNext()) {
            String key = keys.next();
            result.add(key);
        }

        return result;
    }

    // Mocks make life downstream miserable so use a custom mock-like class.
    private static class MockCronetBuilderImpl extends IHttpEngineBuilder {
        private ConnectionMigrationOptions mConnectionMigrationOptions;
        private String mTempExperimentalOptions;
        private String mEffectiveExperimentalOptions;

        private final boolean mSupportsConnectionMigrationConfigOption;

        static MockCronetBuilderImpl withNativeSetterSupport() {
            return new MockCronetBuilderImpl(true);
        }

        static MockCronetBuilderImpl withoutNativeSetterSupport() {
            return new MockCronetBuilderImpl(false);
        }

        private MockCronetBuilderImpl(boolean supportsConnectionMigrationConfigOption) {
            this.mSupportsConnectionMigrationConfigOption = supportsConnectionMigrationConfigOption;
        }

        @Override
        public IHttpEngineBuilder addPublicKeyPins(String hostName, Set<byte[]> pinsSha256,
                boolean includeSubdomains, Instant expirationDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IHttpEngineBuilder addQuicHint(String host, int port, int alternatePort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IHttpEngineBuilder enableHttp2(boolean value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IHttpEngineBuilder enableHttpCache(int cacheMode, long maxSize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IHttpEngineBuilder enablePublicKeyPinningBypassForLocalTrustAnchors(
                boolean value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IHttpEngineBuilder enableQuic(boolean value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IHttpEngineBuilder enableSdch(boolean value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IHttpEngineBuilder setExperimentalOptions(String options) {
            mTempExperimentalOptions = options;
            return this;
        }

        @Override
        public IHttpEngineBuilder setStoragePath(String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IHttpEngineBuilder setUserAgent(String userAgent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getDefaultUserAgent() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IHttpEngineBuilder setConnectionMigrationOptions(
                ConnectionMigrationOptions options) {
            mConnectionMigrationOptions = options;
            return this;
        }

        @Override
        public Set<Integer> getSupportedConfigOptions() {
            if (mSupportsConnectionMigrationConfigOption) {
                return Collections.singleton(IHttpEngineBuilder.CONNECTION_MIGRATION_OPTIONS);
            } else {
                return Collections.emptySet();
            }
        }

        @Override
        public ExperimentalHttpEngine build() {
            mEffectiveExperimentalOptions = mTempExperimentalOptions;
            return null;
        }
    }

    @NativeMethods("cronet_tests")
    interface Natives {
        // Sets a host cache entry with hostname "host-cache-test-host" and an AddressList
        // containing the provided address.
        void writeToHostCache(long adapter, String address);

        // Whether Cronet engine creation can fail due to failure during experimental options
        // parsing.
        boolean experimentalOptionsParsingIsAllowedToFail();
    }
}
