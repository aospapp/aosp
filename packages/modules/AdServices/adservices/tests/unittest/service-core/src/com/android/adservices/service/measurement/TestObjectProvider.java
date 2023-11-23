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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.annotation.IntDef;
import android.content.ContentResolver;
import android.test.mock.MockContentResolver;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.data.measurement.DatastoreManager;
import com.android.adservices.data.measurement.deletion.MeasurementDataDeleter;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.measurement.attribution.AttributionJobHandlerWrapper;
import com.android.adservices.service.measurement.inputverification.ClickVerifier;
import com.android.adservices.service.measurement.noising.SourceNoiseHandler;
import com.android.adservices.service.measurement.registration.AsyncRegistrationQueueRunner;
import com.android.adservices.service.measurement.registration.AsyncSourceFetcher;
import com.android.adservices.service.measurement.registration.AsyncTriggerFetcher;
import com.android.adservices.service.measurement.reporting.DebugReportApi;
import com.android.adservices.service.measurement.reporting.EventReportWindowCalcDelegate;
import com.android.adservices.service.measurement.util.UnsignedLong;

import org.mockito.stubbing.Answer;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

class TestObjectProvider {
    private static final long ONE_HOUR_IN_MILLIS = TimeUnit.HOURS.toMillis(1);

    @IntDef(
            value = {
                Type.DENOISED,
                Type.NOISY,
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface Type {
        int DENOISED = 1;
        int NOISY = 2;
    }

    static AttributionJobHandlerWrapper getAttributionJobHandler(
            DatastoreManager datastoreManager, Flags flags) {
        return new AttributionJobHandlerWrapper(
                datastoreManager,
                flags,
                new DebugReportApi(ApplicationProvider.getApplicationContext(), flags),
                new EventReportWindowCalcDelegate(flags),
                new SourceNoiseHandler(flags));
    }

    static MeasurementImpl getMeasurementImpl(
            DatastoreManager datastoreManager,
            ClickVerifier clickVerifier,
            MeasurementDataDeleter measurementDataDeleter,
            ContentResolver contentResolver) {
        return spy(
                new MeasurementImpl(
                        null,
                        datastoreManager,
                        clickVerifier,
                        measurementDataDeleter,
                        contentResolver));
    }

    static AsyncRegistrationQueueRunner getAsyncRegistrationQueueRunner(
            @Type int type,
            DatastoreManager datastoreManager,
            AsyncSourceFetcher asyncSourceFetcher,
            AsyncTriggerFetcher asyncTriggerFetcher,
            DebugReportApi debugReportApi) {
        SourceNoiseHandler sourceNoiseHandler =
                spy(new SourceNoiseHandler(FlagsFactory.getFlagsForTest()));
        if (type == Type.DENOISED) {
            // Disable Impression Noise
            doReturn(Collections.emptyList())
                    .when(sourceNoiseHandler)
                    .assignAttributionModeAndGenerateFakeReports(any(Source.class));
        } else if (type == Type.NOISY) {
            // Create impression noise with 100% probability
            Answer<?> answerSourceEventReports =
                    invocation -> {
                        Source source = invocation.getArgument(0);
                        source.setAttributionMode(Source.AttributionMode.FALSELY);
                        return Collections.singletonList(
                                new Source.FakeReport(
                                        new UnsignedLong(0L),
                                        source.getExpiryTime() + ONE_HOUR_IN_MILLIS,
                                        source.getAppDestinations()));
                    };
            doAnswer(answerSourceEventReports)
                    .when(sourceNoiseHandler)
                    .assignAttributionModeAndGenerateFakeReports(any(Source.class));
        }

        return new AsyncRegistrationQueueRunner(
                new MockContentResolver(),
                asyncSourceFetcher,
                asyncTriggerFetcher,
                datastoreManager,
                debugReportApi,
                sourceNoiseHandler);
    }
}
