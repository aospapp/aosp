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

package com.android.adservices.service.measurement.util;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import com.android.adservices.LogUtil;
import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.service.Flags;
import com.android.adservices.service.common.AppManifestConfigHelper;
import com.android.adservices.service.enrollment.EnrollmentData;

import java.util.Optional;

/** Enrollment utilities for measurement. */
public final class Enrollment {

    public static final String FAKE_ENROLLMENT = "fake_enrollment";

    private Enrollment() { }

    /**
     * Returns an {@code Optional<String>} of the ad-tech enrollment record ID.
     *
     * @param registrationUri the ad-tech URL used to register a source or trigger.
     * @param packageName Package mame of the registrant
     * @param enrollmentDao an instance of {@code EnrollmentDao}.
     * @param context a valid {@code Context} object
     * @param flags a valid {@code Flags} object
     * @return enrollmentId if enrollment id exists and all validations pass otherwise empty
     */
    public static Optional<String> getValidEnrollmentId(
            Uri registrationUri,
            String packageName,
            EnrollmentDao enrollmentDao,
            Context context,
            Flags flags) {
        Uri uriWithoutParams = registrationUri.buildUpon().clearQuery().fragment(null).build();

        EnrollmentData enrollmentData =
                enrollmentDao.getEnrollmentDataFromMeasurementUrl(uriWithoutParams);
        if (enrollmentData == null) {
            LogUtil.w(
                    "Enrollment check failed, Reason: Enrollment Id Not Found, "
                            + "Registration URI: %s",
                    registrationUri);
            return Optional.empty();
        }
        if (flags.isEnrollmentBlocklisted(enrollmentData.getEnrollmentId())) {
            LogUtil.w(
                    "Enrollment check failed, Reason: Enrollment Id in blocklist, "
                            + "Registration URI: %s, Enrollment Id: %s",
                    registrationUri, enrollmentData.getEnrollmentId());
            return Optional.empty();
        }
        // TODO(b/269798827): Enable for R.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !AppManifestConfigHelper.isAllowedAttributionAccess(
                        context, packageName, enrollmentData.getEnrollmentId())) {
            LogUtil.w(
                    "Enrollment check failed, Reason: Enrollment Id missing from "
                            + "App Manifest AdTech allowlist, "
                            + "Registration URI: %s, Enrollment Id: %s",
                    registrationUri, enrollmentData.getEnrollmentId());
            return Optional.empty();
        }
        return Optional.of(enrollmentData.getEnrollmentId());
    }

    /**
     * Returns an {@code Optional<Uri>} of the ad-tech server URL that accepts attribution reports.
     *
     * @param enrollmentId the enrollment record ID.
     * @param enrollmentDao an instance of {@code EnrollmentDao}.
     */
    public static Optional<Uri> maybeGetReportingOrigin(String enrollmentId,
            EnrollmentDao enrollmentDao) {
        EnrollmentData enrollmentData = enrollmentDao.getEnrollmentData(enrollmentId);
        if (enrollmentData != null && enrollmentData.getAttributionReportingUrl().size() > 0) {
            return Optional.of(Uri.parse(enrollmentData.getAttributionReportingUrl().get(0)));
        }
        return Optional.empty();
    }
}
