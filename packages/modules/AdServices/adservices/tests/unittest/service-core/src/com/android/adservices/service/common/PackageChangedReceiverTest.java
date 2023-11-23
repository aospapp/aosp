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

package com.android.adservices.service.common;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyLong;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyZeroInteractions;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.adservices.AdServicesCommon;
import com.android.adservices.data.adselection.AppInstallDao;
import com.android.adservices.data.adselection.SharedStorageDatabase;
import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.data.customaudience.CustomAudienceDatabase;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.compat.PackageManagerCompatUtils;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.measurement.MeasurementImpl;
import com.android.adservices.service.topics.AppUpdateManager;
import com.android.adservices.service.topics.BlockedTopicsManager;
import com.android.adservices.service.topics.CacheManager;
import com.android.adservices.service.topics.EpochManager;
import com.android.adservices.service.topics.TopicsWorker;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.modules.utils.build.SdkLevel;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Unit test for {@link com.android.adservices.service.common.PackageChangedReceiver}. */
@SmallTest
public class PackageChangedReceiverTest {
    private static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final String SAMPLE_PACKAGE = "com.example.measurement.sampleapp";
    private static final String PACKAGE_SCHEME = "package:";
    private static final int BACKGROUND_THREAD_TIMEOUT_MS = 500;
    private static final int DEFAULT_PACKAGE_UID = -1;

    @Mock EpochManager mMockEpochManager;
    @Mock CacheManager mMockCacheManager;
    @Mock BlockedTopicsManager mBlockedTopicsManager;
    @Mock AppUpdateManager mMockAppUpdateManager;
    @Mock CustomAudienceDatabase mCustomAudienceDatabaseMock;
    @Mock SharedStorageDatabase mSharedStorageDatabaseMock;
    @Mock CustomAudienceDao mCustomAudienceDaoMock;
    @Mock AppInstallDao mAppInstallDaoMock;
    @Mock ConsentManager mConsentManager;
    @Mock Flags mMockFlags;

