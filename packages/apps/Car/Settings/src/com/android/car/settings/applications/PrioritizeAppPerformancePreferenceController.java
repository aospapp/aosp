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

package com.android.car.settings.applications;

import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.watchdog.CarWatchdogManager;
import android.car.watchdog.PackageKillableState;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.UserHandle;

import androidx.annotation.VisibleForTesting;
import androidx.preference.TwoStatePreference;

import com.android.car.settings.R;
import com.android.car.settings.common.ConfirmationDialogFragment;
import com.android.car.settings.common.FragmentController;
import com.android.car.settings.common.Logger;
import com.android.car.settings.common.PreferenceController;
import com.android.internal.annotations.GuardedBy;

import java.util.Objects;

/** Controller for preference which turns on / off prioritize app performance setting. */
public class PrioritizeAppPerformancePreferenceController
        extends PreferenceController<TwoStatePreference> {
    private static final Logger LOG =
            new Logger(PrioritizeAppPerformancePreferenceController.class);

    @VisibleForTesting
    static final String TURN_ON_PRIORITIZE_APP_PERFORMANCE_DIALOG_TAG =
            "com.android.car.settings.applications.TurnOnPrioritizeAppPerformanceDialogTag";

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private CarWatchdogManager mCarWatchdogManager;

    private Car mCar;
    private String mPackageName;
    private UserHandle mUserHandle;

    private final ConfirmationDialogFragment.ConfirmListener mConfirmListener = arguments -> {
        setKillableState(false);
        getPreference().setChecked(true);
    };

    public PrioritizeAppPerformancePreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController,
            CarUxRestrictions uxRestrictions) {
        super(context, preferenceKey, fragmentController, uxRestrictions);
    }

    @Override
    protected void onCreateInternal() {
        if (mCar != null && mCar.isConnected()) {
            mCar.disconnect();
            mCar = null;
        }
        mCar = Car.createCar(getContext(), null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (car, isReady) -> {
                    synchronized (mLock) {
                        mCarWatchdogManager = isReady
                                ? (CarWatchdogManager) car.getCarManager(Car.CAR_WATCHDOG_SERVICE)
                                : null;
                    }
                });

        ConfirmationDialogFragment dialogFragment =
                (ConfirmationDialogFragment) getFragmentController().findDialogByTag(
                        TURN_ON_PRIORITIZE_APP_PERFORMANCE_DIALOG_TAG);
        ConfirmationDialogFragment.resetListeners(
                dialogFragment, mConfirmListener, /* rejectListener= */ null,
                /* neutralListener= */ null);
    }

    @Override
    protected void onDestroyInternal() {
        if (mCar != null) {
            mCar.disconnect();
            mCar = null;
        }
    }

    /**
     * Set the package info of the application.
     */
    public void setPackageInfo(PackageInfo packageInfo) {
        mPackageName = packageInfo.packageName;
        mUserHandle = UserHandle.getUserHandleForUid(packageInfo.applicationInfo.uid);
    }

    @Override
    protected Class<TwoStatePreference> getPreferenceType() {
        return TwoStatePreference.class;
    }

    @Override
    protected void updateState(TwoStatePreference preference) {
        int killableState = getKillableState();
        preference.setChecked(killableState == PackageKillableState.KILLABLE_STATE_NO);
        preference.setEnabled(killableState != PackageKillableState.KILLABLE_STATE_NEVER);
    }

    @Override
    protected boolean handlePreferenceChanged(TwoStatePreference preference, Object newValue) {
        boolean isToggledOn = (boolean) newValue;
        if (isToggledOn) {
            showConfirmationDialog();
            return false;
        }
        setKillableState(true);
        return true;
    }

    private int getKillableState() {
        synchronized (mLock) {
            return Objects.requireNonNull(mCarWatchdogManager)
                    .getPackageKillableStatesAsUser(mUserHandle).stream()
                    .filter(pks -> pks.getPackageName().equals(mPackageName))
                    .findFirst().map(PackageKillableState::getKillableState).orElse(-1);
        }
    }

    private void setKillableState(boolean isKillable) {
        synchronized (mLock) {
            mCarWatchdogManager.setKillablePackageAsUser(mPackageName, mUserHandle, isKillable);
        }
    }

    private void showConfirmationDialog() {
        ConfirmationDialogFragment dialogFragment =
                new ConfirmationDialogFragment.Builder(getContext())
                        .setTitle(R.string.prioritize_app_performance_dialog_title)
                        .setMessage(R.string.prioritize_app_performance_dialog_text)
                        .setPositiveButton(R.string.prioritize_app_performance_dialog_action_on,
                                mConfirmListener)
                        .setNegativeButton(R.string.prioritize_app_performance_dialog_action_off,
                                /* rejectListener= */ null)
                        .build();
        getFragmentController().showDialog(
                dialogFragment, TURN_ON_PRIORITIZE_APP_PERFORMANCE_DIALOG_TAG);
    }
}
