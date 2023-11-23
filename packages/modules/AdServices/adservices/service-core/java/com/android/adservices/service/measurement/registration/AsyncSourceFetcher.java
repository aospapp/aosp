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

import static com.android.adservices.service.measurement.PrivacyParams.MAX_DISTINCT_WEB_DESTINATIONS_IN_SOURCE_REGISTRATION;
import static com.android.adservices.service.measurement.PrivacyParams.MAX_INSTALL_ATTRIBUTION_WINDOW;
import static com.android.adservices.service.measurement.PrivacyParams.MAX_POST_INSTALL_EXCLUSIVITY_WINDOW;
import static com.android.adservices.service.measurement.PrivacyParams.MAX_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS;
import static com.android.adservices.service.measurement.PrivacyParams.MIN_INSTALL_ATTRIBUTION_WINDOW;
import static com.android.adservices.service.measurement.PrivacyParams.MIN_POST_INSTALL_EXCLUSIVITY_WINDOW;
import static com.android.adservices.service.measurement.PrivacyParams.MIN_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS;
import static com.android.adservices.service.measurement.SystemHealthParams.MAX_AGGREGATE_KEYS_PER_REGISTRATION;
import static com.android.adservices.service.measurement.util.BaseUriExtractor.getBaseUri;
import static com.android.adservices.service.measurement.util.MathUtils.extractValidNumberInRange;

import static java.lang.Math.min;

import android.annotation.NonNull;
import android.content.Context;
import android.net.Uri;

import com.android.adservices.LogUtil;
import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.AllowLists;
import com.android.adservices.service.measurement.EventSurfaceType;
import com.android.adservices.service.measurement.MeasurementHttpClient;
import com.android.adservices.service.measurement.Source;
import com.android.adservices.service.measurement.util.Enrollment;
import com.android.adservices.service.measurement.util.UnsignedLong;
import com.android.adservices.service.measurement.util.Web;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesLoggerImpl;
import com.android.internal.annotations.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Download and decode Response Based Registration
 *
 * @hide
 */
public class AsyncSourceFetcher {

    private static final long ONE_DAY_IN_SECONDS = TimeUnit.DAYS.toSeconds(1);
    private static final String DEFAULT_ANDROID_APP_SCHEME = "android-app";
    private static final String DEFAULT_ANDROID_APP_URI_PREFIX = DEFAULT_ANDROID_APP_SCHEME + "://";
    private final MeasurementHttpClient mNetworkConnection = new MeasurementHttpClient();
    private final EnrollmentDao mEnrollmentDao;
    private final Flags mFlags;
    private final AdServicesLogger mLogger;
    private final Context mContext;

    public AsyncSourceFetcher(Context context) {
        this(
                context,
                EnrollmentDao.getInstance(context),
                FlagsFactory.getFlags(),
                AdServicesLoggerImpl.getInstance());
    }

    @VisibleForTesting
    public AsyncSourceFetcher(
            Context context, EnrollmentDao enrollmentDao, Flags flags, AdServicesLogger logger) {
        mContext = context;
        mEnrollmentDao = enrollmentDao;
        mFlags = flags;
        mLogger = logger;
    }

