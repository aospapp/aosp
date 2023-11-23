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

package com.android.adservices.service.measurement;

import static android.view.MotionEvent.ACTION_BUTTON_PRESS;
import static android.view.MotionEvent.obtain;

import static com.android.adservices.service.measurement.reporting.AggregateReportSender.AGGREGATE_ATTRIBUTION_REPORT_URI_PATH;
import static com.android.adservices.service.measurement.reporting.AggregateReportSender.DEBUG_AGGREGATE_ATTRIBUTION_REPORT_URI_PATH;
import static com.android.adservices.service.measurement.reporting.DebugReportSender.DEBUG_REPORT_URI_PATH;
import static com.android.adservices.service.measurement.reporting.EventReportSender.DEBUG_EVENT_ATTRIBUTION_REPORT_URI_PATH;
import static com.android.adservices.service.measurement.reporting.EventReportSender.EVENT_ATTRIBUTION_REPORT_URI_PATH;

import android.content.AttributionSource;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.provider.DeviceConfig;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;

import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.data.DbTestUtil;
import com.android.adservices.service.Flags;
import com.android.adservices.service.measurement.actions.Action;
import com.android.adservices.service.measurement.actions.AggregateReportingJob;
import com.android.adservices.service.measurement.actions.EventReportingJob;
import com.android.adservices.service.measurement.actions.InstallApp;
import com.android.adservices.service.measurement.actions.RegisterSource;
import com.android.adservices.service.measurement.actions.RegisterTrigger;
import com.android.adservices.service.measurement.actions.RegisterWebSource;
import com.android.adservices.service.measurement.actions.RegisterWebTrigger;
import com.android.adservices.service.measurement.actions.ReportObjects;
import com.android.adservices.service.measurement.actions.UninstallApp;
import com.android.adservices.service.measurement.actions.UriConfig;

import com.google.common.collect.ImmutableList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * End-to-end test from source and trigger registration to attribution reporting. Extensions of
 * this class can implement different ways to prepare the registrations, either with an external
 * server or mocking HTTP responses, for example; similarly for examining the attribution reports.
 *
 * Consider @RunWith(Parameterized.class)
 */
public abstract class E2ETest {
    // Used to fuzzy-match expected report (not delivery) time
    private static final long REPORT_TIME_EPSILON = TimeUnit.HOURS.toMillis(2);
    private static final String LOG_TAG = "ADSERVICES_MSMT_E2E_TEST";

    static final Context sContext = ApplicationProvider.getApplicationContext();
    private final String mName;
    private final Collection<Action> mActionsList;
    final ReportObjects mExpectedOutput;
    private final Map<String, String> mPhFlagsMap;
    // Extenders of the class populate in their own ways this container for actual output.
    final ReportObjects mActualOutput;

    enum ReportType {
        EVENT,
        AGGREGATE,
        EVENT_DEBUG,
        AGGREGATE_DEBUG,
        DEBUG_REPORT_API
    }

    private enum OutputType {
        EXPECTED,
        ACTUAL
    }

    private interface EventReportPayloadKeys {
        // Keys used to compare actual with expected output
        List<String> STRINGS =
                ImmutableList.of(
                        "scheduled_report_time",
                        "source_event_id",
                        "trigger_data",
                        "source_type",
                        "source_debug_key",
                        "trigger_debug_key");
        String DOUBLE = "randomized_trigger_rate";
        String STRING_OR_ARRAY = "attribution_destination";
    }

    interface AggregateReportPayloadKeys {
        String ATTRIBUTION_DESTINATION = "attribution_destination";
        String HISTOGRAMS = "histograms";
        String SOURCE_DEBUG_KEY = "source_debug_key";
        String TRIGGER_DEBUG_KEY = "trigger_debug_key";
    }

    interface DebugReportPayloadKeys {
        String TYPE = "type";
        String BODY = "body";
        List<String> BODY_KEYS =
                ImmutableList.of(
                        "attribution_destination",
                        "limit",
                        "randomized_trigger_rate",
                        "scheduled_report_time",
                        "source_debug_key",
                        "source_event_id",
                        "source_site",
                        "source_type",
                        "trigger_debug_key");
    }

    interface AggregateHistogramKeys {
        String BUCKET = "key";
        String VALUE = "value";
    }

    public interface TestFormatJsonMapping {
        String API_CONFIG_KEY = "api_config";
        String PH_FLAGS_OVERRIDE_KEY = "phflags_override";
        String TEST_INPUT_KEY = "input";
        String TEST_OUTPUT_KEY = "output";
        String SOURCE_REGISTRATIONS_KEY = "sources";
        String WEB_SOURCES_KEY = "web_sources";
        String SOURCE_PARAMS_REGISTRATIONS_KEY = "source_params";
        String TRIGGER_KEY = "triggers";
        String WEB_TRIGGERS_KEY = "web_triggers";
        String TRIGGER_PARAMS_REGISTRATIONS_KEY = "trigger_params";
        String URI_TO_RESPONSE_HEADERS_KEY = "responses";
        String URI_TO_RESPONSE_HEADERS_URL_KEY = "url";
        String URI_TO_RESPONSE_HEADERS_RESPONSE_KEY = "response";
        String REGISTRATION_REQUEST_KEY = "registration_request";
        String ATTRIBUTION_SOURCE_KEY = "registrant";
        String ATTRIBUTION_SOURCE_DEFAULT = "com.interop.app";
        String SOURCE_TOP_ORIGIN_URI_KEY = "source_origin";
        String TRIGGER_TOP_ORIGIN_URI_KEY = "destination_origin";
        String SOURCE_APP_DESTINATION_URI_KEY = "app_destination";
        String SOURCE_WEB_DESTINATION_URI_KEY = "web_destination";
        String SOURCE_VERIFIED_DESTINATION_URI_KEY = "verified_destination";
        String REGISTRATION_URI_KEY = "attribution_src_url";
        String HAS_AD_ID_PERMISSION = "has_ad_id_permission";
        String DEBUG_KEY = "debug_key";
        String DEBUG_PERMISSION_KEY = "debug_permission";
        String DEBUG_REPORTING_KEY = "debug_reporting";
        String INPUT_EVENT_KEY = "source_type";
        String SOURCE_VIEW_TYPE = "event";
        String TIMESTAMP_KEY = "timestamp";
        String EVENT_REPORT_OBJECTS_KEY = "event_level_results";
        String AGGREGATE_REPORT_OBJECTS_KEY = "aggregatable_results";
        String DEBUG_EVENT_REPORT_OBJECTS_KEY = "debug_event_level_results";
        String DEBUG_AGGREGATE_REPORT_OBJECTS_KEY = "debug_aggregatable_results";
        String DEBUG_REPORT_API_OBJECTS_KEY = "verbose_debug_reports";
        String INSTALLS_KEY = "installs";
        String UNINSTALLS_KEY = "uninstalls";
        String INSTALLS_URI_KEY = "uri";
        String INSTALLS_TIMESTAMP_KEY = "timestamp";
        String REPORT_TIME_KEY = "report_time";
        String REPORT_TO_KEY = "report_url";
        String PAYLOAD_KEY = "payload";
        String ENROLL = "enroll";
    }

