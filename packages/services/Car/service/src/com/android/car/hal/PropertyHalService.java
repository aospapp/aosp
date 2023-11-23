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
package com.android.car.hal;

import static android.car.hardware.property.CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_ACCESS_DENIED;
import static android.car.hardware.property.CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_INVALID_ARG;
import static android.car.hardware.property.CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_PROPERTY_NOT_AVAILABLE;
import static android.car.hardware.property.CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_TRY_AGAIN;
import static android.car.hardware.property.CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_ACCESS_DENIED;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_INTERNAL_ERROR;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_INVALID_ARG;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_NOT_AVAILABLE;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_NOT_AVAILABLE_POOR_VISIBILITY;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_TRY_AGAIN;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;
import static com.android.car.internal.property.CarPropertyHelper.STATUS_OK;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.VehiclePropertyIds;
import android.car.builtin.os.BuildHelper;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.CarPropertyAsyncErrorCode;
import android.car.hardware.property.CarPropertyManager.CarSetPropertyErrorCode;
import android.car.hardware.property.VehicleHalStatusCode.VehicleHalStatusCodeInt;
import android.hardware.automotive.vehicle.VehiclePropError;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyStatus;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.CarServiceUtils;
import com.android.car.VehicleStub;
import com.android.car.VehicleStub.AsyncGetSetRequest;
import com.android.car.VehicleStub.GetVehicleStubAsyncResult;
import com.android.car.VehicleStub.SetVehicleStubAsyncResult;
import com.android.car.VehicleStub.VehicleStubCallbackInterface;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.LongPendingRequestPool;
import com.android.car.internal.LongPendingRequestPool.TimeoutCallback;
import com.android.car.internal.LongRequestIdWithTimeout;
import com.android.car.internal.property.AsyncPropertyServiceRequest;
import com.android.car.internal.property.CarPropertyHelper;
import com.android.car.internal.property.GetSetValueResult;
import com.android.car.internal.property.GetSetValueResultList;
import com.android.car.internal.property.IAsyncPropertyResultCallback;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Common interface for HAL services that send Vehicle Properties back and forth via ICarProperty.
 * Services that communicate by passing vehicle properties back and forth via ICarProperty should
 * extend this class.
 */