    private boolean parseCommonSourceParams(
            JSONObject json,
            AsyncRegistration asyncRegistration,
            Source.Builder builder,
            String enrollmentId)
            throws JSONException {
        if (!hasRequiredParams(json)) {
            throw new JSONException(
                    String.format(
                            "Expected %s and a destination", SourceHeaderContract.SOURCE_EVENT_ID));
        }
        long sourceEventTime = asyncRegistration.getRequestTime();
        UnsignedLong eventId = new UnsignedLong(0L);
        if (!json.isNull(SourceHeaderContract.SOURCE_EVENT_ID)) {
            try {
                eventId = new UnsignedLong(json.getString(SourceHeaderContract.SOURCE_EVENT_ID));
            } catch (NumberFormatException e) {
                LogUtil.d(e, "parseCommonSourceParams: parsing source_event_id failed.");
            }
        }
        builder.setEventId(eventId);
        long expiry;
        if (!json.isNull(SourceHeaderContract.EXPIRY)) {
            expiry =
                    extractValidNumberInRange(
                            json.getLong(SourceHeaderContract.EXPIRY),
                            MIN_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS,
                            MAX_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS);
            if (asyncRegistration.getSourceType() == Source.SourceType.EVENT) {
                expiry = roundSecondsToWholeDays(expiry);
            }
        } else {
            expiry = MAX_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS;
        }
        builder.setExpiryTime(
                asyncRegistration.getRequestTime() + TimeUnit.SECONDS.toMillis(expiry));
        long eventReportWindow;
        if (!json.isNull(SourceHeaderContract.EVENT_REPORT_WINDOW)) {
            eventReportWindow =
                    Math.min(
                            expiry,
                            extractValidNumberInRange(
                                    json.getLong(SourceHeaderContract.EVENT_REPORT_WINDOW),
                                    MIN_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS,
                                    MAX_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS));
        } else {
            eventReportWindow = expiry;
        }
        builder.setEventReportWindow(
                sourceEventTime + TimeUnit.SECONDS.toMillis(eventReportWindow));
        long aggregateReportWindow;
        if (!json.isNull(SourceHeaderContract.AGGREGATABLE_REPORT_WINDOW)) {
            aggregateReportWindow =
                    min(
                            expiry,
                            extractValidNumberInRange(
                                    json.getLong(SourceHeaderContract.AGGREGATABLE_REPORT_WINDOW),
                                    MIN_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS,
                                    MAX_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS));
        } else {
            aggregateReportWindow = expiry;
        }
        builder.setAggregatableReportWindow(
                sourceEventTime + TimeUnit.SECONDS.toMillis(aggregateReportWindow));
        if (!json.isNull(SourceHeaderContract.PRIORITY)) {
            builder.setPriority(json.getLong(SourceHeaderContract.PRIORITY));
        }
        if (!json.isNull(SourceHeaderContract.DEBUG_REPORTING)) {
            builder.setIsDebugReporting(json.optBoolean(SourceHeaderContract.DEBUG_REPORTING));
        }
        if (!json.isNull(SourceHeaderContract.DEBUG_KEY)) {
            try {
                builder.setDebugKey(
                        new UnsignedLong(json.getString(SourceHeaderContract.DEBUG_KEY)));
            } catch (NumberFormatException e) {
                LogUtil.e(e, "parseCommonSourceParams: parsing debug key failed");
            }
        }
        if (!json.isNull(SourceHeaderContract.INSTALL_ATTRIBUTION_WINDOW_KEY)) {
            long installAttributionWindow =
                    extractValidNumberInRange(
                            json.getLong(SourceHeaderContract.INSTALL_ATTRIBUTION_WINDOW_KEY),
                            MIN_INSTALL_ATTRIBUTION_WINDOW,
                            MAX_INSTALL_ATTRIBUTION_WINDOW);
            builder.setInstallAttributionWindow(
                    TimeUnit.SECONDS.toMillis(installAttributionWindow));
        } else {
            builder.setInstallAttributionWindow(
                    TimeUnit.SECONDS.toMillis(MAX_INSTALL_ATTRIBUTION_WINDOW));
        }
        if (!json.isNull(SourceHeaderContract.POST_INSTALL_EXCLUSIVITY_WINDOW_KEY)) {
            long installCooldownWindow =
                    extractValidNumberInRange(
                            json.getLong(SourceHeaderContract.POST_INSTALL_EXCLUSIVITY_WINDOW_KEY),
                            MIN_POST_INSTALL_EXCLUSIVITY_WINDOW,
                            MAX_POST_INSTALL_EXCLUSIVITY_WINDOW);
            builder.setInstallCooldownWindow(TimeUnit.SECONDS.toMillis(installCooldownWindow));
        } else {
            builder.setInstallCooldownWindow(
                    TimeUnit.SECONDS.toMillis(MIN_POST_INSTALL_EXCLUSIVITY_WINDOW));
        }
        // This "filter_data" field is used to generate reports.
        if (!json.isNull(SourceHeaderContract.FILTER_DATA)) {
            if (!FetcherUtil.areValidAttributionFilters(
                    json.optJSONObject(SourceHeaderContract.FILTER_DATA))) {
                LogUtil.d("Source filter-data is invalid.");
                return false;
            }
            builder.setFilterData(json.getJSONObject(SourceHeaderContract.FILTER_DATA).toString());
        }

        Uri appUri = null;
        if (!json.isNull(SourceHeaderContract.DESTINATION)) {
            appUri = Uri.parse(json.getString(SourceHeaderContract.DESTINATION));
            if (appUri.getScheme() == null) {
                LogUtil.d("App destination is missing app scheme, adding.");
                appUri = Uri.parse(DEFAULT_ANDROID_APP_URI_PREFIX + appUri);
            }
            if (!DEFAULT_ANDROID_APP_SCHEME.equals(appUri.getScheme())) {
                LogUtil.e(
                        "Invalid scheme for app destination: %s; dropping the source.",
                        appUri.getScheme());
                return false;
            }
        }

        String enrollmentBlockList =
                mFlags.getMeasurementPlatformDebugAdIdMatchingEnrollmentBlocklist();
        Set<String> blockedEnrollmentsString =
                new HashSet<>(AllowLists.splitAllowList(enrollmentBlockList));
        if (!AllowLists.doesAllowListAllowAll(enrollmentBlockList)
                && !blockedEnrollmentsString.contains(enrollmentId)
                && !json.isNull(SourceHeaderContract.DEBUG_AD_ID)) {
            builder.setDebugAdId(json.optString(SourceHeaderContract.DEBUG_AD_ID));
        }

        Set<String> allowedEnrollmentsString =
                new HashSet<>(
                        AllowLists.splitAllowList(
                                mFlags.getMeasurementDebugJoinKeyEnrollmentAllowlist()));
        if (allowedEnrollmentsString.contains(enrollmentId)
                && !json.isNull(SourceHeaderContract.DEBUG_JOIN_KEY)) {
            builder.setDebugJoinKey(json.optString(SourceHeaderContract.DEBUG_JOIN_KEY));
        }

        if (asyncRegistration.isWebRequest()
                // Only validate when non-null in request
                && asyncRegistration.getOsDestination() != null
                && !asyncRegistration.getOsDestination().equals(appUri)) {
            LogUtil.d("Expected destination to match with the supplied one!");
            return false;
        }

        if (appUri != null) {
            builder.setAppDestinations(Collections.singletonList(getBaseUri(appUri)));
        }

        boolean shouldMatchAtLeastOneWebDestination =
                asyncRegistration.isWebRequest() && asyncRegistration.getWebDestination() != null;
        boolean matchedOneWebDestination = false;

        if (!json.isNull(SourceHeaderContract.WEB_DESTINATION)) {
            Set<Uri> destinationSet = new HashSet<>();
            JSONArray jsonDestinations;
            Object obj = json.get(SourceHeaderContract.WEB_DESTINATION);
            if (obj instanceof String) {
                jsonDestinations = new JSONArray();
                jsonDestinations.put(json.getString(SourceHeaderContract.WEB_DESTINATION));
            } else {
                jsonDestinations = json.getJSONArray(SourceHeaderContract.WEB_DESTINATION);
            }
            if (jsonDestinations.length() > MAX_DISTINCT_WEB_DESTINATIONS_IN_SOURCE_REGISTRATION) {
                LogUtil.d("Source registration exceeded the number of allowed destinations.");
                return false;
            }
            for (int i = 0; i < jsonDestinations.length(); i++) {
                Uri destination = Uri.parse(jsonDestinations.getString(i));
                if (shouldMatchAtLeastOneWebDestination
                        && asyncRegistration.getWebDestination().equals(destination)) {
                    matchedOneWebDestination = true;
                }
                Optional<Uri> topPrivateDomainAndScheme =
                        Web.topPrivateDomainAndScheme(destination);
                if (topPrivateDomainAndScheme.isEmpty()) {
                    LogUtil.d("Unable to extract top private domain and scheme from web "
                            + "destination.");
                    return false;
                } else {
                    destinationSet.add(topPrivateDomainAndScheme.get());
                }
            }
            List<Uri> destinationList = new ArrayList<>(destinationSet);
            builder.setWebDestinations(destinationList);
        }

        if (mFlags.getMeasurementEnableCoarseEventReportDestinations()
                && !json.isNull(SourceHeaderContract.COARSE_EVENT_REPORT_DESTINATIONS)) {
            builder.setCoarseEventReportDestinations(
                    json.getBoolean(SourceHeaderContract.COARSE_EVENT_REPORT_DESTINATIONS));
        }

        if (shouldMatchAtLeastOneWebDestination && !matchedOneWebDestination) {
            LogUtil.d("Expected at least one web_destination to match with the supplied one!");
            return false;
        }

        return true;
    }

