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

package com.android.adservices.service.measurement.registration;


import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.RemoteException;

import com.android.adservices.LogUtil;
import com.android.adservices.data.measurement.DatastoreException;
import com.android.adservices.data.measurement.DatastoreManager;
import com.android.adservices.data.measurement.DatastoreManagerFactory;
import com.android.adservices.data.measurement.IMeasurementDao;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.measurement.Attribution;
import com.android.adservices.service.measurement.EventReport;
import com.android.adservices.service.measurement.EventSurfaceType;
import com.android.adservices.service.measurement.KeyValueData;
import com.android.adservices.service.measurement.KeyValueData.DataType;
import com.android.adservices.service.measurement.PrivacyParams;
import com.android.adservices.service.measurement.Source;
import com.android.adservices.service.measurement.SystemHealthParams;
import com.android.adservices.service.measurement.Trigger;
import com.android.adservices.service.measurement.attribution.TriggerContentProvider;
import com.android.adservices.service.measurement.noising.SourceNoiseHandler;
import com.android.adservices.service.measurement.reporting.DebugReportApi;
import com.android.adservices.service.measurement.util.BaseUriExtractor;
import com.android.adservices.service.measurement.util.Web;
import com.android.adservices.service.stats.AdServicesLoggerImpl;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Runner for servicing queued registration requests */
public class AsyncRegistrationQueueRunner {
    private static AsyncRegistrationQueueRunner sAsyncRegistrationQueueRunner;
    private final DatastoreManager mDatastoreManager;
    private final AsyncSourceFetcher mAsyncSourceFetcher;
    private final AsyncTriggerFetcher mAsyncTriggerFetcher;
    private final ContentResolver mContentResolver;
    private final DebugReportApi mDebugReportApi;
    private final SourceNoiseHandler mSourceNoiseHandler;

    private AsyncRegistrationQueueRunner(Context context) {
        mDatastoreManager = DatastoreManagerFactory.getDatastoreManager(context);
        mAsyncSourceFetcher = new AsyncSourceFetcher(context);
        mAsyncTriggerFetcher = new AsyncTriggerFetcher(context);
        mContentResolver = context.getContentResolver();
        mDebugReportApi = new DebugReportApi(context, FlagsFactory.getFlags());
        mSourceNoiseHandler = new SourceNoiseHandler(FlagsFactory.getFlags());
    }

    @VisibleForTesting
    public AsyncRegistrationQueueRunner(
            ContentResolver contentResolver,
            AsyncSourceFetcher asyncSourceFetcher,
            AsyncTriggerFetcher asyncTriggerFetcher,
            DatastoreManager datastoreManager,
            DebugReportApi debugReportApi,
            SourceNoiseHandler sourceNoiseHandler) {
        mAsyncSourceFetcher = asyncSourceFetcher;
        mAsyncTriggerFetcher = asyncTriggerFetcher;
        mDatastoreManager = datastoreManager;
        mContentResolver = contentResolver;
        mDebugReportApi = debugReportApi;
        mSourceNoiseHandler = sourceNoiseHandler;
    }

    /**
     * Returns an instance of AsyncRegistrationQueueRunner.
     *
     * @param context the current {@link Context}.
     */
    public static synchronized AsyncRegistrationQueueRunner getInstance(Context context) {
        Objects.requireNonNull(context);
        if (sAsyncRegistrationQueueRunner == null) {
            sAsyncRegistrationQueueRunner = new AsyncRegistrationQueueRunner(context);
        }
        return sAsyncRegistrationQueueRunner;
    }

