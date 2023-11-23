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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.adservices.common.AdServicesStatusUtils;
import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.data.measurement.DatastoreException;
import com.android.adservices.data.measurement.DatastoreManager;
import com.android.adservices.data.measurement.IMeasurementDao;
import com.android.adservices.data.measurement.ITransaction;
import com.android.adservices.service.measurement.WebUtil;
import com.android.adservices.service.measurement.aggregation.AggregateCryptoFixture;
import com.android.adservices.service.measurement.aggregation.AggregateEncryptionKey;
import com.android.adservices.service.measurement.aggregation.AggregateEncryptionKeyManager;
import com.android.adservices.service.measurement.aggregation.AggregateReport;
import com.android.adservices.service.measurement.util.UnsignedLong;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Unit test for {@link AggregateReportingJobHandler} */
@RunWith(MockitoJUnitRunner.class)
public class AggregateReportingJobHandlerTest {
    private static final Uri REPORTING_URI = WebUtil.validUri("https://subdomain.example.test");
    private static final String ENROLLMENT_ID = "enrollment-id";

    private static final String CLEARTEXT_PAYLOAD =
            "{\"operation\":\"histogram\",\"data\":[{\"bucket\":\"1\",\"value\":2}]}";

    private static final UnsignedLong SOURCE_DEBUG_KEY = new UnsignedLong(237865L);
    private static final UnsignedLong TRIGGER_DEBUG_KEY = new UnsignedLong(928762L);

    protected static final Context sContext = ApplicationProvider.getApplicationContext();
    DatastoreManager mDatastoreManager;

    @Mock IMeasurementDao mMeasurementDao;

    @Mock ITransaction mTransaction;

    @Mock EnrollmentDao mEnrollmentDao;

    AggregateReportingJobHandler mAggregateReportingJobHandler;
    AggregateReportingJobHandler mSpyAggregateReportingJobHandler;
    AggregateReportingJobHandler mSpyDebugAggregateReportingJobHandler;

    class FakeDatasoreManager extends DatastoreManager {

        @Override
        public ITransaction createNewTransaction() {
            return mTransaction;
        }

        @Override
        public IMeasurementDao getMeasurementDao() {
            return mMeasurementDao;
        }

        @Override
        protected int getDataStoreVersion() {
            return 0;
        }
    }

    @Before
    public void setUp() {
        AggregateEncryptionKeyManager mockKeyManager = mock(AggregateEncryptionKeyManager.class);
        ArgumentCaptor<Integer> captorNumberOfKeys = ArgumentCaptor.forClass(Integer.class);
        when(mockKeyManager.getAggregateEncryptionKeys(captorNumberOfKeys.capture()))
                .thenAnswer(
                        invocation -> {
                            List<AggregateEncryptionKey> keys = new ArrayList<>();
                            for (int i = 0; i < captorNumberOfKeys.getValue(); i++) {
                                keys.add(AggregateCryptoFixture.getKey());
                            }
                            return keys;
                        });
        mDatastoreManager = new FakeDatasoreManager();
        mAggregateReportingJobHandler = new AggregateReportingJobHandler(
                mEnrollmentDao, mDatastoreManager, mockKeyManager);
        mSpyAggregateReportingJobHandler = Mockito.spy(mAggregateReportingJobHandler);
        mSpyDebugAggregateReportingJobHandler =
                Mockito.spy(
                        new AggregateReportingJobHandler(
                                        mEnrollmentDao, mDatastoreManager, mockKeyManager)
                                .setIsDebugInstance(true));
    }