    private TopicsWorker mSpyTopicsWorker;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);

        // Mock TopicsWorker to test app update flow in topics API.
        mSpyTopicsWorker =
                Mockito.spy(
                        new TopicsWorker(
                                mMockEpochManager,
                                mMockCacheManager,
                                mBlockedTopicsManager,
                                mMockAppUpdateManager,
                                FlagsFactory.getFlagsForTest()));
        doReturn(true).when(mMockFlags).getFledgeAdSelectionFilteringEnabled();
        PackageChangedReceiver.enableReceiver(sContext, mMockFlags);
    }

    private PackageChangedReceiver createSpyPackageReceiverForMeasurement() {
        PackageChangedReceiver spyReceiver = Mockito.spy(new PackageChangedReceiver());
        doNothingForTopics(spyReceiver);
        doNothingForFledge(spyReceiver);
        doNothingForConsent(spyReceiver);
        return spyReceiver;
    }

    private PackageChangedReceiver createSpyPackageReceiverForTopics() {
        PackageChangedReceiver spyReceiver = Mockito.spy(new PackageChangedReceiver());
        doNothingForMeasurement(spyReceiver);
        doNothingForFledge(spyReceiver);
        doNothingForConsent(spyReceiver);
        return spyReceiver;
    }

    private PackageChangedReceiver createSpyPackageReceiverForFledge() {
        PackageChangedReceiver spyReceiver = Mockito.spy(new PackageChangedReceiver());
        doNothingForMeasurement(spyReceiver);
        doNothingForTopics(spyReceiver);
        doNothingForConsent(spyReceiver);
        return spyReceiver;
    }

    private PackageChangedReceiver createSpyPackageReceiverForConsent() {
        PackageChangedReceiver spyReceiver = Mockito.spy(new PackageChangedReceiver());
        doNothingForMeasurement(spyReceiver);
        doNothingForTopics(spyReceiver);
        doNothingForFledge(spyReceiver);
        return spyReceiver;
    }

    private PackageChangedReceiver createSpyPackageReceiverForExtServices() {
        PackageChangedReceiver spyReceiver = Mockito.spy(new PackageChangedReceiver());
        doNothingForMeasurement(spyReceiver);
        doNothingForTopics(spyReceiver);
        doNothingForFledge(spyReceiver);
        doNothingForConsent(spyReceiver);
        return spyReceiver;
    }

    private void doNothingForMeasurement(PackageChangedReceiver receiver) {
        doNothing().when(receiver).measurementOnPackageFullyRemoved(any(), any());
        doNothing().when(receiver).measurementOnPackageAdded(any(), any());
        doNothing().when(receiver).measurementOnPackageDataCleared(any(), any());
    }

    private void doNothingForTopics(PackageChangedReceiver receiver) {
        doNothing().when(receiver).topicsOnPackageFullyRemoved(any(), any());
        doNothing().when(receiver).topicsOnPackageAdded(any(), any());
    }

    private void doNothingForFledge(PackageChangedReceiver receiver) {
        doNothing().when(receiver).fledgeOnPackageFullyRemovedOrDataCleared(any(), any());
    }

    private void doNothingForConsent(PackageChangedReceiver receiver) {
        doNothing().when(receiver).consentOnPackageFullyRemoved(any(), any(), anyInt());
    }

    // This intent is sent from the AdServices system service.
    private Intent createIntentSentByAdServiceSystemService(String value) {
        Intent intent = new Intent();
        intent.setAction(PackageChangedReceiver.PACKAGE_CHANGED_BROADCAST);
        intent.setData(Uri.parse(PACKAGE_SCHEME + SAMPLE_PACKAGE));
        intent.putExtra(PackageChangedReceiver.ACTION_KEY, value);
        intent.putExtra(Intent.EXTRA_UID, 0);

        return intent;
    }

    // The ExtServices module registers PackageChangedReceiver to receive these broadcasts from the
    // system.
    private Intent createIntentSentBySystem(String action) {
        Intent intent = new Intent();
        intent.setAction(action);
        intent.setData(Uri.parse(PACKAGE_SCHEME + SAMPLE_PACKAGE));
        return intent;
    }

    @Test
    public void testReceivePackageFullyRemoved_topicsKillSwitchOff() throws Exception {
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForTopicsKillSwitchOff(intent);
    }

    @Test
    public void testReceivePackageFullyRemoved_topicsKillSwitchOff_backCompat() throws Exception {
        Intent intent = createIntentSentBySystem(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForTopicsKillSwitchOff(intent);
    }

    @Test
    public void testReceivePackageFullyRemoved_topicsKillSwitchOn() throws Exception {
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForTopicsKillSwitchOn(intent);
    }

    @Test
    public void testReceivePackageFullyRemoved_topicsKillSwitchOn_backCompat() throws Exception {
        Intent intent = createIntentSentBySystem(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForTopicsKillSwitchOn(intent);
    }

    @Test
    public void testReceivePackageFullyRemoved_measurementKillSwitchOff() throws Exception {
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForMsmtKillSwitchOff(intent);
    }

    @Test
    public void testReceivePackageFullyRemoved_measurementKillSwitchOff_backCompat()
            throws Exception {
        Intent intent = createIntentSentBySystem(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForMsmtKillSwitchOff(intent);
    }

    @Test
    public void testReceivePackageFullyRemoved_measurementKillSwitchOn() throws Exception {
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForMsmtKillSwitchOn(intent);
    }

    @Test
    public void testReceivePackageFullyRemoved_measurementKillSwitchOn_backCompat()
            throws Exception {
        Intent intent = createIntentSentBySystem(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForMsmtKillSwitchOn(intent);
    }

    @Test
    public void testReceivePackageFullyRemoved_fledgeKillSwitchOff() throws Exception {
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForFledgeKillSwitchOff(intent, true);
    }

    @Test
    public void testReceivePackageFullyRemoved_fledgeKillSwitchOffFilteringDisabled()
            throws Exception {
        doReturn(false).when(mMockFlags).getFledgeAdSelectionFilteringEnabled();
        PackageChangedReceiver.enableReceiver(sContext, mMockFlags);
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForFledgeKillSwitchOff(intent, false);
    }

    @Test
    public void testReceivePackageFullyRemoved_fledgeKillSwitchOff_backCompat() throws Exception {
        Intent intent = createIntentSentBySystem(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForFledgeKillSwitchOff(intent, true);
    }

    @Test
    public void testReceivePackageFullyRemoved_fledgeKillSwitchOn() {
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForFledgeKillSwitchOn(intent);
    }

    @Test
    public void testReceivePackageFullyRemoved_fledgeKillSwitchOn_backCompat() {
        Intent intent = createIntentSentBySystem(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForFledgeKillSwitchOn(intent);
    }

    @Test
    public void testReceivePackageFullyRemoved_consent() throws Exception {
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_FULLY_REMOVED);
        runPackageFullyRemovedForConsent(intent);
    }

    /**
     * Tests that when no packageUid is present via the Intent Extra, consent data for this app is
     * cleared when the app is removed.
     */
    @Test
    public void testReceivePackageFullyRemoved_consent_noPackageUid()
            throws InterruptedException, IOException {
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_FULLY_REMOVED);
        intent.removeExtra(Intent.EXTRA_UID);

        validateConsentWhenPackageUidAbsent(intent, false);
        validateConsentWhenPackageUidAbsent(intent, true);
    }

    /**
     * Tests that when packageUid is explicitly set to the default value via the Intent Extra,
     * consent data for this app is cleared when the app is removed.
     */
    @Test
    public void testReceivePackageFullyRemoved_consent_packageUidIsExplicitlyDefault()
            throws InterruptedException, IOException {
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_FULLY_REMOVED);
        intent.putExtra(Intent.EXTRA_UID, DEFAULT_PACKAGE_UID);

        validateConsentWhenPackageUidAbsent(intent, false);
        validateConsentWhenPackageUidAbsent(intent, true);
    }

    @Test
    public void testReceivePackageFullyRemoved_consent_noPackageUid_backCompat()
            throws InterruptedException, IOException {
        Intent intent = createIntentSentBySystem(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        intent.removeExtra(Intent.EXTRA_UID);

        validateConsentWhenPackageUidAbsent(intent, false);
        validateConsentWhenPackageUidAbsent(intent, true);
    }

    @Test
    public void testReceivePackageFullyRemoved_consent_packageUidIsExplicitlyDefault_backCompat()
            throws InterruptedException, IOException {
        Intent intent = createIntentSentBySystem(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        intent.putExtra(Intent.EXTRA_UID, DEFAULT_PACKAGE_UID);

        validateConsentWhenPackageUidAbsent(intent, false);
        validateConsentWhenPackageUidAbsent(intent, true);
    }

    @Test
    public void testReceivePackageAdded_topics() throws Exception {
        runPackageAddedForTopics(
                createIntentSentByAdServiceSystemService(PackageChangedReceiver.PACKAGE_ADDED));
    }

    @Test
    public void testReceivePackageAdded_measurementKillSwitchOff() throws Exception {
        Intent intent =
                createIntentSentByAdServiceSystemService(PackageChangedReceiver.PACKAGE_ADDED);
        runPackageAddedMsmtKillSwitchOff(intent);
    }

    @Test
    public void testReceivePackageAdded_measurementKillSwitchOn() throws Exception {
        Intent intent =
                createIntentSentByAdServiceSystemService(PackageChangedReceiver.PACKAGE_ADDED);
        runPackageAddedForMsmtKillSwitchOn(intent);
    }

    @Test
    public void testReceivePackageDataCleared_measurementKillSwitchOff() throws Exception {
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_DATA_CLEARED);
        runPackageDataClearedForMsmtKillSwitchOff(intent);
    }

    @Test
    public void testReceivePackageDataCleared_measurementKillSwitchOff_backCompat()
            throws Exception {
        Intent intent = createIntentSentBySystem(Intent.ACTION_PACKAGE_DATA_CLEARED);
        runPackageDataClearedForMsmtKillSwitchOff(intent);
    }

    @Test
    public void testReceivePackageDataCleared_measurementKillSwitchOn() throws Exception {
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_DATA_CLEARED);
        runPackageDataClearedForMsmtKillSwitchOn(intent);
    }

    @Test
    public void testReceivePackageDataCleared_measurementKillSwitchOn_backCompat()
            throws Exception {
        Intent intent = createIntentSentBySystem(Intent.ACTION_PACKAGE_DATA_CLEARED);
        runPackageDataClearedForMsmtKillSwitchOn(intent);
    }

    @Test
    public void testReceivePackageDataCleared_fledgeKillSwitchOff() throws Exception {
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_DATA_CLEARED);
        runPackageDataClearedForFledgeKillSwitchOff(intent);
    }

    @Test
    public void testReceivePackageDataCleared_fledgeKillSwitchOff_backCompat() throws Exception {
        Intent intent = createIntentSentBySystem(Intent.ACTION_PACKAGE_DATA_CLEARED);
        runPackageDataClearedForFledgeKillSwitchOff(intent);
    }

    @Test
    public void testReceivePackageDataCleared_fledgeKillSwitchOn() {
        Intent intent =
                createIntentSentByAdServiceSystemService(
                        PackageChangedReceiver.PACKAGE_DATA_CLEARED);
        runPackageDataClearedForFledgeKillSwitchOn(intent);
    }

    @Test
    public void testReceivePackageDataCleared_fledgeKillSwitchOn_backCompat() {
        Intent intent = createIntentSentBySystem(Intent.ACTION_PACKAGE_DATA_CLEARED);
        runPackageDataClearedForFledgeKillSwitchOn(intent);
    }

    @Test
    public void testPackageChangedReceiverDisabled() {
        Context mockContext = mock(Context.class);
        PackageManager mockPackageManager = mock(PackageManager.class);
        doReturn(mockPackageManager).when(mockContext).getPackageManager();

        PackageChangedReceiver.disableReceiver(mockContext, mMockFlags);

        ArgumentCaptor<ComponentName> cap = ArgumentCaptor.forClass(ComponentName.class);
        verify(mockPackageManager)
                .setComponentEnabledSetting(
                        cap.capture(),
                        eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                        anyInt());
        assertThat(cap.getValue().getClassName()).isEqualTo(PackageChangedReceiver.class.getName());
    }

    private void runPackageFullyRemovedForTopicsKillSwitchOff(Intent intent) throws Exception {
        // Start a mockitoSession to mock static method
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(TopicsWorker.class)
                        .spyStatic(FlagsFactory.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        try {
            long epochId = 1;

            // Kill switch is off.
            doReturn(false).when(mMockFlags).getTopicsKillSwitch();

            // Mock static method FlagsFactory.getFlags() to return Mock Flags.
            when(FlagsFactory.getFlags()).thenReturn(mMockFlags);

            // Stubbing TopicsWorker.getInstance() to return mocked TopicsWorker instance
            doReturn(mSpyTopicsWorker).when(() -> TopicsWorker.getInstance(any()));
            doReturn(epochId).when(mMockEpochManager).getCurrentEpochId();

            // Initialize package receiver meant for Topics
            PackageChangedReceiver spyReceiver = createSpyPackageReceiverForTopics();
            spyReceiver.onReceive(sContext, intent);

            // Grant some time to allow background thread to execute
            Thread.sleep(BACKGROUND_THREAD_TIMEOUT_MS);

            // Verify method in AppUpdateManager is invoked
            // getCurrentEpochId() is invoked twice: handleAppUninstallation() + loadCache()
            // Note that only package name is passed into following methods.
            verify(mMockEpochManager, times(2)).getCurrentEpochId();
            verify(mMockAppUpdateManager)
                    .handleAppUninstallationInRealTime(Uri.parse(SAMPLE_PACKAGE), epochId);
        } finally {
            session.finishMocking();
        }
    }

    private void runPackageFullyRemovedForTopicsKillSwitchOn(Intent intent) throws Exception {
        // Start a mockitoSession to mock static method
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(TopicsWorker.class)
                        .spyStatic(FlagsFactory.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        try {
            // Kill switch is on.
            doReturn(true).when(mMockFlags).getTopicsKillSwitch();

            // Mock static method FlagsFactory.getFlags() to return Mock Flags.
            when(FlagsFactory.getFlags()).thenReturn(mMockFlags);

            // Initialize package receiver meant for Topics and execute
            createSpyPackageReceiverForTopics().onReceive(sContext, intent);

            // Grant some time to allow background thread to execute
            Thread.sleep(BACKGROUND_THREAD_TIMEOUT_MS);

            // When the kill switch is on, there is no Topics related work.
            verify(mSpyTopicsWorker, never()).handleAppUninstallation(any());
        } finally {
            session.finishMocking();
        }
    }

    private void runPackageFullyRemovedForMsmtKillSwitchOff(Intent intent) throws Exception {
        // Start a mockitoSession to mock static method
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(MeasurementImpl.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        try {
            // Kill switch is off.
            doReturn(false).when(mMockFlags).getMeasurementReceiverDeletePackagesKillSwitch();

            // Mock static method FlagsFactory.getFlags() to return Mock Flags.
            when(FlagsFactory.getFlags()).thenReturn(mMockFlags);

            // Mock static method MeasurementImpl.getInstance that executes on a separate thread
            MeasurementImpl mockMeasurementImpl = mock(MeasurementImpl.class);
            doReturn(mockMeasurementImpl).when(() -> MeasurementImpl.getInstance(any()));

            // Initialize package receiver meant for Measurement
            PackageChangedReceiver spyReceiver = createSpyPackageReceiverForMeasurement();
            spyReceiver.onReceive(sContext, intent);

            // Verify only measurement fully removed method was executed from measurement methods
            verify(spyReceiver, never()).measurementOnPackageDataCleared(any(), any());
            verify(spyReceiver, times(1)).measurementOnPackageFullyRemoved(any(), any());
            verify(spyReceiver, never()).measurementOnPackageAdded(any(), any());

            // Grant some time to allow background thread to execute
            Thread.sleep(BACKGROUND_THREAD_TIMEOUT_MS);

            // Verify method inside measurement background thread executes
            verify(mockMeasurementImpl, times(1)).deletePackageRecords(any());
        } finally {
            session.finishMocking();
        }
    }

    private void runPackageFullyRemovedForMsmtKillSwitchOn(Intent intent) throws Exception {
        // Start a mockitoSession to mock static method
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(MeasurementImpl.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        try {
            // Kill Switch is on.
            doReturn(true).when(mMockFlags).getMeasurementReceiverDeletePackagesKillSwitch();

            // Mock static method FlagsFactory.getFlags() to return Mock Flags.
            doReturn(mMockFlags).when(FlagsFactory::getFlags);

            // Mock static method MeasurementImpl.getInstance that executes on a separate thread
            MeasurementImpl mockMeasurementImpl = mock(MeasurementImpl.class);
            doReturn(mockMeasurementImpl).when(() -> MeasurementImpl.getInstance(any()));

            // Initialize package receiver meant for Measurement
            PackageChangedReceiver spyReceiver = createSpyPackageReceiverForMeasurement();
            spyReceiver.onReceive(sContext, intent);

            // Verify only measurement fully removed method was executed from measurement methods
            verify(spyReceiver, never()).measurementOnPackageDataCleared(any(), any());
            verify(spyReceiver, times(1)).measurementOnPackageFullyRemoved(any(), any());
            verify(spyReceiver, never()).measurementOnPackageAdded(any(), any());

            // Allow background thread to execute
            Thread.sleep(BACKGROUND_THREAD_TIMEOUT_MS);

            // Verify method inside measurement background thread does not execute
            verify(mockMeasurementImpl, never()).deletePackageRecords(any());
        } finally {
            session.finishMocking();
        }
    }

    private void runPackageFullyRemovedForFledgeKillSwitchOff(
            Intent intent, boolean filteringEnabled) throws Exception {
        // Start a mockitoSession to mock static method
        // Lenient added to allow easy disabling of other APIs' methods
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .mockStatic(FlagsFactory.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();
        try {
            // Kill switch is off; service is enabled
            doReturn(false).when(mMockFlags).getFledgeCustomAudienceServiceKillSwitch();
            doReturn(mMockFlags).when(FlagsFactory::getFlags);

            // Mock static database .getInstance() methods executed on a separate thread
            doReturn(mCustomAudienceDaoMock).when(mCustomAudienceDatabaseMock).customAudienceDao();
            doReturn(mAppInstallDaoMock).when(mSharedStorageDatabaseMock).appInstallDao();

            CountDownLatch caCompletionLatch = new CountDownLatch(1);
            Answer<Void> caAnswer =
                    unusedInvocation -> {
                        caCompletionLatch.countDown();
                        return null;
                    };
            doAnswer(caAnswer).when(mCustomAudienceDaoMock).deleteCustomAudienceDataByOwner(any());
            CountDownLatch appInstallCompletionLatch = new CountDownLatch(1);
            Answer<Void> appInstallanswer =
                    unusedInvocation -> {
                        appInstallCompletionLatch.countDown();
                        return null;
                    };
            doAnswer(appInstallanswer).when(mAppInstallDaoMock).deleteByPackageName(any());

            // Initialize package receiver meant for FLEDGE
            PackageChangedReceiver spyReceiver = createSpyPackageReceiverForFledge();
            doReturn(mCustomAudienceDatabaseMock)
                    .when(spyReceiver)
                    .getCustomAudienceDatabase(any());
            doReturn(mSharedStorageDatabaseMock).when(spyReceiver).getSharedStorageDatabase(any());
            spyReceiver.onReceive(sContext, intent);

            verify(spyReceiver).fledgeOnPackageFullyRemovedOrDataCleared(any(), any());

            // Verify method inside background thread executes
            assertThat(caCompletionLatch.await(500, TimeUnit.MILLISECONDS)).isTrue();
            verify(mCustomAudienceDaoMock).deleteCustomAudienceDataByOwner(any());
            if (filteringEnabled) {
                assertThat(appInstallCompletionLatch.await(500, TimeUnit.MILLISECONDS)).isTrue();
                verify(mAppInstallDaoMock).deleteByPackageName(any());
            } else {
                verifyZeroInteractions(mAppInstallDaoMock);
            }
        } finally {
            session.finishMocking();
        }
    }

    private void runPackageFullyRemovedForFledgeKillSwitchOn(Intent intent) {
        // Start a mockitoSession to mock static method
        // Lenient added to allow easy disabling of other APIs' methods
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .mockStatic(FlagsFactory.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();
        try {
            // Kill switch is on; service is disabled
            doReturn(true).when(mMockFlags).getFledgeCustomAudienceServiceKillSwitch();
            doReturn(mMockFlags).when(FlagsFactory::getFlags);

            // Initialize package receiver meant for FLEDGE
            PackageChangedReceiver spyReceiver = createSpyPackageReceiverForFledge();
            spyReceiver.onReceive(sContext, intent);

            verify(spyReceiver).fledgeOnPackageFullyRemovedOrDataCleared(any(), any());

            // Verify no executions
            verify(spyReceiver, never()).getCustomAudienceDatabase(any());
            verifyZeroInteractions(mCustomAudienceDatabaseMock, mCustomAudienceDaoMock);
            verify(spyReceiver, never()).getSharedStorageDatabase(any());
            verifyZeroInteractions(mSharedStorageDatabaseMock, mAppInstallDaoMock);
        } finally {
            session.finishMocking();
        }
    }

    private void runPackageFullyRemovedForConsent(Intent intent) throws Exception {
        // Start a mockitoSession to mock static method
        // Lenient added to allow easy disabling of other APIs' methods
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .mockStatic(ConsentManager.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();
        try {
            // Mock static method AppConsentDao.getInstance() executed on a separate thread
            doReturn(mConsentManager).when(() -> ConsentManager.getInstance(any()));

            CountDownLatch completionLatch = new CountDownLatch(1);
            doAnswer(
                            unusedInvocation -> {
                                completionLatch.countDown();
                                return null;
                            })
                    .when(mConsentManager)
                    .clearConsentForUninstalledApp(any(), anyInt());

            // Initialize package receiver meant for Consent
            PackageChangedReceiver spyReceiver = createSpyPackageReceiverForConsent();
            spyReceiver.onReceive(sContext, intent);

            verify(spyReceiver).consentOnPackageFullyRemoved(any(), any(), anyInt());

            // Verify method inside background thread executes
            assertThat(completionLatch.await(500, TimeUnit.MILLISECONDS)).isTrue();
            verify(mConsentManager).clearConsentForUninstalledApp(any(), anyInt());
        } finally {
            session.finishMocking();
        }
    }

    private void validateConsentWhenPackageUidAbsent(Intent intent, boolean isPackageStillInstalled)
            throws IOException, InterruptedException {
        // Start a mockitoSession to mock static method
        // Lenient added to allow easy disabling of other APIs' methods
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .mockStatic(ConsentManager.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();
        try {
            // Mock static method AppConsentDao.getInstance() executed on a separate thread
            doReturn(mConsentManager).when(() -> ConsentManager.getInstance(any()));

            // Track whether the clearConsentForUninstalledApp was ever invoked.
            // Use a CountDownLatch since this invocation happens on a background thread.
            CountDownLatch completionLatch = new CountDownLatch(1);
            doAnswer(
                            unusedInvocation -> {
                                completionLatch.countDown();
                                return null;
                            })
                    .when(mConsentManager)
                    .clearConsentForUninstalledApp(anyString());

            // Initialize package receiver meant for Consent
            PackageChangedReceiver spyReceiver = createSpyPackageReceiverForConsent();
            doReturn(isPackageStillInstalled)
                    .when(spyReceiver)
                    .isPackageStillInstalled(any(), anyString());

            // Invoke the onReceive method to test the behavior
            spyReceiver.onReceive(sContext, intent);

            // Package UID is expected to be -1 if there is no EXTRA_UID in the Intent's Extra.
            verify(spyReceiver).consentOnPackageFullyRemoved(any(), any(), eq(DEFAULT_PACKAGE_UID));

            // Verify method inside background thread executes if package is no longer installed
            // and that it does not execute if the package is still installed.
            assertThat(completionLatch.await(500, TimeUnit.MILLISECONDS))
                    .isEqualTo(!isPackageStillInstalled);
        } finally {
            session.finishMocking();
        }
    }

    private void runPackageAddedForTopics(Intent intent) throws Exception {
        // Start a mockitoSession to mock static method
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(TopicsWorker.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            // Stubbing TopicsWorker.getInstance() to return mocked TopicsWorker instance
            doReturn(mSpyTopicsWorker).when(() -> TopicsWorker.getInstance(eq(sContext)));

            // Track whether the TopicsWorker.handleAppInstallation was ever invoked.
            // Use a CountDownLatch since this invocation happens on a background thread.
            CountDownLatch completionLatch = new CountDownLatch(1);
            doAnswer(
                            unusedInvocation -> {
                                completionLatch.countDown();
                                return null;
                            })
                    .when(mSpyTopicsWorker)
                    .handleAppInstallation(Uri.parse(SAMPLE_PACKAGE));

            // Initialize package receiver meant for Topics and execute
            createSpyPackageReceiverForTopics().onReceive(sContext, intent);

            // Verify the execution in background thread has occurred.
            assertThat(completionLatch.await(/* timeout */ 500, TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            session.finishMocking();
        }
    }

    private void runPackageAddedMsmtKillSwitchOff(Intent intent) throws Exception {
        // Start a mockitoSession to mock static method
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(MeasurementImpl.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        try {
            // Kill Switch is off.
            doReturn(false).when(mMockFlags).getMeasurementReceiverInstallAttributionKillSwitch();

            // Mock static method FlagsFactory.getFlags() to return Mock Flags.
            doReturn(mMockFlags).when(FlagsFactory::getFlags);

            // Mock static method MeasurementImpl.getInstance that executes on a separate thread
            MeasurementImpl mockMeasurementImpl = mock(MeasurementImpl.class);
            doReturn(mockMeasurementImpl).when(() -> MeasurementImpl.getInstance(any()));

            // Initialize package receiver meant for Measurement
            PackageChangedReceiver spyReceiver = createSpyPackageReceiverForMeasurement();
            spyReceiver.onReceive(sContext, intent);

            // Verify only measurement added method was executed from measurement methods
            verify(spyReceiver, never()).measurementOnPackageDataCleared(any(), any());
            verify(spyReceiver, never()).measurementOnPackageFullyRemoved(any(), any());
            verify(spyReceiver, times(1)).measurementOnPackageAdded(any(), any());

            // Allow background thread to execute
            Thread.sleep(BACKGROUND_THREAD_TIMEOUT_MS);

            // Verify method inside measurement background thread executes
            verify(mockMeasurementImpl, times(1)).doInstallAttribution(any(), anyLong());
        } finally {
            session.finishMocking();
        }
    }

    private void runPackageAddedForMsmtKillSwitchOn(Intent intent) throws Exception {
        // Start a mockitoSession to mock static method
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(MeasurementImpl.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        try {
            // Kill Switch is on.
            doReturn(true).when(mMockFlags).getMeasurementReceiverInstallAttributionKillSwitch();

            // Mock static method FlagsFactory.getFlags() to return Mock Flags.
            doReturn(mMockFlags).when(FlagsFactory::getFlags);

            // Mock static method MeasurementImpl.getInstance that executes on a separate thread
            MeasurementImpl mockMeasurementImpl = mock(MeasurementImpl.class);
            doReturn(mockMeasurementImpl).when(() -> MeasurementImpl.getInstance(any()));

            // Initialize package receiver meant for Measurement
            PackageChangedReceiver spyReceiver = createSpyPackageReceiverForMeasurement();
            spyReceiver.onReceive(sContext, intent);

            // Verify only measurement added method was executed from measurement methods
            verify(spyReceiver, never()).measurementOnPackageDataCleared(any(), any());
            verify(spyReceiver, never()).measurementOnPackageFullyRemoved(any(), any());
            verify(spyReceiver, times(1)).measurementOnPackageAdded(any(), any());

            // Allow background thread to execute
            Thread.sleep(BACKGROUND_THREAD_TIMEOUT_MS);

            // Verify method inside measurement background thread does not execute
            verify(mockMeasurementImpl, never()).doInstallAttribution(any(), anyLong());
        } finally {
            session.finishMocking();
        }
    }

    private void runPackageDataClearedForMsmtKillSwitchOff(Intent intent) throws Exception {
        // Start a mockitoSession to mock static method
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(MeasurementImpl.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        try {
            // Kill Switch is off.
            doReturn(false).when(mMockFlags).getMeasurementReceiverDeletePackagesKillSwitch();

            // Mock static method FlagsFactory.getFlags() to return Mock Flags.
            doReturn(mMockFlags).when(FlagsFactory::getFlags);

            // Mock static method MeasurementImpl.getInstance that executes on a separate thread
            MeasurementImpl mockMeasurementImpl = mock(MeasurementImpl.class);
            doReturn(mockMeasurementImpl).when(() -> MeasurementImpl.getInstance(any()));

            // Initialize package receiver meant for Measurement
            PackageChangedReceiver spyReceiver = createSpyPackageReceiverForMeasurement();
            spyReceiver.onReceive(sContext, intent);

            // Verify only measurement cleared method was executed from measurement methods
            verify(spyReceiver, times(1)).measurementOnPackageDataCleared(any(), any());
            verify(spyReceiver, never()).measurementOnPackageFullyRemoved(any(), any());
            verify(spyReceiver, never()).measurementOnPackageAdded(any(), any());

            // Allow background thread to execute
            Thread.sleep(BACKGROUND_THREAD_TIMEOUT_MS);

            // Verify method inside measurement background thread executes
            verify(mockMeasurementImpl, times(1)).deletePackageRecords(any());
        } finally {
            session.finishMocking();
        }
    }

    private void runPackageDataClearedForMsmtKillSwitchOn(Intent intent) throws Exception {
        // Start a mockitoSession to mock static method
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(MeasurementImpl.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        try {
            // Kill Switch is on.
            doReturn(true).when(mMockFlags).getMeasurementReceiverDeletePackagesKillSwitch();

            // Mock static method FlagsFactory.getFlags() to return Mock Flags.
            doReturn(mMockFlags).when(FlagsFactory::getFlags);

            // Mock static method MeasurementImpl.getInstance that executes on a separate thread
            MeasurementImpl mockMeasurementImpl = mock(MeasurementImpl.class);
            doReturn(mockMeasurementImpl).when(() -> MeasurementImpl.getInstance(any()));

            // Initialize package receiver meant for Measurement
            PackageChangedReceiver spyReceiver = createSpyPackageReceiverForMeasurement();
            spyReceiver.onReceive(sContext, intent);

            // Verify only measurement cleared method was executed from measurement methods
            verify(spyReceiver, times(1)).measurementOnPackageDataCleared(any(), any());
            verify(spyReceiver, never()).measurementOnPackageFullyRemoved(any(), any());
            verify(spyReceiver, never()).measurementOnPackageAdded(any(), any());

            // Allow background thread to execute
            Thread.sleep(BACKGROUND_THREAD_TIMEOUT_MS);

            // Verify method inside measurement background thread does not execute
            verify(mockMeasurementImpl, never()).deletePackageRecords(any());
        } finally {
            session.finishMocking();
        }
    }

    private void runPackageDataClearedForFledgeKillSwitchOff(Intent intent) throws Exception {
        // Start a mockitoSession to mock static method
        // Lenient added to allow easy disabling of other APIs' methods
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .mockStatic(FlagsFactory.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();
        try {
            // Kill switch is off; service is enabled
            doReturn(false).when(mMockFlags).getFledgeCustomAudienceServiceKillSwitch();
            doReturn(mMockFlags).when(FlagsFactory::getFlags);

            // Mock static database .getInstance() methods executed on a separate thread
            doReturn(mCustomAudienceDaoMock).when(mCustomAudienceDatabaseMock).customAudienceDao();
            doReturn(mAppInstallDaoMock).when(mSharedStorageDatabaseMock).appInstallDao();

            CountDownLatch caCompletionLatch = new CountDownLatch(1);
            Answer<Void> caAnswer =
                    unusedInvocation -> {
                        caCompletionLatch.countDown();
                        return null;
                    };
            doAnswer(caAnswer).when(mCustomAudienceDaoMock).deleteCustomAudienceDataByOwner(any());
            CountDownLatch appInstallCompletionLatch = new CountDownLatch(1);
            Answer<Void> appInstallAnswer =
                    unusedInvocation -> {
                        appInstallCompletionLatch.countDown();
                        return null;
                    };
            doAnswer(appInstallAnswer).when(mAppInstallDaoMock).deleteByPackageName(any());

            // Initialize package receiver meant for FLEDGE
            PackageChangedReceiver spyReceiver = createSpyPackageReceiverForFledge();
            doReturn(mCustomAudienceDatabaseMock)
                    .when(spyReceiver)
                    .getCustomAudienceDatabase(any());
            doReturn(mSharedStorageDatabaseMock).when(spyReceiver).getSharedStorageDatabase(any());
            spyReceiver.onReceive(sContext, intent);

            verify(spyReceiver).fledgeOnPackageFullyRemovedOrDataCleared(any(), any());

            // Verify method inside background thread executes
            assertThat(caCompletionLatch.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(appInstallCompletionLatch.await(500, TimeUnit.MILLISECONDS)).isTrue();
            verify(mCustomAudienceDaoMock).deleteCustomAudienceDataByOwner(any());
            verify(mAppInstallDaoMock).deleteByPackageName(any());
        } finally {
            session.finishMocking();
        }
    }

    private void runPackageDataClearedForFledgeKillSwitchOn(Intent intent) {
        // Start a mockitoSession to mock static method
        // Lenient added to allow easy disabling of other APIs' methods
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .mockStatic(FlagsFactory.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();
        try {
            // Kill switch is on; service is disabled
            doReturn(true).when(mMockFlags).getFledgeCustomAudienceServiceKillSwitch();
            doReturn(mMockFlags).when(FlagsFactory::getFlags);

            // Initialize package receiver meant for FLEDGE
            PackageChangedReceiver spyReceiver = createSpyPackageReceiverForFledge();
            spyReceiver.onReceive(sContext, intent);

            verify(spyReceiver).fledgeOnPackageFullyRemovedOrDataCleared(any(), any());

            // Verify no executions
            verify(spyReceiver, never()).getCustomAudienceDatabase(any());
            verifyZeroInteractions(mCustomAudienceDatabaseMock, mCustomAudienceDaoMock);
            verifyZeroInteractions(mSharedStorageDatabaseMock, mAppInstallDaoMock);
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testIsPackageStillInstalled() {
        // Start a mockitoSession to mock static method
        // Lenient added to allow easy disabling of other APIs' methods
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .mockStatic(PackageManagerCompatUtils.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();

        try {
            final String packageNamePrefix = "com.example.package";
            final int count = 4;
            final List<PackageInfo> packages = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                PackageInfo packageInfo = new PackageInfo();
                packageInfo.packageName = packageNamePrefix + i;
                packages.add(packageInfo);
            }

            doReturn(packages)
                    .when(() -> PackageManagerCompatUtils.getInstalledPackages(any(), anyInt()));

            // Initialize package receiver
            final Context context = ApplicationProvider.getApplicationContext();
            PackageChangedReceiver receiver = createSpyPackageReceiverForConsent();
            assertThat(receiver.isPackageStillInstalled(context, packageNamePrefix + 0)).isTrue();
            assertThat(receiver.isPackageStillInstalled(context, packageNamePrefix + count))
                    .isFalse();
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testReceive_onT_onExtServices() {
        MockitoSession mMockitoSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(SdkLevel.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();
        try {
            ExtendedMockito.doReturn(true).when(SdkLevel::isAtLeastT);
            Intent intent =
                    createIntentSentByAdServiceSystemService(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            PackageChangedReceiver receiver = createSpyPackageReceiverForExtServices();
            Context spyContext = Mockito.spy(ApplicationProvider.getApplicationContext());
            doReturn("com." + AdServicesCommon.ADEXTSERVICES_PACKAGE_NAME_SUFFIX)
                    .when(spyContext)
                    .getPackageName();
            receiver.onReceive(spyContext, intent);
            verify(receiver, never()).consentOnPackageFullyRemoved(any(), any(), anyInt());
            verify(receiver, never()).measurementOnPackageFullyRemoved(any(), any());
            verify(receiver, never()).topicsOnPackageFullyRemoved(any(), any());
            verify(receiver, never()).fledgeOnPackageFullyRemovedOrDataCleared(any(), any());
        } finally {
            mMockitoSession.finishMocking();
        }
    }

    @Test
    public void testReceive_onS_onExtServices() {
        MockitoSession mMockitoSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(SdkLevel.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();
        try {
            ExtendedMockito.doReturn(false).when(SdkLevel::isAtLeastT);
            Intent intent = createIntentSentBySystem(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            PackageChangedReceiver receiver = createSpyPackageReceiverForExtServices();
            Context spyContext = Mockito.spy(ApplicationProvider.getApplicationContext());
            doReturn("com." + AdServicesCommon.ADEXTSERVICES_PACKAGE_NAME_SUFFIX)
                    .when(spyContext)
                    .getPackageName();
            receiver.onReceive(spyContext, intent);
            verify(receiver).consentOnPackageFullyRemoved(any(), any(), anyInt());
            verify(receiver).measurementOnPackageFullyRemoved(any(), any());
            verify(receiver).topicsOnPackageFullyRemoved(any(), any());
            verify(receiver).fledgeOnPackageFullyRemovedOrDataCleared(any(), any());
        } finally {
            mMockitoSession.finishMocking();
        }
    }
}
