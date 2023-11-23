/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.adservices.service.measurement.registration;

import static com.android.adservices.service.measurement.SystemHealthParams.MAX_AGGREGATABLE_TRIGGER_DATA;
import static com.android.adservices.service.measurement.SystemHealthParams.MAX_AGGREGATE_DEDUPLICATION_KEYS_PER_REGISTRATION;
import static com.android.adservices.service.measurement.SystemHealthParams.MAX_AGGREGATE_KEYS_PER_REGISTRATION;
import static com.android.adservices.service.measurement.SystemHealthParams.MAX_ATTRIBUTION_EVENT_TRIGGER_DATA;
import static com.android.adservices.service.measurement.SystemHealthParams.MAX_ATTRIBUTION_FILTERS;
import static com.android.adservices.service.measurement.SystemHealthParams.MAX_FILTER_MAPS_PER_FILTER_SET;
import static com.android.adservices.service.measurement.SystemHealthParams.MAX_VALUES_PER_ATTRIBUTION_FILTER;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_REGISTRATIONS;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_REGISTRATIONS__TYPE__TRIGGER;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.adservices.measurement.RegistrationRequest;
import android.adservices.measurement.WebTriggerParams;
import android.adservices.measurement.WebTriggerRegistrationRequest;
import android.content.Context;
import android.net.Uri;
import android.util.Pair;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.measurement.AttributionConfig;
import com.android.adservices.service.measurement.FilterMap;
import com.android.adservices.service.measurement.Source;
import com.android.adservices.service.measurement.SourceFixture;
import com.android.adservices.service.measurement.Trigger;
import com.android.adservices.service.measurement.WebUtil;
import com.android.adservices.service.measurement.util.AdIdEncryption;
import com.android.adservices.service.measurement.util.Enrollment;
import com.android.adservices.service.measurement.util.UnsignedLong;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.MeasurementRegistrationResponseStats;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.net.ssl.HttpsURLConnection;

/** Unit tests for {@link AsyncTriggerFetcher} */
@RunWith(MockitoJUnitRunner.class)
@SmallTest
public final class AsyncTriggerFetcherTest {
    private static final String ANDROID_APP_SCHEME = "android-app";
    private static final String ANDROID_APP_SCHEME_URI_PREFIX = ANDROID_APP_SCHEME + "://";
    private static final String SDK_PACKAGE_NAME = "sdk.package.name";
    private static final String TRIGGER_URI = WebUtil.validUrl("https://subdomain.foo.test");
    private static final String ENROLLMENT_ID = "enrollment-id";
    private static final String TOP_ORIGIN = WebUtil.validUrl("https://baz.test");
    private static final long TRIGGER_DATA = 7;
    private static final long PRIORITY = 1;
    private static final String LONG_FILTER_STRING = "12345678901234567890123456";
    private static final String LONG_AGGREGATE_KEY_ID = "12345678901234567890123456";
    private static final String LONG_AGGREGATE_KEY_PIECE = "0x123456789012345678901234567890123";
    private static final long DEDUP_KEY = 100;
    private static final UnsignedLong DEBUG_KEY = new UnsignedLong(34787843L);
    private static final String DEBUG_JOIN_KEY = "SAMPLE_DEBUG_JOIN_KEY";
    private static final String DEFAULT_REDIRECT = WebUtil.validUrl("https://subdomain.bar.test");
    private static final String EVENT_TRIGGERS_1 =
            "[\n"
                    + "{\n"
                    + "  \"trigger_data\": \""
                    + TRIGGER_DATA
                    + "\",\n"
                    + "  \"priority\": \""
                    + PRIORITY
                    + "\",\n"
                    + "  \"deduplication_key\": \""
                    + DEDUP_KEY
                    + "\",\n"
                    + "  \"filters\": [{\n"
                    + "    \"source_type\": [\"navigation\"],\n"
                    + "    \"key_1\": [\"value_1\"] \n"
                    + "   }]\n"
                    + "}"
                    + "]\n";
    private static final String AGGREGATE_DEDUPLICATION_KEYS_1 =
            "[{\"deduplication_key\": \""
                    + DEDUP_KEY
                    + "\",\n"
                    + "\"filters\": {\n"
                    + "  \"category_1\": [\"filter\"],\n"
                    + "  \"category_2\": [\"filter\"] \n"
                    + " },"
                    + "\"not_filters\": {\n"
                    + "  \"category_1\": [\"filter\"],\n"
                    + "  \"category_2\": [\"filter\"] \n"
                    + "}}"
                    + "]";
    private static final String LIST_TYPE_REDIRECT_URI =
            WebUtil.validUrl("https://subdomain.bar.test");
    private static final String LOCATION_TYPE_REDIRECT_URI =
            WebUtil.validUrl("https://example.test");
    private static final Uri REGISTRATION_URI_1 = WebUtil.validUri("https://subdomain.foo.test");
    private static final WebTriggerParams TRIGGER_REGISTRATION_1 =
            new WebTriggerParams.Builder(REGISTRATION_URI_1).setDebugKeyAllowed(true).build();
    private static final Context CONTEXT =
            InstrumentationRegistry.getInstrumentation().getContext();
    private static final String DEFAULT_EVENT_TRIGGER_DATA = "[]";

    private static final int UNKNOWN_SOURCE_TYPE = 0;
    private static final int UNKNOWN_REGISTRATION_SURFACE_TYPE = 0;
    private static final int APP_REGISTRATION_SURFACE_TYPE = 2;
    private static final int UNKNOWN_STATUS = 0;
    private static final int SUCCESS_STATUS = 1;
    private static final int UNKNOWN_REGISTRATION_FAILURE_TYPE = 0;
    private static final String PLATFORM_AD_ID_VALUE = "SAMPLE_PLATFORM_AD_ID_VALUE";
    private static final String DEBUG_AD_ID_VALUE = "SAMPLE_DEBUG_AD_ID_VALUE";

    AsyncTriggerFetcher mFetcher;

    @Mock HttpsURLConnection mUrlConnection;
    @Mock HttpsURLConnection mUrlConnection1;
    @Mock EnrollmentDao mEnrollmentDao;
    @Mock Flags mFlags;
    @Mock AdServicesLogger mLogger;

    private MockitoSession mStaticMockSession;

    @Before
    public void setup() {
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(Enrollment.class)
                        .strictness(Strictness.WARN)
                        .startMocking();
        ExtendedMockito.doReturn(FlagsFactory.getFlagsForTest()).when(FlagsFactory::getFlags);
        mFetcher = spy(new AsyncTriggerFetcher(CONTEXT, mEnrollmentDao, mFlags, mLogger));
        // For convenience, return the same enrollment-ID since we're using many arbitrary
        // registration URIs and not yet enforcing uniqueness of enrollment.
        ExtendedMockito.doReturn(Optional.of(ENROLLMENT_ID))
                .when(
                        () ->
                                Enrollment.getValidEnrollmentId(
                                        any(), anyString(), any(), any(), any()));
        when(mFlags.getMeasurementEnableXNA()).thenReturn(false);
        when(mFlags.getMeasurementDebugJoinKeyEnrollmentAllowlist())
                .thenReturn(SourceFixture.ValidSourceParams.ENROLLMENT_ID);
        when(mFlags.getMeasurementPlatformDebugAdIdMatchingEnrollmentBlocklist()).thenReturn("");
    }

