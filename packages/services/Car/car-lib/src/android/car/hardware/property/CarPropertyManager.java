/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.car.hardware.property;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;
import static com.android.car.internal.property.CarPropertyHelper.STATUS_OK;
import static com.android.car.internal.property.CarPropertyHelper.SYNC_OP_LIMIT_TRY_AGAIN;
import static com.android.car.internal.util.VersionUtils.assertPlatformVersionAtLeastU;

import static java.lang.Integer.toHexString;
import static java.util.Objects.requireNonNull;

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.VehiclePropertyIds;
import android.car.annotation.AddedInOrBefore;
import android.car.annotation.ApiRequirements;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.internal.CarPropertyEventCallbackController;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.SingleMessageHandler;
import com.android.car.internal.os.HandlerExecutor;
import com.android.car.internal.property.AsyncPropertyServiceRequest;
import com.android.car.internal.property.AsyncPropertyServiceRequestList;
import com.android.car.internal.property.CarPropertyHelper;
import com.android.car.internal.property.GetSetValueResult;
import com.android.car.internal.property.GetSetValueResultList;
import com.android.car.internal.property.IAsyncPropertyResultCallback;
import com.android.car.internal.property.InputSanitizationUtils;
import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides an application interface for interacting with the Vehicle specific properties.
 * For details about the individual properties, see the descriptions in {@link VehiclePropertyIds}
 */
public class CarPropertyManager extends CarManagerBase {
    private static final String TAG = "CarPropertyManager";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final int MSG_GENERIC_EVENT = 0;
    private static final int SYNC_OP_RETRY_SLEEP_IN_MS = 10;
    private static final int SYNC_OP_RETRY_MAX_COUNT = 10;
    /**
     * The default timeout in MS for {@link CarPropertyManager#getPropertiesAsync}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final long ASYNC_GET_DEFAULT_TIMEOUT_MS = 10_000;

    private final SingleMessageHandler<CarPropertyEvent> mHandler;
    private final ICarProperty mService;
    private final int mAppTargetSdk;
    private final AtomicInteger mRequestIdCounter = new AtomicInteger(0);
    @GuardedBy("mLock")
    private final SparseArray<AsyncPropertyRequestInfo<?, ?>> mRequestIdToAsyncRequestInfo =
            new SparseArray<>();
    private final AsyncPropertyResultCallback mAsyncPropertyResultCallback =
            new AsyncPropertyResultCallback();

    private final CarPropertyEventListenerToService mCarPropertyEventToService =
            new CarPropertyEventListenerToService(this);

    // This lock is shared with all CarPropertyEventCallbackController instances to prevent
    // potential deadlock.
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseArray<CarPropertyEventCallbackController>
            mPropertyIdToCarPropertyEventCallbackController = new SparseArray<>();

    private final CarPropertyEventCallbackController.RegistrationUpdateCallback
            mRegistrationUpdateCallback =
            new CarPropertyEventCallbackController.RegistrationUpdateCallback() {
                @Override
                public boolean register(int propertyId, float updateRateHz) {
                    try {
                        mService.registerListener(propertyId, updateRateHz,
                                mCarPropertyEventToService);
                    } catch (RemoteException e) {
                        handleRemoteExceptionFromCarService(e);
                        return false;
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "register: propertyId: "
                                + VehiclePropertyIds.toString(propertyId) + ", updateRateHz: "
                                + updateRateHz + ", exception: ", e);
                        return false;
                    }
                    return true;
                }

                @Override
                public boolean unregister(int propertyId) {
                    try {
                        mService.unregisterListener(propertyId, mCarPropertyEventToService);
                    } catch (RemoteException e) {
                        handleRemoteExceptionFromCarService(e);
                        return false;
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "unregister: propertyId: "
                                + VehiclePropertyIds.toString(propertyId) + ", exception: ", e);
                        return false;
                    }
                    return true;
                }
            };

    private final GetPropertyResultCallback mGetPropertyResultCallback =
            new GetPropertyResultCallback();

    private final SetPropertyResultCallback mSetPropertyResultCallback =
            new SetPropertyResultCallback();

    /**
     * Application registers {@link CarPropertyEventCallback} object to receive updates and changes
     * to subscribed Vehicle specific properties.
     */
    public interface CarPropertyEventCallback {
        /**
         * Called when a property is updated
         *
         * @param value the new value of the property
         */
        @AddedInOrBefore(majorVersion = 33)
        void onChangeEvent(CarPropertyValue value);

        /**
         * Called when an error is detected when setting a property.
         *
         * @param propertyId the property ID which has detected an error
         * @param areaId the area ID which has detected an error
         *
         * @see CarPropertyEventCallback#onErrorEvent(int, int, int)
         */
        @AddedInOrBefore(majorVersion = 33)
        void onErrorEvent(int propertyId, int areaId);

        /**
         * Called when an error is detected when setting a property.
         *
         * <p>Clients which changed the property value in the areaId most recently will receive
         * this callback. If multiple clients set a property for the same area ID simultaneously,
         * which one takes precedence is undefined. Typically, the last set operation
         * (in the order that they are issued to car's ECU) overrides the previous set operations.
         * The delivered error reflects the error happened in the last set operation.
         *
         * @param propertyId the property ID which is detected an error
         * @param areaId the area ID which is detected an error
         * @param errorCode the error code is raised in the car
         */
        @AddedInOrBefore(majorVersion = 33)
        default void onErrorEvent(int propertyId, int areaId,
                @CarSetPropertyErrorCode int errorCode) {
            if (DBG) {
                Log.d(TAG, "onErrorEvent propertyId: " + VehiclePropertyIds.toString(propertyId)
                        + " areaId: 0x" + toHexString(areaId) + " ErrorCode: " + errorCode);
            }
            onErrorEvent(propertyId, areaId);
        }
    }

    /**
     * A callback {@link CarPropertyManager#getPropertiesAsync} when succeeded or failed.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public interface GetPropertyCallback {
        /**
         * Method called when {@link GetPropertyRequest} successfully gets a result.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        void onSuccess(@NonNull GetPropertyResult<?> getPropertyResult);

        /**
         * Method called when {@link GetPropertyRequest} returns an error.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        void onFailure(@NonNull PropertyAsyncError propertyAsyncError);
    }

    /**
     * A callback {@link CarPropertyManager#setPropertiesAsync} when succeeded or failed.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public interface SetPropertyCallback {
        /**
         * Method called when the {@link SetPropertyRequest} successfully set the value.
         *
         * <p>This means: the set value request is successfully sent to vehicle
         *
         * <p>and
         *
         * <p>either the current property value is already the target value, or we have received a
         * property update event indicating the value is updated to the target value.
         *
         * <p>If multiple clients set a property for the same area ID simultaneously with different
         * values, the order is undefined. One possible case is that both requests are sent to the
         * vehicle bus, which causes two property update events. As a result, the success callback
         * would be called for both clients, but in an undefined order. This means that even if
         * the success callback is called, it doesn't necessarily mean getting the property would
         * return the same value you just set. Another client might have changed the value after you
         * set it.
         *
         * <p>If only one requests is successfully processed by the vehicle bus, overwriting the
         * other request, then only one success callback would be called for one client. The other
         * client would get the failure callback with
         * {@link CarPropertyManager#STATUS_ERROR_TIMEOUT} error code.
         *
         * <p>If multiple clients set a property for the same area ID simultaneously with the same
         * value. The success callback for both clients would be called in an undefined order.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        void onSuccess(@NonNull SetPropertyResult setPropertyResult);

        /**
         * Method called when {@link SetPropertyRequest} returns an error.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        void onFailure(@NonNull PropertyAsyncError propertyAsyncError);
    }

    /**
     * An async get/set property request.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public interface AsyncPropertyRequest {
        /**
         * Returns the unique ID for this request.
         *
         * <p>Each request must have a unique request ID so the responses can be differentiated.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        int getRequestId();

        /**
         * Returns the ID for the property of this request.
         *
         * <p>The ID must be one of the {@link VehiclePropertyIds} or vendor property IDs.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        int getPropertyId();

        /**
         * Returns the area ID for the property of this request.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        int getAreaId();
    }

    /**
     * A request for {@link CarPropertyManager#getPropertiesAsync(List, long, CancellationSignal,
     * Executor, GetPropertyCallback)}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                     minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class GetPropertyRequest implements AsyncPropertyRequest {
        private final int mRequestId;
        private final int mPropertyId;
        private final int mAreaId;

        /**
         * @see AsyncPropertyRequest#getRequestId
         */
        @Override
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getRequestId() {
            return mRequestId;
        }

        /**
         * @see AsyncPropertyRequest#getPropertyId
         */
        @Override
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getPropertyId() {
            return mPropertyId;
        }

        /**
         * @see AsyncPropertyRequest#getAreaId
         */
        @Override
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getAreaId() {
            return mAreaId;
        }

        /**
         * Internal use only. Users should use {@link #generateGetPropertyRequest(int, int)}
         * instead.
         */
        private GetPropertyRequest(int requestId, int propertyId, int areaId) {
            mRequestId = requestId;
            mPropertyId = propertyId;
            mAreaId = areaId;
        }