public class PropertyHalService extends HalServiceBase {
    private static final String TAG = CarLog.tagFor(PropertyHalService.class);
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);
    private static final int ASYNC_RETRY_SLEEP_IN_MS = 100;

    // Async get request from user.
    private static final int GET = 0;
    // Async set request from user.
    private static final int SET = 1;
    // Async get request for getting initial value when user issues async set property request.
    // The reason we need to get initial value is that if the value to be set is the same as
    // the current value, there might not be a property update event generated. In this case,
    // it should be considered a success. If we get the initial value successfully and the
    // initial value is the same as the target value, we treat the async set as success.
    private static final int GET_INITIAL_VALUE_FOR_SET = 2;

    // Different type of async get/set property requests.
    @IntDef({GET, SET, GET_INITIAL_VALUE_FOR_SET})
    @Retention(RetentionPolicy.SOURCE)
    private @interface AsyncRequestType {}

    private static final class AsyncPropRequestInfo implements LongRequestIdWithTimeout {
        private final AsyncPropertyServiceRequest mPropMgrRequest;
        // The uptimeMillis when this request time out.
        private final long mTimeoutUptimeMs;
        private final @AsyncRequestType int mRequestType;
        private final VehicleStubCallback mVehicleStubCallback;
        private boolean mSetRequestSent;
        private long mUpdateTimestampNanos;
        private boolean mValueUpdated;
        private int mServiceRequestId;
        private float mUpdateRateHz;
        // The associated async set request for get_initial_value request.
        private @Nullable AsyncPropRequestInfo mAssocSetValueRequestInfo;
        // The associated get initial value request for async set request.
        private @Nullable AsyncPropRequestInfo mAssocGetInitValueRequestInfo;

        AsyncPropRequestInfo(@AsyncRequestType int requestType,
                AsyncPropertyServiceRequest propMgrRequest,
                long timeoutUptimeMs, VehicleStubCallback vehicleStubCallback) {
            mPropMgrRequest = propMgrRequest;
            mTimeoutUptimeMs = timeoutUptimeMs;
            mRequestType = requestType;
            mVehicleStubCallback = vehicleStubCallback;
        }

        private @AsyncRequestType int getRequestType() {
            return mRequestType;
        }

        private int getManagerRequestId() {
            return mPropMgrRequest.getRequestId();
        }


        private String getPropertyName() {
            return VehiclePropertyIds.toString(getPropertyId());
        }

        private static String requestTypeToString(@AsyncRequestType int requestType) {
            switch (requestType) {
                case GET:
                    return "GET";
                case SET:
                    return "SET";
                case GET_INITIAL_VALUE_FOR_SET:
                    return "GET_INITIAL_VALUE_FOR_SET";
                default:
                    return "UNKNOWN";
            }
        }

        int getPropertyId() {
            return mPropMgrRequest.getPropertyId();
        }

        int getAreaId() {
            return mPropMgrRequest.getAreaId();
        }

        public long getUpdateTimestampNanos() {
            return mUpdateTimestampNanos;
        }

        AsyncPropertyServiceRequest getPropSvcRequest() {
            return mPropMgrRequest;
        }

        GetSetValueResult toErrorResult(@CarPropertyAsyncErrorCode int errorCode,
                int vendorErrorCode) {
            return GetSetValueResult.newErrorResult(getManagerRequestId(), errorCode,
                    vendorErrorCode);
        }

        GetSetValueResult toGetValueResult(CarPropertyValue value) {
            return GetSetValueResult.newGetValueResult(getManagerRequestId(), value);
        }

        GetSetValueResult toSetValueResult(long updateTimestampNanos) {
            return GetSetValueResult.newSetValueResult(getManagerRequestId(),
                    updateTimestampNanos);
        }

        void setSetRequestSent() {
            mSetRequestSent = true;
        }

        void setValueUpdated(long updateTimestampNanos) {
            mValueUpdated = true;
            mUpdateTimestampNanos = updateTimestampNanos;
        }

        boolean isWaitForPropertyUpdate() {
            return mPropMgrRequest.isWaitForPropertyUpdate();
        }

        boolean success() {
            // If the set request is sent and either we don't wait for property update or the
            // property update happened (which includes the initial value is already the target
            // value)
            return mSetRequestSent && (!isWaitForPropertyUpdate() || mValueUpdated);
        }

        void setAssocSetValueRequestInfo(AsyncPropRequestInfo requestInfo) {
            mAssocSetValueRequestInfo = requestInfo;
        }

        @Nullable AsyncPropRequestInfo getAssocSetValueRequestInfo() {
            return mAssocSetValueRequestInfo;
        }

        void setAssocGetInitValueRequestInfo(AsyncPropRequestInfo requestInfo) {
            mAssocGetInitValueRequestInfo = requestInfo;
        }

        @Nullable AsyncPropRequestInfo getAssocGetInitValueRequestInfo() {
            return mAssocGetInitValueRequestInfo;
        }

        void setServiceRequestId(int serviceRequestId) {
            mServiceRequestId = serviceRequestId;
        }

        int getServiceRequestId() {
            return mServiceRequestId;
        }

        VehicleStubCallback getVehicleStubCallback() {
            return mVehicleStubCallback;
        }

        float getUpdateRateHz() {
            return mUpdateRateHz;
        }

        /**
         * Parses the updateRateHz from client and sanitize it.
         */
        void parseClientUpdateRateHz(HalPropConfig halPropConfig) {
            float clientUpdateRateHz = mPropMgrRequest.getUpdateRateHz();
            if (clientUpdateRateHz == 0.0f) {
                // If client does not specify a sample rate for async set, subscribe at the max
                // sample rate so that we can get the property update as soon as possible.
                clientUpdateRateHz = halPropConfig.getMaxSampleRate();
            }
            mUpdateRateHz = sanitizeUpdateRateHz(clientUpdateRateHz, halPropConfig);
        }

        @Override
        public long getTimeoutUptimeMs() {
            return mTimeoutUptimeMs;
        }

        @Override
        public long getRequestId() {
            return getServiceRequestId();
        }

        @Override
        @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
        public String toString() {
            return new StringBuilder()
                    .append("AsyncPropRequestInfo{type: ")
                    .append(requestTypeToString(mRequestType))
                    .append(", mgrRequestId: ")
                    .append(getManagerRequestId())
                    .append(", property: ")
                    .append(getPropertyName())
                    .append(", areaId: ")
                    .append(getAreaId())
                    .append(", timeout at uptime: ")
                    .append(getTimeoutUptimeMs()).append("ms")
                    .append(", serviceRequestId: ")
                    .append(getServiceRequestId())
                    .append(", update rate: ")
                    .append(getUpdateRateHz()).append("hz")
                    .append(", value updated for set: ")
                    .append(mValueUpdated)
                    .append(", request sent for set: ")
                    .append(mSetRequestSent)
                    .append("}").toString();
        }
    };

    // The request ID passed by CarPropertyService (ManagerRequestId) is directly passed from
    // CarPropertyManager. Multiple CarPropertyManagers use the same car service instance, thus,
    // the ManagerRequestId is not unique. We have to create another unique ID called
    // ServiceRequestId and pass it to underlying layer (VehicleHal and VehicleStub).
    // Internally, we will map ManagerRequestId to ServiceRequestId.
    private final AtomicInteger mServiceRequestIdCounter = new AtomicInteger(0);
    // Only contains property ID if value is different for the CarPropertyManager and the HAL.
    private static final BidirectionalSparseIntArray MGR_PROP_ID_TO_HAL_PROP_ID =
            BidirectionalSparseIntArray.create(
                    new int[]{VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS,
                            VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS});
    private final VehicleHal mVehicleHal;
    private final PropertyHalServiceIds mPropertyHalServiceIds = new PropertyHalServiceIds();
    private final HalPropValueBuilder mPropValueBuilder;
    private final HandlerThread mHandlerThread =
            CarServiceUtils.getHandlerThread(getClass().getSimpleName());
    private final Handler mHandler = new Handler(mHandlerThread.getLooper());
    private final TimeoutCallback mTimeoutCallback = new AsyncRequestTimeoutCallback();

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<IBinder, VehicleStubCallback>
            mResultBinderToVehicleStubCallback = new ArrayMap<>();
    @GuardedBy("mLock")
    private final SparseArray<CarPropertyConfig<?>> mMgrPropIdToCarPropConfig = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<HalPropConfig> mHalPropIdToPropConfig =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<Pair<String, String>> mMgrPropIdToPermissions = new SparseArray<>();
    // A pending request pool to store all pending async get/set property request info.
    // Service request ID is int, not long, but we only have one version of PendingRequestPool.
    @GuardedBy("mLock")
    private final LongPendingRequestPool<AsyncPropRequestInfo> mPendingAsyncRequests =
            new LongPendingRequestPool<>(mHandler.getLooper(), mTimeoutCallback);
    @GuardedBy("mLock")
    private PropertyHalListener mPropertyHalListener;
    // A map from subscribed PropertyHalService property IDs to their current update rate.
    // This value will be updated by {@link #subscribeProperty} or {@link #unsubscribeProperty}.
    @GuardedBy("mLock")
    private final SparseArray<Float> mSubscribedHalPropIdToUpdateRateHz = new SparseArray<>();
    // A map to store pending async set request info that are currently waiting for property update
    // events.
    @GuardedBy("mLock")
    private final SparseArray<List<AsyncPropRequestInfo>> mHalPropIdToWaitingUpdateRequestInfo =
            new SparseArray<>();

    private class AsyncRequestTimeoutCallback implements TimeoutCallback {
        @Override
        public void onRequestsTimeout(List<Long> serviceRequestIds) {
            ArrayMap<VehicleStubCallback, List<Integer>> callbackToRequestIds = new ArrayMap<>();
            synchronized (mLock) {
                // Get the callback for the pending requests.
                for (int i = 0; i < serviceRequestIds.size(); i++) {
                    // Service ID is always a valid int.
                    int serviceRequestId = serviceRequestIds.get(i).intValue();
                    AsyncPropRequestInfo requestInfo =
                            getPendingAsyncPropRequestInfoLocked(serviceRequestId);
                    if (requestInfo == null) {
                        Slogf.w(TAG, "The pending request: %d finished before timeout handler",
                                serviceRequestId);
                        continue;
                    }
                    VehicleStubCallback callback = requestInfo.getVehicleStubCallback();
                    if (callbackToRequestIds.get(callback) == null) {
                        callbackToRequestIds.put(callback, new ArrayList<>());
                    }
                    callbackToRequestIds.get(callback).add(serviceRequestId);
                }
            }
            for (int i = 0; i < callbackToRequestIds.size(); i++) {
                callbackToRequestIds.keyAt(i).onRequestsTimeout(callbackToRequestIds.valueAt(i));
            }
        }
    }

    private class VehicleStubCallback extends VehicleStubCallbackInterface {
        private final IAsyncPropertyResultCallback mAsyncPropertyResultCallback;
        private final IBinder mClientBinder;

        VehicleStubCallback(
                IAsyncPropertyResultCallback asyncPropertyResultCallback) {
            mAsyncPropertyResultCallback = asyncPropertyResultCallback;
            mClientBinder = asyncPropertyResultCallback.asBinder();
        }

        private void sendGetValueResults(List<GetSetValueResult> results) {
            if (results.isEmpty()) {
                return;
            }
            try {
                mAsyncPropertyResultCallback.onGetValueResults(new GetSetValueResultList(results));
            } catch (RemoteException e) {
                Slogf.w(TAG, "sendGetValueResults: Client might have died already", e);
            }
        }

        void sendSetValueResults(List<GetSetValueResult> results) {
            if (results.isEmpty()) {
                return;
            }
            try {
                mAsyncPropertyResultCallback.onSetValueResults(new GetSetValueResultList(results));
            } catch (RemoteException e) {
                Slogf.w(TAG, "sendSetValueResults: Client might have died already", e);
            }
        }

        private void retryIfNotExpired(List<AsyncPropRequestInfo> retryRequests) {
            List<AsyncGetSetRequest> vehicleStubAsyncGetRequests = new ArrayList<>();
            List<GetSetValueResult> timeoutGetResults = new ArrayList<>();
            List<AsyncGetSetRequest> vehicleStubAsyncSetRequests = new ArrayList<>();
            List<GetSetValueResult> timeoutSetResults = new ArrayList<>();
            List<AsyncPropRequestInfo> pendingRetryRequests = new ArrayList<>();
            synchronized (mLock) {
                // Get the current time after obtaining lock since it might take some time to get
                // the lock.
                long currentUptimeMs = SystemClock.uptimeMillis();
                for (int i = 0; i < retryRequests.size(); i++) {
                    AsyncPropRequestInfo requestInfo = retryRequests.get(i);
                    long timeoutUptimeMs = requestInfo.getTimeoutUptimeMs();
                    if (timeoutUptimeMs <= currentUptimeMs) {
                        // The request already expired.
                        generateTimeoutResult(requestInfo, timeoutGetResults, timeoutSetResults);
                        continue;
                    }

                    // Generate a new service request ID and async request object for the retry.
                    AsyncGetSetRequest vehicleStubAsyncRequest =
                            generateVehicleStubAsyncRequestLocked(requestInfo);
                    pendingRetryRequests.add(requestInfo);

                    switch (requestInfo.getRequestType()) {
                        case GET: // fallthrough
                        case GET_INITIAL_VALUE_FOR_SET:
                            vehicleStubAsyncGetRequests.add(vehicleStubAsyncRequest);
                            break;
                        case SET:
                            vehicleStubAsyncSetRequests.add(vehicleStubAsyncRequest);
                            break;
                    }
                }

                // We already marked all the input requests as finished. Now for the new retry
                // requests, we need to put them back into the pending request pool.
                mPendingAsyncRequests.addPendingRequests(pendingRetryRequests);
            }

            sendGetValueResults(timeoutGetResults);
            if (!vehicleStubAsyncGetRequests.isEmpty()) {
                mVehicleHal.getAsync(vehicleStubAsyncGetRequests, this);
            }
            sendSetValueResults(timeoutSetResults);
            if (!vehicleStubAsyncSetRequests.isEmpty()) {
                mVehicleHal.setAsync(vehicleStubAsyncSetRequests, this);
            }
        }

        IBinder getClientBinder() {
            return mClientBinder;
        }

        // This is a wrapper for death recipient that will unlink itself upon binder death.
        private final class DeathRecipientWrapper implements DeathRecipient {
            private DeathRecipient mInnerRecipient;

            DeathRecipientWrapper(DeathRecipient innerRecipient) {
                mInnerRecipient = innerRecipient;
            }

            @Override
            public void binderDied() {
                mInnerRecipient.binderDied();
                mClientBinder.unlinkToDeath(this, /* flags= */ 0);
            }
        }

        @Override
        public void linkToDeath(DeathRecipient recipient) throws RemoteException {
            mClientBinder.linkToDeath(new DeathRecipientWrapper(recipient),
                    /* flags= */ 0);
        }

        // Parses an async getProperty result and convert it to an okay/error result.
        private GetSetValueResult parseGetAsyncResults(
                GetVehicleStubAsyncResult getVehicleStubAsyncResult,
                AsyncPropRequestInfo clientRequestInfo) {
            int carPropMgrErrorCode = getVehicleStubAsyncResult.getErrorCode();
            if (carPropMgrErrorCode != STATUS_OK) {
                // All other error results will be delivered back through callback.
                return clientRequestInfo.toErrorResult(carPropMgrErrorCode,
                        getVehicleStubAsyncResult.getVendorErrorCode());
            }

            // For okay status, convert the property value to the type the client expects.
            int mgrPropId = clientRequestInfo.getPropertyId();
            int halPropId = managerToHalPropId(mgrPropId);
            HalPropConfig halPropConfig;
            synchronized (mLock) {
                halPropConfig = mHalPropIdToPropConfig.get(halPropId);
            }
            if (halPropConfig == null) {
                Slogf.e(TAG, "No configuration found for property: %s, must not happen",
                        clientRequestInfo.getPropertyName());
                return clientRequestInfo.toErrorResult(
                        CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR,
                        /* vendorErrorCode= */ 0);
            }
            HalPropValue halPropValue = getVehicleStubAsyncResult.getHalPropValue();
            if (halPropValue.getStatus() == VehiclePropertyStatus.UNAVAILABLE) {
                return clientRequestInfo.toErrorResult(
                        CarPropertyManager.STATUS_ERROR_NOT_AVAILABLE,
                        /* vendorErrorCode= */ 0);
            }
            if (halPropValue.getStatus() != VehiclePropertyStatus.AVAILABLE) {
                return clientRequestInfo.toErrorResult(
                        CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR,
                        /* vendorErrorCode= */ 0);
            }

            try {
                return clientRequestInfo.toGetValueResult(
                        halPropValue.toCarPropertyValue(mgrPropId, halPropConfig));
            } catch (IllegalStateException e) {
                Slogf.e(TAG, e,
                        "Cannot convert halPropValue to carPropertyValue, property: %s, areaId: %d",
                        halPropIdToName(halPropValue.getPropId()), halPropValue.getAreaId());
                return clientRequestInfo.toErrorResult(
                        CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR,
                        /* vendorErrorCode= */ 0);
            }
        }

        @Override
        public void onGetAsyncResults(
                List<GetVehicleStubAsyncResult> getVehicleStubAsyncResults) {
            List<GetSetValueResult> getValueResults = new ArrayList<>();
            // If we receive get value result for initial value request and the result is the
            // same as the target value, we might finish the associated async set value request.
            // So we need potential set value results here.
            List<GetSetValueResult> setValueResults = new ArrayList<>();
            List<AsyncPropRequestInfo> retryRequests = new ArrayList<>();
            synchronized (mLock) {
                Set<Integer> updatedHalPropIds = new ArraySet<>();
                for (int i = 0; i < getVehicleStubAsyncResults.size(); i++) {
                    GetVehicleStubAsyncResult getVehicleStubAsyncResult =
                            getVehicleStubAsyncResults.get(i);
                    int serviceRequestId = getVehicleStubAsyncResult.getServiceRequestId();
                    AsyncPropRequestInfo clientRequestInfo =
                            getAndRemovePendingAsyncPropRequestInfoLocked(serviceRequestId,
                                    updatedHalPropIds);
                    if (clientRequestInfo == null) {
                        Slogf.w(TAG, "async request for ID: %d not found, ignore the result",
                                serviceRequestId);
                        continue;
                    }

                    int carPropMgrErrorCode = getVehicleStubAsyncResult.getErrorCode();
                    if (carPropMgrErrorCode == VehicleStub.STATUS_TRY_AGAIN) {
                        // The request might need to be retried.
                        if (DBG) {
                            Slogf.d(TAG, "request: %s try again", clientRequestInfo);
                        }
                        retryRequests.add(clientRequestInfo);
                        continue;
                    }

                    GetSetValueResult result = parseGetAsyncResults(getVehicleStubAsyncResult,
                            clientRequestInfo);
                    if (clientRequestInfo.getRequestType() != GET_INITIAL_VALUE_FOR_SET) {
                        getValueResults.add(result);
                        continue;
                    }

                    if (DBG) {
                        Slogf.d(TAG, "handling init value result for request: %s",
                                clientRequestInfo);
                    }
                    // Handle GET_INITIAL_VALUE_FOR_SET result.
                    int errorCode = result.getErrorCode();
                    if (errorCode != STATUS_OK) {
                        Slogf.w(TAG, "the init value get request: %s failed, ignore the result, "
                                + "error: %d", clientRequestInfo, errorCode);
                        continue;
                    }
                    // If the initial value result is the target value and the async set
                    // request returned, we finish the pending async set result.
                    AsyncPropRequestInfo assocSetValueRequestInfo =
                            clientRequestInfo.getAssocSetValueRequestInfo();
                    if (assocSetValueRequestInfo == null) {
                        Slogf.e(TAG, "received get initial value result, but no associated set "
                                + "value request is defined");
                        continue;
                    }
                    GetSetValueResult maybeSetResult = maybeFinishPendingSetValueRequestLocked(
                            assocSetValueRequestInfo, result.getCarPropertyValue());
                    if (maybeSetResult != null) {
                        if (DBG) {
                            Slogf.d(TAG, "The initial value is the same as target value for "
                                    + "request: %s, sending success set result",
                                    assocSetValueRequestInfo);
                        }
                        setValueResults.add(maybeSetResult);
                        removePendingAsyncPropRequestInfoLocked(
                                assocSetValueRequestInfo, updatedHalPropIds);
                    }
                }
                updateSubscriptionRateLocked(updatedHalPropIds);
            }

            sendGetValueResults(getValueResults);
            sendSetValueResults(setValueResults);

            if (!retryRequests.isEmpty()) {
                mHandler.postDelayed(() -> {
                    retryIfNotExpired(retryRequests);
                }, ASYNC_RETRY_SLEEP_IN_MS);
            }
        }

        @Override
        public void onSetAsyncResults(
                List<SetVehicleStubAsyncResult> setVehicleStubAsyncResults) {
            List<GetSetValueResult> setValueResults = new ArrayList<>();
            List<AsyncPropRequestInfo> retryRequests = new ArrayList<>();
            Set<Integer> updatedHalPropIds = new ArraySet<>();
            synchronized (mLock) {
                for (int i = 0; i < setVehicleStubAsyncResults.size(); i++) {
                    SetVehicleStubAsyncResult setVehicleStubAsyncResult =
                            setVehicleStubAsyncResults.get(i);
                    int serviceRequestId = setVehicleStubAsyncResult.getServiceRequestId();
                    AsyncPropRequestInfo clientRequestInfo =
                            getPendingAsyncPropRequestInfoLocked(serviceRequestId);
                    if (clientRequestInfo == null) {
                        Slogf.w(TAG, "async request for ID:  %d not found, ignore the result",
                                serviceRequestId);
                        continue;
                    }
                    int carPropMgrErrorCode = setVehicleStubAsyncResult.getErrorCode();

                    if (carPropMgrErrorCode == VehicleStub.STATUS_TRY_AGAIN) {
                        // The request might need to be retried.
                        retryRequests.add(clientRequestInfo);
                        removePendingAsyncPropRequestInfoLocked(clientRequestInfo,
                                updatedHalPropIds);
                        continue;
                    }

                    if (carPropMgrErrorCode != STATUS_OK) {
                        // All other error results will be delivered back through callback.
                        setValueResults.add(clientRequestInfo.toErrorResult(
                                carPropMgrErrorCode,
                                setVehicleStubAsyncResult.getVendorErrorCode()));
                        removePendingAsyncPropRequestInfoLocked(clientRequestInfo,
                                updatedHalPropIds);
                        continue;
                    }

                    clientRequestInfo.setSetRequestSent();
                    if (clientRequestInfo.success()) {
                        // If we have already received event for the target value or the initial
                        // value is already the target value. Mark the request as complete.
                        removePendingAsyncPropRequestInfoLocked(clientRequestInfo,
                                updatedHalPropIds);
                         // If we don't wait for property update event, then we don't know when
                        // the property is updated to the target value. We set it to the
                        // current timestamp.
                        long updateTimestampNanos = clientRequestInfo.isWaitForPropertyUpdate()
                                ? clientRequestInfo.getUpdateTimestampNanos() :
                                SystemClock.elapsedRealtimeNanos();
                        setValueResults.add(clientRequestInfo.toSetValueResult(
                                updateTimestampNanos));
                    }
                }
                updateSubscriptionRateLocked(updatedHalPropIds);
            }

            sendSetValueResults(setValueResults);

            if (!retryRequests.isEmpty()) {
                mHandler.postDelayed(() -> {
                    retryIfNotExpired(retryRequests);
                }, ASYNC_RETRY_SLEEP_IN_MS);
            }
        }

        private void generateTimeoutResult(AsyncPropRequestInfo requestInfo,
                List<GetSetValueResult> timeoutGetResults,
                List<GetSetValueResult> timeoutSetResults) {
            GetSetValueResult timeoutResult =  requestInfo.toErrorResult(
                    CarPropertyManager.STATUS_ERROR_TIMEOUT,
                    /* vendorErrorCode= */ 0);
            switch (requestInfo.getRequestType()) {
                case GET:
                    timeoutGetResults.add(timeoutResult);
                    break;
                case GET_INITIAL_VALUE_FOR_SET:
                    // Do not send the timeout requests back to the user because the original
                    // request is not originated from the user.
                    Slogf.e(TAG, "the initial value request: %s timeout", requestInfo);
                    break;
                case SET:
                    timeoutSetResults.add(timeoutResult);
                    break;
            }
        }

        @Override
        public void onRequestsTimeout(List<Integer> serviceRequestIds) {
            List<GetSetValueResult> timeoutGetResults = new ArrayList<>();
            List<GetSetValueResult> timeoutSetResults = new ArrayList<>();
            Set<Integer> updatedHalPropIds = new ArraySet<>();
            synchronized (mLock) {
                for (int i = 0; i < serviceRequestIds.size(); i++) {
                    int serviceRequestId = serviceRequestIds.get(i);
                    AsyncPropRequestInfo requestInfo =
                            getAndRemovePendingAsyncPropRequestInfoLocked(serviceRequestId,
                                    updatedHalPropIds);
                    if (requestInfo == null) {
                        Slogf.w(TAG, "Service request ID %d time out but no "
                                + "pending request is found. The request may have already been "
                                + "cancelled or finished", serviceRequestId);
                        continue;
                    }
                    if (DBG) {
                        Slogf.d(TAG, "Request: %s time out", requestInfo);
                    }
                    generateTimeoutResult(requestInfo, timeoutGetResults, timeoutSetResults);
                }
                updateSubscriptionRateLocked(updatedHalPropIds);
            }
            sendGetValueResults(timeoutGetResults);
            sendSetValueResults(timeoutSetResults);
        }
    }

    /**
     * Converts manager property ID to Vehicle HAL property ID.
     */
    private static int managerToHalPropId(int mgrPropId) {
        return MGR_PROP_ID_TO_HAL_PROP_ID.getValue(mgrPropId, mgrPropId);
    }

    /**
     * Converts Vehicle HAL property ID to manager property ID.
     */
    private static int halToManagerPropId(int halPropId) {
        return MGR_PROP_ID_TO_HAL_PROP_ID.getKey(halPropId, halPropId);
    }

    private static void checkHalPropValueStatus(HalPropValue halPropValue, int mgrPropId,
            int areaId) {
        if (halPropValue.getStatus() == VehiclePropertyStatus.UNAVAILABLE) {
            throw new ServiceSpecificException(STATUS_NOT_AVAILABLE,
                    "VHAL returned property status as UNAVAILABLE for property: "
                    + VehiclePropertyIds.toString(mgrPropId) + ", areaId: " + areaId);
        }
        if (halPropValue.getStatus() == VehiclePropertyStatus.ERROR) {
            throw new ServiceSpecificException(STATUS_INTERNAL_ERROR,
                    "VHAL returned property status as ERROR for property: "
                    + VehiclePropertyIds.toString(mgrPropId) + ", areaId: " + areaId);
        }
    }

    /**
     * Maybe finish the pending set value request depending on the updated value.
     *
     * Check whether the updated property value is the same as the target value for pending
     * set value requests. If so, finish those requests.
     *
     * @return A success set value result for the finished request or {@code null}.
     */
    @GuardedBy("mLock")
    @Nullable
    private GetSetValueResult maybeFinishPendingSetValueRequestLocked(
            AsyncPropRequestInfo pendingSetValueRequest, CarPropertyValue updatedValue) {
        Object targetValue = pendingSetValueRequest.getPropSvcRequest()
                .getCarPropertyValue().getValue();
        Object currentValue = updatedValue.getValue();
        if (!targetValue.equals(currentValue)) {
            if (DBG) {
                Slogf.d(TAG, "Async set value request: %s receive different updated value: %s"
                        + " than target value: %s", pendingSetValueRequest, currentValue,
                        targetValue);
            }
            return null;
        }
        long updateTimestampNanos = updatedValue.getTimestamp();
        pendingSetValueRequest.setValueUpdated(updateTimestampNanos);
        if (!pendingSetValueRequest.success()) {
            return null;
        }

        return pendingSetValueRequest.toSetValueResult(updateTimestampNanos);
    }

    /**
     * Generates a {@link AsyncGetSetRequest} according to a {@link AsyncPropRequestInfo}.
     *
     * <p>Generates a new PropertyHalService Request ID. Associate the ID with the request and
     * returns a {@link AsyncGetSetRequest} that could be sent to {@link VehicleStub}.
     */
    @GuardedBy("mLock")
    private AsyncGetSetRequest generateVehicleStubAsyncRequestLocked(
            AsyncPropRequestInfo asyncPropRequestInfo) {
        int serviceRequestId = mServiceRequestIdCounter.getAndIncrement();
        asyncPropRequestInfo.setServiceRequestId(serviceRequestId);

        HalPropValue halPropValue;
        CarPropertyValue requestCarPropertyValue = asyncPropRequestInfo.getPropSvcRequest()
                .getCarPropertyValue();
        if (requestCarPropertyValue != null) {
            // If this is a set request, the car property value stores the value to be set.
            halPropValue = carPropertyValueToHalPropValueLocked(requestCarPropertyValue);
        } else {
            // Otherwise this is a get request, we only need the property ID and area ID.
            int halPropertyId = managerToHalPropId(asyncPropRequestInfo.getPropertyId());
            int areaId = asyncPropRequestInfo.getAreaId();
            halPropValue = mPropValueBuilder.build(halPropertyId, areaId);
        }
        return new AsyncGetSetRequest(serviceRequestId, halPropValue,
                asyncPropRequestInfo.getTimeoutUptimeMs());
    }

    @GuardedBy("mLock")
    @Nullable private AsyncPropRequestInfo getPendingAsyncPropRequestInfoLocked(
            int serviceRequestId) {
        AsyncPropRequestInfo requestInfo =
                mPendingAsyncRequests.getRequestIfFound(serviceRequestId);
        if (requestInfo == null) {
            Slogf.w(TAG, "the request for propertyHalService request "
                    + "ID: %d already timed out or already completed", serviceRequestId);
        }
        return requestInfo;
    }

    @GuardedBy("mLock")
    @Nullable private AsyncPropRequestInfo getAndRemovePendingAsyncPropRequestInfoLocked(
            int serviceRequestId, Set<Integer> updatedHalPropIds) {
        AsyncPropRequestInfo requestInfo = getPendingAsyncPropRequestInfoLocked(serviceRequestId);
        if (requestInfo == null) {
            Slogf.w(TAG, "the request for propertyHalService request "
                    + "ID: %d already timed out or already completed", serviceRequestId);
            return null;
        }
        removePendingAsyncPropRequestInfoLocked(requestInfo, updatedHalPropIds);
        return requestInfo;
    }

    /**
     * Remove the pending async request from the pool.
     *
     * If the request to remove is an async set request, also remove it from the
     * {@code mHalPropIdToWaitingUpdateRequestInfo} map. This will cause the subscription rate to
     * be updated for the specific property because we no longer need to monitor this property
     * any more internally.
     *
     * The {@code updatedHalPropIds} will store the affected property IDs if their subscription
     * rate need to be recalculated.
     */
    @GuardedBy("mLock")
    private void removePendingAsyncPropRequestInfoLocked(
            AsyncPropRequestInfo pendingRequest, Set<Integer> updatedHalPropIds) {
        int serviceRequestId = pendingRequest.getServiceRequestId();
        mPendingAsyncRequests.removeRequest(serviceRequestId);
        if (pendingRequest.getRequestType() == SET) {
            cleanupPendingAsyncSetRequestLocked(pendingRequest, updatedHalPropIds);
        }
    }

    @GuardedBy("mLock")
    private void cleanupPendingAsyncSetRequestLocked(
            AsyncPropRequestInfo pendingRequest, Set<Integer> updatedHalPropIds) {
        int halPropId = managerToHalPropId(pendingRequest.getPropertyId());
        if (!pendingRequest.isWaitForPropertyUpdate()) {
            return;
        }
        if (pendingRequest.getAssocGetInitValueRequestInfo() == null) {
            Slogf.e(TAG, "The pending async set value request: %s"
                    + " does not have an associated get initial value request, must not happen",
                    pendingRequest);
            return;
        }
        // If we are removing an async set property request, then we should remove its associated
        // get initial value request as well if it has not been finished.
        AsyncPropRequestInfo assocGetInitValueRequestInfo =
                pendingRequest.getAssocGetInitValueRequestInfo();
        int assocInitValueRequestId = assocGetInitValueRequestInfo.getServiceRequestId();
        assocGetInitValueRequestInfo = mPendingAsyncRequests.getRequestIfFound(
                assocInitValueRequestId);
        if (assocGetInitValueRequestInfo != null) {
            mPendingAsyncRequests.removeRequest(assocInitValueRequestId);
            // Use a separate runnable to do this outside lock.
            mHandler.post(() -> mVehicleHal.cancelRequests(List.of(assocInitValueRequestId)));
        }
        if (!mHalPropIdToWaitingUpdateRequestInfo.contains(halPropId)) {
            return;
        }
        if (!mHalPropIdToWaitingUpdateRequestInfo.get(halPropId).remove(pendingRequest)) {
            return;
        }
        if (mHalPropIdToWaitingUpdateRequestInfo.get(halPropId).isEmpty()) {
            mHalPropIdToWaitingUpdateRequestInfo.remove(halPropId);
        }
        updatedHalPropIds.add(halPropId);
    }

    /**
     * PropertyHalListener used to send events to CarPropertyService
     */
    public interface PropertyHalListener {
        /**
         * This event is sent whenever the property value is updated
         */
        void onPropertyChange(List<CarPropertyEvent> events);

        /**
         * This event is sent when the set property call fails
         */
        void onPropertySetError(int property, int area,
                @CarSetPropertyErrorCode int errorCode);

    }

    public PropertyHalService(VehicleHal vehicleHal) {
        mVehicleHal = vehicleHal;
        if (DBG) {
            Slogf.d(TAG, "started PropertyHalService");
        }
        mPropValueBuilder = vehicleHal.getHalPropValueBuilder();
    }

    /**
     * Set the listener for the HAL service
     */
    public void setPropertyHalListener(PropertyHalListener propertyHalListener) {
        synchronized (mLock) {
            mPropertyHalListener = propertyHalListener;
        }
    }

    /**
     * @return SparseArray<CarPropertyConfig> List of configs available.
     */
    public SparseArray<CarPropertyConfig<?>> getPropertyList() {
        if (DBG) {
            Slogf.d(TAG, "getPropertyList");
        }
        synchronized (mLock) {
            if (mMgrPropIdToCarPropConfig.size() == 0) {
                for (int i = 0; i < mHalPropIdToPropConfig.size(); i++) {
                    HalPropConfig halPropConfig = mHalPropIdToPropConfig.valueAt(i);
                    int mgrPropId = halToManagerPropId(halPropConfig.getPropId());
                    CarPropertyConfig<?> carPropertyConfig = halPropConfig.toCarPropertyConfig(
                            mgrPropId);
                    mMgrPropIdToCarPropConfig.put(mgrPropId, carPropertyConfig);
                }
            }
            return mMgrPropIdToCarPropConfig;
        }
    }

    /**
     * Returns property value.
     *
     * @param mgrPropId property id in {@link VehiclePropertyIds}
     * @throws IllegalArgumentException if argument is not valid.
     * @throws ServiceSpecificException if there is an exception in HAL or the property status is
     *                                  not available.
     */
    public CarPropertyValue getProperty(int mgrPropId, int areaId)
            throws IllegalArgumentException, ServiceSpecificException {
        int halPropId = managerToHalPropId(mgrPropId);
        // CarPropertyManager catches and rethrows exception, no need to handle here.
        HalPropValue halPropValue = mVehicleHal.get(halPropId, areaId);
        HalPropConfig halPropConfig;
        synchronized (mLock) {
            halPropConfig = mHalPropIdToPropConfig.get(halPropId);
        }
        checkHalPropValueStatus(halPropValue, mgrPropId, areaId);
        try {
            return halPropValue.toCarPropertyValue(mgrPropId, halPropConfig);
        } catch (IllegalStateException e) {
            throw new ServiceSpecificException(STATUS_INTERNAL_ERROR,
                    "Cannot convert halPropValue to carPropertyValue, property: "
                    + VehiclePropertyIds.toString(mgrPropId) + " areaId: " + areaId
                    + ", exception: " + e);
        }
    }

    /**
     * Returns update rate in HZ for the subscribed property, or -1 if not subscribed.
     *
     * The update rate returned here only consideres the subscription originated from
     * {@link PropertyHalService#subscribeProperty} and does not consider the internal subscription
     * for async set value requests.
     */
    public float getSubscribedUpdateRateHz(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        synchronized (mLock) {
            return mSubscribedHalPropIdToUpdateRateHz.get(halPropId, Float.valueOf(-1f));
        }
    }

    /**
     * Get the read permission string for the property.
     */
    @Nullable
    public String getReadPermission(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        return mPropertyHalServiceIds.getReadPermission(halPropId);
    }

    /**
     * Get the write permission string for the property.
     */
    @Nullable
    public String getWritePermission(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        return mPropertyHalServiceIds.getWritePermission(halPropId);
    }

    /**
     * Get permissions for all properties in the vehicle.
     *
     * @return a SparseArray. key: propertyId, value: Pair(readPermission, writePermission).
     */
    @NonNull
    public SparseArray<Pair<String, String>> getPermissionsForAllProperties() {
        synchronized (mLock) {
            if (mMgrPropIdToPermissions.size() != 0) {
                return mMgrPropIdToPermissions;
            }
            for (int i = 0; i < mHalPropIdToPropConfig.size(); i++) {
                int halPropId = mHalPropIdToPropConfig.keyAt(i);
                mMgrPropIdToPermissions.put(halToManagerPropId(halPropId),
                        new Pair<>(mPropertyHalServiceIds.getReadPermission(halPropId),
                                mPropertyHalServiceIds.getWritePermission(halPropId)));
            }
            return mMgrPropIdToPermissions;
        }
    }

    /**
     * Return true if property is a display_units property
     */
    public boolean isDisplayUnitsProperty(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        return mPropertyHalServiceIds.isPropertyToChangeUnits(halPropId);
    }

    /**
     * Set the property value.
     *
     * @throws IllegalArgumentException if argument is invalid.
     * @throws ServiceSpecificException if there is an exception in HAL.
     */
    public void setProperty(CarPropertyValue carPropertyValue)
            throws IllegalArgumentException, ServiceSpecificException {
        HalPropValue valueToSet;
        synchronized (mLock) {
            valueToSet = carPropertyValueToHalPropValueLocked(carPropertyValue);
        }

        // CarPropertyManager catches and rethrows exception, no need to handle here.
        mVehicleHal.set(valueToSet);
    }

    /**
     * Subscribe to this property at the specified update updateRateHz.
     *
     * @throws IllegalArgumentException thrown if property is not supported by VHAL.
     */
    public void subscribeProperty(int mgrPropId, float updateRateHz)
            throws IllegalArgumentException {
        if (DBG) {
            Slogf.d(TAG, "subscribeProperty propertyId: %s, updateRateHz=%f",
                    VehiclePropertyIds.toString(mgrPropId), updateRateHz);
        }
        int halPropId = managerToHalPropId(mgrPropId);
        synchronized (mLock) {
            // Even though this involves binder call, this must be done inside the lock so that
            // the state in {@code mSubscribedHalPropIdToUpdateRateHz} is consistent with the
            // state in VHAL.
            mSubscribedHalPropIdToUpdateRateHz.put(halPropId, updateRateHz);
            updateSubscriptionRateForHalPropIdLocked(halPropId);
        }
    }

    /**
     * Unsubscribe the property and turn off update events for it.
     */
    public void unsubscribeProperty(int mgrPropId) {
        if (DBG) {
            Slogf.d(TAG, "unsubscribeProperty mgrPropId=%s",
                    VehiclePropertyIds.toString(mgrPropId));
        }
        int halPropId = managerToHalPropId(mgrPropId);
        synchronized (mLock) {
            // Even though this involves binder call, this must be done inside the lock so that
            // the state in {@code mSubscribedHalPropIdToUpdateRateHz} is consistent with the
            // state in VHAL.
            if (mSubscribedHalPropIdToUpdateRateHz.get(halPropId) == null) {
                Slogf.w(TAG, "property: %s is not subscribed.",
                        VehiclePropertyIds.toString(mgrPropId));
                return;
            }
            mSubscribedHalPropIdToUpdateRateHz.remove(halPropId);
            updateSubscriptionRateForHalPropIdLocked(halPropId);
        }
    }

    @Override
    public void init() {
        if (DBG) {
            Slogf.d(TAG, "init()");
        }
    }

    @Override
    public void release() {
        if (DBG) {
            Slogf.d(TAG, "release()");
        }
        synchronized (mLock) {
            for (int i = 0; i < mSubscribedHalPropIdToUpdateRateHz.size(); i++) {
                int halPropId = mSubscribedHalPropIdToUpdateRateHz.keyAt(i);
                mVehicleHal.unsubscribeProperty(this, halPropId);
            }
            mSubscribedHalPropIdToUpdateRateHz.clear();
            mHalPropIdToPropConfig.clear();
            mMgrPropIdToCarPropConfig.clear();
            mMgrPropIdToPermissions.clear();
            mPropertyHalListener = null;
        }
        mHandlerThread.quitSafely();
    }

    @Override
    public boolean isSupportedProperty(int halPropId) {
        return mPropertyHalServiceIds.isSupportedProperty(halPropId)
                && CarPropertyHelper.isSupported(halToManagerPropId(halPropId));
    }

    @Override
    public int[] getAllSupportedProperties() {
        return CarServiceUtils.EMPTY_INT_ARRAY;
    }

    // The method is called in HAL init(). Avoid handling complex things in here.
    @Override
    public void takeProperties(Collection<HalPropConfig> halPropConfigs) {
        for (HalPropConfig halPropConfig : halPropConfigs) {
            int halPropId = halPropConfig.getPropId();
            if (isSupportedProperty(halPropId)) {
                synchronized (mLock) {
                    mHalPropIdToPropConfig.put(halPropId, halPropConfig);
                }
                if (DBG) {
                    Slogf.d(TAG, "takeSupportedProperties: %s", halPropIdToName(halPropId));
                }
            }
        }
        if (DBG) {
            Slogf.d(TAG, "takeSupportedProperties() took %d properties", halPropConfigs.size());
        }
        // If vehicle hal support to select permission for vendor properties.
        HalPropConfig customizePermission;
        synchronized (mLock) {
            customizePermission = mHalPropIdToPropConfig.get(
                    VehicleProperty.SUPPORT_CUSTOMIZE_VENDOR_PERMISSION);
        }
        if (customizePermission != null) {
            mPropertyHalServiceIds.customizeVendorPermission(customizePermission.getConfigArray());
        }
    }

    private static void storeResultForRequest(GetSetValueResult result,
            AsyncPropRequestInfo request,
            Map<VehicleStubCallback, List<GetSetValueResult>> callbackToResults) {
        VehicleStubCallback clientCallback = request.getVehicleStubCallback();
        if (callbackToResults.get(clientCallback) == null) {
            callbackToResults.put(clientCallback, new ArrayList<>());
        }
        callbackToResults.get(clientCallback).add(result);
    }

    /**
     * Check whether there is pending async set value request for the property.
     *
     * If there are pending async set value request, check whether the updated property value is
     * the target value. If so, store the success set value result into callbackToSetValueResults.
     */
    @GuardedBy("mLock")
    private void checkPendingWaitForUpdateRequestsLocked(int halPropId,
            CarPropertyValue<?> updatedValue,
            Map<VehicleStubCallback, List<GetSetValueResult>> callbackToSetValueResults,
            Set<Integer> updatedHalPropIds) {
        List<AsyncPropRequestInfo> pendingSetRequests = mHalPropIdToWaitingUpdateRequestInfo.get(
                halPropId);
        if (pendingSetRequests == null) {
            return;
        }
        List<AsyncPropRequestInfo> finishedPendingSetRequests = new ArrayList<>();
        for (AsyncPropRequestInfo pendingSetRequest : pendingSetRequests) {
            GetSetValueResult maybeSetResult = maybeFinishPendingSetValueRequestLocked(
                    pendingSetRequest, updatedValue);
            if (pendingSetRequest.getAreaId() != updatedValue.getAreaId()) {
                continue;
            }
            // Don't remove the finished pending request info during the loop since it will
            // modify pendingSetRequests array.
            if (maybeSetResult == null) {
                if (DBG) {
                    Slogf.d(TAG, "received property update event for request: %s, but the value is "
                            + "different than target value", pendingSetRequest);
                }
                continue;
            }
            if (DBG) {
                Slogf.d(TAG, "received property update to target value event for request: %s"
                        + ", sending success async set value result", pendingSetRequest);
            }
            storeResultForRequest(maybeSetResult, pendingSetRequest, callbackToSetValueResults);
            finishedPendingSetRequests.add(pendingSetRequest);
        }

        for (AsyncPropRequestInfo finishedRequest : finishedPendingSetRequests) {
            // Pending set value request is now succeeded. Remove all record to the pending request.
            removePendingAsyncPropRequestInfoLocked(finishedRequest, updatedHalPropIds);
        }
    }

    /**
     * Calculate the new subscription rate for the hal property ID.
     *
     * Use {@code subscribeProperty} to update its subscription rate or {@code unsubscribeProperty}
     * if it is no longer subscribed.
     *
     * Note that {@code VehicleHal} subscription logic will ignore subscribe property request with
     * the same subscription rate, so we do not need to check that here.
     */
    @GuardedBy("mLock")
    private void updateSubscriptionRateForHalPropIdLocked(int halPropId) {
        Float newUpdateRateHz = calcNewUpdateRateHzLocked(halPropId);
        String propertyName = halPropIdToName(halPropId);
        if (newUpdateRateHz == null) {
            if (DBG) {
                Slogf.d(TAG, "unsubscribeProperty for property ID: %s", propertyName);
            }
            mVehicleHal.unsubscribeProperty(this, halPropId);
        } else {
            if (DBG) {
                Slogf.d(TAG, "subscribeProperty for property ID: %s, new sample rate: %f hz",
                        propertyName, newUpdateRateHz);
            }
            mVehicleHal.subscribeProperty(this, halPropId, newUpdateRateHz);
        }
    }

    @GuardedBy("mLock")
    private void updateSubscriptionRateLocked(Set<Integer> updatedHalPropIds) {
        // This functions involves binder call to VHAL, but we intentionally keep this inside the
        // lock because we need to keep the subscription status consistent. If we do not use lock
        // here, the following situation might happen:
        // 1. Lock is obtained by thread 1.
        // 2. mHalPropIdToWaitingUpdateRequestInfo is updated by one thread to state 1.
        // 3. New update rate (local variable) is calculated based on state 1.
        // 4. Lock is released by thread 1..
        // 5. Lock is obtained by thread 2.
        // 6. mHalPropIdToWaitingUpdateRequestInfo is updated by thread 2 to state 2.
        // 7. New update rate (local variable) is calculated based on state 2.
        // 8. Lock is released by thread 2.
        // 9. Thread 2 calls subscribeProperty to VHAL based on state 2.
        // 10. Thread 1 calls subscribeProperty to VHAL based on state 1.
        // 11. Now internally, the state is in state 2, but from VHAL side, it is in state 1.
        if (updatedHalPropIds.isEmpty()) {
            return;
        }
        if (DBG) {
            Slogf.d(TAG, "updated subscription rate for hal prop IDs: %s", updatedHalPropIds);
        }
        for (int updatedHalPropId : updatedHalPropIds) {
            updateSubscriptionRateForHalPropIdLocked(updatedHalPropId);
        }
    }

    @Override
    public void onHalEvents(List<HalPropValue> halPropValues) {

        List<CarPropertyEvent> eventsToDispatch = new ArrayList<>();

        // A map to store potential succeeded set value results which is caused by the values
        // updated to the target values.
        Map<VehicleStubCallback, List<GetSetValueResult>> callbackToSetValueResults =
                new ArrayMap<>();

        synchronized (mLock) {
            Set<Integer> updatedHalPropIds = new ArraySet<>();
            for (HalPropValue halPropValue : halPropValues) {
                if (halPropValue == null) {
                    continue;
                }
                int halPropId = halPropValue.getPropId();
                HalPropConfig halPropConfig = mHalPropIdToPropConfig.get(halPropId);
                if (halPropConfig == null) {
                    Slogf.w(TAG, "onHalEvents - received HalPropValue for unsupported property: %s",
                            halPropIdToName(halPropId));
                    continue;
                }
                // Check payload if it is an userdebug build.
                if (BuildHelper.isDebuggableBuild() && !mPropertyHalServiceIds.checkPayload(
                        halPropValue)) {
                    Slogf.w(TAG,
                            "Drop event for property: %s because it is failed "
                                    + "in payload checking.", halPropValue);
                    continue;
                }
                int mgrPropId = halToManagerPropId(halPropId);
                if (halPropValue.getStatus() != VehiclePropertyStatus.AVAILABLE) {
                    Slogf.w(TAG, "Drop event %s with status that is not AVAILABLE", halPropValue);
                    continue;
                }
                try {
                    CarPropertyValue<?> carPropertyValue = halPropValue.toCarPropertyValue(
                            mgrPropId, halPropConfig);
                    CarPropertyEvent carPropertyEvent = new CarPropertyEvent(
                            CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, carPropertyValue);
                    eventsToDispatch.add(carPropertyEvent);

                    checkPendingWaitForUpdateRequestsLocked(halPropId, carPropertyValue,
                            callbackToSetValueResults, updatedHalPropIds);
                } catch (IllegalStateException e) {
                    Slogf.w(TAG, "Drop event %s that does not have valid value", halPropValue);
                    continue;
                }
            }
            updateSubscriptionRateLocked(updatedHalPropIds);
        }

        PropertyHalListener propertyHalListener;
        synchronized (mLock) {
            propertyHalListener = mPropertyHalListener;
        }
        if (propertyHalListener != null) {
            propertyHalListener.onPropertyChange(eventsToDispatch);
        }

        for (VehicleStubCallback callback : callbackToSetValueResults.keySet()) {
            callback.sendSetValueResults(callbackToSetValueResults.get(callback));
        }
    }

    private static @CarSetPropertyErrorCode int convertStatusCodeToCarSetPropertyErrorCode(
            @VehicleHalStatusCodeInt int vhalStatusCode) {
        switch (vhalStatusCode) {
            case STATUS_TRY_AGAIN:
                return CAR_SET_PROPERTY_ERROR_CODE_TRY_AGAIN;
            case STATUS_INVALID_ARG:
                return CAR_SET_PROPERTY_ERROR_CODE_INVALID_ARG;
            case STATUS_NOT_AVAILABLE: // fallthrough
            case STATUS_NOT_AVAILABLE_DISABLED: // fallthrough
            case STATUS_NOT_AVAILABLE_SPEED_LOW: // fallthrough
            case STATUS_NOT_AVAILABLE_SPEED_HIGH: // fallthrough
            case STATUS_NOT_AVAILABLE_POOR_VISIBILITY: // fallthrough
            case STATUS_NOT_AVAILABLE_SAFETY:
                return CAR_SET_PROPERTY_ERROR_CODE_PROPERTY_NOT_AVAILABLE;
            case STATUS_ACCESS_DENIED:
                return CAR_SET_PROPERTY_ERROR_CODE_ACCESS_DENIED;
            default:
                return CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN;
        }
    }

    @Override
    public void onPropertySetError(ArrayList<VehiclePropError> vehiclePropErrors) {
        PropertyHalListener propertyHalListener;
        synchronized (mLock) {
            propertyHalListener = mPropertyHalListener;
        }
        if (propertyHalListener != null) {
            for (int i = 0; i < vehiclePropErrors.size(); i++) {
                VehiclePropError vehiclePropError = vehiclePropErrors.get(i);
                int mgrPropId = halToManagerPropId(vehiclePropError.propId);
                int vhalErrorCode = CarPropertyHelper.getVhalSystemErrorCode(
                        vehiclePropError.errorCode);
                Slogf.w(TAG,
                        "onPropertySetError for property: %s, area ID: %d, vhal error code: %d",
                        VehiclePropertyIds.toString(mgrPropId), vehiclePropError.areaId,
                        vhalErrorCode);
                @CarSetPropertyErrorCode int carPropErrorCode =
                        convertStatusCodeToCarSetPropertyErrorCode(vhalErrorCode);
                propertyHalListener.onPropertySetError(mgrPropId, vehiclePropError.areaId,
                        carPropErrorCode);
            }
        }
        Set<Integer> updatedHalPropIds = new ArraySet<>();
        Map<VehicleStubCallback, List<GetSetValueResult>> callbackToSetValueResults =
                new ArrayMap<>();
        synchronized (mLock) {
            for (int i = 0; i < vehiclePropErrors.size(); i++) {
                VehiclePropError vehiclePropError = vehiclePropErrors.get(i);
                // Fail all pending async set requests that are currently waiting for property
                // update which has the same property ID and same area ID.
                int halPropId = vehiclePropError.propId;
                List<AsyncPropRequestInfo> pendingSetRequests =
                        mHalPropIdToWaitingUpdateRequestInfo.get(halPropId);
                if (pendingSetRequests == null) {
                    continue;
                }
                for (int j = 0; j < pendingSetRequests.size(); j++) {
                    AsyncPropRequestInfo pendingRequest = pendingSetRequests.get(j);
                    if (pendingRequest.getAreaId() != vehiclePropError.areaId) {
                        continue;
                    }
                    removePendingAsyncPropRequestInfoLocked(pendingRequest, updatedHalPropIds);
                    int[] errorCodes = VehicleStub.convertHalToCarPropertyManagerError(
                            vehiclePropError.errorCode);
                    GetSetValueResult errorResult = pendingRequest.toErrorResult(
                            errorCodes[0], errorCodes[1]);
                    Slogf.w(TAG, "Pending async set request received property set error with "
                            + "error: %d, vendor error code: %d, fail the pending request: %s",
                            errorCodes[0], errorCodes[1], pendingRequest);
                    storeResultForRequest(errorResult, pendingRequest, callbackToSetValueResults);
                }
            }
            updateSubscriptionRateLocked(updatedHalPropIds);
        }
        for (VehicleStubCallback callback : callbackToSetValueResults.keySet()) {
            callback.sendSetValueResults(callbackToSetValueResults.get(callback));
        }
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(PrintWriter writer) {
        writer.println(TAG);
        writer.println("  Properties available:");
        synchronized (mLock) {
            for (int i = 0; i < mHalPropIdToPropConfig.size(); i++) {
                HalPropConfig halPropConfig = mHalPropIdToPropConfig.valueAt(i);
                writer.println("    " + halPropConfig);
            }
        }
    }

    private List<AsyncGetSetRequest> prepareVehicleStubRequests(@AsyncRequestType int requestType,
            List<AsyncPropertyServiceRequest> serviceRequests,
            long timeoutInMs,
            VehicleStubCallback vehicleStubCallback,
            @Nullable List<AsyncPropRequestInfo> assocSetValueRequestInfo,
            @Nullable List<AsyncPropRequestInfo> outRequestInfo) {
        // TODO(b/242326085): Change local variables into memory pool to reduce memory
        //  allocation/release cycle
        List<AsyncGetSetRequest> vehicleStubRequests = new ArrayList<>();
        List<AsyncPropRequestInfo> pendingRequestInfo = new ArrayList<>();
        Long nowUptimeMs = SystemClock.uptimeMillis();
        synchronized (mLock) {
            for (int i = 0; i < serviceRequests.size(); i++) {
                AsyncPropertyServiceRequest serviceRequest = serviceRequests.get(i);
                AsyncPropRequestInfo pendingRequest = new AsyncPropRequestInfo(requestType,
                        serviceRequest, nowUptimeMs + timeoutInMs, vehicleStubCallback);
                if (assocSetValueRequestInfo != null) {
                    // Link the async set value request and the get init value request together.
                    pendingRequest.setAssocSetValueRequestInfo(assocSetValueRequestInfo.get(i));
                    assocSetValueRequestInfo.get(i).setAssocGetInitValueRequestInfo(pendingRequest);
                }
                AsyncGetSetRequest vehicleStubRequest = generateVehicleStubAsyncRequestLocked(
                        pendingRequest);
                vehicleStubRequests.add(vehicleStubRequest);
                pendingRequestInfo.add(pendingRequest);
                if (outRequestInfo != null) {
                    outRequestInfo.add(pendingRequest);
                }
            }
            mPendingAsyncRequests.addPendingRequests(pendingRequestInfo);
        }
        return vehicleStubRequests;
    }

    VehicleStubCallback createVehicleStubCallback(
            IAsyncPropertyResultCallback asyncPropertyResultCallback) {
        IBinder asyncPropertyResultBinder = asyncPropertyResultCallback.asBinder();
        VehicleStubCallback callback;
        synchronized (mLock) {
            if (mResultBinderToVehicleStubCallback.get(asyncPropertyResultBinder) == null) {
                callback = new VehicleStubCallback(asyncPropertyResultCallback);
                try {
                    callback.linkToDeath(() -> onBinderDied(asyncPropertyResultBinder));
                } catch (RemoteException e) {
                    throw new IllegalStateException("Linking to binder death recipient failed, "
                            + "the client might already died", e);
                }
                mResultBinderToVehicleStubCallback.put(asyncPropertyResultBinder, callback);
            } else {
                callback = mResultBinderToVehicleStubCallback.get(asyncPropertyResultBinder);
            }
        }
        return callback;
    }

    private void sendVehicleStubRequests(@AsyncRequestType int requestType,
            List<AsyncGetSetRequest> vehicleStubRequests, VehicleStubCallback callback) {
        switch (requestType) {
            case GET: // fallthrough
            case GET_INITIAL_VALUE_FOR_SET:
                mVehicleHal.getAsync(vehicleStubRequests, callback);
                break;
            case SET:
                mVehicleHal.setAsync(vehicleStubRequests, callback);
                break;
        }
    }

    /**
     * Queries CarPropertyValue with list of AsyncPropertyServiceRequest objects.
     *
     * <p>This method gets the CarPropertyValue using async methods. </p>
     */
    public void getCarPropertyValuesAsync(
            List<AsyncPropertyServiceRequest> serviceRequests,
            IAsyncPropertyResultCallback asyncPropertyResultCallback,
            long timeoutInMs) {
        VehicleStubCallback vehicleStubCallback = createVehicleStubCallback(
                asyncPropertyResultCallback);
        List<AsyncGetSetRequest> vehicleStubRequests = prepareVehicleStubRequests(
                GET, serviceRequests, timeoutInMs, vehicleStubCallback,
                /* assocSetValueRequestInfo= */ null, /* outRequestInfo= */ null);
        sendVehicleStubRequests(GET, vehicleStubRequests, vehicleStubCallback);
    }

    private static float sanitizeUpdateRateHz(float updateRateHz, HalPropConfig halPropConfig) {
        float sanitizedUpdateRateHz = updateRateHz;
        if (halPropConfig.getChangeMode()
                != CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) {
            sanitizedUpdateRateHz = CarPropertyManager.SENSOR_RATE_ONCHANGE;
        } else if (sanitizedUpdateRateHz > halPropConfig.getMaxSampleRate()) {
            sanitizedUpdateRateHz = halPropConfig.getMaxSampleRate();
        } else if (sanitizedUpdateRateHz < halPropConfig.getMinSampleRate()) {
            sanitizedUpdateRateHz = halPropConfig.getMinSampleRate();
        }
        return sanitizedUpdateRateHz;
    }

    @GuardedBy("mLock")
    @Nullable
    private Float calcNewUpdateRateHzLocked(int halPropId) {
        Float maxUpdateRateHz = null;
        List<AsyncPropRequestInfo> requests = mHalPropIdToWaitingUpdateRequestInfo.get(halPropId);
        if (requests != null) {
            for (int i = 0; i < requests.size(); i++) {
                float currentUpdateRateHz = requests.get(i).getUpdateRateHz();
                if (maxUpdateRateHz == null || currentUpdateRateHz > maxUpdateRateHz) {
                    maxUpdateRateHz = currentUpdateRateHz;
                }
            }
        }
        Float subscribedUpdateRateHz = mSubscribedHalPropIdToUpdateRateHz.get(halPropId);
        if (subscribedUpdateRateHz != null && (
                maxUpdateRateHz == null || subscribedUpdateRateHz > maxUpdateRateHz)) {
            maxUpdateRateHz = subscribedUpdateRateHz;
        }
        return maxUpdateRateHz;
    }

    private <T> List<T> filterWaitForUpdateRequests(List<T> requests,
            Function<T, Boolean> isWaitForPropertyUpdate) {
        List<T> waitForUpdateSetRequests = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            if (isWaitForPropertyUpdate.apply(requests.get(i))) {
                waitForUpdateSetRequests.add(requests.get(i));
            }
        }
        return waitForUpdateSetRequests;
    }

    /**
     * For every pending async set request that needs to wait for property update, generates an
     * async get initial value request and subscribes to the property update event.
     */
    private void sendGetInitialValueAndSubscribeUpdateEvent(
            List<AsyncPropertyServiceRequest> serviceRequests,
            VehicleStubCallback vehicleStubCallback, long timeoutInMs,
            List<AsyncPropRequestInfo> waitForUpdateSetRequestInfo) {
        // Stores a list of async GET_INITIAL_VALUE request to be sent.
        List<AsyncGetSetRequest> getInitValueRequests = prepareVehicleStubRequests(
                GET_INITIAL_VALUE_FOR_SET, serviceRequests, timeoutInMs,
                vehicleStubCallback, /* assocSetValueRequestInfo= */ waitForUpdateSetRequestInfo,
                /* outRequestInfo= */ null);
        Set<Integer> updatedHalPropIds = new ArraySet<>();

        // Subscribe to the property's change events before setting the property.
        synchronized (mLock) {
            for (AsyncPropRequestInfo setRequestInfo : waitForUpdateSetRequestInfo) {
                int halPropId = managerToHalPropId(setRequestInfo.getPropertyId());
                // We already checked in {@code carPropertyValueToHalPropValueLocked} inside
                // {@code prepareVehicleStubRequests}, this is guaranteed not to be null.
                HalPropConfig halPropConfig = mHalPropIdToPropConfig.get(halPropId);

                setRequestInfo.parseClientUpdateRateHz(halPropConfig);

                if (mHalPropIdToWaitingUpdateRequestInfo.get(halPropId) == null) {
                    mHalPropIdToWaitingUpdateRequestInfo.put(halPropId, new ArrayList<>());
                }

                mHalPropIdToWaitingUpdateRequestInfo.get(halPropId).add(setRequestInfo);

                updatedHalPropIds.add(halPropId);
            }
            updateSubscriptionRateLocked(updatedHalPropIds);
        }

        sendVehicleStubRequests(GET_INITIAL_VALUE_FOR_SET, getInitValueRequests,
                vehicleStubCallback);
    }

    /**
     * Sets car property values asynchronously.
     */
    public void setCarPropertyValuesAsync(
            List<AsyncPropertyServiceRequest> serviceRequests,
            IAsyncPropertyResultCallback asyncPropertyResultCallback,
            long timeoutInMs) {
        List<AsyncPropRequestInfo> pendingSetRequestInfo = new ArrayList<>();
        VehicleStubCallback vehicleStubCallback = createVehicleStubCallback(
                asyncPropertyResultCallback);
        List<AsyncGetSetRequest> setValueRequests = prepareVehicleStubRequests(
                SET, serviceRequests, timeoutInMs, vehicleStubCallback,
                 /* assocSetValueRequestInfo= */ null, /* outRequestInfo= */ pendingSetRequestInfo);
        List<AsyncPropRequestInfo> waitForUpdateSetRequestInfo = filterWaitForUpdateRequests(
                pendingSetRequestInfo, (request) -> request.isWaitForPropertyUpdate());

        if (waitForUpdateSetRequestInfo.size() != 0) {
            List<AsyncPropertyServiceRequest> waitForUpdateServiceRequests =
                    filterWaitForUpdateRequests(serviceRequests,
                            (request) -> request.isWaitForPropertyUpdate());
            sendGetInitialValueAndSubscribeUpdateEvent(waitForUpdateServiceRequests,
                    vehicleStubCallback, timeoutInMs, waitForUpdateSetRequestInfo);
        }

        sendVehicleStubRequests(SET, setValueRequests, vehicleStubCallback);
    }

    /**
     * Maps managerRequestIds to serviceRequestIds and remove them from the pending request map.
     */
    public void cancelRequests(int[] managerRequestIds) {
        List<Integer> serviceRequestIdsToCancel = new ArrayList<>();
        Set<Integer> managerRequestIdsSet = new ArraySet<>();
        for (int i = 0; i < managerRequestIds.length; i++) {
            managerRequestIdsSet.add(managerRequestIds[i]);
        }
        synchronized (mLock) {
            for (int i = 0; i < mPendingAsyncRequests.size(); i++) {
                // For GET_INITIAL_VALUE request, they have the same manager request ID as their
                // associated async set request. While cancelling the async set request, they will
                // be cancelled as well, see {@link cleanupPendingAsyncSetRequestLocked}, so no need
                // to cancel them here.
                AsyncPropRequestInfo propRequestInfo = mPendingAsyncRequests.valueAt(i);
                if (managerRequestIdsSet.contains(propRequestInfo.getManagerRequestId())
                        && propRequestInfo.getRequestType() != GET_INITIAL_VALUE_FOR_SET) {
                    serviceRequestIdsToCancel.add((int) mPendingAsyncRequests.keyAt(i));
                }
            }
            cancelRequestsByServiceRequestIdsLocked(serviceRequestIdsToCancel);
        }
        if (!serviceRequestIdsToCancel.isEmpty()) {
            mVehicleHal.cancelRequests(serviceRequestIdsToCancel);
        }
    }

    private void onBinderDied(IBinder binder) {
        List<Integer> serviceRequestIdsToCancel = new ArrayList<>();
        synchronized (mLock) {
            mResultBinderToVehicleStubCallback.remove(binder);
            for (int i = 0; i < mPendingAsyncRequests.size(); i++) {
                AsyncPropRequestInfo clientRequestInfo = mPendingAsyncRequests.valueAt(i);
                if (clientRequestInfo.getVehicleStubCallback().getClientBinder() != binder) {
                    continue;
                }
                serviceRequestIdsToCancel.add((int) mPendingAsyncRequests.keyAt(i));
            }
            cancelRequestsByServiceRequestIdsLocked(serviceRequestIdsToCancel);
        }
        if (!serviceRequestIdsToCancel.isEmpty()) {
            mVehicleHal.cancelRequests(serviceRequestIdsToCancel);
        }
    }

    @GuardedBy("mLock")
    private void cancelRequestsByServiceRequestIdsLocked(List<Integer> serviceRequestIdsToCancel) {
        if (serviceRequestIdsToCancel.isEmpty()) {
            return;
        }
        Set<Integer> updatedHalPropIds = new ArraySet<>();
        for (int i = 0; i < serviceRequestIdsToCancel.size(); i++) {
            Slogf.w(TAG, "the request for propertyHalService request ID: %d is cancelled",
                    serviceRequestIdsToCancel.get(i));
            getAndRemovePendingAsyncPropRequestInfoLocked(
                    serviceRequestIdsToCancel.get(i), updatedHalPropIds);
        }
        updateSubscriptionRateLocked(updatedHalPropIds);
    }

    @GuardedBy("mLock")
    private HalPropValue carPropertyValueToHalPropValueLocked(CarPropertyValue carPropertyValue) {
        int mgrPropId = carPropertyValue.getPropertyId();
        int halPropId = managerToHalPropId(mgrPropId);
        HalPropConfig halPropConfig = mHalPropIdToPropConfig.get(halPropId);
        if (halPropConfig == null) {
            throw new IllegalArgumentException("Property ID: " + mgrPropId + " is not supported");
        }
        return mPropValueBuilder.build(carPropertyValue, halPropId, halPropConfig);
    }

    private String halPropIdToName(int halPropId) {
        return VehiclePropertyIds.toString(halToManagerPropId(halPropId));
    }

    /**
     * Get the pending async requests size.
     *
     * For test only.
     */
    public int countPendingAsyncRequests() {
        synchronized (mLock) {
            return mPendingAsyncRequests.size();
        }
    }

    /**
     * Get the size of the map from hal prop ID to pending async set value requests.
     *
     * For test only.
     */
    public int countHalPropIdToWaitForUpdateRequests() {
        synchronized (mLock) {
            return mHalPropIdToWaitingUpdateRequestInfo.size();
        }
    }
}