    /** Processes records in the AsyncRegistration Queue table. */
    public void runAsyncRegistrationQueueWorker() {
        Flags flags = FlagsFactory.getFlags();
        int recordServiceLimit = flags.getMeasurementMaxRegistrationsPerJobInvocation();
        int retryLimit = flags.getMeasurementMaxRetriesPerRegistrationRequest();

        Set<Uri> failedOrigins = new HashSet<>();
        for (int i = 0; i < recordServiceLimit; i++) {
            Optional<AsyncRegistration> optAsyncRegistration =
                    mDatastoreManager.runInTransactionWithResult(
                            (dao) ->
                                    dao.fetchNextQueuedAsyncRegistration(
                                            retryLimit, failedOrigins));

            AsyncRegistration asyncRegistration;
            if (optAsyncRegistration.isPresent()) {
                asyncRegistration = optAsyncRegistration.get();
            } else {
                LogUtil.d("AsyncRegistrationQueueRunner: no async registration fetched.");
                return;
            }

            if (asyncRegistration.isSourceRequest()) {
                LogUtil.d("AsyncRegistrationQueueRunner:" + " processing source");
                processSourceRegistration(asyncRegistration, failedOrigins);
            } else {
                LogUtil.d("AsyncRegistrationQueueRunner:" + " processing trigger");
                processTriggerRegistration(asyncRegistration, failedOrigins);
            }
        }
    }

    private void processSourceRegistration(
            AsyncRegistration asyncRegistration, Set<Uri> failedOrigins) {
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        long startTime = asyncRegistration.getRequestTime();
        Optional<Source> resultSource =
                mAsyncSourceFetcher.fetchSource(asyncRegistration, asyncFetchStatus, asyncRedirect);
        long endTime = System.currentTimeMillis();
        asyncFetchStatus.setRegistrationDelay(endTime - startTime);

        boolean transactionResult =
                mDatastoreManager.runInTransaction(
                        (dao) -> {
                            if (asyncFetchStatus.isRequestSuccess()) {
                                if (resultSource.isPresent()) {
                                    storeSource(resultSource.get(), asyncRegistration, dao);
                                }
                                handleSuccess(
                                        asyncRegistration, asyncFetchStatus, asyncRedirect, dao);
                            } else {
                                handleFailure(
                                        asyncRegistration, asyncFetchStatus, failedOrigins, dao);
                            }
                        });

        if (!transactionResult) {
            asyncFetchStatus.setEntityStatus(AsyncFetchStatus.EntityStatus.STORAGE_ERROR);
        }

        FetcherUtil.emitHeaderMetrics(
                FlagsFactory.getFlags(),
                AdServicesLoggerImpl.getInstance(),
                asyncRegistration,
                asyncFetchStatus);
    }

    /** Visible only for testing. */
    @VisibleForTesting
    public void storeSource(
            Source source, AsyncRegistration asyncRegistration, IMeasurementDao dao)
            throws DatastoreException {
        Uri topOrigin =
                asyncRegistration.getType() == AsyncRegistration.RegistrationType.WEB_SOURCE
                        ? asyncRegistration.getTopOrigin()
                        : getPublisher(asyncRegistration);
        @EventSurfaceType
        int publisherType =
                asyncRegistration.getType() == AsyncRegistration.RegistrationType.WEB_SOURCE
                        ? EventSurfaceType.WEB
                        : EventSurfaceType.APP;
        if (isSourceAllowedToInsert(source, topOrigin, publisherType, dao, mDebugReportApi)) {
            insertSourceFromTransaction(source, dao);
            mDebugReportApi.scheduleSourceSuccessDebugReport(source, dao);
        }
    }

