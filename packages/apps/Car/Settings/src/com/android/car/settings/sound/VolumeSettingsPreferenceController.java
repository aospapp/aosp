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

package com.android.car.settings.sound;

import static android.car.media.CarAudioManager.AUDIO_FEATURE_DYNAMIC_ROUTING;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_VOLUME_GROUP_MUTING;
import static android.os.UserManager.DISALLOW_ADJUST_VOLUME;

import static com.android.car.settings.enterprise.ActionDisabledByAdminDialogFragment.DISABLED_BY_ADMIN_CONFIRM_DIALOG_TAG;
import static com.android.car.settings.enterprise.EnterpriseUtils.hasUserRestrictionByDpm;
import static com.android.car.settings.enterprise.EnterpriseUtils.hasUserRestrictionByUm;
import static com.android.car.settings.sound.VolumeItemParser.VolumeItem;

import android.car.CarNotConnectedException;
import android.car.drivingstate.CarUxRestrictions;
import android.car.media.CarAudioManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.XmlRes;
import androidx.preference.PreferenceGroup;

import com.android.car.settings.CarSettingsApplication;
import com.android.car.settings.R;
import com.android.car.settings.common.FragmentController;
import com.android.car.settings.common.Logger;
import com.android.car.settings.common.PreferenceController;
import com.android.car.settings.common.SeekBarPreference;
import com.android.car.settings.enterprise.EnterpriseUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Business logic which parses car volume items into groups, creates a seek bar preference for each
 * group, and interfaces with the ringtone manager and audio manager.
 *
 * @see VolumeSettingsRingtoneManager
 * @see android.car.media.CarAudioManager
 */
public class VolumeSettingsPreferenceController extends PreferenceController<PreferenceGroup> {
    private static final Logger LOG = new Logger(VolumeSettingsPreferenceController.class);
    private static final String VOLUME_GROUP_KEY = "volume_group_key";
    private static final String VOLUME_USAGE_KEY = "volume_usage_key";

    private final SparseArray<VolumeItem> mVolumeItems;
    private final List<VolumeSeekBarPreference> mVolumePreferences = new ArrayList<>();
    private final VolumeSettingsRingtoneManager mRingtoneManager;

    private final Handler mUiHandler;

    @VisibleForTesting
    final CarAudioManager.CarVolumeCallback mVolumeChangeCallback =
            new CarAudioManager.CarVolumeCallback() {
                @Override
                public void onGroupVolumeChanged(int zoneId, int groupId, int flags) {
                    updateVolumeAndMute(zoneId, groupId);
                }

                @Override
                public void onMasterMuteChanged(int zoneId, int flags) {

                    // Mute is not being used yet
                }

                @Override
                public void onGroupMuteChanged(int zoneId, int groupId, int flags) {
                    updateVolumeAndMute(zoneId, groupId);
                }
            };

