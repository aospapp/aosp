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

import static android.adservices.common.AdServicesStatusUtils.STATUS_INTERNAL_ERROR;
import static android.adservices.common.AdServicesStatusUtils.STATUS_INVALID_ARGUMENT;
import static android.adservices.common.AdServicesStatusUtils.STATUS_IO_ERROR;
import static android.adservices.common.AdServicesStatusUtils.STATUS_SUCCESS;

import android.adservices.common.AdServicesStatusUtils;
import android.adservices.measurement.DeletionParam;
import android.adservices.measurement.RegistrationRequest;
import android.adservices.measurement.WebSourceRegistrationRequest;
import android.adservices.measurement.WebSourceRegistrationRequestInternal;
import android.adservices.measurement.WebTriggerRegistrationRequest;
import android.adservices.measurement.WebTriggerRegistrationRequestInternal;
import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.app.adservices.AdServicesManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.view.InputEvent;

import androidx.annotation.RequiresApi;

import com.android.adservices.LogUtil;
import com.android.adservices.data.measurement.DatastoreManager;
import com.android.adservices.data.measurement.DatastoreManagerFactory;
import com.android.adservices.data.measurement.deletion.MeasurementDataDeleter;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.appsearch.AppSearchMeasurementRollbackManager;
import com.android.adservices.service.common.compat.PackageManagerCompatUtils;
import com.android.adservices.service.measurement.inputverification.ClickVerifier;
import com.android.adservices.service.measurement.registration.EnqueueAsyncRegistration;
import com.android.adservices.service.measurement.util.Web;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;

import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import javax.annotation.concurrent.ThreadSafe;

/**
 * This class is thread safe.
 *
 * @hide
 */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
@ThreadSafe
@WorkerThread
public final class MeasurementImpl {
    private static final String ANDROID_APP_SCHEME = "android-app";
    private static volatile MeasurementImpl sMeasurementImpl;
    private final Context mContext;
    private final ReadWriteLock mReadWriteLock = new ReentrantReadWriteLock();
    private final DatastoreManager mDatastoreManager;
    private final ContentResolver mContentResolver;
    private final ClickVerifier mClickVerifier;
    private final MeasurementDataDeleter mMeasurementDataDeleter;
    private final Flags mFlags;

    @VisibleForTesting
    MeasurementImpl(Context context) {
        mContext = context;
        mDatastoreManager = DatastoreManagerFactory.getDatastoreManager(context);
        mClickVerifier = new ClickVerifier(context);
        mFlags = FlagsFactory.getFlags();
        mMeasurementDataDeleter = new MeasurementDataDeleter(mDatastoreManager);
        mContentResolver = mContext.getContentResolver();
        deleteOnRollback();
    }

    @VisibleForTesting
    MeasurementImpl(
            Context context,
            DatastoreManager datastoreManager,
            ClickVerifier clickVerifier,
            MeasurementDataDeleter measurementDataDeleter,
            ContentResolver contentResolver) {
        mContext = context;
        mDatastoreManager = datastoreManager;
        mClickVerifier = clickVerifier;
        mMeasurementDataDeleter = measurementDataDeleter;
        mFlags = FlagsFactory.getFlagsForTest();
        mContentResolver = contentResolver;
    }

    /**
     * Gets an instance of MeasurementImpl to be used.
     *
     * <p>If no instance has been initialized yet, a new one will be created. Otherwise, the
     * existing instance will be returned.
     */
    @NonNull
    public static MeasurementImpl getInstance(Context context) {
        if (sMeasurementImpl == null) {
            synchronized (MeasurementImpl.class) {
                if (sMeasurementImpl == null) {
                    sMeasurementImpl = new MeasurementImpl(context);
                }
            }
        }
        return sMeasurementImpl;
    }

