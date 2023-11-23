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

import static com.android.adservices.service.measurement.SystemHealthParams.MAX_ATTRIBUTION_FILTERS;
import static com.android.adservices.service.measurement.SystemHealthParams.MAX_BYTES_PER_ATTRIBUTION_AGGREGATE_KEY_ID;
import static com.android.adservices.service.measurement.SystemHealthParams.MAX_FILTER_MAPS_PER_FILTER_SET;
import static com.android.adservices.service.measurement.SystemHealthParams.MAX_VALUES_PER_ATTRIBUTION_FILTER;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_REGISTRATIONS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

import android.net.Uri;

import androidx.test.filters.SmallTest;

import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.measurement.WebUtil;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.MeasurementRegistrationResponseStats;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import com.google.common.collect.ImmutableMap;

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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Unit tests for {@link FetcherUtil} */
@SmallTest
@RunWith(MockitoJUnitRunner.class)
public final class FetcherUtilTest {
    private static final String LONG_FILTER_STRING = "12345678901234567890123456";
    private static final Uri REGISTRATION_URI = WebUtil.validUri("https://foo.test");
    @Mock Flags mFlags;
    @Mock AdServicesLogger mLogger;

    private MockitoSession mStaticMockSession;

    public static final int UNKNOWN_SOURCE_TYPE = 0;
    public static final int UNKNOWN_REGISTRATION_SURFACE_TYPE = 0;
    public static final int APP_REGISTRATION_SURFACE_TYPE = 2;
    public static final int UNKNOWN_STATUS = 0;
    public static final int UNKNOWN_REGISTRATION_FAILURE_TYPE = 0;

    @Before
    public void setup() {
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .strictness(Strictness.WARN)
                        .startMocking();
        ExtendedMockito.doReturn(FlagsFactory.getFlagsForTest()).when(FlagsFactory::getFlags);
    }

