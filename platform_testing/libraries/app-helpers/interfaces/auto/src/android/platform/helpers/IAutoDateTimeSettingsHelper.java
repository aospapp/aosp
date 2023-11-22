/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.platform.helpers;

/**
 * Helper class for functional tests of date & time
 */

import java.time.LocalDate;

public interface IAutoDateTimeSettingsHelper extends IAppHelper {
    /**
     * Setup expectation: Date & time setting is open
     *
     * Set the device date
     *
     * @param date - input LocalDate object
     */
    void setDate(LocalDate date);

    /**
     * Setup expectation: Date & time setting is open
     *
     * Get the current date displayed on the UI in LocalDate object
     */
    LocalDate getDate();

    /**
     * Setup expectation: Date & time setting is open
     *
     * Set the device time
     *
     * @param hour - input hour
     * @param minute - input minute
     * @param AM_PM - input am/pm
     */
    void setTime(int hour, int minute, boolean is_am);

    /**
     * Setup expectation: Date & time setting is open
     *
     * Get the current time displayed on the UI
     * The return string format will match the UI format exactly
     */
    String getTime();

    /**
     * Setup expectation: Date & time setting is open
     *
     * Set the device time zone
     *
     * @param timezone - city selected for timezone
     */
    void setTimeZone(String timezone);

    /**
     * Setup expectation: Date & time setting is open
     *
     * Get the current timezone displayed on the UI
     */
    String getTimeZone();

    /**
     * Setup expectation: Date & time setting is open
     *
     * Check if the 24 hour format menu switch widget is toggoled on
     */
    boolean isUseTwentyFourHourFormatSwitchWidgetOn();

    /**
     * Setup expectation: Date & time setting is open
     *
     * Toggle on/off 24 hour format widget switch
     */
    boolean toggleTwentyFourHourFormatSwitch();
}
