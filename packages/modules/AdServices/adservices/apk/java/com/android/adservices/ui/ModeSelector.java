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

package com.android.adservices.ui;

import com.android.adservices.service.FlagsFactory;

/**
 * Activities and Action Delegates should implement this interface to ensure they implement all
 * existing modes of AdServices.
 */
public interface ModeSelector {
    /**
     * Contains all the modes for AdServices module. Each mode should have a corresponding method to
     * get the layoutResId for that mode.
     */
    enum ModeEnum {
        BETA,
        GA,
        U18,
    }

    /**
     * Temporary Utility class to get the current mode for AdServices module. This will be replace
     * with UX Engine.
     */
    class CurrentMode {
        private static ModeEnum sCurrentMode;

        public static ModeEnum get(boolean refresh) {
            if (refresh || sCurrentMode == null) {
                sCurrentMode =
                        FlagsFactory.getFlags().getGaUxFeatureEnabled()
                                ? ModeEnum.GA
                                : ModeEnum.BETA;
            }
            return sCurrentMode;
        }
    }

    /**
     * This method will be called in during initialization of class to determine which mode to
     * choose.
     *
     * @param refresh if true, will re-fetch current UI mode. Should only be true at start of UI
     *     flows (e.g. main view of settings, notification landing page, notification card)
     */
    default void initWithMode(boolean refresh) {
        switch (CurrentMode.get(refresh)) {
            case BETA:
                initBeta();
                break;
            case GA:
                initGA();
                break;
            case U18:
                initU18();
                break;
            default:
                // TODO: log some warning or error
                initGA();
        }
    }

    /**
     * This method will be called in {@link #initWithMode} if app is in {@link ModeEnum#BETA} mode.
     */
    void initBeta();

    /**
     * This method will be called in {@link #initWithMode} if app is in {@link ModeEnum#GA} mode.
     */
    void initGA();

    /**
     * This method will be called in {@link #initWithMode} if app is in {@link ModeEnum#U18} mode.
     */
    void initU18();
}
