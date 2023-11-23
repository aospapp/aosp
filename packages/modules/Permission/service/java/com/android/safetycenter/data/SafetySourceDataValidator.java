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

package com.android.safetycenter.data;

import static android.os.Build.VERSION_CODES.TIRAMISU;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.safetycenter.SafetySourceData;
import android.safetycenter.SafetySourceIssue;
import android.safetycenter.SafetySourceStatus;
import android.safetycenter.config.SafetySource;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.modules.utils.build.SdkLevel;
import com.android.permission.util.UserUtils;
import com.android.safetycenter.SafetyCenterConfigReader;
import com.android.safetycenter.SafetyCenterFlags;
import com.android.safetycenter.SafetySources;

import java.util.List;
import java.util.Set;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Validates calls made to the Safety Center API to get, set or clear {@link SafetySourceData}, or
 * to report an error.
 *
 * <p>This class isn't thread safe. Thread safety must be handled by the caller.
 */
@RequiresApi(TIRAMISU)
@NotThreadSafe
final class SafetySourceDataValidator {

    private static final String TAG = "SafetySourceDataValidator";

    private final Context mContext;
    private final SafetyCenterConfigReader mSafetyCenterConfigReader;
    private final PackageManager mPackageManager;

    SafetySourceDataValidator(Context context, SafetyCenterConfigReader safetyCenterConfigReader) {
        mContext = context;
        mSafetyCenterConfigReader = safetyCenterConfigReader;
        mPackageManager = mContext.getPackageManager();
    }

    /**
     * Validates a call to the Safety Center API, from the given {@code packageName} and {@code
     * userId} to get, set or clear {@link SafetySourceData}, or to report an error, for the given
     * {@code safetySourceId}. Returns {@code true} if the call is valid and should proceed, or
     * {@code false} otherwise.
     *
     * <p>This method may throw an {@link IllegalArgumentException} in some invalid cases.
     *
     * @param safetySourceData being set, or {@code null} if retrieving or clearing data, or
     *     reporting an error
     */
    boolean validateRequest(
            @Nullable SafetySourceData safetySourceData,
            String safetySourceId,
            String packageName,
            @UserIdInt int userId) {
        SafetyCenterConfigReader.ExternalSafetySource externalSafetySource =
                mSafetyCenterConfigReader.getExternalSafetySource(safetySourceId, packageName);
        if (externalSafetySource == null) {
            throw new IllegalArgumentException("Unexpected safety source: " + safetySourceId);
        }

        SafetySource safetySource = externalSafetySource.getSafetySource();
        validateCallingPackage(safetySource, packageName, safetySourceId);

        if (UserUtils.isManagedProfile(userId, mContext)
                && !SafetySources.supportsManagedProfiles(safetySource)) {
            throw new IllegalArgumentException(
                    "Unexpected managed profile request for safety source: " + safetySourceId);
        }

        boolean retrievingOrClearingData = safetySourceData == null;
        if (retrievingOrClearingData) {
            return mSafetyCenterConfigReader.isExternalSafetySourceActive(
                    safetySourceId, packageName);
        }

        SafetySourceStatus safetySourceStatus = safetySourceData.getStatus();

        if (safetySource.getType() == SafetySource.SAFETY_SOURCE_TYPE_ISSUE_ONLY
                && safetySourceStatus != null) {
            throw new IllegalArgumentException(
                    "Unexpected status for issue only safety source: " + safetySourceId);
        }

        if (safetySource.getType() == SafetySource.SAFETY_SOURCE_TYPE_DYNAMIC
                && safetySourceStatus == null) {
            throw new IllegalArgumentException(
                    "Missing status for dynamic safety source: " + safetySourceId);
        }

        if (safetySourceStatus != null) {
            int sourceSeverityLevel = safetySourceStatus.getSeverityLevel();

            if (externalSafetySource.hasEntryInStatelessGroup()
                    && sourceSeverityLevel != SafetySourceData.SEVERITY_LEVEL_UNSPECIFIED) {
                throw new IllegalArgumentException(
                        "Safety source: "
                                + safetySourceId
                                + " is in a stateless group but specified a severity level: "
                                + sourceSeverityLevel);
            }

            int maxSourceSeverityLevel =
                    Math.max(
                            SafetySourceData.SEVERITY_LEVEL_INFORMATION,
                            safetySource.getMaxSeverityLevel());

            if (sourceSeverityLevel > maxSourceSeverityLevel) {
                throw new IllegalArgumentException(
                        "Unexpected severity level: "
                                + sourceSeverityLevel
                                + ", for safety source: "
                                + safetySourceId);
            }
        }

        List<SafetySourceIssue> safetySourceIssues = safetySourceData.getIssues();

        for (int i = 0; i < safetySourceIssues.size(); i++) {
            SafetySourceIssue safetySourceIssue = safetySourceIssues.get(i);
            int issueSeverityLevel = safetySourceIssue.getSeverityLevel();
            if (issueSeverityLevel > safetySource.getMaxSeverityLevel()) {
                throw new IllegalArgumentException(
                        "Unexpected severity level: "
                                + issueSeverityLevel
                                + ", for issue in safety source: "
                                + safetySourceId);
            }

            int issueCategory = safetySourceIssue.getIssueCategory();
            if (!SafetyCenterFlags.isIssueCategoryAllowedForSource(issueCategory, safetySourceId)) {
                throw new IllegalArgumentException(
                        "Unexpected issue category: "
                                + issueCategory
                                + ", for issue in safety source: "
                                + safetySourceId);
            }
        }

        return mSafetyCenterConfigReader.isExternalSafetySourceActive(safetySourceId, packageName);
    }

    private void validateCallingPackage(
            SafetySource safetySource, String packageName, String safetySourceId) {
        if (!packageName.equals(safetySource.getPackageName())) {
            throw new IllegalArgumentException(
                    "Unexpected package name: "
                            + packageName
                            + ", for safety source: "
                            + safetySourceId);
        }

        if (!SdkLevel.isAtLeastU()) {
            // No more validation checks possible on T devices
            return;
        }

        Set<String> certificateHashes = safetySource.getPackageCertificateHashes();
        if (certificateHashes.isEmpty()) {
            Log.d(TAG, "No cert check requested for package " + packageName);
            return;
        }

        if (!checkCerts(packageName, certificateHashes)
                && !checkCerts(
                        packageName,
                        SafetyCenterFlags.getAdditionalAllowedPackageCerts(packageName))) {
            Log.e(
                    TAG,
                    "Package "
                            + packageName
                            + " for source "
                            + safetySourceId
                            + " signed with invalid signature");
            throw new IllegalArgumentException("Invalid signature for package " + packageName);
        }
    }

    private boolean checkCerts(String packageName, Set<String> certificateHashes) {
        boolean hasMatchingCert = false;
        for (String certHash : certificateHashes) {
            try {
                byte[] certificate = new Signature(certHash).toByteArray();
                if (mPackageManager.hasSigningCertificate(
                        packageName, certificate, PackageManager.CERT_INPUT_SHA256)) {
                    Log.d(TAG, "Package " + packageName + " has expected signature");
                    hasMatchingCert = true;
                }
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Failed to parse signing certificate: " + certHash, e);
                throw new IllegalStateException(
                        "Failed to parse signing certificate: " + certHash, e);
            }
        }
        return hasMatchingCert;
    }
}
