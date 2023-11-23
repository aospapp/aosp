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

package com.android.adservices.service.measurement.reporting;


import android.adservices.common.AdServicesStatusUtils;
import android.net.Uri;

import com.android.adservices.LogUtil;
import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.data.measurement.DatastoreManager;
import com.android.adservices.data.measurement.IMeasurementDao;
import com.android.internal.annotations.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Optional;

/** Class for handling debug reporting. */
public class DebugReportingJobHandler {

    private final EnrollmentDao mEnrollmentDao;
    private final DatastoreManager mDatastoreManager;

    DebugReportingJobHandler(EnrollmentDao enrollmentDao, DatastoreManager datastoreManager) {
        mEnrollmentDao = enrollmentDao;
        mDatastoreManager = datastoreManager;
    }

    /** Finds all debug reports and attempts to upload them individually. */
    void performScheduledPendingReports() {
        Optional<List<String>> pendingDebugReports =
                mDatastoreManager.runInTransactionWithResult(IMeasurementDao::getDebugReportIds);
        if (!pendingDebugReports.isPresent()) {
            LogUtil.d("Pending Debug Reports not found");
            return;
        }

        List<String> pendingDebugReportIdsInWindow = pendingDebugReports.get();
        for (String debugReportId : pendingDebugReportIdsInWindow) {
            performReport(debugReportId);
        }
    }

    /**
     * Perform reporting by finding the relevant {@link DebugReport} and making an HTTP POST request
     * to the specified report to URL with the report data as a JSON in the body.
     *
     * @param debugReportId for the datastore id of the {@link DebugReport}
     * @return success
     */
    int performReport(String debugReportId) {
        Optional<DebugReport> debugReportOpt =
                mDatastoreManager.runInTransactionWithResult(
                        (dao) -> dao.getDebugReport(debugReportId));
        if (!debugReportOpt.isPresent()) {
            LogUtil.d("Reading Scheduled Debug Report failed");
            return AdServicesStatusUtils.STATUS_IO_ERROR;
        }
        DebugReport debugReport = debugReportOpt.get();

        try {
            Uri reportingOrigin = debugReport.getRegistrationOrigin();
            JSONArray debugReportJsonPayload = createReportJsonPayload(debugReport);
            int returnCode = makeHttpPostRequest(reportingOrigin, debugReportJsonPayload);

            if (returnCode >= HttpURLConnection.HTTP_OK && returnCode <= 299) {
                boolean success =
                        mDatastoreManager.runInTransaction(
                                (dao) -> {
                                    dao.deleteDebugReport(debugReport.getId());
                                });
                if (success) {
                    return AdServicesStatusUtils.STATUS_SUCCESS;
                } else {
                    LogUtil.d("Deleting debug report failed");
                    return AdServicesStatusUtils.STATUS_IO_ERROR;
                }
            } else {
                LogUtil.d("Sending debug report failed with http error");
                return AdServicesStatusUtils.STATUS_IO_ERROR;
            }
        } catch (Exception e) {
            LogUtil.e(e, "Sending debug report error");
            return AdServicesStatusUtils.STATUS_UNKNOWN_ERROR;
        }
    }

    /** Creates the JSON payload for the POST request from the DebugReport. */
    @VisibleForTesting
    JSONArray createReportJsonPayload(DebugReport debugReport) throws JSONException {
        JSONArray debugReportJsonPayload = new JSONArray();
        debugReportJsonPayload.put(debugReport.toPayloadJson());
        return debugReportJsonPayload;
    }

    /** Makes the POST request to the reporting URL. */
    @VisibleForTesting
    public int makeHttpPostRequest(Uri adTechDomain, JSONArray debugReportPayload)
            throws IOException {
        DebugReportSender debugReportSender = new DebugReportSender();
        return debugReportSender.sendReport(adTechDomain, debugReportPayload);
    }
}