    @Test
    public void testSendReportForPendingReportSuccess()
            throws DatastoreException, IOException, JSONException {
        AggregateReport aggregateReport =
                new AggregateReport.Builder()
                        .setId("aggregateReportId")
                        .setStatus(AggregateReport.Status.PENDING)
                        .setEnrollmentId(ENROLLMENT_ID)
                        .setSourceDebugKey(SOURCE_DEBUG_KEY)
                        .setTriggerDebugKey(TRIGGER_DEBUG_KEY)
                        .setRegistrationOrigin(REPORTING_URI)
                        .build();
        JSONObject aggregateReportBody =
                new AggregateReportBody.Builder()
                        .setReportId(aggregateReport.getId())
                        .setDebugCleartextPayload(CLEARTEXT_PAYLOAD)
                        .build()
                        .toJson(AggregateCryptoFixture.getKey());

        when(mMeasurementDao.getAggregateReport(aggregateReport.getId()))
                .thenReturn(aggregateReport);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyAggregateReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_URI), Mockito.any());
        doReturn(aggregateReportBody)
                .when(mSpyAggregateReportingJobHandler)
                .createReportJsonPayload(Mockito.any(), Mockito.eq(REPORTING_URI), Mockito.any());

        doNothing()
                .when(mMeasurementDao)
                .markAggregateReportStatus(
                        aggregateReport.getId(), AggregateReport.Status.DELIVERED);
        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_SUCCESS,
                mSpyAggregateReportingJobHandler.performReport(
                        aggregateReport.getId(),
                        AggregateCryptoFixture.getKey(),
                        new ReportingStatus()));

        verify(mMeasurementDao, times(1)).markAggregateReportStatus(any(), anyInt());
        verify(mTransaction, times(2)).begin();
        verify(mTransaction, times(2)).end();
    }

    @Test
    public void testSendReportForPendingDebugReportSuccess()
            throws DatastoreException, IOException, JSONException {
        AggregateReport aggregateReport =
                new AggregateReport.Builder()
                        .setId("aggregateReportId")
                        .setStatus(AggregateReport.Status.PENDING)
                        .setDebugReportStatus(AggregateReport.DebugReportStatus.PENDING)
                        .setEnrollmentId(ENROLLMENT_ID)
                        .setSourceDebugKey(SOURCE_DEBUG_KEY)
                        .setTriggerDebugKey(TRIGGER_DEBUG_KEY)
                        .setRegistrationOrigin(REPORTING_URI)
                        .build();
        JSONObject aggregateReportBody =
                new AggregateReportBody.Builder()
                        .setReportId(aggregateReport.getId())
                        .setDebugCleartextPayload(CLEARTEXT_PAYLOAD)
                        .build()
                        .toJson(AggregateCryptoFixture.getKey());

        when(mMeasurementDao.getAggregateReport(aggregateReport.getId()))
                .thenReturn(aggregateReport);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyDebugAggregateReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_URI), Mockito.any());
        doReturn(aggregateReportBody)
                .when(mSpyDebugAggregateReportingJobHandler)
                .createReportJsonPayload(Mockito.any(), Mockito.eq(REPORTING_URI), Mockito.any());

        doNothing()
                .when(mMeasurementDao)
                .markAggregateDebugReportDelivered(aggregateReport.getId());
        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_SUCCESS,
                mSpyDebugAggregateReportingJobHandler.performReport(
                        aggregateReport.getId(),
                        AggregateCryptoFixture.getKey(),
                        new ReportingStatus()));

        verify(mMeasurementDao, times(1)).markAggregateDebugReportDelivered(any());
        verify(mTransaction, times(2)).begin();
        verify(mTransaction, times(2)).end();
    }

    @Test
    public void testSendReportForPendingReportSuccessSingleTriggerDebugKey()
            throws DatastoreException, IOException, JSONException {
        AggregateReport aggregateReport =
                new AggregateReport.Builder()
                        .setId("aggregateReportId")
                        .setStatus(AggregateReport.Status.PENDING)
                        .setEnrollmentId(ENROLLMENT_ID)
                        .setTriggerDebugKey(TRIGGER_DEBUG_KEY)
                        .setRegistrationOrigin(REPORTING_URI)
                        .build();
        JSONObject aggregateReportBody =
                new AggregateReportBody.Builder()
                        .setReportId(aggregateReport.getId())
                        .setDebugCleartextPayload(CLEARTEXT_PAYLOAD)
                        .build()
                        .toJson(AggregateCryptoFixture.getKey());

        when(mMeasurementDao.getAggregateReport(aggregateReport.getId()))
                .thenReturn(aggregateReport);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyAggregateReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_URI), Mockito.any());
        doReturn(aggregateReportBody)
                .when(mSpyAggregateReportingJobHandler)
                .createReportJsonPayload(Mockito.any(), Mockito.eq(REPORTING_URI), Mockito.any());

        doNothing()
                .when(mMeasurementDao)
                .markAggregateReportStatus(
                        aggregateReport.getId(), AggregateReport.Status.DELIVERED);
        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_SUCCESS,
                mSpyAggregateReportingJobHandler.performReport(
                        aggregateReport.getId(),
                        AggregateCryptoFixture.getKey(),
                        new ReportingStatus()));

        verify(mMeasurementDao, times(1)).markAggregateReportStatus(any(), anyInt());
        verify(mTransaction, times(2)).begin();
        verify(mTransaction, times(2)).end();
    }

    @Test
    public void testSendReportForPendingReportSuccessSingleSourceDebugKey()
            throws DatastoreException, IOException, JSONException {
        AggregateReport aggregateReport =
                new AggregateReport.Builder()
                        .setId("aggregateReportId")
                        .setStatus(AggregateReport.Status.PENDING)
                        .setEnrollmentId(ENROLLMENT_ID)
                        .setSourceDebugKey(SOURCE_DEBUG_KEY)
                        .setRegistrationOrigin(REPORTING_URI)
                        .build();
        JSONObject aggregateReportBody =
                new AggregateReportBody.Builder()
                        .setReportId(aggregateReport.getId())
                        .setDebugCleartextPayload(CLEARTEXT_PAYLOAD)
                        .build()
                        .toJson(AggregateCryptoFixture.getKey());

        when(mMeasurementDao.getAggregateReport(aggregateReport.getId()))
                .thenReturn(aggregateReport);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyAggregateReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_URI), Mockito.any());
        doReturn(aggregateReportBody)
                .when(mSpyAggregateReportingJobHandler)
                .createReportJsonPayload(Mockito.any(), Mockito.eq(REPORTING_URI), Mockito.any());

        doNothing()
                .when(mMeasurementDao)
                .markAggregateReportStatus(
                        aggregateReport.getId(), AggregateReport.Status.DELIVERED);
        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_SUCCESS,
                mSpyAggregateReportingJobHandler.performReport(
                        aggregateReport.getId(),
                        AggregateCryptoFixture.getKey(),
                        new ReportingStatus()));

        verify(mMeasurementDao, times(1)).markAggregateReportStatus(any(), anyInt());
        verify(mTransaction, times(2)).begin();
        verify(mTransaction, times(2)).end();
    }

    @Test
    public void testSendReportForPendingReportSuccessNullDebugKeys()
            throws DatastoreException, IOException, JSONException {
        AggregateReport aggregateReport =
                new AggregateReport.Builder()
                        .setId("aggregateReportId")
                        .setStatus(AggregateReport.Status.PENDING)
                        .setEnrollmentId(ENROLLMENT_ID)
                        .setSourceDebugKey(null)
                        .setTriggerDebugKey(null)
                        .setRegistrationOrigin(REPORTING_URI)
                        .build();
        JSONObject aggregateReportBody =
                new AggregateReportBody.Builder()
                        .setReportId(aggregateReport.getId())
                        .setDebugCleartextPayload(CLEARTEXT_PAYLOAD)
                        .build()
                        .toJson(AggregateCryptoFixture.getKey());

        when(mMeasurementDao.getAggregateReport(aggregateReport.getId()))
                .thenReturn(aggregateReport);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyAggregateReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_URI), Mockito.any());
        doReturn(aggregateReportBody)
                .when(mSpyAggregateReportingJobHandler)
                .createReportJsonPayload(Mockito.any(), Mockito.eq(REPORTING_URI), Mockito.any());

        doNothing()
                .when(mMeasurementDao)
                .markAggregateReportStatus(
                        aggregateReport.getId(), AggregateReport.Status.DELIVERED);
        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_SUCCESS,
                mSpyAggregateReportingJobHandler.performReport(
                        aggregateReport.getId(),
                        AggregateCryptoFixture.getKey(),
                        new ReportingStatus()));

        verify(mMeasurementDao, times(1)).markAggregateReportStatus(any(), anyInt());
        verify(mTransaction, times(2)).begin();
        verify(mTransaction, times(2)).end();
    }

    @Test
    public void testSendReportForPendingReportFailure()
            throws DatastoreException, IOException, JSONException {
        AggregateReport aggregateReport =
                new AggregateReport.Builder()
                        .setId("aggregateReportId")
                        .setStatus(AggregateReport.Status.PENDING)
                        .setEnrollmentId(ENROLLMENT_ID)
                        .setRegistrationOrigin(REPORTING_URI)
                        .build();
        JSONObject aggregateReportBody =
                new AggregateReportBody.Builder()
                        .setReportId(aggregateReport.getId())
                        .setDebugCleartextPayload(CLEARTEXT_PAYLOAD)
                        .build()
                        .toJson(AggregateCryptoFixture.getKey());

        when(mMeasurementDao.getAggregateReport(aggregateReport.getId()))
                .thenReturn(aggregateReport);
        doReturn(HttpURLConnection.HTTP_BAD_REQUEST)
                .when(mSpyAggregateReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_URI), Mockito.any());
        doReturn(aggregateReportBody)
                .when(mSpyAggregateReportingJobHandler)
                .createReportJsonPayload(Mockito.any(), Mockito.eq(REPORTING_URI), Mockito.any());

        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_IO_ERROR,
                mSpyAggregateReportingJobHandler.performReport(
                        aggregateReport.getId(),
                        AggregateCryptoFixture.getKey(),
                        new ReportingStatus()));

        verify(mMeasurementDao, never()).markAggregateReportStatus(any(), anyInt());
        verify(mTransaction, times(1)).begin();
        verify(mTransaction, times(1)).end();
    }

    @Test
    public void testSendReportForAlreadyDeliveredReport() throws DatastoreException {
        AggregateReport aggregateReport =
                new AggregateReport.Builder()
                        .setId("aggregateReportId")
                        .setStatus(AggregateReport.Status.DELIVERED)
                        .setDebugCleartextPayload(CLEARTEXT_PAYLOAD)
                        .setEnrollmentId(ENROLLMENT_ID)
                        .setRegistrationOrigin(REPORTING_URI)
                        .build();

        when(mMeasurementDao.getAggregateReport(aggregateReport.getId()))
                .thenReturn(aggregateReport);
        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_INVALID_ARGUMENT,
                mSpyAggregateReportingJobHandler.performReport(
                        aggregateReport.getId(),
                        AggregateCryptoFixture.getKey(),
                        new ReportingStatus()));

        verify(mMeasurementDao, never()).markAggregateReportStatus(any(), anyInt());
        verify(mTransaction, times(1)).begin();
        verify(mTransaction, times(1)).end();
    }

    @Test
    public void testPerformScheduledPendingReportsForMultipleReports()
            throws DatastoreException, IOException, JSONException {
        AggregateReport aggregateReport1 =
                new AggregateReport.Builder()
                        .setId("aggregateReportId1")
                        .setStatus(AggregateReport.Status.PENDING)
                        .setScheduledReportTime(1000L)
                        .setEnrollmentId(ENROLLMENT_ID)
                        .setRegistrationOrigin(REPORTING_URI)
                        .build();
        JSONObject aggregateReportBody1 =
                new AggregateReportBody.Builder()
                        .setReportId(aggregateReport1.getId())
                        .setDebugCleartextPayload(CLEARTEXT_PAYLOAD)
                        .build()
                        .toJson(AggregateCryptoFixture.getKey());
        AggregateReport aggregateReport2 =
                new AggregateReport.Builder()
                        .setId("aggregateReportId2")
                        .setStatus(AggregateReport.Status.PENDING)
                        .setScheduledReportTime(1100L)
                        .setEnrollmentId(ENROLLMENT_ID)
                        .setRegistrationOrigin(REPORTING_URI)
                        .build();
        JSONObject aggregateReportBody2 =
                new AggregateReportBody.Builder()
                        .setReportId(aggregateReport2.getId())
                        .setDebugCleartextPayload(CLEARTEXT_PAYLOAD)
                        .build()
                        .toJson(AggregateCryptoFixture.getKey());

        when(mMeasurementDao.getPendingAggregateReportIdsInWindow(1000, 1100))
                .thenReturn(List.of(aggregateReport1.getId(), aggregateReport2.getId()));
        when(mMeasurementDao.getAggregateReport(aggregateReport1.getId()))
                .thenReturn(aggregateReport1);
        when(mMeasurementDao.getAggregateReport(aggregateReport2.getId()))
                .thenReturn(aggregateReport2);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyAggregateReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_URI), Mockito.any());
        doReturn(aggregateReportBody1)
                .when(mSpyAggregateReportingJobHandler)
                .createReportJsonPayload(
                        aggregateReport1, REPORTING_URI, AggregateCryptoFixture.getKey());
        doReturn(aggregateReportBody2)
                .when(mSpyAggregateReportingJobHandler)
                .createReportJsonPayload(
                        aggregateReport2, REPORTING_URI, AggregateCryptoFixture.getKey());

        Assert.assertTrue(
                mSpyAggregateReportingJobHandler.performScheduledPendingReportsInWindow(
                        1000, 1100));

        verify(mMeasurementDao, times(2)).markAggregateReportStatus(any(), anyInt());
        verify(mTransaction, times(5)).begin();
        verify(mTransaction, times(5)).end();
    }

    @Test
    public void testPerformScheduledPendingReportsInWindow_noKeys()
            throws DatastoreException, IOException, JSONException {
        AggregateReport aggregateReport =
                new AggregateReport.Builder()
                        .setId("aggregateReportId1")
                        .setStatus(AggregateReport.Status.PENDING)
                        .setScheduledReportTime(1000L)
                        .setEnrollmentId(ENROLLMENT_ID)
                        .setRegistrationOrigin(REPORTING_URI)
                        .build();
        JSONObject aggregateReportBody =
                new AggregateReportBody.Builder()
                        .setReportId(aggregateReport.getId())
                        .setDebugCleartextPayload(CLEARTEXT_PAYLOAD)
                        .build()
                        .toJson(AggregateCryptoFixture.getKey());

        when(mMeasurementDao.getPendingAggregateReportIdsInWindow(1000, 1100))
                .thenReturn(List.of(aggregateReport.getId()));
        when(mMeasurementDao.getAggregateReport(aggregateReport.getId()))
                .thenReturn(aggregateReport);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyAggregateReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_URI), Mockito.any());
        doReturn(aggregateReportBody)
                .when(mSpyAggregateReportingJobHandler)
                .createReportJsonPayload(
                        aggregateReport, REPORTING_URI, AggregateCryptoFixture.getKey());

        AggregateEncryptionKeyManager mockKeyManager = mock(AggregateEncryptionKeyManager.class);
        when(mockKeyManager.getAggregateEncryptionKeys(anyInt()))
                .thenReturn(Collections.emptyList());
        mAggregateReportingJobHandler = new AggregateReportingJobHandler(
                mEnrollmentDao, new FakeDatasoreManager(), mockKeyManager);
        mSpyAggregateReportingJobHandler = Mockito.spy(mAggregateReportingJobHandler);

        Assert.assertTrue(
                mSpyAggregateReportingJobHandler.performScheduledPendingReportsInWindow(
                        1000, 1100));

        verify(mMeasurementDao, never()).markAggregateReportStatus(any(), anyInt());
    }
}
