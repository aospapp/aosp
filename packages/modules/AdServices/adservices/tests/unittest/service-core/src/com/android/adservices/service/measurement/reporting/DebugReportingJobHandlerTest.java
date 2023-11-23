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
import com.android.adservices.service.measurement.WebUtil;

import org.json.JSONArray;
import org.json.JSONException;
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

/** Unit test for {@link DebugReportingJobHandler} */
@RunWith(MockitoJUnitRunner.class)
public class DebugReportingJobHandlerTest {

    private static final Uri REGISTRATION_URI = WebUtil.validUri("https://subdomain.example.test");

    protected static final Context sContext = ApplicationProvider.getApplicationContext();
    DatastoreManager mDatastoreManager;

    @Mock IMeasurementDao mMeasurementDao;

    @Mock ITransaction mTransaction;

    @Mock EnrollmentDao mEnrollmentDao;

    DebugReportingJobHandler mDebugReportingJobHandler;
    DebugReportingJobHandler mSpyDebugReportingJobHandler;
    DebugReportingJobHandler mSpyDebugDebugReportingJobHandler;

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
        mDebugReportingJobHandler = new DebugReportingJobHandler(mEnrollmentDao, mDatastoreManager);
        mSpyDebugReportingJobHandler = Mockito.spy(mDebugReportingJobHandler);
        mSpyDebugDebugReportingJobHandler =
                Mockito.spy(new DebugReportingJobHandler(mEnrollmentDao, mDatastoreManager));
    }

    @Test
    public void testSendDebugReportForSuccess()
            throws DatastoreException, IOException, JSONException {
        DebugReport debugReport = createDebugReport1();
        JSONArray debugReportPayload = new JSONArray();
        debugReportPayload.put(debugReport.toPayloadJson());

        when(mMeasurementDao.getDebugReport(debugReport.getId())).thenReturn(debugReport);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyDebugReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REGISTRATION_URI), Mockito.any());
        doReturn(debugReportPayload)
                .when(mSpyDebugReportingJobHandler)
                .createReportJsonPayload(Mockito.any());

        doNothing().when(mMeasurementDao).deleteDebugReport(debugReport.getId());

        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_SUCCESS,
                mSpyDebugReportingJobHandler.performReport(debugReport.getId()));

        verify(mMeasurementDao, times(1)).deleteDebugReport(any());
        verify(mTransaction, times(2)).begin();
        verify(mTransaction, times(2)).end();
    }

    @Test
    public void testSendDebugReportForFailure()
            throws DatastoreException, IOException, JSONException {
        DebugReport debugReport = createDebugReport1();
        JSONArray debugReportPayload = new JSONArray();
        debugReportPayload.put(debugReport.toPayloadJson());
        when(mMeasurementDao.getDebugReport(debugReport.getId())).thenReturn(debugReport);
        doReturn(HttpURLConnection.HTTP_BAD_REQUEST)
                .when(mSpyDebugReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REGISTRATION_URI), Mockito.any());
        doReturn(debugReportPayload)
                .when(mSpyDebugReportingJobHandler)
                .createReportJsonPayload(Mockito.any());

        Assert.assertEquals(
                AdServicesStatusUtils.STATUS_IO_ERROR,
                mSpyDebugReportingJobHandler.performReport(debugReport.getId()));

        verify(mMeasurementDao, never()).deleteDebugReport(any());
        verify(mTransaction, times(1)).begin();
        verify(mTransaction, times(1)).end();
    }

    @Test
    public void testPerformScheduledReportsForMultipleReports()
            throws DatastoreException, IOException, JSONException {
        DebugReport debugReport1 = createDebugReport1();
        JSONArray debugReportPayload1 = new JSONArray();
        debugReportPayload1.put(debugReport1.toPayloadJson());
        DebugReport debugReport2 = createDebugReport2();
        JSONArray debugReportPayload2 = new JSONArray();
        debugReportPayload2.put(debugReport2.toPayloadJson());

        when(mMeasurementDao.getDebugReportIds())
                .thenReturn(List.of(debugReport1.getId(), debugReport2.getId()));
        when(mMeasurementDao.getDebugReport(debugReport1.getId())).thenReturn(debugReport1);
        when(mMeasurementDao.getDebugReport(debugReport2.getId())).thenReturn(debugReport2);
        doReturn(HttpURLConnection.HTTP_OK)
                .when(mSpyDebugReportingJobHandler)
                .makeHttpPostRequest(Mockito.eq(REGISTRATION_URI), any());
        doReturn(debugReportPayload1)
                .when(mSpyDebugReportingJobHandler)
                .createReportJsonPayload(debugReport1);
        doReturn(debugReportPayload2)
                .when(mSpyDebugReportingJobHandler)
                .createReportJsonPayload(debugReport2);

        mSpyDebugReportingJobHandler.performScheduledPendingReports();

        verify(mMeasurementDao, times(2)).deleteDebugReport(any());
        verify(mTransaction, times(5)).begin();
        verify(mTransaction, times(5)).end();
    }

    private DebugReport createDebugReport1() {
        return new DebugReport.Builder()
                .setId("reportId1")
                .setType("trigger-event-deduplicated")
                .setBody(
                        " {\n"
                                + "      \"attribution_destination\":"
                                + " \"https://destination.example\",\n"
                                + "      \"source_event_id\": \"45623\"\n"
                                + "    }")
                .setEnrollmentId("1")
                .setRegistrationOrigin(REGISTRATION_URI)
                .build();
    }

    private DebugReport createDebugReport2() {
        return new DebugReport.Builder()
                .setId("reportId2")
                .setType("source-destination-limit")
                .setBody(
                        " {\n"
                                + "      \"attribution_destination\":"
                                + " \"https://destination.example\",\n"
                                + "      \"source_event_id\": \"45623\"\n"
                                + "    }")
                .setEnrollmentId("1")
                .setRegistrationOrigin(REGISTRATION_URI)
                .build();
    }
}
