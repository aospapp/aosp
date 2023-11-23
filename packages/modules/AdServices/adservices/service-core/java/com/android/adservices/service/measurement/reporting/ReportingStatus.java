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

package com.android.adservices.service.measurement.reporting;

import android.annotation.Nullable;

import java.util.Optional;

/** POJO for storing aggregate and event reporting status */
public class ReportingStatus {

    /** Enums are tied to the AdservicesMeasurementReportsUploaded atom */
    public enum UploadStatus {
        UNKNOWN,
        SUCCESS,
        FAILURE
    }

    public enum FailureStatus {
        UNKNOWN,
        ENROLLMENT_NOT_FOUND,
        NETWORK,
        DATASTORE,
        REPORT_NOT_PENDING
    }

    public enum UploadMethod {
        UNKNOWN,
        REGULAR,
        FALLBACK
    }

    private UploadStatus mUploadStatus;

    private FailureStatus mFailureStatus;

    private UploadMethod mUploadMethod;

    @Nullable private Long mReportingDelay;

    public ReportingStatus() {
        mUploadStatus = UploadStatus.UNKNOWN;
        mFailureStatus = FailureStatus.UNKNOWN;
        mUploadMethod = UploadMethod.UNKNOWN;
    }

    /** Get the upload status of reporting. */
    public UploadStatus getUploadStatus() {
        return mUploadStatus;
    }

    /** Set upload status of reporting. */
    public void setUploadStatus(UploadStatus status) {
        mUploadStatus = status;
    }

    /** Get the failure status of reporting. */
    public FailureStatus getFailureStatus() {
        return mFailureStatus;
    }

    /** Set failure status of reporting. */
    public void setFailureStatus(FailureStatus status) {
        mFailureStatus = status;
    }

    /** Get the upload method of reporting. */
    public UploadMethod getUploadMethod() {
        return mUploadMethod;
    }

    /** Set upload method of reporting. */
    public void setUploadMethod(UploadMethod method) {
        mUploadMethod = method;
    }

    /** Get registration delay. */
    public Optional<Long> getReportingDelay() {
        return Optional.ofNullable(mReportingDelay);
    }

    /** Set registration delay. */
    public void setReportingDelay(Long reportingDelay) {
        mReportingDelay = reportingDelay;
    }
}