    private interface ApiConfigKeys {
        // Privacy params
        String RATE_LIMIT_MAX_ATTRIBUTIONS = "rate_limit_max_attributions";
        String NAVIGATION_SOURCE_TRIGGER_DATA_CARDINALITY =
                "navigation_source_trigger_data_cardinality";
        String RATE_LIMIT_MAX_ATTRIBUTION_REPORTING_ORIGINS =
                "rate_limit_max_attribution_reporting_origins";
        String MAX_DESTINATIONS_PER_SOURCE_SITE_REPORTING_ORIGIN =
                "max_destinations_per_source_site_reporting_origin";
        String RATE_LIMIT_MAX_SOURCE_REGISTRATION_REPORTING_ORIGINS =
                "rate_limit_max_source_registration_reporting_origins";
        // System health params
        String MAX_SOURCES_PER_ORIGIN = "max_sources_per_origin";
        String MAX_EVENT_LEVEL_REPORTS_PER_DESTINATION =
                "max_event_level_reports_per_destination";
        String MAX_AGGREGATABLE_REPORTS_PER_DESTINATION =
                "max_aggregatable_reports_per_destination";
    }

    public static class ParamsProvider {
        // Privacy params
        private Integer mMaxAttributionPerRateLimitWindow;
        private Integer mNavigationTriggerDataCardinality;
        private Integer mMaxDistinctEnrollmentsPerPublisherXDestinationInAttribution;
        private Integer mMaxDistinctDestinationsPerPublisherXEnrollmentInActiveSource;
        private Integer mMaxDistinctEnrollmentsPerPublisherXDestinationInSource;
        // System health params
        private Integer mMaxSourcesPerPublisher;
        private Integer mMaxEventReportsPerDestination;
        private Integer mMaxAggregateReportsPerDestination;

        public ParamsProvider(JSONObject json) throws JSONException {
            // Privacy params
            if (!json.isNull(ApiConfigKeys.RATE_LIMIT_MAX_ATTRIBUTIONS)) {
                mMaxAttributionPerRateLimitWindow = json.getInt(
                        ApiConfigKeys.RATE_LIMIT_MAX_ATTRIBUTIONS);
            } else {
                mMaxAttributionPerRateLimitWindow =
                        Flags.MEASUREMENT_MAX_ATTRIBUTION_PER_RATE_LIMIT_WINDOW;
            }
            if (!json.isNull(ApiConfigKeys.NAVIGATION_SOURCE_TRIGGER_DATA_CARDINALITY)) {
                mNavigationTriggerDataCardinality = json.getInt(
                        ApiConfigKeys.NAVIGATION_SOURCE_TRIGGER_DATA_CARDINALITY);
            } else {
                mNavigationTriggerDataCardinality =
                        PrivacyParams.getNavigationTriggerDataCardinality();
            }
            if (!json.isNull(ApiConfigKeys
                    .RATE_LIMIT_MAX_ATTRIBUTION_REPORTING_ORIGINS)) {
                mMaxDistinctEnrollmentsPerPublisherXDestinationInAttribution = json.getInt(
                        ApiConfigKeys.RATE_LIMIT_MAX_ATTRIBUTION_REPORTING_ORIGINS);
            } else {
                mMaxDistinctEnrollmentsPerPublisherXDestinationInAttribution =
                        Flags.MEASUREMENT_MAX_DISTINCT_ENROLLMENTS_IN_ATTRIBUTION;
            }
            if (!json.isNull(ApiConfigKeys
                    .MAX_DESTINATIONS_PER_SOURCE_SITE_REPORTING_ORIGIN)) {
                mMaxDistinctDestinationsPerPublisherXEnrollmentInActiveSource = json.getInt(
                        ApiConfigKeys.MAX_DESTINATIONS_PER_SOURCE_SITE_REPORTING_ORIGIN);
            } else {
                mMaxDistinctDestinationsPerPublisherXEnrollmentInActiveSource =
                        Flags.MEASUREMENT_MAX_DISTINCT_DESTINATIONS_IN_ACTIVE_SOURCE;
            }
            if (!json.isNull(ApiConfigKeys
                    .RATE_LIMIT_MAX_SOURCE_REGISTRATION_REPORTING_ORIGINS)) {
                mMaxDistinctEnrollmentsPerPublisherXDestinationInSource = json.getInt(
                        ApiConfigKeys.RATE_LIMIT_MAX_SOURCE_REGISTRATION_REPORTING_ORIGINS);
            } else {
                mMaxDistinctEnrollmentsPerPublisherXDestinationInSource =
                        PrivacyParams
                                .getMaxDistinctEnrollmentsPerPublisherXDestinationInSource();
            }
            // System health params
            if (!json.isNull(ApiConfigKeys.MAX_SOURCES_PER_ORIGIN)) {
                mMaxSourcesPerPublisher = json.getInt(ApiConfigKeys.MAX_SOURCES_PER_ORIGIN);
            } else {
                mMaxSourcesPerPublisher = Flags.MEASUREMENT_MAX_SOURCES_PER_PUBLISHER;
            }
            if (!json.isNull(ApiConfigKeys.MAX_EVENT_LEVEL_REPORTS_PER_DESTINATION)) {
                mMaxEventReportsPerDestination = json.getInt(
                        ApiConfigKeys.MAX_EVENT_LEVEL_REPORTS_PER_DESTINATION);
            } else {
                mMaxEventReportsPerDestination =
                        Flags.MEASUREMENT_MAX_EVENT_REPORTS_PER_DESTINATION;
            }
            if (!json.isNull(ApiConfigKeys.MAX_AGGREGATABLE_REPORTS_PER_DESTINATION)) {
                mMaxAggregateReportsPerDestination = json.getInt(
                        ApiConfigKeys.MAX_AGGREGATABLE_REPORTS_PER_DESTINATION);
            } else {
                mMaxAggregateReportsPerDestination =
                        Flags.MEASUREMENT_MAX_AGGREGATE_REPORTS_PER_DESTINATION;
            }
        }

        // Privacy params
        public Integer getMaxAttributionPerRateLimitWindow() {
            return mMaxAttributionPerRateLimitWindow;
        }

        public Integer getNavigationTriggerDataCardinality() {
            return mNavigationTriggerDataCardinality;
        }

        public Integer getMaxDistinctEnrollmentsInAttribution() {
            return mMaxDistinctEnrollmentsPerPublisherXDestinationInAttribution;
        }

        public Integer getMaxDistinctDestinationsInActiveSource() {
            return mMaxDistinctDestinationsPerPublisherXEnrollmentInActiveSource;
        }

        public Integer getMaxDistinctEnrollmentsPerPublisherXDestinationInSource() {
            return mMaxDistinctEnrollmentsPerPublisherXDestinationInSource;
        }

        // System health params
        public Integer getMaxSourcesPerPublisher() {
            return mMaxSourcesPerPublisher;
        }

        public Integer getMaxEventReportsPerDestination() {
            return mMaxEventReportsPerDestination;
        }

        public Integer getMaxAggregateReportsPerDestination() {
            return mMaxAggregateReportsPerDestination;
        }
    }

    static Collection<Object[]> data(String testDirName, Function<String, String> preprocessor)
            throws IOException, JSONException {
        AssetManager assetManager = sContext.getAssets();
        List<InputStream> inputStreams = new ArrayList<>();
        String[] testDirectoryList = assetManager.list(testDirName);
        for (String testFile : testDirectoryList) {
            inputStreams.add(assetManager.open(testDirName + "/" + testFile));
        }
        return getTestCasesFrom(inputStreams, testDirectoryList, preprocessor);
    }

    public static boolean hasArDebugPermission(JSONObject obj) throws JSONException {
        JSONObject urlToResponse =
                obj.getJSONArray(TestFormatJsonMapping.URI_TO_RESPONSE_HEADERS_KEY)
                        .getJSONObject(0);
        return urlToResponse.optBoolean(TestFormatJsonMapping.DEBUG_PERMISSION_KEY, false);
    }