    /** Parse a {@code Source}, given response headers, adding the {@code Source} to a given list */
    @VisibleForTesting
    public Optional<Source> parseSource(
            AsyncRegistration asyncRegistration,
            String enrollmentId,
            Map<String, List<String>> headers,
            AsyncFetchStatus asyncFetchStatus) {
        boolean arDebugPermission = asyncRegistration.getDebugKeyAllowed();
        LogUtil.d("Source ArDebug permission enabled %b", arDebugPermission);
        Source.Builder builder = new Source.Builder();
        builder.setRegistrationId(asyncRegistration.getRegistrationId());
        builder.setPublisher(getBaseUri(asyncRegistration.getTopOrigin()));
        builder.setEnrollmentId(enrollmentId);
        builder.setRegistrant(asyncRegistration.getRegistrant());
        builder.setSourceType(asyncRegistration.getSourceType());
        builder.setAttributionMode(Source.AttributionMode.TRUTHFULLY);
        builder.setEventTime(asyncRegistration.getRequestTime());
        builder.setAdIdPermission(asyncRegistration.hasAdIdPermission());
        builder.setArDebugPermission(arDebugPermission);
        builder.setPublisherType(
                asyncRegistration.isWebRequest() ? EventSurfaceType.WEB : EventSurfaceType.APP);
        Optional<Uri> registrationUriOrigin =
                Web.originAndScheme(asyncRegistration.getRegistrationUri());
        if (!registrationUriOrigin.isPresent()) {
            LogUtil.d(
                    "AsyncSourceFetcher: "
                            + "Invalid or empty registration uri - "
                            + asyncRegistration.getRegistrationUri());
            return Optional.empty();
        }
        builder.setRegistrationOrigin(registrationUriOrigin.get());

        builder.setPlatformAdId(
                FetcherUtil.getEncryptedPlatformAdIdIfPresent(asyncRegistration, enrollmentId));

        List<String> field =
                headers.get(SourceHeaderContract.HEADER_ATTRIBUTION_REPORTING_REGISTER_SOURCE);
        if (field == null || field.size() != 1) {
            LogUtil.d(
                    "AsyncSourceFetcher: "
                            + "Invalid Attribution-Reporting-Register-Source header.");
            asyncFetchStatus.setEntityStatus(AsyncFetchStatus.EntityStatus.HEADER_ERROR);
            return Optional.empty();
        }
        try {
            JSONObject json = new JSONObject(field.get(0));
            boolean isValid =
                    parseCommonSourceParams(json, asyncRegistration, builder, enrollmentId);
            if (!isValid) {
                asyncFetchStatus.setEntityStatus(AsyncFetchStatus.EntityStatus.VALIDATION_ERROR);
                return Optional.empty();
            }
            if (!json.isNull(SourceHeaderContract.AGGREGATION_KEYS)) {
                if (!areValidAggregationKeys(
                        json.getJSONObject(SourceHeaderContract.AGGREGATION_KEYS))) {
                    asyncFetchStatus.setEntityStatus(
                            AsyncFetchStatus.EntityStatus.VALIDATION_ERROR);
                    return Optional.empty();
                }
                builder.setAggregateSource(json.getString(SourceHeaderContract.AGGREGATION_KEYS));
            }
            if (mFlags.getMeasurementEnableXNA()
                    && !json.isNull(SourceHeaderContract.SHARED_AGGREGATION_KEYS)) {
                // Parsed as JSONArray for validation
                JSONArray sharedAggregationKeys =
                        json.getJSONArray(SourceHeaderContract.SHARED_AGGREGATION_KEYS);
                builder.setSharedAggregationKeys(sharedAggregationKeys.toString());
            }
            asyncFetchStatus.setEntityStatus(AsyncFetchStatus.EntityStatus.SUCCESS);
            return Optional.of(builder.build());
        } catch (JSONException | NumberFormatException e) {
            LogUtil.d(e, "AsyncSourceFetcher: Invalid JSON");
            asyncFetchStatus.setEntityStatus(AsyncFetchStatus.EntityStatus.PARSING_ERROR);
            return Optional.empty();
        }
    }

