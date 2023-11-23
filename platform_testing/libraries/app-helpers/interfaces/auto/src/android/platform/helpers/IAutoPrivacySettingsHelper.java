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

package android.platform.helpers;

/** Interface class for privacy setting helper */
public interface IAutoPrivacySettingsHelper extends IAppHelper {

    /**
     * Setup expectation: MicroPhone settings is open.
     *
     * <p>This method turns on and Off the MicroPhone.
     */
    void turnOnOffMicroPhone(boolean onOff);

    /**
     * Setup expectation: MicroPhone settings is open.
     *
     * <p>This method checks if MicroPhone is turned on or off.
     */
    boolean isMicroPhoneOn();

    /**
     * Setup expectation: MicroPhone Muted.
     *
     * <p>This method checks if muted MicroPhone Chip is present on Status bar.
     */
    boolean isMutedMicChipPresentOnStatusBar();

    /**
     * Setup expectation: MicroPhone unMuted.
     *
     * <p>This method checks if muted MicroPhone Chip is present on Status bar when micphrone panel
     * is opened .
     */
    boolean isMutedMicChipPresentWithMicPanel();

    /**
     * Setup expectation: None.
     *
     * <p>This method checks if MicroPhone Chip(dark/light) is present on Status bar.
     */
    boolean isMicChipPresentOnStatusBar();

    /**
     * Setup expectation: MicroPhone settings is open.
     *
     * <p>This method taps microphone button in status bar.
     */
    void clickMicroPhoneStatusBar();

    /**
     * Setup expectation: MicroPhone panel is open.
     *
     * <p>This method checks if Micro Phone Settings links is present in the micro phone panel
     */
    boolean isMicroPhoneSettingsLinkPresent();

    /**
     * Setup expectation: MicroPhone panel is open.
     *
     * <p>This method taos on Micro Phone Settings links is present in the micro phone panel
     */
    void clickMicroPhoneSettingsLink();

    /**
     * Setup expectation: MicroPhone panel is open.
     *
     * <p>This method checks if Micro Phone Toggle is present in the panel
     */
    boolean isMicroPhoneTogglePresent();

    /**
     * Setup expectation: MicroPhone panel is open.
     *
     * <p>This method taps on micro phone toggle
     */
    void clickMicroPhoneToggleStatusBar();

    /**
     * Setup expectation: MicroPhone Settings is open and no apps has used microphone recently.
     *
     * <p>This method checks if no recent apps is present.
     */
    boolean verifyNoRecentAppsPresent();

    /**
     * Setup expectation: Micro Phone Panel is open
     *
     * <p>This method checks correct micro phone status is displayed in Micro Phone Panel.
     */
    boolean isMicroPhoneStatusMessageUpdated(String status);

    /**
     * Setup expectation: MicroPhone settings is open
     *
     * <p>This method taps on Micro Phone Permissions
     */
    void clickManageMicroPhonePermissions();

    /**
     * Setup expectation: MicroPhone settings is open, google account is added.
     *
     * <p>This method checks if Activity Control Page is displayed.
     */
    boolean isManageActivityControlOpen();

    /**
     * Setup expectation: MicroPhone settings is open, no google account is added
     *
     * <p>This method checks if No account added dialog is displayed.
     */
    boolean isNoAccountAddedDialogOpen();

    /**
     * Setup expectation: MicroPhone settings is open, no google account is added
     *
     * <p>This method checks if add account text is displayed on Autofill Page
     */
    boolean isAccountAddedAutofill();

    /**
     * Setup expectation: None
     *
     * <p>This method checks if Recently accessed apps is displayed in MicroPhone settings with
     * timestamp
     */
    boolean isRecentAppDisplayedWithStamp(String app);

    /**
     * Setup expectation: MicroPhone Settings is open
     *
     * <p>This method clicks on ViewAll Link.
     */
    void clickViewAllLink();

    /**
     * Setup expectation: open Google assistant app, microphone is on
     *
     * <p>Tap on unmuted microphone status bar
     */
    void clickUnMutedMicroPhoneStatusBar();
}