    public static boolean hasAdIdPermission(JSONObject obj) throws JSONException {
        JSONObject urlToResponse =
                obj.getJSONArray(TestFormatJsonMapping.URI_TO_RESPONSE_HEADERS_KEY)
                        .getJSONObject(0);
        return urlToResponse.optBoolean(TestFormatJsonMapping.HAS_AD_ID_PERMISSION, false);
    }

    public static boolean hasSourceDebugReportingPermission(JSONObject obj) throws JSONException {
        JSONObject headersMapJson =
                obj.getJSONArray(TestFormatJsonMapping.URI_TO_RESPONSE_HEADERS_KEY)
                        .getJSONObject(0)
                        .getJSONObject(TestFormatJsonMapping.URI_TO_RESPONSE_HEADERS_RESPONSE_KEY);
        JSONObject registerSource =
                headersMapJson.getJSONObject("Attribution-Reporting-Register-Source");
        return registerSource.optBoolean(TestFormatJsonMapping.DEBUG_REPORTING_KEY, false);
    }

    public static boolean hasTriggerDebugReportingPermission(JSONObject obj) throws JSONException {
        JSONObject headersMapJson =
                obj.getJSONArray(TestFormatJsonMapping.URI_TO_RESPONSE_HEADERS_KEY)
                        .getJSONObject(0)
                        .getJSONObject(TestFormatJsonMapping.URI_TO_RESPONSE_HEADERS_RESPONSE_KEY);
        JSONObject registerTrigger =
                headersMapJson.getJSONObject("Attribution-Reporting-Register-Trigger");
        return registerTrigger.optBoolean(TestFormatJsonMapping.DEBUG_REPORTING_KEY, false);
    }

    public static Map<String, List<Map<String, List<String>>>> getUriToResponseHeadersMap(
            JSONObject obj) throws JSONException {
        JSONArray uriToResArray = obj.getJSONArray(
                TestFormatJsonMapping.URI_TO_RESPONSE_HEADERS_KEY);
        Map<String, List<Map<String, List<String>>>> uriToResponseHeadersMap = new HashMap<>();

        for (int i = 0; i < uriToResArray.length(); i++) {
            JSONObject urlToResponse = uriToResArray.getJSONObject(i);
            String uri = urlToResponse.getString(
                    TestFormatJsonMapping.URI_TO_RESPONSE_HEADERS_URL_KEY);
            JSONObject headersMapJson = urlToResponse.getJSONObject(
                    TestFormatJsonMapping.URI_TO_RESPONSE_HEADERS_RESPONSE_KEY);

            Iterator<String> headers = headersMapJson.keys();
            Map<String, List<String>> headersMap = new HashMap<>();

            while (headers.hasNext()) {
                String header = headers.next();
                if (!headersMapJson.isNull(header)) {
                    String data = headersMapJson.getString(header);
                    if (header.equals("Attribution-Reporting-Redirect")) {
                        JSONArray redirects = new JSONArray(data);
                        for (int j = 0; j < redirects.length(); j++) {
                            String redirectUri = redirects.getString(j);
                            headersMap.computeIfAbsent(
                                    header, k -> new ArrayList<>()).add(redirectUri);
                        }
                    } else {
                        headersMap.put(header, Collections.singletonList(data));
                    }
                } else {
                    headersMap.put(header, null);
                }
            }

            uriToResponseHeadersMap.computeIfAbsent(uri, k -> new ArrayList<>()).add(headersMap);
        }

        return uriToResponseHeadersMap;
    }

    public static Map<String, UriConfig> getUriConfigMap(JSONObject obj) throws JSONException {
        JSONArray uriToResArray =
                obj.getJSONArray(TestFormatJsonMapping.URI_TO_RESPONSE_HEADERS_KEY);
        Map<String, UriConfig> uriConfigMap = new HashMap<>();

        for (int i = 0; i < uriToResArray.length(); i++) {
            JSONObject urlToResponse = uriToResArray.getJSONObject(i);
            String uri =
                    urlToResponse.getString(TestFormatJsonMapping.URI_TO_RESPONSE_HEADERS_URL_KEY);
            uriConfigMap.put(uri, new UriConfig(urlToResponse));
        }

        return uriConfigMap;
    }

    // 'uid', the parameter passed to Builder(), is unimportant for this test; we only need the
    // package name.
    public static AttributionSource getAttributionSource(String source) {
        return new AttributionSource.Builder(1).setPackageName(source).build();
    }

    public static InputEvent getInputEvent() {
        return obtain(
                0 /*long downTime*/,
                0 /*long eventTime*/,
                ACTION_BUTTON_PRESS,
                1 /*int pointerCount*/,
                new PointerProperties[] { new PointerProperties() },
                new PointerCoords[] { new PointerCoords() },
                0 /*int metaState*/,
                0 /*int buttonState*/,
                1.0f /*float xPrecision*/,
                1.0f /*float yPrecision*/,
                0 /*int deviceId*/,
                0 /*int edgeFlags*/,
                InputDevice.SOURCE_TOUCH_NAVIGATION,
                0 /*int flags*/);
    }

    static String getReportUrl(ReportType reportType, String origin) {
        String reportUrl = null;
        if (reportType == ReportType.EVENT) {
            reportUrl = EVENT_ATTRIBUTION_REPORT_URI_PATH;
        } else if (reportType == ReportType.AGGREGATE) {
            reportUrl = AGGREGATE_ATTRIBUTION_REPORT_URI_PATH;
        } else if (reportType == ReportType.EVENT_DEBUG) {
            reportUrl = DEBUG_EVENT_ATTRIBUTION_REPORT_URI_PATH;
        } else if (reportType == ReportType.AGGREGATE_DEBUG) {
            reportUrl = DEBUG_AGGREGATE_ATTRIBUTION_REPORT_URI_PATH;
        } else if (reportType == ReportType.DEBUG_REPORT_API) {
            reportUrl = DEBUG_REPORT_URI_PATH;
        }
        return origin + "/" + reportUrl;
    }

    static void clearDatabase() {
        SQLiteDatabase db = DbTestUtil.getMeasurementDbHelperForTest().getWritableDatabase();
        emptyTables(db);

        DbTestUtil.getSharedDbHelperForTest()
                .getWritableDatabase()
                .delete("enrollment_data", null, null);
    }

    // The 'name' parameter is needed for the JUnit parameterized test, although it's ostensibly
    // unused by this constructor.
    E2ETest(
            Collection<Action> actions,
            ReportObjects expectedOutput,
            String name,
            Map<String, String> phFlagsMap) {
        mActionsList = actions;
        mExpectedOutput = expectedOutput;
        mActualOutput = new ReportObjects();
        mName = name;
        mPhFlagsMap = phFlagsMap;
    }

    @Test
    public void runTest() throws IOException, JSONException, DeviceConfig.BadConfigException {
        clearDatabase();
        setupDeviceConfigForPhFlags();
        for (Action action : mActionsList) {
            if (action instanceof RegisterSource) {
                processAction((RegisterSource) action);
            } else if (action instanceof RegisterTrigger) {
                processAction((RegisterTrigger) action);
            } else if (action instanceof RegisterWebSource) {
                processAction((RegisterWebSource) action);
            } else if (action instanceof RegisterWebTrigger) {
                processAction((RegisterWebTrigger) action);
            } else if (action instanceof EventReportingJob) {
                processAction((EventReportingJob) action);
            } else if (action instanceof AggregateReportingJob) {
                processAction((AggregateReportingJob) action);
            } else if (action instanceof InstallApp) {
                processAction((InstallApp) action);
            } else if (action instanceof UninstallApp) {
                processAction((UninstallApp) action);
            }
        }
        evaluateResults();
        clearDatabase();
    }