        /**
         * Prints out debug message.
         */
        @Override
        @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
        public String toString() {
            return new StringBuilder()
                    .append("GetPropertyRequest{request ID: ")
                    .append(mRequestId)
                    .append(", property ID: ")
                    .append(VehiclePropertyIds.toString(mPropertyId))
                    .append(", area ID: ")
                    .append(mAreaId)
                    .append("}").toString();
        }
    }

    /**
     * A request for {@link CarPropertyManager#setPropertiesAsync(List, long, CancellationSignal,
     * Executor, SetPropertyCallback)}.
     *
     * @param <T> the type for the property value, must be one of Object, Boolean, Float, Integer,
     *      Long, Float[], Integer[], Long[], String, byte[], Object[]
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class SetPropertyRequest<T> implements AsyncPropertyRequest {
        private final int mRequestId;
        private final int mPropertyId;
        private final int mAreaId;
        private float mUpdateRateHz = 0.f;
        // By default, the async set operation will wait for the property to be updated to the
        // target value before calling the success callback (or when the target value is the
        // same as the current value).
        private boolean mWaitForPropertyUpdate = true;

        /**
         * The value to set.
         */
        private final T mValue;

        /**
         * Sets the update rate in Hz for listening for property updates for continuous property.
         *
         * <p>If {@code waitForPropertyUpdate} is set to {@code true} (by default) and if the
         * property is set to a different value than its current value, the success callback will be
         * called when a property update event for the new value arrived. This option controls how
         * frequent the property update event should be reported for continuous property. This is
         * similar to {@code updateRateHz} in {@link CarPropertyManager#registerCallback}.
         *
         * <p>This is ignored for non-continuous properties.
         *
         * <p>This is ignored if {@code waitForPropertyUpdate} is set to {@code false}.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public void setUpdateRateHz(float updateRateHz) {
            mUpdateRateHz = updateRateHz;
        }

        /**
         * Sets whether to wait for the property update event before calling success callback.
         *
         * <p>This arguments controls under what conditions the operation is considered succeeded
         * and the success callback will be called.
         *
         * <p>If this is set to {@code true} (by default), the success callback will be called when
         * both of the following coniditions are met:
         *
         * <ul>
         * <li>the set operation is successfully delivered to vehicle bus.
         * <li>the {@code mPropertyId}+{@code mAreaId}'s value already equal to {@code mValue} or
         * is successfully updated to the {@code mValue} through the set operation.
         * </ul>
         *
         * <p>Even if the target value is the same as the current value, we will still send the set
         * operation to the vehicle bus. If caller wants to reduce unnecessary overhead, caller must
         * check existing values before issuing the requests.
         *
         * <p>If the first condition fails, the error callback will be called. If the second
         * condition fails, which means we don't see the property updated to the target value within
         * a specified timeout, the error callback will be called with {@link
         * #STATUS_ERROR_TIMEOUT}.
         *
         * <p>If this is set to {@code false}, the success callback will be called after the
         * set operation is successfully delivered to vehicle bus.
         *
         * <p>Under most cases, client should wait for the property update to verify that the set
         * operation actually succeeded.
         *
         * <p>For cases when the property is write-only (no way to get property update event) or
         * when the property represents some action, instead of an actual state, e.g. key stroke
         * where the property's current value is not meaningful, caller should set
         * {@code waitForPropertyUpdate} to {@code false}.
         *
         * <p>For {@code HVAC_TEMPERATURE_VALUE_SUGGESTION}, this must be set to {@code false}
         * because the updated property value will not be the same as the value to be set.
         *
         * <p>Note that even if this is set to {@code true}, it is only guaranteed that the property
         * value is the target value after the success callback is called if no other clients are
         * changing the property at the same time. It is always possible that another client changes
         * the property value after the property is updated to the target value, but before the
         * client success callback runs. We only guarantee that at some point during the period
         * after the client issues the request and before the success callback is called, the
         * property value was set to the target value.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public void setWaitForPropertyUpdate(boolean waitForPropertyUpdate) {
            mWaitForPropertyUpdate = waitForPropertyUpdate;
        }

        /**
         * @see AsyncPropertyRequest#getRequestId
         */
        @Override
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getRequestId() {
            return mRequestId;
        }

        /**
         * @see AsyncPropertyRequest#getPropertyId
         */
        @Override
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getPropertyId() {
            return mPropertyId;
        }

        /**
         * @see AsyncPropertyRequest#getAreaId
         */
        @Override
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getAreaId() {
            return mAreaId;
        }

        /**
         * Get the property value to set.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public T getValue() {
            return mValue;
        }

        /**
         * Gets the update rate for listening for property updates.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public float getUpdateRateHz() {
            return mUpdateRateHz;
        }

        /**
         * Gets whether to wait for property update event before calling success callback.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public boolean isWaitForPropertyUpdate() {
            return mWaitForPropertyUpdate;
        }

        /**
         * Internal use only. Users should use {@link #generateSetPropertyRequest(int, int, T)}
         * instead.
         */
        private SetPropertyRequest(int requestId, int propertyId, int areaId, T value) {
            mRequestId = requestId;
            mPropertyId = propertyId;
            mAreaId = areaId;
            mValue = value;
        }

        /**
         * Prints out debug message.
         */
        @Override
        @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
        public String toString() {
            return new StringBuilder()
                    .append("SetPropertyRequest{request ID: ")
                    .append(mRequestId)
                    .append(", property ID: ")
                    .append(VehiclePropertyIds.toString(mPropertyId))
                    .append(", area ID: ")
                    .append(mAreaId)
                    .append(", value: ")
                    .append(mValue)
                    .append(", waitForPropertyUpdate: ")
                    .append(mWaitForPropertyUpdate)
                    .append(", mUpdateRateHz: ")
                    .append(mUpdateRateHz)
                    .append("}").toString();
        }
    }

    /**
     * An error result for {@link GetPropertyCallback} or {@link SetPropertyCallback}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class PropertyAsyncError {
        private final int mRequestId;
        private final int mPropertyId;
        private final int mAreaId;
        private final @CarPropertyAsyncErrorCode int mErrorCode;
        private final int mVendorErrorCode;

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getRequestId() {
            return mRequestId;
        }

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getPropertyId() {
            return mPropertyId;
        }

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getAreaId() {
            return mAreaId;
        }

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public @CarPropertyAsyncErrorCode int getErrorCode() {
            return mErrorCode;
        }

        /**
         * Gets the vendor error codes to allow for more detailed error codes.
         *
         * @return the vendor error code if it is set, otherwise 0. A vendor error code will have a
         * range from 0x0000 to 0xffff.
         *
         * @hide
         */
        @SystemApi
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
        public int getVendorErrorCode() {
            assertPlatformVersionAtLeastU();
            return mVendorErrorCode;
        }

        /**
         * Creates a new error result for async property request.
         *
         * @param requestId the request ID
         * @param propertyId the property ID in the request
         * @param areaId the area ID for the property in the request
         * @param errorCode the code indicating the error
         */
        PropertyAsyncError(int requestId, int propertyId, int areaId,
                @CarPropertyAsyncErrorCode int errorCode,
                int vendorErrorCode) {
            mRequestId = requestId;
            mPropertyId = propertyId;
            mAreaId = areaId;
            mErrorCode = errorCode;
            mVendorErrorCode = vendorErrorCode;
        }

        /**
         * Prints out debug message.
         */
        @Override
        @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
        public String toString() {
            return new StringBuilder()
                    .append("PropertyAsyncError{request ID: ")
                    .append(mRequestId)
                    .append(", property: ")
                    .append(VehiclePropertyIds.toString(mPropertyId))
                    .append(", areaId: ")
                    .append(mAreaId)
                    .append(", error code: ")
                    .append(mErrorCode)
                    .append(", vendor error code: ")
                    .append(mVendorErrorCode)
                    .append("}").toString();
        }
    }

    /**
     * A successful result for {@link GetPropertyCallback}.
     *
     * @param <T> the type for the property value, must be one of Object, Boolean, Float, Integer,
     *      Long, Float[], Integer[], Long[], String, byte[], Object[]
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class GetPropertyResult<T> {
        private final int mRequestId;
        private final int mPropertyId;
        private final int mAreaId;
        private final long mTimestampNanos;
        private final T mValue;

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getRequestId() {
            return mRequestId;
        }

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getPropertyId() {
            return mPropertyId;
        }

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getAreaId() {
            return mAreaId;
        }

        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        @NonNull
        public T getValue() {
            return mValue;
        }

        /**
         * Returns the timestamp in nanoseconds at which the value for the vehicle property
         * happened. For a given vehicle property, each new timestamp should be monotonically
         * increasing using the same time base as {@link SystemClock#elapsedRealtimeNanos()}.
         *
         * <p>NOTE: Timestamp should be synchronized with other signals from the platform (e.g.
         * {@link android.location.Location} and {@link android.hardware.SensorEvent} instances).
         * Ideally, timestamp synchronization error should be below 1 millisecond.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public long getTimestampNanos() {
            return mTimestampNanos;
        }

        /**
         * Creates a new value result for async GetProperty request.
         *
         * @param requestId the request ID
         * @param propertyId the property ID in the request
         * @param areaId the area ID for the property in the request
         * @param timestampNanos the timestamp in nanoseconds when this property is updated
         * @param value the property's value
         */
        GetPropertyResult(int requestId, int propertyId, int areaId, long timestampNanos,
                 @NonNull T value) {
            mRequestId = requestId;
            mPropertyId = propertyId;
            mAreaId = areaId;
            mTimestampNanos = timestampNanos;
            mValue = value;
        }

        /**
         * Prints out debug message.
         */
        @Override
        @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
        public String toString() {
            return new StringBuilder()
                    .append("GetPropertyResult{type: ")
                    .append(mValue.getClass())
                    .append(", request ID: ")
                    .append(mRequestId)
                    .append(", property: ")
                    .append(VehiclePropertyIds.toString(mPropertyId))
                    .append(", areaId: ")
                    .append(mAreaId)
                    .append(", value: ")
                    .append(mValue)
                    .append(", timestamp: ")
                    .append(mTimestampNanos).append("ns")
                    .append("}").toString();
        }
    }

    /**
     * A successful result for {@link SetPropertyCallback}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final class SetPropertyResult {
        private final int mRequestId;
        private final int mPropertyId;
        private final int mAreaId;
        private final long mUpdateTimestampNanos;

        /**
         * Gets the ID for the request this result is for.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getRequestId() {
            return mRequestId;
        }

        /**
         * Gets the property ID this result is for.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getPropertyId() {
            return mPropertyId;
        }

        /**
         * Gets the area ID this result is for.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public int getAreaId() {
            return mAreaId;
        }

        /**
         * Gets the timestamp in nanoseconds at which the property was updated to the desired value.
         *
         * <p>The timestamp will use the same time base as
         * {@link SystemClock#elapsedRealtimeNanos()}.
         *
         * <p>NOTE: If {@code waitForPropertyUpdate} is set to {@code false} for the request, then
         * this value will be the time when the async set request is successfully sent to the
         * vehicle bus, not when the property is updated since we have no way of knowing that.
         *
         * <p>NOTE: Timestamp should be synchronized with other signals from the platform (e.g.
         * {@link android.location.Location} and {@link android.hardware.SensorEvent} instances).
         * Ideally, timestamp synchronization error should be below 1 millisecond.
         */
        @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
                         minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
        public long getUpdateTimestampNanos() {
            return mUpdateTimestampNanos;
        }

        SetPropertyResult(int requestId, int propertyId, int areaId, long updateTimestampNanos) {
            mRequestId = requestId;
            mPropertyId = propertyId;
            mAreaId = areaId;
            mUpdateTimestampNanos = updateTimestampNanos;
        }

        /**
         * Prints out debug message.
         */
        @Override
        @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
        public String toString() {
            return new StringBuilder()
                    .append("SetPropertyResult{request ID: ")
                    .append(mRequestId)
                    .append(", property: ")
                    .append(VehiclePropertyIds.toString(mPropertyId))
                    .append(", areaId: ")
                    .append(mAreaId)
                    .append(", updated timestamp: ")
                    .append(mUpdateTimestampNanos).append("ns")
                    .append("}").toString();
        }
    }

    /**
     * An abstract interface for converting async get/set result and calling client callbacks.
     */
    private interface PropertyResultCallback<CallbackType, ResultType> {
        ResultType build(int requestId, int propertyId, int areaId, long timestampNanos,
                @Nullable Object value);
        void onSuccess(CallbackType callback, ResultType result);
        void onFailure(CallbackType callback, PropertyAsyncError error);
    }

    /**
     * Class to hide implementation detail for get/set callbacks.
     */
    private static final class GetPropertyResultCallback implements
            PropertyResultCallback<GetPropertyCallback, GetPropertyResult> {
        public GetPropertyResult build(int requestId, int propertyId, int areaId,
                long timestampNanos, @Nullable Object value) {
            return new GetPropertyResult(requestId, propertyId, areaId, timestampNanos, value);
        }

        public void onSuccess(GetPropertyCallback callback, GetPropertyResult result) {
            if (DBG) {
                Log.d(TAG, "delivering success get property result: " + result);
            }
            callback.onSuccess(result);
        }

        public void onFailure(GetPropertyCallback callback, PropertyAsyncError error) {
            if (DBG) {
                Log.d(TAG, "delivering error get property result: " + error);
            }
            callback.onFailure(error);
        }
    }

    /**
     * Class to hide implementation detail for get/set callbacks.
     */
    private static final class SetPropertyResultCallback implements
            PropertyResultCallback<SetPropertyCallback, SetPropertyResult> {
        public  SetPropertyResult build(int requestId, int propertyId, int areaId,
                long timestampNanos, @Nullable Object value) {
            return new SetPropertyResult(requestId, propertyId, areaId, timestampNanos);
        }

        public void onSuccess(SetPropertyCallback callback, SetPropertyResult result) {
            if (DBG) {
                Log.d(TAG, "delivering success set property result: " + result);
            }
            callback.onSuccess(result);
        }

        public void onFailure(SetPropertyCallback callback, PropertyAsyncError error) {
            if (DBG) {
                Log.d(TAG, "delivering error set property result: " + error);
            }
            callback.onFailure(error);
        }
    }

    /**
     * A class for delivering {@link GetPropertyCallback} or {@link SetPropertyCallback} client
     * callback when {@code IAsyncPropertyResultCallback} returns results.
     */
    private class AsyncPropertyResultCallback extends IAsyncPropertyResultCallback.Stub {

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public void onGetValueResults(GetSetValueResultList getValueResults) {
            this.<GetPropertyRequest, GetPropertyCallback, GetPropertyResult>onResults(
                    getValueResults.getList(), mGetPropertyResultCallback);
        }

        @Override
        public void onSetValueResults(GetSetValueResultList setValueResults) {
            this.<SetPropertyRequest<?>, SetPropertyCallback, SetPropertyResult>onResults(
                    setValueResults.getList(), mSetPropertyResultCallback);
        }

        @SuppressLint("WrongConstant")
        private <RequestType extends AsyncPropertyRequest, CallbackType, ResultType> void onResults(
                List<GetSetValueResult> results,
                PropertyResultCallback<CallbackType, ResultType> propertyResultCallback) {
            for (int i = 0; i < results.size(); i++) {
                GetSetValueResult result = results.get(i);
                int requestId = result.getRequestId();
                AsyncPropertyRequestInfo<RequestType, CallbackType> requestInfo;
                synchronized (mLock) {
                    requestInfo =
                            (AsyncPropertyRequestInfo<RequestType, CallbackType>)
                            mRequestIdToAsyncRequestInfo.get(requestId);
                    mRequestIdToAsyncRequestInfo.remove(requestId);
                }
                if (requestInfo == null) {
                    Log.w(TAG, "onResults: Request ID: " + requestId
                            + " might have been completed, cancelled or an exception might have "
                            + "been thrown");
                    continue;
                }
                Executor callbackExecutor = requestInfo.getCallbackExecutor();
                CallbackType clientCallback = requestInfo.getCallback();
                @CarPropertyAsyncErrorCode int errorCode = result.getErrorCode();
                int propertyId = requestInfo.getRequest().getPropertyId();
                String propertyName = VehiclePropertyIds.toString(propertyId);
                int areaId = requestInfo.getRequest().getAreaId();
                if (errorCode == STATUS_OK) {
                    CarPropertyValue<?> carPropertyValue = result.getCarPropertyValue();
                    long timestampNanos;
                    if (carPropertyValue != null) {
                        // This is a get result.
                        int valuePropertyId = carPropertyValue.getPropertyId();
                        if (propertyId  != valuePropertyId) {
                            Log.e(TAG, "onResults: Request ID: " + requestId + " received get "
                                    + "property value result, but has mismatch property ID, "
                                    + " expect: " + propertyName + ", got: "
                                    + VehiclePropertyIds.toString(valuePropertyId));
                        }
                        int valueAreaId = carPropertyValue.getAreaId();
                        if (areaId  != valueAreaId) {
                            Log.e(TAG, "onResults: Property: " + propertyName + " Request ID: "
                                    + requestId + " received get property value result, but has "
                                    + "mismatch area ID, expect: " + areaId + ", got: "
                                    + valueAreaId);
                        }
                        timestampNanos = carPropertyValue.getTimestamp();
                    } else {
                        // This is a set result.
                        timestampNanos = result.getUpdateTimestampNanos();
                    }

                    ResultType clientResult = propertyResultCallback.build(
                            requestId, propertyId, areaId, timestampNanos,
                            carPropertyValue == null ? null : carPropertyValue.getValue());
                    callbackExecutor.execute(() -> propertyResultCallback.onSuccess(
                            clientCallback, clientResult));
                } else {
                    callbackExecutor.execute(() -> propertyResultCallback.onFailure(clientCallback,
                            new PropertyAsyncError(requestId, propertyId, areaId, errorCode,
                                    result.getVendorErrorCode())));
                }
            }
        }
    }

    /**
     * A class to store async get/set property request info.
     */
    private static final class AsyncPropertyRequestInfo<RequestType, CallbackType> {
        private final RequestType mRequest;
        private final Executor mCallbackExecutor;
        private final CallbackType mCallback;

        public RequestType getRequest() {
            return mRequest;
        }

        public Executor getCallbackExecutor() {
            return mCallbackExecutor;
        }

        public CallbackType getCallback() {
            return mCallback;
        }

        private AsyncPropertyRequestInfo(RequestType request, Executor callbackExecutor,
                CallbackType callback) {
            mRequest = request;
            mCallbackExecutor = callbackExecutor;
            mCallback = callback;
        }
    }

    /** Read ONCHANGE sensors. */
    @AddedInOrBefore(majorVersion = 33)
    public static final float SENSOR_RATE_ONCHANGE = 0f;
    /** Read sensors at the rate of  1 hertz */
    @AddedInOrBefore(majorVersion = 33)
    public static final float SENSOR_RATE_NORMAL = 1f;
    /** Read sensors at the rate of 5 hertz */
    @AddedInOrBefore(majorVersion = 33)
    public static final float SENSOR_RATE_UI = 5f;
    /** Read sensors at the rate of 10 hertz */
    @AddedInOrBefore(majorVersion = 33)
    public static final float SENSOR_RATE_FAST = 10f;
    /** Read sensors at the rate of 100 hertz */
    @AddedInOrBefore(majorVersion = 33)
    public static final float SENSOR_RATE_FASTEST = 100f;



    /**
     * Status to indicate that set operation failed. Try it again.
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final int CAR_SET_PROPERTY_ERROR_CODE_TRY_AGAIN = 1;

    /**
     * Status to indicate that set operation failed because of an invalid argument.
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final int CAR_SET_PROPERTY_ERROR_CODE_INVALID_ARG = 2;

    /**
     * Status to indicate that set operation failed because the property is not available.
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final int CAR_SET_PROPERTY_ERROR_CODE_PROPERTY_NOT_AVAILABLE = 3;

    /**
     * Status to indicate that set operation failed because car denied access to the property.
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final int CAR_SET_PROPERTY_ERROR_CODE_ACCESS_DENIED = 4;

    /**
     * Status to indicate that set operation failed because of a general error in cars.
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final int CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN = 5;

    /** @hide */
    @IntDef(prefix = {"CAR_SET_PROPERTY_ERROR_CODE_"}, value = {
            CAR_SET_PROPERTY_ERROR_CODE_TRY_AGAIN,
            CAR_SET_PROPERTY_ERROR_CODE_INVALID_ARG,
            CAR_SET_PROPERTY_ERROR_CODE_PROPERTY_NOT_AVAILABLE,
            CAR_SET_PROPERTY_ERROR_CODE_ACCESS_DENIED,
            CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CarSetPropertyErrorCode {}

    /**
     * Error indicating that there is an error detected in cars.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int STATUS_ERROR_INTERNAL_ERROR = 1;
    /**
     * Error indicating that the property is temporarily not available.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int STATUS_ERROR_NOT_AVAILABLE = 2;
    /**
     * Error indicating the operation has timed-out.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public static final int STATUS_ERROR_TIMEOUT = 3;

    /** @hide */
    @IntDef(prefix = {"STATUS_"}, value = {
            STATUS_OK,
            STATUS_ERROR_INTERNAL_ERROR,
            STATUS_ERROR_NOT_AVAILABLE,
            STATUS_ERROR_TIMEOUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CarPropertyAsyncErrorCode {}

    /**
     * Get an instance of the CarPropertyManager.
     *
     * Should not be obtained directly by clients, use {@link Car#getCarManager(String)} instead.
     *
     * @param car the Car instance
     * @param service the ICarProperty instance
     * @hide
     */
    public CarPropertyManager(Car car, @NonNull ICarProperty service) {
        super(car);
        mService = service;
        mAppTargetSdk = getContext().getApplicationInfo().targetSdkVersion;

        Handler eventHandler = getEventHandler();
        if (eventHandler == null) {
            mHandler = null;
            return;
        }
        mHandler = new SingleMessageHandler<CarPropertyEvent>(eventHandler.getLooper(),
                MSG_GENERIC_EVENT) {
            @Override
            protected void handleEvent(CarPropertyEvent carPropertyEvent) {
                CarPropertyEventCallbackController carPropertyEventCallbackController;
                synchronized (mLock) {
                    carPropertyEventCallbackController =
                            mPropertyIdToCarPropertyEventCallbackController.get(
                                    carPropertyEvent.getCarPropertyValue().getPropertyId());
                }
                if (carPropertyEventCallbackController == null) {
                    return;
                }
                switch (carPropertyEvent.getEventType()) {
                    case CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE:
                        carPropertyEventCallbackController.forwardPropertyChanged(carPropertyEvent);
                        break;
                    case CarPropertyEvent.PROPERTY_EVENT_ERROR:
                        carPropertyEventCallbackController.forwardErrorEvent(carPropertyEvent);
                        break;
                    default:
                        throw new IllegalArgumentException();
                }
            }
        };
    }

    /**
     * Register {@link CarPropertyEventCallback} to get property updates. Multiple callbacks
     * can be registered for a single property or the same callback can be used for different
     * properties. If the same callback is registered again for the same property, it will be
     * updated to new {@code updateRateHz}.
     *
     * <p>Rate could be one of the following:
     * <ul>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_ONCHANGE}</li>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_NORMAL}</li>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_UI}</li>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_FAST}</li>
     *   <li>{@link CarPropertyManager#SENSOR_RATE_FASTEST}</li>
     * </ul>
     *
     * <p>
     * <b>Note:</b>Rate has no effect if the property has one of the following change modes:
     * <ul>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_CHANGE_MODE_STATIC}</li>
     *   <li>{@link CarPropertyConfig#VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE}</li>
     * </ul>
     *
     * <p>
     * <b>Note:</b> If listener registers a callback for updates for a property for the first time,
     * it will receive the property's current value via a change event upon registration if the
     * property is currently available for reading.
     *
     * <p>For properties that might be unavailable for reading because their power state is off,
     * property change events containing the property's initial value will be generated once their
     * power state is on.
     *
     * <p>If {@code updateRateHz} is higher than {@link CarPropertyConfig#getMaxSampleRate()}, it
     * will be registered with max sample {@code updateRateHz}.
     *
     * <p>If {@code updateRateHz} is lower than {@link CarPropertyConfig#getMinSampleRate()}, it
     * will be registered with min sample {@code updateRateHz}.
     *
     * <p>
     * <b>Note:</b> A property change event will only happen when the property is available. Caller
     * must never depend on the change event to check property's availability. For properties that
     * might be unavailable because they depend on certain power state, caller should subscribe
     * to the power state property (e.g. {@link VehiclePropertyIds#HVAC_POWER_ON} for hvac dependant
     * properties) to decide this property's availability.
     *
     * @param carPropertyEventCallback the CarPropertyEventCallback to be registered
     * @param propertyId               the property ID to subscribe
     * @param updateRateHz             how fast the property events are delivered in Hz
     * @return {@code true} if the listener is successfully registered
     * @throws SecurityException if missing the appropriate permission.
     */
    @AddedInOrBefore(majorVersion = 33)
    @SuppressWarnings("FormatString")
    public boolean registerCallback(@NonNull CarPropertyEventCallback carPropertyEventCallback,
            int propertyId, @FloatRange(from = 0.0, to = 100.0) float updateRateHz) {
        if (DBG) {
            Log.d(TAG, String.format("registerCallback, callback: %s propertyId: %s, "
                    + "updateRateHz: %f", carPropertyEventCallback,
                    VehiclePropertyIds.toString(propertyId), updateRateHz));
        }
        requireNonNull(carPropertyEventCallback);
        CarPropertyConfig<?> carPropertyConfig = getCarPropertyConfig(propertyId);
        if (carPropertyConfig == null) {
            Log.e(TAG, "registerCallback:  propertyId is not in carPropertyConfig list:  "
                    + VehiclePropertyIds.toString(propertyId));
            return false;
        }

        float sanitizedUpdateRateHz = InputSanitizationUtils.sanitizeUpdateRateHz(carPropertyConfig,
                updateRateHz);

        boolean registerSuccessful;
        synchronized (mLock) {
            boolean isNewInstance = false;
            CarPropertyEventCallbackController carPropertyEventCallbackController =
                    mPropertyIdToCarPropertyEventCallbackController.get(propertyId);
            if (carPropertyEventCallbackController == null) {
                carPropertyEventCallbackController = new CarPropertyEventCallbackController(
                        propertyId, mLock, mRegistrationUpdateCallback);
                isNewInstance = true;
            }

            registerSuccessful = carPropertyEventCallbackController.add(carPropertyEventCallback,
                    sanitizedUpdateRateHz);
            if (registerSuccessful && isNewInstance) {
                mPropertyIdToCarPropertyEventCallbackController.put(propertyId,
                        carPropertyEventCallbackController);
            }
        }
        return registerSuccessful;
    }

    private static class CarPropertyEventListenerToService extends ICarPropertyEventListener.Stub {
        private final WeakReference<CarPropertyManager> mCarPropertyManager;

        CarPropertyEventListenerToService(CarPropertyManager carPropertyManager) {
            mCarPropertyManager = new WeakReference<>(carPropertyManager);
        }

        @Override
        public void onEvent(List<CarPropertyEvent> carPropertyEvents) throws RemoteException {
            CarPropertyManager carPropertyManager = mCarPropertyManager.get();
            if (carPropertyManager != null) {
                carPropertyManager.handleEvents(carPropertyEvents);
            }
        }
    }

    private void handleEvents(List<CarPropertyEvent> carPropertyEvents) {
        if (mHandler != null) {
            mHandler.sendEvents(carPropertyEvents);
        }
    }

    /**
     * Stop getting property updates for the given {@link CarPropertyEventCallback}. If there are
     * multiple registrations for this {@link CarPropertyEventCallback}, all listening will be
     * stopped.
     *
     * @throws SecurityException if missing the appropriate permission.
     */
    @AddedInOrBefore(majorVersion = 33)
    public void unregisterCallback(@NonNull CarPropertyEventCallback carPropertyEventCallback) {
        if (DBG) {
            Log.d(TAG, "unregisterCallback, callback: " + carPropertyEventCallback);
        }
        requireNonNull(carPropertyEventCallback);
        int[] propertyIds;
        synchronized (mLock) {
            propertyIds = new int[mPropertyIdToCarPropertyEventCallbackController.size()];
            for (int i = 0; i < mPropertyIdToCarPropertyEventCallbackController.size(); i++) {
                propertyIds[i] = mPropertyIdToCarPropertyEventCallbackController.keyAt(i);
            }
        }
        for (int propertyId : propertyIds) {
            unregisterCallback(carPropertyEventCallback, propertyId);
        }
    }

    /**
     * Stop getting update for {@code propertyId} to the given {@link CarPropertyEventCallback}. If
     * the same {@link CarPropertyEventCallback} is used for other properties, those subscriptions
     * will not be affected.
     *
     * @throws SecurityException if missing the appropriate permission.
     */
    @AddedInOrBefore(majorVersion = 33)
    @SuppressWarnings("FormatString")
    public void unregisterCallback(@NonNull CarPropertyEventCallback carPropertyEventCallback,
            int propertyId) {
        if (DBG) {
            Log.d(TAG, String.format("unregisterCallback, callback: %s, property Id: %s",
                    carPropertyEventCallback, VehiclePropertyIds.toString(propertyId)));
        }
        requireNonNull(carPropertyEventCallback);
        if (!CarPropertyHelper.isSupported(propertyId)) {
            Log.e(TAG, "unregisterCallback: propertyId: "
                    + VehiclePropertyIds.toString(propertyId) + " is not supported");
            return;
        }
        synchronized (mLock) {
            CarPropertyEventCallbackController carPropertyEventCallbackController =
                    mPropertyIdToCarPropertyEventCallbackController.get(propertyId);

            if (carPropertyEventCallbackController == null) {
                return;
            }

            boolean allCallbacksRemoved = carPropertyEventCallbackController.remove(
                    carPropertyEventCallback);
            if (allCallbacksRemoved) {
                mPropertyIdToCarPropertyEventCallbackController.remove(propertyId);
            }
        }
    }

    /**
     * @return the list of properties supported by this car that the application may access
     */
    @NonNull
    @AddedInOrBefore(majorVersion = 33)
    public List<CarPropertyConfig> getPropertyList() {
        if (DBG) {
            Log.d(TAG, "getPropertyList");
        }
        List<CarPropertyConfig> configs;
        try {
            configs = mService.getPropertyList().getConfigs();
        } catch (RemoteException e) {
            Log.e(TAG, "getPropertyList exception ", e);
            return handleRemoteExceptionFromCarService(e, new ArrayList<>());
        }
        if (DBG) {
            Log.d(TAG, "getPropertyList returns " + configs.size() + " configs");
            for (int i = 0; i < configs.size(); i++) {
                Log.v(TAG, i + ": " + configs.get(i));
            }
        }
        return configs;
    }

    /**
     * Checks the given property IDs and returns a list of property configs supported by the car.
     *
     * If some of the properties in the given ID list are not supported, they will not be returned.
     *
     * @param propertyIds the list of property IDs
     * @return the list of property configs
     */
    @NonNull
    @AddedInOrBefore(majorVersion = 33)
    public List<CarPropertyConfig> getPropertyList(@NonNull ArraySet<Integer> propertyIds) {
        if (DBG) {
            Log.d(TAG, "getPropertyList(" + CarPropertyHelper.propertyIdsToString(propertyIds)
                    + ")");
        }
        List<Integer> filteredPropertyIds = new ArrayList<>();
        for (int propertyId : propertyIds) {
            if (!CarPropertyHelper.isSupported(propertyId)) {
                continue;
            }
            filteredPropertyIds.add(propertyId);
        }
        int[] filteredPropertyIdsArray = new int[filteredPropertyIds.size()];
        for (int i = 0; i < filteredPropertyIds.size(); i++) {
            filteredPropertyIdsArray[i] = filteredPropertyIds.get(i);
        }
        try {
            List<CarPropertyConfig> configs = mService.getPropertyConfigList(
                    filteredPropertyIdsArray).getConfigs();
            if (DBG) {
                Log.d(TAG, "getPropertyList(" + CarPropertyHelper.propertyIdsToString(propertyIds)
                        + ") returns " + configs.size() + " configs");
                for (int i = 0; i < configs.size(); i++) {
                    Log.v(TAG, i + ": " + configs.get(i));
                }
            }
            return configs;
        } catch (RemoteException e) {
            Log.e(TAG, "getPropertyList exception ", e);
            return handleRemoteExceptionFromCarService(e, new ArrayList<>());
        }
    }

    /**
     * Get {@link CarPropertyConfig} by property ID.
     *
     * @param propertyId the property ID
     * @return the {@link CarPropertyConfig} for the selected property, {@code null} if the property
     * is not available
     */
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public CarPropertyConfig<?> getCarPropertyConfig(int propertyId) {
        if (DBG) {
            Log.d(TAG, "getCarPropertyConfig(" + VehiclePropertyIds.toString(propertyId) + ")");
        }
        if (!CarPropertyHelper.isSupported(propertyId)) {
            Log.w(TAG, "Property: " + VehiclePropertyIds.toString(propertyId)
                    + " is not supported");
            return null;
        }
        List<CarPropertyConfig> configs;
        try {
            configs = mService.getPropertyConfigList(new int[] {propertyId}).getConfigs();
        } catch (RemoteException e) {
            Log.e(TAG, "getPropertyList exception ", e);
            return handleRemoteExceptionFromCarService(e, null);
        }
        CarPropertyConfig<?> config = configs.size() == 0 ? null : configs.get(0);
        if (DBG) {
            Log.d(TAG, "getCarPropertyConfig(" + VehiclePropertyIds.toString(propertyId)
                    + ") returns " + config);
        }
        return config;
    }

    /**
     * Returns areaId contains the selected area for the property.
     *
     * @param propertyId the property ID
     * @param area the area enum such as Enums in {@link android.car.VehicleAreaSeat}
     * @throws IllegalArgumentException if the property is not available in the vehicle for
     * the selected area
     * @return the {@code AreaId} containing the selected area for the property
     */
    @AddedInOrBefore(majorVersion = 33)
    public int getAreaId(int propertyId, int area) {
        String propertyIdStr = VehiclePropertyIds.toString(propertyId);
        if (DBG) {
            Log.d(TAG, "getAreaId(propertyId = " + propertyIdStr + ", area = " + area + ")");
        }
        CarPropertyConfig<?> propConfig = getCarPropertyConfig(propertyId);
        if (propConfig == null) {
            throw new IllegalArgumentException("The propertyId: " + propertyIdStr
                    + " is not available");
        }
        // For the global property, areaId is 0
        if (propConfig.isGlobalProperty()) {
            if (DBG) {
                Log.d(TAG, "getAreaId returns the global area ID (0)");
            }
            return 0;
        }
        for (int areaId : propConfig.getAreaIds()) {
            if ((area & areaId) == area) {
                if (DBG) {
                    Log.d(TAG, "getAreaId returns " + areaId);
                }
                return areaId;
            }
        }

        throw new IllegalArgumentException("The propertyId: " + propertyIdStr
                + " is not available at the area: 0x" + toHexString(area));
    }

    /**
     * Return read permission string for given property ID.
     *
     * @param propId the property ID to query
     * @return the permission needed to read this property, {@code null} if the property ID is not
     * available
     *
     * @hide
     */
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public String getReadPermission(int propId) {
        try {
            String permission = mService.getReadPermission(propId);
            if (DBG) {
                Log.d(TAG, "getReadPermission(propId =" + VehiclePropertyIds.toString(propId)
                        + ") returns " + permission);
            }
            return permission;
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, "");
        }
    }

    /**
     * Return write permission string for given property ID.
     *
     * @param propId the property ID to query
     * @return the permission needed to write this property, {@code null} if the property ID is not
     * available.
     *
     * @hide
     */
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public String getWritePermission(int propId) {
        try {
            String permission = mService.getWritePermission(propId);
            if (DBG) {
                Log.d(TAG, "getWritePermission(propId = " + VehiclePropertyIds.toString(propId)
                        + ") returns " + permission);
            }
            return permission;
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, "");
        }
    }


    /**
     * Check whether a given property is available or disabled based on the car's current state.
     *
     * @param propertyId the property ID
     * @param areaId the area ID
     * @return {@code true} if {@link CarPropertyValue#STATUS_AVAILABLE}, {@code false} otherwise
     * (eg {@link CarPropertyValue#STATUS_UNAVAILABLE})
     */
    @AddedInOrBefore(majorVersion = 33)
    public boolean isPropertyAvailable(int propertyId, int areaId) {
        if (DBG) {
            Log.d(TAG, "isPropertyAvailable(propertyId = "
                    + VehiclePropertyIds.toString(propertyId) + ", areaId = " + areaId + ")");
        }
        if (!CarPropertyHelper.isSupported(propertyId)) {
            if (DBG) {
                Log.d(TAG, "Property: " + VehiclePropertyIds.toString(propertyId)
                        + " is not supported");
            }
            return false;
        }

        try {
            CarPropertyValue propValue = runSyncOperation(() -> {
                if (DBG) {
                    Log.d(TAG, "calling getProperty to check property's availability");
                }
                return mService.getProperty(propertyId, areaId);
            });
            return propValue != null;
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, false);
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "unable to get property, error: " + e);
            return false;
        }
    }

    /**
     * Returns value of a bool property
     *
     * <p>This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion} equal
     * or later than {@link Build.VERSION_CODES#R} will receive the following exceptions when
     * request is failed.
     * <ul>
     *     <li>{@link CarInternalErrorException}
     *     <li>{@link PropertyAccessDeniedSecurityException}
     *     <li>{@link PropertyNotAvailableAndRetryException}
     *     <li>{@link PropertyNotAvailableException}
     *     <li>{@link IllegalArgumentException}
     * </ul>
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion}
     * earlier than {@link Build.VERSION_CODES#R} will receive the following exceptions if the call
     * fails.
     * <ul>
     *     <li>{@link IllegalStateException} when there is an error detected in cars, or when
     *         cars denied the access of the property, or when the property is not available and
     *         might be unavailable for a while, or when unexpected error happens.
     *     <li>{@link IllegalArgumentException} when the [propertyId, areaId] is not supported.
     * </ul>
     *
     * <p>For pre-R client, the returned value is {@code false} if the property is temporarily not
     * available.
     *
     * @param propertyId the property ID to get
     * @param areaId the area ID of the property to get
     *
     * @throws CarInternalErrorException when there is an unexpected error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException when the property is not available and might be
     * unavailable for a while.
     * @throws IllegalArgumentException when the [propertyId, areaId] is not supported.
     *
     * @return the value of a bool property, or {@code false} for pre-R client if the property is
     *         temporarily not available
     */
    @AddedInOrBefore(majorVersion = 33)
    public boolean getBooleanProperty(int propertyId, int areaId) {
        CarPropertyValue<Boolean> carProp = getProperty(Boolean.class, propertyId, areaId);
        return carProp != null ? carProp.getValue() : false;
    }

    /**
     * Returns value of a float property
     *
     * <p>This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p>This method has the same exception behavior as {@link #getBooleanProperty(int, int)}.
     *
     * @param propertyId the property ID to get
     * @param areaId the area ID of the property to get
     *
     * @throws CarInternalErrorException when there is an unexpected error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException when the property is not available and might be
     * unavailable for a while.
     * @throws IllegalArgumentException when the [propertyId, areaId] is not supported.
     *
     * @return the value of a float property, or 0 if client is pre-R and the property is
     *         temporarily not available
     */
    @AddedInOrBefore(majorVersion = 33)
    public float getFloatProperty(int propertyId, int areaId) {
        CarPropertyValue<Float> carProp = getProperty(Float.class, propertyId, areaId);
        return carProp != null ? carProp.getValue() : 0f;
    }

    /**
     * Returns value of an integer property
     *
     * <p>This method may take couple seconds to complete, so it needs to be called form a
     * non-main thread.
     *
     * <p>This method has the same exception behavior as {@link #getBooleanProperty(int, int)}.
     *
     * @param propertyId the property ID to get
     * @param areaId the area ID of the property to get
     *
     * @throws CarInternalErrorException when there is an unexpected error detected in cars
     * @throws PropertyAccessDeniedSecurityException} when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException} when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException when the property is not available and might be
     * unavailable for a while.
     * @throws IllegalArgumentException when the [propertyId, areaId] is not supported.
     *
     * @return the value of a integer property, or 0 if client is pre-R and the property is
     *         temporarily not available
     */
    @AddedInOrBefore(majorVersion = 33)
    public int getIntProperty(int propertyId, int areaId) {
        CarPropertyValue<Integer> carProp = getProperty(Integer.class, propertyId, areaId);
        return carProp != null ? carProp.getValue() : 0;
    }

    /**
     * Returns value of an integer array property
     *
     * <p>This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p>This method has the same exception behavior as {@link #getBooleanProperty(int, int)}.
     *
     * @param propertyId the property ID to get
     * @param areaId the area ID of the property to get
     *
     * @throws CarInternalErrorException when there is an unexpected error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException} when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException} when the property is not available and might be
     * unavailable for a while.
     * @throws IllegalArgumentException} when the [propertyId, areaId] is not supported.
     *
     * @return the value of a integer array property, or an empty integer array if client is pre-R
     *         and the property is temporarily not available
     */
    @NonNull
    @AddedInOrBefore(majorVersion = 33)
    public int[] getIntArrayProperty(int propertyId, int areaId) {
        CarPropertyValue<Integer[]> carProp = getProperty(Integer[].class, propertyId, areaId);
        return carProp != null ? toIntArray(carProp.getValue()) : new int[0];
    }

    private static int[] toIntArray(Integer[] input) {
        int len = input.length;
        int[] arr = new int[len];
        for (int i = 0; i < len; i++) {
            arr[i] = input[i];
        }
        return arr;
    }

    @FunctionalInterface
    private interface RemoteCallable<V> {
        V call() throws RemoteException;
    }

    @Nullable
    private static <V> V runSyncOperation(RemoteCallable<V> c)
            throws RemoteException, ServiceSpecificException {
        int retryCount = 0;
        while (retryCount < SYNC_OP_RETRY_MAX_COUNT) {
            retryCount++;
            try {
                return c.call();
            } catch (ServiceSpecificException e) {
                if (e.errorCode != SYNC_OP_LIMIT_TRY_AGAIN) {
                    throw e;
                }
                // If car service don't have enough binder thread to handle this request. Sleep for
                // 10ms and try again.
                Log.d(TAG, "too many sync request, sleeping for " + SYNC_OP_RETRY_SLEEP_IN_MS
                        + " ms before retry");
                SystemClock.sleep(SYNC_OP_RETRY_SLEEP_IN_MS);
                continue;
            } catch (RemoteException e) {
                throw e;
            }
        }
        throw new ServiceSpecificException(VehicleHalStatusCode.STATUS_INTERNAL_ERROR,
                "failed to call car service sync operations after " + retryCount + " retries");
    }

    /**
     * Return {@link CarPropertyValue}
     *
     * <p>This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion} equal
     * or later than {@link Build.VERSION_CODES#R} will receive the following exceptions when
     * request is failed.
     * <ul>
     *     <li>{@link CarInternalErrorException}
     *     <li>{@link PropertyAccessDeniedSecurityException}
     *     <li>{@link PropertyNotAvailableAndRetryException}
     *     <li>{@link PropertyNotAvailableException}
     *     <li>{@link IllegalArgumentException}
     * </ul>
     *
     * <p>For R or later version client, the returned value will never be null.
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion}
     * earlier than {@link Build.VERSION_CODES#R} will receive the following exceptions when request
     * is failed.
     * <ul>
     *     <li>{@link IllegalStateException} when there is an error detected in cars, or when
     *         cars denied the access of the property, or when the property is not available and
     *         might be unavailable for a while, or when unexpected error happens.
     *     <li>{@link IllegalArgumentException} when the [propertyId, areaId] is not supported.
     * </ul>
     *
     * <p>For pre-R client, the returned value might be null if the property is temporarily not
     * available. The client should try again in this case.
     *
     * @param clazz the class object for the CarPropertyValue
     * @param propertyId the property ID to get
     * @param areaId the area ID of the property to get
     *
     * @throws CarInternalErrorException when there is an unexpected error detected in cars
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily
     * not available and likely that retrying will be successful
     * @throws PropertyNotAvailableException when the property is not available and might be
     * unavailable for a while.
     * @throws IllegalArgumentException when the [propertyId, areaId] is not supported.
     *
     * @return the value of a property
     */
    @SuppressWarnings("unchecked")
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public <E> CarPropertyValue<E> getProperty(@NonNull Class<E> clazz, int propertyId,
            int areaId) {
        CarPropertyValue<E> carPropertyValue = getProperty(propertyId, areaId);
        if (carPropertyValue == null) {
            return null;
        }
        Class<?> actualClass = carPropertyValue.getValue().getClass();
        if (actualClass != clazz) {
            throw new IllegalArgumentException(
                    "Invalid property type. " + "Expected: " + clazz + ", but was: "
                            + actualClass);
        }
        return carPropertyValue;
    }

    /**
     * Query {@link CarPropertyValue} with property id and areaId.
     *
     * <p>This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion} equal
     * or later than {@link Build.VERSION_CODES#R} will receive the following exceptions when
     * request is failed.
     * <ul>
     *     <li>{@link CarInternalErrorException}
     *     <li>{@link PropertyAccessDeniedSecurityException}
     *     <li>{@link PropertyNotAvailableAndRetryException}
     *     <li>{@link PropertyNotAvailableException}
     *     <li>{@link IllegalArgumentException}
     * </ul>
     *
     * <p>For R or later version client, the returned value will never be null.
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion}
     * earlier than {@link Build.VERSION_CODES#R} will receive the following exceptions when request
     * is failed.
     * <ul>
     *     <li>{@link IllegalStateException} when there is an error detected in cars, or when
     *         cars denied the access of the property, or when the property is not available and
     *         might be unavailable for a while, or when unexpected error happens.
     *     <li>{@link IllegalArgumentException} when the [propertyId, areaId] is not supported.
     * </ul>
     *
     * <p>For pre-R client, the returned value might be null if the property is temporarily not
     * available. The client should try again in this case.
     *
     * @param propertyId the property ID to get
     * @param areaId the area ID of the property to get
     * @param <E> the class type of the property
     *
     * @throws CarInternalErrorException when there is an unexpected error detected in cars.
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the
     * property.
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily
     * not available and likely that retrying will be successful.
     * @throws PropertyNotAvailableException when the property is not available and might be
     * unavailable for a while.
     * @throws IllegalArgumentException when the [propertyId, areaId] is not supported.
     *
     * @return the value of a property
     */
    @Nullable
    @AddedInOrBefore(majorVersion = 33)
    public <E> CarPropertyValue<E> getProperty(int propertyId, int areaId) {
        if (DBG) {
            Log.d(TAG, "getProperty, propertyId: " + VehiclePropertyIds.toString(propertyId)
                    + ", areaId: 0x" + toHexString(areaId));
        }

        assertPropertyIdIsSupported(propertyId);

        Trace.beginSection("getProperty-" + propertyId + "/" + areaId);
        try {
            return (CarPropertyValue<E>) (runSyncOperation(() -> {
                return mService.getProperty(propertyId, areaId);
            }));
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, null);
        } catch (ServiceSpecificException e) {
            if (DBG) {
                Log.d(TAG, "getProperty received service specific exception, code: " + e.errorCode);
            }
            if (mAppTargetSdk < Build.VERSION_CODES.R) {
                if (e.errorCode == VehicleHalStatusCode.STATUS_TRY_AGAIN) {
                    return null;
                } else {
                    throw new IllegalStateException("Failed to get propertyId: "
                            + VehiclePropertyIds.toString(propertyId) + " areaId: 0x"
                            + toHexString(areaId), e);
                }
            }
            handleCarServiceSpecificException(e, propertyId, areaId);

            // Never reaches here.
            return null;
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Set value of car property by areaId.
     *
     * <p>If multiple clients set a property for the same area ID simultaneously, which one takes
     * precedence is undefined. Typically, the last set operation (in the order that they are issued
     * to the car's ECU) overrides the previous set operations.
     *
     * <p>This method may take couple seconds to complete, so it needs to be called form a
     * non-main thread.
     *
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion} equal
     * or later than {@link Build.VERSION_CODES#R} will receive the following exceptions when
     * request is failed.
     * <ul>
     *     <li>{@link CarInternalErrorException}
     *     <li>{@link PropertyAccessDeniedSecurityException}
     *     <li>{@link PropertyNotAvailableAndRetryException}
     *     <li>{@link PropertyNotAvailableException}
     *     <li>{@link IllegalArgumentException}
     * </ul>
     * <p>Clients that declare a {@link android.content.pm.ApplicationInfo#targetSdkVersion}
     * earlier than {@link Build.VERSION_CODES#R} will receive the following exceptions when request
     * is failed.
     * <ul>
     *     <li>{@link RuntimeException} when the property is temporarily not available.
     *     <li>{@link IllegalStateException} when there is an error detected in cars, or when
     *         cars denied the access of the property, or when the property is not available and
     *         might be unavailable for a while, or when unexpected error happens.
     *     <li>{@link IllegalArgumentException} when the [propertyId, areaId] is not supported.
     * </ul>
     *
     * @param clazz the class object for the CarPropertyValue
     * @param propertyId the property ID to modify
     * @param areaId the area ID to apply the modification
     * @param val the value to set
     * @param <E> the class type of the given property, for example property that was
     * defined as {@code VEHICLE_VALUE_TYPE_INT32} in vehicle HAL could be accessed using
     * {@code Integer.class}.
     *
     * @throws CarInternalErrorException when there is an unexpected error detected in cars.
     * @throws PropertyAccessDeniedSecurityException when cars denied the access of the property.
     * @throws PropertyNotAvailableException when the property is not available and might be
     * unavailable for a while.
     * @throws PropertyNotAvailableAndRetryException when the property is temporarily not available
     * and likely that retrying will be successful.
     * @throws IllegalArgumentException when the [propertyId, areaId] is not supported.
     */
    @AddedInOrBefore(majorVersion = 33)
    public <E> void setProperty(@NonNull Class<E> clazz, int propertyId, int areaId,
            @NonNull E val) {
        if (DBG) {
            Log.d(TAG, "setProperty, propertyId: " + VehiclePropertyIds.toString(propertyId)
                    + ", areaId: 0x" + toHexString(areaId) + ", class: " + clazz + ", val: " + val);
        }

        assertPropertyIdIsSupported(propertyId);

        Trace.beginSection("setProperty-" + propertyId + "/" + areaId);
        try {
            runSyncOperation(() -> {
                mService.setProperty(new CarPropertyValue<>(propertyId, areaId, val),
                        mCarPropertyEventToService);
                return null;
            });
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
            return;
        } catch (ServiceSpecificException e) {
            if (DBG) {
                Log.d(TAG, "setProperty received service specific exception", e);
            }
            if (mAppTargetSdk < Build.VERSION_CODES.R) {
                if (e.errorCode == VehicleHalStatusCode.STATUS_TRY_AGAIN) {
                    throw new RuntimeException("Failed to set propertyId: "
                            + VehiclePropertyIds.toString(propertyId) + " areaId: 0x"
                            + toHexString(areaId), e);
                } else {
                    throw new IllegalStateException("Failed to set propertyId: "
                            + VehiclePropertyIds.toString(propertyId) + " areaId: 0x"
                            + toHexString(areaId), e);
                }
            }
            handleCarServiceSpecificException(e, propertyId, areaId);
            return;
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Modifies a property.  If the property modification doesn't occur, an error event shall be
     * generated and propagated back to the application.
     *
     * <p>This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * @param propertyId the property ID to modify
     * @param areaId the area ID to apply the modification
     * @param val the value to set
     */
    @AddedInOrBefore(majorVersion = 33)
    public void setBooleanProperty(int propertyId, int areaId, boolean val) {
        setProperty(Boolean.class, propertyId, areaId, val);
    }

    /**
     * Set float value of property
     *
     * <p>This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * @param propertyId the property ID to modify
     * @param areaId the area ID to apply the modification
     * @param val the value to set
     */
    @AddedInOrBefore(majorVersion = 33)
    public void setFloatProperty(int propertyId, int areaId, float val) {
        setProperty(Float.class, propertyId, areaId, val);
    }

    /**
     * Set int value of property
     *
     * <p>This method may take couple seconds to complete, so it needs to be called from a
     * non-main thread.
     *
     * @param propertyId the property ID to modify
     * @param areaId the area ID to apply the modification
     * @param val the value to set
     */
    @AddedInOrBefore(majorVersion = 33)
    public void setIntProperty(int propertyId, int areaId, int val) {
        setProperty(Integer.class, propertyId, areaId, val);
    }

    /**
     *  Handles {@code ServiceSpecificException} in {@code CarService} for R and later version.
     */
    private void handleCarServiceSpecificException(
            ServiceSpecificException e, int propertyId, int areaId) {
        // We are not passing the error message down, so log it here.
        Log.w(TAG, "received ServiceSpecificException: " + e);
        int errorCode = CarPropertyHelper.getVhalSystemErrorCode(e.errorCode);
        int vendorErrorCode = CarPropertyHelper.getVhalVendorErrorCode(e.errorCode);

        switch (errorCode) {
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE:
                throw new PropertyNotAvailableException(propertyId, areaId, vendorErrorCode);
            case VehicleHalStatusCode.STATUS_TRY_AGAIN:
                // Vendor error code is ignored for STATUS_TRY_AGAIN error
                throw new PropertyNotAvailableAndRetryException(propertyId, areaId);
            case VehicleHalStatusCode.STATUS_ACCESS_DENIED:
                // Vendor error code is ignored for STATUS_ACCESS_DENIED error
                throw new PropertyAccessDeniedSecurityException(propertyId, areaId);
            case VehicleHalStatusCode.STATUS_INTERNAL_ERROR:
                throw new CarInternalErrorException(propertyId, areaId, vendorErrorCode);
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED:
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW:
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH:
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_POOR_VISIBILITY:
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY:
                throw new PropertyNotAvailableException(propertyId, areaId,
                        getPropertyNotAvailableErrorCodeFromStatusCode(errorCode), vendorErrorCode);
            default:
                Log.e(TAG, "Invalid errorCode: " + errorCode + " in CarService");
                throw new CarInternalErrorException(propertyId, areaId);
        }
    }

    /**
     * Convert {@link VehicleHalStatusCode} into public {@link PropertyNotAvailableErrorCode}
     * equivalents.
     *
     * @throws IllegalArgumentException if an invalid status code is passed in.
     * @hide
     */
    private static int getPropertyNotAvailableErrorCodeFromStatusCode(int statusCode) {
        switch (statusCode) {
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE:
                return PropertyNotAvailableErrorCode.NOT_AVAILABLE;
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED:
                return PropertyNotAvailableErrorCode.NOT_AVAILABLE_DISABLED;
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW:
                return PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_LOW;
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH:
                return PropertyNotAvailableErrorCode.NOT_AVAILABLE_SPEED_HIGH;
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_POOR_VISIBILITY:
                return PropertyNotAvailableErrorCode.NOT_AVAILABLE_POOR_VISIBILITY;
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY:
                return PropertyNotAvailableErrorCode.NOT_AVAILABLE_SAFETY;
            default:
                throw new IllegalArgumentException("Invalid status code: " + statusCode);
        }
    }

    private void clearRequestIdToAsyncRequestInfo(
            List<? extends AsyncPropertyRequest> asyncPropertyRequests) {
        if (DBG) {
            Log.d(TAG, "clear pending async requests: " + asyncPropertyRequests);
        }
        synchronized (mLock) {
            for (int i = 0; i < asyncPropertyRequests.size(); i++) {
                mRequestIdToAsyncRequestInfo.remove(asyncPropertyRequests.get(i).getRequestId());
            }
        }
    }

    /**
     * Set an {@code onCancelListener} for the cancellation signal.
     *
     * <p>When the signal is cancelled, car service will remove the stored state for the specified
     * pending request IDs and ignore all the future results.
     */
    private void setOnCancelListener(CancellationSignal cancellationSignal,
            List<Integer> requestIds) {
        cancellationSignal.setOnCancelListener(() -> {
            int[] requestIdsArray = new int[requestIds.size()];
            synchronized (mLock) {
                for (int i = 0; i < requestIds.size(); i++) {
                    int requestId = requestIds.get(i);
                    requestIdsArray[i] = requestId;
                    mRequestIdToAsyncRequestInfo.remove(requestId);
                }
            }
            try {
                mService.cancelRequests(requestIdsArray);
            } catch (RemoteException e) {
                handleRemoteExceptionFromCarService(e);
            }
        });
    }

    /** @hide */
    @Override
    @AddedInOrBefore(majorVersion = 33)
    public void onCarDisconnected() {
        synchronized (mLock) {
            mPropertyIdToCarPropertyEventCallbackController.clear();
        }
    }

    /**
     * Generate unique get request ID and return to the client.
     *
     * @param propertyId the property ID
     * @param areaId the area ID
     * @return the GetPropertyRequest object
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @NonNull
    @SuppressWarnings("FormatString")
    public GetPropertyRequest generateGetPropertyRequest(int propertyId, int areaId) {
        int requestIdCounter = mRequestIdCounter.getAndIncrement();
        if (DBG) {
            Log.d(TAG, String.format("generateGetPropertyRequest, requestId: %d, propertyId: %s, "
                    + "areaId: %d", requestIdCounter, VehiclePropertyIds.toString(propertyId),
                    areaId));
        }
        return new GetPropertyRequest(requestIdCounter, propertyId, areaId);
    }

    /**
     * Generate unique set request ID and return to the client.
     *
     * @param <T> the type for the property value, must be one of Object, Boolean, Float, Integer,
     *      Long, Float[], Integer[], Long[], String, byte[], Object[]
     * @param propertyId the property ID
     * @param areaId the area ID
     * @param value the value to set
     * @return the {@link SetPropertyRequest} object
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @NonNull
    @SuppressWarnings("FormatString")
    public <T> SetPropertyRequest<T> generateSetPropertyRequest(int propertyId, int areaId,
            @NonNull T value) {
        requireNonNull(value);
        int requestIdCounter = mRequestIdCounter.getAndIncrement();
        if (DBG) {
            Log.d(TAG, String.format("generateSetPropertyRequest, requestId: %d, propertyId: %s, "
                    + "areaId: %d, value: %s", requestIdCounter,
                    VehiclePropertyIds.toString(propertyId), areaId, value));
        }
        return new SetPropertyRequest(requestIdCounter, propertyId, areaId, value);
    }

    private void checkAsyncArguments(Object requests, Object callback, long timeoutInMs) {
        requireNonNull(requests);
        requireNonNull(callback);
        if (timeoutInMs <= 0) {
            throw new IllegalArgumentException("timeoutInMs must be a positive number");
        }
    }

    /**
     * Query a list of {@link CarPropertyValue} with property ID and area ID asynchronously.
     *
     * <p>This function would return immediately before the results are ready. For each request,
     * the corresponding result would either be delivered through one
     * {@code resultCallback.onSuccess} call if the request succeeded or through one
     * {@code errorCallback.onFailure} call if failed. It is guaranteed that the total times the
     * callback functions are called is equal to the number of requests if this function does not
     * throw an exception. It is guaranteed that none of the callback functions are called if an
     * exception is thrown. If the {@code callbackExecutor} is {@code null}, the callback will be
     * executed on the default event handler thread. If the callback is doing heavy work, it is
     * recommended that the {@code callbackExecutor} is provided.
     *
     * <p>If the operation is cancelled, it is guaranteed that no more callbacks will be called.
     *
     * <p>For one request, if the property's status is not available,
     * {@code errorCallback.onFailure} will be called once with {@link #STATUS_ERROR_NOT_AVAILABLE}.
     *
     * <p>For one request, if the property's status is error,
     * {@code errorCallback.onFailure} will be called once with {@link
     * #STATUS_ERROR_INTERNAL_ERROR}.
     *
     * @param getPropertyRequests a list of properties to get
     * @param timeoutInMs the timeout for the operation, in milliseconds
     * @param cancellationSignal a signal that could be used to cancel the on-going operation
     * @param callbackExecutor the executor to execute the callback with
     * @param getPropertyCallback the callback function to deliver the result
     * @throws SecurityException if missing permission to read one of the specific properties.
     * @throws IllegalArgumentException if one of the properties to read is not supported.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public void getPropertiesAsync(
            @NonNull List<GetPropertyRequest> getPropertyRequests,
            long timeoutInMs,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable Executor callbackExecutor,
            @NonNull GetPropertyCallback getPropertyCallback) {
        if (DBG) {
            Log.d(TAG, "getPropertiesAsync, requests: " + getPropertyRequests + ", timeoutInMs: "
                    + timeoutInMs + ", callback: " + getPropertyCallback);
        }

        checkAsyncArguments(getPropertyRequests, getPropertyCallback, timeoutInMs);
        if (callbackExecutor == null) {
            callbackExecutor = new HandlerExecutor(getEventHandler());
        }

        List<AsyncPropertyServiceRequest> getPropertyServiceRequests = new ArrayList<>(
                getPropertyRequests.size());
        for (int i = 0; i < getPropertyRequests.size(); i++) {
            GetPropertyRequest getPropertyRequest = getPropertyRequests.get(i);
            int propertyId = getPropertyRequest.getPropertyId();
            int areaId = getPropertyRequest.getAreaId();
            assertPropertyIdIsSupported(propertyId);

            getPropertyServiceRequests.add(AsyncPropertyServiceRequest.newGetAsyncRequest(
                    getPropertyRequest));
        }

        List<Integer> requestIds = storePendingRequestInfo(getPropertyRequests, callbackExecutor,
                getPropertyCallback);

        try {
            if (DBG) {
                Log.d(TAG, "calling CarPropertyService.getPropertiesAsync");
            }
            mService.getPropertiesAsync(new AsyncPropertyServiceRequestList(
                    getPropertyServiceRequests), mAsyncPropertyResultCallback, timeoutInMs);
            if (DBG) {
                Log.d(TAG, "CarPropertyService.getPropertiesAsync succeed");
            }
        } catch (RemoteException e) {
            clearRequestIdToAsyncRequestInfo(getPropertyRequests);
            handleRemoteExceptionFromCarService(e);
        } catch (IllegalArgumentException | SecurityException e) {
            clearRequestIdToAsyncRequestInfo(getPropertyRequests);
            throw e;
        }
        if (cancellationSignal != null) {
            setOnCancelListener(cancellationSignal, requestIds);
        }
    }

    /**
     * Query a list of {@link CarPropertyValue} with property Id and area Id asynchronously.
     *
     * Same as {@link CarPropertyManager#getPropertiesAsync(List, long, CancellationSignal,
     * Executor, GetPropertyCallback)} with default timeout 10s.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public void getPropertiesAsync(
            @NonNull List<GetPropertyRequest> getPropertyRequests,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable Executor callbackExecutor,
            @NonNull GetPropertyCallback getPropertyCallback) {
        getPropertiesAsync(getPropertyRequests, ASYNC_GET_DEFAULT_TIMEOUT_MS, cancellationSignal,
                callbackExecutor, getPropertyCallback);
    }

    /**
     * Sets a list of car property values asynchronously.
     *
     * <p>This function would return immediately before the results are ready. For each request,
     * the corresponding result would either be delivered through one
     * {@code resultCallback.onSuccess} call if the request succeeded or through one
     * {@code errorCallback.onFailure} call if failed. It is guaranteed that the total times the
     * callback functions are called is equal to the number of requests if this function does not
     * throw an exception. It is guaranteed that none of the callback functions are called if an
     * exception is thrown. If the {@code callbackExecutor} is {@code null}, the callback will be
     * executed on the default event handler thread. If the callback is doing heavy work, it is
     * recommended that the {@code callbackExecutor} is provided.
     *
     * <p>If the operation is cancelled, it is guaranteed that no more callbacks will be called.
     *
     * <p>If multiple clients set a property for the same area ID simultaneously, which one takes
     * precedence is undefined. Typically, the last set operation (in the order that they are issued
     * to the car's ECU) overrides the previous set operations.
     *
     * <p>When the success callback will be called depends on whether {@code waitForPropertyUpdate}
     * for each request is set. If this is set to {@code true} (by default), the success callback
     * will be called when the set operation is successfully delivered to vehicle bus AND either
     * target value is the same as the current or when the property is updated to the target value.
     *
     * <p>When {@code waitForPropertyUpdate} is set to {@code false}, the success callback will be
     * called as long as the set operation is successfully delivered to vehicle bus.
     *
     * <p>Under most cases, client should wait for the property update to verify that the set
     * operation actually succeeded.
     *
     * <p>For cases when the property is write-only (no way to get property update event) or when
     * the property represents some action, instead of an actual state, e.g. key stroke where the
     * property's current value is not meaningful, caller must set {@code waitForPropertyUpdate}
     * to {@code false}.
     *
     * <p>For {@code HVAC_TEMPERATURE_VALUE_SUGGESTION}, this must be set to {@code false}
     * because the updated property value will not be the same as the value to be set.
     *
     * @param setPropertyRequests a list of properties to set
     * @param timeoutInMs the timeout for the operation, in milliseconds
     * @param cancellationSignal a signal that could be used to cancel the on-going operation
     * @param callbackExecutor the executor to execute the callback with
     * @param setPropertyCallback the callback function to deliver the result
     * @throws SecurityException if missing permission to write one of the specific properties.
     * @throws IllegalArgumentException if one of the properties to set is not supported.
     * @throws IllegalArgumentException if one of the properties is not readable and does not set
     *   {@code waitForPropertyUpdate} to {@code false}.
     * @throws IllegalArgumentException if one of the properties is
     *   {@code HVAC_TEMPERATURE_VALUE_SUGGESTION} and does not set {@code waitForPropertyUpdate}
     *   to {@code false}.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public void setPropertiesAsync(
            @NonNull List<SetPropertyRequest<?>> setPropertyRequests,
            long timeoutInMs,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable Executor callbackExecutor,
            @NonNull SetPropertyCallback setPropertyCallback) {
        if (DBG) {
            Log.d(TAG, "setPropertiesAsync, requests: " + setPropertyRequests + ", timeoutInMs: "
                    + timeoutInMs + ", callback: " + setPropertyCallback);
        }

        checkAsyncArguments(setPropertyRequests, setPropertyCallback, timeoutInMs);
        if (callbackExecutor == null) {
            callbackExecutor = new HandlerExecutor(getEventHandler());
        }

        List<AsyncPropertyServiceRequest> setPropertyServiceRequests = new ArrayList<>(
                setPropertyRequests.size());
        for (int i = 0; i < setPropertyRequests.size(); i++) {
            SetPropertyRequest setPropertyRequest = setPropertyRequests.get(i);
            int propertyId = setPropertyRequest.getPropertyId();
            int areaId = setPropertyRequest.getAreaId();
            requireNonNull(setPropertyRequest.getValue());
            assertPropertyIdIsSupported(propertyId);

            setPropertyServiceRequests.add(AsyncPropertyServiceRequest.newSetAsyncRequest(
                    setPropertyRequest));
        }

        List<Integer> requestIds = storePendingRequestInfo(setPropertyRequests, callbackExecutor,
                setPropertyCallback);

        try {
            if (DBG) {
                Log.d(TAG, "calling CarPropertyService.setPropertiesAsync");
            }
            mService.setPropertiesAsync(new AsyncPropertyServiceRequestList(
                    setPropertyServiceRequests), mAsyncPropertyResultCallback, timeoutInMs);
            if (DBG) {
                Log.d(TAG, "CarPropertyService.setPropertiesAsync succeed");
            }
        } catch (RemoteException e) {
            clearRequestIdToAsyncRequestInfo(setPropertyRequests);
            handleRemoteExceptionFromCarService(e);
        } catch (IllegalArgumentException | SecurityException e) {
            clearRequestIdToAsyncRequestInfo(setPropertyRequests);
            throw e;
        }
        if (cancellationSignal != null) {
            setOnCancelListener(cancellationSignal, requestIds);
        }
    }

    /**
     * Sets a list of car property values asynchronously.
     *
     * Same as {@link CarPropertyManager#setPropertiesAsync(List, long, CancellationSignal,
     * Executor, SetPropertyCallback)} with default timeout 10s.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    public void setPropertiesAsync(
            @NonNull List<SetPropertyRequest<?>> setPropertyRequests,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable Executor callbackExecutor,
            @NonNull SetPropertyCallback setPropertyCallback) {
        setPropertiesAsync(setPropertyRequests, ASYNC_GET_DEFAULT_TIMEOUT_MS, cancellationSignal,
                callbackExecutor, setPropertyCallback);
    }

    private void assertPropertyIdIsSupported(int propertyId) {
        if (!CarPropertyHelper.isSupported(propertyId)) {
            throw new IllegalArgumentException("The property: "
                    + VehiclePropertyIds.toString(propertyId) + " is unsupported");
        }
    }

    private <RequestType extends AsyncPropertyRequest, CallbackType> List<Integer>
            storePendingRequestInfo(
                    List<RequestType> requests, Executor callbackExecutor, CallbackType callback) {
        if (DBG) {
            Log.d(TAG, "store pending async requests: " + requests);
        }
        List<Integer> requestIds = new ArrayList<>();
        SparseArray<AsyncPropertyRequestInfo<?, ?>> requestInfoToAdd = new SparseArray<>();
        synchronized (mLock) {
            for (int i = 0; i < requests.size(); i++) {
                RequestType request = requests.get(i);
                AsyncPropertyRequestInfo<RequestType, CallbackType> requestInfo =
                        new AsyncPropertyRequestInfo(request, callbackExecutor, callback);
                int requestId = request.getRequestId();
                requestIds.add(requestId);
                if (mRequestIdToAsyncRequestInfo.contains(requestId)
                        || requestInfoToAdd.contains(requestId)) {
                    throw new IllegalArgumentException(
                            "Request ID: " + requestId + " already exists");
                }
                requestInfoToAdd.put(requestId, requestInfo);
            }
            for (int i = 0; i < requestInfoToAdd.size(); i++) {
                mRequestIdToAsyncRequestInfo.put(requestInfoToAdd.keyAt(i),
                        requestInfoToAdd.valueAt(i));
            }
        }
        return requestIds;
    }
}