    private static boolean hasRequiredParams(JSONObject json) {
        return !json.isNull(SourceHeaderContract.DESTINATION)
                || !json.isNull(SourceHeaderContract.WEB_DESTINATION);
    }

    /** Provided a testing hook. */
    @NonNull
    @VisibleForTesting
    public URLConnection openUrl(@NonNull URL url) throws IOException {
        return mNetworkConnection.setup(url);
    }

    /**
     * Fetch a source type registration.
     *
     * @param asyncRegistration a {@link AsyncRegistration}, a request the record.
     * @param asyncFetchStatus a {@link AsyncFetchStatus}, stores Ad Tech server status.
     */
    public Optional<Source> fetchSource(
            AsyncRegistration asyncRegistration,
            AsyncFetchStatus asyncFetchStatus,
            AsyncRedirect asyncRedirect) {
        HttpURLConnection urlConnection = null;
        Map<String, List<String>> headers;
        // TODO(b/276825561): Fix code duplication between fetchSource & fetchTrigger request flow
        try {
            urlConnection =
                    (HttpURLConnection)
                            openUrl(new URL(asyncRegistration.getRegistrationUri().toString()));
            urlConnection.setRequestMethod("POST");
            urlConnection.setRequestProperty(
                    SourceRequestContract.SOURCE_INFO,
                    asyncRegistration.getSourceType().toString());
            urlConnection.setInstanceFollowRedirects(false);
            headers = urlConnection.getHeaderFields();
            asyncFetchStatus.setResponseSize(FetcherUtil.calculateHeadersCharactersLength(headers));
            int responseCode = urlConnection.getResponseCode();
            LogUtil.d("Response code = " + responseCode);
            if (!FetcherUtil.isRedirect(responseCode) && !FetcherUtil.isSuccess(responseCode)) {
                asyncFetchStatus.setResponseStatus(
                        AsyncFetchStatus.ResponseStatus.SERVER_UNAVAILABLE);
                return Optional.empty();
            }
            asyncFetchStatus.setResponseStatus(AsyncFetchStatus.ResponseStatus.SUCCESS);
        } catch (MalformedURLException e) {
            LogUtil.d(e, "Malformed registration target URL");
            asyncFetchStatus.setResponseStatus(AsyncFetchStatus.ResponseStatus.INVALID_URL);
            return Optional.empty();
        } catch (IOException e) {
            LogUtil.e(e, "Failed to get registration response");
            asyncFetchStatus.setResponseStatus(AsyncFetchStatus.ResponseStatus.NETWORK_ERROR);
            return Optional.empty();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }

        if (asyncRegistration.shouldProcessRedirects()) {
            FetcherUtil.parseRedirects(headers).forEach(asyncRedirect::addToRedirects);
        }

        if (!isSourceHeaderPresent(headers)) {
            asyncFetchStatus.setEntityStatus(AsyncFetchStatus.EntityStatus.HEADER_MISSING);
            return Optional.empty();
        }

        Optional<String> enrollmentId =
                mFlags.isDisableMeasurementEnrollmentCheck()
                        ? Optional.of(Enrollment.FAKE_ENROLLMENT)
                        : Enrollment.getValidEnrollmentId(
                                asyncRegistration.getRegistrationUri(),
                                asyncRegistration.getRegistrant().getAuthority(),
                                mEnrollmentDao,
                                mContext,
                                mFlags);
        if (enrollmentId.isEmpty()) {
            LogUtil.d(
                    "fetchSource: Valid enrollment id not found. Registration URI: %s",
                    asyncRegistration.getRegistrationUri());
            asyncFetchStatus.setEntityStatus(AsyncFetchStatus.EntityStatus.INVALID_ENROLLMENT);
            return Optional.empty();
        }

        Optional<Source> parsedSource =
                parseSource(asyncRegistration, enrollmentId.get(), headers, asyncFetchStatus);
        return parsedSource;
    }