    private void processTriggerRegistration(
            AsyncRegistration asyncRegistration, Set<Uri> failedOrigins) {
        AsyncFetchStatus asyncFetchStatus = new AsyncFetchStatus();
        AsyncRedirect asyncRedirect = new AsyncRedirect();
        long startTime = asyncRegistration.getRequestTime();
        Optional<Trigger> resultTrigger = mAsyncTriggerFetcher.fetchTrigger(
                asyncRegistration, asyncFetchStatus, asyncRedirect);
        long endTime = System.currentTimeMillis();
        asyncFetchStatus.setRegistrationDelay(endTime - startTime);

        boolean transactionResult =
                mDatastoreManager.runInTransaction(
                        (dao) -> {
                            if (asyncFetchStatus.isRequestSuccess()) {
                                if (resultTrigger.isPresent()) {
                                    storeTrigger(resultTrigger.get(), dao);
                                }
                                handleSuccess(
                                        asyncRegistration, asyncFetchStatus, asyncRedirect, dao);
                            } else {
                                handleFailure(
                                        asyncRegistration, asyncFetchStatus, failedOrigins, dao);
                            }
                        });

        if (!transactionResult) {
            asyncFetchStatus.setEntityStatus(AsyncFetchStatus.EntityStatus.STORAGE_ERROR);
        }

        FetcherUtil.emitHeaderMetrics(
                FlagsFactory.getFlags(),
                AdServicesLoggerImpl.getInstance(),
                asyncRegistration,
                asyncFetchStatus);
    }

    /** Visible only for testing. */
    @VisibleForTesting
    public void storeTrigger(Trigger trigger, IMeasurementDao dao) throws DatastoreException {
        if (isTriggerAllowedToInsert(dao, trigger)) {
            try {
                dao.insertTrigger(trigger);
            } catch (DatastoreException e) {
                mDebugReportApi.scheduleTriggerNoMatchingSourceDebugReport(
                        trigger, dao, DebugReportApi.Type.TRIGGER_UNKNOWN_ERROR);
                LogUtil.e(e, "Insert trigger to DB error, generate trigger-unknown-error report");
                throw new DatastoreException(
                        "Insert trigger to DB error, generate trigger-unknown-error report");
            }
            notifyTriggerContentProvider();
        }
    }

    /** Visible only for testing. */
    @VisibleForTesting
    public static boolean isSourceAllowedToInsert(
            Source source,
            Uri topOrigin,
            @EventSurfaceType int publisherType,
            IMeasurementDao dao,
            DebugReportApi debugReportApi)
            throws DatastoreException {
        long windowStartTime = source.getEventTime() - PrivacyParams.RATE_LIMIT_WINDOW_MILLISECONDS;
        Optional<Uri> publisher = getTopLevelPublisher(topOrigin, publisherType);
        if (!publisher.isPresent()) {
            LogUtil.d("insertSources: getTopLevelPublisher failed", topOrigin);
            return false;
        }
        long numOfSourcesPerPublisher =
                dao.getNumSourcesPerPublisher(
                        BaseUriExtractor.getBaseUri(topOrigin), publisherType);

        if (numOfSourcesPerPublisher >= SystemHealthParams.getMaxSourcesPerPublisher()) {
            LogUtil.d(
                    "insertSources: Max limit of %s sources for publisher - %s reached.",
                    SystemHealthParams.getMaxSourcesPerPublisher(), publisher);
            debugReportApi.scheduleSourceStorageLimitDebugReport(
                    source, String.valueOf(numOfSourcesPerPublisher), dao);
            return false;
        }
        int numOfOriginExcludingRegistrationOrigin =
                dao.countSourcesPerPublisherXEnrollmentExcludingRegOrigin(
                        source.getRegistrationOrigin(),
                        publisher.get(),
                        publisherType,
                        source.getEnrollmentId(),
                        source.getEventTime(),
                        PrivacyParams.MIN_REPORTING_ORIGIN_UPDATE_WINDOW);
        if (numOfOriginExcludingRegistrationOrigin > 0) {
            LogUtil.d(
                    "insertSources: Max limit of 1 reporting origin for publisher - %s and"
                            + " enrollment - %s reached.",
                    publisher, source.getEnrollmentId());
            return false;
        }
        if (source.getAppDestinations() != null
                && !isDestinationWithinBounds(
                        debugReportApi,
                        source,
                        publisher.get(),
                        publisherType,
                        source.getEnrollmentId(),
                        source.getAppDestinations(),
                        EventSurfaceType.APP,
                        windowStartTime,
                        source.getEventTime(),
                        dao)) {
            return false;
        }

        if (source.getWebDestinations() != null
                && !isDestinationWithinBounds(
                        debugReportApi,
                        source,
                        publisher.get(),
                        publisherType,
                        source.getEnrollmentId(),
                        source.getWebDestinations(),
                        EventSurfaceType.WEB,
                        windowStartTime,
                        source.getEventTime(),
                        dao)) {
            return false;
        }
        return true;
    }

