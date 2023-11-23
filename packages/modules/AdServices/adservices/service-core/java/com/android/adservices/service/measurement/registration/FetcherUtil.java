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
import static com.android.adservices.service.measurement.SystemHealthParams.MAX_BYTES_PER_ATTRIBUTION_FILTER_STRING;
import static com.android.adservices.service.measurement.SystemHealthParams.MAX_FILTER_MAPS_PER_FILTER_SET;
import static com.android.adservices.service.measurement.SystemHealthParams.MAX_VALUES_PER_ATTRIBUTION_FILTER;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_REGISTRATIONS;

import android.annotation.NonNull;
import android.net.Uri;

import com.android.adservices.LogUtil;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.measurement.Source;
import com.android.adservices.service.measurement.util.AdIdEncryption;
import com.android.adservices.service.measurement.util.Web;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.MeasurementRegistrationResponseStats;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Common handling for Response Based Registration
 *
 * @hide
 */
class FetcherUtil {
    static final String REDIRECT_LIST_HEADER_KEY = "Attribution-Reporting-Redirect";
    static final String REDIRECT_LOCATION_HEADER_KEY = "Location";

    /**
     * Determine all redirects.
     *
     * <p>Generates a list of: (url, allows_regular_redirects) tuples. Returns true if all steps
     * succeed. Returns false if there are any failures.
     */
    static Map<AsyncRegistration.RedirectType, List<Uri>> parseRedirects(
            @NonNull Map<String, List<String>> headers) {
        Map<AsyncRegistration.RedirectType, List<Uri>> uriMap = new HashMap<>();
        uriMap.put(AsyncRegistration.RedirectType.LOCATION, parseLocationRedirects(headers));
        uriMap.put(AsyncRegistration.RedirectType.LIST, parseListRedirects(headers));
        return uriMap;
    }

    /**
     * Check HTTP response codes that indicate a redirect.
     */
    static boolean isRedirect(int responseCode) {
        return (responseCode / 100) == 3;
    }

    /**
     * Check HTTP response code for success.
     */
    static boolean isSuccess(int responseCode) {
        return (responseCode / 100) == 2;
    }

    /**
     * Validate aggregate key ID.
     */
    static boolean isValidAggregateKeyId(String id) {
        return id != null && id.getBytes().length <= MAX_BYTES_PER_ATTRIBUTION_AGGREGATE_KEY_ID;
    }

    /** Validate aggregate deduplication key. */
    static boolean isValidAggregateDeduplicationKey(String deduplicationKey) {
        if (deduplicationKey == null) {
            return false;
        }
        try {
            Long.parseUnsignedLong(deduplicationKey);
        } catch (NumberFormatException exception) {
            return false;
        }
        return true;
    }

    /**
     * Validate aggregate key-piece.
     */
    static boolean isValidAggregateKeyPiece(String keyPiece) {
        if (keyPiece == null) {
            return false;
        }
        int length = keyPiece.getBytes().length;
        // Key-piece is restricted to a maximum of 128 bits and the hex strings therefore have at
        // most 32 digits.
        return (keyPiece.startsWith("0x") || keyPiece.startsWith("0X"))
                && 2 < length && length < 35;
    }

