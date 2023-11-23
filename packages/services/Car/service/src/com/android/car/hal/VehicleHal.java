/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.os.SystemClock.uptimeMillis;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.CheckResult;
import android.annotation.Nullable;
import android.car.VehiclePropertyIds;
import android.car.builtin.os.TraceHelper;
import android.car.builtin.util.Slogf;
import android.content.Context;
import android.hardware.automotive.vehicle.StatusCode;
import android.hardware.automotive.vehicle.SubscribeOptions;
import android.hardware.automotive.vehicle.VehiclePropError;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.VehiclePropertyChangeMode;
import android.hardware.automotive.vehicle.VehiclePropertyStatus;
import android.hardware.automotive.vehicle.VehiclePropertyType;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.CarServiceUtils;
import com.android.car.CarSystemService;
import com.android.car.VehicleStub;
import com.android.car.VehicleStub.SubscriptionClient;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.internal.util.Lists;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Abstraction for vehicle HAL. This class handles interface with native HAL and does basic parsing
 * of received data (type check). Then each event is sent to corresponding {@link HalServiceBase}
 * implementation. It is the responsibility of {@link HalServiceBase} to convert data to
 * corresponding Car*Service for Car*Manager API.
 */
public class VehicleHal implements VehicleHalCallback, CarSystemService {

    private static final boolean DBG = false;
    private static final long TRACE_TAG = TraceHelper.TRACE_TAG_CAR_SERVICE;

    /**
     * Used in {@link VehicleHal#dumpPropValue} method when copying
     * {@link HalPropValue}.
     */
    private static final int MAX_BYTE_SIZE = 20;

    public static final int NO_AREA = -1;
    public static final float NO_SAMPLE_RATE = -1;

    /**
     * If call to vehicle HAL returns StatusCode.TRY_AGAIN, we will retry to invoke that method
     * again for this amount of milliseconds.
     */
    private static final int MAX_DURATION_FOR_RETRIABLE_RESULT_MS = 2000;

    private static final int SLEEP_BETWEEN_RETRIABLE_INVOKES_MS = 100;
    private static final float PRECISION_THRESHOLD = 0.001f;

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final SubscriptionClient mSubscriptionClient;

    private final PowerHalService mPowerHal;
    private final PropertyHalService mPropertyHal;
    private final InputHalService mInputHal;
    private final VmsHalService mVmsHal;
    private final UserHalService mUserHal;
    private final DiagnosticHalService mDiagnosticHal;
    private final ClusterHalService mClusterHalService;
    private final EvsHalService mEvsHal;
    private final TimeHalService mTimeHalService;
    private final HalPropValueBuilder mPropValueBuilder;
    private final VehicleStub mVehicleStub;

    private final Object mLock = new Object();

    // Only changed for test.
    private int mMaxDurationForRetryMs = MAX_DURATION_FOR_RETRIABLE_RESULT_MS;
    // Only changed for test.
    private int mSleepBetweenRetryMs = SLEEP_BETWEEN_RETRIABLE_INVOKES_MS;

    /** Stores handler for each HAL property. Property events are sent to handler. */
    @GuardedBy("mLock")
    private final SparseArray<HalServiceBase> mPropertyHandlers = new SparseArray<>();
    /** This is for iterating all HalServices with fixed order. */
    @GuardedBy("mLock")
    private final List<HalServiceBase> mAllServices;
    @GuardedBy("mLock")
    private final ArrayMap<Pair<Integer, Integer>, Float> mUpdateRateByPropIdAreadId =
            new ArrayMap<>();
    @GuardedBy("mLock")
    private final SparseArray<HalPropConfig> mAllProperties = new SparseArray<>();

    @GuardedBy("mLock")
    private final SparseArray<VehiclePropertyEventInfo> mEventLog = new SparseArray<>();

    // Used by injectVHALEvent for testing purposes.  Delimiter for an array of data
    private static final String DATA_DELIMITER = ",";

    /**
     * Constructs a new {@link VehicleHal} object given the {@link Context} and {@link IVehicle}
     * both passed as parameters.
     */
    public VehicleHal(Context context, VehicleStub vehicle) {
        this(context, /* powerHal= */ null, /* propertyHal= */ null,
                /* inputHal= */ null, /* vmsHal= */ null, /* userHal= */ null,
                /* diagnosticHal= */ null, /* clusterHalService= */ null,
                /* timeHalService= */ null,
                CarServiceUtils.getHandlerThread(VehicleHal.class.getSimpleName()), vehicle);
    }

    /**
     * Constructs a new {@link VehicleHal} object given the services passed as parameters.
     * This method must be used by tests only.
     */
    @VisibleForTesting
    VehicleHal(Context context,
            PowerHalService powerHal,
            PropertyHalService propertyHal,
            InputHalService inputHal,
            VmsHalService vmsHal,
            UserHalService userHal,
            DiagnosticHalService diagnosticHal,
            ClusterHalService clusterHalService,
            TimeHalService timeHalService,
            HandlerThread handlerThread,
            VehicleStub vehicle) {
        // Must be initialized before HalService so that HalService could use this.
        mPropValueBuilder = vehicle.getHalPropValueBuilder();
        mHandlerThread = handlerThread;
        mHandler = new Handler(mHandlerThread.getLooper());
        mPowerHal = powerHal != null ? powerHal : new PowerHalService(context, this);
        mPropertyHal = propertyHal != null ? propertyHal : new PropertyHalService(this);
        mInputHal = inputHal != null ? inputHal : new InputHalService(this);
        mVmsHal = vmsHal != null ? vmsHal : new VmsHalService(context, this);
        mUserHal = userHal != null ? userHal :  new UserHalService(this);
        mDiagnosticHal = diagnosticHal != null ? diagnosticHal : new DiagnosticHalService(this);
        mClusterHalService = clusterHalService != null
                ? clusterHalService : new ClusterHalService(this);
        mEvsHal = new EvsHalService(this);
        mTimeHalService = timeHalService != null
                ? timeHalService : new TimeHalService(context, this);
        mAllServices = List.of(
                mPowerHal,
                mInputHal,
                mDiagnosticHal,
                mVmsHal,
                mUserHal,
                mClusterHalService,
                mEvsHal,
                mTimeHalService,
                // mPropertyHal must be the last so that on init/release it can be used for all
                // other HAL services properties.
                mPropertyHal);
        mVehicleStub = vehicle;
        mSubscriptionClient = vehicle.newSubscriptionClient(this);
    }