    @After
    public void cleanup() throws InterruptedException {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testIsSuccess() {
        assertTrue(FetcherUtil.isSuccess(200));
        assertTrue(FetcherUtil.isSuccess(201));
        assertTrue(FetcherUtil.isSuccess(202));
        assertTrue(FetcherUtil.isSuccess(204));
        assertFalse(FetcherUtil.isSuccess(404));
        assertFalse(FetcherUtil.isSuccess(500));
        assertFalse(FetcherUtil.isSuccess(0));
    }

    @Test
    public void testIsRedirect() {
        assertTrue(FetcherUtil.isRedirect(301));
        assertTrue(FetcherUtil.isRedirect(302));
        assertTrue(FetcherUtil.isRedirect(303));
        assertTrue(FetcherUtil.isRedirect(307));
        assertTrue(FetcherUtil.isRedirect(308));
        assertFalse(FetcherUtil.isRedirect(200));
        assertFalse(FetcherUtil.isRedirect(404));
        assertFalse(FetcherUtil.isRedirect(500));
        assertFalse(FetcherUtil.isRedirect(0));
    }

    @Test
    public void parseRedirects_noRedirectHeaders_returnsEmpty() {
        Map<AsyncRegistration.RedirectType, List<Uri>> redirectMap =
                FetcherUtil.parseRedirects(Map.of());
        assertEquals(2, redirectMap.size());
        assertTrue(redirectMap.get(AsyncRegistration.RedirectType.LIST).isEmpty());
        assertTrue(redirectMap.get(AsyncRegistration.RedirectType.LOCATION).isEmpty());
    }

    @Test
    public void parseRedirects_bothHeaderTypes() {
        Map<AsyncRegistration.RedirectType, List<Uri>> redirectMap =
                FetcherUtil.parseRedirects(
                        Map.of(
                                "Attribution-Reporting-Redirect", List.of("foo.test", "bar.test"),
                                "Location", List.of("baz.test")));
        assertEquals(2, redirectMap.size());
        // Verify List Redirects
        List<Uri> redirects = redirectMap.get(AsyncRegistration.RedirectType.LIST);
        assertEquals(2, redirects.size());
        assertEquals(Uri.parse("foo.test"), redirects.get(0));
        assertEquals(Uri.parse("bar.test"), redirects.get(1));
        // Verify Location Redirect
        redirects = redirectMap.get(AsyncRegistration.RedirectType.LOCATION);
        assertEquals(1, redirects.size());
        assertEquals(Uri.parse("baz.test"), redirects.get(0));
    }

    @Test
    public void parseRedirects_locationHeaderOnly() {
        Map<AsyncRegistration.RedirectType, List<Uri>> redirectMap =
                FetcherUtil.parseRedirects(Map.of("Location", List.of("baz.test")));
        assertEquals(2, redirectMap.size());
        List<Uri> redirects = redirectMap.get(AsyncRegistration.RedirectType.LOCATION);
        assertEquals(1, redirects.size());
        assertEquals(Uri.parse("baz.test"), redirects.get(0));
        assertTrue(redirectMap.get(AsyncRegistration.RedirectType.LIST).isEmpty());
    }

    @Test
    public void parseRedirects_lsitHeaderOnly() {
        Map<AsyncRegistration.RedirectType, List<Uri>> redirectMap =
                FetcherUtil.parseRedirects(
                        Map.of("Attribution-Reporting-Redirect", List.of("foo.test", "bar.test")));
        assertEquals(2, redirectMap.size());
        // Verify List Redirects
        List<Uri> redirects = redirectMap.get(AsyncRegistration.RedirectType.LIST);
        assertEquals(2, redirects.size());
        assertEquals(Uri.parse("foo.test"), redirects.get(0));
        assertEquals(Uri.parse("bar.test"), redirects.get(1));
        assertTrue(redirectMap.get(AsyncRegistration.RedirectType.LOCATION).isEmpty());
    }

    @Test
    public void testIsValidAggregateKeyId_valid() {
        assertTrue(FetcherUtil.isValidAggregateKeyId("abcd"));
    }

    @Test
    public void testIsValidAggregateKeyId_null() {
        assertFalse(FetcherUtil.isValidAggregateKeyId(null));
    }

    @Test
    public void testIsValidAggregateKeyId_tooLong() {
        StringBuilder keyId = new StringBuilder("");
        for (int i = 0; i < MAX_BYTES_PER_ATTRIBUTION_AGGREGATE_KEY_ID + 1; i++) {
            keyId.append("a");
        }
        assertFalse(FetcherUtil.isValidAggregateKeyId(keyId.toString()));
    }

    @Test
    public void testIsValidAggregateKeyPiece_valid() {
        assertTrue(FetcherUtil.isValidAggregateKeyPiece("0x15A"));
    }

    @Test
    public void testIsValidAggregateKeyPiece_validWithUpperCasePrefix() {
        assertTrue(FetcherUtil.isValidAggregateKeyPiece("0X15A"));
    }

    @Test
    public void testIsValidAggregateKeyPiece_null() {
        assertFalse(FetcherUtil.isValidAggregateKeyPiece(null));
    }

    @Test
    public void testIsValidAggregateKeyPiece_missingPrefix() {
        assertFalse(FetcherUtil.isValidAggregateKeyPiece("1234"));
    }

    @Test
    public void testIsValidAggregateKeyPiece_tooShort() {
        assertFalse(FetcherUtil.isValidAggregateKeyPiece("0x"));
    }

    @Test
    public void testIsValidAggregateKeyPiece_tooLong() {
        StringBuilder keyPiece = new StringBuilder("0x");
        for (int i = 0; i < 33; i++) {
            keyPiece.append("1");
        }
        assertFalse(FetcherUtil.isValidAggregateKeyPiece(keyPiece.toString()));
    }

    @Test
    public void testAreValidAttributionFilters_filterSet_valid() throws JSONException {
        String json = "[{"
                + "\"filter-string-1\": [\"filter-value-1\"],"
                + "\"filter-string-2\": [\"filter-value-2\", \"filter-value-3\"]"
                + "}]";
        JSONArray filters = new JSONArray(json);
        assertTrue(FetcherUtil.areValidAttributionFilters(filters));
    }

    @Test
    public void testAreValidAttributionFilters_filterMap_valid() throws JSONException {
        String json = "{"
                + "\"filter-string-1\": [\"filter-value-1\"],"
                + "\"filter-string-2\": [\"filter-value-2\", \"filter-value-3\"]"
                + "}";
        JSONObject filters = new JSONObject(json);
        assertTrue(FetcherUtil.areValidAttributionFilters(filters));
    }

    @Test
    public void testAreValidAttributionFilters_filterMap_null() {
        JSONObject nullFilterMap = null;
        assertFalse(FetcherUtil.areValidAttributionFilters(nullFilterMap));
    }

    @Test
    public void testAreValidAttributionFilters_filterSet_tooManyFilters() throws JSONException {
        StringBuilder json = new StringBuilder("[{");
        json.append(IntStream.range(0, MAX_ATTRIBUTION_FILTERS + 1)
                .mapToObj(i -> "\"filter-string-" + i + "\": [\"filter-value\"]")
                .collect(Collectors.joining(",")));
        json.append("}]");
        JSONArray filters = new JSONArray(json.toString());
        assertFalse(FetcherUtil.areValidAttributionFilters(filters));
    }

    @Test
    public void testAreValidAttributionFilters_filterMap_tooManyFilters() throws JSONException {
        StringBuilder json = new StringBuilder("{");
        json.append(IntStream.range(0, MAX_ATTRIBUTION_FILTERS + 1)
                .mapToObj(i -> "\"filter-string-" + i + "\": [\"filter-value\"]")
                .collect(Collectors.joining(",")));
        json.append("}");
        JSONObject filters = new JSONObject(json.toString());
        assertFalse(FetcherUtil.areValidAttributionFilters(filters));
    }

    @Test
    public void testAreValidAttributionFilters_filterSet_keyTooLong() throws JSONException {
        String json = "[{"
                + "\"" + LONG_FILTER_STRING + "\": [\"filter-value-1\"],"
                + "\"filter-string-2\": [\"filter-value-2\", \"filter-value-3\"]"
                + "}]";
        JSONArray filters = new JSONArray(json);
        assertFalse(FetcherUtil.areValidAttributionFilters(filters));
    }

    @Test
    public void testAreValidAttributionFilters_filterMap_keyTooLong() throws JSONException {
        String json = "{"
                + "\"" + LONG_FILTER_STRING + "\": [\"filter-value-1\"],"
                + "\"filter-string-2\": [\"filter-value-2\", \"filter-value-3\"]"
                + "}";
        JSONObject filters = new JSONObject(json);
        assertFalse(FetcherUtil.areValidAttributionFilters(filters));
    }

    @Test
    public void testAreValidAttributionFilters_filterSet_tooManyFilterMaps() throws JSONException {
        StringBuilder json = new StringBuilder("[");
        json.append(IntStream.range(0, MAX_FILTER_MAPS_PER_FILTER_SET + 1)
                .mapToObj(i -> "{\"filter-string-1\": [\"filter-value-1\"],"
                        + "\"filter-string-2\": [\"filter-value-" + i + "\"]}")
                .collect(Collectors.joining(",")));
        json.append("]");
        JSONArray filters = new JSONArray(json.toString());
        assertFalse(FetcherUtil.areValidAttributionFilters(filters));
    }

    @Test
    public void testAreValidAttributionFilters_filterSet_tooManyValues() throws JSONException {
        StringBuilder json = new StringBuilder("[{"
                + "\"filter-string-1\": [\"filter-value-1\"],"
                + "\"filter-string-2\": [");
        json.append(IntStream.range(0, MAX_VALUES_PER_ATTRIBUTION_FILTER + 1)
                .mapToObj(i -> "\"filter-value-" + i + "\"")
                .collect(Collectors.joining(",")));
        json.append("]}]");
        JSONArray filters = new JSONArray(json.toString());
        assertFalse(FetcherUtil.areValidAttributionFilters(filters));
    }

    @Test
    public void testAreValidAttributionFilters_filterMap_tooManyValues() throws JSONException {
        StringBuilder json = new StringBuilder("{"
                + "\"filter-string-1\": [\"filter-value-1\"],"
                + "\"filter-string-2\": [");
        json.append(IntStream.range(0, MAX_VALUES_PER_ATTRIBUTION_FILTER + 1)
                .mapToObj(i -> "\"filter-value-" + i + "\"")
                .collect(Collectors.joining(",")));
        json.append("]}");
        JSONObject filters = new JSONObject(json.toString());
        assertFalse(FetcherUtil.areValidAttributionFilters(filters));
    }

    @Test
    public void testAreValidAttributionFilters_filterSet_valueTooLong() throws JSONException {
        String json = "[{"
                + "\"filter-string-1\": [\"filter-value-1\"],"
                + "\"filter-string-2\": [\"filter-value-2\", \"" + LONG_FILTER_STRING + "\"]"
                + "}]";
        JSONArray filters = new JSONArray(json);
        assertFalse(FetcherUtil.areValidAttributionFilters(filters));
    }

    @Test
    public void testAreValidAttributionFilters_filterMap_valueTooLong() throws JSONException {
        String json = "{"
                + "\"filter-string-1\": [\"filter-value-1\"],"
                + "\"filter-string-2\": [\"filter-value-2\", \"" + LONG_FILTER_STRING + "\"]"
                + "}";
        JSONObject filters = new JSONObject(json);
        assertFalse(FetcherUtil.areValidAttributionFilters(filters));
    }

    @Test
    public void emitHeaderMetrics_headersSizeLessThanMaxAllowed_doesNotLogAdTechDomain() {
        // Setup
        int registrationType = 1;
        long maxAllowedHeadersSize = 30;
        doReturn(maxAllowedHeadersSize)
                .when(mFlags)
                .getMaxResponseBasedRegistrationPayloadSizeBytes();
        Map<String, List<String>> headersMap = createHeadersMap();
        int headersMapSize = 28;

        // Execution
        AsyncRegistration asyncRegistration =
                new AsyncRegistration.Builder()
                        .setRegistrationId(UUID.randomUUID().toString())
                        .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                        .setRegistrationUri(REGISTRATION_URI)
                        .build();

        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        asyncFetchStatus.setRegistrationDelay(0L);
        asyncFetchStatus.setResponseSize(FetcherUtil.calculateHeadersCharactersLength(headersMap));

        FetcherUtil.emitHeaderMetrics(mFlags, mLogger, asyncRegistration, asyncFetchStatus);

        // Verify
        verify(mLogger)
                .logMeasurementRegistrationsResponseSize(
                        eq(
                                new MeasurementRegistrationResponseStats.Builder(
                                                AD_SERVICES_MEASUREMENT_REGISTRATIONS,
                                                registrationType,
                                                headersMapSize,
                                                UNKNOWN_SOURCE_TYPE,
                                                APP_REGISTRATION_SURFACE_TYPE,
                                                UNKNOWN_STATUS,
                                                UNKNOWN_REGISTRATION_FAILURE_TYPE,
                                                0)
                                        .setAdTechDomain(null)
                                        .build()));
    }

    @Test
    public void emitHeaderMetrics_headersSizeExceedsMaxAllowed_logsAdTechDomain() {
        // Setup
        int registrationType = 1;
        long maxAllowedHeadersSize = 25;
        doReturn(maxAllowedHeadersSize)
                .when(mFlags)
                .getMaxResponseBasedRegistrationPayloadSizeBytes();
        Map<String, List<String>> headersMap = createHeadersMap();
        int headersMapSize = 28;

        // Execution
        AsyncRegistration asyncRegistration =
                new AsyncRegistration.Builder()
                        .setRegistrationId(UUID.randomUUID().toString())
                        .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                        .setRegistrationUri(REGISTRATION_URI)
                        .build();

        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        asyncFetchStatus.setRegistrationDelay(0L);
        asyncFetchStatus.setResponseSize(FetcherUtil.calculateHeadersCharactersLength(headersMap));

        FetcherUtil.emitHeaderMetrics(mFlags, mLogger, asyncRegistration, asyncFetchStatus);

        // Verify
        verify(mLogger)
                .logMeasurementRegistrationsResponseSize(
                        eq(
                                new MeasurementRegistrationResponseStats.Builder(
                                                AD_SERVICES_MEASUREMENT_REGISTRATIONS,
                                                registrationType,
                                                headersMapSize,
                                                UNKNOWN_SOURCE_TYPE,
                                                APP_REGISTRATION_SURFACE_TYPE,
                                                UNKNOWN_STATUS,
                                                UNKNOWN_REGISTRATION_FAILURE_TYPE,
                                                0)
                                        .setAdTechDomain(REGISTRATION_URI.toString())
                                        .build()));
    }

    @Test
    public void emitHeaderMetrics_headersWithNullValues_success() {
        // Setup
        int registrationType = 1;
        long maxAllowedHeadersSize = 25;
        doReturn(maxAllowedHeadersSize)
                .when(mFlags)
                .getMaxResponseBasedRegistrationPayloadSizeBytes();

        Map<String, List<String>> headersMap = new HashMap<>();
        headersMap.put("key1", Arrays.asList("val11", "val12"));
        headersMap.put("key2", null);
        int headersMapSize = 18;

        // Execution
        AsyncRegistration asyncRegistration =
                new AsyncRegistration.Builder()
                        .setRegistrationId(UUID.randomUUID().toString())
                        .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                        .setRegistrationUri(REGISTRATION_URI)
                        .build();

        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        asyncFetchStatus.setRegistrationDelay(0L);
        asyncFetchStatus.setResponseSize(FetcherUtil.calculateHeadersCharactersLength(headersMap));

        FetcherUtil.emitHeaderMetrics(mFlags, mLogger, asyncRegistration, asyncFetchStatus);

        // Verify
        verify(mLogger)
                .logMeasurementRegistrationsResponseSize(
                        eq(
                                new MeasurementRegistrationResponseStats.Builder(
                                                AD_SERVICES_MEASUREMENT_REGISTRATIONS,
                                                registrationType,
                                                headersMapSize,
                                                UNKNOWN_SOURCE_TYPE,
                                                APP_REGISTRATION_SURFACE_TYPE,
                                                UNKNOWN_STATUS,
                                                UNKNOWN_REGISTRATION_FAILURE_TYPE,
                                                0)
                                        .setAdTechDomain(null)
                                        .build()));
    }

    @Test
    public void isValidAggregateDeduplicationKey_negativeValue() {
        assertFalse(FetcherUtil.isValidAggregateDeduplicationKey("-1"));
    }

    @Test
    public void isValidAggregateDeduplicationKey_nonNumericalValue() {
        assertFalse(FetcherUtil.isValidAggregateDeduplicationKey("w"));
    }

    @Test
    public void isValidAggregateDeduplicationKey_success() {
        assertTrue(FetcherUtil.isValidAggregateDeduplicationKey("18446744073709551615"));
        assertTrue(FetcherUtil.isValidAggregateDeduplicationKey("0"));
    }

    @Test
    public void isValidAggregateDeduplicationKey_nullValue_success() {
        assertFalse(FetcherUtil.isValidAggregateDeduplicationKey(null));
    }

    @Test
    public void getEncryptedPlatformAdIdIfPresent_webRegistration_null() {
        AsyncRegistration asyncRegistration =
                new AsyncRegistration.Builder()
                        .setRegistrationId(UUID.randomUUID().toString())
                        .setType(AsyncRegistration.RegistrationType.WEB_SOURCE)
                        .setAdIdPermission(true)
                        .setPlatformAdId("not-null-ad-id")
                        .build();

        assertNull(
                FetcherUtil.getEncryptedPlatformAdIdIfPresent(asyncRegistration, "enrollment_id"));
    }

    @Test
    public void getEncryptedPlatformAdIdIfPresent_missingAdIdPermission_null() {
        AsyncRegistration asyncRegistration =
                new AsyncRegistration.Builder()
                        .setRegistrationId(UUID.randomUUID().toString())
                        .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                        .setAdIdPermission(false)
                        .setPlatformAdId("not-null-ad-id")
                        .build();

        assertNull(
                FetcherUtil.getEncryptedPlatformAdIdIfPresent(asyncRegistration, "enrollment_id"));
    }

    @Test
    public void getEncryptedPlatformAdIdIfPresent_nullAdIdValue_null() {
        AsyncRegistration asyncRegistration =
                new AsyncRegistration.Builder()
                        .setRegistrationId(UUID.randomUUID().toString())
                        .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                        .setAdIdPermission(true)
                        .setPlatformAdId(null)
                        .build();

        assertNull(
                FetcherUtil.getEncryptedPlatformAdIdIfPresent(asyncRegistration, "enrollment_id"));
    }

    @Test
    public void getEncryptedPlatformAdIdIfPresent_adIdValuePresent_notNull() {
        AsyncRegistration asyncRegistration =
                new AsyncRegistration.Builder()
                        .setRegistrationId(UUID.randomUUID().toString())
                        .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                        .setAdIdPermission(true)
                        .setPlatformAdId("not-null-ad-id")
                        .build();

        assertNotNull(
                FetcherUtil.getEncryptedPlatformAdIdIfPresent(asyncRegistration, "enrollment_id"));
    }

    private Map<String, List<String>> createHeadersMap() {
        return new ImmutableMap.Builder<String, List<String>>()
                .put("key1", Arrays.asList("val11", "val12"))
                .put("key2", Arrays.asList("val21", "val22"))
                .build();
    }
}
