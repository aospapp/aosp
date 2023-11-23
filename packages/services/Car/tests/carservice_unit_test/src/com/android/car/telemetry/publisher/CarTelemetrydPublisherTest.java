/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.telemetry.publisher;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.automotive.telemetry.internal.ICarDataListener;
import android.automotive.telemetry.internal.ICarTelemetryInternal;
import android.car.telemetry.TelemetryProto;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.car.CarLog;
import com.android.car.telemetry.databroker.DataSubscriber;
import com.android.car.test.FakeHandlerWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class CarTelemetrydPublisherTest extends AbstractExtendedMockitoTestCase {
    private static final String SERVICE_NAME = ICarTelemetryInternal.DESCRIPTOR + "/default";
    private static final int CAR_DATA_ID_1 = 1;
    private static final TelemetryProto.Publisher PUBLISHER_PARAMS_1 =
            TelemetryProto.Publisher.newBuilder()
                    .setCartelemetryd(TelemetryProto.CarTelemetrydPublisher.newBuilder()
                            .setId(CAR_DATA_ID_1))
                    .build();

    private final FakeHandlerWrapper mFakeHandlerWrapper =
            new FakeHandlerWrapper(Looper.getMainLooper(), FakeHandlerWrapper.Mode.IMMEDIATE);
    private final FakePublisherListener mFakePublisherListener = new FakePublisherListener();

    @Mock private IBinder mMockBinder;
    @Mock private DataSubscriber mMockDataSubscriber;

    @Captor private ArgumentCaptor<IBinder.DeathRecipient> mLinkToDeathCallbackCaptor;

    private FakeCarTelemetryInternal mFakeCarTelemetryInternal;
    private CarTelemetrydPublisher mPublisher;

    public CarTelemetrydPublisherTest() {
        super(CarLog.TAG_TELEMETRY);
    }

    @Before
    public void setUp() throws Exception {
        mPublisher = new CarTelemetrydPublisher(
                mFakePublisherListener, mFakeHandlerWrapper.getMockHandler());
        mFakeCarTelemetryInternal = new FakeCarTelemetryInternal(mMockBinder);
        when(mMockDataSubscriber.getPublisherParam()).thenReturn(PUBLISHER_PARAMS_1);
        when(mMockBinder.queryLocalInterface(any())).thenReturn(mFakeCarTelemetryInternal);
        doNothing().when(mMockBinder).linkToDeath(mLinkToDeathCallbackCaptor.capture(), anyInt());
        doReturn(mMockBinder).when(() -> ServiceManager.checkService(SERVICE_NAME));
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder.spyStatic(ServiceManager.class);
    }

    @Test
    public void testAddDataSubscriber_registersNewListener() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        assertThat(mFakeCarTelemetryInternal.mListener).isNotNull();
        assertThat(mPublisher.isConnectedToCarTelemetryd()).isTrue();
        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isTrue();
    }

    @Test
    public void testAddDataSubscriber_withInvalidId_fails() {
        DataSubscriber invalidDataSubscriber = Mockito.mock(DataSubscriber.class);
        when(invalidDataSubscriber.getPublisherParam()).thenReturn(
                TelemetryProto.Publisher.newBuilder()
                        .setCartelemetryd(TelemetryProto.CarTelemetrydPublisher.newBuilder()
                                .setId(42000))  // invalid ID
                        .build());

        Throwable error = assertThrows(IllegalArgumentException.class,
                () -> mPublisher.addDataSubscriber(invalidDataSubscriber));

        assertThat(error).hasMessageThat().contains("Invalid CarData ID");
        assertThat(mFakeCarTelemetryInternal.mListener).isNull();
        assertThat(mPublisher.isConnectedToCarTelemetryd()).isFalse();
        assertThat(mPublisher.hasDataSubscriber(invalidDataSubscriber)).isFalse();
    }

    @Test
    public void testRemoveDataSubscriber_ignoresIfNotFound() {
        mPublisher.removeDataSubscriber(mMockDataSubscriber);
    }

    @Test
    public void testRemoveDataSubscriber_removesOnlySingleSubscriber() {
        DataSubscriber subscriber2 = Mockito.mock(DataSubscriber.class);
        when(subscriber2.getPublisherParam()).thenReturn(PUBLISHER_PARAMS_1);
        mPublisher.addDataSubscriber(mMockDataSubscriber);
        mPublisher.addDataSubscriber(subscriber2);

        mPublisher.removeDataSubscriber(subscriber2);

        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isTrue();
        assertThat(mPublisher.hasDataSubscriber(subscriber2)).isFalse();
        assertThat(mFakeCarTelemetryInternal.mListener).isNotNull();
    }

    @Test
    public void testRemoveDataSubscriber_disconnectsFromICarTelemetry() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        mPublisher.removeDataSubscriber(mMockDataSubscriber);

        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isFalse();
        assertThat(mFakeCarTelemetryInternal.mListener).isNull();
    }

    @Test
    public void testRemoveAllDataSubscribers_succeeds() {
        DataSubscriber subscriber2 = Mockito.mock(DataSubscriber.class);
        when(subscriber2.getPublisherParam()).thenReturn(PUBLISHER_PARAMS_1);
        mPublisher.addDataSubscriber(mMockDataSubscriber);
        mPublisher.addDataSubscriber(subscriber2);

        mPublisher.removeAllDataSubscribers();

        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isFalse();
        assertThat(mPublisher.hasDataSubscriber(subscriber2)).isFalse();
        assertThat(mFakeCarTelemetryInternal.mListener).isNull();
    }

    @Test
    public void testNotifiesFailureConsumer_whenBinderDies() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        mLinkToDeathCallbackCaptor.getValue().binderDied();

        assertThat(mFakeCarTelemetryInternal.mSetListenerCallCount).isEqualTo(1);
        assertThat(mFakePublisherListener.mPublisherFailure).hasMessageThat()
                .contains("ICarTelemetryInternal binder died");
        assertThat(mFakePublisherListener.mFailedConfigs).hasSize(1);  // got all the failed configs
    }

    @Test
    public void testNotifiesFailureConsumer_whenFailsConnectToService() {
        mFakeCarTelemetryInternal.setApiFailure(new RemoteException("tough life"));

        mPublisher.addDataSubscriber(mMockDataSubscriber);

        assertThat(mFakePublisherListener.mPublisherFailure).hasMessageThat()
                .contains("Cannot set CarData listener");
        assertThat(mFakePublisherListener.mFailedConfigs).hasSize(1);
    }

    private static class FakeCarTelemetryInternal implements ICarTelemetryInternal {
        @Nullable ICarDataListener mListener;
        int mSetListenerCallCount = 0;
        private final IBinder mBinder;
        @Nullable private RemoteException mApiFailure = null;

        FakeCarTelemetryInternal(IBinder binder) {
            mBinder = binder;
        }

        @Override
        public IBinder asBinder() {
            return mBinder;
        }

        @Override
        public void setListener(ICarDataListener listener) throws RemoteException {
            mSetListenerCallCount += 1;
            if (mApiFailure != null) {
                throw mApiFailure;
            }
            mListener = listener;
        }

        @Override
        public void clearListener() throws RemoteException {
            if (mApiFailure != null) {
                throw mApiFailure;
            }
            mListener = null;
        }

        void setApiFailure(RemoteException e) {
            mApiFailure = e;
        }

        @Override
        public String getInterfaceHash() {
            return ICarTelemetryInternal.HASH;
        }

        @Override
        public int getInterfaceVersion() {
            return ICarTelemetryInternal.VERSION;
        }
    }
}