    private static boolean isDestinationWithinBounds(
            DebugReportApi debugReportApi,
            Source source,
            Uri publisher,
            @EventSurfaceType int publisherType,
            String enrollmentId,
            List<Uri> destinations,
            @EventSurfaceType int destinationType,
            long windowStartTime,
            long requestTime,
            IMeasurementDao dao)
            throws DatastoreException {
        int destinationCount =
                dao.countDistinctDestinationsPerPublisherXEnrollmentInActiveSource(
                        publisher,
                        publisherType,
                        enrollmentId,
                        destinations,
                        destinationType,
                        windowStartTime,
                        requestTime);
        int maxDistinctDestinations =
                FlagsFactory.getFlags().getMeasurementMaxDistinctDestinationsInActiveSource();
        if (destinationCount + destinations.size() > maxDistinctDestinations) {
            LogUtil.d(
                    "AsyncRegistrationQueueRunner: "
                            + (destinationType == EventSurfaceType.APP ? "App" : "Web")
                            + " destination count >= "
                            + "MaxDistinctDestinationsPerPublisherXEnrollmentInActiveSource");
            debugReportApi.scheduleSourceDestinationLimitDebugReport(
                    source, String.valueOf(maxDistinctDestinations), dao);
            return false;
        }

        int destinationEnrollmentCount =
                dao.countDistinctEnrollmentsPerPublisherXDestinationInSource(
                        publisher,
                        publisherType,
                        destinations,
                        enrollmentId,
                        windowStartTime,
                        requestTime);
        if (destinationEnrollmentCount
                >= PrivacyParams.getMaxDistinctEnrollmentsPerPublisherXDestinationInSource()) {
            debugReportApi.scheduleSourceSuccessDebugReport(source, dao);
            LogUtil.d(
                    "AsyncRegistrationQueueRunner: "
                            + (destinationType == EventSurfaceType.APP ? "App" : "Web")
                            + " enrollment count >= "
                            + "MaxDistinctEnrollmentsPerPublisherXDestinationInSource");
            return false;
        }
        return true;
    }

    @VisibleForTesting
    static boolean isTriggerAllowedToInsert(IMeasurementDao dao, Trigger trigger) {
        long triggerInsertedPerDestination;
        try {
            triggerInsertedPerDestination =
                    dao.getNumTriggersPerDestination(
                            trigger.getAttributionDestination(), trigger.getDestinationType());
        } catch (DatastoreException e) {
            LogUtil.e("Unable to fetch number of triggers currently registered per destination.");
            return false;
        }

        return triggerInsertedPerDestination < SystemHealthParams.getMaxTriggersPerDestination();
    }

    private static AsyncRegistration createAsyncRegistrationFromRedirect(
            AsyncRegistration asyncRegistration, Uri redirectUri) {
        return new AsyncRegistration.Builder()
                .setId(UUID.randomUUID().toString())
                .setRegistrationUri(redirectUri)
                .setWebDestination(asyncRegistration.getWebDestination())
                .setOsDestination(asyncRegistration.getOsDestination())
                .setRegistrant(asyncRegistration.getRegistrant())
                .setVerifiedDestination(asyncRegistration.getVerifiedDestination())
                .setTopOrigin(asyncRegistration.getTopOrigin())
                .setType(asyncRegistration.getType())
                .setSourceType(asyncRegistration.getSourceType())
                .setRequestTime(asyncRegistration.getRequestTime())
                .setRetryCount(0)
                .setDebugKeyAllowed(asyncRegistration.getDebugKeyAllowed())
                .setAdIdPermission(asyncRegistration.hasAdIdPermission())
                .setRegistrationId(asyncRegistration.getRegistrationId())
                .build();
    }

