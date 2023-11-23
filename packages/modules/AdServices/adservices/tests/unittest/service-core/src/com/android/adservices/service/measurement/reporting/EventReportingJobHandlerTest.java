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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
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
import com.android.adservices.service.enrollment.EnrollmentData;
import com.android.adservices.service.measurement.EventReport;
import com.android.adservices.service.measurement.WebUtil;
import com.android.adservices.service.measurement.aggregation.AggregateReport;
import com.android.adservices.service.measurement.util.UnsignedLong;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;

/** Unit test for {@link EventReportingJobHandler} */
@RunWith(MockitoJUnitRunner.class)
public class EventReportingJobHandlerTest {
    private static final UnsignedLong SOURCE_DEBUG_KEY = new UnsignedLong(237865L);
    private static final UnsignedLong TRIGGER_DEBUG_KEY = new UnsignedLong(928762L);

    private static final Uri REPORTING_ORIGIN = WebUtil.validUri("https://subdomain.example.test");
    private static final List<Uri> ATTRIBUTION_DESTINATIONS = List.of(
            Uri.parse("https://destination.test"));

    private static final EnrollmentData ENROLLMENT = new EnrollmentData.Builder()
            .setAttributionReportingUrl(List.of("https://ad-tech.test"))
            .build();

    protected static final Context sContext = ApplicationProvider.getApplicationContext();
    DatastoreManager mDatastoreManager;

    @Mock IMeasurementDao mMeasurementDao;

    @Mock ITransaction mTransaction;

    @Mock EnrollmentDao mEnrollmentDao;