    public void log(String message) {
        Log.i(LOG_TAG, String.format("%s: %s", mName, message));
    }

    /**
     * The reporting job may be handled differently depending on whether network requests are mocked
     * or a test server is used.
     */
    abstract void processAction(EventReportingJob reportingJob) throws IOException, JSONException;

    /**
     * The reporting job may be handled differently depending on whether network requests are mocked
     * or a test server is used.
     */
    abstract void processAction(AggregateReportingJob reportingJob)
            throws IOException, JSONException;

    /**
     * Override with HTTP response mocks, for example.
     */
    abstract void prepareRegistrationServer(RegisterSource sourceRegistration)
            throws IOException;

    /**
     * Override with HTTP response mocks, for example.
     */
    abstract void prepareRegistrationServer(RegisterTrigger triggerRegistration)
            throws IOException;

    /** Override with HTTP response mocks, for example. */
    abstract void prepareRegistrationServer(RegisterWebSource sourceRegistration)
            throws IOException;

    /** Override with HTTP response mocks, for example. */
    abstract void prepareRegistrationServer(RegisterWebTrigger triggerRegistration)
            throws IOException;

    private static int hashForEventReportObject(OutputType outputType, JSONObject obj) {
        int n = EventReportPayloadKeys.STRINGS.size();
        int numValuesExcludingN = 3;
        Object[] objArray = new Object[n + numValuesExcludingN];
        // We cannot use report time due to fuzzy matching between actual and expected output.
        String url = obj.optString(TestFormatJsonMapping.REPORT_TO_KEY, "");
        objArray[0] =
                outputType == OutputType.EXPECTED ? url : getReportUrl(ReportType.EVENT, url);
        JSONObject payload = obj.optJSONObject(TestFormatJsonMapping.PAYLOAD_KEY);
        objArray[1] = payload.optDouble(EventReportPayloadKeys.DOUBLE, 0);
        // Try string then JSONArray in order so as to override the string if the array parsing is
        // successful.
        objArray[2] = null;
        String maybeString = payload.optString(EventReportPayloadKeys.STRING_OR_ARRAY);
        if (maybeString != null) {
            objArray[2] = maybeString;
        }
        JSONArray maybeArray = payload.optJSONArray(EventReportPayloadKeys.STRING_OR_ARRAY);
        if (maybeArray != null) {
            objArray[2] = maybeArray;
        }
        for (int i = 0; i < n; i++) {
            objArray[i + numValuesExcludingN] =
                    payload.optString(EventReportPayloadKeys.STRINGS.get(i), "");
        }
        return Arrays.hashCode(objArray);
    }

    private static int hashForAggregateReportObject(OutputType outputType,
            JSONObject obj) {
        Object[] objArray = new Object[5];
        // We cannot use report time due to fuzzy matching between actual and expected output.
        String url = obj.optString(TestFormatJsonMapping.REPORT_TO_KEY, "");
        objArray[0] =
                outputType == OutputType.EXPECTED ? url : getReportUrl(ReportType.AGGREGATE, url);
        JSONObject payload = obj.optJSONObject(TestFormatJsonMapping.PAYLOAD_KEY);
        objArray[1] = payload.optString(AggregateReportPayloadKeys.ATTRIBUTION_DESTINATION, "");
        // To compare histograms, we already converted them to an ordered string of value pairs.
        objArray[2] = getComparableHistograms(
                payload.optJSONArray(AggregateReportPayloadKeys.HISTOGRAMS));
        objArray[3] = payload.optString(AggregateReportPayloadKeys.SOURCE_DEBUG_KEY, "");
        objArray[4] = payload.optString(AggregateReportPayloadKeys.TRIGGER_DEBUG_KEY, "");
        return Arrays.hashCode(objArray);
    }

    private static long reportTimeFrom(JSONObject obj) {
        return obj.optLong(TestFormatJsonMapping.REPORT_TIME_KEY, 0);
    }

    // 'obj1' is the expected result, 'obj2' is the actual result.
    private boolean matchReportTimeAndReportTo(ReportType reportType, JSONObject obj1,
            JSONObject obj2) throws JSONException {
        if (Math.abs(obj1.getLong(TestFormatJsonMapping.REPORT_TIME_KEY)
                - obj2.getLong(TestFormatJsonMapping.REPORT_TIME_KEY))
                > REPORT_TIME_EPSILON) {
            log("Report-time mismatch. Report type: " + reportType.name());
            return false;
        }
        if (!obj1.getString(TestFormatJsonMapping.REPORT_TO_KEY).equals(
                getReportUrl(reportType, obj2.getString(TestFormatJsonMapping.REPORT_TO_KEY)))) {
            log("Report-to mismatch. Report type: " + reportType.name());
            return false;
        }
        return true;
    }