    private List<EventReport> generateFakeEventReports(Source source) {
        List<Source.FakeReport> fakeReports =
                mSourceNoiseHandler.assignAttributionModeAndGenerateFakeReports(source);
        return fakeReports.stream()
                .map(
                        fakeReport ->
                                new EventReport.Builder()
                                        .setSourceEventId(source.getEventId())
                                        .setReportTime(fakeReport.getReportingTime())
                                        .setTriggerData(fakeReport.getTriggerData())
                                        .setAttributionDestinations(fakeReport.getDestinations())
                                        .setEnrollmentId(source.getEnrollmentId())
                                        // The query for attribution check is from
                                        // (triggerTime - 30 days) to triggerTime and max expiry is
                                        // 30 days, so it's safe to choose triggerTime as source
                                        // event time so that it gets considered when the query is
                                        // fired for attribution rate limit check.
                                        .setTriggerTime(source.getEventTime())
                                        .setTriggerPriority(0L)
                                        .setTriggerDedupKey(null)
                                        .setSourceType(source.getSourceType())
                                        .setStatus(EventReport.Status.PENDING)
                                        .setRandomizedTriggerRate(
                                                mSourceNoiseHandler.getRandomAttributionProbability(
                                                        source))
                                        .setRegistrationOrigin(source.getRegistrationOrigin())
                                        .build())
                .collect(Collectors.toList());
    }

    @VisibleForTesting
    void insertSourceFromTransaction(Source source, IMeasurementDao dao) throws DatastoreException {
        List<EventReport> eventReports = generateFakeEventReports(source);
        if (!eventReports.isEmpty()) {
            mDebugReportApi.scheduleSourceNoisedDebugReport(source, dao);
        }
        try {
            dao.insertSource(source);
        } catch (DatastoreException e) {
            mDebugReportApi.scheduleSourceUnknownErrorDebugReport(source, dao);
            LogUtil.e(e, "Insert source to DB error, generate source-unknown-error report");
            throw new DatastoreException(
                    "Insert source to DB error, generate source-unknown-error report");
        }
        for (EventReport report : eventReports) {
            dao.insertEventReport(report);
        }
        // We want to account for attribution if fake report generation was considered
        // based on the probability. In that case the attribution mode will be NEVER
        // (empty fake reports state) or FALSELY (non-empty fake reports).
        if (source.getAttributionMode() != Source.AttributionMode.TRUTHFULLY) {
            // Attribution rate limits for app and web destinations are counted
            // separately, so add a fake report entry for each type of destination if
            // non-null.
            if (!Objects.isNull(source.getAppDestinations())) {
                for (Uri destination : source.getAppDestinations()) {
                    dao.insertAttribution(createFakeAttributionRateLimit(source, destination));
                }
            }

            if (!Objects.isNull(source.getWebDestinations())) {
                for (Uri destination : source.getWebDestinations()) {
                    dao.insertAttribution(createFakeAttributionRateLimit(source, destination));
                }
            }
        }
    }

    private void handleSuccess(
            AsyncRegistration asyncRegistration,
            AsyncFetchStatus asyncFetchStatus,
            AsyncRedirect asyncRedirect,
            IMeasurementDao dao)
            throws DatastoreException {
        // deleteAsyncRegistration will throw an exception & rollback the transaction if the record
        // is already deleted. This can happen if both fallback & regular job are running at the
        // same time or if deletion job deletes the records.
        dao.deleteAsyncRegistration(asyncRegistration.getId());
        if (asyncRedirect.getRedirects().isEmpty()) {
            return;
        }
        int maxRedirects = FlagsFactory.getFlags().getMeasurementMaxRegistrationRedirects();
        KeyValueData keyValueData =
                dao.getKeyValueData(
                        asyncRegistration.getRegistrationId(),
                        DataType.REGISTRATION_REDIRECT_COUNT);
        int currentCount = keyValueData.getRegistrationRedirectCount();
        if (currentCount == maxRedirects) {
            asyncFetchStatus.setRedirectError(true);
            return;
        }
        for (Uri uri : asyncRedirect.getRedirects()) {
            if (currentCount >= maxRedirects) {
                break;
            }
            dao.insertAsyncRegistration(
                    createAsyncRegistrationFromRedirect(asyncRegistration, uri));
            currentCount++;
        }
        keyValueData.setRegistrationRedirectCount(currentCount);
        dao.insertOrUpdateKeyValueData(keyValueData);
    }

