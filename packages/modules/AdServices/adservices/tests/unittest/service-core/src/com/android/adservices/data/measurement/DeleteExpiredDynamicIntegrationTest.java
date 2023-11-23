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

package com.android.adservices.data.measurement;

import android.net.Uri;

import com.android.adservices.data.DbTestUtil;
import com.android.adservices.service.Flags;
import com.android.adservices.service.measurement.EventSurfaceType;
import com.android.adservices.service.measurement.Source;
import com.android.adservices.service.measurement.WebUtil;
import com.android.adservices.service.measurement.util.UnsignedLong;

import org.json.JSONException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;

/**
 * Tests for {@link MeasurementDao} app deletion that affect the database, and
 * set variables that change from test to test (hence the name, "dynamic").
 */
@RunWith(Parameterized.class)
public class DeleteExpiredDynamicIntegrationTest extends AbstractDbIntegrationTest {
    private final DatastoreManager mDatastoreManager;

    @Parameterized.Parameters(name = "{2}")
    public static Collection<Object[]> data() throws IOException, JSONException {
        InputStream inputStream = sContext.getAssets().open(
                "measurement_delete_expired_test.json");
        Collection<Object[]> testCases =
                AbstractDbIntegrationTest.getTestCasesFrom(inputStream, null);

        // Add a non-expired Source.
        long insideExpiredWindow =
                System.currentTimeMillis() - Flags.MEASUREMENT_DATA_EXPIRY_WINDOW_MS / 2;

        Source source =
                new Source.Builder()
                        .setEnrollmentId("enrollment-id")
                        .setPublisher(Uri.parse("https://example.test/aS"))
                        .setId("non-expired")
                        .setEventId(new UnsignedLong(2L))
                        .setPriority(3L)
                        .setEventTime(insideExpiredWindow)
                        .setExpiryTime(5L)
                        .setStatus(Source.Status.ACTIVE)
                        .setRegistrant(Uri.parse("android-app://com.example.abc"))
                        .setRegistrationOrigin(
                                WebUtil.validUri("https://example1.test-registration.test"))
                        .build();

        SourceDestination sourceDest =
                new SourceDestination.Builder()
                        .setSourceId(source.getId())
                        .setDestination("android-app://com.destination")
                        .setDestinationType(EventSurfaceType.APP)
                        .build();

        for (Object[] testCase : testCases) {
            // input
            ((DbState) testCase[0]).mSourceList.add(source);
            ((DbState) testCase[0]).mSourceDestinationList.add(sourceDest);
            // output
            ((DbState) testCase[1]).mSourceList.add(source);
            ((DbState) testCase[1]).mSourceDestinationList.add(sourceDest);
        }

        return testCases;
    }

    // The 'name' parameter is needed for the JUnit parameterized
    // test, although it's ostensibly unused by this constructor.
    public DeleteExpiredDynamicIntegrationTest(DbState input, DbState output, String name) {
        super(input, output);
        this.mDatastoreManager =
                new SQLDatastoreManager(DbTestUtil.getMeasurementDbHelperForTest());
    }

    public void runActionToTest() {
        long earliestValidInsertion =
                System.currentTimeMillis() - Flags.MEASUREMENT_DATA_EXPIRY_WINDOW_MS;
        mDatastoreManager.runInTransaction(dao -> dao.deleteExpiredRecords(earliestValidInsertion));
    }
}
