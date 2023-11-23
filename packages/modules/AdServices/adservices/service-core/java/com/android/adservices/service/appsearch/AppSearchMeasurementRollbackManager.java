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

package com.android.adservices.service.appsearch;

import static com.android.adservices.AdServicesCommon.EXTSERVICES_APEX_NAME_SUFFIX;

import android.annotation.NonNull;
import android.app.adservices.AdServicesManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;

import androidx.annotation.RequiresApi;

import com.android.adservices.LogUtil;

import java.util.List;
import java.util.Objects;

/**
 * This class manages the interface to AppSearch for reading/writing AdServices Measurement deletion
 * data on S- devices. This is needed because AdServices does not run any code in the system server
 * on S- devices, so data indicating that a deletion happened prior to rollback is stored in
 * AppSearch in order to make it rollback safe.
 */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class AppSearchMeasurementRollbackManager {
    private static final long APEX_VERSION_WHEN_NOT_FOUND = -1L;

    private final AppSearchMeasurementRollbackWorker mWorker;
    private final long mCurrentApexVersion;
    private final int mDeletionApiType;

    private AppSearchMeasurementRollbackManager(
            AppSearchMeasurementRollbackWorker worker,
            long currentApexVersion,
            int deletionApiType) {
        mWorker = worker;
        mCurrentApexVersion = currentApexVersion;
        mDeletionApiType = deletionApiType;
    }

    /** Returns an instance of AppSearchMeasurementRollbackManager. */
    public static AppSearchMeasurementRollbackManager getInstance(
            @NonNull Context context, @AdServicesManager.DeletionApiType int deletionApiType) {
        Objects.requireNonNull(context);

        String userId = getUserId();
        long apexVersion = computeApexVersion(context);
        return new AppSearchMeasurementRollbackManager(
                AppSearchMeasurementRollbackWorker.getInstance(context, userId),
                apexVersion,
                deletionApiType);
    }

    /**
     * Records in AppSearch that a measurement deletion event occurred. This will create a document
     * in AppSearch that contains the userId and the apex version that this deletion occurred in.
     */
    public void recordAdServicesDeletionOccurred() {
        if (mCurrentApexVersion == APEX_VERSION_WHEN_NOT_FOUND) {
            LogUtil.e(
                    "AppSearchMeasurementRollbackManager current apex version not found. Skipping"
                            + " recording deletion.");
            return;
        }

        try {
            mWorker.recordAdServicesDeletionOccurred(mDeletionApiType, mCurrentApexVersion);
        } catch (RuntimeException e) {
            LogUtil.e(e, "AppSearchMeasurementRollbackManager failed to record deletion");
        }
    }

    /**
     * Checks if AppSearch contains data indicating that a rollback happened in a higher apex
     * version than is currently executing. This method loads the document from AppSearch and
     * compares the version stored in it to the current version.
     *
     * <p><b><u>Side effect</u></b>: If the stored document indicates that a rollback reconciliation
     * is needed, the stored document is deleted before returning. This method is therefore NOT
     * idempotent.
     *
     * @return true if AppSearch contains the document with a higher than the current version; false
     *     otherwise.
     */
    public boolean needsToHandleRollbackReconciliation() {
        if (mCurrentApexVersion == APEX_VERSION_WHEN_NOT_FOUND) {
            LogUtil.e(
                    "AppSearchMeasurementRollbackManager current apex version not found. Skipping"
                            + " handling rollback reconciliation.");
            return false;
        }

        try {
            AppSearchMeasurementRollbackDao dao =
                    mWorker.getAdServicesDeletionRollbackMetadata(mDeletionApiType);
            if (dao == null || dao.getApexVersion() <= mCurrentApexVersion) {
                return false;
            }

            mWorker.clearAdServicesDeletionOccurred(dao.getId());
            return true;
        } catch (RuntimeException e) {
            LogUtil.e(
                    e,
                    "AppSearchMeasurementRollbackManager failed to handle rollback reconciliation");
            return false;
        }
    }

    private static String getUserId() {
        return "" + UserHandle.getUserHandleForUid(Binder.getCallingUid()).getIdentifier();
    }

    private static long computeApexVersion(Context context) {
        PackageManager packageManager = context.getPackageManager();
        List<PackageInfo> installedPackages =
                packageManager.getInstalledPackages(PackageManager.MATCH_APEX);
        return installedPackages.stream()
                .filter(s -> s.isApex && s.packageName.endsWith(EXTSERVICES_APEX_NAME_SUFFIX))
                .findFirst()
                .map(PackageInfo::getLongVersionCode)
                .orElse(APEX_VERSION_WHEN_NOT_FOUND);
    }
}