    private boolean isSourceHeaderPresent(Map<String, List<String>> headers) {
        return headers.containsKey(
                SourceHeaderContract.HEADER_ATTRIBUTION_REPORTING_REGISTER_SOURCE);
    }

    private boolean areValidAggregationKeys(JSONObject aggregationKeys) {
        if (aggregationKeys.length() > MAX_AGGREGATE_KEYS_PER_REGISTRATION) {
            LogUtil.d(
                    "Aggregation-keys have more entries than permitted. %s",
                    aggregationKeys.length());
            return false;
        }
        for (String id : aggregationKeys.keySet()) {
            if (!FetcherUtil.isValidAggregateKeyId(id)) {
                LogUtil.d("SourceFetcher: aggregation key ID is invalid. %s", id);
                return false;
            }
            String keyPiece = aggregationKeys.optString(id);
            if (!FetcherUtil.isValidAggregateKeyPiece(keyPiece)) {
                LogUtil.d("SourceFetcher: aggregation key-piece is invalid. %s", keyPiece);
                return false;
            }
        }
        return true;
    }

    private static long roundSecondsToWholeDays(long seconds) {
        long remainder = seconds % ONE_DAY_IN_SECONDS;
        boolean roundUp = remainder >= ONE_DAY_IN_SECONDS / 2L;
        return seconds - remainder + (roundUp ? ONE_DAY_IN_SECONDS : 0);
    }

