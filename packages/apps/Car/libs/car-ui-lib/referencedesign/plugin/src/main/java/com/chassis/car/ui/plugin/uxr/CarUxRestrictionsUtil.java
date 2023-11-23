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
package com.chassis.car.ui.plugin.uxr;

import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Utility class to access {@link CarUxRestrictionsManager}.
 *
 * <p>This class must be a singleton because only one listener can be registered with {@link
 * CarUxRestrictionsManager} at a time, as documented in
 * {@link CarUxRestrictionsManager#registerListener}.
 */
public class CarUxRestrictionsUtil {
    private static final String TAG = "CarUxRestrictionsUtil";

    @NonNull
    private CarUxRestrictions mCarUxRestrictions = getDefaultRestrictions();

    private final Set<OnUxRestrictionsChangedListener> mObservers =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static CarUxRestrictionsUtil sInstance;

    private CarUxRestrictionsUtil(Context context) {
        CarUxRestrictionsManager.OnUxRestrictionsChangedListener listener =
                (carUxRestrictions) -> {
                    if (carUxRestrictions == null) {
                        mCarUxRestrictions = getDefaultRestrictions();
                    } else {
                        mCarUxRestrictions = carUxRestrictions;
                    }

                    for (OnUxRestrictionsChangedListener observer : mObservers) {
                        observer.onRestrictionsChanged(mCarUxRestrictions);
                    }
                };

        try {
            Car.createCar(context, null,
                    Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                    (Car car, boolean ready) -> {
                        if (ready) {
                            CarUxRestrictionsManager carUxRestrictionsManager =
                                    (CarUxRestrictionsManager) car.getCarManager(
                                            Car.CAR_UX_RESTRICTION_SERVICE);
                            carUxRestrictionsManager.registerListener(listener);
                            listener.onUxRestrictionsChanged(
                                    carUxRestrictionsManager.getCurrentCarUxRestrictions());
                        } else {
                            Log.w(TAG, "Car service disconnected, assuming fully restricted uxr");
                            listener.onUxRestrictionsChanged(null);
                        }
                    });

        } catch (SecurityException e) {
            Log.w(TAG, "Unable to connect to car service, assuming unrestricted", e);
            listener.onUxRestrictionsChanged(new CarUxRestrictions.Builder(
                    false, CarUxRestrictions.UX_RESTRICTIONS_BASELINE, 0)
                    .build());
        }
    }

    @NonNull
    private static CarUxRestrictions getDefaultRestrictions() {
        return new CarUxRestrictions.Builder(
                true, CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED, 0)
                .build();
    }

    /**
     * Listener interface used to update clients on UxRestrictions changes
     */
    public interface OnUxRestrictionsChangedListener {
        /**
         * Called when CarUxRestrictions changes
         */
        void onRestrictionsChanged(@NonNull CarUxRestrictions carUxRestrictions);
    }

    /**
     * Returns the singleton sInstance of this class
     */
    @NonNull
    public static CarUxRestrictionsUtil getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new CarUxRestrictionsUtil(context);
        }

        return sInstance;
    }

    /**
     * Registers a listener on this class for updates to CarUxRestrictions. Multiple listeners may
     * be registered. Note that this class will only hold a weak reference to the listener, you must
     * maintain a strong reference to it elsewhere.
     */
    public void register(OnUxRestrictionsChangedListener listener) {
        mObservers.add(listener);
        listener.onRestrictionsChanged(mCarUxRestrictions);
    }

    /**
     * Unregisters a registered listener
     */
    public void unregister(OnUxRestrictionsChangedListener listener) {
        mObservers.remove(listener);
    }
}