    @VisibleForTesting
    void setMaxDurationForRetryMs(int maxDurationForRetryMs) {
        mMaxDurationForRetryMs = maxDurationForRetryMs;
    }

    @VisibleForTesting
    void setSleepBetweenRetryMs(int sleepBetweenRetryMs) {
        mSleepBetweenRetryMs = sleepBetweenRetryMs;
    }

    private void fetchAllPropConfigs() {
        synchronized (mLock) {
            if (mAllProperties.size() != 0) { // already set
                Slogf.i(CarLog.TAG_HAL, "fetchAllPropConfigs already fetched");
                return;
            }
        }
        HalPropConfig[] configs;
        try {
            configs = mVehicleStub.getAllPropConfigs();
            if (configs == null || configs.length == 0) {
                Slogf.e(CarLog.TAG_HAL, "getAllPropConfigs returned empty configs");
                return;
            }
        } catch (RemoteException | ServiceSpecificException e) {
            throw new RuntimeException("Unable to retrieve vehicle property configuration", e);
        }

        synchronized (mLock) {
            // Create map of all properties
            for (HalPropConfig p : configs) {
                if (DBG) {
                    Slogf.i(CarLog.TAG_HAL, "Add config for prop: 0x%x config: %s", p.getPropId(),
                            p.toString());
                }
                mAllProperties.put(p.getPropId(), p);
            }
        }
    }

    private void handleOnPropertyEvent(List<HalPropValue> propValues) {
        synchronized (mLock) {
            for (int i = 0; i < propValues.size(); i++) {
                HalPropValue v = propValues.get(i);
                int propId = v.getPropId();
                HalServiceBase service = mPropertyHandlers.get(propId);
                if (service == null) {
                    Slogf.e(CarLog.TAG_HAL,
                            "handleOnPropertyEvent: HalService not found for prop: 0x%x", propId);
                    continue;
                }
                service.getDispatchList().add(v);
                mServicesToDispatch.add(service);
                VehiclePropertyEventInfo info = mEventLog.get(propId);
                if (info == null) {
                    info = new VehiclePropertyEventInfo(v);
                    mEventLog.put(propId, info);
                } else {
                    info.addNewEvent(v);
                }
            }
        }
        for (HalServiceBase s : mServicesToDispatch) {
            s.onHalEvents(s.getDispatchList());
            s.getDispatchList().clear();
        }
        mServicesToDispatch.clear();
    }

    private void handleOnPropertySetError(List<VehiclePropError> errors) {
        SparseArray<ArrayList<VehiclePropError>> errorsByPropId =
                new SparseArray<ArrayList<VehiclePropError>>();
        for (int i = 0; i < errors.size(); i++) {
            VehiclePropError error = errors.get(i);
            int errorCode = error.errorCode;
            int propId = error.propId;
            int areaId = error.areaId;
            Slogf.w(CarLog.TAG_HAL, "onPropertySetError, errorCode: %d, prop: 0x%x, area: 0x%x",
                    errorCode, propId, areaId);
            if (propId == VehicleProperty.INVALID) {
                continue;
            }

            ArrayList<VehiclePropError> propErrors;
            if (errorsByPropId.get(propId) == null) {
                propErrors = new ArrayList<VehiclePropError>();
                errorsByPropId.put(propId, propErrors);
            } else {
                propErrors = errorsByPropId.get(propId);
            }
            propErrors.add(error);
        }

        for (int i = 0; i < errorsByPropId.size(); i++) {
            int propId = errorsByPropId.keyAt(i);
            HalServiceBase service;
            synchronized (mLock) {
                service = mPropertyHandlers.get(propId);
            }
            if (service == null) {
                Slogf.e(CarLog.TAG_HAL,
                        "handleOnPropertySetError: HalService not found for prop: 0x%x", propId);
                continue;
            }

            ArrayList<VehiclePropError> propErrors = errorsByPropId.get(propId);
            service.onPropertySetError(propErrors);
        }
    }

    private static String errorMessage(String action, HalPropValue propValue, String errorMsg) {
        return String.format("Failed to %s value for: 0x%x, areaId: 0x%x, error: %s", action,
                propValue.getPropId(), propValue.getAreaId(), errorMsg);
    }

    private HalPropValue getValueWithRetry(HalPropValue value) {
        return getValueWithRetry(value, /* maxRetries= */ 0);
    }

    private HalPropValue getValueWithRetry(HalPropValue value, int maxRetries) {
        HalPropValue result;
        Trace.traceBegin(TRACE_TAG, "VehicleStub#getValueWithRetry");
        try {
            result = invokeRetriable((requestValue) -> {
                Trace.traceBegin(TRACE_TAG, "VehicleStub#get");
                try {
                    return mVehicleStub.get(requestValue);
                } finally {
                    Trace.traceEnd(TRACE_TAG);
                }
            }, "get", value, mMaxDurationForRetryMs, mSleepBetweenRetryMs, maxRetries);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }

        if (result == null) {
            // If VHAL returns null result, but the status is OKAY. We treat that as NOT_AVAILABLE.
            throw new ServiceSpecificException(StatusCode.NOT_AVAILABLE,
                    errorMessage("get", value, "VHAL returns null for property value"));
        }
        return result;
    }

    private void setValueWithRetry(HalPropValue value)  {
        invokeRetriable((requestValue) -> {
            Trace.traceBegin(TRACE_TAG, "VehicleStub#set");
            mVehicleStub.set(requestValue);
            Trace.traceEnd(TRACE_TAG);
            return null;
        }, "set", value, mMaxDurationForRetryMs, mSleepBetweenRetryMs, /* maxRetries= */ 0);
    }

    /**
     * Inits the vhal configurations.
     */
    @Override
    public void init() {
        // nothing to init as everything was done on priorityInit
    }

