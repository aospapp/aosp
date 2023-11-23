/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tv.settings.device.displaysound;


import static android.content.DialogInterface.OnClickListener;
import static android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION;

import static com.android.tv.settings.device.displaysound.DisplaySoundUtils.createAlertDialog;
import static com.android.tv.settings.device.displaysound.DisplaySoundUtils.disableHdrType;
import static com.android.tv.settings.device.displaysound.DisplaySoundUtils.doesCurrentModeNotSupportDvBecauseLimitedTo4k30;
import static com.android.tv.settings.device.displaysound.DisplaySoundUtils.enableHdrType;
import static com.android.tv.settings.device.displaysound.DisplaySoundUtils.findMode1080p60;
import static com.android.tv.settings.device.displaysound.DisplaySoundUtils.isHdrFormatSupported;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.HdrConversionMode;
import android.view.Display;

import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

import com.android.settingslib.core.AbstractPreferenceController;
import com.android.tv.settings.R;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controller for the hdr formats switch preferences.
 */
public class HdrFormatPreferenceController extends AbstractPreferenceController {

    private final int mHdrType;
    private final DisplayManager mDisplayManager;

    public HdrFormatPreferenceController(
            Context context, int hdrType, DisplayManager displayManager) {
        super(context);
        mHdrType = hdrType;
        mDisplayManager = displayManager;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getPreferenceKey() {
        return HdrFormatSelectionFragment.KEY_HDR_FORMAT_PREFIX + mHdrType;
    }

    @Override
    public void updateState(Preference preference) {
        super.updateState(preference);
        if (preference.getKey().equals(getPreferenceKey())) {
            preference.setEnabled(getPreferenceEnabledState());
            Display.Mode mode = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY).getMode();
            if (!isHdrFormatSupported(mode, mHdrType)) {
                ((SwitchPreference) preference).setChecked(false);
            } else {
                ((SwitchPreference) preference).setChecked(getPreferenceCheckedState());
            }
        }
    }

    @Override
    public boolean handlePreferenceTreeClick(Preference preference) {
        if (preference.getKey().equals(getPreferenceKey())) {
            onPreferenceClicked((SwitchPreference) preference);
        }
        return super.handlePreferenceTreeClick(preference);
    }

    /**
     * @return checked state of a HDR format switch based displayManager.
     */
    private boolean getPreferenceCheckedState() {
        if (!mDisplayManager.areUserDisabledHdrTypesAllowed()) {
            return !(toSet(mDisplayManager.getUserDisabledHdrTypes()).contains(mHdrType));
        }
        return true;
    }

    /** @return true if the format checkboxes should be enabled, i.e. in manual mode. */
    private boolean getPreferenceEnabledState() {
        return !mDisplayManager.areUserDisabledHdrTypesAllowed();
    }

    /**
     * Handler for when this particular format preference is clicked.
     */
    private void onPreferenceClicked(SwitchPreference preference) {
        final boolean enabled = preference.isChecked();

        if (enabled) {
            Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (mHdrType == HDR_TYPE_DOLBY_VISION
                    && doesCurrentModeNotSupportDvBecauseLimitedTo4k30(display)) {
                enableDvAndChangeTo1080p60(display, preference);
            } else {
                enableHdrType(mDisplayManager, mHdrType);
            }
        } else {
            disableHdrType(mDisplayManager, mHdrType);
            // If HDR output type is mHdrType, change the HDR output type. This can happen in 2
            // cases:
            // mHdrType is selected by implementation in case of AUTO - re-calling
            // setHdrConversionMode will handle this as mHdrType is no longer permissible coz of
            // setUserDisabledHdrTypes
            // mHdrType is selected by user by using FORCE. Change the preferred strategy to AUTO
            // and mHdrType is no longer permissible coz of setUserDisabledHdrTypes
            if (mDisplayManager.getHdrConversionModeSetting().getPreferredHdrOutputType()
                    == mHdrType) {
                mDisplayManager.setHdrConversionMode(
                        new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_SYSTEM));
            }
        }
    }

    private void enableDvAndChangeTo1080p60(Display display, SwitchPreference preference) {
        String dialogDescription =
                mContext.getString(
                        R.string.preferred_dynamic_range_force_dialog_desc_4k30_issue);
        String title =
                mContext.getString(
                        R.string.manual_dolby_vision_format_on_4k60_title);
        OnClickListener onOkClicked = (dialog, which) -> {
            mDisplayManager.setGlobalUserPreferredDisplayMode(findMode1080p60(display));
            preference.setChecked(true);
            enableHdrType(mDisplayManager, HDR_TYPE_DOLBY_VISION);
            dialog.dismiss();

        };
        OnClickListener onCancelClicked = (dialog, which) -> {
            dialog.dismiss();
            preference.setChecked(false);
        };
        createAlertDialog(mContext, title, dialogDescription, onOkClicked, onCancelClicked)
                .show();
    }

    private int[] toArray(Set<Integer> set) {
        return set.stream().mapToInt(Integer::intValue).toArray();
    }

    private Set<Integer> toSet(int[] array) {
        return Arrays.stream(array).boxed().collect(Collectors.toSet());
    }
}