    /**
     * Validate attribution filters JSONArray.
     */
    static boolean areValidAttributionFilters(@NonNull JSONArray filterSet) {
        if (filterSet.length() > MAX_FILTER_MAPS_PER_FILTER_SET) {
            return false;
        }
        for (int i = 0; i < filterSet.length(); i++) {
            if (!areValidAttributionFilters(filterSet.optJSONObject(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validate attribution filters JSONObject.
     */
    static boolean areValidAttributionFilters(JSONObject filtersObj) {
        if (filtersObj == null || filtersObj.length() > MAX_ATTRIBUTION_FILTERS) {
            return false;
        }
        Iterator<String> keys = filtersObj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.getBytes().length > MAX_BYTES_PER_ATTRIBUTION_FILTER_STRING) {
                return false;
            }
            JSONArray values = filtersObj.optJSONArray(key);
            if (values == null || values.length() > MAX_VALUES_PER_ATTRIBUTION_FILTER) {
                return false;
            }
            for (int i = 0; i < values.length(); i++) {
                String value = values.optString(i);
                if (value == null
                        || value.getBytes().length > MAX_BYTES_PER_ATTRIBUTION_FILTER_STRING) {
                    return false;
                }
            }
        }
        return true;
    }

    static void emitHeaderMetrics(
            Flags flags,
            AdServicesLogger logger,
            AsyncRegistration asyncRegistration,
            AsyncFetchStatus asyncFetchStatus) {
        long headerSize = 0;
        if (asyncFetchStatus.getResponseSize().isPresent()) {
            headerSize = asyncFetchStatus.getResponseSize().get();
        }
        long maxSize = flags.getMaxResponseBasedRegistrationPayloadSizeBytes();
        String adTechDomain = null;

        if (headerSize > maxSize) {
            adTechDomain =
                    Web.topPrivateDomainAndScheme(asyncRegistration.getRegistrationUri())
                            .map(Uri::toString)
                            .orElse(null);
        }

        logger.logMeasurementRegistrationsResponseSize(
                new MeasurementRegistrationResponseStats.Builder(
                                AD_SERVICES_MEASUREMENT_REGISTRATIONS,
                                getRegistrationType(asyncRegistration),
                                headerSize,
                                getSourceType(asyncRegistration),
                                getSurfaceType(asyncRegistration),
                                getStatus(asyncFetchStatus),
                                getFailureType(asyncFetchStatus),
                                asyncFetchStatus.getRegistrationDelay().get())
                        .setAdTechDomain(adTechDomain)
                        .build());
    }

    private static List<Uri> parseListRedirects(Map<String, List<String>> headers) {
        List<Uri> redirects = new ArrayList<>();
        List<String> field = headers.get(REDIRECT_LIST_HEADER_KEY);
        int maxRedirects = FlagsFactory.getFlags().getMeasurementMaxRegistrationRedirects();
        if (field != null) {
            for (int i = 0; i < Math.min(field.size(), maxRedirects); i++) {
                redirects.add(Uri.parse(field.get(i)));
            }
        }
        return redirects;
    }

    private static List<Uri> parseLocationRedirects(Map<String, List<String>> headers) {
        List<Uri> redirects = new ArrayList<>();
        List<String> field = headers.get(REDIRECT_LOCATION_HEADER_KEY);
        if (field != null && !field.isEmpty()) {
            redirects.add(Uri.parse(field.get(0)));
            if (field.size() > 1) {
                LogUtil.d("Expected one Location redirect only, others ignored!");
            }
        }
        return redirects;
    }

    public static long calculateHeadersCharactersLength(Map<String, List<String>> headers) {
        long size = 0;
        for (String headerKey : headers.keySet()) {
            if (headerKey != null) {
                size = size + headerKey.length();
                List<String> headerValues = headers.get(headerKey);
                if (headerValues != null) {
                    for (String headerValue : headerValues) {
                        size = size + headerValue.length();
                    }
                }
            }
        }

        return size;
    }

    public static String getEncryptedPlatformAdIdIfPresent(
            AsyncRegistration asyncRegistration, String enrollmentId) {
        if (asyncRegistration.isAppRequest()
                && asyncRegistration.hasAdIdPermission()
                && asyncRegistration.getPlatformAdId() != null) {
            return AdIdEncryption.encryptAdIdAndEnrollmentSha256(
                    asyncRegistration.getPlatformAdId(), enrollmentId);
        } else {
            return null;
        }
    }

    private static int getRegistrationType(AsyncRegistration asyncRegistration) {
        if (asyncRegistration.getType() == AsyncRegistration.RegistrationType.APP_SOURCE
                || asyncRegistration.getType() == AsyncRegistration.RegistrationType.WEB_SOURCE) {
            return RegistrationEnumsValues.TYPE_SOURCE;
        } else if (asyncRegistration.getType() == AsyncRegistration.RegistrationType.APP_TRIGGER
                || asyncRegistration.getType() == AsyncRegistration.RegistrationType.WEB_TRIGGER) {
            return RegistrationEnumsValues.TYPE_TRIGGER;
        } else {
            return RegistrationEnumsValues.TYPE_UNKNOWN;
        }
    }

    private static int getSourceType(AsyncRegistration asyncRegistration) {
        if (asyncRegistration.getSourceType() == Source.SourceType.EVENT) {
            return RegistrationEnumsValues.SOURCE_TYPE_EVENT;
        } else if (asyncRegistration.getSourceType() == Source.SourceType.NAVIGATION) {
            return RegistrationEnumsValues.SOURCE_TYPE_NAVIGATION;
        } else {
            return RegistrationEnumsValues.SOURCE_TYPE_UNKNOWN;
        }
    }

    private static int getSurfaceType(AsyncRegistration asyncRegistration) {
        if (asyncRegistration.getType() == AsyncRegistration.RegistrationType.APP_SOURCE
                || asyncRegistration.getType() == AsyncRegistration.RegistrationType.APP_TRIGGER) {
            return RegistrationEnumsValues.SURFACE_TYPE_APP;
        } else if (asyncRegistration.getType() == AsyncRegistration.RegistrationType.WEB_SOURCE
                || asyncRegistration.getType() == AsyncRegistration.RegistrationType.WEB_TRIGGER) {
            return RegistrationEnumsValues.SURFACE_TYPE_WEB;
        } else {
            return RegistrationEnumsValues.SURFACE_TYPE_UNKNOWN;
        }
    }

    private static int getStatus(AsyncFetchStatus asyncFetchStatus) {
        if (asyncFetchStatus.getEntityStatus() == AsyncFetchStatus.EntityStatus.SUCCESS
                || (asyncFetchStatus.getResponseStatus() == AsyncFetchStatus.ResponseStatus.SUCCESS
                        && (asyncFetchStatus.getEntityStatus()
                                        == AsyncFetchStatus.EntityStatus.UNKNOWN
                                || asyncFetchStatus.getEntityStatus()
                                        == AsyncFetchStatus.EntityStatus.HEADER_MISSING))) {
            // successful source/trigger fetching/parsing and successful redirects (with no header)
            return RegistrationEnumsValues.STATUS_SUCCESS;
        } else if (asyncFetchStatus.getEntityStatus() == AsyncFetchStatus.EntityStatus.UNKNOWN
                && asyncFetchStatus.getResponseStatus()
                        == AsyncFetchStatus.ResponseStatus.UNKNOWN) {
            return RegistrationEnumsValues.STATUS_UNKNOWN;
        } else {
            return RegistrationEnumsValues.STATUS_FAILURE;
        }
    }

    private static int getFailureType(AsyncFetchStatus asyncFetchStatus) {
        if (asyncFetchStatus.getResponseStatus()
                        == AsyncFetchStatus.ResponseStatus.SERVER_UNAVAILABLE
                || asyncFetchStatus.getResponseStatus()
                        == AsyncFetchStatus.ResponseStatus.NETWORK_ERROR
                || asyncFetchStatus.getResponseStatus()
                        == AsyncFetchStatus.ResponseStatus.INVALID_URL) {
            return RegistrationEnumsValues.FAILURE_TYPE_NETWORK;
        } else if (asyncFetchStatus.getEntityStatus()
                == AsyncFetchStatus.EntityStatus.INVALID_ENROLLMENT) {
            return RegistrationEnumsValues.FAILURE_TYPE_ENROLLMENT;
        } else if (asyncFetchStatus.getEntityStatus()
                        == AsyncFetchStatus.EntityStatus.VALIDATION_ERROR
                || asyncFetchStatus.getEntityStatus() == AsyncFetchStatus.EntityStatus.PARSING_ERROR
                || asyncFetchStatus.getEntityStatus()
                        == AsyncFetchStatus.EntityStatus.HEADER_MISSING
                || asyncFetchStatus.getEntityStatus()
                        == AsyncFetchStatus.EntityStatus.HEADER_ERROR) {
            return RegistrationEnumsValues.FAILURE_TYPE_PARSING;
        } else if (asyncFetchStatus.getEntityStatus()
                == AsyncFetchStatus.EntityStatus.STORAGE_ERROR) {
            return RegistrationEnumsValues.FAILURE_TYPE_STORAGE;
        } else if (asyncFetchStatus.isRedirectError()) {
            return RegistrationEnumsValues.FAILURE_TYPE_REDIRECT;
        } else {
            return RegistrationEnumsValues.FAILURE_TYPE_UNKNOWN;
        }
    }
    /** AdservicesMeasurementRegistrations atom enum values. */
    public interface RegistrationEnumsValues {
        int TYPE_UNKNOWN = 0;
        int TYPE_SOURCE = 1;
        int TYPE_TRIGGER = 2;
        int SOURCE_TYPE_UNKNOWN = 0;
        int SOURCE_TYPE_EVENT = 1;
        int SOURCE_TYPE_NAVIGATION = 2;
        int SURFACE_TYPE_UNKNOWN = 0;
        int SURFACE_TYPE_WEB = 1;
        int SURFACE_TYPE_APP = 2;
        int STATUS_UNKNOWN = 0;
        int STATUS_SUCCESS = 1;
        int STATUS_FAILURE = 2;
        int FAILURE_TYPE_UNKNOWN = 0;
        int FAILURE_TYPE_PARSING = 1;
        int FAILURE_TYPE_NETWORK = 2;
        int FAILURE_TYPE_ENROLLMENT = 3;
        int FAILURE_TYPE_REDIRECT = 4;
        int FAILURE_TYPE_STORAGE = 5;
    }
}