    EventReportingJobHandler mEventReportingJobHandler;
    EventReportingJobHandler mSpyEventReportingJobHandler;
    EventReportingJobHandler mSpyDebugEventReportingJobHandler;

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
        mDatastoreManager = new FakeDatasoreManager();
        when(mEnrollmentDao.getEnrollmentData(any())).thenReturn(ENROLLMENT);
        mEventReportingJobHandler = new EventReportingJobHandler(mEnrollmentDao, mDatastoreManager);
        mSpyEventReportingJobHandler = Mockito.spy(mEventReportingJobHandler);
        mSpyDebugEventReportingJobHandler =
                Mockito.spy(
                        new EventReportingJobHandler(mEnrollmentDao, mDatastoreManager)
                                .setIsDebugInstance(true));
    }

    @Test
    public void testSendReportForPendingReportSuccess()
            throws DatastoreException, IOException, JSONException {
        EventReport eventReport =
                new EventReport.Builder()
                        .setId("eventReportId")
                        .setSourceEventId(new UnsignedLong(1234L))
                        .setAttributionDestinations(ATTRIBUTION_DESTINATIONS)
                        .setStatus(EventReport.Status.PENDING)
                        .setSourceDebugKey(SOURCE_DEBUG_KEY)
                        .setTriggerDebugKey(TRIGGER_DEBUG_KEY)
                        .setRegistrationOrigin(REPORTING_ORIGIN)
                        .build();
        JSONObject eventReportPayload =
                new EventReportPayload.Builder()
                        .setReportId(eventReport.getId())
                        .setSourceEventId(eventReport.getSourceEventId())
                        .setAttributionDestination(eventReport.getAttributionDestinations())
                        .build()
                        .toJson();

        when(mMeasurementDao.getEventReport(eventReport.getId())).thenReturn(eventReport);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyEventReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        doReturn(eventReportPayload)
                .when(mSpyEventReportingJobHandler)
                .createReportJsonPayload(Mockito.any());

        doNothing()
                .when(mMeasurementDao)
                .markAggregateReportStatus(eventReport.getId(), AggregateReport.Status.DELIVERED);

        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_SUCCESS,
                mSpyEventReportingJobHandler.performReport(
                        eventReport.getId(), new ReportingStatus()));

        verify(mMeasurementDao, times(1)).markEventReportStatus(any(), anyInt());
        verify(mSpyEventReportingJobHandler, times(1))
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        verify(mTransaction, times(2)).begin();
        verify(mTransaction, times(2)).end();
    }

    @Test
    public void testSendReportForPendingDebugReportSuccess()
            throws DatastoreException, IOException, JSONException {
        EventReport eventReport =
                new EventReport.Builder()
                        .setId("eventReportId")
                        .setSourceEventId(new UnsignedLong(1234L))
                        .setAttributionDestinations(ATTRIBUTION_DESTINATIONS)
                        .setStatus(EventReport.Status.PENDING)
                        .setDebugReportStatus(EventReport.DebugReportStatus.PENDING)
                        .setSourceDebugKey(SOURCE_DEBUG_KEY)
                        .setTriggerDebugKey(TRIGGER_DEBUG_KEY)
                        .setRegistrationOrigin(REPORTING_ORIGIN)
                        .build();
        JSONObject eventReportPayload =
                new EventReportPayload.Builder()
                        .setReportId(eventReport.getId())
                        .setSourceEventId(eventReport.getSourceEventId())
                        .setAttributionDestination(eventReport.getAttributionDestinations())
                        .build()
                        .toJson();

        when(mMeasurementDao.getEventReport(eventReport.getId())).thenReturn(eventReport);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyDebugEventReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        doReturn(eventReportPayload)
                .when(mSpyDebugEventReportingJobHandler)
                .createReportJsonPayload(Mockito.any());

        doNothing().when(mMeasurementDao).markEventDebugReportDelivered(eventReport.getId());

        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_SUCCESS,
                mSpyDebugEventReportingJobHandler.performReport(
                        eventReport.getId(), new ReportingStatus()));

        verify(mMeasurementDao, times(1)).markEventDebugReportDelivered(any());
        verify(mSpyDebugEventReportingJobHandler, times(1))
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        verify(mTransaction, times(2)).begin();
        verify(mTransaction, times(2)).end();
    }

    @Test
    public void testSendReportForPendingReportSuccessSingleTriggerDebugKey()
            throws DatastoreException, IOException, JSONException {
        EventReport eventReport =
                new EventReport.Builder()
                        .setId("eventReportId")
                        .setSourceEventId(new UnsignedLong(1234L))
                        .setAttributionDestinations(ATTRIBUTION_DESTINATIONS)
                        .setStatus(EventReport.Status.PENDING)
                        .setTriggerDebugKey(TRIGGER_DEBUG_KEY)
                        .setRegistrationOrigin(REPORTING_ORIGIN)
                        .build();
        JSONObject eventReportPayload =
                new EventReportPayload.Builder()
                        .setReportId(eventReport.getId())
                        .setSourceEventId(eventReport.getSourceEventId())
                        .setAttributionDestination(eventReport.getAttributionDestinations())
                        .build()
                        .toJson();

        when(mMeasurementDao.getEventReport(eventReport.getId())).thenReturn(eventReport);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyEventReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        doReturn(eventReportPayload)
                .when(mSpyEventReportingJobHandler)
                .createReportJsonPayload(Mockito.any());

        doNothing()
                .when(mMeasurementDao)
                .markAggregateReportStatus(eventReport.getId(), AggregateReport.Status.DELIVERED);

        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_SUCCESS,
                mSpyEventReportingJobHandler.performReport(
                        eventReport.getId(), new ReportingStatus()));

        verify(mMeasurementDao, times(1)).markEventReportStatus(any(), anyInt());
        verify(mSpyEventReportingJobHandler, times(1))
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        verify(mTransaction, times(2)).begin();
        verify(mTransaction, times(2)).end();
    }

    @Test
    public void testSendReportForPendingReportSuccessSingleSourceDebugKey()
            throws DatastoreException, IOException, JSONException {
        EventReport eventReport =
                new EventReport.Builder()
                        .setId("eventReportId")
                        .setSourceEventId(new UnsignedLong(1234L))
                        .setAttributionDestinations(ATTRIBUTION_DESTINATIONS)
                        .setStatus(EventReport.Status.PENDING)
                        .setSourceDebugKey(SOURCE_DEBUG_KEY)
                        .setRegistrationOrigin(REPORTING_ORIGIN)
                        .build();
        JSONObject eventReportPayload =
                new EventReportPayload.Builder()
                        .setReportId(eventReport.getId())
                        .setSourceEventId(eventReport.getSourceEventId())
                        .setAttributionDestination(eventReport.getAttributionDestinations())
                        .build()
                        .toJson();

        when(mMeasurementDao.getEventReport(eventReport.getId())).thenReturn(eventReport);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyEventReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        doReturn(eventReportPayload)
                .when(mSpyEventReportingJobHandler)
                .createReportJsonPayload(Mockito.any());

        doNothing()
                .when(mMeasurementDao)
                .markAggregateReportStatus(eventReport.getId(), AggregateReport.Status.DELIVERED);

        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_SUCCESS,
                mSpyEventReportingJobHandler.performReport(
                        eventReport.getId(), new ReportingStatus()));

        verify(mMeasurementDao, times(1)).markEventReportStatus(any(), anyInt());
        verify(mSpyEventReportingJobHandler, times(1))
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        verify(mTransaction, times(2)).begin();
        verify(mTransaction, times(2)).end();
    }

    @Test
    public void testSendReportForPendingReportSuccessWithNullDebugKeys()
            throws DatastoreException, IOException, JSONException {
        EventReport eventReport =
                new EventReport.Builder()
                        .setId("eventReportId")
                        .setSourceEventId(new UnsignedLong(1234L))
                        .setAttributionDestinations(ATTRIBUTION_DESTINATIONS)
                        .setStatus(EventReport.Status.PENDING)
                        .setSourceDebugKey(null)
                        .setTriggerDebugKey(null)
                        .setRegistrationOrigin(REPORTING_ORIGIN)
                        .build();
        JSONObject eventReportPayload =
                new EventReportPayload.Builder()
                        .setReportId(eventReport.getId())
                        .setSourceEventId(eventReport.getSourceEventId())
                        .setAttributionDestination(eventReport.getAttributionDestinations())
                        .build()
                        .toJson();

        when(mMeasurementDao.getEventReport(eventReport.getId())).thenReturn(eventReport);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyEventReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        doReturn(eventReportPayload)
                .when(mSpyEventReportingJobHandler)
                .createReportJsonPayload(Mockito.any());

        doNothing()
                .when(mMeasurementDao)
                .markAggregateReportStatus(eventReport.getId(), AggregateReport.Status.DELIVERED);
        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_SUCCESS,
                mSpyEventReportingJobHandler.performReport(
                        eventReport.getId(), new ReportingStatus()));

        verify(mMeasurementDao, times(1)).markEventReportStatus(any(), anyInt());
        verify(mSpyEventReportingJobHandler, times(1))
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        verify(mTransaction, times(2)).begin();
        verify(mTransaction, times(2)).end();
    }

    @Test
    public void testSendReportForPendingReportFailure()
            throws DatastoreException, IOException, JSONException {
        EventReport eventReport =
                new EventReport.Builder()
                        .setId("eventReportId")
                        .setSourceEventId(new UnsignedLong(1234L))
                        .setAttributionDestinations(ATTRIBUTION_DESTINATIONS)
                        .setStatus(EventReport.Status.PENDING)
                        .setRegistrationOrigin(REPORTING_ORIGIN)
                        .build();
        JSONObject eventReportPayload =
                new EventReportPayload.Builder()
                        .setReportId(eventReport.getId())
                        .setSourceEventId(eventReport.getSourceEventId())
                        .setAttributionDestination(eventReport.getAttributionDestinations())
                        .build()
                        .toJson();

        when(mMeasurementDao.getEventReport(eventReport.getId())).thenReturn(eventReport);
        doReturn(HttpURLConnection.HTTP_BAD_REQUEST)
                .when(mSpyEventReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        doReturn(eventReportPayload)
                .when(mSpyEventReportingJobHandler)
                .createReportJsonPayload(Mockito.any());

        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_IO_ERROR,
                mSpyEventReportingJobHandler.performReport(
                        eventReport.getId(), new ReportingStatus()));

        verify(mMeasurementDao, never()).markEventReportStatus(any(), anyInt());
        verify(mSpyEventReportingJobHandler, times(1))
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        verify(mTransaction, times(1)).begin();
        verify(mTransaction, times(1)).end();
    }

    @Test
    public void testSendReportForAlreadyDeliveredReport() throws DatastoreException, IOException {
        EventReport eventReport =
                new EventReport.Builder()
                        .setId("eventReportId")
                        .setStatus(EventReport.Status.DELIVERED)
                        .setRegistrationOrigin(REPORTING_ORIGIN)
                        .build();

        when(mMeasurementDao.getEventReport(eventReport.getId())).thenReturn(eventReport);
        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_INVALID_ARGUMENT,
                mSpyEventReportingJobHandler.performReport(
                        eventReport.getId(), new ReportingStatus()));

        verify(mMeasurementDao, never()).markEventReportStatus(any(), anyInt());
        verify(mSpyEventReportingJobHandler, times(0))
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        verify(mTransaction, times(1)).begin();
        verify(mTransaction, times(1)).end();
    }

    @Test
    public void testPerformScheduledPendingReportsForMultipleReports()
            throws DatastoreException, IOException, JSONException {
        EventReport eventReport1 =
                new EventReport.Builder()
                        .setId("eventReport1")
                        .setSourceEventId(new UnsignedLong(1234L))
                        .setAttributionDestinations(ATTRIBUTION_DESTINATIONS)
                        .setStatus(EventReport.Status.PENDING)
                        .setReportTime(1000L)
                        .setRegistrationOrigin(REPORTING_ORIGIN)
                        .build();
        JSONObject eventReportPayload1 =
                new EventReportPayload.Builder()
                        .setReportId(eventReport1.getId())
                        .setSourceEventId(eventReport1.getSourceEventId())
                        .setAttributionDestination(eventReport1.getAttributionDestinations())
                        .build()
                        .toJson();
        EventReport eventReport2 =
                new EventReport.Builder()
                        .setId("eventReport2")
                        .setSourceEventId(new UnsignedLong(12345L))
                        .setAttributionDestinations(ATTRIBUTION_DESTINATIONS)
                        .setStatus(EventReport.Status.PENDING)
                        .setReportTime(1100L)
                        .setRegistrationOrigin(REPORTING_ORIGIN)
                        .build();
        JSONObject eventReportPayload2 =
                new EventReportPayload.Builder()
                        .setReportId(eventReport2.getId())
                        .setSourceEventId(eventReport2.getSourceEventId())
                        .setAttributionDestination(eventReport2.getAttributionDestinations())
                        .build()
                        .toJson();

        when(mMeasurementDao.getPendingEventReportIdsInWindow(1000, 1100))
                .thenReturn(List.of(eventReport1.getId(), eventReport2.getId()));
        when(mMeasurementDao.getEventReport(eventReport1.getId())).thenReturn(eventReport1);
        when(mMeasurementDao.getEventReport(eventReport2.getId())).thenReturn(eventReport2);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyEventReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), any());
        doReturn(eventReportPayload1)
                .when(mSpyEventReportingJobHandler)
                .createReportJsonPayload(eventReport1);
        doReturn(eventReportPayload2)
                .when(mSpyEventReportingJobHandler)
                .createReportJsonPayload(eventReport2);

        Assert.assertTrue(
                mSpyEventReportingJobHandler.performScheduledPendingReportsInWindow(1000, 1100));

        verify(mMeasurementDao, times(2)).markEventReportStatus(any(), anyInt());
        verify(mSpyEventReportingJobHandler, times(2))
                .makeHttpPostRequest(Mockito.eq(REPORTING_ORIGIN), Mockito.any());
        verify(mTransaction, times(5)).begin();
        verify(mTransaction, times(5)).end();
    }
}
