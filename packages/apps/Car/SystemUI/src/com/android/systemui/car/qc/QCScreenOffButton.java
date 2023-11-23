/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.car.qc;

import static com.android.systemui.car.users.CarSystemUIUserUtil.getCurrentUserHandle;

import android.car.Car;
import android.car.hardware.power.CarPowerManager;
import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;

/**
 * One of {@link QCFooterButtonView} for quick control panels, which turns off the screen.
 */

public class QCScreenOffButton extends QCFooterButtonView {
    private static final String TAG = QCUserPickerButton.class.getSimpleName();

    private CarPowerManager mCarPowerManager;

    public QCScreenOffButton(Context context) {
        this(context, null);
    }

    public QCScreenOffButton(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QCScreenOffButton(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public QCScreenOffButton(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mCarServiceLifecycleListener = (car, ready) -> {
            if (!ready) {
                return;
            }
            mCarPowerManager = (CarPowerManager) car.getCarManager(Car.POWER_SERVICE);
        };

        Car.createCar(getContext(), /* handler= */ null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                mCarServiceLifecycleListener);

        mOnClickListener = v -> turnScreenOff();
        setOnClickListener(mOnClickListener);
    }

    private void turnScreenOff() {
        getContext().sendBroadcastAsUser(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                getCurrentUserHandle(getContext(), mUserTracker));
        if (mCarPowerManager != null) {
            mCarPowerManager.setDisplayPowerState(getContext().getDisplayId(), /* enable= */ false);
        }
    }
}