    /**
     * PriorityInit for the vhal configurations.
     */
    public void priorityInit() {
        fetchAllPropConfigs();

        // PropertyHalService will take most properties, so make it big enough.
        ArrayMap<HalServiceBase, ArrayList<HalPropConfig>> configsForAllServices;
        synchronized (mLock) {
            configsForAllServices = new ArrayMap<>(mAllServices.size());
            for (int i = 0; i < mAllServices.size(); i++) {
                ArrayList<HalPropConfig> configsForService = new ArrayList();
                HalServiceBase service = mAllServices.get(i);
                configsForAllServices.put(service, configsForService);
                int[] supportedProps = service.getAllSupportedProperties();
                if (supportedProps.length == 0) {
                    for (int j = 0; j < mAllProperties.size(); j++) {
                        Integer propId = mAllProperties.keyAt(j);
                        if (service.isSupportedProperty(propId)) {
                            HalPropConfig config = mAllProperties.get(propId);
                            mPropertyHandlers.append(propId, service);
                            configsForService.add(config);
                        }
                    }
                } else {
                    for (int prop : supportedProps) {
                        HalPropConfig config = mAllProperties.get(prop);
                        if (config == null) {
                            continue;
                        }
                        mPropertyHandlers.append(prop, service);
                        configsForService.add(config);
                    }
                }
            }
        }

        for (Map.Entry<HalServiceBase, ArrayList<HalPropConfig>> entry
                : configsForAllServices.entrySet()) {
            HalServiceBase service = entry.getKey();
            ArrayList<HalPropConfig> configsForService = entry.getValue();
            service.takeProperties(configsForService);
            service.init();
        }
    }

    /**
     * Releases all connected services (power management service, input service, etc).
     */
    @Override
    public void release() {
        ArraySet<Integer> subscribedProperties = new ArraySet<>();
        synchronized (mLock) {
            // release in reverse order from init
            for (int i = mAllServices.size() - 1; i >= 0; i--) {
                mAllServices.get(i).release();
            }
            for (int i = 0; i < mUpdateRateByPropIdAreadId.size(); i++) {
                subscribedProperties.add(mUpdateRateByPropIdAreadId.keyAt(i).first);
            }
            mUpdateRateByPropIdAreadId.clear();
            mAllProperties.clear();
        }
        for (int i = 0; i < subscribedProperties.size(); i++) {
            try {
                mSubscriptionClient.unsubscribe(subscribedProperties.valueAt(i));
            } catch (RemoteException | ServiceSpecificException e) {
                //  Ignore exceptions on shutdown path.
                Slogf.w(CarLog.TAG_HAL, "Failed to unsubscribe", e);
            }
        }
        // keep the looper thread as should be kept for the whole life cycle.
    }

    public DiagnosticHalService getDiagnosticHal() {
        return mDiagnosticHal;
    }

    public PowerHalService getPowerHal() {
        return mPowerHal;
    }

    public PropertyHalService getPropertyHal() {
        return mPropertyHal;
    }

    public InputHalService getInputHal() {
        return mInputHal;
    }

    public UserHalService getUserHal() {
        return mUserHal;
    }

    public VmsHalService getVmsHal() {
        return mVmsHal;
    }

    public ClusterHalService getClusterHal() {
        return mClusterHalService;
    }

    public EvsHalService getEvsHal() {
        return mEvsHal;
    }

    public TimeHalService getTimeHalService() {
        return mTimeHalService;
    }

    public HalPropValueBuilder getHalPropValueBuilder() {
        return mPropValueBuilder;
    }

    @GuardedBy("mLock")
    private void assertServiceOwnerLocked(HalServiceBase service, int property) {
        if (service != mPropertyHandlers.get(property)) {
            throw new IllegalArgumentException(String.format(
                    "Property 0x%x  is not owned by service: %s", property, service));
        }
    }

    /**
     * Subscribes given properties with sampling rate defaults to 0 and no special flags provided.
     *
     * @throws IllegalArgumentException thrown if property is not supported by VHAL
     * @see #subscribeProperty(HalServiceBase, int, float)
     */
    public void subscribeProperty(HalServiceBase service, int property)
            throws IllegalArgumentException {
        subscribeProperty(service, property, /* samplingRateHz= */ 0f);
    }

    /**
     * Subscribe given property. Only Hal service owning the property can subscribe it.
     *
     * @param service HalService that owns this property
     * @param property property id (VehicleProperty)
     * @param samplingRateHz sampling rate in Hz for continuous properties
     * @throws IllegalArgumentException thrown if property is not supported by VHAL
     */
    public void subscribeProperty(HalServiceBase service, int property, float samplingRateHz)
            throws IllegalArgumentException {
        subscribeProperty(service, property, samplingRateHz, new int[0]);
    }

    /**
     * Subscribe given property. Only Hal service owning the property can subscribe it.
     *
     * @param service HalService that owns this property
     * @param property property id (VehicleProperty)
     * @param samplingRateHz sampling rate in Hz for continuous properties
     * @param areaIds The areaId that is being subscribed to, if empty subscribe to all areas
     * @throws IllegalArgumentException thrown if property is not supported by VHAL
     */
    public void subscribeProperty(HalServiceBase service, int property, float samplingRateHz,
            int[] areaIds) {
        if (DBG) {
            Slogf.d(CarLog.TAG_HAL, "subscribeProperty, service, areaIds, SamplingRateHz:"
                    + toCarPropertyLog(property) + ", " + service + ", "
                    + CarServiceUtils.asList(areaIds) + ", " + samplingRateHz);
        }
        HalPropConfig config;
        synchronized (mLock) {
            config = mAllProperties.get(property);
        }

        if (config == null) {
            throw new IllegalArgumentException(
                    String.format("subscribe error: config is null for property 0x%x", property));
        } else if (isPropertySubscribable(config)) {
            if (areaIds.length == 0) {
                areaIds = getAllAreaIdsFromPropertyId(config);
            }
            SubscribeOptions opts = new SubscribeOptions();
            opts.propId = property;
            opts.sampleRate = samplingRateHz;
            int[] filteredAreaIds = checkAlreadySubscribed(property, areaIds, samplingRateHz);
            opts.areaIds = filteredAreaIds;
            if (opts.areaIds.length == 0) {
                Slogf.w(CarLog.TAG_HAL, "property: " + VehiclePropertyIds.toString(property)
                        + " is already subscribed at rate: " + samplingRateHz + " hz");
                return;
            }
            synchronized (mLock) {
                assertServiceOwnerLocked(service, property);
                for (int i = 0; i < filteredAreaIds.length; i++) {
                    mUpdateRateByPropIdAreadId.put(Pair.create(property,
                                    filteredAreaIds[i]), samplingRateHz);
                }
            }
            try {
                mSubscriptionClient.subscribe(new SubscribeOptions[]{opts});
            } catch (RemoteException | ServiceSpecificException e) {
                Slogf.w(CarLog.TAG_HAL, "Failed to subscribe to " + toCarPropertyLog(property),
                        e);
            }
        } else {
            Slogf.w(CarLog.TAG_HAL, "Cannot subscribe to " + toCarPropertyLog(property));
        }
    }

