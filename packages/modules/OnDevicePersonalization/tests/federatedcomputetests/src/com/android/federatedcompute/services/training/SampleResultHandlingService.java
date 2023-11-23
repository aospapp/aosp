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

package com.android.federatedcompute.services.training;

import static android.federatedcompute.common.ClientConstants.STATUS_INTERNAL_ERROR;
import static android.federatedcompute.common.ClientConstants.STATUS_SUCCESS;

import android.federatedcompute.ResultHandlingService;
import android.federatedcompute.common.ExampleConsumption;
import android.federatedcompute.common.TrainingOptions;
import android.util.Log;

import java.util.List;
import java.util.function.Consumer;

/** A simple implementation of {@link ResultHandlingService}. */
public class SampleResultHandlingService extends ResultHandlingService {
    private static final String TAG = "SampleResultHandlingService";

    public void handleResult(
            TrainingOptions trainingOptions,
            boolean success,
            List<ExampleConsumption> exampleConsumptionList,
            Consumer<Integer> callback) {
        Log.i(TAG, "Handling result for population: " + trainingOptions.getPopulationName());
        if (exampleConsumptionList.isEmpty()) {
            callback.accept(STATUS_INTERNAL_ERROR);
            return;
        }
        callback.accept(STATUS_SUCCESS);
    }
}
