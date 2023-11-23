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

package android.adservices.clients.measurement;

import android.adservices.measurement.DeletionRequest;
import android.adservices.measurement.MeasurementManager;
import android.adservices.measurement.WebSourceRegistrationRequest;
import android.adservices.measurement.WebTriggerRegistrationRequest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.OutcomeReceiver;
import android.view.InputEvent;

import androidx.concurrent.futures.CallbackToFutureAdapter;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Objects;
import java.util.concurrent.Executor;

/** MeasurementClient. Currently, this is for testing only. */
// TODO: This should be in JetPack code.
public class MeasurementClient {
    private MeasurementManager mMeasurementManager;
    private Context mContext;
    private Executor mExecutor;

    private MeasurementClient(@NonNull Context context, @Nullable Executor executor) {
        mContext = context;
        mExecutor = executor;
        mMeasurementManager =
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        ? mContext.getSystemService(MeasurementManager.class)
                        : MeasurementManager.get(context);
    }

    /**
     * Invokes the {@code registerSource} method of {@link MeasurementManager}, and returns a Void
     * future.
     */
    @NonNull
    public ListenableFuture<Void> registerSource(
            @NonNull Uri attributionSource, @Nullable InputEvent inputEvent) {
        return CallbackToFutureAdapter.getFuture(
                completer -> {
                    mMeasurementManager.registerSource(
                            attributionSource,
                            inputEvent,
                            mExecutor,
                            new OutcomeReceiver<Object, Exception>() {
                                @Override
                                public void onResult(@NonNull Object ignoredResult) {
                                    completer.set(null);
                                }

                                @Override
                                public void onError(@NonNull Exception error) {
                                    completer.setException(error);
                                }
                            });
                    // This value is used only for debug purposes: it will be used in toString()
                    // of returned future or error cases.
                    return "registerSource";
                });
    }

    /**
     * Invokes the {@code registerTrigger} method of {@link MeasurementManager}, and returns a Void
     * future.
     */
    @NonNull
    public ListenableFuture<Void> registerTrigger(@NonNull Uri trigger) {
        return CallbackToFutureAdapter.getFuture(
                completer -> {
                    mMeasurementManager.registerTrigger(
                            trigger,
                            mExecutor,
                            new OutcomeReceiver<Object, Exception>() {
                                @Override
                                public void onResult(@NonNull Object ignoredResult) {
                                    completer.set(null);
                                }

                                @Override
                                public void onError(@NonNull Exception error) {
                                    completer.setException(error);
                                }
                            });
                    // This value is used only for debug purposes: it will be used in toString()
                    // of returned future or error cases.
                    return "registerTrigger";
                });
    }
    /**
     * Invokes the {@code registerWebSource} method of {@link MeasurementManager}, and returns a
     * Void future.
     */
    @NonNull
    public ListenableFuture<Void> registerWebSource(@NonNull WebSourceRegistrationRequest request) {
        return CallbackToFutureAdapter.getFuture(
                completer -> {
                    mMeasurementManager.registerWebSource(
                            request,
                            mExecutor,
                            new OutcomeReceiver<Object, Exception>() {
                                @Override
                                public void onResult(@NonNull Object ignoredResult) {
                                    completer.set(null);
                                }

                                @Override
                                public void onError(@NonNull Exception error) {
                                    completer.setException(error);
                                }
                            });
                    // This value is used only for debug purposes: it will be used in toString()
                    // of returned future or error cases.
                    return "registerWebSource";
                });
    }

    /**
     * Invokes the {@code registerWebTrigger} method of {@link MeasurementManager}, and returns a
     * Void future.
     */
    @NonNull
    public ListenableFuture<Void> registerWebTrigger(
            @NonNull WebTriggerRegistrationRequest request) {
        return CallbackToFutureAdapter.getFuture(
                completer -> {
                    mMeasurementManager.registerWebTrigger(
                            request,
                            mExecutor,
                            new OutcomeReceiver<Object, Exception>() {
                                @Override
                                public void onResult(@NonNull Object ignoredResult) {
                                    completer.set(null);
                                }

                                @Override
                                public void onError(@NonNull Exception error) {
                                    completer.setException(error);
                                }
                            });
                    // This value is used only for debug purposes: it will be used in toString()
                    // of returned future or error cases.
                    return "registerWebTrigger";
                });
    }

    /**
     * Invokes the {@code registerWebTrigger} method of {@link MeasurementManager} with a null
     * callback, and returns a Void future if successful, or an {@link Exception} if unsuccessful.
     */
    @NonNull
    public ListenableFuture<Void> deleteRegistrations(@NonNull DeletionRequest request) {
        return CallbackToFutureAdapter.getFuture(
                completer -> {
                    mMeasurementManager.deleteRegistrations(
                            request,
                            mExecutor,
                            new OutcomeReceiver<Object, Exception>() {
                                @Override
                                public void onResult(@NonNull Object ignoredResult) {
                                    completer.set(null);
                                }

                                @Override
                                public void onError(@NonNull Exception error) {
                                    completer.setException(error);
                                }
                            });
                    // This value is used only for debug purposes: it will be used in toString()
                    // of returned future or error cases.
                    return "deleteRegistrations";
                });
    }

    /** Builder class. */
    public static final class Builder {
        private Context mContext;
        private Executor mExecutor;

        /** Sets the context. */
        @NonNull
        public MeasurementClient.Builder setContext(@NonNull Context context) {
            Objects.requireNonNull(context);
            mContext = context;
            return this;
        }

        /** Sets the executor. */
        @NonNull
        public MeasurementClient.Builder setExecutor(@Nullable Executor executor) {
            mExecutor = executor;
            return this;
        }

        /** Builds the MeasurementClient. */
        @NonNull
        public MeasurementClient build() {
            Objects.requireNonNull(mContext);

            return new MeasurementClient(mContext, mExecutor);
        }
    }
}