    private int[] checkAlreadySubscribed(int property, int[] areaIds, float sampleRateHz) {
        List<Integer> areaIdList = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < areaIds.length; i++) {
                Pair<Integer, Integer> propertyAndAreadId = Pair.create(property, areaIds[i]);
                Float savedSampleRateHz = mUpdateRateByPropIdAreadId.get(propertyAndAreadId);
                if (savedSampleRateHz != null
                        && savedSampleRateHz - sampleRateHz < PRECISION_THRESHOLD) {
                    continue;
                }
                areaIdList.add(areaIds[i]);
            }
        }
        return CarServiceUtils.toIntArray(areaIdList);
    }

    private int[] getAllAreaIdsFromPropertyId(HalPropConfig config) {
        HalAreaConfig[] allAreaConfigs = config.getAreaConfigs();
        if (allAreaConfigs.length == 0) {
            return new int[]{/* areaId= */ 0};
        }
        int[] areaId = new int[allAreaConfigs.length];
        for (int i = 0; i < allAreaConfigs.length; i++) {
            areaId[i] = allAreaConfigs[i].getAreaId();
        }
        return areaId;
    }

    /**
     * Unsubscribes from receiving notifications for the property and HAL services passed
     * as parameters.
     */
    public void unsubscribeProperty(HalServiceBase service, int property) {
        if (DBG) {
            Slogf.i(CarLog.TAG_HAL, "unsubscribeProperty, service:" + service
                    + ", " + toCarPropertyLog(property));
        }
        HalPropConfig config;
        synchronized (mLock) {
            config = mAllProperties.get(property);
        }

        if (config == null) {
            Slogf.w(CarLog.TAG_HAL, "unsubscribeProperty " + toCarPropertyLog(property)
                    + " does not exist");
        } else if (isPropertySubscribable(config)) {
            synchronized (mLock) {
                assertServiceOwnerLocked(service, property);
                int[] areaIds = getAllAreaIdsFromPropertyId(config);
                for (int i = 0; i < areaIds.length; i++) {
                    mUpdateRateByPropIdAreadId.remove(Pair.create(property, areaIds[i]));
                }
            }
            try {
                mSubscriptionClient.unsubscribe(property);
            } catch (RemoteException | ServiceSpecificException e) {
                Slogf.w(CarLog.TAG_SERVICE, "Failed to unsubscribe: "
                        + toCarPropertyLog(property), e);
            }
        } else {
            Slogf.w(CarLog.TAG_HAL, "Cannot unsubscribe " + toCarPropertyLog(property));
        }
    }

    /**
     * Indicates if the property passed as parameter is supported.
     */
    public boolean isPropertySupported(int propertyId) {
        synchronized (mLock) {
            return mAllProperties.contains(propertyId);
        }
    }

    /**
     * Gets given property with retries.
     *
     * <p>If getting the property fails after all retries, it will throw
     * {@code IllegalStateException}. If the property is not supported, it will simply return
     * {@code null}.
     */
    @Nullable
    public HalPropValue getIfSupportedOrFail(int propertyId, int maxRetries) {
        if (!isPropertySupported(propertyId)) {
            return null;
        }
        try {
            return getValueWithRetry(mPropValueBuilder.build(propertyId, NO_AREA), maxRetries);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * This works similar to {@link #getIfSupportedOrFail(int, int)} except that this can be called
     * before {@code init()} is called.
     *
     * <p>This call will check if requested vhal property is supported by querying directly to vhal
     * and can have worse performance. Use this only for accessing vhal properties before
     * {@code ICarImpl.init()} phase.
     */
    @Nullable
    public HalPropValue getIfSupportedOrFailForEarlyStage(int propertyId, int maxRetries) {
        fetchAllPropConfigs();
        return getIfSupportedOrFail(propertyId, maxRetries);
    }

    /**
     * Returns the property's {@link HalPropValue} for the property id passed as parameter and
     * not specified area.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    public HalPropValue get(int propertyId)
            throws IllegalArgumentException, ServiceSpecificException {
        return get(propertyId, NO_AREA);
    }

    /**
     * Returns the property's {@link HalPropValue} for the property id and area id passed as
     * parameters.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    public HalPropValue get(int propertyId, int areaId)
            throws IllegalArgumentException, ServiceSpecificException {
        if (DBG) {
            Slogf.i(CarLog.TAG_HAL, "get, " + toCarPropertyLog(propertyId)
                    + toCarAreaLog(areaId));
        }
        return getValueWithRetry(mPropValueBuilder.build(propertyId, areaId));
    }

    /**
     * Returns the property object value for the class and property id passed as parameter and
     * no area specified.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    public <T> T get(Class clazz, int propertyId)
            throws IllegalArgumentException, ServiceSpecificException {
        return get(clazz, propertyId, NO_AREA);
    }

    /**
     * Returns the property object value for the class, property id, and area id passed as
     * parameter.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    public <T> T get(Class clazz, int propertyId, int areaId)
            throws IllegalArgumentException, ServiceSpecificException {
        return get(clazz, mPropValueBuilder.build(propertyId, areaId));
    }

    /**
     * Returns the property object value for the class and requested property value passed as
     * parameter.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class clazz, HalPropValue requestedPropValue)
            throws IllegalArgumentException, ServiceSpecificException {
        HalPropValue propValue;
        propValue = getValueWithRetry(requestedPropValue);

        if (clazz == Long.class || clazz == long.class) {
            Long value = propValue.getInt64Value(0);
            return (T) value;
        } else if (clazz == Integer.class || clazz == int.class) {
            Integer value = propValue.getInt32Value(0);
            return (T) value;
        } else if (clazz == Boolean.class || clazz == boolean.class) {
            Boolean value = Boolean.valueOf(propValue.getInt32Value(0) == 1);
            return (T) value;
        } else if (clazz == Float.class || clazz == float.class) {
            Float value = propValue.getFloatValue(0);
            return (T) value;
        } else if (clazz == Long[].class) {
            int size = propValue.getInt64ValuesSize();
            Long[] longArray = new Long[size];
            for (int i = 0; i < size; i++) {
                longArray[i] = propValue.getInt64Value(i);
            }
            return (T) longArray;
        } else if (clazz == Integer[].class) {
            int size = propValue.getInt32ValuesSize();
            Integer[] intArray = new Integer[size];
            for (int i = 0; i < size; i++) {
                intArray[i] = propValue.getInt32Value(i);
            }
            return (T) intArray;
        } else if (clazz == Float[].class) {
            int size = propValue.getFloatValuesSize();
            Float[] floatArray = new Float[size];
            for (int i = 0; i < size; i++) {
                floatArray[i] = propValue.getFloatValue(i);
            }
            return (T) floatArray;
        } else if (clazz == long[].class) {
            int size = propValue.getInt64ValuesSize();
            long[] longArray = new long[size];
            for (int i = 0; i < size; i++) {
                longArray[i] = propValue.getInt64Value(i);
            }
            return (T) longArray;
        } else if (clazz == int[].class) {
            int size = propValue.getInt32ValuesSize();
            int[] intArray = new int[size];
            for (int i = 0; i < size; i++) {
                intArray[i] = propValue.getInt32Value(i);
            }
            return (T) intArray;
        } else if (clazz == float[].class) {
            int size = propValue.getFloatValuesSize();
            float[] floatArray = new float[size];
            for (int i = 0; i < size; i++) {
                floatArray[i] = propValue.getFloatValue(i);
            }
            return (T) floatArray;
        } else if (clazz == byte[].class) {
            return (T) propValue.getByteArray();
        } else if (clazz == String.class) {
            return (T) propValue.getStringValue();
        } else {
            throw new IllegalArgumentException("Unexpected type: " + clazz);
        }
    }

    /**
     * Returns the vehicle's {@link HalPropValue} for the requested property value passed
     * as parameter.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    public HalPropValue get(HalPropValue requestedPropValue)
            throws IllegalArgumentException, ServiceSpecificException {
        return getValueWithRetry(requestedPropValue);
    }

    /**
     * Set property.
     *
     * @throws IllegalArgumentException if argument is invalid
     * @throws ServiceSpecificException if VHAL returns error
     */
    public void set(HalPropValue propValue)
            throws IllegalArgumentException, ServiceSpecificException {
        setValueWithRetry(propValue);
    }

    @CheckResult
    HalPropValueSetter set(int propId) {
        return set(propId, NO_AREA);
    }

    @CheckResult
    HalPropValueSetter set(int propId, int areaId) {
        return new HalPropValueSetter(propId, areaId);
    }

    static boolean isPropertySubscribable(HalPropConfig config) {
        return (config.getAccess() & VehiclePropertyAccess.READ) != 0
                && (config.getChangeMode() != VehiclePropertyChangeMode.STATIC);
    }

    /**
     * Sets a property passed from the shell command.
     *
     * @param property Property ID in hex or decimal.
     * @param areaId Area ID
     * @param data Comma-separated value.
     */
    public void setPropertyFromCommand(int property, int areaId, String data,
            IndentingPrintWriter writer) throws IllegalArgumentException, ServiceSpecificException {
        long timestamp = SystemClock.elapsedRealtimeNanos();
        HalPropValue v = createPropValueForInjecting(mPropValueBuilder, property, areaId,
                List.of(data.split(DATA_DELIMITER)), timestamp);
        if (v == null) {
            throw new IllegalArgumentException("Unsupported property type: property=" + property
                    + ", areaId=" + areaId);
        }
        set(v);
    }

    private final ArraySet<HalServiceBase> mServicesToDispatch = new ArraySet<>();

    @Override
    public void onPropertyEvent(ArrayList<HalPropValue> propValues) {
        mHandler.post(() -> handleOnPropertyEvent(propValues));
    }

    @Override
    public void onPropertySetError(ArrayList<VehiclePropError> errors) {
        mHandler.post(() -> handleOnPropertySetError(errors));
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        synchronized (mLock) {
            writer.println("**dump HAL services**");
            for (int i = 0; i < mAllServices.size(); i++) {
                mAllServices.get(i).dump(writer);
            }
            // Dump all VHAL property configure.
            dumpPropertyConfigs(writer, -1);
            writer.printf("**All Events, now ns:%d**\n",
                    SystemClock.elapsedRealtimeNanos());
            for (int i = 0; i < mEventLog.size(); i++) {
                VehiclePropertyEventInfo info = mEventLog.valueAt(i);
                writer.printf("event count:%d, lastEvent: ", info.mEventCount);
                dumpPropValue(writer, info.mLastEvent);
            }
            writer.println("**Property handlers**");
            for (int i = 0; i < mPropertyHandlers.size(); i++) {
                int propId = mPropertyHandlers.keyAt(i);
                HalServiceBase service = mPropertyHandlers.valueAt(i);
                writer.printf("Property Id: %d // 0x%x name: %s, service: %s\n", propId, propId,
                        VehiclePropertyIds.toString(propId), service);
            }
        }
    }

     /**
     * Dumps or debug VHAL.
     */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dumpVhal(ParcelFileDescriptor fd, List<String> options) throws RemoteException {
        mVehicleStub.dump(fd.getFileDescriptor(), options);
    }

    /**
     * Dumps the list of HALs.
     */
    public void dumpListHals(PrintWriter writer) {
        synchronized (mLock) {
            for (int i = 0; i < mAllServices.size(); i++) {
                writer.println(mAllServices.get(i).getClass().getName());
            }
        }
    }

    /**
     * Dumps the given HALs.
     */
    public void dumpSpecificHals(PrintWriter writer, String... halNames) {
        synchronized (mLock) {
            Map<String, HalServiceBase> byName = mAllServices.stream()
                    .collect(Collectors.toMap(s -> s.getClass().getSimpleName(), s -> s));
            for (String halName : halNames) {
                HalServiceBase service = byName.get(halName);
                if (service == null) {
                    writer.printf("No HAL named %s. Valid options are: %s\n",
                            halName, byName.keySet());
                    continue;
                }
                service.dump(writer);
            }
        }
    }

    /**
     * Dumps vehicle property values.
     *
     * @param propId property id, dump all properties' value if it is empty string
     * @param areaId areaId of the property, dump the property for all areaIds in the config
     *               if it is empty string
     */
    public void dumpPropertyValueByCommand(PrintWriter writer, int propId, int areaId) {
        if (propId == -1) {
            writer.println("**All property values**");
            synchronized (mLock) {
                for (int i = 0; i < mAllProperties.size(); i++) {
                    HalPropConfig config = mAllProperties.valueAt(i);
                    dumpPropertyValueByConfig(writer, config);
                }
            }
        } else if (areaId == -1) {
            synchronized (mLock) {
                HalPropConfig config = mAllProperties.get(propId);
                if (config == null) {
                    writer.print("Property ");
                    dumpPropHelper(writer, propId);
                    writer.print(" not supported by HAL\n");
                    return;
                }
                dumpPropertyValueByConfig(writer, config);
            }
        } else {
            try {
                HalPropValue value = get(propId, areaId);
                dumpPropValue(writer, value);
            } catch (RuntimeException e) {
                writer.printf("Can not get property value for property: %d // 0x%x "
                        + "in areaId: %d // 0x%x.\n", propId, propId, areaId, areaId);
            }
        }
    }

    /**
     * Gets all property configs from VHAL.
     */
    public HalPropConfig[] getAllPropConfigs() throws RemoteException, ServiceSpecificException {
        return mVehicleStub.getAllPropConfigs();
    }

    /**
     * Checks whether we are connected to AIDL VHAL: {@code true} or HIDL VHAL: {@code false}.
     */
    public boolean isAidlVhal() {
        return mVehicleStub.isAidlVhal();
    }

    /**
     * Checks if fake VHAL mode is enabled.
     *
     * @return {@code true} if car service is connected to FakeVehicleStub.
     */
    public boolean isFakeModeEnabled() {
        return mVehicleStub.isFakeModeEnabled();
    }

    private static void dumpPropHelper(PrintWriter pw, int propId) {
        pw.printf("Id: %d // 0x%x, name: %s ", propId, propId, VehiclePropertyIds.toString(propId));
    }

    private void dumpPropertyValueByConfig(PrintWriter writer, HalPropConfig config) {
        int propId = config.getPropId();
        HalAreaConfig[] areaConfigs = config.getAreaConfigs();
        if (areaConfigs == null || areaConfigs.length == 0) {
            try {
                HalPropValue value = get(config.getPropId());
                dumpPropValue(writer, value);
            } catch (RuntimeException e) {
                writer.printf("Can not get property value for property: %d // 0x%x,"
                        + " areaId: 0 \n", propId, propId);
            }
        } else {
            for (HalAreaConfig areaConfig : areaConfigs) {
                int areaId = areaConfig.getAreaId();
                try {
                    HalPropValue value = get(propId, areaId);
                    dumpPropValue(writer, value);
                } catch (RuntimeException e) {
                    writer.printf("Can not get property value for property: %d // 0x%x "
                            + "in areaId: %d // 0x%x\n", propId, propId, areaId, areaId);
                }
            }
        }
    }

    /**
     * Dump VHAL property configs.
     * Dump all properties if propid param is empty.
     *
     * @param propId the property ID
     */
    public void dumpPropertyConfigs(PrintWriter writer, int propId) {
        HalPropConfig[] configs;
        synchronized (mLock) {
            configs = new HalPropConfig[mAllProperties.size()];
            for (int i = 0; i < mAllProperties.size(); i++) {
                configs[i] = mAllProperties.valueAt(i);
            }
        }

        if (propId == -1) {
            writer.println("**All properties**");
            for (HalPropConfig config : configs) {
                dumpPropertyConfigsHelp(writer, config);
            }
            return;
        }
        for (HalPropConfig config : configs) {
            if (config.getPropId() == propId) {
                dumpPropertyConfigsHelp(writer, config);
                return;
            }
        }

    }

    /** Dumps VehiclePropertyConfigs */
    private static void dumpPropertyConfigsHelp(PrintWriter writer, HalPropConfig config) {
        int propId = config.getPropId();
        writer.printf("Property:0x%x, Property name:%s, access:0x%x, changeMode:0x%x, "
                        + "config:%s, fs min:%f, fs max:%f\n",
                propId, VehiclePropertyIds.toString(propId), config.getAccess(),
                config.getChangeMode(), Arrays.toString(config.getConfigArray()),
                config.getMinSampleRate(), config.getMaxSampleRate());
        if (config.getAreaConfigs() == null) {
            return;
        }
        for (HalAreaConfig area : config.getAreaConfigs()) {
            writer.printf("\tareaId:0x%x, f min:%f, f max:%f, i min:%d, i max:%d,"
                            + " i64 min:%d, i64 max:%d\n",
                    area.getAreaId(), area.getMinFloatValue(), area.getMaxFloatValue(),
                    area.getMinInt32Value(), area.getMaxInt32Value(), area.getMinInt64Value(),
                    area.getMaxInt64Value());
        }
    }

    /**
     * Inject a VHAL event
     *
     * @param property the Vehicle property Id as defined in the HAL
     * @param zone the zone that this event services
     * @param value the data value of the event
     * @param delayTime add a certain duration to event timestamp
     */
    public void injectVhalEvent(int property, int zone, String value, int delayTime)
            throws NumberFormatException {
        long timestamp = SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(delayTime);
        HalPropValue v = createPropValueForInjecting(mPropValueBuilder, property, zone,
                Arrays.asList(value.split(DATA_DELIMITER)), timestamp);
        if (v == null) {
            return;
        }
        mHandler.post(() -> handleOnPropertyEvent(Lists.newArrayList(v)));
    }

    /**
     * Injects continuous VHAL events.
     *
     * @param property the Vehicle property Id as defined in the HAL
     * @param zone the zone that this event services
     * @param value the data value of the event
     * @param sampleRate the sample rate for events in Hz
     * @param timeDurationInSec the duration for injecting events in seconds
     */
    public void injectContinuousVhalEvent(int property, int zone, String value,
            float sampleRate, long timeDurationInSec) {

        HalPropValue v = createPropValueForInjecting(mPropValueBuilder, property, zone,
                new ArrayList<>(Arrays.asList(value.split(DATA_DELIMITER))), 0);
        if (v == null) {
            return;
        }
        // rate in Hz
        if (sampleRate <= 0) {
            Slogf.e(CarLog.TAG_HAL, "Inject events at an invalid sample rate: " + sampleRate);
            return;
        }
        long period = (long) (1000 / sampleRate);
        long stopTime = timeDurationInSec * 1000 + SystemClock.elapsedRealtime();
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (stopTime < SystemClock.elapsedRealtime()) {
                    timer.cancel();
                    timer.purge();
                } else {
                    // Avoid the fake events be covered by real Event
                    long timestamp = SystemClock.elapsedRealtimeNanos()
                            + TimeUnit.SECONDS.toNanos(timeDurationInSec);
                    HalPropValue v = createPropValueForInjecting(mPropValueBuilder, property, zone,
                            new ArrayList<>(Arrays.asList(value.split(DATA_DELIMITER))), timestamp);
                    mHandler.post(() -> handleOnPropertyEvent(Lists.newArrayList(v)));
                }
            }
        }, /* delay= */0, period);
    }

    // Returns null if the property type is unsupported.
    @Nullable
    private static HalPropValue createPropValueForInjecting(HalPropValueBuilder builder,
            int propId, int zoneId, List<String> dataList, long timestamp) {
        int propertyType = propId & VehiclePropertyType.MASK;
        // Values can be comma separated list
        switch (propertyType) {
            case VehiclePropertyType.BOOLEAN:
                boolean boolValue = Boolean.parseBoolean(dataList.get(0));
                return builder.build(propId, zoneId, timestamp, VehiclePropertyStatus.AVAILABLE,
                        boolValue ? 1 : 0);
            case VehiclePropertyType.INT64:
            case VehiclePropertyType.INT64_VEC:
                long[] longValues = new long[dataList.size()];
                for (int i = 0; i < dataList.size(); i++) {
                    longValues[i] = Long.decode(dataList.get(i));
                }
                return builder.build(propId, zoneId, timestamp, VehiclePropertyStatus.AVAILABLE,
                        longValues);
            case VehiclePropertyType.INT32:
            case VehiclePropertyType.INT32_VEC:
                int[] intValues = new int[dataList.size()];
                for (int i = 0; i < dataList.size(); i++) {
                    intValues[i] = Integer.decode(dataList.get(i));
                }
                return builder.build(propId, zoneId, timestamp, VehiclePropertyStatus.AVAILABLE,
                        intValues);
            case VehiclePropertyType.FLOAT:
            case VehiclePropertyType.FLOAT_VEC:
                float[] floatValues = new float[dataList.size()];
                for (int i = 0; i < dataList.size(); i++) {
                    floatValues[i] = Float.parseFloat(dataList.get(i));
                }
                return builder.build(propId, zoneId, timestamp, VehiclePropertyStatus.AVAILABLE,
                        floatValues);
            default:
                Slogf.e(CarLog.TAG_HAL, "Property type unsupported:" + propertyType);
                return null;
        }
    }

    private static class VehiclePropertyEventInfo {
        private int mEventCount;
        private HalPropValue mLastEvent;

        private VehiclePropertyEventInfo(HalPropValue event) {
            mEventCount = 1;
            mLastEvent = event;
        }

        private void addNewEvent(HalPropValue event) {
            mEventCount++;
            mLastEvent = event;
        }
    }

    final class HalPropValueSetter {
        final int mPropId;
        final int mAreaId;

        private HalPropValueSetter(int propId, int areaId) {
            mPropId = propId;
            mAreaId = areaId;
        }

        /**
         * Set the property to the given value.
         *
         * @throws IllegalArgumentException if argument is invalid
         * @throws ServiceSpecificException if VHAL returns error
         */
        void to(boolean value) throws IllegalArgumentException, ServiceSpecificException {
            to(value ? 1 : 0);
        }

        /**
         * Set the property to the given value.
         *
         * @throws IllegalArgumentException if argument is invalid
         * @throws ServiceSpecificException if VHAL returns error
         */
        void to(int value) throws IllegalArgumentException, ServiceSpecificException {
            HalPropValue propValue = mPropValueBuilder.build(mPropId, mAreaId, value);
            submit(propValue);
        }

        /**
         * Set the property to the given values.
         *
         * @throws IllegalArgumentException if argument is invalid
         * @throws ServiceSpecificException if VHAL returns error
         */
        void to(int[] values) throws IllegalArgumentException, ServiceSpecificException {
            HalPropValue propValue = mPropValueBuilder.build(mPropId, mAreaId, values);
            submit(propValue);
        }

        /**
         * Set the property to the given values.
         *
         * @throws IllegalArgumentException if argument is invalid
         * @throws ServiceSpecificException if VHAL returns error
         */
        void to(Collection<Integer> values)
                throws IllegalArgumentException, ServiceSpecificException {
            int[] intValues = new int[values.size()];
            int i = 0;
            for (int value : values) {
                intValues[i] = value;
                i++;
            }
            HalPropValue propValue = mPropValueBuilder.build(mPropId, mAreaId, intValues);
            submit(propValue);
        }

        void submit(HalPropValue propValue)
                throws IllegalArgumentException, ServiceSpecificException {
            if (DBG) {
                Slogf.i(CarLog.TAG_HAL, "set, " + toCarPropertyLog(mPropId)
                        + toCarAreaLog(mAreaId));
            }
            setValueWithRetry(propValue);
        }
    }

    private static void dumpPropValue(PrintWriter writer, HalPropValue value) {
        String bytesString = "";
        byte[] byteValues = value.getByteArray();
        if (byteValues.length > MAX_BYTE_SIZE) {
            byte[] bytes = Arrays.copyOf(byteValues, MAX_BYTE_SIZE);
            bytesString = Arrays.toString(bytes);
        } else {
            bytesString = Arrays.toString(byteValues);
        }

        writer.printf("Property:0x%x, status: %d, timestamp: %d, zone: 0x%x, "
                        + "floatValues: %s, int32Values: %s, int64Values: %s, bytes: %s, string: "
                        + "%s\n",
                value.getPropId(), value.getStatus(), value.getTimestamp(), value.getAreaId(),
                value.dumpFloatValues(), value.dumpInt32Values(), value.dumpInt64Values(),
                bytesString, value.getStringValue());
    }

    private static String toCarPropertyLog(int propId) {
        return String.format("property Id: %d // 0x%x, property name: %s ", propId, propId,
                VehiclePropertyIds.toString(propId));
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    private static String toCarAreaLog(int areaId) {
        return String.format("areaId: %d // 0x%x", areaId, areaId);
    }

    interface RetriableAction {
        @Nullable HalPropValue run(HalPropValue requestValue)
                throws ServiceSpecificException, RemoteException;
    }

    private static HalPropValue invokeRetriable(RetriableAction action,
            String operation, HalPropValue requestValue, long maxDurationForRetryMs,
            long sleepBetweenRetryMs, int maxRetries)
            throws ServiceSpecificException, IllegalArgumentException {
        Retrier retrier = new Retrier(action, operation, requestValue, maxDurationForRetryMs,
                sleepBetweenRetryMs, maxRetries);
        return retrier.invokeAction();
    }

    private static final class Retrier {
        private final RetriableAction mAction;
        private final String mOperation;
        private final HalPropValue mRequestValue;
        private final long mMaxDurationForRetryMs;
        private final long mSleepBetweenRetryMs;
        private final int mMaxRetries;
        private final long mStartTime;
        private int mRetryCount = 0;

        Retrier(RetriableAction action,
                String operation, HalPropValue requestValue, long maxDurationForRetryMs,
                long sleepBetweenRetryMs, int maxRetries) {
            mAction = action;
            mOperation = operation;
            mRequestValue = requestValue;
            mMaxDurationForRetryMs = maxDurationForRetryMs;
            mSleepBetweenRetryMs = sleepBetweenRetryMs;
            mMaxRetries = maxRetries;
            mStartTime = uptimeMillis();
        }

        HalPropValue invokeAction()
                throws ServiceSpecificException, IllegalArgumentException {
            mRetryCount++;

            try {
                return mAction.run(mRequestValue);
            } catch (ServiceSpecificException e) {
                switch (e.errorCode) {
                    case StatusCode.INVALID_ARG:
                        throw new IllegalArgumentException(errorMessage(mOperation, mRequestValue,
                            e.toString()));
                    case StatusCode.TRY_AGAIN:
                        return sleepAndTryAgain(e);
                    default:
                        throw e;
                }
            } catch (RemoteException e) {
                return sleepAndTryAgain(e);
            }
        }

        private HalPropValue sleepAndTryAgain(Exception e)
                throws ServiceSpecificException, IllegalArgumentException {
            Slogf.d(CarLog.TAG_HAL, "trying the request: "
                    + toCarPropertyLog(mRequestValue.getPropId()) + ", "
                    + toCarAreaLog(mRequestValue.getAreaId()) + " again...");
            try {
                Thread.sleep(mSleepBetweenRetryMs);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                Slogf.w(CarLog.TAG_HAL, "Thread was interrupted while waiting for vehicle HAL.",
                        interruptedException);
                throw new ServiceSpecificException(StatusCode.INTERNAL_ERROR,
                        errorMessage(mOperation, mRequestValue, interruptedException.toString()));
            }

            if (mMaxRetries != 0) {
                // If mMaxRetries is specified, check the retry count.
                if (mMaxRetries == mRetryCount) {
                    throw new ServiceSpecificException(StatusCode.TRY_AGAIN,
                            errorMessage(mOperation, mRequestValue,
                                    "cannot get property after " + mRetryCount + " retires, "
                                    + "last exception: " + e));
                }
            } else if ((uptimeMillis() - mStartTime) >= mMaxDurationForRetryMs) {
                // Otherwise, check whether we have reached timeout.
                throw new ServiceSpecificException(StatusCode.TRY_AGAIN,
                        errorMessage(mOperation, mRequestValue,
                                "cannot get property within " + mMaxDurationForRetryMs
                                + "ms, last exception: " + e));
            }
            return invokeAction();
        }
    }


    /**
     * Queries HalPropValue with list of GetVehicleHalRequest objects.
     *
     * <p>This method gets the HalPropValue using async methods.
     */
    public void getAsync(List<VehicleStub.AsyncGetSetRequest> getVehicleStubAsyncRequests,
            VehicleStub.VehicleStubCallbackInterface getVehicleStubAsyncCallback) {
        mVehicleStub.getAsync(getVehicleStubAsyncRequests, getVehicleStubAsyncCallback);
    }

    /**
     * Sets vehicle property value asynchronously.
     */
    public void setAsync(List<VehicleStub.AsyncGetSetRequest> setVehicleStubAsyncRequests,
            VehicleStub.VehicleStubCallbackInterface setVehicleStubAsyncCallback) {
        mVehicleStub.setAsync(setVehicleStubAsyncRequests, setVehicleStubAsyncCallback);
    }

    /**
     * Cancels all the on-going async requests with the given request IDs.
     */
    public void cancelRequests(List<Integer> vehicleStubRequestIds) {
        mVehicleStub.cancelRequests(vehicleStubRequestIds);
    }
}