    private static boolean areEqualStringOrJSONArray(Object expected, Object actual)
            throws JSONException {
        if (expected instanceof String) {
            return (actual instanceof String) && (expected.equals(actual));
        } else {
            JSONArray jsonArr1 = (JSONArray) expected;
            JSONArray jsonArr2 = (JSONArray) actual;
            if (jsonArr1.length() != jsonArr2.length()) {
                return false;
            }
            for (int i = 0; i < jsonArr1.length(); i++) {
                if (!jsonArr1.getString(i).equals(jsonArr2.getString(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean areEqualEventReportJsons(
            ReportType reportType, JSONObject expected, JSONObject actual) throws JSONException {
        JSONObject expectedPayload = expected.getJSONObject(TestFormatJsonMapping.PAYLOAD_KEY);
        JSONObject actualPayload = actual.getJSONObject(TestFormatJsonMapping.PAYLOAD_KEY);
        if (expectedPayload.getDouble(EventReportPayloadKeys.DOUBLE)
                != actualPayload.getDouble(EventReportPayloadKeys.DOUBLE)) {
            log("Event payload double mismatch. Report type: " + reportType.name());
            return false;
        }
        if (!areEqualStringOrJSONArray(
                expectedPayload.get(EventReportPayloadKeys.STRING_OR_ARRAY),
                actualPayload.get(EventReportPayloadKeys.STRING_OR_ARRAY))) {
            return false;
        }
        for (String key : EventReportPayloadKeys.STRINGS) {
            if (!expectedPayload.optString(key, "").equals(actualPayload.optString(key, ""))) {
                log("Event payload string mismatch: " + key + ". Report type: "
                        + reportType.name());
                return false;
            }
        }
        return matchReportTimeAndReportTo(reportType, expected, actual);
    }

    private boolean areEqualAggregateReportJsons(
            ReportType reportType, JSONObject expected, JSONObject actual) throws JSONException {
        JSONObject payload1 = expected.getJSONObject(TestFormatJsonMapping.PAYLOAD_KEY);
        JSONObject payload2 = actual.getJSONObject(TestFormatJsonMapping.PAYLOAD_KEY);
        if (!payload1.optString(AggregateReportPayloadKeys.ATTRIBUTION_DESTINATION, "").equals(
                payload2.optString(AggregateReportPayloadKeys.ATTRIBUTION_DESTINATION, ""))) {
            log("Aggregate attribution destination mismatch");
            return false;
        }
        if (!payload1.optString(AggregateReportPayloadKeys.SOURCE_DEBUG_KEY, "")
                .equals(payload2.optString(AggregateReportPayloadKeys.SOURCE_DEBUG_KEY, ""))) {
            return false;
        }
        if (!payload1.optString(AggregateReportPayloadKeys.TRIGGER_DEBUG_KEY, "")
                .equals(payload2.optString(AggregateReportPayloadKeys.TRIGGER_DEBUG_KEY, ""))) {
            return false;
        }
        JSONArray histograms1 = payload1.optJSONArray(AggregateReportPayloadKeys.HISTOGRAMS);
        JSONArray histograms2 = payload2.optJSONArray(AggregateReportPayloadKeys.HISTOGRAMS);
        if (!getComparableHistograms(histograms1).equals(getComparableHistograms(histograms2))) {
            log("Aggregate histogram mismatch");
            return false;
        }
        return matchReportTimeAndReportTo(reportType, expected, actual);
    }

    private boolean areEqualDebugReportJsons(JSONObject expected, JSONObject actual)
            throws JSONException {
        JSONArray payloads1 = expected.getJSONArray(TestFormatJsonMapping.PAYLOAD_KEY);
        JSONArray payloads2 = actual.getJSONArray(TestFormatJsonMapping.PAYLOAD_KEY);
        if (payloads1.length() != payloads2.length()) {
            log("Debug report size mismatch");
            return false;
        }
        for (int i = 0; i < payloads1.length(); i++) {
            JSONObject payload1 = payloads1.getJSONObject(i);
            String type = payload1.optString(DebugReportPayloadKeys.TYPE, "");
            boolean hasSameType = false;
            for (int j = 0; j < payloads2.length(); j++) {
                JSONObject payload2 = payloads2.getJSONObject(j);
                if (type.equals(payload2.optString(DebugReportPayloadKeys.TYPE, ""))) {
                    hasSameType = true;
                    JSONObject body1 = payload1.getJSONObject(DebugReportPayloadKeys.BODY);
                    JSONObject body2 = payload2.getJSONObject(DebugReportPayloadKeys.BODY);
                    if (body1.length() != body2.length()) {
                        log(
                                "Verbose debug report payload body key-value pair not equal for"
                                        + " type: "
                                        + type);
                        return false;
                    }
                    for (String key : DebugReportPayloadKeys.BODY_KEYS) {
                        if (!body1.optString(key, "").equals(body2.optString(key, ""))) {
                            log(
                                    "Verbose debug report payload body mismatch for type: "
                                            + type
                                            + ", body key: "
                                            + key);
                            return false;
                        }
                    }
                    break;
                }
            }
            if (!hasSameType) {
                log("Debug report type mismatch.");
                return false;
            }
        }
        return expected.optString(TestFormatJsonMapping.REPORT_TO_KEY)
                .equals(
                        getReportUrl(
                                ReportType.DEBUG_REPORT_API,
                                actual.optString(TestFormatJsonMapping.REPORT_TO_KEY)));
    }

    private static String getComparableHistograms(@Nullable JSONArray arr) {
        if (arr == null) {
            return "";
        }
        try {
            List<String> tempList = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject pair = arr.getJSONObject(i);
                tempList.add(pair.getString(AggregateHistogramKeys.BUCKET) + ","
                        + pair.getString(AggregateHistogramKeys.VALUE));
            }
            Collections.sort(tempList);
            return String.join(";", tempList);
        } catch (JSONException ignored) {
            return "";
        }
    }

    private static void sortEventReportObjects(OutputType outputType,
            List<JSONObject> eventReportObjects) {
        eventReportObjects.sort(
                // Report time can vary across implementations so cannot be included in the hash;
                // they should be similarly ordered, however, so we can use them to sort.
                Comparator.comparing(E2ETest::reportTimeFrom)
                        .thenComparing(obj -> hashForEventReportObject(outputType, obj)));
    }

    private static void sortAggregateReportObjects(OutputType outputType,
            List<JSONObject> aggregateReportObjects) {
        aggregateReportObjects.sort(
                // Unlike event reports (sorted elsewhere in this file), aggregate reports are
                // scheduled with randomised times, and using report time for sorting can result
                // in unexpected variations in the sort order, depending on test timing. Without
                // time ordering, we rely on other data across the reports to yield different
                // hash codes.
                Comparator.comparing(obj -> hashForAggregateReportObject(outputType, obj)));
    }

    private static void sortDebugReportObjects(List<JSONObject> debugReportObjects) {
        List<String> debugReportList = new ArrayList<>();
        for (int i = 0; i < debugReportObjects.size(); i++) {
            debugReportList.add(debugReportObjects.get(i).toString());
        }
        Collections.sort(debugReportList);
    }

    private boolean areEqual(ReportObjects expected, ReportObjects actual) throws JSONException {
        if (expected.mEventReportObjects.size() != actual.mEventReportObjects.size()
                || expected.mAggregateReportObjects.size() != actual.mAggregateReportObjects.size()
                || expected.mDebugAggregateReportObjects.size()
                        != actual.mDebugAggregateReportObjects.size()
                || expected.mDebugEventReportObjects.size()
                        != actual.mDebugEventReportObjects.size()
                || expected.mDebugReportObjects.size() != actual.mDebugReportObjects.size()) {
            log("Report list size mismatch");
            return false;
        }
        for (int i = 0; i < expected.mEventReportObjects.size(); i++) {
            if (!areEqualEventReportJsons(
                    ReportType.EVENT,
                    expected.mEventReportObjects.get(i),
                    actual.mEventReportObjects.get(i))) {
                log("Event report object mismatch");
                return false;
            }
        }
        for (int i = 0; i < expected.mAggregateReportObjects.size(); i++) {
            if (!areEqualAggregateReportJsons(
                    ReportType.AGGREGATE,
                    expected.mAggregateReportObjects.get(i),
                    actual.mAggregateReportObjects.get(i))) {
                log("Aggregate report object mismatch");
                return false;
            }
        }
        for (int i = 0; i < expected.mDebugEventReportObjects.size(); i++) {
            if (!areEqualEventReportJsons(
                    ReportType.EVENT_DEBUG,
                    expected.mDebugEventReportObjects.get(i),
                    actual.mDebugEventReportObjects.get(i))) {
                log("Debug event report object mismatch");
                return false;
            }
        }
        for (int i = 0; i < expected.mDebugAggregateReportObjects.size(); i++) {
            if (!areEqualAggregateReportJsons(
                    ReportType.AGGREGATE_DEBUG,
                    expected.mDebugAggregateReportObjects.get(i),
                    actual.mDebugAggregateReportObjects.get(i))) {
                log("Debug aggregate report object mismatch");
                return false;
            }
        }
        for (int i = 0; i < expected.mDebugReportObjects.size(); i++) {
            if (!areEqualDebugReportJsons(
                    expected.mDebugReportObjects.get(i), actual.mDebugReportObjects.get(i))) {
                log("Debug report object mismatch");
                return false;
            }
        }

        return true;
    }

    private static String getTestFailureMessage(ReportObjects expectedOutput,
            ReportObjects actualOutput) {
        return String.format(
                        "Actual output does not match expected.\n\n"
                            + "(Note that displayed randomized_trigger_rate and report_url are not"
                            + " normalised.\n"
                            + "Note that report IDs are ignored in comparisons since they are not"
                            + " known in advance.)\n\n"
                            + "Event report objects:\n"
                            + "%s\n\n"
                            + "Debug Event report objects:\n"
                            + "%s\n\n"
                            + "Expected aggregate report objects: %s\n\n"
                            + "Actual aggregate report objects: %s\n\n"
                            + "Expected debug aggregate report objects: %s\n\n"
                            + "Actual debug aggregate report objects: %s\n\n"
                            + "Expected debug report objects: %s\n\n"
                            + "Actual debug report objects: %s\n",
                        prettify(
                                expectedOutput.mEventReportObjects,
                                actualOutput.mEventReportObjects),
                        prettify(
                                expectedOutput.mDebugEventReportObjects,
                                actualOutput.mDebugEventReportObjects),
                        expectedOutput.mAggregateReportObjects,
                        actualOutput.mAggregateReportObjects,
                        expectedOutput.mDebugAggregateReportObjects,
                        actualOutput.mDebugAggregateReportObjects,
                        expectedOutput.mDebugReportObjects,
                        actualOutput.mDebugReportObjects)
                + getDatastoreState();
    }

    private static String prettify(List<JSONObject> expected, List<JSONObject> actual) {
        StringBuilder result = new StringBuilder("(Expected ::: Actual)"
                + "\n------------------------\n");
        for (int i = 0; i < Math.max(expected.size(), actual.size()); i++) {
            if (i < expected.size() && i < actual.size()) {
                result.append(prettifyObjs(expected.get(i), actual.get(i)));
            } else {
                if (i < expected.size()) {
                    result.append(prettifyObj("", expected.get(i)));
                }
                if (i < actual.size()) {
                    result.append(prettifyObj(" ::: ", actual.get(i)));
                }
            }
            result.append("\n------------------------\n");
        }
        return result.toString();
    }

    private static String prettifyObjs(JSONObject obj1, JSONObject obj2) {
        StringBuilder result = new StringBuilder();
        result.append(TestFormatJsonMapping.REPORT_TIME_KEY + ": ")
                .append(obj1.optString(TestFormatJsonMapping.REPORT_TIME_KEY))
                .append(" ::: ")
                .append(obj2.optString(TestFormatJsonMapping.REPORT_TIME_KEY))
                .append("\n");
        result.append(TestFormatJsonMapping.REPORT_TO_KEY + ": ")
                .append(obj1.optString(TestFormatJsonMapping.REPORT_TO_KEY))
                .append(" ::: ")
                .append(obj2.optString(TestFormatJsonMapping.REPORT_TO_KEY))
                .append("\n");
        JSONObject payload1 = obj1.optJSONObject(TestFormatJsonMapping.PAYLOAD_KEY);
        JSONObject payload2 = obj2.optJSONObject(TestFormatJsonMapping.PAYLOAD_KEY);
        try {
            result.append(EventReportPayloadKeys.STRING_OR_ARRAY + ": ")
                    .append(payload1.get(EventReportPayloadKeys.STRING_OR_ARRAY).toString())
                    .append(" ::: ")
                    .append(payload2.get(EventReportPayloadKeys.STRING_OR_ARRAY).toString() + "\n");
        } catch (JSONException e) {
            result.append("JSONObject::get failed for EventReportPayloadKeys.STRING_OR_ARRAY "
                    + e + "\n");
        }
        for (String key : EventReportPayloadKeys.STRINGS) {
            result.append(key)
                    .append(": ")
                    .append(payload1.optString(key))
                    .append(" ::: ")
                    .append(payload2.optString(key))
                    .append("\n");
        }
        result.append(EventReportPayloadKeys.DOUBLE + ": ")
                .append(payload1.optDouble(EventReportPayloadKeys.DOUBLE))
                .append(" ::: ")
                .append(payload2.optDouble(EventReportPayloadKeys.DOUBLE));
        return result.toString();
    }

    private static String prettifyObj(String pad, JSONObject obj) {
        StringBuilder result = new StringBuilder();
        result.append(TestFormatJsonMapping.REPORT_TIME_KEY + ": ")
                .append(pad)
                .append(obj.optString(TestFormatJsonMapping.REPORT_TIME_KEY))
                .append("\n");
        JSONObject payload = obj.optJSONObject(TestFormatJsonMapping.PAYLOAD_KEY);
        try {
            result.append(EventReportPayloadKeys.STRING_OR_ARRAY + ": ")
                    .append(pad)
                    .append(payload.get(EventReportPayloadKeys.STRING_OR_ARRAY).toString() + "\n");
        } catch (JSONException e) {
            result.append("JSONObject::get failed for EventReportPayloadKeys.STRING_OR_ARRAY "
                    + e + "\n");
        }
        for (String key : EventReportPayloadKeys.STRINGS) {
            result.append(key).append(": ").append(pad).append(payload.optString(key)).append("\n");
        }
        result.append(EventReportPayloadKeys.DOUBLE + ": ")
                .append(pad)
                .append(payload.optDouble(EventReportPayloadKeys.DOUBLE));
        return result.toString();
    }

    protected static String getDatastoreState() {
        StringBuilder result = new StringBuilder();
        SQLiteDatabase db = DbTestUtil.getMeasurementDbHelperForTest().getWritableDatabase();
        List<String> tableNames =
                ImmutableList.of(
                        "msmt_source",
                        "msmt_source_destination",
                        "msmt_trigger",
                        "msmt_attribution",
                        "msmt_event_report",
                        "msmt_aggregate_report",
                        "msmt_async_registration_contract");
        for (String tableName : tableNames) {
            result.append("\n" + tableName + ":\n");
            result.append(getTableState(db, tableName));
        }
        SQLiteDatabase enrollmentDb = DbTestUtil.getSharedDbHelperForTest().getWritableDatabase();
        List<String> enrollmentTables = ImmutableList.of("enrollment_data");
        for (String tableName : enrollmentTables) {
            result.append("\n" + tableName + ":\n");
            result.append(getTableState(enrollmentDb, tableName));
        }
        return result.toString();
    }

    private static String getTableState(SQLiteDatabase db, String tableName) {
        Cursor cursor = getAllRows(db, tableName);
        StringBuilder result = new StringBuilder();
        while (cursor.moveToNext()) {
            result.append("\n" + DatabaseUtils.dumpCurrentRowToString(cursor));
        }
        return result.toString();
    }

    private static Cursor getAllRows(SQLiteDatabase db, String tableName) {
        return db.query(
                /* boolean distinct */ false,
                tableName,
                /* String[] columns */ null,
                /* String selection */ null,
                /* String[] selectionArgs */ null,
                /* String groupBy */ null,
                /* String having */ null,
                /* String orderBy */ null,
                /* String limit */ null);
    }

    private static Set<Long> getExpiryTimesFrom(
            Collection<List<Map<String, List<String>>>> responseHeadersCollection)
            throws JSONException {
        Set<Long> expiryTimes = new HashSet<>();

        for (List<Map<String, List<String>>> responseHeaders : responseHeadersCollection) {
            for (Map<String, List<String>> headersMap : responseHeaders) {
                if (!headersMap.containsKey("Attribution-Reporting-Register-Source")) {
                    continue;
                }
                String sourceStr = headersMap.get("Attribution-Reporting-Register-Source").get(0);
                JSONObject sourceJson = new JSONObject(sourceStr);
                if (sourceJson.has("expiry")) {
                    expiryTimes.add(sourceJson.getLong("expiry"));
                } else {
                    expiryTimes.add(
                            PrivacyParams.MAX_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS);
                }
            }
        }

        return expiryTimes;
    }

    private static long roundSecondsToWholeDays(long seconds) {
        long remainder = seconds % TimeUnit.DAYS.toSeconds(1);
        boolean roundUp = remainder >= TimeUnit.DAYS.toSeconds(1) / 2L;
        return seconds - remainder + (roundUp ? TimeUnit.DAYS.toSeconds(1) : 0);
    }

    private static Set<Action> maybeAddEventReportingJobTimes(boolean isEventType,
            long sourceTime, Collection<List<Map<String, List<String>>>> responseHeaders)
            throws JSONException {
        Set<Action> reportingJobsActions = new HashSet<>();
        Set<Long> expiryTimes = getExpiryTimesFrom(responseHeaders);
        for (Long expiry : expiryTimes) {
            long validExpiry = expiry;
            if (expiry > PrivacyParams.MAX_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS) {
                validExpiry = PrivacyParams.MAX_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS;
            } else if (expiry < PrivacyParams.MIN_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS) {
                validExpiry = PrivacyParams.MIN_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS;
            }
            if (isEventType) {
                validExpiry = roundSecondsToWholeDays(validExpiry);
            }

            long jobTime = sourceTime + 1000 * validExpiry + 3600000L;

            reportingJobsActions.add(new EventReportingJob(jobTime));
            // Add a job two days earlier for interop tests
            reportingJobsActions.add(new EventReportingJob(jobTime - TimeUnit.DAYS.toMillis(2)));
        }

        return reportingJobsActions;
    }

    static String preprocessTestJson(String json) {
        return json.replaceAll("\\.test(?=[\"\\/])", ".com");
    }

    /**
     * Builds and returns test cases from a JSON InputStream to be used by JUnit parameterized
     * tests.
     *
     * @return A collection of Object arrays, each with
     * {@code [Collection<Object> actions, ReportObjects expectedOutput,
     * ParamsProvider paramsProvider, String name]}
     */
    private static Collection<Object[]> getTestCasesFrom(List<InputStream> inputStreams,
            String[] filenames, Function<String, String> preprocessor)
            throws IOException, JSONException {
        List<Object[]> testCases = new ArrayList<>();

        for (int i = 0; i < inputStreams.size(); i++) {
            int size = inputStreams.get(i).available();
            byte[] buffer = new byte[size];
            inputStreams.get(i).read(buffer);
            inputStreams.get(i).close();
            String json = new String(buffer, StandardCharsets.UTF_8);

            JSONObject testObj = new JSONObject(preprocessor.apply(json));
            String name = filenames[i];
            JSONObject input = testObj.getJSONObject(TestFormatJsonMapping.TEST_INPUT_KEY);
            JSONObject output = testObj.getJSONObject(TestFormatJsonMapping.TEST_OUTPUT_KEY);

            // "Actions" are source or trigger registrations, or a reporting job.
            List<Action> actions = new ArrayList<>();

            actions.addAll(createSourceBasedActions(input));
            actions.addAll(createTriggerBasedActions(input));
            actions.addAll(createInstallActions(input));
            actions.addAll(createUninstallActions(input));

            actions.sort(Comparator.comparing(Action::getComparable));

            ReportObjects expectedOutput = getExpectedOutput(output);

            JSONObject ApiConfigObj = testObj.isNull(TestFormatJsonMapping.API_CONFIG_KEY)
                    ? new JSONObject()
                    : testObj.getJSONObject(TestFormatJsonMapping.API_CONFIG_KEY);

            ParamsProvider paramsProvider = new ParamsProvider(ApiConfigObj);

            testCases.add(
                    new Object[] {
                        actions, expectedOutput, paramsProvider, name, extractPhFlags(testObj)
                    });
        }

        return testCases;
    }

    private static Map<String, String> extractPhFlags(JSONObject testObj) {
        Map<String, String> phFlagsMap = new HashMap<>();
        if (testObj.isNull(TestFormatJsonMapping.PH_FLAGS_OVERRIDE_KEY)) {
            return phFlagsMap;
        }

        JSONObject phFlagsObject =
                testObj.optJSONObject(TestFormatJsonMapping.PH_FLAGS_OVERRIDE_KEY);
        phFlagsObject.keySet().forEach((key) -> phFlagsMap.put(key, phFlagsObject.optString(key)));
        return phFlagsMap;
    }

    private static List<Action> createSourceBasedActions(JSONObject input) throws JSONException {
        List<Action> actions = new ArrayList<>();
        // Set avoids duplicate reporting times across sources to do attribution upon.
        Set<Action> eventReportingJobActions = new HashSet<>();
        if (!input.isNull(TestFormatJsonMapping.SOURCE_REGISTRATIONS_KEY)) {
            JSONArray sourceRegistrationArray = input.getJSONArray(
                    TestFormatJsonMapping.SOURCE_REGISTRATIONS_KEY);
            for (int j = 0; j < sourceRegistrationArray.length(); j++) {
                RegisterSource sourceRegistration =
                        new RegisterSource(sourceRegistrationArray.getJSONObject(j));
                actions.add(sourceRegistration);
                // Add corresponding reporting job time actions
                eventReportingJobActions.addAll(
                        maybeAddEventReportingJobTimes(
                                sourceRegistration.mRegistrationRequest.getInputEvent() == null,
                                sourceRegistration.mTimestamp,
                                sourceRegistration.mUriToResponseHeadersMap.values()));
            }
        }

        if (!input.isNull(TestFormatJsonMapping.WEB_SOURCES_KEY)) {
            JSONArray webSourceRegistrationArray =
                    input.getJSONArray(TestFormatJsonMapping.WEB_SOURCES_KEY);
            for (int j = 0; j < webSourceRegistrationArray.length(); j++) {
                RegisterWebSource webSource =
                        new RegisterWebSource(webSourceRegistrationArray.getJSONObject(j));
                actions.add(webSource);
                // Add corresponding reporting job time actions
                eventReportingJobActions.addAll(
                        maybeAddEventReportingJobTimes(
                                webSource.mRegistrationRequest.getSourceRegistrationRequest()
                                        .getInputEvent() == null,
                                webSource.mTimestamp,
                                webSource.mUriToResponseHeadersMap.values()));
            }
        }

        actions.addAll(eventReportingJobActions);
        return actions;
    }

    private static List<Action> createTriggerBasedActions(JSONObject input) throws JSONException {
        List<Action> actions = new ArrayList<>();
        long firstTriggerTime = Long.MAX_VALUE;
        long lastTriggerTime = -1;
        if (!input.isNull(TestFormatJsonMapping.TRIGGER_KEY)) {
            JSONArray triggerRegistrationArray =
                    input.getJSONArray(TestFormatJsonMapping.TRIGGER_KEY);
            for (int j = 0; j < triggerRegistrationArray.length(); j++) {
                RegisterTrigger triggerRegistration =
                        new RegisterTrigger(triggerRegistrationArray.getJSONObject(j));
                actions.add(triggerRegistration);
                firstTriggerTime = Math.min(firstTriggerTime, triggerRegistration.mTimestamp);
                lastTriggerTime = Math.max(lastTriggerTime, triggerRegistration.mTimestamp);
            }
        }

        if (!input.isNull(TestFormatJsonMapping.WEB_TRIGGERS_KEY)) {
            JSONArray webTriggerRegistrationArray =
                    input.getJSONArray(TestFormatJsonMapping.WEB_TRIGGERS_KEY);
            for (int j = 0; j < webTriggerRegistrationArray.length(); j++) {
                RegisterWebTrigger webTrigger =
                        new RegisterWebTrigger(webTriggerRegistrationArray.getJSONObject(j));
                actions.add(webTrigger);
                firstTriggerTime = Math.min(firstTriggerTime, webTrigger.mTimestamp);
                lastTriggerTime = Math.max(lastTriggerTime, webTrigger.mTimestamp);
            }
        }

        // Aggregate reports are scheduled close to trigger time. Add aggregate report jobs to cover
        // the time span outlined by triggers.
        List<Action> aggregateReportingJobActions = new ArrayList<>();
        long window = SystemHealthParams.MAX_AGGREGATE_REPORT_UPLOAD_RETRY_WINDOW_MS - 10;
        long t = firstTriggerTime;

        do {
            t += window;
            aggregateReportingJobActions.add(new AggregateReportingJob(t));
        } while (t <= lastTriggerTime);

        // Account for edge case of t between lastTriggerTime and the latter's max report delay.
        if (t <= lastTriggerTime + PrivacyParams.AGGREGATE_MAX_REPORT_DELAY) {
            // t must be greater than lastTriggerTime so adding max report
            // delay should be beyond the report delay for lastTriggerTime.
            aggregateReportingJobActions.add(new AggregateReportingJob(t
                    + PrivacyParams.AGGREGATE_MAX_REPORT_DELAY));
        }

        actions.addAll(aggregateReportingJobActions);
        return actions;
    }

    private static List<Action> createInstallActions(JSONObject input) throws JSONException {
        List<Action> actions = new ArrayList<>();
        if (!input.isNull(TestFormatJsonMapping.INSTALLS_KEY)) {
            JSONArray installsArray = input.getJSONArray(TestFormatJsonMapping.INSTALLS_KEY);
            for (int j = 0; j < installsArray.length(); j++) {
                InstallApp installApp = new InstallApp(installsArray.getJSONObject(j));
                actions.add(installApp);
            }
        }

        return actions;
    }

    private static List<Action> createUninstallActions(JSONObject input) throws JSONException {
        List<Action> actions = new ArrayList<>();
        if (!input.isNull(TestFormatJsonMapping.UNINSTALLS_KEY)) {
            JSONArray uninstallsArray = input.getJSONArray(TestFormatJsonMapping.UNINSTALLS_KEY);
            for (int j = 0; j < uninstallsArray.length(); j++) {
                UninstallApp uninstallApp = new UninstallApp(uninstallsArray.getJSONObject(j));
                actions.add(uninstallApp);
            }
        }

        return actions;
    }

    private static ReportObjects getExpectedOutput(JSONObject output) throws JSONException {
        List<JSONObject> eventReportObjects = new ArrayList<>();
        if (!output.isNull(TestFormatJsonMapping.EVENT_REPORT_OBJECTS_KEY)) {
            JSONArray eventReportObjectsArray = output.getJSONArray(
                    TestFormatJsonMapping.EVENT_REPORT_OBJECTS_KEY);
            for (int i = 0; i < eventReportObjectsArray.length(); i++) {
                JSONObject obj = eventReportObjectsArray.getJSONObject(i);
                String adTechDomain = obj.getString(TestFormatJsonMapping.REPORT_TO_KEY);
                eventReportObjects.add(obj.put(TestFormatJsonMapping.REPORT_TO_KEY, adTechDomain));
            }
        }

        List<JSONObject> aggregateReportObjects = new ArrayList<>();
        if (!output.isNull(TestFormatJsonMapping.AGGREGATE_REPORT_OBJECTS_KEY)) {
            JSONArray aggregateReportObjectsArray =
                    output.getJSONArray(TestFormatJsonMapping.AGGREGATE_REPORT_OBJECTS_KEY);
            for (int i = 0; i < aggregateReportObjectsArray.length(); i++) {
                aggregateReportObjects.add(aggregateReportObjectsArray.getJSONObject(i));
            }
        }

        List<JSONObject> debugEventReportObjects = new ArrayList<>();
        if (!output.isNull(TestFormatJsonMapping.DEBUG_EVENT_REPORT_OBJECTS_KEY)) {
            JSONArray debugEventReportObjectsArray =
                    output.getJSONArray(TestFormatJsonMapping.DEBUG_EVENT_REPORT_OBJECTS_KEY);
            for (int i = 0; i < debugEventReportObjectsArray.length(); i++) {
                JSONObject obj = debugEventReportObjectsArray.getJSONObject(i);
                String adTechDomain = obj.getString(TestFormatJsonMapping.REPORT_TO_KEY);
                debugEventReportObjects.add(
                        obj.put(TestFormatJsonMapping.REPORT_TO_KEY, adTechDomain));
            }
        }

        List<JSONObject> debugAggregateReportObjects = new ArrayList<>();
        if (!output.isNull(TestFormatJsonMapping.DEBUG_AGGREGATE_REPORT_OBJECTS_KEY)) {
            JSONArray debugAggregateReportObjectsArray =
                    output.getJSONArray(TestFormatJsonMapping.DEBUG_AGGREGATE_REPORT_OBJECTS_KEY);
            for (int i = 0; i < debugAggregateReportObjectsArray.length(); i++) {
                debugAggregateReportObjects.add(debugAggregateReportObjectsArray.getJSONObject(i));
            }
        }
        List<JSONObject> debugReportObjects = new ArrayList<>();
        if (!output.isNull(TestFormatJsonMapping.DEBUG_REPORT_API_OBJECTS_KEY)) {
            JSONArray debugReportObjectsArray =
                    output.getJSONArray(TestFormatJsonMapping.DEBUG_REPORT_API_OBJECTS_KEY);
            for (int i = 0; i < debugReportObjectsArray.length(); i++) {
                debugReportObjects.add(debugReportObjectsArray.getJSONObject(i));
            }
        }

        return new ReportObjects(
                eventReportObjects,
                aggregateReportObjects,
                debugEventReportObjects,
                debugAggregateReportObjects,
                debugReportObjects);
    }

    /**
     * Empties measurement database tables, used for test cleanup.
     */
    private static void emptyTables(SQLiteDatabase db) {
        db.delete("msmt_source", null, null);
        db.delete("msmt_trigger", null, null);
        db.delete("msmt_event_report", null, null);
        db.delete("msmt_attribution", null, null);
        db.delete("msmt_aggregate_report", null, null);
        db.delete("msmt_async_registration_contract", null, null);
    }

    abstract void processAction(RegisterSource sourceRegistration)
            throws IOException, JSONException;

    abstract void processAction(RegisterWebSource sourceRegistration)
            throws IOException, JSONException;

    abstract void processAction(RegisterTrigger triggerRegistration)
            throws IOException, JSONException;

    abstract void processAction(RegisterWebTrigger triggerRegistration)
            throws IOException, JSONException;

    abstract void processAction(InstallApp installApp);

    abstract void processAction(UninstallApp uninstallApp);

    void evaluateResults() throws JSONException {
        sortEventReportObjects(OutputType.EXPECTED, mExpectedOutput.mEventReportObjects);
        sortEventReportObjects(OutputType.ACTUAL, mActualOutput.mEventReportObjects);
        sortAggregateReportObjects(OutputType.EXPECTED, mExpectedOutput.mAggregateReportObjects);
        sortAggregateReportObjects(OutputType.ACTUAL, mActualOutput.mAggregateReportObjects);
        sortEventReportObjects(OutputType.EXPECTED, mExpectedOutput.mDebugEventReportObjects);
        sortEventReportObjects(OutputType.ACTUAL, mActualOutput.mDebugEventReportObjects);
        sortAggregateReportObjects(
                OutputType.EXPECTED, mExpectedOutput.mDebugAggregateReportObjects);
        sortAggregateReportObjects(OutputType.ACTUAL, mActualOutput.mDebugAggregateReportObjects);
        sortDebugReportObjects(mExpectedOutput.mDebugReportObjects);
        sortDebugReportObjects(mActualOutput.mDebugReportObjects);
        Assert.assertTrue(getTestFailureMessage(mExpectedOutput, mActualOutput),
                areEqual(mExpectedOutput, mActualOutput));
    }

    private void setupDeviceConfigForPhFlags() {
        mPhFlagsMap
                .keySet()
                .forEach(
                        key ->
                                DeviceConfig.setProperty(
                                        DeviceConfig.NAMESPACE_ADSERVICES,
                                        key,
                                        mPhFlagsMap.get(key),
                                        false));
    }
}