    /**
     * Invoked when a package is installed.
     *
     * @param packageUri installed package {@link Uri}.
     * @param eventTime  time when the package was installed.
     */
    public void doInstallAttribution(@NonNull Uri packageUri, long eventTime) {
        LogUtil.d("Attributing installation for: " + packageUri);
        Uri appUri = getAppUri(packageUri);
        mReadWriteLock.readLock().lock();
        try {
            mDatastoreManager.runInTransaction(
                    (dao) -> dao.doInstallAttribution(appUri, eventTime));
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /** Implement a registration request, returning a {@link AdServicesStatusUtils.StatusCode}. */
    @AdServicesStatusUtils.StatusCode
    int register(@NonNull RegistrationRequest request, boolean adIdPermission, long requestTime) {
        mReadWriteLock.readLock().lock();
        try {
            switch (request.getRegistrationType()) {
                case RegistrationRequest.REGISTER_SOURCE:
                case RegistrationRequest.REGISTER_TRIGGER:
                    return EnqueueAsyncRegistration.appSourceOrTriggerRegistrationRequest(
                                    request,
                                    adIdPermission,
                                    getRegistrant(request.getAppPackageName()),
                                    requestTime,
                                    request.getRegistrationType()
                                                    == RegistrationRequest.REGISTER_TRIGGER
                                            ? null
                                            : getSourceType(
                                                    request.getInputEvent(),
                                                    request.getRequestTime()),
                                    mDatastoreManager,
                                    mContentResolver)
                            ? STATUS_SUCCESS
                            : STATUS_IO_ERROR;

                default:
                    return STATUS_INVALID_ARGUMENT;
            }
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Processes a source registration request delegated to OS from the caller, e.g. Chrome,
     * returning a status code.
     */
    int registerWebSource(
            @NonNull WebSourceRegistrationRequestInternal request,
            boolean adIdPermission,
            long requestTime) {
        WebSourceRegistrationRequest sourceRegistrationRequest =
                request.getSourceRegistrationRequest();
        if (!isValid(sourceRegistrationRequest)) {
            LogUtil.e("registerWebSource received invalid parameters");
            return STATUS_INVALID_ARGUMENT;
        }
        mReadWriteLock.readLock().lock();
        try {
            boolean enqueueStatus =
                    EnqueueAsyncRegistration.webSourceRegistrationRequest(
                            sourceRegistrationRequest,
                            adIdPermission,
                            getRegistrant(request.getAppPackageName()),
                            requestTime,
                            getSourceType(
                                    sourceRegistrationRequest.getInputEvent(),
                                    request.getRequestTime()),
                            mDatastoreManager,
                            mContentResolver);
            if (enqueueStatus) {
                return STATUS_SUCCESS;
            } else {

                return STATUS_IO_ERROR;
            }
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Processes a trigger registration request delegated to OS from the caller, e.g. Chrome,
     * returning a status code.
     */
    int registerWebTrigger(
            @NonNull WebTriggerRegistrationRequestInternal request,
            boolean adIdPermission,
            long requestTime) {
        WebTriggerRegistrationRequest triggerRegistrationRequest =
                request.getTriggerRegistrationRequest();
        if (!isValid(triggerRegistrationRequest)) {
            LogUtil.e("registerWebTrigger received invalid parameters");
            return STATUS_INVALID_ARGUMENT;
        }
        mReadWriteLock.readLock().lock();
        try {
            boolean enqueueStatus =
                    EnqueueAsyncRegistration.webTriggerRegistrationRequest(
                            triggerRegistrationRequest,
                            adIdPermission,
                            getRegistrant(request.getAppPackageName()),
                            requestTime,
                            mDatastoreManager,
                            mContentResolver);
            if (enqueueStatus) {
                return STATUS_SUCCESS;
            } else {

                return STATUS_IO_ERROR;
            }
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Implement a deleteRegistrations request, returning a r{@link
     * AdServicesStatusUtils.StatusCode}.
     */
    @AdServicesStatusUtils.StatusCode
    int deleteRegistrations(@NonNull DeletionParam request) {
        mReadWriteLock.readLock().lock();
        try {
            boolean deleteResult = mMeasurementDataDeleter.delete(request);
            if (deleteResult) {
                markDeletion();
            }
            return deleteResult ? STATUS_SUCCESS : STATUS_INTERNAL_ERROR;
        } catch (NullPointerException | IllegalArgumentException e) {
            LogUtil.e(e, "Delete registration received invalid parameters");
            return STATUS_INVALID_ARGUMENT;
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Delete all records from a specific package.
     */
    public void deletePackageRecords(Uri packageUri) {
        Uri appUri = getAppUri(packageUri);
        LogUtil.d("Deleting records for " + appUri);
        mReadWriteLock.writeLock().lock();
        try {
            Optional<Boolean> didDeletionOccurOpt =
                    mDatastoreManager.runInTransactionWithResult(
                            (dao) -> {
                                dao.undoInstallAttribution(appUri);
                                return dao.deleteAppRecords(appUri);
                            });
            if (didDeletionOccurOpt.isPresent() && didDeletionOccurOpt.get()) {
                markDeletion();
            }
        } catch (NullPointerException | IllegalArgumentException e) {
            LogUtil.e(e, "Delete package records received invalid parameters");
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Delete all data generated by Measurement API, except for tables in the exclusion list.
     *
     * @param tablesToExclude a {@link List} of tables that won't be deleted.
     */
    public void deleteAllMeasurementData(@NonNull List<String> tablesToExclude) {
        mReadWriteLock.writeLock().lock();
        try {
            mDatastoreManager.runInTransaction(
                    (dao) -> dao.deleteAllMeasurementData(tablesToExclude));
            LogUtil.v(
                    "All data is cleared for Measurement API except: %s",
                    tablesToExclude.toString());
            markDeletion();
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /** Delete all data generated from apps that are not currently installed. */
    public void deleteAllUninstalledMeasurementData() {
        List<Uri> installedApplicationsList = getCurrentInstalledApplicationsList(mContext);
        mReadWriteLock.writeLock().lock();
        try {
            Optional<Boolean> didDeletionOccurOpt =
                    mDatastoreManager.runInTransactionWithResult(
                            (dao) -> dao.deleteAppRecordsNotPresent(installedApplicationsList));
            if (didDeletionOccurOpt.isPresent() && didDeletionOccurOpt.get()) {
                markDeletion();
            }
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    private List<Uri> getCurrentInstalledApplicationsList(Context context) {
        PackageManager packageManager = context.getPackageManager();
        List<ApplicationInfo> applicationInfoList =
                PackageManagerCompatUtils.getInstalledApplications(
                        packageManager, PackageManager.GET_META_DATA);
        return applicationInfoList.stream()
                .map(applicationInfo -> Uri.parse("android-app://" + applicationInfo.packageName))
                .collect(Collectors.toList());
    }

    @VisibleForTesting
    Source.SourceType getSourceType(InputEvent inputEvent, long requestTime) {
        // If click verification is enabled and the InputEvent is not null, but it cannot be
        // verified, then the SourceType is demoted to EVENT.
        if (mFlags.getMeasurementIsClickVerificationEnabled()
                && inputEvent != null
                && !mClickVerifier.isInputEventVerifiable(inputEvent, requestTime)) {
            return Source.SourceType.EVENT;
        } else {
            return inputEvent == null ? Source.SourceType.EVENT : Source.SourceType.NAVIGATION;
        }
    }

    private Uri getRegistrant(String packageName) {
        return Uri.parse(ANDROID_APP_SCHEME + "://" + packageName);
    }

    private Uri getAppUri(Uri packageUri) {
        return Uri.parse(ANDROID_APP_SCHEME + "://" + packageUri.getEncodedSchemeSpecificPart());
    }

    private boolean isValid(WebSourceRegistrationRequest sourceRegistrationRequest) {
        Uri verifiedDestination = sourceRegistrationRequest.getVerifiedDestination();
        Uri webDestination = sourceRegistrationRequest.getWebDestination();

        if (verifiedDestination == null) {
            return webDestination == null
                    ? true
                    : Web.topPrivateDomainAndScheme(webDestination).isPresent();
        }

        return isVerifiedDestination(
                verifiedDestination, webDestination, sourceRegistrationRequest.getAppDestination());
    }

    private boolean isVerifiedDestination(
            Uri verifiedDestination, Uri webDestination, Uri appDestination) {
        String destinationPackage = null;
        if (appDestination != null) {
            destinationPackage = appDestination.getHost();
        }
        String verifiedScheme = verifiedDestination.getScheme();
        String verifiedHost = verifiedDestination.getHost();

        // Verified destination matches appDestination value
        if (destinationPackage != null
                && verifiedHost != null
                && (verifiedScheme == null || verifiedScheme.equals(ANDROID_APP_SCHEME))
                && verifiedHost.equals(destinationPackage)) {
            return true;
        }

        try {
            Intent intent = Intent.parseUri(verifiedDestination.toString(), 0);
            ComponentName componentName = intent.resolveActivity(mContext.getPackageManager());
            if (componentName == null) {
                return false;
            }

            // (ComponentName::getPackageName cannot be null)
            String verifiedPackage = componentName.getPackageName();

            // Try to match an app vendor store and extract a target package
            if (destinationPackage != null
                    && verifiedPackage.equals(AppVendorPackages.PLAY_STORE)) {
                String targetPackage = getTargetPackageFromPlayStoreUri(verifiedDestination);
                return targetPackage != null && targetPackage.equals(destinationPackage);

            // Try to match web destination
            } else if (webDestination == null) {
                return false;
            } else {
                Optional<Uri> webDestinationTopPrivateDomainAndScheme =
                        Web.topPrivateDomainAndScheme(webDestination);
                Optional<Uri> verifiedDestinationTopPrivateDomainAndScheme =
                        Web.topPrivateDomainAndScheme(verifiedDestination);
                return webDestinationTopPrivateDomainAndScheme.isPresent()
                        && verifiedDestinationTopPrivateDomainAndScheme.isPresent()
                        && webDestinationTopPrivateDomainAndScheme.get().equals(
                                verifiedDestinationTopPrivateDomainAndScheme.get());
            }
        } catch (URISyntaxException e) {
            LogUtil.e(e,
                    "MeasurementImpl::handleVerifiedDestination: failed to parse intent URI: %s",
                    verifiedDestination.toString());
            return false;
        }
    }

    private static boolean isValid(WebTriggerRegistrationRequest triggerRegistrationRequest) {
        Uri destination = triggerRegistrationRequest.getDestination();
        return Web.topPrivateDomainAndScheme(destination).isPresent();
    }

    private static String getTargetPackageFromPlayStoreUri(Uri uri) {
        return uri.getQueryParameter("id");
    }

    private interface AppVendorPackages {
        String PLAY_STORE = "com.android.vending";
    }

    /**
     * Checks if the module was rollback and if there was a deletion in the version rolled back
     * from. If there was, delete all measurement data to prioritize user privacy.
     */
    private void deleteOnRollback() {
        if (FlagsFactory.getFlags().getMeasurementRollbackDeletionKillSwitch()) {
            LogUtil.e("Rollback deletion is disabled. Not checking system server for rollback.");
            return;
        }

        LogUtil.d("Checking rollback status.");
        boolean needsToHandleRollbackReconciliation = checkIfNeedsToHandleReconciliation();
        if (needsToHandleRollbackReconciliation) {
            LogUtil.d("Rollback and deletion detected, deleting all measurement data.");
            mReadWriteLock.writeLock().lock();
            try {
                mDatastoreManager.runInTransaction(
                        (dao) -> dao.deleteAllMeasurementData(Collections.emptyList()));
            } finally {
                mReadWriteLock.writeLock().unlock();
            }
        }
    }

    @VisibleForTesting
    boolean checkIfNeedsToHandleReconciliation() {
        if (SdkLevel.isAtLeastT()) {
            return AdServicesManager.getInstance(mContext)
                    .needsToHandleRollbackReconciliation(AdServicesManager.MEASUREMENT_DELETION);
        }

        // Not on Android T+
        if (FlagsFactory.getFlags().getMeasurementRollbackDeletionAppSearchKillSwitch()) {
            LogUtil.e("Rollback deletion is disabled. Not checking App Search for rollback.");
            return false;
        }

        return AppSearchMeasurementRollbackManager.getInstance(
                        mContext, AdServicesManager.MEASUREMENT_DELETION)
                .needsToHandleRollbackReconciliation();
    }

    /**
     * Stores a bit in the system server indicating that a deletion happened for the current
     * AdServices module version. This information is used for deleting data after it has been
     * restored by a module rollback.
     */
    private void markDeletion() {
        if (FlagsFactory.getFlags().getMeasurementRollbackDeletionKillSwitch()) {
            LogUtil.e("Rollback deletion is disabled. Not storing status in system server.");
            return;
        }

        if (SdkLevel.isAtLeastT()) {
            LogUtil.d("Marking deletion in system server.");
            AdServicesManager.getInstance(mContext)
                    .recordAdServicesDeletionOccurred(AdServicesManager.MEASUREMENT_DELETION);
            return;
        }

        // On Android S or lower.
        if (FlagsFactory.getFlags().getMeasurementRollbackDeletionAppSearchKillSwitch()) {
            LogUtil.e("Rollback deletion in AppSearch disabled. Not storing status in AppSearch.");
            return;
        }

        LogUtil.d("Marking deletion in AppSearch");
        AppSearchMeasurementRollbackManager.getInstance(
                        mContext, AdServicesManager.MEASUREMENT_DELETION)
                .recordAdServicesDeletionOccurred();
    }
}