    @After
    public void cleanup() throws InterruptedException {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testBasicTriggerRequest() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        MeasurementRegistrationResponseStats expectedStats =
                new MeasurementRegistrationResponseStats.Builder(
                                AD_SERVICES_MEASUREMENT_REGISTRATIONS,
                                AD_SERVICES_MEASUREMENT_REGISTRATIONS__TYPE__TRIGGER,
                                223,
                                UNKNOWN_SOURCE_TYPE,
                                APP_REGISTRATION_SURFACE_TYPE,
                                SUCCESS_STATUS,
                                UNKNOWN_REGISTRATION_FAILURE_TYPE,
                                0)
                        .setAdTechDomain(null)
                        .build();
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + EVENT_TRIGGERS_1 + "}")));
        doReturn(5000L).when(mFlags).getMaxResponseBasedRegistrationPayloadSizeBytes();

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        asyncFetchStatus.setRegistrationDelay(0L);
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(
                asyncRegistration.getTopOrigin().toString(),
                result.getAttributionDestination().toString());
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection).setRequestMethod("POST");
        FetcherUtil.emitHeaderMetrics(mFlags, mLogger, asyncRegistration, asyncFetchStatus);
        verify(mLogger).logMeasurementRegistrationsResponseSize(eq(expectedStats));
    }

    @Test
    public void testBasicTriggerRequest_withAggregateDeduplicationKey() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        MeasurementRegistrationResponseStats expectedStats =
                new MeasurementRegistrationResponseStats.Builder(
                                AD_SERVICES_MEASUREMENT_REGISTRATIONS,
                                AD_SERVICES_MEASUREMENT_REGISTRATIONS__TYPE__TRIGGER,
                                436,
                                UNKNOWN_SOURCE_TYPE,
                                APP_REGISTRATION_SURFACE_TYPE,
                                SUCCESS_STATUS,
                                UNKNOWN_REGISTRATION_FAILURE_TYPE,
                                0)
                        .setAdTechDomain(null)
                        .build();
        String wrappedFilters =
                "[{\n"
                        + "  \"category_1\": [\"filter\"],\n"
                        + "  \"category_2\": [\"filter\"] \n"
                        + " }]";
        String wrappedNotFilters =
                "[{\n"
                        + "  \"category_1\": [\"filter\"],\n"
                        + "  \"category_2\": [\"filter\"] \n"
                        + "}]";
        String expectedAggregateDedupKeys =
                "[{\"deduplication_key\": \""
                        + DEDUP_KEY
                        + "\",\n"
                        + "\"filters\": "
                        + wrappedFilters
                        + ","
                        + "\"not_filters\":"
                        + wrappedNotFilters
                        + "}"
                        + "]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ","
                                                + "\"aggregatable_deduplication_keys\":"
                                                + AGGREGATE_DEDUPLICATION_KEYS_1
                                                + "}")));
        doReturn(5000L).when(mFlags).getMaxResponseBasedRegistrationPayloadSizeBytes();

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        asyncFetchStatus.setRegistrationDelay(0L);
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(
                asyncRegistration.getTopOrigin().toString(),
                result.getAttributionDestination().toString());
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(
                new JSONArray(expectedAggregateDedupKeys).toString(),
                result.getAggregateDeduplicationKeys());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection).setRequestMethod("POST");
        FetcherUtil.emitHeaderMetrics(mFlags, mLogger, asyncRegistration, asyncFetchStatus);
        verify(mLogger).logMeasurementRegistrationsResponseSize(eq(expectedStats));
    }

    // Tests for redirect types

    @Test
    public void testRedirectType_bothRedirectHeaderTypes_choosesListType() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        Map<String, List<String>> headers = getDefaultHeaders();

        // Populate both 'list' and 'location' type headers
        headers.put("Attribution-Reporting-Redirect", List.of(LIST_TYPE_REDIRECT_URI));
        headers.put("Location", List.of(LOCATION_TYPE_REDIRECT_URI));

        when(mUrlConnection.getHeaderFields()).thenReturn(headers);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertTriggerRegistration(asyncRegistration, result);

        assertEquals(2, asyncRedirect.getRedirects().size());
        assertEquals(
                LIST_TYPE_REDIRECT_URI,
                asyncRedirect
                        .getRedirectsByType(AsyncRegistration.RedirectType.LIST)
                        .get(0)
                        .toString());
        assertEquals(
                LOCATION_TYPE_REDIRECT_URI,
                asyncRedirect
                        .getRedirectsByType(AsyncRegistration.RedirectType.LOCATION)
                        .get(0)
                        .toString());

        verify(mUrlConnection, times(1)).setRequestMethod("POST");
    }

    @Test
    public void testRedirectType_locationRedirectHeaderType_choosesLocationType() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(302);
        Map<String, List<String>> headers = getDefaultHeaders();

        // Populate only 'location' type header
        headers.put("Location", List.of(LOCATION_TYPE_REDIRECT_URI));

        when(mUrlConnection.getHeaderFields()).thenReturn(headers);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertTriggerRegistration(asyncRegistration, result);

        assertEquals(1, asyncRedirect.getRedirects().size());
        assertEquals(LOCATION_TYPE_REDIRECT_URI, asyncRedirect.getRedirects().get(0).toString());

        verify(mUrlConnection, times(1)).setRequestMethod("POST");
    }

    @Test
    public void testRedirectType_locationRedirectType_ignoresListType() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(302);
        Map<String, List<String>> headers = getDefaultHeaders();

        // Populate both 'list' and 'location' type headers
        headers.put("Attribution-Reporting-Redirect", List.of(LIST_TYPE_REDIRECT_URI));
        headers.put("Location", List.of(LOCATION_TYPE_REDIRECT_URI));

        when(mUrlConnection.getHeaderFields()).thenReturn(headers);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertTriggerRegistration(asyncRegistration, result);

        assertEquals(2, asyncRedirect.getRedirects().size());
        assertEquals(
                LOCATION_TYPE_REDIRECT_URI,
                asyncRedirect
                        .getRedirectsByType(AsyncRegistration.RedirectType.LOCATION)
                        .get(0)
                        .toString());
        assertEquals(
                LIST_TYPE_REDIRECT_URI,
                asyncRedirect
                        .getRedirectsByType(AsyncRegistration.RedirectType.LIST)
                        .get(0)
                        .toString());

        verify(mUrlConnection, times(1)).setRequestMethod("POST");
    }

    // End tests for redirect types

    @Test
    public void testTriggerRequest_eventTriggerData_tooManyEntries() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder tooManyEntries = new StringBuilder("[");
        int i;
        for (i = 0; i < MAX_ATTRIBUTION_EVENT_TRIGGER_DATA; i++) {
            tooManyEntries.append("{\"trigger_data\": \"2\",\"priority\": \"101\"},");
        }
        tooManyEntries.append("{\"trigger_data\": \"2\",\"priority\": \"101\"}");
        tooManyEntries.append("]");
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + tooManyEntries + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(
                AsyncFetchStatus.EntityStatus.VALIDATION_ERROR, asyncFetchStatus.getEntityStatus());
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void fetchTrigger_eventTriggerDataNull_eventTriggerDataEqualsEmptyArray()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData = "null";
        String expectedResult = DEFAULT_EVENT_TRIGGER_DATA;
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(expectedResult, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_tooManyFilterMaps() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder eventTriggers = new StringBuilder("[{\"trigger_data\":\"2\",\"filters\":[");
        for (int i = 0; i < MAX_FILTER_MAPS_PER_FILTER_SET + 1; i++) {
            eventTriggers.append("{\"key-" + i + "\":[\"val1" + i + "\", \"val2" + i + "\"]}");
        }
        eventTriggers.append("}]");
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + eventTriggers + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void fetchTrigger_eventTriggerDataMissing_eventTriggerDataEqualsEmptyArray()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String expectedResult = DEFAULT_EVENT_TRIGGER_DATA;
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"debug_key\": \"" + DEBUG_KEY + "\"}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(expectedResult, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void fetchTrigger_eventTriggerDataEmptyObjects_emptyObjectsPopulated() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData = "[{\"trigger_data\":\"2\"},{},{}]";
        String expectedResult =
                "[{\"trigger_data\":\"2\"},{\"trigger_data\":\"0\"},{\"trigger_data\":\"0\"}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(expectedResult, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_tooManyNotFilterMaps() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder eventTriggers = new StringBuilder(
                "[{\"trigger_data\":\"2\",\"not_filters\":[");
        int i;
        for (i = 0; i < MAX_FILTER_MAPS_PER_FILTER_SET; i++) {
            eventTriggers.append("{\"key-" + i + "\":[\"val1" + i + "\", \"val2" + i + "\"]},");
        }
        eventTriggers.append("{\"key-" + i + "\":[\"val1" + i + "\", \"val2" + i + "\"]}");
        eventTriggers.append("}]");
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + eventTriggers + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void fetchTrigger_triggerDataNegative_triggerDataEqualsZero() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData = "[{\"trigger_data\":\"-2\",\"priority\":\"101\"}]";
        String expectedResult = "[{\"trigger_data\":\"0\",\"priority\":\"101\"}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(expectedResult, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void fetchTrigger_triggerDataTooLarge_triggerDataEqualsZero() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData =
                "[{\"trigger_data\":\"18446744073709551616\"," + "\"priority\":\"101\"}]";
        String expectedResult = "[{\"trigger_data\":\"0\",\"priority\":\"101\"}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(expectedResult, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void fetchTrigger_triggerDataNotAnInt_triggerDataEqualsZero() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData = "[{\"trigger_data\":\"101z\",\"priority\":\"101\"}]";
        String expectedResult = "[{\"trigger_data\":\"0\",\"priority\":\"101\"}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(expectedResult, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_triggerData_uses64thBit() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData =
                "[{\"trigger_data\":\"18446744073709551615\"," + "\"priority\":\"101\"}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(eventTriggerData, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_priority_negative() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData = "[{\"trigger_data\":\"2\",\"priority\":\"-101\"}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(eventTriggerData, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_priority_tooLarge() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":" + "\"18446744073709551615\"}]";
        String expectedResult = "[{\"trigger_data\":\"2\"}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(expectedResult, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_priority_notAnInt() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData = "[{\"trigger_data\":\"2\",\"priority\":\"a101\"}]";
        String expectedResult = "[{\"trigger_data\":\"2\"}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(expectedResult, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_deduplicationKey_negative() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\","
                        + "\"deduplication_key\":\"-34\"}]";
        String expectedResult = "[{\"trigger_data\":\"2\",\"priority\":\"101\"}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(expectedResult, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_deduplicationKey_tooLarge() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\","
                        + "\"deduplication_key\":\"18446744073709551616\"}]";
        String expectedResult = "[{\"trigger_data\":\"2\",\"priority\":\"101\"}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(expectedResult, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_deduplicationKey_notAnInt() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\","
                        + "\"deduplication_key\":\"145l\"}]";
        String expectedResult = "[{\"trigger_data\":\"2\",\"priority\":\"101\"}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(expectedResult, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_deduplicationKey_uses64thBit()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\","
                        + "\"deduplication_key\":\"18446744073709551615\"}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(eventTriggerData, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_wrapsFilters() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"filters\":{\"id\":[\"val1\",\"val2\"]}}]";
        String eventTriggerDataWithWrappedFilters =
                "[{\"trigger_data\":\"2\",\"filters\":[{\"id\":[\"val1\",\"val2\"]}]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(eventTriggerDataWithWrappedFilters, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_filters_invalidJson() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String filters =
                "{\"product\":[\"1234\",\"2345\"], \"\"\":[\"id\"]}";
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\"," + "\"filters\":" + filters + "}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + eventTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_filters_tooManyFilters() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder filters = new StringBuilder("{");
        filters.append(
                IntStream.range(0, MAX_ATTRIBUTION_FILTERS + 1)
                        .mapToObj(i -> "\"filter-string-" + i + "\": [\"filter-value\"]")
                        .collect(Collectors.joining(",")));
        filters.append("}");
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\"," + "\"filters\":" + filters + "}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + eventTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_filters_keyTooLong() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String filters =
                "{\"product\":[\"1234\",\"2345\"], \"" + LONG_FILTER_STRING + "\":[\"id\"]}";
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\"," + "\"filters\":" + filters + "}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + eventTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_filters_tooManyValues() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder filters =
                new StringBuilder(
                        "{"
                                + "\"filter-string-1\": [\"filter-value-1\"],"
                                + "\"filter-string-2\": [");
        filters.append(
                IntStream.range(0, MAX_VALUES_PER_ATTRIBUTION_FILTER + 1)
                        .mapToObj(i -> "\"filter-value-" + i + "\"")
                        .collect(Collectors.joining(",")));
        filters.append("]}");
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\"," + "\"filters\":" + filters + "}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + eventTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_filters_valueTooLong() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String filters =
                "{\"product\":[\"1234\",\"" + LONG_FILTER_STRING + "\"], \"ctid\":[\"id\"]}";
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\"," + "\"filters\":" + filters + "}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + eventTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_wrapsNotFilters() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"not_filters\":{\"id\":[\"val1\",\"val2\"]}}]";
        String eventTriggerDataWithWrappedNotFilters =
                "[{\"trigger_data\":\"2\",\"not_filters\":[{\"id\":[\"val1\",\"val2\"]}]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"event_trigger_data\":" + eventTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(eventTriggerDataWithWrappedNotFilters, result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_notFilters_invalidJson() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String notFilters =
                "{\"product\":[\"1234\",\"2345\"], \"\"\":[\"id\"]}";
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\","
                        + "\"not_filters\":"
                        + notFilters
                        + "}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + eventTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_notFilters_tooManyFilters() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder notFilters = new StringBuilder("{");
        notFilters.append(
                IntStream.range(0, MAX_ATTRIBUTION_FILTERS + 1)
                        .mapToObj(i -> "\"filter-string-" + i + "\": [\"filter-value\"]")
                        .collect(Collectors.joining(",")));
        notFilters.append("}");
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\","
                        + "\"not_filters\":"
                        + notFilters
                        + "}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + eventTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_notFilters_keyTooLong() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String notFilters =
                "{\"product\":[\"1234\",\"2345\"], \"" + LONG_FILTER_STRING + "\":[\"id\"]}";
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\","
                        + "\"not_filters\":"
                        + notFilters
                        + "}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + eventTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_notFilters_tooManyValues() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder notFilters =
                new StringBuilder(
                        "{"
                                + "\"filter-string-1\": [\"filter-value-1\"],"
                                + "\"filter-string-2\": [");
        notFilters.append(
                IntStream.range(0, MAX_VALUES_PER_ATTRIBUTION_FILTER + 1)
                        .mapToObj(i -> "\"filter-value-" + i + "\"")
                        .collect(Collectors.joining(",")));
        notFilters.append("]}");
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\","
                        + "\"not_filters\":"
                        + notFilters
                        + "}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + eventTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_eventTriggerData_notFilters_valueTooLong() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String notFilters =
                "{\"product\":[\"1234\",\"" + LONG_FILTER_STRING + "\"], \"ctid\":[\"id\"]}";
        String eventTriggerData =
                "[{\"trigger_data\":\"2\",\"priority\":\"101\","
                        + "\"not_filters\":"
                        + notFilters
                        + "}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + eventTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_wrapsFilters() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String filters =
                "{\"product\":[\"1234\",\"2345\"],\"cid\":[\"id\"]}";
        String wrappedFilters =
                "[{\"product\":[\"1234\",\"2345\"],\"cid\":[\"id\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"filters\":" + filters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(wrappedFilters, result.getFilters());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_tooManyFilterMaps() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder filters = new StringBuilder("[");
        for (int i = 0; i < MAX_FILTER_MAPS_PER_FILTER_SET + 1; i++) {
            filters.append("{\"key-" + i + "\":[\"val1" + i + "\", \"val2" + i + "\"]}");
        }
        filters.append("]");
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"filters\":" + filters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_filters_invalidJson() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String filters =
                "{\"product\":[\"1234\",\"2345\"], \"\"\":[\"id\"]}";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"filters\":" + filters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_AggregateDeduplicationKeys_tooManyEntries() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder tooManyEntries = new StringBuilder("[");
        tooManyEntries.append(
                IntStream.range(0, MAX_AGGREGATE_DEDUPLICATION_KEYS_PER_REGISTRATION + 1)
                        .mapToObj(i -> "{\"deduplication_key\": \"" + i + "\"}")
                        .collect(Collectors.joining(",")));
        tooManyEntries.append("]");
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ","
                                                + "\"aggregatable_deduplication_keys\":"
                                                + tooManyEntries
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_AggregateDeduplicationKeys_missingDeduplicationKey()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder deduplicationKeys = new StringBuilder("[");
        deduplicationKeys.append(
                IntStream.range(0, MAX_AGGREGATE_DEDUPLICATION_KEYS_PER_REGISTRATION)
                        .mapToObj(i -> "{\"deduplication_key\":}")
                        .collect(Collectors.joining(",")));
        deduplicationKeys.append("]");
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ","
                                                + "\"aggregatable_deduplication_keys\":"
                                                + deduplicationKeys
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_AggregateDeduplicationKeys_tooManyFilters() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder filters = new StringBuilder("{");
        filters.append(
                IntStream.range(0, MAX_ATTRIBUTION_FILTERS + 1)
                        .mapToObj(i -> "\"filter-string-" + i + "\": [\"filter-value\"]")
                        .collect(Collectors.joining(",")));
        filters.append("}");
        StringBuilder notFilters = new StringBuilder("{");
        notFilters.append(
                IntStream.range(0, MAX_ATTRIBUTION_FILTERS)
                        .mapToObj(i -> "\"not-filter-string-" + i + "\": [\"not-filter-value\"]")
                        .collect(Collectors.joining(",")));
        notFilters.append("}");
        StringBuilder deduplicationKeys = new StringBuilder("[");
        deduplicationKeys.append(
                IntStream.range(0, MAX_AGGREGATE_DEDUPLICATION_KEYS_PER_REGISTRATION)
                        .mapToObj(
                                i ->
                                        "{\"deduplication_key\":"
                                                + "\""
                                                + i
                                                + "\""
                                                + ",\"filters\" :"
                                                + filters
                                                + ","
                                                + "\"not_filters\" :\""
                                                + notFilters
                                                + "}")
                        .collect(Collectors.joining(",")));
        deduplicationKeys.append("]");

        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ",\"aggregatable_deduplication_keys\":"
                                                + deduplicationKeys
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_AggregateDeduplicationKeys_tooManyNotFilters() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder filters = new StringBuilder("{");
        filters.append(
                IntStream.range(0, MAX_ATTRIBUTION_FILTERS)
                        .mapToObj(i -> "\"filter-string-" + i + "\": [\"filter-value\"]")
                        .collect(Collectors.joining(",")));
        filters.append("}");
        StringBuilder notFilters = new StringBuilder("{");
        notFilters.append(
                IntStream.range(0, MAX_ATTRIBUTION_FILTERS + 1)
                        .mapToObj(i -> "\"not-filter-string-" + i + "\": [\"not-filter-value\"]")
                        .collect(Collectors.joining(",")));
        notFilters.append("}");
        StringBuilder deduplicationKeys = new StringBuilder("[");
        deduplicationKeys.append(
                IntStream.range(0, MAX_AGGREGATE_DEDUPLICATION_KEYS_PER_REGISTRATION)
                        .mapToObj(
                                i ->
                                        "{\"deduplication_key\":"
                                                + "\""
                                                + i
                                                + "\""
                                                + ",\"filters\" :"
                                                + filters
                                                + ","
                                                + "\"not_filters\" :\""
                                                + notFilters
                                                + "}")
                        .collect(Collectors.joining(",")));
        deduplicationKeys.append("]");

        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ","
                                                + "\"aggregatable_deduplication_keys\":"
                                                + deduplicationKeys
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_AggregateDeduplicationKeys_deduplicationKeyIsEmpty()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ","
                                                + "\"aggregatable_deduplication_keys\":{}"
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_AggregateDeduplicationKeys_deduplicationKeysNotPresent()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ","
                                                + "\"aggregatable_deduplication_keys\":"
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_filters_tooManyFilters() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder filters = new StringBuilder("{");
        filters.append(
                IntStream.range(0, MAX_ATTRIBUTION_FILTERS + 1)
                        .mapToObj(i -> "\"filter-string-" + i + "\": [\"filter-value\"]")
                        .collect(Collectors.joining(",")));
        filters.append("}");
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"filters\":" + filters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_filters_keyTooLong() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String filters =
                "{\"product\":[\"1234\",\"2345\"], \"" + LONG_FILTER_STRING + "\":[\"id\"]}";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"filters\":" + filters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_filters_tooManyValues() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder filters =
                new StringBuilder(
                        "{"
                                + "\"filter-string-1\": [\"filter-value-1\"],"
                                + "\"filter-string-2\": [");
        filters.append(
                IntStream.range(0, MAX_VALUES_PER_ATTRIBUTION_FILTER + 1)
                        .mapToObj(i -> "\"filter-value-" + i + "\"")
                        .collect(Collectors.joining(",")));
        filters.append("]}");
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"filters\":" + filters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_filters_valueTooLong() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String filters =
                "{\"product\":[\"1234\",\"" + LONG_FILTER_STRING + "\"], \"ctid\":[\"id\"]}";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"filters\":" + filters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_wrapsNotFilters() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String notFilters =
                "{\"product\":[\"1234\",\"2345\"],\"cid\":[\"id\"]}";
        String wrappedNotFilters =
                "[{\"product\":[\"1234\",\"2345\"],\"cid\":[\"id\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"not_filters\":" + notFilters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(wrappedNotFilters, result.getNotFilters());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_tooManyNotFilterMaps() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder notFilters = new StringBuilder("[");
        for (int i = 0; i < MAX_FILTER_MAPS_PER_FILTER_SET + 1; i++) {
            notFilters.append("{\"key-" + i + "\":[\"val1" + i + "\", \"val2" + i + "\"]}");
        }
        notFilters.append("]");
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{\"not_filters\":" + notFilters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_notFilters_invalidJson() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String notFilters =
                "{\"product\":[\"1234\",\"2345\"], \"\"\":[\"id\"]}";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"not_filters\":" + notFilters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_notFilters_tooManyFilters() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder notFilters = new StringBuilder("{");
        notFilters.append(
                IntStream.range(0, MAX_ATTRIBUTION_FILTERS + 1)
                        .mapToObj(i -> "\"filter-string-" + i + "\": [\"filter-value\"]")
                        .collect(Collectors.joining(",")));
        notFilters.append("}");
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"not_filters\":" + notFilters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_notFilters_keyTooLong() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String notFilters =
                "{\"product\":[\"1234\",\"2345\"], \"" + LONG_FILTER_STRING + "\":[\"id\"]}";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"not_filters\":" + notFilters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_notFilters_tooManyValues() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder notFilters =
                new StringBuilder(
                        "{"
                                + "\"filter-string-1\": [\"filter-value-1\"],"
                                + "\"filter-string-2\": [");
        notFilters.append(
                IntStream.range(0, MAX_VALUES_PER_ATTRIBUTION_FILTER + 1)
                        .mapToObj(i -> "\"filter-value-" + i + "\"")
                        .collect(Collectors.joining(",")));
        notFilters.append("]}");
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"not_filters\":" + notFilters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequest_notFilters_valueTooLong() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String notFilters =
                "{\"product\":[\"1234\",\"" + LONG_FILTER_STRING + "\"], \"ctid\":[\"id\"]}";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"not_filters\":" + notFilters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testBasicTriggerRequest_skipTriggerWhenNotEnrolled_processRedirects()
            throws IOException {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        ExtendedMockito.doReturn(Optional.empty())
                .when(
                        () ->
                                Enrollment.getValidEnrollmentId(
                                        any(), anyString(), any(), any(), any()));
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Redirect",
                                List.of(DEFAULT_REDIRECT),
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + EVENT_TRIGGERS_1 + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        verify(mFetcher, times(1)).openUrl(any());
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertEquals(1, asyncRedirect.getRedirects().size());
        assertEquals(DEFAULT_REDIRECT, asyncRedirect.getRedirects().get(0).toString());
        assertEquals(
                AsyncFetchStatus.EntityStatus.INVALID_ENROLLMENT,
                asyncFetchStatus.getEntityStatus());
        assertFalse(fetch.isPresent());
    }

    @Test
    public void testBasicTriggerRequestWithDebugKey() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{"
                                + "\"event_trigger_data\": "
                                + EVENT_TRIGGERS_1
                                + ", \"debug_key\": \""
                                + DEBUG_KEY
                                + "\""
                                + "}"));
        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(
                asyncRegistration.getTopOrigin().toString(),
                result.getAttributionDestination().toString());
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(DEBUG_KEY, result.getDebugKey());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequest_debugKey_negative() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);

        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{\"event_trigger_data\":"
                                + EVENT_TRIGGERS_1
                                + ",\"debug_key\":\"-376\"}"));

        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertNull(result.getDebugKey());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequest_debugKey_tooLarge() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);

        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{\"event_trigger_data\":"
                                + EVENT_TRIGGERS_1
                                + ",\"debug_key\":\"18446744073709551616\"}"));

        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertNull(result.getDebugKey());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequest_debugKey_notAnInt() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);

        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{\"event_trigger_data\":"
                                + EVENT_TRIGGERS_1
                                + ",\"debug_key\":\"65g43\"}"));

        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertNull(result.getDebugKey());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequest_debugKey_uses64thBit() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);

        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{\"event_trigger_data\":"
                                + EVENT_TRIGGERS_1
                                + ",\"debug_key\":\"18446744073709551615\"}"));

        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertEquals(new UnsignedLong(-1L), result.getDebugKey());
    }

    @Test
    public void testBadTriggerUrl() throws Exception {
        RegistrationRequest request = buildRequest(WebUtil.validUrl("bad-schema://foo.test"));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(
                AsyncFetchStatus.ResponseStatus.INVALID_URL, asyncFetchStatus.getResponseStatus());
        assertFalse(fetch.isPresent());
    }

    @Test
    public void testBadTriggerConnection() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doThrow(new IOException("Bad internet things"))
                .when(mFetcher)
                .openUrl(new URL(TRIGGER_URI));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(
                AsyncFetchStatus.ResponseStatus.NETWORK_ERROR,
                asyncFetchStatus.getResponseStatus());
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, never()).setRequestMethod("POST");
    }

    @Test
    public void testBadRequestReturnFailure() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(400);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + EVENT_TRIGGERS_1 + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(
                AsyncFetchStatus.ResponseStatus.SERVER_UNAVAILABLE,
                asyncFetchStatus.getResponseStatus());
        assertFalse(fetch.isPresent());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void fetchTrigger_eventTriggerDataNoFields_triggerDataEqualsZero() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\": " + "[{}]" + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(
                asyncRegistration.getTopOrigin().toString(),
                result.getAttributionDestination().toString());
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertEquals("[{\"trigger_data\":\"0\"}]", result.getEventTriggers());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testFirst200Next500_ignoreFailureReturnSuccess() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(any(URL.class));
        when(mUrlConnection.getResponseCode()).thenReturn(200).thenReturn(500);
        Map<String, List<String>> headersFirstRequest = new HashMap<>();
        headersFirstRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of("{" + "\"event_trigger_data\":" + EVENT_TRIGGERS_1 + "}"));
        headersFirstRequest.put("Attribution-Reporting-Redirect", List.of(DEFAULT_REDIRECT));
        when(mUrlConnection.getHeaderFields()).thenReturn(headersFirstRequest);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(
                asyncRegistration.getTopOrigin().toString(),
                result.getAttributionDestination().toString());
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
    }

    @Test
    public void testMissingHeaderButWithRedirect() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(any(URL.class));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(Map.of("Attribution-Reporting-Redirect", List.of(DEFAULT_REDIRECT)))
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + EVENT_TRIGGERS_1 + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertEquals(1, asyncRedirect.getRedirects().size());
        assertEquals(DEFAULT_REDIRECT, asyncRedirect.getRedirects().get(0).toString());

        assertEquals(
                AsyncFetchStatus.EntityStatus.HEADER_MISSING, asyncFetchStatus.getEntityStatus());
        assertFalse(fetch.isPresent());

        verify(mUrlConnection, times(1)).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequestWithAggregateTriggerData() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String aggregateTriggerData =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"filters\":"
                        + "[{\"conversion_subdomain\":[\"electronics.megastore\"]}],"
                        + "\"not_filters\":[{\"product\":[\"1\"]}]},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"],"
                        + "\"x_network_data\":{\"key_offset\":\"20\"}}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregateTriggerData
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(
                asyncRegistration.getTopOrigin().toString(),
                result.getAttributionDestination().toString());
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(aggregateTriggerData, result.getAggregateTriggerData());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void basicTriggerRequest_withInvalidOffsetInAggregateTriggerData_throwsParsingError()
            throws IOException {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String aggregateTriggerData =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"filters\":"
                        + "[{\"conversion_subdomain\":[\"electronics.megastore\"]}],"
                        + "\"not_filters\":[{\"product\":[\"1\"]}]},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"],"
                        + "\"x_network_data\":{\"key_offset\":\"INVALID_VALUE\"}}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregateTriggerData
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(
                AsyncFetchStatus.EntityStatus.PARSING_ERROR, asyncFetchStatus.getEntityStatus());
        assertFalse(fetch.isPresent());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void basicTriggerRequest_withNoOffsetInAggregateTriggerData_consideredValid()
            throws IOException {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String aggregateTriggerData =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"filters\":"
                        + "[{\"conversion_subdomain\":[\"electronics.megastore\"]}],"
                        + "\"not_filters\":[{\"product\":[\"1\"]}]},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"],"
                        + "\"x_network_data\":{}}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregateTriggerData
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(aggregateTriggerData, result.getAggregateTriggerData());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequestWithAggregateTriggerData_wrapsFilters() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String aggregateTriggerData =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"filters\":"
                        + "{\"conversion_subdomain\":[\"electronics.megastore\"]},"
                        + "\"not_filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        String aggregateTriggerDataWithWrappedFilters =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"filters\":"
                        + "[{\"conversion_subdomain\":[\"electronics.megastore\"]}],"
                        + "\"not_filters\":[{\"product\":[\"1\"]}]},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregateTriggerData
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(
                asyncRegistration.getTopOrigin().toString(),
                result.getAttributionDestination().toString());
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(aggregateTriggerDataWithWrappedFilters, result.getAggregateTriggerData());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequestWithAggregateTriggerData_tooManyFilterMaps()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder filters = new StringBuilder("[");
        int i;
        for (i = 0; i < MAX_FILTER_MAPS_PER_FILTER_SET; i++) {
            filters.append("{\"key-" + i + "\":[\"val1" + i + "\", \"val2" + i + "\"]},");
        }
        filters.append("{\"key-" + i + "\":[\"val1" + i + "\", \"val2" + i + "\"]}");
        filters.append("]");
        String aggregateTriggerData =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"filters\":" + filters + "},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{\"aggregatable_trigger_data\":"
                                        + aggregateTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(
                AsyncFetchStatus.EntityStatus.VALIDATION_ERROR, asyncFetchStatus.getEntityStatus());
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testBasicTriggerRequestWithAggregateTriggerData_tooManyNotFilterMaps()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder notFilters = new StringBuilder("[");
        int i;
        for (i = 0; i < MAX_FILTER_MAPS_PER_FILTER_SET; i++) {
            notFilters.append("{\"key-" + i + "\":[\"val1" + i + "\", \"val2" + i + "\"]},");
        }
        notFilters.append("{\"key-" + i + "\":[\"val1" + i + "\", \"val2" + i + "\"]}");
        notFilters.append("]");
        String aggregateTriggerData =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"not_filters\":" + notFilters + "},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{\"aggregatable_trigger_data\":"
                                        + aggregateTriggerData + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(
                AsyncFetchStatus.EntityStatus.VALIDATION_ERROR, asyncFetchStatus.getEntityStatus());
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testBasicTriggerRequestWithAggregateTriggerData_rejectsTooManyDataKeys()
            throws Exception {
        StringBuilder tooManyKeys = new StringBuilder("[");
        int i;
        for (i = 0; i < 51; i++) {
            tooManyKeys.append(
                    String.format(
                            "{\"key_piece\": \"0x15%1$s\",\"source_keys\":[\"campaign-%1$s\"]},",
                            i));
        }
        tooManyKeys.append(
                String.format(
                        "{\"key_piece\": \"0x15%1$s\",\"source_keys\":[\"campaign-%1$s\"]}", i));
        tooManyKeys.append("]");
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + tooManyKeys
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertEquals(
                AsyncFetchStatus.EntityStatus.VALIDATION_ERROR, asyncFetchStatus.getEntityStatus());
        assertFalse(fetch.isPresent());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequestWithAggregateValues() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String aggregatable_values = "{\"campaignCounts\":32768,\"geoValue\":1644}";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_values\": "
                                                + aggregatable_values
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(new JSONObject(aggregatable_values).toString(), result.getAggregateValues());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequestWithAggregateTriggerData_rejectsTooManyValueKeys()
            throws Exception {
        StringBuilder tooManyKeys = new StringBuilder("{");
        int i = 0;
        for (; i < 50; i++) {
            tooManyKeys.append(String.format("\"key-%s\": 12345,", i));
        }
        tooManyKeys.append(String.format("\"key-%s\": 12345}", i));
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"aggregatable_values\": " + tooManyKeys + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(
                AsyncFetchStatus.EntityStatus.VALIDATION_ERROR, asyncFetchStatus.getEntityStatus());
        assertFalse(fetch.isPresent());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void fetchTrigger_withReportingFilters_success() throws IOException, JSONException {
        // Setup
        String filters =
                "[{\n"
                        + "  \"key_1\": [\"value_1\", \"value_2\"],\n"
                        + "  \"key_2\": [\"value_1\", \"value_2\"]\n"
                        + "}]";
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"filters\": " + filters + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertEquals(new JSONArray(filters).toString(), result.getFilters());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void fetchWebTriggers_basic_success() throws IOException, JSONException {
        // Setup
        WebTriggerRegistrationRequest request =
                buildWebTriggerRegistrationRequest(
                        Arrays.asList(TRIGGER_REGISTRATION_1), TOP_ORIGIN);
        doReturn(mUrlConnection1).when(mFetcher).openUrl(new URL(REGISTRATION_URI_1.toString()));
        when(mUrlConnection1.getResponseCode()).thenReturn(200);
        when(mFlags.getWebContextClientAppAllowList()).thenReturn("");
        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{"
                                + "\"event_trigger_data\": "
                                + EVENT_TRIGGERS_1
                                + ", \"debug_key\": \""
                                + DEBUG_KEY
                                + "\""
                                + "}"));
        when(mUrlConnection1.getHeaderFields()).thenReturn(headersRequest);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        webTriggerRegistrationRequest(request, true),
                        asyncFetchStatus,
                        asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        verify(mUrlConnection1).setRequestMethod("POST");
    }

    @Test
    public void fetchWebTriggers_withExtendedHeaders_success() throws IOException, JSONException {
        // Setup
        WebTriggerRegistrationRequest request =
                buildWebTriggerRegistrationRequest(
                        Collections.singletonList(TRIGGER_REGISTRATION_1), TOP_ORIGIN);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(REGISTRATION_URI_1.toString()));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mFlags.getWebContextClientAppAllowList()).thenReturn("");
        String aggregatableValues = "{\"campaignCounts\":32768,\"geoValue\":1644}";
        String filters =
                "[{\n"
                        + "  \"key_1\": [\"value_1\", \"value_2\"],\n"
                        + "  \"key_2\": [\"value_1\", \"value_2\"]\n"
                        + "}]";
        String aggregatableTriggerData =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"filters\":"
                        + "[{\"conversion_subdomain\":[\"electronics.megastore\"]}],"
                        + "\"not_filters\":[{\"product\":[\"1\"]}]},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"event_trigger_data\": "
                                                + EVENT_TRIGGERS_1
                                                + ", \"filters\": "
                                                + filters
                                                + ", \"aggregatable_values\": "
                                                + aggregatableValues
                                                + ", \"aggregatable_trigger_data\": "
                                                + aggregatableTriggerData
                                                + "}")));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        webTriggerRegistrationRequest(request, true),
                        asyncFetchStatus,
                        asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(new JSONArray(aggregatableTriggerData).toString(),
                result.getAggregateTriggerData());
        assertEquals(new JSONObject(aggregatableValues).toString(), result.getAggregateValues());
        assertEquals(new JSONArray(filters).toString(), result.getFilters());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void fetchWebTriggers_withRedirects_ignoresRedirects()
            throws IOException, JSONException {
        // Setup
        WebTriggerRegistrationRequest request =
                buildWebTriggerRegistrationRequest(
                        Collections.singletonList(TRIGGER_REGISTRATION_1), TOP_ORIGIN);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(REGISTRATION_URI_1.toString()));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mFlags.getWebContextClientAppAllowList()).thenReturn("");
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\": " + EVENT_TRIGGERS_1 + "}"),
                                "Attribution-Reporting-Redirect",
                                List.of(LIST_TYPE_REDIRECT_URI),
                                "Location",
                                List.of(LOCATION_TYPE_REDIRECT_URI)));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        webTriggerRegistrationRequest(request, true),
                        asyncFetchStatus,
                        asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(0, asyncRedirect.getRedirects().size());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(REGISTRATION_URI_1, result.getRegistrationOrigin());
        verify(mUrlConnection).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_invalidJson() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0x400\",\"source_keys\":\"campaignCounts\"],"
                        + "\"filters\":"
                        + "{\"conversion_subdomain\":[\"electronics.megastore\"]},"
                        + "\"not_filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch = mFetcher.fetchTrigger(
                appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_tooManyEntries() throws Exception {
        StringBuilder tooManyEntries = new StringBuilder("[");
        for (int i = 0; i < MAX_AGGREGATABLE_TRIGGER_DATA + 1; i++) {
            tooManyEntries.append(
                    String.format(
                            "{\"key_piece\": \"0x15%1$s\",\"source_keys\":[\"campaign-%1$s\"]}",
                            i));
        }
        tooManyEntries.append("]");
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + tooManyEntries
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_invalidKeyPiece_missingPrefix()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"filters\":"
                        + "{\"conversion_subdomain\":[\"electronics.megastore\"]},"
                        + "\"not_filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_invalidKeyPiece_tooLong()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"filters\":"
                        + "{\"conversion_subdomain\":[\"electronics.megastore\"]},"
                        + "\"not_filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\""
                        + LONG_AGGREGATE_KEY_PIECE
                        + "\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_sourceKeys_notAnArray()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0x400\",\"source_keys\":{\"campaignCounts\": true},"
                        + "\"filters\":"
                        + "{\"conversion_subdomain\":[\"electronics.megastore\"]},"
                        + "\"not_filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_sourceKeys_tooManyKeys()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder tooManyKeys = new StringBuilder("[");
        tooManyKeys.append(
                IntStream.range(0, MAX_AGGREGATE_KEYS_PER_REGISTRATION + 1)
                        .mapToObj(i -> "aggregate-key-" + i)
                        .collect(Collectors.joining(",")));
        tooManyKeys.append("]");
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0x400\",\"source_keys\": "
                        + tooManyKeys
                        + ","
                        + "\"filters\":"
                        + "{\"conversion_subdomain\":[\"electronics.megastore\"]},"
                        + "\"not_filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_sourceKeys_invalidKeyId()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\", \""
                        + LONG_AGGREGATE_KEY_ID
                        + "\"],"
                        + "\"filters\":"
                        + "{\"conversion_subdomain\":[\"electronics.megastore\"]},"
                        + "\"not_filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_filters_tooManyFilters()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder filters = new StringBuilder("{");
        filters.append(
                IntStream.range(0, MAX_ATTRIBUTION_FILTERS + 1)
                        .mapToObj(i -> "\"filter-string-" + i + "\": [\"filter-value\"]")
                        .collect(Collectors.joining(",")));
        filters.append("}");
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"filters\": "
                        + filters
                        + ","
                        + "\"not_filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_filters_keyTooLong() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String filters =
                "{\"product\":[\"1234\",\"2345\"], \"" + LONG_FILTER_STRING + "\":[\"id\"]}";
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"filters\": "
                        + filters
                        + ","
                        + "\"not_filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_filters_tooManyValues()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder filters =
                new StringBuilder(
                        "{"
                                + "\"filter-string-1\": [\"filter-value-1\"],"
                                + "\"filter-string-2\": [");
        filters.append(
                IntStream.range(0, MAX_VALUES_PER_ATTRIBUTION_FILTER + 1)
                        .mapToObj(i -> "\"filter-value-" + i + "\"")
                        .collect(Collectors.joining(",")));
        filters.append("]}");
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"filters\": "
                        + filters
                        + ","
                        + "\"not_filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_filters_valueTooLong() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String filters =
                "{\"product\":[\"1234\",\"" + LONG_FILTER_STRING + "\"], \"ctid\":[\"id\"]}";
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"filters\": "
                        + filters
                        + ","
                        + "\"not_filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_notFilters_tooManyFilters()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder filters = new StringBuilder("{");
        filters.append(
                IntStream.range(0, MAX_ATTRIBUTION_FILTERS + 1)
                        .mapToObj(i -> "\"filter-string-" + i + "\": [\"filter-value\"]")
                        .collect(Collectors.joining(",")));
        filters.append("}");
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"not_filters\": "
                        + filters
                        + ","
                        + "\"filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_notFilters_keyTooLong()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String filters =
                "{\"product\":[\"1234\",\"2345\"], \"" + LONG_FILTER_STRING + "\":[\"id\"]}";
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"not_filters\": "
                        + filters
                        + ","
                        + "\"filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_notFilters_tooManyValues()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        StringBuilder filters =
                new StringBuilder(
                        "{"
                                + "\"filter-string-1\": [\"filter-value-1\"],"
                                + "\"filter-string-2\": [");
        filters.append(
                IntStream.range(0, MAX_VALUES_PER_ATTRIBUTION_FILTER + 1)
                        .mapToObj(i -> "\"filter-value-" + i + "\"")
                        .collect(Collectors.joining(",")));
        filters.append("]}");
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"not_filters\": "
                        + filters
                        + ","
                        + "\"filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregateTriggerData_notFilters_valueTooLong()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String filters =
                "{\"product\":[\"1234\",\"" + LONG_FILTER_STRING + "\"], \"ctid\":[\"id\"]}";
        String aggregatable_trigger_data =
                "[{\"key_piece\":\"0x400\",\"source_keys\":[\"campaignCounts\"],"
                        + "\"not_filters\": "
                        + filters
                        + ","
                        + "\"filters\":{\"product\":[\"1\"]}},"
                        + "{\"key_piece\":\"0xA80\",\"source_keys\":[\"geoValue\"]}]";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_trigger_data\": "
                                                + aggregatable_trigger_data
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testBasicTriggerRequestWithDebugReportingHeader() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);

        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{\"event_trigger_data\":"
                                + EVENT_TRIGGERS_1
                                + ",\"debug_reporting\":\"true\"}"));

        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertTrue(result.isDebugReporting());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequestWithInvalidDebugReportingHeader() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);

        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{\"event_trigger_data\":"
                                + EVENT_TRIGGERS_1
                                + ",\"debug_reporting\":\"invalid\"}"));

        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertFalse(result.isDebugReporting());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequestWithNullDebugReportingHeader() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);

        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{\"event_trigger_data\":"
                                + EVENT_TRIGGERS_1
                                + ",\"debug_reporting\":\"null\"}"));

        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertFalse(result.isDebugReporting());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequestWithNoQuotesDebugReportingHeader() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);

        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{\"event_trigger_data\":"
                                + EVENT_TRIGGERS_1
                                + ",\"debug_reporting\":invalid}"));

        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertFalse(result.isDebugReporting());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequestWithEmptyDebugReportingHeader() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);

        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of("{\"event_trigger_data\":" + EVENT_TRIGGERS_1 + "}"));

        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertFalse(result.isDebugReporting());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testBasicTriggerRequestWithAggregatableValues() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String aggregatable_values = "{\"campaignCounts\":32768,\"geoValue\":1644}";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_values\": "
                                                + aggregatable_values
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONObject(aggregatable_values).toString(), result.getAggregateValues());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void testTriggerRequestWithAggregatableValues_invalidJson() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String aggregatable_values = "{\"campaignCounts\":32768\"geoValue\":1644}";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_values\": "
                                                + aggregatable_values
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregatableValues_tooManyKeys() throws Exception {
        StringBuilder tooManyKeys = new StringBuilder("{");
        tooManyKeys.append(
                IntStream.range(0, MAX_AGGREGATE_KEYS_PER_REGISTRATION + 1)
                        .mapToObj(i -> String.format("\"key-%s\": 12345,", i))
                        .collect(Collectors.joining(",")));
        tooManyKeys.append("}");
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"aggregatable_values\": " + tooManyKeys + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void testTriggerRequestWithAggregatableValues_invalidKeyId() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        String aggregatable_values =
                "{\"campaignCounts\":32768, \"" + LONG_AGGREGATE_KEY_ID + "\":1644}";
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"aggregatable_values\": "
                                                + aggregatable_values
                                                + "}")));
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        appTriggerRegistrationRequest(request), asyncFetchStatus, asyncRedirect);
        assertFalse(fetch.isPresent());
        verify(mUrlConnection, times(1)).setRequestMethod("POST");
        verify(mFetcher, times(1)).openUrl(any());
    }

    @Test
    public void basicTriggerRequest_headersMoreThanMaxResponseSize_emitsMetricsWithAdTechDomain()
            throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        MeasurementRegistrationResponseStats expectedStats =
                new MeasurementRegistrationResponseStats.Builder(
                                AD_SERVICES_MEASUREMENT_REGISTRATIONS,
                                AD_SERVICES_MEASUREMENT_REGISTRATIONS__TYPE__TRIGGER,
                                223,
                                UNKNOWN_SOURCE_TYPE,
                                APP_REGISTRATION_SURFACE_TYPE,
                                SUCCESS_STATUS,
                                UNKNOWN_REGISTRATION_FAILURE_TYPE,
                                0)
                        .setAdTechDomain(WebUtil.validUrl("https://foo.test"))
                        .build();
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of("{" + "\"event_trigger_data\":" + EVENT_TRIGGERS_1 + "}")));
        doReturn(5L).when(mFlags).getMaxResponseBasedRegistrationPayloadSizeBytes();
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        asyncFetchStatus.setRegistrationDelay(0L);
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection).setRequestMethod("POST");
        FetcherUtil.emitHeaderMetrics(mFlags, mLogger, asyncRegistration, asyncFetchStatus);
        verify(mLogger).logMeasurementRegistrationsResponseSize(eq(expectedStats));
    }

    @Test
    public void triggerRequest_appRegWithValidAttributionConfig_parsesCorrectly() throws Exception {
        // Setup
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        String originalAttributionConfigString =
                "[{\n"
                        + "\"source_network\": \"AdTech1-Ads\",\n"
                        + "\"source_priority_range\": {\n"
                        + "\"start\": 100,\n"
                        + "\"end\": 1000\n"
                        + "},\n"
                        + "\"source_filters\": {\n"
                        + "\"campaign_type\": [\"install\"]"
                        + "},\n"
                        + "\"source_not_filters\": {\n"
                        + "\"product\": [\"prod1\"]"
                        + "},\n"
                        + "\"priority\": \"99\",\n"
                        + "\"expiry\": \"604800\",\n"
                        + "\"source_expiry_override\": \"1209600\",\n"
                        + "\"post_install_exclusivity_window\": \"5000\",\n"
                        + "\"filter_data\": {\n"
                        + "\"campaign_type\": [\"install\"]\n"
                        + "}\n"
                        + "},"
                        + "{\n"
                        + "\"source_network\": \"AdTech2-Ads\"}"
                        + "]";
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ", \"attribution_config\":"
                                                + originalAttributionConfigString
                                                + "}")));
        doReturn(5000L).when(mFlags).getMaxResponseBasedRegistrationPayloadSizeBytes();
        List<FilterMap> sourceFilters =
                Collections.singletonList(
                        new FilterMap.Builder()
                                .setAttributionFilterMap(
                                        Map.of(
                                                "campaign_type",
                                                Collections.singletonList("install")))
                                .build());
        List<FilterMap> sourceNotFilters =
                Collections.singletonList(
                        new FilterMap.Builder()
                                .setAttributionFilterMap(
                                        Map.of("product", Collections.singletonList("prod1")))
                                .build());
        List<FilterMap> filterData =
                Collections.singletonList(
                        new FilterMap.Builder()
                                .setAttributionFilterMap(
                                        Map.of(
                                                "campaign_type",
                                                Collections.singletonList("install")))
                                .build());
        AttributionConfig attributionConfig1 =
                new AttributionConfig.Builder()
                        .setSourceAdtech("AdTech1-Ads")
                        .setSourcePriorityRange(new Pair<>(100L, 1000L))
                        .setSourceFilters(sourceFilters)
                        .setSourceNotFilters(sourceNotFilters)
                        .setPriority(99L)
                        .setExpiry(604800L)
                        .setSourceExpiryOverride(1209600L)
                        .setPostInstallExclusivityWindow(5000L)
                        .setFilterData(filterData)
                        .build();
        AttributionConfig attributionConfig2 =
                new AttributionConfig.Builder().setSourceAdtech("AdTech2-Ads").build();

        JSONArray expectedAttributionConfigJsonArray =
                new JSONArray(
                        Arrays.asList(
                                attributionConfig1.serializeAsJson(),
                                attributionConfig2.serializeAsJson()));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);

        when(mFlags.getMeasurementEnableXNA()).thenReturn(true);

        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);

        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertEquals(expectedAttributionConfigJsonArray.toString(), result.getAttributionConfig());
    }

    @Test
    public void triggerRequest_allowListedWebRegWithValidAttributionConfig_parsesCorrectly()
            throws Exception {
        // Setup
        WebTriggerRegistrationRequest request =
                buildWebTriggerRegistrationRequest(List.of(TRIGGER_REGISTRATION_1), TOP_ORIGIN);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mFlags.getWebContextClientAppAllowList())
                .thenReturn(CONTEXT.getPackageName() + ",some_other_package");
        String originalAttributionConfigString =
                "[{\n"
                        + "\"source_network\": \"AdTech1-Ads\",\n"
                        + "\"source_priority_range\": {\n"
                        + "\"start\": 100,\n"
                        + "\"end\": 1000\n"
                        + "},\n"
                        + "\"source_filters\": {\n"
                        + "\"campaign_type\": [\"install\"]"
                        + "},\n"
                        + "\"source_not_filters\": {\n"
                        + "\"product\": [\"prod1\"]"
                        + "},\n"
                        + "\"priority\": \"99\",\n"
                        + "\"expiry\": \"604800\",\n"
                        + "\"source_expiry_override\": \"1209600\",\n"
                        + "\"post_install_exclusivity_window\": \"5000\",\n"
                        + "\"filter_data\": {\n"
                        + "\"campaign_type\": [\"install\"]\n"
                        + "}\n"
                        + "},"
                        + "{\n"
                        + "\"source_network\": \"AdTech2-Ads\"}"
                        + "]";
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ", \"attribution_config\":"
                                                + originalAttributionConfigString
                                                + "}")));
        doReturn(5000L).when(mFlags).getMaxResponseBasedRegistrationPayloadSizeBytes();
        List<FilterMap> sourceFilters =
                Collections.singletonList(
                        new FilterMap.Builder()
                                .setAttributionFilterMap(
                                        Map.of(
                                                "campaign_type",
                                                Collections.singletonList("install")))
                                .build());
        List<FilterMap> sourceNotFilters =
                Collections.singletonList(
                        new FilterMap.Builder()
                                .setAttributionFilterMap(
                                        Map.of("product", Collections.singletonList("prod1")))
                                .build());
        List<FilterMap> filterData =
                Collections.singletonList(
                        new FilterMap.Builder()
                                .setAttributionFilterMap(
                                        Map.of(
                                                "campaign_type",
                                                Collections.singletonList("install")))
                                .build());
        AttributionConfig attributionConfig1 =
                new AttributionConfig.Builder()
                        .setSourceAdtech("AdTech1-Ads")
                        .setSourcePriorityRange(new Pair<>(100L, 1000L))
                        .setSourceFilters(sourceFilters)
                        .setSourceNotFilters(sourceNotFilters)
                        .setPriority(99L)
                        .setExpiry(604800L)
                        .setSourceExpiryOverride(1209600L)
                        .setPostInstallExclusivityWindow(5000L)
                        .setFilterData(filterData)
                        .build();
        AttributionConfig attributionConfig2 =
                new AttributionConfig.Builder().setSourceAdtech("AdTech2-Ads").build();

        JSONArray expectedAttributionConfigJsonArray =
                new JSONArray(
                        Arrays.asList(
                                attributionConfig1.serializeAsJson(),
                                attributionConfig2.serializeAsJson()));

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = webTriggerRegistrationRequest(request, true);

        when(mFlags.getMeasurementEnableXNA()).thenReturn(true);

        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);

        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertEquals(expectedAttributionConfigJsonArray.toString(), result.getAttributionConfig());
    }

    @Test
    public void triggerRequest_disallowListedWebRegWithValidAttributionConfig_doesntParse()
            throws Exception {
        // Setup
        WebTriggerRegistrationRequest request =
                buildWebTriggerRegistrationRequest(List.of(TRIGGER_REGISTRATION_1), TOP_ORIGIN);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        // Doesn't have the app package
        when(mFlags.getWebContextClientAppAllowList()).thenReturn("some_other_package");
        String originalAttributionConfigString =
                "[{\n"
                        + "\"source_network\": \"AdTech1-Ads\",\n"
                        + "\"source_priority_range\": {\n"
                        + "\"start\": 100,\n"
                        + "\"end\": 1000\n"
                        + "},\n"
                        + "\"source_filters\": {\n"
                        + "\"campaign_type\": [\"install\"]"
                        + "},\n"
                        + "\"source_not_filters\": {\n"
                        + "\"product\": [\"prod1\"]"
                        + "},\n"
                        + "\"priority\": \"99\",\n"
                        + "\"expiry\": \"604800\",\n"
                        + "\"source_expiry_override\": \"680\",\n"
                        + "\"post_install_exclusivity_window\": \"5000\",\n"
                        + "\"filter_data\": {\n"
                        + "\"campaign_type\": [\"install\"]\n"
                        + "}\n"
                        + "},"
                        + "{\n"
                        + "\"source_network\": \"AdTech2-Ads\"}"
                        + "]";
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ", \"attribution_config\":"
                                                + originalAttributionConfigString
                                                + "}")));
        doReturn(5000L).when(mFlags).getMaxResponseBasedRegistrationPayloadSizeBytes();

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = webTriggerRegistrationRequest(request, true);

        when(mFlags.getMeasurementEnableXNA()).thenReturn(true);

        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);

        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertNull(result.getAttributionConfig());
    }

    @Test
    public void triggerRequest_attributionConfigThrowingJsonException_dropsTheTriggerCompletely()
            throws Exception {
        // Setup
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ", \"attribution_config\": INVALID_JSON_ARRAY"
                                                + "}")));
        doReturn(5000L).when(mFlags).getMaxResponseBasedRegistrationPayloadSizeBytes();

        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);

        when(mFlags.getMeasurementEnableXNA()).thenReturn(true);

        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, new AsyncRedirect());

        // Assertion
        assertEquals(
                AsyncFetchStatus.EntityStatus.PARSING_ERROR, asyncFetchStatus.getEntityStatus());
        assertFalse(fetch.isPresent());
    }

    @Test
    public void triggerRequest_withValidAdtechBitMapping_storesCorrectly() throws Exception {
        // Setup
        String validAdTechBitMapping =
                "{" + "\"Google-Ads\":\"0x1\"," + "\"Facebook-Ads\":\"0x2\"" + "}";
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ", \"x_network_key_mapping\":"
                                                + validAdTechBitMapping
                                                + "}")));
        doReturn(5000L).when(mFlags).getMaxResponseBasedRegistrationPayloadSizeBytes();

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);

        when(mFlags.getMeasurementEnableXNA()).thenReturn(true);

        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);

        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertEquals(validAdTechBitMapping, result.getAdtechKeyMapping());
    }

    @Test
    public void triggerRequest_withInvalidAdtechBitMapping_dropsBitMapping() throws Exception {
        // Setup
        String invalidAdTechBitMapping =
                "{"
                        // Values don't start with 0x -- invalid
                        + "\"Google-Ads\": 1234,"
                        + "\"Facebook-Ads\": 2"
                        + "}";
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ", \"x_network_key_mapping\":"
                                                + invalidAdTechBitMapping
                                                + "}")));
        doReturn(5000L).when(mFlags).getMaxResponseBasedRegistrationPayloadSizeBytes();

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);

        when(mFlags.getMeasurementEnableXNA()).thenReturn(true);

        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);

        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertNull(result.getAdtechKeyMapping());
    }

    @Test
    public void triggerRequest_xnaDisabled_nullXNAFields() throws Exception {
        // Setup
        String validAdTechBitMapping =
                "{" + "\"Google-Ads\":\"0x1\"," + "\"Facebook-Ads\":\"0x2\"" + "}";
        String validAttributionConfig =
                "[{\n"
                        + "\"source_network\": \"AdTech1-Ads\",\n"
                        + "\"source_priority_range\": {\n"
                        + "\"start\": 100,\n"
                        + "\"end\": 1000\n"
                        + "},\n"
                        + "\"source_filters\": {\n"
                        + "\"campaign_type\": [\"install\"]"
                        + "},\n"
                        + "\"source_not_filters\": {\n"
                        + "\"product\": [\"prod1\"]"
                        + "},\n"
                        + "\"priority\": \"99\",\n"
                        + "\"expiry\": \"604800\",\n"
                        + "\"source_expiry_override\": \"680\",\n"
                        + "\"post_install_exclusivity_window\": \"5000\",\n"
                        + "\"filter_data\": {\n"
                        + "\"campaign_type\": [\"install\"]\n"
                        + "}\n"
                        + "},"
                        + "{\n"
                        + "\"source_network\": \"AdTech2-Ads\"}"
                        + "]";
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        when(mUrlConnection.getHeaderFields())
                .thenReturn(
                        Map.of(
                                "Attribution-Reporting-Register-Trigger",
                                List.of(
                                        "{"
                                                + "\"event_trigger_data\":"
                                                + EVENT_TRIGGERS_1
                                                + ", \"adtech_bit_mapping\":"
                                                + validAdTechBitMapping
                                                + ", \"attribution_config\":"
                                                + validAttributionConfig
                                                + "}")));
        doReturn(5000L).when(mFlags).getMaxResponseBasedRegistrationPayloadSizeBytes();

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);

        when(mFlags.getMeasurementEnableXNA()).thenReturn(false);

        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);

        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertNull(result.getAdtechKeyMapping());
        assertNull(result.getAttributionConfig());
    }

    @Test
    public void fetchWebTriggers_withDebugJoinKey_getsParsed() throws IOException, JSONException {
        // Setup
        WebTriggerRegistrationRequest request =
                buildWebTriggerRegistrationRequest(
                        Arrays.asList(TRIGGER_REGISTRATION_1), TOP_ORIGIN);
        doReturn(mUrlConnection1).when(mFetcher).openUrl(new URL(REGISTRATION_URI_1.toString()));
        when(mUrlConnection1.getResponseCode()).thenReturn(200);
        when(mFlags.getWebContextClientAppAllowList()).thenReturn("");
        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{"
                                + "\"event_trigger_data\": "
                                + EVENT_TRIGGERS_1
                                + ", \"debug_key\": \""
                                + DEBUG_KEY
                                + "\""
                                + ", \"debug_join_key\": \""
                                + DEBUG_JOIN_KEY
                                + "\""
                                + "}"));
        when(mUrlConnection1.getHeaderFields()).thenReturn(headersRequest);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        webTriggerRegistrationRequest(request, true),
                        asyncFetchStatus,
                        asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(REGISTRATION_URI_1, result.getRegistrationOrigin());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(DEBUG_JOIN_KEY, result.getDebugJoinKey());
        verify(mUrlConnection1).setRequestMethod("POST");
    }

    @Test
    public void fetchWebTriggers_withDebugJoinKeyEnrollmentNotAllowlisted_joinKeyDropped()
            throws IOException, JSONException {
        // Setup
        when(mFlags.getMeasurementDebugJoinKeyEnrollmentAllowlist())
                .thenReturn("some_random_enrollment1,some_random_enrollment2");
        WebTriggerRegistrationRequest request =
                buildWebTriggerRegistrationRequest(
                        Arrays.asList(TRIGGER_REGISTRATION_1), TOP_ORIGIN);
        doReturn(mUrlConnection1).when(mFetcher).openUrl(new URL(REGISTRATION_URI_1.toString()));
        when(mUrlConnection1.getResponseCode()).thenReturn(200);
        when(mFlags.getWebContextClientAppAllowList()).thenReturn("");
        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{"
                                + "\"event_trigger_data\": "
                                + EVENT_TRIGGERS_1
                                + ", \"debug_key\": \""
                                + DEBUG_KEY
                                + "\""
                                + ", \"debug_join_key\": \""
                                + DEBUG_JOIN_KEY
                                + "\""
                                + "}"));
        when(mUrlConnection1.getHeaderFields()).thenReturn(headersRequest);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        webTriggerRegistrationRequest(request, true),
                        asyncFetchStatus,
                        asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(REGISTRATION_URI_1, result.getRegistrationOrigin());
        assertNull(result.getDebugJoinKey());
        verify(mUrlConnection1).setRequestMethod("POST");
    }

    @Test
    public void fetchTrigger_basicWithDebugJoinKey_getsParsed() throws Exception {
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);

        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{\"event_trigger_data\":"
                                + EVENT_TRIGGERS_1
                                + ",\"debug_key\":\""
                                + DEBUG_KEY
                                + "\" ,\"debug_join_key\":\""
                                + DEBUG_JOIN_KEY
                                + "\"}"));

        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        assertEquals(DEBUG_KEY, result.getDebugKey());
        assertEquals(DEBUG_JOIN_KEY, result.getDebugJoinKey());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void fetchTrigger_basicWithDebugJoinKeyEnrollmentNotInAllowlist_joinKeyDropped()
            throws Exception {
        when(mFlags.getMeasurementDebugJoinKeyEnrollmentAllowlist()).thenReturn("");
        RegistrationRequest request = buildRequest(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);

        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{\"event_trigger_data\":"
                                + EVENT_TRIGGERS_1
                                + ",\"debug_key\":\""
                                + DEBUG_KEY
                                + "\" ,\"debug_join_key\":\""
                                + DEBUG_JOIN_KEY
                                + "\"}"));

        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(DEBUG_KEY, result.getDebugKey());
        assertNull(result.getDebugJoinKey());
        assertEquals(TRIGGER_URI, result.getRegistrationOrigin().toString());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void fetchTrigger_setsRegistrationOriginWithoutPath_forRegistrationURIWithPath()
            throws Exception {
        String uri = WebUtil.validUrl("https://test1.example.test/path1");
        RegistrationRequest request = buildRequest(uri);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(uri));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{"
                                + "\"event_trigger_data\": "
                                + EVENT_TRIGGERS_1
                                + ", \"debug_key\": \""
                                + DEBUG_KEY
                                + "\""
                                + "}"));
        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(
                WebUtil.validUrl("https://test1.example.test"),
                result.getRegistrationOrigin().toString());
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(DEBUG_KEY, result.getDebugKey());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void fetchTrigger_setsRegistrationOriginWithPort_forRegistrationURIWithPort()
            throws Exception {
        String uri = WebUtil.validUrl("https://test1.example.test:8081");
        RegistrationRequest request = buildRequest(uri);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(uri));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{"
                                + "\"event_trigger_data\": "
                                + EVENT_TRIGGERS_1
                                + ", \"debug_key\": \""
                                + DEBUG_KEY
                                + "\""
                                + "}"));
        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(
                WebUtil.validUrl("https://test1.example.test:8081"),
                result.getRegistrationOrigin().toString());
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(DEBUG_KEY, result.getDebugKey());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    @Test
    public void fetchTrigger_appRegistrationWithAdId_encodedAdIdAddedToTrigger() throws Exception {
        RegistrationRequest request = buildRequestWithAdId(TRIGGER_URI);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(TRIGGER_URI));
        when(mUrlConnection.getResponseCode()).thenReturn(200);

        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{\"event_trigger_data\":"
                                + EVENT_TRIGGERS_1
                                + ",\"debug_key\":\""
                                + DEBUG_KEY
                                + "\"}"));

        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);

        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequestWithAdId(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(DEBUG_KEY, result.getDebugKey());
        verify(mUrlConnection).setRequestMethod("POST");

        String expectedAdIdHash =
                AdIdEncryption.encryptAdIdAndEnrollmentSha256(PLATFORM_AD_ID_VALUE, ENROLLMENT_ID);
        assertEquals(expectedAdIdHash, result.getPlatformAdId());
    }

    @Test
    public void fetchWebTrigger_withDebugAdIdValue_getsParsed() throws IOException, JSONException {
        // Setup
        WebTriggerRegistrationRequest request =
                buildWebTriggerRegistrationRequest(
                        Arrays.asList(TRIGGER_REGISTRATION_1), TOP_ORIGIN);
        doReturn(mUrlConnection1).when(mFetcher).openUrl(new URL(REGISTRATION_URI_1.toString()));
        when(mUrlConnection1.getResponseCode()).thenReturn(200);
        when(mFlags.getWebContextClientAppAllowList()).thenReturn("");
        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{"
                                + "\"event_trigger_data\": "
                                + EVENT_TRIGGERS_1
                                + ", \"debug_key\": \""
                                + DEBUG_KEY
                                + "\""
                                + ", \"debug_ad_id\": \""
                                + DEBUG_AD_ID_VALUE
                                + "\""
                                + "}"));
        when(mUrlConnection1.getHeaderFields()).thenReturn(headersRequest);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        webTriggerRegistrationRequest(request, true),
                        asyncFetchStatus,
                        asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(DEBUG_AD_ID_VALUE, result.getDebugAdId());
        verify(mUrlConnection1).setRequestMethod("POST");
    }

    @Test
    public void fetchWebTrigger_withDebugAdIdValue_enrollmentBlockListed_doesNotGetParsed()
            throws IOException, JSONException {
        // Setup
        when(mFlags.getMeasurementPlatformDebugAdIdMatchingEnrollmentBlocklist())
                .thenReturn(ENROLLMENT_ID);
        WebTriggerRegistrationRequest request =
                buildWebTriggerRegistrationRequest(
                        Arrays.asList(TRIGGER_REGISTRATION_1), TOP_ORIGIN);
        doReturn(mUrlConnection1).when(mFetcher).openUrl(new URL(REGISTRATION_URI_1.toString()));
        when(mUrlConnection1.getResponseCode()).thenReturn(200);
        when(mFlags.getWebContextClientAppAllowList()).thenReturn("");
        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{"
                                + "\"event_trigger_data\": "
                                + EVENT_TRIGGERS_1
                                + ", \"debug_key\": \""
                                + DEBUG_KEY
                                + "\""
                                + ", \"debug_ad_id\": \""
                                + DEBUG_AD_ID_VALUE
                                + "\""
                                + "}"));
        when(mUrlConnection1.getHeaderFields()).thenReturn(headersRequest);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        webTriggerRegistrationRequest(request, true),
                        asyncFetchStatus,
                        asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertNull(result.getDebugAdId());
        verify(mUrlConnection1).setRequestMethod("POST");
    }

    @Test
    public void fetchWebTrigger_withDebugAdIdValue_blockListMatchesAll_doesNotGetParsed()
            throws IOException, JSONException {
        // Setup
        when(mFlags.getMeasurementPlatformDebugAdIdMatchingEnrollmentBlocklist()).thenReturn("*");
        WebTriggerRegistrationRequest request =
                buildWebTriggerRegistrationRequest(
                        Arrays.asList(TRIGGER_REGISTRATION_1), TOP_ORIGIN);
        doReturn(mUrlConnection1).when(mFetcher).openUrl(new URL(REGISTRATION_URI_1.toString()));
        when(mUrlConnection1.getResponseCode()).thenReturn(200);
        when(mFlags.getWebContextClientAppAllowList()).thenReturn("");
        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{"
                                + "\"event_trigger_data\": "
                                + EVENT_TRIGGERS_1
                                + ", \"debug_key\": \""
                                + DEBUG_KEY
                                + "\""
                                + ", \"debug_ad_id\": \""
                                + DEBUG_AD_ID_VALUE
                                + "\""
                                + "}"));
        when(mUrlConnection1.getHeaderFields()).thenReturn(headersRequest);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(
                        webTriggerRegistrationRequest(request, true),
                        asyncFetchStatus,
                        asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertNull(result.getDebugAdId());
        verify(mUrlConnection1).setRequestMethod("POST");
    }

    @Test
    public void fetchTrigger_setsFakeEnrollmentId_whenDisableEnrollmentFlagIsTrue()
            throws Exception {
        String uri = WebUtil.validUrl("https://test1.example.test:8081");
        RegistrationRequest request = buildRequest(uri);
        doReturn(mUrlConnection).when(mFetcher).openUrl(new URL(uri));
        when(mUrlConnection.getResponseCode()).thenReturn(200);
        doReturn(true).when(mFlags).isDisableMeasurementEnrollmentCheck();
        Map<String, List<String>> headersRequest = new HashMap<>();
        headersRequest.put(
                "Attribution-Reporting-Register-Trigger",
                List.of(
                        "{"
                                + "\"event_trigger_data\": "
                                + EVENT_TRIGGERS_1
                                + ", \"debug_key\": \""
                                + DEBUG_KEY
                                + "\""
                                + "}"));
        when(mUrlConnection.getHeaderFields()).thenReturn(headersRequest);
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRegistration asyncRegistration = appTriggerRegistrationRequest(request);
        // Execution
        Optional<Trigger> fetch =
                mFetcher.fetchTrigger(asyncRegistration, asyncFetchStatus, asyncRedirect);
        // Assertion
        assertEquals(AsyncFetchStatus.ResponseStatus.SUCCESS, asyncFetchStatus.getResponseStatus());
        assertTrue(fetch.isPresent());
        Trigger result = fetch.get();
        assertEquals(
                WebUtil.validUrl("https://test1.example.test:8081"),
                result.getRegistrationOrigin().toString());
        assertEquals(Enrollment.FAKE_ENROLLMENT, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(DEBUG_KEY, result.getDebugKey());
        verify(mUrlConnection).setRequestMethod("POST");
    }

    private RegistrationRequest buildRequest(String triggerUri) {
        return new RegistrationRequest.Builder(
                        RegistrationRequest.REGISTER_TRIGGER,
                        Uri.parse(triggerUri),
                        CONTEXT.getAttributionSource().getPackageName(),
                        SDK_PACKAGE_NAME)
                .build();
    }

    private RegistrationRequest buildRequestWithAdId(String triggerUri) {
        return new RegistrationRequest.Builder(
                        RegistrationRequest.REGISTER_TRIGGER,
                        Uri.parse(triggerUri),
                        CONTEXT.getAttributionSource().getPackageName(),
                        SDK_PACKAGE_NAME)
                .setAdIdPermissionGranted(true)
                .setAdIdValue(PLATFORM_AD_ID_VALUE)
                .build();
    }

    private WebTriggerRegistrationRequest buildWebTriggerRegistrationRequest(
            List<WebTriggerParams> triggerParams, String topOrigin) {
        return new WebTriggerRegistrationRequest.Builder(triggerParams, Uri.parse(topOrigin))
                .build();
    }

    private static AsyncRegistration appTriggerRegistrationRequest(
            RegistrationRequest registrationRequest) {
        // Necessary for testing
        String enrollmentId = "";
        if (EnrollmentDao.getInstance(CONTEXT)
                        .getEnrollmentDataFromMeasurementUrl(
                                registrationRequest
                                        .getRegistrationUri()
                                        .buildUpon()
                                        .clearQuery()
                                        .build())
                != null) {
            enrollmentId =
                    EnrollmentDao.getInstance(CONTEXT)
                            .getEnrollmentDataFromMeasurementUrl(
                                    registrationRequest
                                            .getRegistrationUri()
                                            .buildUpon()
                                            .clearQuery()
                                            .build())
                            .getEnrollmentId();
        }
        return createAsyncRegistration(
                UUID.randomUUID().toString(),
                registrationRequest.getRegistrationUri(),
                null,
                null,
                Uri.parse(ANDROID_APP_SCHEME_URI_PREFIX + CONTEXT.getPackageName()),
                null,
                Uri.parse(ANDROID_APP_SCHEME_URI_PREFIX + CONTEXT.getPackageName()),
                registrationRequest.getRegistrationType() == RegistrationRequest.REGISTER_SOURCE
                        ? AsyncRegistration.RegistrationType.APP_SOURCE
                        : AsyncRegistration.RegistrationType.APP_TRIGGER,
                null,
                System.currentTimeMillis(),
                0,
                false,
                false,
                null);
    }

    private static AsyncRegistration appTriggerRegistrationRequestWithAdId(
            RegistrationRequest registrationRequest) {
        // Necessary for testing
        String enrollmentId = "";
        if (EnrollmentDao.getInstance(CONTEXT)
                        .getEnrollmentDataFromMeasurementUrl(
                                registrationRequest
                                        .getRegistrationUri()
                                        .buildUpon()
                                        .clearQuery()
                                        .build())
                != null) {
            enrollmentId =
                    EnrollmentDao.getInstance(CONTEXT)
                            .getEnrollmentDataFromMeasurementUrl(
                                    registrationRequest
                                            .getRegistrationUri()
                                            .buildUpon()
                                            .clearQuery()
                                            .build())
                            .getEnrollmentId();
        }
        return createAsyncRegistration(
                UUID.randomUUID().toString(),
                registrationRequest.getRegistrationUri(),
                null,
                null,
                Uri.parse(ANDROID_APP_SCHEME_URI_PREFIX + CONTEXT.getPackageName()),
                null,
                Uri.parse(ANDROID_APP_SCHEME_URI_PREFIX + CONTEXT.getPackageName()),
                registrationRequest.getRegistrationType() == RegistrationRequest.REGISTER_SOURCE
                        ? AsyncRegistration.RegistrationType.APP_SOURCE
                        : AsyncRegistration.RegistrationType.APP_TRIGGER,
                null,
                System.currentTimeMillis(),
                0,
                false,
                true,
                PLATFORM_AD_ID_VALUE);
    }

    private static AsyncRegistration webTriggerRegistrationRequest(
            WebTriggerRegistrationRequest webTriggerRegistrationRequest,
            boolean arDebugPermission) {
        if (webTriggerRegistrationRequest.getTriggerParams().size() > 0) {
            WebTriggerParams webTriggerParams =
                    webTriggerRegistrationRequest.getTriggerParams().get(0);
            // Necessary for testing
            String enrollmentId = "";
            if (EnrollmentDao.getInstance(CONTEXT)
                            .getEnrollmentDataFromMeasurementUrl(
                                    webTriggerRegistrationRequest
                                            .getTriggerParams()
                                            .get(0)
                                            .getRegistrationUri()
                                            .buildUpon()
                                            .clearQuery()
                                            .build())
                    != null) {
                enrollmentId =
                        EnrollmentDao.getInstance(CONTEXT)
                                .getEnrollmentDataFromMeasurementUrl(
                                        webTriggerParams
                                                .getRegistrationUri()
                                                .buildUpon()
                                                .clearQuery()
                                                .build())
                                .getEnrollmentId();
            }
            return createAsyncRegistration(
                    UUID.randomUUID().toString(),
                    webTriggerParams.getRegistrationUri(),
                    null,
                    null,
                    Uri.parse(ANDROID_APP_SCHEME_URI_PREFIX + CONTEXT.getPackageName()),
                    null,
                    webTriggerRegistrationRequest.getDestination(),
                    AsyncRegistration.RegistrationType.WEB_TRIGGER,
                    null,
                    System.currentTimeMillis(),
                    0,
                    arDebugPermission,
                    false,
                    null);
        }
        return null;
    }

    private static AsyncRegistration createAsyncRegistration(
            String iD,
            Uri registrationUri,
            Uri webDestination,
            Uri osDestination,
            Uri registrant,
            Uri verifiedDestination,
            Uri topOrigin,
            AsyncRegistration.RegistrationType registrationType,
            Source.SourceType sourceType,
            long mRequestTime,
            long mRetryCount,
            boolean debugKeyAllowed,
            boolean adIdPermission,
            String adIdValue) {
        return new AsyncRegistration.Builder()
                .setId(iD)
                .setRegistrationUri(registrationUri)
                .setWebDestination(webDestination)
                .setOsDestination(osDestination)
                .setRegistrant(registrant)
                .setVerifiedDestination(verifiedDestination)
                .setTopOrigin(topOrigin)
                .setType(registrationType)
                .setSourceType(sourceType)
                .setRequestTime(mRequestTime)
                .setRetryCount(mRetryCount)
                .setDebugKeyAllowed(debugKeyAllowed)
                .setRegistrationId(UUID.randomUUID().toString())
                .setAdIdPermission(adIdPermission)
                .setPlatformAdId(adIdValue)
                .build();
    }

    private static Map<String, List<String>> getDefaultHeaders() {
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("Attribution-Reporting-Register-Trigger",
                List.of("{" + "\"event_trigger_data\":" + EVENT_TRIGGERS_1 + "}"));
        return headers;
    }

    private static void assertTriggerRegistration(
            AsyncRegistration asyncRegistration, Trigger result) throws JSONException {
        assertEquals(
                asyncRegistration.getRegistrant().toString(),
                result.getAttributionDestination().toString());
        assertEquals(ENROLLMENT_ID, result.getEnrollmentId());
        assertEquals(new JSONArray(EVENT_TRIGGERS_1).toString(), result.getEventTriggers());
        assertEquals(asyncRegistration.getRegistrationUri(), result.getRegistrationOrigin());
    }
}