    private void handleFailure(
            AsyncRegistration asyncRegistration,
            AsyncFetchStatus asyncFetchStatus,
            Set<Uri> failedOrigins,
            IMeasurementDao dao)
            throws DatastoreException {
        if (asyncFetchStatus.canRetry()) {
            LogUtil.d(
                    "AsyncRegistrationQueueRunner: "
                            + "async "
                            + asyncRegistration.getType()
                            + " registration will be queued for retry "
                            + "Fetch Status : "
                            + asyncFetchStatus.getResponseStatus());
            failedOrigins.add(BaseUriExtractor.getBaseUri(asyncRegistration.getRegistrationUri()));
            asyncRegistration.incrementRetryCount();
            dao.updateRetryCount(asyncRegistration);
        } else {
            LogUtil.d(
                    "AsyncRegistrationQueueRunner: "
                            + "async "
                            + asyncRegistration.getType()
                            + " registration will not be queued for retry. "
                            + "Fetch Status : "
                            + asyncFetchStatus.getResponseStatus());
            dao.deleteAsyncRegistration(asyncRegistration.getId());
        }
    }

    /**
     * {@link Attribution} generated from here will only be used for fake report attribution.
     *
     * @param source source to derive parameters from
     * @param destination destination for attribution
     * @return a fake {@link Attribution}
     */
    private Attribution createFakeAttributionRateLimit(Source source, Uri destination) {
        Optional<Uri> topLevelPublisher =
                getTopLevelPublisher(source.getPublisher(), source.getPublisherType());

        if (!topLevelPublisher.isPresent()) {
            throw new IllegalArgumentException(
                    String.format(
                            "insertAttributionRateLimit: getSourceAndDestinationTopPrivateDomains"
                                    + " failed. Publisher: %s; Attribution destination: %s",
                            source.getPublisher(), destination));
        }

        return new Attribution.Builder()
                .setSourceSite(topLevelPublisher.get().toString())
                .setSourceOrigin(source.getPublisher().toString())
                .setDestinationSite(destination.toString())
                .setDestinationOrigin(destination.toString())
                .setEnrollmentId(source.getEnrollmentId())
                .setTriggerTime(source.getEventTime())
                .setRegistrant(source.getRegistrant().toString())
                .setSourceId(source.getId())
                // Intentionally kept it as null because it's a fake attribution
                .setTriggerId(null)
                .build();
    }

    private static Optional<Uri> getTopLevelPublisher(
            Uri topOrigin, @EventSurfaceType int publisherType) {
        return publisherType == EventSurfaceType.APP
                ? Optional.of(topOrigin)
                : Web.topPrivateDomainAndScheme(topOrigin);
    }

    private Uri getPublisher(AsyncRegistration request) {
        return request.getRegistrant();
    }

    private void notifyTriggerContentProvider() {
        try (ContentProviderClient contentProviderClient =
                mContentResolver.acquireContentProviderClient(TriggerContentProvider.TRIGGER_URI)) {
            if (contentProviderClient != null) {
                contentProviderClient.insert(TriggerContentProvider.TRIGGER_URI, null);
            }
        } catch (RemoteException e) {
            LogUtil.e(e, "Trigger Content Provider invocation failed.");
        }
    }
}
