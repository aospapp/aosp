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

package com.android.car;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;
import static com.android.car.internal.property.CarPropertyHelper.SYNC_OP_LIMIT_TRY_AGAIN;

import static java.lang.Integer.toHexString;
import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.builtin.os.TraceHelper;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarHvacFanDirection;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.AreaIdConfig;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CruiseControlType;
import android.car.hardware.property.ErrorState;
import android.car.hardware.property.EvStoppingMode;
import android.car.hardware.property.ICarProperty;
import android.car.hardware.property.ICarPropertyEventListener;
import android.car.hardware.property.WindshieldWipersSwitch;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.SparseArray;

import com.android.car.hal.PropertyHalService;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.property.AsyncPropertyServiceRequest;
import com.android.car.internal.property.AsyncPropertyServiceRequestList;
import com.android.car.internal.property.CarPropertyConfigList;
import com.android.car.internal.property.IAsyncPropertyResultCallback;
import com.android.car.internal.property.InputSanitizationUtils;
import com.android.car.internal.util.ArrayUtils;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * This class implements the binder interface for ICarProperty.aidl to make it easier to create
 * multiple managers that deal with Vehicle Properties. The property Ids in this class are IDs in
 * manager level.
 */
public class CarPropertyService extends ICarProperty.Stub
        implements CarServiceBase, PropertyHalService.PropertyHalListener {
    private static final boolean DBG = false;
    private static final String TAG = CarLog.tagFor(CarPropertyService.class);
    // Maximum count of sync get/set property operation allowed at once. The reason we limit this
    // is because each sync get/set property operation takes up one binder thread. If they take
    // all the binder thread, we do not have thread left for the result callback from VHAL. This
    // will cause all the pending sync operation to timeout because result cannot be delivered.
    private static final int SYNC_GET_SET_PROPERTY_OP_LIMIT = 16;
    private static final long TRACE_TAG = TraceHelper.TRACE_TAG_CAR_SERVICE;
    // A list of properties that must not set waitForPropertyUpdate to {@code true} for set async.
    private static final Set<Integer> NOT_ALLOWED_WAIT_FOR_UPDATE_PROPERTIES =
            new HashSet<>(Arrays.asList(
                VehiclePropertyIds.HVAC_TEMPERATURE_VALUE_SUGGESTION
            ));

    private static final Set<Integer> ERROR_STATES =
            new HashSet<Integer>(Arrays.asList(
                    ErrorState.OTHER_ERROR_STATE,
                    ErrorState.NOT_AVAILABLE_DISABLED,
                    ErrorState.NOT_AVAILABLE_SPEED_LOW,
                    ErrorState.NOT_AVAILABLE_SPEED_HIGH,
                    ErrorState.NOT_AVAILABLE_SAFETY
            ));
    private static final Set<Integer> CAR_HVAC_FAN_DIRECTION_UNWRITABLE_STATES =
            new HashSet<Integer>(Arrays.asList(
                    CarHvacFanDirection.UNKNOWN
            ));
    private static final Set<Integer> CRUISE_CONTROL_TYPE_UNWRITABLE_STATES =
            new HashSet<Integer>(Arrays.asList(
                    CruiseControlType.OTHER
            ));
    static {
        CRUISE_CONTROL_TYPE_UNWRITABLE_STATES.addAll(ERROR_STATES);
    }
    private static final Set<Integer> EV_STOPPING_MODE_UNWRITABLE_STATES =
            new HashSet<Integer>(Arrays.asList(
                    EvStoppingMode.STATE_OTHER
            ));
    private static final Set<Integer> WINDSHIELD_WIPERS_SWITCH_UNWRITABLE_STATES =
            new HashSet<Integer>(Arrays.asList(
                    WindshieldWipersSwitch.OTHER
            ));

    private static final SparseArray<Set<Integer>> PROPERTY_ID_TO_UNWRITABLE_STATES =
            new SparseArray<>();
    static {
        PROPERTY_ID_TO_UNWRITABLE_STATES.put(
                VehiclePropertyIds.CRUISE_CONTROL_TYPE,
                CRUISE_CONTROL_TYPE_UNWRITABLE_STATES);
        PROPERTY_ID_TO_UNWRITABLE_STATES.put(
                VehiclePropertyIds.EV_STOPPING_MODE,
                EV_STOPPING_MODE_UNWRITABLE_STATES);
        PROPERTY_ID_TO_UNWRITABLE_STATES.put(
                VehiclePropertyIds.HVAC_FAN_DIRECTION,
                CAR_HVAC_FAN_DIRECTION_UNWRITABLE_STATES);
        PROPERTY_ID_TO_UNWRITABLE_STATES.put(
                VehiclePropertyIds.WINDSHIELD_WIPERS_SWITCH,
                WINDSHIELD_WIPERS_SWITCH_UNWRITABLE_STATES);
    }

    private final Context mContext;
    private final PropertyHalService mPropertyHalService;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<IBinder, Client> mClientMap = new ArrayMap<>();
    @GuardedBy("mLock")
    private final SparseArray<List<Client>> mPropIdClientMap = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<SparseArray<Client>> mSetOperationClientMap = new SparseArray<>();
    private final HandlerThread mHandlerThread =
            CarServiceUtils.getHandlerThread(getClass().getSimpleName());
    private final Handler mHandler = new Handler(mHandlerThread.getLooper());
    // Use SparseArray instead of map to save memory.
    @GuardedBy("mLock")
    private SparseArray<CarPropertyConfig<?>> mPropertyIdToCarPropertyConfig = new SparseArray<>();
    @GuardedBy("mLock")
    private SparseArray<Pair<String, String>> mPropToPermission = new SparseArray<>();
    @GuardedBy("mLock")
    private int mSyncGetSetPropertyOpCount;

    public CarPropertyService(Context context, PropertyHalService propertyHalService) {
        if (DBG) {
            Slogf.d(TAG, "CarPropertyService started!");
        }
        mPropertyHalService = propertyHalService;
        mContext = context;
    }

    // Helper class to keep track of listeners to this service.
    private final class Client implements IBinder.DeathRecipient {
        private final ICarPropertyEventListener mListener;
        private final IBinder mListenerBinder;
        private final Object mLock = new Object();
        // propId->rate map.
        @GuardedBy("mLock")
        private final SparseArray<Float> mRateMap = new SparseArray<>();
        @GuardedBy("mLock")
        private boolean mIsDead = false;

        Client(ICarPropertyEventListener listener) {
            mListener = listener;
            mListenerBinder = listener.asBinder();

            try {
                mListenerBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                mIsDead = true;
            }
        }

        /**
         * Returns whether this client is already dead.
         *
         * Caller should not assume this client is alive after getting True response because the
         * binder might die after this function checks the status. Caller should only use this
         * function to fail early.
         */
        boolean isDead() {
            synchronized (mLock) {
                return mIsDead;
            }
        }

        void addProperty(int propId, float rate) {
            synchronized (mLock) {
                if (mIsDead) {
                    return;
                }
                mRateMap.put(propId, rate);
            }
        }

        float getRate(int propId) {
            synchronized (mLock) {
                // Return 0 if no key found, since that is the slowest rate.
                return mRateMap.get(propId, 0.0f);
            }
        }

        int removeProperty(int propId) {
            synchronized (mLock) {
                mRateMap.remove(propId);
                if (mRateMap.size() == 0) {
                    mListenerBinder.unlinkToDeath(this, 0);
                }
                return mRateMap.size();
            }
        }

        /**
         * Handler to be called when client died.
         *
         * Remove the listener from HAL service and unregister if this is the last client.
         */
        @Override
        public void binderDied() {
            List<Integer> propIds = new ArrayList<>();
            synchronized (mLock) {
                mIsDead = true;

                if (DBG) {
                    Slogf.d(TAG, "binderDied %s", mListenerBinder);
                }

                // Because we set mIsDead to true here, we are sure mRateMap would not have new
                // elements. The propIds here is going to cover all the prop Ids that we need to
                // unregister.
                for (int i = 0; i < mRateMap.size(); i++) {
                    propIds.add(mRateMap.keyAt(i));
                }
            }

            CarPropertyService.this.unregisterListenerBinderForProps(propIds, mListenerBinder);
        }

        /**
         * Calls onEvent function on the listener if the binder is alive.
         *
         * There is still chance when onEvent might fail because binderDied is not called before
         * this function.
         */
        void onEvent(List<CarPropertyEvent> events) throws RemoteException {
            synchronized (mLock) {
                if (mIsDead) {
                    return;
                }
            }
            mListener.onEvent(events);
        }
    }

    @Override
    public void init() {
        synchronized (mLock) {
            // Cache the configs list and permissions to avoid subsequent binder calls
            mPropertyIdToCarPropertyConfig = mPropertyHalService.getPropertyList();
            mPropToPermission = mPropertyHalService.getPermissionsForAllProperties();
            if (DBG) {
                Slogf.d(TAG, "cache CarPropertyConfigs " + mPropertyIdToCarPropertyConfig.size());
            }
        }
        mPropertyHalService.setPropertyHalListener(this);
    }

    @Override
    public void release() {
        synchronized (mLock) {
            mClientMap.clear();
            mPropIdClientMap.clear();
            mPropertyHalService.setPropertyHalListener(null);
            mSetOperationClientMap.clear();
        }
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.println("*CarPropertyService*");
        writer.increaseIndent();
        synchronized (mLock) {
            writer.println(String.format("There are %d clients using CarPropertyService.",
                    mClientMap.size()));
            writer.println("Current sync operation count: " + mSyncGetSetPropertyOpCount);
            writer.println("Properties registered: ");
            writer.increaseIndent();
            for (int i = 0; i < mPropIdClientMap.size(); i++) {
                int propId = mPropIdClientMap.keyAt(i);
                writer.println("propId: 0x" + toHexString(propId)
                        + " is registered by " + mPropIdClientMap.valueAt(i).size()
                        + " client(s).");
            }
            writer.decreaseIndent();
            writer.println("Properties changed by CarPropertyService: ");
            writer.increaseIndent();
            for (int i = 0; i < mSetOperationClientMap.size(); i++) {
                int propId = mSetOperationClientMap.keyAt(i);
                SparseArray areaIdToClient = mSetOperationClientMap.valueAt(i);
                for (int j = 0; j < areaIdToClient.size(); j++) {
                    int areaId = areaIdToClient.keyAt(j);
                    writer.println(String.format("propId: 0x%s areaId: 0x%s by client: %s",
                            toHexString(propId), toHexString(areaId), areaIdToClient.valueAt(j)));
                }
            }
            writer.decreaseIndent();
        }
        writer.decreaseIndent();
    }

    @Override
    public void registerListener(int propertyId, float updateRateHz,
            ICarPropertyEventListener iCarPropertyEventListener) throws IllegalArgumentException {
        requireNonNull(iCarPropertyEventListener);
        validateRegisterParameter(propertyId);

        CarPropertyConfig<?> carPropertyConfig = getCarPropertyConfig(propertyId);

        float sanitizedUpdateRateHz = InputSanitizationUtils.sanitizeUpdateRateHz(carPropertyConfig,
                updateRateHz);

        if (DBG) {
            Slogf.d(TAG, "registerListener: property ID=" + VehiclePropertyIds.toString(propertyId)
                    + " updateRateHz=" + sanitizedUpdateRateHz);
        }

        Client finalClient;
        synchronized (mLock) {
            // Get or create the client for this iCarPropertyEventListener
            IBinder listenerBinder = iCarPropertyEventListener.asBinder();
            Client client = mClientMap.get(listenerBinder);
            if (client == null) {
                client = new Client(iCarPropertyEventListener);
                if (client.isDead()) {
                    Slogf.w(TAG, "the ICarPropertyEventListener is already dead");
                    return;
                }
                mClientMap.put(listenerBinder, client);
            }
            client.addProperty(propertyId, sanitizedUpdateRateHz);
            // Insert the client into the propertyId --> clients map
            List<Client> clients = mPropIdClientMap.get(propertyId);
            if (clients == null) {
                clients = new ArrayList<>();
                mPropIdClientMap.put(propertyId, clients);
            }
            if (!clients.contains(client)) {
                clients.add(client);
            }
            // Set the new updateRateHz
            if (sanitizedUpdateRateHz > mPropertyHalService.getSubscribedUpdateRateHz(propertyId)) {
                mPropertyHalService.subscribeProperty(propertyId, sanitizedUpdateRateHz);
            }
            finalClient = client;
        }

        // propertyConfig and client are NonNull.
        mHandler.post(() ->
                getAndDispatchPropertyInitValue(carPropertyConfig, finalClient));
    }

    /**
     * Register property listener for car service's internal usage.
     */
    public boolean registerListenerSafe(int propertyId, float updateRateHz,
            ICarPropertyEventListener iCarPropertyEventListener) {
        try {
            registerListener(propertyId, updateRateHz, iCarPropertyEventListener);
            return true;
        } catch (Exception e) {
            Slogf.e(TAG, e, "registerListenerSafe() failed for property ID: %s updateRateHz: %f",
                    VehiclePropertyIds.toString(propertyId), updateRateHz);
            return false;
        }
    }

    private void getAndDispatchPropertyInitValue(CarPropertyConfig carPropertyConfig,
            Client client) {
        List<CarPropertyEvent> events = new ArrayList<>();
        for (int areaId : carPropertyConfig.getAreaIds()) {
            CarPropertyValue value = getPropertySafe(carPropertyConfig.getPropertyId(), areaId);
            if (value != null) {
                CarPropertyEvent event = new CarPropertyEvent(
                        CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, value);
                events.add(event);
            }
        }
        if (events.isEmpty()) {
            return;
        }
        try {
            client.onEvent(events);
        } catch (RemoteException ex) {
            // If we cannot send a record, it's likely the connection snapped. Let the binder
            // death handle the situation.
            Slogf.e(TAG, "onEvent calling failed", ex);
        }
    }

    @Override
    public void unregisterListener(int propertyId,
            ICarPropertyEventListener iCarPropertyEventListener) {
        requireNonNull(iCarPropertyEventListener);
        validateRegisterParameter(propertyId);

        if (DBG) {
            Slogf.d(TAG,
                    "unregisterListener property ID=" + VehiclePropertyIds.toString(propertyId));
        }

        IBinder listenerBinder = iCarPropertyEventListener.asBinder();
        unregisterListenerBinderForProps(List.of(propertyId), listenerBinder);
    }

    /**
     * Unregister property listener for car service's internal usage.
     */
    public boolean unregisterListenerSafe(int propertyId,
            ICarPropertyEventListener iCarPropertyEventListener) {
        try {
            unregisterListener(propertyId, iCarPropertyEventListener);
            return true;
        } catch (Exception e) {
            Slogf.e(TAG, e, "unregisterListenerSafe() failed for property ID: %s",
                    VehiclePropertyIds.toString(propertyId));
            return false;
        }
    }


    @GuardedBy("mLock")
    private void unregisterListenerBinderLocked(int propId, IBinder listenerBinder) {
        float updateMaxRate = 0f;
        Client client = mClientMap.get(listenerBinder);
        List<Client> propertyClients = mPropIdClientMap.get(propId);
        if (mPropertyIdToCarPropertyConfig.get(propId) == null) {
            // Do not attempt to unregister an invalid propId
            Slogf.e(TAG, "unregisterListener: propId is not in config list:0x%s",
                    toHexString(propId));
            return;
        }
        if ((client == null) || (propertyClients == null)) {
            Slogf.e(TAG, "unregisterListenerBinderLocked: Listener was not previously "
                    + "registered.");
            return;
        }
        if (propertyClients.remove(client)) {
            int propLeft = client.removeProperty(propId);
            if (propLeft == 0) {
                mClientMap.remove(listenerBinder);
            }
            clearSetOperationRecorderLocked(propId, client);

        } else {
            Slogf.e(TAG, "unregisterListenerBinderLocked: Listener was not registered for "
                    + "propId=0x" + toHexString(propId));
            return;
        }

        if (propertyClients.isEmpty()) {
            // Last listener for this property unsubscribed.  Clean up
            mPropIdClientMap.remove(propId);
            mSetOperationClientMap.remove(propId);
            mPropertyHalService.unsubscribeProperty(propId);
            return;
        }
        // Other listeners are still subscribed.  Calculate the new rate
        for (int i = 0; i < propertyClients.size(); i++) {
            Client c = propertyClients.get(i);
            float rate = c.getRate(propId);
            updateMaxRate = Math.max(rate, updateMaxRate);
        }
        if (Float.compare(updateMaxRate,
                mPropertyHalService.getSubscribedUpdateRateHz(propId)) != 0) {
            try {
                // Only reset the sample rate if needed
                mPropertyHalService.subscribeProperty(propId, updateMaxRate);
            } catch (IllegalArgumentException e) {
                Slogf.e(TAG, "failed to subscribe to propId=0x" + toHexString(propId)
                        + ", error: " + e);
            }
        }
    }

    private void unregisterListenerBinderForProps(List<Integer> propIds, IBinder listenerBinder) {
        synchronized (mLock) {
            for (int i = 0; i < propIds.size(); i++) {
                int propId = propIds.get(i);
                unregisterListenerBinderLocked(propId, listenerBinder);
            }
        }
    }

    /**
     * Return the list of properties' configs that the caller may access.
     */
    @NonNull
    @Override
    public CarPropertyConfigList getPropertyList() {
        int[] allPropId;
        // Avoid permission checking under lock.
        synchronized (mLock) {
            allPropId = new int[mPropertyIdToCarPropertyConfig.size()];
            for (int i = 0; i < mPropertyIdToCarPropertyConfig.size(); i++) {
                allPropId[i] = mPropertyIdToCarPropertyConfig.keyAt(i);
            }
        }
        return getPropertyConfigList(allPropId);
    }

    /**
     * @param propIds Array of property Ids
     * @return the list of properties' configs that the caller may access.
     */
    @NonNull
    @Override
    public CarPropertyConfigList getPropertyConfigList(int[] propIds) {
        // Cache the granted permissions
        Set<String> grantedPermission = new HashSet<>();
        List<CarPropertyConfig> availableProp = new ArrayList<>();
        if (propIds == null) {
            return new CarPropertyConfigList(availableProp);
        }
        for (int propId : propIds) {
            String readPermission = getReadPermission(propId);
            String writePermission = getWritePermission(propId);
            if (readPermission == null && writePermission == null) {
                continue;
            }

            // Check if context already granted permission first
            if (checkAndUpdateGrantedPermissionSet(mContext, grantedPermission, readPermission)
                    || checkAndUpdateGrantedWritePermissionSet(mContext, grantedPermission,
                            writePermission, propId)
                    || checkAndUpdateGrantedTemperatureDisplayUnitsPermissionSet(mContext,
                            grantedPermission, propId)) {
                synchronized (mLock) {
                    availableProp.add(mPropertyIdToCarPropertyConfig.get(propId));
                }
            }
        }
        if (DBG) {
            Slogf.d(TAG, "getPropertyList returns " + availableProp.size() + " configs");
        }
        return new CarPropertyConfigList(availableProp);
    }

    private boolean checkAndUpdateGrantedWritePermissionSet(Context context,
            Set<String> grantedPermissions, @Nullable String permission, int propertyId) {
        if (!checkAndUpdateGrantedPermissionSet(context, grantedPermissions, permission)) {
            return false;
        }
        if (mPropertyHalService.isDisplayUnitsProperty(propertyId) && permission != null
                && permission.equals(Car.PERMISSION_CONTROL_DISPLAY_UNITS)) {
            return checkAndUpdateGrantedPermissionSet(context, grantedPermissions,
                    Car.PERMISSION_VENDOR_EXTENSION);
        }
        return true;
    }

    private static boolean checkAndUpdateGrantedTemperatureDisplayUnitsPermissionSet(
            Context context, Set<String> grantedPermissions, int propertyId) {
        return propertyId == VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS
                && checkAndUpdateGrantedPermissionSet(context, grantedPermissions,
                Car.PERMISSION_READ_DISPLAY_UNITS);
    }

    private static boolean checkAndUpdateGrantedPermissionSet(Context context,
            Set<String> grantedPermissions, @Nullable String permission) {
        if (permission != null && (grantedPermissions.contains(permission)
                || CarServiceUtils.hasPermission(context, permission))) {
            grantedPermissions.add(permission);
            return true;
        }
        return false;
    }

    @Nullable
    private <V> V runSyncOperationCheckLimit(Callable<V> c) {
        synchronized (mLock) {
            if (mSyncGetSetPropertyOpCount >= SYNC_GET_SET_PROPERTY_OP_LIMIT) {
                throw new ServiceSpecificException(SYNC_OP_LIMIT_TRY_AGAIN);
            }
            mSyncGetSetPropertyOpCount += 1;
            if (DBG) {
                Slogf.d(TAG, "mSyncGetSetPropertyOpCount: %d", mSyncGetSetPropertyOpCount);
            }
        }
        try {
            Trace.traceBegin(TRACE_TAG, "call sync operation");
            return c.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Slogf.e(TAG, e, "catching unexpected exception for getProperty/setProperty");
            return null;
        } finally {
            Trace.traceEnd(TRACE_TAG);
            synchronized (mLock) {
                mSyncGetSetPropertyOpCount -= 1;
                if (DBG) {
                    Slogf.d(TAG, "mSyncGetSetPropertyOpCount: %d", mSyncGetSetPropertyOpCount);
                }
            }
        }
    }

    @Override
    public CarPropertyValue getProperty(int propertyId, int areaId)
            throws IllegalArgumentException, ServiceSpecificException {
        validateGetParameters(propertyId, areaId);
        Trace.traceBegin(TRACE_TAG, "CarPropertyValue#getProperty");
        try {
            return runSyncOperationCheckLimit(() -> {
                return mPropertyHalService.getProperty(propertyId, areaId);
            });
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    /**
     * Get property value for car service's internal usage.
     *
     * @return null if property is not implemented or there is an exception in the vehicle.
     */
    @Nullable
    public CarPropertyValue getPropertySafe(int propertyId, int areaId) {
        try {
            return getProperty(propertyId, areaId);
        } catch (Exception e) {
            Slogf.e(TAG, e, "getPropertySafe() failed for property id: %s area id: 0x%s",
                    VehiclePropertyIds.toString(propertyId), toHexString(areaId));
            return null;
        }
    }

    @Nullable
    @Override
    public String getReadPermission(int propId) {
        Pair<String, String> permissions;
        synchronized (mLock) {
            permissions = mPropToPermission.get(propId);
        }
        if (permissions == null) {
            // Property ID does not exist
            Slogf.e(TAG, "getReadPermission: propId is not in config list:0x"
                    + toHexString(propId));
            return null;
        }
        return permissions.first;
    }

    @Nullable
    @Override
    public String getWritePermission(int propId) {
        Pair<String, String> permissions;
        synchronized (mLock) {
            permissions = mPropToPermission.get(propId);
        }
        if (permissions == null) {
            // Property ID does not exist
            Slogf.e(TAG, "getWritePermission: propId is not in config list:0x"
                    + toHexString(propId));
            return null;
        }
        return permissions.second;
    }

    @Override
    public void setProperty(CarPropertyValue carPropertyValue,
            ICarPropertyEventListener iCarPropertyEventListener)
            throws IllegalArgumentException, ServiceSpecificException {
        requireNonNull(iCarPropertyEventListener);
        validateSetParameters(carPropertyValue);

        runSyncOperationCheckLimit(() -> {
            mPropertyHalService.setProperty(carPropertyValue);
            return null;
        });

        IBinder listenerBinder = iCarPropertyEventListener.asBinder();
        synchronized (mLock) {
            Client client = mClientMap.get(listenerBinder);
            if (client == null) {
                client = new Client(iCarPropertyEventListener);
            }
            if (client.isDead()) {
                Slogf.w(TAG, "the ICarPropertyEventListener is already dead");
                return;
            }
            mClientMap.put(listenerBinder, client);
            updateSetOperationRecorderLocked(carPropertyValue.getPropertyId(),
                    carPropertyValue.getAreaId(), client);
        }
    }

    // Updates recorder for set operation.
    @GuardedBy("mLock")
    private void updateSetOperationRecorderLocked(int propId, int areaId, Client client) {
        if (mSetOperationClientMap.get(propId) != null) {
            mSetOperationClientMap.get(propId).put(areaId, client);
        } else {
            SparseArray<Client> areaIdToClient = new SparseArray<>();
            areaIdToClient.put(areaId, client);
            mSetOperationClientMap.put(propId, areaIdToClient);
        }
    }

    // Clears map when client unregister for property.
    @GuardedBy("mLock")
    private void clearSetOperationRecorderLocked(int propId, Client client) {
        SparseArray<Client> areaIdToClient = mSetOperationClientMap.get(propId);
        if (areaIdToClient != null) {
            List<Integer> indexNeedToRemove = new ArrayList<>();
            for (int index = 0; index < areaIdToClient.size(); index++) {
                if (client.equals(areaIdToClient.valueAt(index))) {
                    indexNeedToRemove.add(index);
                }
            }

            for (int index : indexNeedToRemove) {
                if (DBG) {
                    Slogf.d("ErrorEvent", " Clear propId:0x" + toHexString(propId)
                            + " areaId: 0x" + toHexString(areaIdToClient.keyAt(index)));
                }
                areaIdToClient.removeAt(index);
            }
        }
    }

    // Implement PropertyHalListener interface
    @Override
    public void onPropertyChange(List<CarPropertyEvent> events) {
        Map<Client, List<CarPropertyEvent>> eventsToDispatch = new ArrayMap<>();
        synchronized (mLock) {
            for (int i = 0; i < events.size(); i++) {
                CarPropertyEvent event = events.get(i);
                int propId = event.getCarPropertyValue().getPropertyId();
                List<Client> clients = mPropIdClientMap.get(propId);
                if (clients == null) {
                    Slogf.e(TAG, "onPropertyChange: no listener registered for propId=0x%s",
                            toHexString(propId));
                    continue;
                }

                for (int j = 0; j < clients.size(); j++) {
                    Client c = clients.get(j);
                    List<CarPropertyEvent> p = eventsToDispatch.get(c);
                    if (p == null) {
                        // Initialize the linked list for the listener
                        p = new ArrayList<CarPropertyEvent>();
                        eventsToDispatch.put(c, p);
                    }
                    p.add(event);
                }
            }
        }

        // Parse the dispatch list to send events. We must call the callback outside the
        // scoped lock since the callback might call some function in CarPropertyService
        // which might cause deadlock.
        // In rare cases, if this specific client is unregistered after the lock but before
        // the callback, we would call callback on an unregistered client which should be ok because
        // 'onEvent' is an async oneway callback that might be delivered after unregistration
        // anyway.
        for (Client client : eventsToDispatch.keySet()) {
            try {
                client.onEvent(eventsToDispatch.get(client));
            } catch (RemoteException ex) {
                // If we cannot send a record, it's likely the connection snapped. Let binder
                // death handle the situation.
                Slogf.e(TAG, "onEvent calling failed: " + ex);
            }
        }
    }

    @Override
    public void onPropertySetError(int property, int areaId, int errorCode) {
        Client lastOperatedClient = null;
        synchronized (mLock) {
            if (mSetOperationClientMap.get(property) != null
                    && mSetOperationClientMap.get(property).get(areaId) != null) {
                lastOperatedClient = mSetOperationClientMap.get(property).get(areaId);
            } else {
                Slogf.e(TAG, "Can not find the client changed propertyId: 0x"
                        + toHexString(property) + " in areaId: 0x" + toHexString(areaId));
            }

        }
        if (lastOperatedClient != null) {
            dispatchToLastClient(property, areaId, errorCode, lastOperatedClient);
        }
    }

    private void dispatchToLastClient(int property, int areaId, int errorCode,
            Client lastOperatedClient) {
        try {
            List<CarPropertyEvent> eventList = new ArrayList<>();
            eventList.add(
                    CarPropertyEvent.createErrorEventWithErrorCode(property, areaId,
                            errorCode));
            lastOperatedClient.onEvent(eventList);
        } catch (RemoteException ex) {
            Slogf.e(TAG, "onEvent calling failed: " + ex);
        }
    }

    private static void validateGetSetAsyncParameters(AsyncPropertyServiceRequestList requests,
            IAsyncPropertyResultCallback asyncPropertyResultCallback,
            long timeoutInMs) throws IllegalArgumentException {
        requireNonNull(requests);
        requireNonNull(asyncPropertyResultCallback);
        Preconditions.checkArgument(timeoutInMs > 0, "timeoutInMs must be a positive number");
    }

    /**
     * Gets CarPropertyValues asynchronously.
     */
    public void getPropertiesAsync(
            AsyncPropertyServiceRequestList getPropertyServiceRequestsParcelable,
            IAsyncPropertyResultCallback asyncPropertyResultCallback, long timeoutInMs) {
        validateGetSetAsyncParameters(getPropertyServiceRequestsParcelable,
                asyncPropertyResultCallback, timeoutInMs);
        List<AsyncPropertyServiceRequest> getPropertyServiceRequests =
                getPropertyServiceRequestsParcelable.getList();
        for (int i = 0; i < getPropertyServiceRequests.size(); i++) {
            validateGetParameters(getPropertyServiceRequests.get(i).getPropertyId(),
                    getPropertyServiceRequests.get(i).getAreaId());
        }
        mPropertyHalService.getCarPropertyValuesAsync(getPropertyServiceRequests,
                asyncPropertyResultCallback, timeoutInMs);
    }

    /**
     * Sets CarPropertyValues asynchronously.
     */
    @SuppressWarnings("FormatString")
    public void setPropertiesAsync(AsyncPropertyServiceRequestList setPropertyServiceRequests,
            IAsyncPropertyResultCallback asyncPropertyResultCallback,
            long timeoutInMs) {
        validateGetSetAsyncParameters(setPropertyServiceRequests, asyncPropertyResultCallback,
                timeoutInMs);
        List<AsyncPropertyServiceRequest> setPropertyServiceRequestList =
                setPropertyServiceRequests.getList();
        for (int i = 0; i < setPropertyServiceRequestList.size(); i++) {
            AsyncPropertyServiceRequest request = setPropertyServiceRequestList.get(i);
            CarPropertyValue carPropertyValueToSet = request.getCarPropertyValue();
            int propertyId = request.getPropertyId();
            int valuePropertyId = carPropertyValueToSet.getPropertyId();
            int areaId = request.getAreaId();
            int valueAreaId = carPropertyValueToSet.getAreaId();
            String propertyName = VehiclePropertyIds.toString(propertyId);
            if (valuePropertyId != propertyId) {
                throw new IllegalArgumentException(String.format(
                        "Property ID in request and CarPropertyValue mismatch: %s vs %s",
                        VehiclePropertyIds.toString(valuePropertyId), propertyName).toString());
            }
            if (valueAreaId != areaId) {
                throw new IllegalArgumentException(String.format(
                        "For property: %s, area ID in request and CarPropertyValue mismatch: %d vs"
                        + " %d", propertyName, valueAreaId, areaId).toString());
            }
            validateSetParameters(carPropertyValueToSet);
            if (request.isWaitForPropertyUpdate()) {
                if (NOT_ALLOWED_WAIT_FOR_UPDATE_PROPERTIES.contains(propertyId)) {
                    throw new IllegalArgumentException("Property: "
                            + propertyName + " must set waitForPropertyUpdate to false");
                }
                validateGetParameters(propertyId, areaId);
            }
        }
        mPropertyHalService.setCarPropertyValuesAsync(setPropertyServiceRequestList,
                asyncPropertyResultCallback, timeoutInMs);
    }

    /**
     * Cancel on-going async requests.
     *
     * @param serviceRequestIds A list of async get/set property request IDs.
     */
    public void cancelRequests(int[] serviceRequestIds) {
        mPropertyHalService.cancelRequests(serviceRequestIds);
    }

    private static void assertPropertyIsReadable(CarPropertyConfig<?> carPropertyConfig) {
        Preconditions.checkArgument(
                carPropertyConfig.getAccess() == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ
                        || carPropertyConfig.getAccess()
                        == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE,
                "Property is not readable: %s",
                VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()));
    }

    private static void assertConfigIsNotNull(int propertyId,
            CarPropertyConfig<?> carPropertyConfig) {
        Preconditions.checkArgument(carPropertyConfig != null,
                "property ID is not in carPropertyConfig list, and so it is not supported: %s",
                VehiclePropertyIds.toString(propertyId));
    }

    private static void assertAreaIdIsSupported(int areaId,
            CarPropertyConfig<?> carPropertyConfig) {
        Preconditions.checkArgument(ArrayUtils.contains(carPropertyConfig.getAreaIds(), areaId),
                "area ID: 0x" + toHexString(areaId) + " not supported for property ID: "
                        + VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()));
    }

    @Nullable
    private CarPropertyConfig<?> getCarPropertyConfig(int propertyId) {
        CarPropertyConfig<?> carPropertyConfig;
        synchronized (mLock) {
            carPropertyConfig = mPropertyIdToCarPropertyConfig.get(propertyId);
        }
        return carPropertyConfig;
    }

    private void assertReadPermissionGranted(int propertyId) {
        String readPermission = mPropertyHalService.getReadPermission(propertyId);
        if (readPermission == null) {
            throw new SecurityException(
                    "Platform does not have permission to read value for property ID: "
                            + VehiclePropertyIds.toString(propertyId));
        }
        if (propertyId == VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS) {
            CarServiceUtils.assertAnyPermission(mContext, readPermission,
                    Car.PERMISSION_READ_DISPLAY_UNITS);
            return;
        }
        if (propertyId == VehiclePropertyIds.EV_CHARGE_SWITCH) {
            CarServiceUtils.assertAnyPermission(mContext, readPermission,
                    Car.PERMISSION_CONTROL_CAR_ENERGY);
            return;
        }
        CarServiceUtils.assertPermission(mContext, readPermission);
    }

    private void validateRegisterParameter(int propertyId) {
        CarPropertyConfig<?> carPropertyConfig = getCarPropertyConfig(propertyId);
        assertConfigIsNotNull(propertyId, carPropertyConfig);
        assertPropertyIsReadable(carPropertyConfig);
        assertReadPermissionGranted(propertyId);
    }

    private void validateGetParameters(int propertyId, int areaId) {
        CarPropertyConfig<?> carPropertyConfig = getCarPropertyConfig(propertyId);
        assertConfigIsNotNull(propertyId, carPropertyConfig);
        assertPropertyIsReadable(carPropertyConfig);
        assertReadPermissionGranted(propertyId);
        assertAreaIdIsSupported(areaId, carPropertyConfig);
    }

    private void validateSetParameters(CarPropertyValue<?> carPropertyValue) {
        requireNonNull(carPropertyValue);
        int propertyId = carPropertyValue.getPropertyId();
        int areaId = carPropertyValue.getAreaId();
        Object valueToSet = carPropertyValue.getValue();
        CarPropertyConfig<?> carPropertyConfig = getCarPropertyConfig(propertyId);
        assertConfigIsNotNull(propertyId, carPropertyConfig);

        // Assert property is writable.
        Preconditions.checkArgument(
                carPropertyConfig.getAccess() == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE
                        || carPropertyConfig.getAccess()
                        == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE,
                "Property is not writable: %s",
                VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()));

        // Assert write permission is granted.
        String writePermission = mPropertyHalService.getWritePermission(propertyId);
        if (writePermission == null) {
            throw new SecurityException(
                    "Platform does not have permission to write value for property ID: "
                            + VehiclePropertyIds.toString(propertyId));
        }
        CarServiceUtils.assertPermission(mContext, writePermission);
        // need an extra permission for writing display units properties.
        if (mPropertyHalService.isDisplayUnitsProperty(propertyId)) {
            CarServiceUtils.assertPermission(mContext, Car.PERMISSION_VENDOR_EXTENSION);
        }

        assertAreaIdIsSupported(areaId, carPropertyConfig);

        // Assert set value is valid for property.
        Preconditions.checkArgument(valueToSet != null,
                "setProperty: CarPropertyValue's must not be null - property ID: %s area ID: %s",
                VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()),
                toHexString(areaId));
        Preconditions.checkArgument(
                valueToSet.getClass().equals(carPropertyConfig.getPropertyType()),
                "setProperty: CarPropertyValue's value's type does not match property's type. - "
                        + "property ID: %s area ID: %s",
                VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()),
                toHexString(areaId));

        AreaIdConfig<?> areaIdConfig = carPropertyConfig.getAreaIdConfig(areaId);
        if (areaIdConfig.getMinValue() != null) {
            boolean isGreaterThanOrEqualToMinValue = false;
            if (carPropertyConfig.getPropertyType().equals(Integer.class)) {
                isGreaterThanOrEqualToMinValue =
                        (Integer) valueToSet >= (Integer) areaIdConfig.getMinValue();
            } else if (carPropertyConfig.getPropertyType().equals(Long.class)) {
                isGreaterThanOrEqualToMinValue =
                        (Long) valueToSet >= (Long) areaIdConfig.getMinValue();
            } else if (carPropertyConfig.getPropertyType().equals(Float.class)) {
                isGreaterThanOrEqualToMinValue =
                        (Float) valueToSet >= (Float) areaIdConfig.getMinValue();
            }
            Preconditions.checkArgument(isGreaterThanOrEqualToMinValue,
                    "setProperty: value to set must be greater than or equal to the area ID min "
                            + "value. - " + "property ID: %s area ID: 0x%s min value: %s",
                    VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()),
                    toHexString(areaId), areaIdConfig.getMinValue());

        }

        if (areaIdConfig.getMaxValue() != null) {
            boolean isLessThanOrEqualToMaxValue = false;
            if (carPropertyConfig.getPropertyType().equals(Integer.class)) {
                isLessThanOrEqualToMaxValue =
                        (Integer) valueToSet <= (Integer) areaIdConfig.getMaxValue();
            } else if (carPropertyConfig.getPropertyType().equals(Long.class)) {
                isLessThanOrEqualToMaxValue =
                        (Long) valueToSet <= (Long) areaIdConfig.getMaxValue();
            } else if (carPropertyConfig.getPropertyType().equals(Float.class)) {
                isLessThanOrEqualToMaxValue =
                        (Float) valueToSet <= (Float) areaIdConfig.getMaxValue();
            }
            Preconditions.checkArgument(isLessThanOrEqualToMaxValue,
                    "setProperty: value to set must be less than or equal to the area ID max "
                            + "value. - " + "property ID: %s area ID: 0x%s min value: %s",
                    VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()),
                    toHexString(areaId), areaIdConfig.getMaxValue());

        }

        if (!areaIdConfig.getSupportedEnumValues().isEmpty()) {
            Preconditions.checkArgument(areaIdConfig.getSupportedEnumValues().contains(valueToSet),
                    "setProperty: value to set must exist in set of supported enum values. - "
                            + "property ID: %s area ID: 0x%s supported enum values: %s",
                    VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()),
                    toHexString(areaId), areaIdConfig.getSupportedEnumValues());
        }

        if (PROPERTY_ID_TO_UNWRITABLE_STATES.contains(carPropertyConfig.getPropertyId())) {
            Preconditions.checkArgument(!(PROPERTY_ID_TO_UNWRITABLE_STATES
                    .get(carPropertyConfig.getPropertyId()).contains(valueToSet)),
                    "setProperty: value to set: %s must not be an unwritable state value. - "
                            + "property ID: %s area ID: 0x%s unwritable states: %s",
                    valueToSet,
                    VehiclePropertyIds.toString(carPropertyConfig.getPropertyId()),
                    toHexString(areaId),
                    PROPERTY_ID_TO_UNWRITABLE_STATES.get(carPropertyConfig.getPropertyId()));
        }
    }
}