    private interface SourceHeaderContract {
        String HEADER_ATTRIBUTION_REPORTING_REGISTER_SOURCE =
                "Attribution-Reporting-Register-Source";
        String SOURCE_EVENT_ID = "source_event_id";
        String DEBUG_KEY = "debug_key";
        String DESTINATION = "destination";
        String EXPIRY = "expiry";
        String EVENT_REPORT_WINDOW = "event_report_window";
        String AGGREGATABLE_REPORT_WINDOW = "aggregatable_report_window";
        String PRIORITY = "priority";
        String INSTALL_ATTRIBUTION_WINDOW_KEY = "install_attribution_window";
        String POST_INSTALL_EXCLUSIVITY_WINDOW_KEY = "post_install_exclusivity_window";
        String FILTER_DATA = "filter_data";
        String WEB_DESTINATION = "web_destination";
        String AGGREGATION_KEYS = "aggregation_keys";
        String SHARED_AGGREGATION_KEYS = "shared_aggregation_keys";
        String DEBUG_REPORTING = "debug_reporting";
        String DEBUG_JOIN_KEY = "debug_join_key";
        String DEBUG_AD_ID = "debug_ad_id";
        String COARSE_EVENT_REPORT_DESTINATIONS = "coarse_event_report_destinations";
    }

    private interface SourceRequestContract {
        String SOURCE_INFO = "Attribution-Reporting-Source-Info";
    }
}