    public VolumeSettingsPreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions) {
        this(context, preferenceKey, fragmentController, uxRestrictions,
                new VolumeSettingsRingtoneManager(context));
    }

    @VisibleForTesting
    VolumeSettingsPreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions,
            VolumeSettingsRingtoneManager ringtoneManager) {
        super(context, preferenceKey, fragmentController, uxRestrictions);
        mRingtoneManager = ringtoneManager;
        mVolumeItems = VolumeItemParser.loadAudioUsageItems(context, carVolumeItemsXml());
        mUiHandler = new Handler(Looper.getMainLooper());

        CarAudioManager carAudioManager = getCarAudioManager();
        if (carAudioManager != null) {
            int zoneId = getMyAudioZoneId();
            int volumeGroupCount = carAudioManager.getVolumeGroupCount(zoneId);
            cleanUpVolumePreferences();
            // Populates volume slider items from volume groups to UI.
            for (int groupId = 0; groupId < volumeGroupCount; groupId++) {
                VolumeItem volumeItem = getVolumeItemForUsages(
                        carAudioManager.getUsagesForVolumeGroupId(zoneId, groupId));
                VolumeSeekBarPreference volumePreference = createVolumeSeekBarPreference(
                        groupId, volumeItem.getUsage(), volumeItem.getIcon(),
                        volumeItem.getMuteIcon(), volumeItem.getTitle());
                setClickableWhileDisabled(volumePreference, /* clickable= */ true, p -> {
                    if (hasUserRestrictionByDpm(getContext(), DISALLOW_ADJUST_VOLUME)) {
                        showActionDisabledByAdminDialog();
                    } else {
                        Toast.makeText(getContext(),
                                getContext().getString(R.string.action_unavailable),
                                Toast.LENGTH_LONG).show();
                    }
                });
                mVolumePreferences.add(volumePreference);
            }
            carAudioManager.registerCarVolumeCallback(mVolumeChangeCallback);
        }
    }

    @Override
    protected Class<PreferenceGroup> getPreferenceType() {
        return PreferenceGroup.class;
    }

    /** Disconnect from car on destroy. */
    @Override
    protected void onDestroyInternal() {
        cleanupAudioManager();
    }

    @Override
    protected void updateState(PreferenceGroup preferenceGroup) {
        for (SeekBarPreference preference : mVolumePreferences) {
            preferenceGroup.addPreference(preference);
        }
    }

    /**
     * The resource which lists the car volume resources associated with the various usage enums.
     */
    @XmlRes
    @VisibleForTesting
    int carVolumeItemsXml() {
        return R.xml.car_volume_items;
    }

    private VolumeSeekBarPreference createVolumeSeekBarPreference(
            int volumeGroupId, int usage, @DrawableRes int primaryIconResId,
            @DrawableRes int secondaryIconResId, @StringRes int titleId) {
        VolumeSeekBarPreference preference = new VolumeSeekBarPreference(getContext());
        preference.setTitle(getContext().getString(titleId));
        preference.setUnMutedIcon(getContext().getDrawable(primaryIconResId));
        preference.getUnMutedIcon().setTintList(
                getContext().getColorStateList(R.color.icon_color_default));
        preference.setMutedIcon(getContext().getDrawable(secondaryIconResId));
        preference.getMutedIcon().setTintList(
                getContext().getColorStateList(R.color.icon_color_default));

        int zoneId = getMyAudioZoneId();
        CarAudioManager carAudioManager = getCarAudioManager();
        try {
            if (carAudioManager != null) {
                preference.setValue(carAudioManager.getGroupVolume(zoneId, volumeGroupId));
                preference.setMin(carAudioManager.getGroupMinVolume(zoneId, volumeGroupId));
                preference.setMax(carAudioManager.getGroupMaxVolume(zoneId, volumeGroupId));
                preference.setIsMuted(isGroupMuted(carAudioManager, volumeGroupId));
            }
        } catch (CarNotConnectedException e) {
            LOG.e("Car is not connected!", e);
        }
        preference.setContinuousUpdate(true);
        preference.setShowSeekBarValue(false);
        Bundle bundle = preference.getExtras();
        bundle.putInt(VOLUME_GROUP_KEY, volumeGroupId);
        bundle.putInt(VOLUME_USAGE_KEY, usage);
        preference.setOnPreferenceChangeListener((pref, newValue) -> {
            int prefGroup = pref.getExtras().getInt(VOLUME_GROUP_KEY);
            int prefUsage = pref.getExtras().getInt(VOLUME_USAGE_KEY);
            int newVolume = (Integer) newValue;
            setGroupVolume(prefGroup, newVolume);

            if (carAudioManager != null
                    && (!carAudioManager.isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING)
                    || !carAudioManager.isPlaybackOnVolumeGroupActive(zoneId, volumeGroupId))) {
                mRingtoneManager.playAudioFeedback(prefGroup, prefUsage);
            }
            return true;
        });
        return preference;
    }

    private boolean isGroupMuted(CarAudioManager carAudioManager, int volumeGroupId) {
        if (!carAudioManager.isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_MUTING)) {
            return false;
        }
        return carAudioManager.isVolumeGroupMuted(getMyAudioZoneId(), volumeGroupId);
    }

    private void updateVolumeAndMute(int zoneId, int groupId) {
        if (zoneId != getMyAudioZoneId()) {
            return;
        }

        CarAudioManager carAudioManager = getCarAudioManager();
        if (carAudioManager != null) {

            boolean isMuted = isGroupMuted(carAudioManager, groupId);
            int value = carAudioManager.getGroupVolume(getMyAudioZoneId(), groupId);

            for (VolumeSeekBarPreference volumePreference : mVolumePreferences) {
                Bundle extras = volumePreference.getExtras();
                if (extras.getInt(VOLUME_GROUP_KEY) == groupId) {
                    if (volumePreference.isMuted() != isMuted
                            || value != volumePreference.getValue()) {
                        mUiHandler.post(() -> {
                            volumePreference.setIsMuted(isMuted);
                            volumePreference.setValue(value);
                        });
                    }
                    break;
                }
            }
        }
    }

    private void setGroupVolume(int volumeGroupId, int newVolume) {
        try {
            getCarAudioManager()
                    .setGroupVolume(getMyAudioZoneId(), volumeGroupId, newVolume, /* flags= */ 0);
        } catch (CarNotConnectedException e) {
            LOG.w("Ignoring volume change event because the car isn't connected", e);
        }
    }

    private void cleanupAudioManager() {
        cleanUpVolumePreferences();
        getCarAudioManager().unregisterCarVolumeCallback(mVolumeChangeCallback);
    }

    private void cleanUpVolumePreferences() {
        mRingtoneManager.stopCurrentRingtone();
        mVolumePreferences.clear();
    }

    private VolumeItem getVolumeItemForUsages(int[] usages) {
        int rank = Integer.MAX_VALUE;
        VolumeItem result = null;
        for (int usage : usages) {
            VolumeItem volumeItem = mVolumeItems.get(usage);
            if (volumeItem.getRank() < rank) {
                rank = volumeItem.getRank();
                result = volumeItem;
            }
        }
        return result;
    }

    @Override
    public int getDefaultAvailabilityStatus() {
        if (hasUserRestrictionByUm(getContext(), DISALLOW_ADJUST_VOLUME)
                || hasUserRestrictionByDpm(getContext(), DISALLOW_ADJUST_VOLUME)) {
            return AVAILABLE_FOR_VIEWING;
        }
        return AVAILABLE;
    }

    private void showActionDisabledByAdminDialog() {
        getFragmentController().showDialog(
                EnterpriseUtils.getActionDisabledByAdminDialog(getContext(),
                        DISALLOW_ADJUST_VOLUME),
                DISABLED_BY_ADMIN_CONFIRM_DIALOG_TAG);
    }

    private int getMyAudioZoneId() {
        return ((CarSettingsApplication) getContext().getApplicationContext())
                .getMyAudioZoneId();
    }

    private CarAudioManager getCarAudioManager() {
        return ((CarSettingsApplication) getContext().getApplicationContext())
                .getCarAudioManager();
    }
}
