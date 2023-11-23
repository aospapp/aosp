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

package com.android.adservices.service.customaudience;

import static com.android.adservices.service.PhFlagsFixture.EXTENDED_FLEDGE_BACKGROUND_FETCH_NETWORK_CONNECT_TIMEOUT_MS;
import static com.android.adservices.service.PhFlagsFixture.EXTENDED_FLEDGE_BACKGROUND_FETCH_NETWORK_READ_TIMEOUT_MS;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.adservices.common.CommonFixture;
import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.LoggerFactory;
import com.android.adservices.concurrency.AdServicesExecutors;
import com.android.adservices.customaudience.DBCustomAudienceBackgroundFetchDataFixture;
import com.android.adservices.data.adselection.AppInstallDao;
import com.android.adservices.data.adselection.SharedStorageDatabase;
import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.data.customaudience.CustomAudienceDatabase;
import com.android.adservices.data.customaudience.DBCustomAudience;
import com.android.adservices.data.customaudience.DBCustomAudienceBackgroundFetchData;
import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class BackgroundFetchWorkerTest {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();
    private static final Context CONTEXT = ApplicationProvider.getApplicationContext();

    private final Flags mFlags = new BackgroundFetchWorkerTestFlags(true);
    private final ExecutorService mExecutorService = Executors.newFixedThreadPool(8);

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private PackageManager mPackageManagerMock;
    @Mock private EnrollmentDao mEnrollmentDaoMock;
    @Mock private Clock mClock;

    private CustomAudienceDao mCustomAudienceDaoSpy;
    private AppInstallDao mAppInstallDaoSpy;
    private BackgroundFetchRunner mBackgroundFetchRunnerSpy;
    private BackgroundFetchWorker mBackgroundFetchWorker;

    @Before
    public void setup() {
        mCustomAudienceDaoSpy =
                Mockito.spy(
                        Room.inMemoryDatabaseBuilder(CONTEXT, CustomAudienceDatabase.class)
                                .addTypeConverter(new DBCustomAudience.Converters(true))
                                .build()
                                .customAudienceDao());
        mAppInstallDaoSpy =
                Mockito.spy(
                        Room.inMemoryDatabaseBuilder(CONTEXT, SharedStorageDatabase.class)
                                .build()
                                .appInstallDao());

        mBackgroundFetchRunnerSpy =
                Mockito.spy(
                        new BackgroundFetchRunner(
                                mCustomAudienceDaoSpy,
                                mAppInstallDaoSpy,
                                mPackageManagerMock,
                                mEnrollmentDaoMock,
                                mFlags));

        mBackgroundFetchWorker =
                new BackgroundFetchWorker(
                        mCustomAudienceDaoSpy, mFlags, mBackgroundFetchRunnerSpy, mClock);
    }

    @Test
    public void testBackgroundFetchWorkerNullInputsCauseFailure() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new BackgroundFetchWorker(
                                null,
                                FlagsFactory.getFlagsForTest(),
                                mBackgroundFetchRunnerSpy,
                                mClock));

        assertThrows(
                NullPointerException.class,
                () ->
                        new BackgroundFetchWorker(
                                mCustomAudienceDaoSpy, null, mBackgroundFetchRunnerSpy, mClock));

        assertThrows(
                NullPointerException.class,
                () ->
                        new BackgroundFetchWorker(
                                mCustomAudienceDaoSpy,
                                FlagsFactory.getFlagsForTest(),
                                null,
                                mClock));

        assertThrows(
                NullPointerException.class,
                () ->
                        new BackgroundFetchWorker(
                                mCustomAudienceDaoSpy,
                                FlagsFactory.getFlagsForTest(),
                                mBackgroundFetchRunnerSpy,
                                null));
    }

    @Test
    public void testRunBackgroundFetchThrowsTimeoutDuringUpdates() {
        class FlagsWithSmallTimeout implements Flags {
            @Override
            public long getFledgeBackgroundFetchJobMaxRuntimeMs() {
                return 100L;
            }
        }

        class BackgroundFetchRunnerWithSleep extends BackgroundFetchRunner {
            BackgroundFetchRunnerWithSleep(
                    @NonNull CustomAudienceDao customAudienceDao, @NonNull Flags flags) {
                super(
                        customAudienceDao,
                        mAppInstallDaoSpy,
                        mPackageManagerMock,
                        mEnrollmentDaoMock,
                        flags);
            }

            @Override
            public void deleteExpiredCustomAudiences(@NonNull Instant jobStartTime) {
                // Do nothing
            }

            @Override
            public FluentFuture<?> updateCustomAudience(
                    @NonNull Instant jobStartTime,
                    @NonNull DBCustomAudienceBackgroundFetchData fetchData) {

                return FluentFuture.from(
                        AdServicesExecutors.getBlockingExecutor()
                                .submit(
                                        () -> {
                                            try {
                                                Thread.sleep(500L);
                                            } catch (InterruptedException e) {
                                                e.printStackTrace();
                                            }
                                            return null;
                                        }));
            }
        }

        Flags flagsWithSmallTimeout = new FlagsWithSmallTimeout();
        BackgroundFetchRunner backgroundFetchRunnerWithSleep =
                new BackgroundFetchRunnerWithSleep(mCustomAudienceDaoSpy, flagsWithSmallTimeout);
        BackgroundFetchWorker backgroundFetchWorkerThatTimesOut =
                new BackgroundFetchWorker(
                        mCustomAudienceDaoSpy,
                        flagsWithSmallTimeout,
                        backgroundFetchRunnerWithSleep,
                        mClock);

        // Mock a custom audience eligible for update
        DBCustomAudienceBackgroundFetchData fetchData =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW)
                        .build();
        doReturn(Arrays.asList(fetchData))
                .when(mCustomAudienceDaoSpy)
                .getActiveEligibleCustomAudienceBackgroundFetchData(any(), anyLong());

        when(mClock.instant()).thenReturn(Instant.now());

        // Time out while updating custom audiences
        ExecutionException expected =
                assertThrows(
                        ExecutionException.class,
                        () -> backgroundFetchWorkerThatTimesOut.runBackgroundFetch().get());
        assertThat(expected.getCause()).isInstanceOf(TimeoutException.class);
    }

    @Test
    public void testRunBackgroundFetchNothingToUpdate()
            throws ExecutionException, InterruptedException {
        assertTrue(
                mCustomAudienceDaoSpy
                        .getActiveEligibleCustomAudienceBackgroundFetchData(
                                CommonFixture.FIXED_NOW, 1)
                        .isEmpty());

        when(mClock.instant()).thenReturn(CommonFixture.FIXED_NOW);
        mBackgroundFetchWorker.runBackgroundFetch().get();

        verify(mBackgroundFetchRunnerSpy).deleteExpiredCustomAudiences(any());
        verify(mCustomAudienceDaoSpy).deleteAllExpiredCustomAudienceData(any());
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedOwnerCustomAudiences();
        verify(mCustomAudienceDaoSpy).deleteAllDisallowedOwnerCustomAudienceData(any(), any());
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedBuyerCustomAudiences();
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedPackageAppInstallEntries();
        verify(mCustomAudienceDaoSpy).deleteAllDisallowedBuyerCustomAudienceData(any(), any());
        verify(mBackgroundFetchRunnerSpy, never()).updateCustomAudience(any(), any());
    }

    @Test
    public void testRunBackgroundFetchNothingToUpdateNoFilters()
            throws ExecutionException, InterruptedException {
        Flags flagsFilteringDisabled = new BackgroundFetchWorkerTestFlags(false);
        mBackgroundFetchWorker =
                new BackgroundFetchWorker(
                        mCustomAudienceDaoSpy,
                        flagsFilteringDisabled,
                        mBackgroundFetchRunnerSpy,
                        mClock);
        assertTrue(
                mCustomAudienceDaoSpy
                        .getActiveEligibleCustomAudienceBackgroundFetchData(
                                CommonFixture.FIXED_NOW, 1)
                        .isEmpty());

        when(mClock.instant()).thenReturn(CommonFixture.FIXED_NOW);
        mBackgroundFetchWorker.runBackgroundFetch().get();

        verify(mBackgroundFetchRunnerSpy).deleteExpiredCustomAudiences(any());
        verify(mCustomAudienceDaoSpy).deleteAllExpiredCustomAudienceData(any());
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedOwnerCustomAudiences();
        verify(mCustomAudienceDaoSpy).deleteAllDisallowedOwnerCustomAudienceData(any(), any());
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedBuyerCustomAudiences();
        verify(mBackgroundFetchRunnerSpy, times(0)).deleteDisallowedPackageAppInstallEntries();
        verify(mCustomAudienceDaoSpy).deleteAllDisallowedBuyerCustomAudienceData(any(), any());
        verify(mBackgroundFetchRunnerSpy, never()).updateCustomAudience(any(), any());
    }

    @Test
    public void testRunBackgroundFetchUpdateOneCustomAudience()
            throws ExecutionException, InterruptedException {
        // Mock a single custom audience eligible for update
        DBCustomAudienceBackgroundFetchData fetchData =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW)
                        .build();
        doReturn(Arrays.asList(fetchData))
                .when(mCustomAudienceDaoSpy)
                .getActiveEligibleCustomAudienceBackgroundFetchData(any(), anyLong());
        doReturn(FluentFuture.from(immediateFuture(null)))
                .when(mBackgroundFetchRunnerSpy)
                .updateCustomAudience(any(), any());

        when(mClock.instant()).thenReturn(CommonFixture.FIXED_NOW);
        mBackgroundFetchWorker.runBackgroundFetch().get();

        verify(mBackgroundFetchRunnerSpy).deleteExpiredCustomAudiences(any());
        verify(mCustomAudienceDaoSpy).deleteAllExpiredCustomAudienceData(any());
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedOwnerCustomAudiences();
        verify(mCustomAudienceDaoSpy).deleteAllDisallowedOwnerCustomAudienceData(any(), any());
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedBuyerCustomAudiences();
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedPackageAppInstallEntries();
        verify(mCustomAudienceDaoSpy).deleteAllDisallowedBuyerCustomAudienceData(any(), any());
        verify(mBackgroundFetchRunnerSpy).updateCustomAudience(any(), any());
    }

    @Test
    public void testRunBackgroundFetchUpdateCustomAudiences()
            throws ExecutionException, InterruptedException {
        int numEligibleCustomAudiences = 12;

        // Mock a list of custom audiences eligible for update
        DBCustomAudienceBackgroundFetchData.Builder fetchDataBuilder =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW);
        List<DBCustomAudienceBackgroundFetchData> fetchDataList = new ArrayList<>();
        for (int i = 0; i < numEligibleCustomAudiences; i++) {
            fetchDataList.add(fetchDataBuilder.setName("ca" + i).build());
        }

        doReturn(fetchDataList)
                .when(mCustomAudienceDaoSpy)
                .getActiveEligibleCustomAudienceBackgroundFetchData(any(), anyLong());
        doReturn(FluentFuture.from(immediateFuture(null)))
                .when(mBackgroundFetchRunnerSpy)
                .updateCustomAudience(any(), any());

        when(mClock.instant()).thenReturn(CommonFixture.FIXED_NOW);
        mBackgroundFetchWorker.runBackgroundFetch().get();

        verify(mBackgroundFetchRunnerSpy).deleteExpiredCustomAudiences(any());
        verify(mCustomAudienceDaoSpy).deleteAllExpiredCustomAudienceData(any());
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedOwnerCustomAudiences();
        verify(mCustomAudienceDaoSpy).deleteAllDisallowedOwnerCustomAudienceData(any(), any());
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedBuyerCustomAudiences();
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedPackageAppInstallEntries();
        verify(mCustomAudienceDaoSpy).deleteAllDisallowedBuyerCustomAudienceData(any(), any());
        verify(mBackgroundFetchRunnerSpy, times(numEligibleCustomAudiences))
                .updateCustomAudience(any(), any());
    }

    @Test
    public void testRunBackgroundFetchChecksWorkInProgress()
            throws InterruptedException, ExecutionException {
        int numEligibleCustomAudiences = 16;
        CountDownLatch partialCompletionLatch = new CountDownLatch(numEligibleCustomAudiences / 4);

        // Mock a list of custom audiences eligible for update
        DBCustomAudienceBackgroundFetchData.Builder fetchDataBuilder =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW);
        List<DBCustomAudienceBackgroundFetchData> fetchDataList = new ArrayList<>();
        for (int i = 0; i < numEligibleCustomAudiences; i++) {
            fetchDataList.add(fetchDataBuilder.setName("ca" + i).build());
        }

        doReturn(fetchDataList)
                .when(mCustomAudienceDaoSpy)
                .getActiveEligibleCustomAudienceBackgroundFetchData(any(), anyLong());
        doAnswer(
                        unusedInvocation -> {
                            Thread.sleep(100);
                            partialCompletionLatch.countDown();
                            return FluentFuture.from(immediateFuture(null));
                        })
                .when(mBackgroundFetchRunnerSpy)
                .updateCustomAudience(any(), any());

        when(mClock.instant()).thenReturn(CommonFixture.FIXED_NOW);

        CountDownLatch bgfWorkStoppedLatch = new CountDownLatch(1);
        mExecutorService.execute(
                () -> {
                    try {
                        mBackgroundFetchWorker.runBackgroundFetch().get();
                    } catch (Exception exception) {
                        sLogger.e(
                                exception, "Exception encountered while running background fetch");
                    } finally {
                        bgfWorkStoppedLatch.countDown();
                    }
                });

        // Wait til updates are partially complete, then try running background fetch again and
        // verify nothing is done
        partialCompletionLatch.await();
        when(mClock.instant()).thenReturn(CommonFixture.FIXED_NOW.plusSeconds(1));
        mBackgroundFetchWorker.runBackgroundFetch().get();

        bgfWorkStoppedLatch.await();
        verify(mBackgroundFetchRunnerSpy).deleteExpiredCustomAudiences(any());
        verify(mCustomAudienceDaoSpy).deleteAllExpiredCustomAudienceData(any());
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedOwnerCustomAudiences();
        verify(mCustomAudienceDaoSpy).deleteAllDisallowedOwnerCustomAudienceData(any(), any());
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedBuyerCustomAudiences();
        verify(mBackgroundFetchRunnerSpy).deleteDisallowedPackageAppInstallEntries();
        verify(mCustomAudienceDaoSpy).deleteAllDisallowedBuyerCustomAudienceData(any(), any());
        verify(mBackgroundFetchRunnerSpy, times(numEligibleCustomAudiences))
                .updateCustomAudience(any(), any());
    }

    @Test
    public void testStopWorkWithoutRunningFetchDoesNothing() {
        // Verify no errors/exceptions thrown when no work in progress
        mBackgroundFetchWorker.stopWork();
    }

    @Test
    public void testStopWorkGracefullyStopsBackgroundFetch() throws Exception {
        int numEligibleCustomAudiences = 16;
        CountDownLatch partialCompletionLatch = new CountDownLatch(numEligibleCustomAudiences / 4);

        // Mock a list of custom audiences eligible for update
        DBCustomAudienceBackgroundFetchData.Builder fetchDataBuilder =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW);
        List<DBCustomAudienceBackgroundFetchData> fetchDataList = new ArrayList<>();
        for (int i = 0; i < numEligibleCustomAudiences; i++) {
            fetchDataList.add(fetchDataBuilder.setName("ca" + i).build());
        }

        doReturn(fetchDataList)
                .when(mCustomAudienceDaoSpy)
                .getActiveEligibleCustomAudienceBackgroundFetchData(any(), anyLong());
        doAnswer(
                        unusedInvocation -> {
                            Thread.sleep(100);
                            partialCompletionLatch.countDown();
                            return FluentFuture.from(immediateVoidFuture());
                        })
                .when(mBackgroundFetchRunnerSpy)
                .updateCustomAudience(any(), any());

        when(mClock.instant()).thenReturn(CommonFixture.FIXED_NOW);

        ListenableFuture<Void> backgrounFetchResult = mBackgroundFetchWorker.runBackgroundFetch();

        // Wait til updates are partially complete, then try stopping background fetch
        partialCompletionLatch.await();
        mBackgroundFetchWorker.stopWork();
        // stopWork() should notify to the worker that the work should end so the future
        // should complete within the time required to update the custom audiences
        backgrounFetchResult.get(
                100 * (numEligibleCustomAudiences * 3 / 4) + 100, TimeUnit.SECONDS);
    }

    @Test
    public void testStopWorkPreemptsDataUpdates() throws Exception {
        int numEligibleCustomAudiences = 16;
        CountDownLatch beforeUpdatingCasLatch = new CountDownLatch(numEligibleCustomAudiences / 4);

        // Mock a list of custom audiences eligible for update
        DBCustomAudienceBackgroundFetchData.Builder fetchDataBuilder =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW);
        List<DBCustomAudienceBackgroundFetchData> fetchDataList = new ArrayList<>();
        for (int i = 0; i < numEligibleCustomAudiences; i++) {
            fetchDataList.add(fetchDataBuilder.setName("ca" + i).build());
        }

        // Ensuring that stopWork is called before the data update process
        doAnswer(
                        unusedInvocation -> {
                            beforeUpdatingCasLatch.await();
                            return fetchDataList;
                        })
                .when(mCustomAudienceDaoSpy)
                .getActiveEligibleCustomAudienceBackgroundFetchData(any(), anyLong());
        doAnswer(
                        unusedInvocation -> {
                            Thread.sleep(100);
                            return null;
                        })
                .when(mBackgroundFetchRunnerSpy)
                .updateCustomAudience(any(), any());

        when(mClock.instant()).thenReturn(CommonFixture.FIXED_NOW);

        ListenableFuture<Void> backgrounFetchResult = mBackgroundFetchWorker.runBackgroundFetch();

        // Wait til updates are partially complete, then try stopping background fetch
        mBackgroundFetchWorker.stopWork();
        beforeUpdatingCasLatch.countDown();
        // stopWork() called before updating the data should cause immediate termination
        // waiting for 200ms to handle thread scheduling delays.
        // The important check is that the time is less than the time of updating all CAs
        backgrounFetchResult.get(200, TimeUnit.MILLISECONDS);
    }

    @Test
    public void testRunBackgroundFetchInSequence() throws InterruptedException, ExecutionException {
        int numEligibleCustomAudiences = 16;
        CountDownLatch completionLatch = new CountDownLatch(numEligibleCustomAudiences / 2);

        // Mock two lists of custom audiences eligible for update
        DBCustomAudienceBackgroundFetchData.Builder fetchDataBuilder =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW);
        List<DBCustomAudienceBackgroundFetchData> fetchDataList1 = new ArrayList<>();
        List<DBCustomAudienceBackgroundFetchData> fetchDataList2 = new ArrayList<>();
        for (int i = 0; i < numEligibleCustomAudiences; i++) {
            DBCustomAudienceBackgroundFetchData fetchData =
                    fetchDataBuilder.setName("ca" + i).build();
            if (i < numEligibleCustomAudiences / 2) {
                fetchDataList1.add(fetchData);
            } else {
                fetchDataList2.add(fetchData);
            }
        }

        // Count the number of times updateCustomAudience is run
        AtomicInteger completionCount = new AtomicInteger(0);

        // Return the first list the first time, and the second list in the second call
        doReturn(fetchDataList1)
                .doReturn(fetchDataList2)
                .when(mCustomAudienceDaoSpy)
                .getActiveEligibleCustomAudienceBackgroundFetchData(any(), anyLong());
        doAnswer(
                        unusedInvocation -> {
                            completionLatch.countDown();
                            completionCount.getAndIncrement();
                            return FluentFuture.from(immediateFuture(null));
                        })
                .when(mBackgroundFetchRunnerSpy)
                .updateCustomAudience(any(), any());

        when(mClock.instant()).thenReturn(CommonFixture.FIXED_NOW);

        CountDownLatch bgfWorkStoppedLatch = new CountDownLatch(1);
        mExecutorService.execute(
                () -> {
                    try {
                        mBackgroundFetchWorker.runBackgroundFetch().get();
                    } catch (Exception exception) {
                        sLogger.e(
                                exception, "Exception encountered while running background fetch");
                    } finally {
                        bgfWorkStoppedLatch.countDown();
                    }
                });

        // Wait til updates are complete, then try running background fetch again and
        // verify the second run updates more custom audiences successfully
        completionLatch.await();
        bgfWorkStoppedLatch.await();
        when(mClock.instant()).thenReturn(CommonFixture.FIXED_NOW.plusSeconds(1));
        mBackgroundFetchWorker.runBackgroundFetch().get();

        verify(mBackgroundFetchRunnerSpy, times(2)).deleteExpiredCustomAudiences(any());
        verify(mCustomAudienceDaoSpy, times(2)).deleteAllExpiredCustomAudienceData(any());
        verify(mBackgroundFetchRunnerSpy, times(2)).deleteDisallowedOwnerCustomAudiences();
        verify(mCustomAudienceDaoSpy, times(2))
                .deleteAllDisallowedOwnerCustomAudienceData(any(), any());
        verify(mBackgroundFetchRunnerSpy, times(2)).deleteDisallowedBuyerCustomAudiences();
        verify(mBackgroundFetchRunnerSpy, times(2)).deleteDisallowedPackageAppInstallEntries();
        verify(mCustomAudienceDaoSpy, times(2))
                .deleteAllDisallowedBuyerCustomAudienceData(any(), any());
        verify(mBackgroundFetchRunnerSpy, times(numEligibleCustomAudiences))
                .updateCustomAudience(any(), any());
        assertThat(completionCount.get()).isEqualTo(numEligibleCustomAudiences);
    }

    private static class BackgroundFetchWorkerTestFlags implements Flags {
        private final boolean mFledgeAdSelectionFilteringEnabled;

        BackgroundFetchWorkerTestFlags(boolean fledgeAdSelectionFilteringEnabled) {
            mFledgeAdSelectionFilteringEnabled = fledgeAdSelectionFilteringEnabled;
        }

        @Override
        public int getFledgeBackgroundFetchThreadPoolSize() {
            return 4;
        }

        @Override
        public boolean getFledgeAdSelectionFilteringEnabled() {
            return mFledgeAdSelectionFilteringEnabled;
        }

        @Override
        public int getFledgeBackgroundFetchNetworkConnectTimeoutMs() {
            return EXTENDED_FLEDGE_BACKGROUND_FETCH_NETWORK_CONNECT_TIMEOUT_MS;
        }

        @Override
        public int getFledgeBackgroundFetchNetworkReadTimeoutMs() {
            return EXTENDED_FLEDGE_BACKGROUND_FETCH_NETWORK_READ_TIMEOUT_MS;
        }
    }
}
