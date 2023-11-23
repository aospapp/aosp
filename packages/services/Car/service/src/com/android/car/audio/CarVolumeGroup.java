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
package com.android.car.audio;

import static android.car.media.CarVolumeGroupEvent.EVENT_TYPE_ATTENUATION_CHANGED;
import static android.car.media.CarVolumeGroupEvent.EVENT_TYPE_MUTE_CHANGED;
import static android.car.media.CarVolumeGroupEvent.EVENT_TYPE_VOLUME_BLOCKED_CHANGED;
import static android.car.media.CarVolumeGroupEvent.EVENT_TYPE_VOLUME_GAIN_INDEX_CHANGED;

import static com.android.car.audio.hal.HalAudioGainCallback.reasonToString;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.car.builtin.util.Slogf;
import android.car.media.CarVolumeGroupInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.audio.CarAudioContext.AudioContext;
import com.android.car.audio.hal.HalAudioDeviceInfo;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A class encapsulates a volume group in car.
 *
 * Interface holding volume interface APIs and also common code for:
 *
 * -volume groups using {@link AudioManager#setAudioPortGain} to control the volume
 * while the audioserver resource config_useFixedVolume is set.
 *
 * -volume groups relying on audioserver to control the volume and access using
 * {@link AudioManager#setVolumeIndexForAttributes(AudioAttributes, int, int)} and all other
 * related volume APIs.
 * Gain may either be controlled on hardware amplifier using Audio HAL setaudioPortConfig if the
 * correlated audio device port defines a gain controller with attribute name="useForVolume" set
 * or in software using the port id in Audio flinger.
 * Gains are set only when activity is detected on the given audio device port (Mixer thread, or
 * {@link android.media.HwAudioSource} realized through a software bridge or hardware bridge.
 *
 */
/* package */ abstract class CarVolumeGroup {
    public static final int UNINITIALIZED = -1;

    private final boolean mUseCarVolumeGroupMute;
    private final boolean mHasCriticalAudioContexts;
    private final CarAudioSettings mSettingsManager;
    protected final int mId;
    private final String mName;
    protected final int mZoneId;
    protected final int mConfigId;
    protected final SparseArray<String> mContextToAddress;
    protected final ArrayMap<String, CarAudioDeviceInfo> mAddressToCarAudioDeviceInfo;

    protected final Object mLock = new Object();
    private final CarAudioContext mCarAudioContext;

    @GuardedBy("mLock")
    protected int mStoredGainIndex;

    @GuardedBy("mLock")
    protected int mCurrentGainIndex = UNINITIALIZED;

    /**
     * Mute state for requests coming from clients. See {@link #mIsHalMuted} for state of requests
     * coming from HAL.
     */
    @GuardedBy("mLock")
    protected boolean mIsMuted;
    @GuardedBy("mLock")
    protected @UserIdInt int mUserId = UserHandle.CURRENT.getIdentifier();

    /**
     * Attenuated gain is set to {@link #UNINITIALIZED} till attenuation explicitly reported by
     * {@link com.android.car.audio.hal.HalAudioGainCallback#onAudioDeviceGainsChanged} for one or
     * more {@link android.hardware.automotive.audiocontrol.Reasons}. When the reason is cleared,
     * it returns back to {@link #UNINITIALIZED}.
     */
    @GuardedBy("mLock")
    protected int mAttenuatedGainIndex = UNINITIALIZED;

    /**
     * Limitation gain is set to max gain value till limitation explicitly reported by {@link
     * com.android.car.audio.hal.HalAudioGainCallback#onAudioDeviceGainsChanged} for one or more
     * {@link android.hardware.automotive.audiocontrol.Reasons}. When the reason is cleared, it
     * returns back to max.
     */
    @GuardedBy("mLock")
    protected int mLimitedGainIndex;

    /**
     * Blocked gain is set to {@link #UNINITIALIZED} till blocking case explicitly reported by
     * {@link com.android.car.audio.hal.HalAudioGainCallback#onAudioDeviceGainsChanged} for one or
     * more {@link android.hardware.automotive.audiocontrol.Reasons}. When the reason is cleared,
     * it returns back to {@link #UNINITIALIZED}.
     */
    @GuardedBy("mLock")
    protected int mBlockedGainIndex = UNINITIALIZED;

    /**
     * The default state of HAL mute is {@code false} until HAL explicitly reports through
     * {@link com.android.car.audio.hal.HalAudioGainCallback#onAudioDeviceGainsChanged} for one or
     * more {@link android.hardware.automotive.audiocontrol.Reasons}. When the reason
     * is cleared, it is reset. See {@link #mIsMuted} for state of requests coming from clients.
     */
    @GuardedBy("mLock")
    private boolean mIsHalMuted = false;

    /**
     * Reasons list currently reported for this port by {@link
     * com.android.car.audio.hal.HalAudioGainCallback#onAudioDeviceGainsChanged}.
     */
    protected List<Integer> mReasons = new ArrayList<>();

    protected CarVolumeGroup(CarAudioContext carAudioContext, CarAudioSettings settingsManager,
            SparseArray<String> contextToAddress, ArrayMap<String,
            CarAudioDeviceInfo> addressToCarAudioDeviceInfo, int zoneId, int configId,
            int volumeGroupId, String name, boolean useCarVolumeGroupMute) {
        mSettingsManager = settingsManager;
        mContextToAddress = contextToAddress;
        mAddressToCarAudioDeviceInfo = addressToCarAudioDeviceInfo;
        mCarAudioContext = carAudioContext;
        mZoneId = zoneId;
        mConfigId = configId;
        mId = volumeGroupId;
        mName = Objects.requireNonNull(name, "Volume group name cannot be null");
        mUseCarVolumeGroupMute = useCarVolumeGroupMute;
        List<AudioAttributes> volumeAttributes = new ArrayList<>();
        for (int index = 0; index <  contextToAddress.size(); index++) {
            int context = contextToAddress.keyAt(index);
            List<AudioAttributes> audioAttributes =
                    Arrays.asList(mCarAudioContext.getAudioAttributesForContext(context));
            volumeAttributes.addAll(audioAttributes);
        }

        mHasCriticalAudioContexts = containsCriticalAttributes(volumeAttributes);
    }

    void init() {
        synchronized (mLock) {
            mStoredGainIndex = mSettingsManager.getStoredVolumeGainIndexForUser(
                    mUserId, mZoneId, mConfigId, mId);
            updateCurrentGainIndexLocked();
        }
    }

    @GuardedBy("mLock")
    protected boolean hasPendingAttenuationReasonsLocked() {
        return !mReasons.isEmpty();
    }

    @GuardedBy("mLock")
    protected void setBlockedLocked(int blockedIndex) {
        mBlockedGainIndex = blockedIndex;
    }

    @GuardedBy("mLock")
    protected void resetBlockedLocked() {
        setBlockedLocked(UNINITIALIZED);
    }

    @GuardedBy("mLock")
    protected boolean isBlockedLocked() {
        return mBlockedGainIndex != UNINITIALIZED;
    }

    @GuardedBy("mLock")
    protected void setLimitLocked(int limitIndex) {
        mLimitedGainIndex = limitIndex;
    }

    @GuardedBy("mLock")
    protected void resetLimitLocked() {
        setLimitLocked(getMaxGainIndex());
    }

    @GuardedBy("mLock")
    protected boolean isLimitedLocked() {
        return mLimitedGainIndex != getMaxGainIndex();
    }

    @GuardedBy("mLock")
    protected boolean isOverLimitLocked() {
        return isOverLimitLocked(mCurrentGainIndex);
    }

    @GuardedBy("mLock")
    protected boolean isOverLimitLocked(int index) {
        return isLimitedLocked() && (index > mLimitedGainIndex);
    }

    @GuardedBy("mLock")
    protected void setAttenuatedGainLocked(int attenuatedGainIndex) {
        mAttenuatedGainIndex = attenuatedGainIndex;
    }

    @GuardedBy("mLock")
    protected void resetAttenuationLocked() {
        setAttenuatedGainLocked(UNINITIALIZED);
    }

    @GuardedBy("mLock")
    protected boolean isAttenuatedLocked() {
        return mAttenuatedGainIndex != UNINITIALIZED;
    }

    @GuardedBy("mLock")
    private void setHalMuteLocked(boolean mute) {
        mIsHalMuted = mute;
    }

    @GuardedBy("mLock")
    protected boolean isHalMutedLocked() {
        return mIsHalMuted;
    }

    void setBlocked(int blockedIndex) {
        synchronized (mLock) {
            setBlockedLocked(blockedIndex);
        }
    }

    void resetBlocked() {
        synchronized (mLock) {
            resetBlockedLocked();
        }
    }

    boolean isBlocked() {
        synchronized (mLock) {
            return isBlockedLocked();
        }
    }

    void setLimit(int limitIndex) {
        synchronized (mLock) {
            setLimitLocked(limitIndex);
        }
    }

    void resetLimit() {
        synchronized (mLock) {
            resetLimitLocked();
        }
    }

    boolean isLimited() {
        synchronized (mLock) {
            return isLimitedLocked();
        }
    }

    boolean isOverLimit() {
        synchronized (mLock) {
            return isOverLimitLocked();
        }
    }

    void setAttenuatedGain(int attenuatedGainIndex) {
        synchronized (mLock) {
            setAttenuatedGainLocked(attenuatedGainIndex);
        }
    }

    void resetAttenuation() {
        synchronized (mLock) {
            resetAttenuationLocked();
        }
    }

    boolean isAttenuated() {
        synchronized (mLock) {
            return isAttenuatedLocked();
        }
    }

    @Nullable
    CarAudioDeviceInfo getCarAudioDeviceInfoForAddress(String address) {
        return mAddressToCarAudioDeviceInfo.get(address);
    }

    @AudioContext
    int[] getContexts() {
        final int[] carAudioContexts = new int[mContextToAddress.size()];
        for (int i = 0; i < carAudioContexts.length; i++) {
            carAudioContexts[i] = mContextToAddress.keyAt(i);
        }
        return carAudioContexts;
    }

    /**
     * Returns the id of the volume group.
     * <p> Note that all clients are already developed in the way that when they get the number of
     * volume group, they will then address a given volume group using its id as if the id was the
     * index of the array of group (aka 0 to length - 1).
     */
    int getId() {
        return mId;
    }

    String getName() {
        return mName;
    }

    /**
     * Returns the devices address for the given context
     * or {@code null} if the context does not exist in the volume group
     */
    @Nullable
    String getAddressForContext(int audioContext) {
        return mContextToAddress.get(audioContext);
    }

    /**
     * Returns the audio devices for the given context
     * or {@code null} if the context does not exist in the volume group
     */
    @Nullable
    AudioDeviceInfo getAudioDeviceForContext(int audioContext) {
        String address = getAddressForContext(audioContext);
        if (address == null) {
            return null;
        }

        CarAudioDeviceInfo info = mAddressToCarAudioDeviceInfo.get(address);
        if (info == null) {
            return null;
        }

        return info.getAudioDeviceInfo();
    }

    @AudioContext
    List<Integer> getContextsForAddress(@NonNull String address) {
        List<Integer> carAudioContexts = new ArrayList<>();
        for (int i = 0; i < mContextToAddress.size(); i++) {
            String value = mContextToAddress.valueAt(i);
            if (address.equals(value)) {
                carAudioContexts.add(mContextToAddress.keyAt(i));
            }
        }
        return carAudioContexts;
    }

    List<String> getAddresses() {
        return new ArrayList<>(mAddressToCarAudioDeviceInfo.keySet());
    }

    List<Integer> getAllSupportedUsagesForAddress(@NonNull String address) {
        List<Integer> supportedUsagesForAddress = new ArrayList<>();
        List<Integer> contextsForAddress = getContextsForAddress(address);
        for (int contextIndex = 0; contextIndex < contextsForAddress.size(); contextIndex++) {
            int contextId = contextsForAddress.get(contextIndex);
            AudioAttributes[] attributes =
                    mCarAudioContext.getAudioAttributesForContext(contextId);
            for (int attrIndex = 0; attrIndex < attributes.length; attrIndex++) {
                int usage = attributes[attrIndex].getSystemUsage();
                if (!supportedUsagesForAddress.contains(usage)) {
                    supportedUsagesForAddress.add(usage);
                }
            }
        }
        return supportedUsagesForAddress;
    }

    abstract int getMaxGainIndex();

    abstract int getMinGainIndex();

    int getCurrentGainIndex() {
        synchronized (mLock) {
            if (isMutedLocked()) {
                return getMinGainIndex();
            }

            return getRestrictedGainForIndexLocked(getCurrentGainIndexLocked());
        }
    }

    @GuardedBy("mLock")
    protected int getCurrentGainIndexLocked() {
        return mCurrentGainIndex;
    }

    @GuardedBy("mLock")
    protected int getRestrictedGainForIndexLocked(int index) {
        if (isBlockedLocked()) {
            return mBlockedGainIndex;
        }
        if (isOverLimitLocked()) {
            return mLimitedGainIndex;
        }
        if (isAttenuatedLocked()) {
            // Need to figure out if attenuation shall be hidden to end user
            // as while ducked from IAudioControl
            // TODO(b/) clarify in case of volume adjustment if the reference index is the
            // ducked index or the current index. Taking current may lead to gap of index > 1.
            return mAttenuatedGainIndex;
        }
        return index;
    }

    /**
     * Sets the gain on this group, gain will be set on all devices within volume group.
     */
    void setCurrentGainIndex(int gainIndex) {
        synchronized (mLock) {
            int currentgainIndex = gainIndex;
            Preconditions.checkArgument(isValidGainIndexLocked(gainIndex),
                    "Gain out of range (%d:%d) index %d", getMinGainIndex(), getMaxGainIndex(),
                    gainIndex);
            if (isBlockedLocked()) {
                // prevent any volume change while {@link IAudioGainCallback} reported block event.
                return;
            }
            if (isOverLimitLocked(currentgainIndex)) {
                currentgainIndex = mLimitedGainIndex;
            }
            if (isAttenuatedLocked()) {
                resetAttenuationLocked();
            }
            // In case of attenuation/Limitation, requested index is now the new reference for
            // cached current index.
            mCurrentGainIndex = currentgainIndex;

            if (mIsMuted) {
                setMuteLocked(false);
            }
            setCurrentGainIndexLocked(mCurrentGainIndex);
        }
    }

    @GuardedBy("mLock")
    protected void setCurrentGainIndexLocked(int gainIndex) {
        storeGainIndexForUserLocked(gainIndex, mUserId);
    }

    boolean hasCriticalAudioContexts() {
        return mHasCriticalAudioContexts;
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    public String toString() {
        synchronized (mLock) {
            return "CarVolumeGroup id: " + mId
                    + " currentGainIndex: " + mCurrentGainIndex
                    + " contexts: " + Arrays.toString(getContexts())
                    + " addresses: " + String.join(", ", getAddresses());
        }
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    protected abstract void dumpLocked(IndentingPrintWriter writer);

    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    void dump(IndentingPrintWriter writer) {
        synchronized (mLock) {
            writer.printf("CarVolumeGroup(%d)\n", mId);
            writer.increaseIndent();
            writer.printf("Name(%s)\n", mName);
            writer.printf("Zone Id(%d)\n", mZoneId);
            writer.printf("Configuration Id(%d)\n", mConfigId);
            writer.printf("Is Muted(%b)\n", isMutedLocked());
            writer.printf("UserId(%d)\n", mUserId);
            writer.printf("Persist Volume Group Mute(%b)\n",
                    mSettingsManager.isPersistVolumeGroupMuteEnabled(mUserId));
            dumpLocked(writer);
            writer.printf("Gain indexes (min / max / default / current): %d %d %d %d\n",
                    getMinGainIndex(), getMaxGainIndex(), getDefaultGainIndex(),
                    mCurrentGainIndex);
            for (int i = 0; i < mContextToAddress.size(); i++) {
                writer.printf("Context: %s -> Address: %s\n",
                        mCarAudioContext.toString(mContextToAddress.keyAt(i)),
                        mContextToAddress.valueAt(i));
            }
            for (int i = 0; i < mAddressToCarAudioDeviceInfo.size(); i++) {
                String address = mAddressToCarAudioDeviceInfo.keyAt(i);
                CarAudioDeviceInfo info = mAddressToCarAudioDeviceInfo.get(address);
                info.dump(writer);
            }
            writer.printf("Reported reasons:\n");
            writer.increaseIndent();
            for (int index = 0; index < mReasons.size(); index++) {
                int reason = mReasons.get(index);
                writer.printf("%s\n", reasonToString(reason));
            }
            writer.decreaseIndent();
            writer.printf("Gain infos:\n");
            writer.increaseIndent();
            writer.printf(
                    "Blocked: %b%s\n",
                    isBlockedLocked(),
                    (isBlockedLocked() ? " (at: " + mBlockedGainIndex + ")" : ""));
            writer.printf(
                    "Limited: %b%s\n",
                    isLimitedLocked(),
                    (isLimitedLocked() ? " (at: " + mLimitedGainIndex + ")" : ""));
            writer.printf(
                    "Attenuated: %b%s\n",
                    isAttenuatedLocked(),
                    (isAttenuatedLocked() ? " (at: " + mAttenuatedGainIndex + ")" : ""));
            writer.printf("Muted by HAL: %b\n", isHalMutedLocked());
            writer.decreaseIndent();
            // Empty line for comfortable reading
            writer.println();
            writer.decreaseIndent();
        }
    }

    void loadVolumesSettingsForUser(@UserIdInt int userId) {
        synchronized (mLock) {
            //Update the volume for the new user
            updateUserIdLocked(userId);
            //Update the current gain index
            updateCurrentGainIndexLocked();
            setCurrentGainIndexLocked(getCurrentGainIndexLocked());
            //Reset devices with current gain index
            updateGroupMuteLocked();
        }
    }

    /**
     * Set the mute state of the Volume Group
     *
     * @param mute state requested
     * @return true if mute state has changed, false otherwiser (already set or change not allowed)
     */
    boolean setMute(boolean mute) {
        synchronized (mLock) {
            // if hal muted the audio devices, then do not allow other incoming requests
            // to perform unmute.
            if (!mute && isHalMutedLocked()) {
                Slogf.e(CarLog.TAG_AUDIO, "Un-mute request cannot be processed due to active "
                        + "hal mute restriction!");
                return false;
            }
            applyMuteLocked(mute);
            return setMuteLocked(mute);
        }
    }

    @GuardedBy("mLock")
    protected boolean setMuteLocked(boolean mute) {
        boolean hasChanged = mIsMuted != mute;
        mIsMuted = mute;
        if (mSettingsManager.isPersistVolumeGroupMuteEnabled(mUserId)) {
            mSettingsManager.storeVolumeGroupMuteForUser(mUserId, mZoneId, mConfigId, mId, mute);
        }
        return hasChanged;
    }

    @GuardedBy("mLock")
    protected void applyMuteLocked(boolean mute) {
    }

    boolean isMuted() {
        synchronized (mLock) {
            return isMutedLocked();
        }
    }

    @GuardedBy("mLock")
    protected boolean isMutedLocked() {
        // if either of the mute states is set, it results in group being muted.
        return isUserMutedLocked() || isHalMutedLocked();
    }

    @GuardedBy("mLock")
    protected boolean isUserMutedLocked() {
        return mIsMuted;
    }

    @GuardedBy("mLock")
    protected boolean isFullyMutedLocked() {
        return isUserMutedLocked() || isHalMutedLocked() || isBlockedLocked();
    }

    private static boolean containsCriticalAttributes(List<AudioAttributes> volumeAttributes) {
        for (int index = 0; index < volumeAttributes.size(); index++) {
            if (CarAudioContext.isCriticalAudioAudioAttribute(volumeAttributes.get(index))) {
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    private void updateUserIdLocked(@UserIdInt int userId) {
        mUserId = userId;
        mStoredGainIndex = getCurrentGainIndexForUserLocked();
    }

    @GuardedBy("mLock")
    private int getCurrentGainIndexForUserLocked() {
        int gainIndexForUser = mSettingsManager.getStoredVolumeGainIndexForUser(mUserId, mZoneId,
                mConfigId, mId);
        Slogf.i(CarLog.TAG_AUDIO, "updateUserId userId " + mUserId
                + " gainIndexForUser " + gainIndexForUser);
        return gainIndexForUser;
    }

    /**
     * Update the current gain index based on the stored gain index
     */
    @GuardedBy("mLock")
    private void updateCurrentGainIndexLocked() {
        if (isValidGainIndexLocked(mStoredGainIndex)) {
            mCurrentGainIndex = mStoredGainIndex;
        } else {
            mCurrentGainIndex = getDefaultGainIndex();
        }
    }

    protected boolean isValidGainIndex(int gainIndex) {
        synchronized (mLock) {
            return isValidGainIndexLocked(gainIndex);
        }
    }
    protected abstract boolean isValidGainIndexLocked(int gainIndex);

    protected abstract int getDefaultGainIndex();

    @GuardedBy("mLock")
    private void storeGainIndexForUserLocked(int gainIndex, @UserIdInt int userId) {
        mSettingsManager.storeVolumeGainIndexForUser(userId,
                mZoneId, mConfigId, mId, gainIndex);
    }

    @GuardedBy("mLock")
    private void updateGroupMuteLocked() {
        if (!mUseCarVolumeGroupMute) {
            return;
        }
        if (!mSettingsManager.isPersistVolumeGroupMuteEnabled(mUserId)) {
            mIsMuted = false;
            return;
        }
        mIsMuted = mSettingsManager.getVolumeGroupMuteForUser(mUserId, mZoneId, mConfigId, mId);
        applyMuteLocked(isFullyMutedLocked());
    }

    /**
     * Updates volume group states (index, mute, blocked etc) on callback from audio control hal.
     *
     * <p>If gain config info carries duplicate info, do not generate events (i.e. eventType = 0)
     * @param halReasons reasons for change to gain config info
     * @param gain updated gain config info
     * @return one or more of {@link EventTypeEnum}. Or 0 for duplicate gain config info
     */
    int onAudioGainChanged(List<Integer> halReasons, CarAudioGainConfigInfo gain) {
        int eventType = 0;
        int halIndex = gain.getVolumeIndex();
        if (getCarAudioDeviceInfoForAddress(gain.getDeviceAddress()) == null
                || !isValidGainIndex(halIndex)) {
            Slogf.e(CarLog.TAG_AUDIO,
                    "onAudioGainChanged invalid CarAudioGainConfigInfo: " + gain
                    + " for group id: " + mId);
            return eventType;
        }
        synchronized (mLock) {
            int previousRestrictedIndex = getRestrictedGainForIndexLocked(mCurrentGainIndex);
            mReasons = new ArrayList<>(halReasons);

            boolean shouldBlock = CarAudioGainMonitor.shouldBlockVolumeRequest(halReasons);
            if ((shouldBlock != isBlockedLocked())
                    || (shouldBlock && (halIndex != mBlockedGainIndex))) {
                setBlockedLocked(shouldBlock ? halIndex : UNINITIALIZED);
                eventType |= EVENT_TYPE_VOLUME_BLOCKED_CHANGED;
            }

            boolean shouldLimit = CarAudioGainMonitor.shouldLimitVolume(halReasons);
            if ((shouldLimit != isLimitedLocked())
                    || (shouldLimit && (halIndex != mLimitedGainIndex))) {
                setLimitLocked(shouldLimit ? halIndex : getMaxGainIndex());
                eventType |= EVENT_TYPE_ATTENUATION_CHANGED;
            }

            boolean shouldDuck = CarAudioGainMonitor.shouldDuckGain(halReasons);
            if ((shouldDuck != isAttenuatedLocked())
                    || (shouldDuck && (halIndex != mAttenuatedGainIndex))) {
                setAttenuatedGainLocked(shouldDuck ? halIndex : UNINITIALIZED);
                eventType |= EVENT_TYPE_ATTENUATION_CHANGED;
            }

            // Accept mute callbacks from hal only if group mute is enabled.
            // If disabled, such callbacks will be considered as blocking restriction only.
            boolean shouldMute = CarAudioGainMonitor.shouldMuteVolumeGroup(halReasons);
            if (mUseCarVolumeGroupMute && (shouldMute != isHalMutedLocked())) {
                setHalMuteLocked(shouldMute);
                eventType |= EVENT_TYPE_MUTE_CHANGED;
            }

            if (CarAudioGainMonitor.shouldUpdateVolumeIndex(halReasons)
                    && (halIndex != getRestrictedGainForIndexLocked(mCurrentGainIndex))) {
                mCurrentGainIndex = halIndex;
                eventType |= EVENT_TYPE_VOLUME_GAIN_INDEX_CHANGED;
            }

            // Blocked/Attenuated index shall have been already apply by Audio HAL on HW.
            // However, keep in sync & broadcast to all ports this volume group deals with.
            //
            // Do not update current gain cache, keep it for restoring rather using reported index
            // when the event is cleared.
            int newRestrictedIndex = getRestrictedGainForIndexLocked(mCurrentGainIndex);
            setCurrentGainIndexLocked(newRestrictedIndex);
            // Hal or user mute state can change (only user mute enabled while hal muted allowed).
            // Force a sync of mute application.
            applyMuteLocked(isFullyMutedLocked());

            if (newRestrictedIndex != previousRestrictedIndex) {
                eventType |= EVENT_TYPE_VOLUME_GAIN_INDEX_CHANGED;
            }
        }
        return eventType;
    }

    CarVolumeGroupInfo getCarVolumeGroupInfo() {
        int gainIndex;
        boolean isMuted;
        boolean isBlocked;
        boolean isAttenuated;
        synchronized (mLock) {
            gainIndex = getRestrictedGainForIndexLocked(mCurrentGainIndex);
            isMuted = isMutedLocked();
            isBlocked = isBlockedLocked();
            isAttenuated = isAttenuatedLocked() || isLimitedLocked();
        }

        return new CarVolumeGroupInfo.Builder("group id " + mId, mZoneId, mId)
                .setVolumeGainIndex(gainIndex).setMaxVolumeGainIndex(getMaxGainIndex())
                .setMinVolumeGainIndex(getMinGainIndex()).setMuted(isMuted).setBlocked(isBlocked)
                .setAttenuated(isAttenuated).setAudioAttributes(getAudioAttributes()).build();
    }

    List<AudioAttributes> getAudioAttributes() {
        List<AudioAttributes> audioAttributes = new ArrayList<>();
        for (int index = 0; index < mContextToAddress.size(); index++) {
            int context = mContextToAddress.keyAt(index);
            AudioAttributes[] contextAttributes =
                    mCarAudioContext.getAudioAttributesForContext(context);
            for (int attrIndex = 0; attrIndex < contextAttributes.length; attrIndex++) {
                audioAttributes.add(contextAttributes[attrIndex]);
            }
        }

        return audioAttributes;
    }

    /**
     * @return one or more {@link CarVolumeGroupEvent#EventTypeEnum}
     */
    public int onAudioVolumeGroupChanged(int flags) {
        return 0;
    }

    /**
     * Updates car audio device info with the hal audio device info
     */
    void updateAudioDeviceInfo(HalAudioDeviceInfo halDeviceInfo) {
        synchronized (mLock) {
            CarAudioDeviceInfo info = mAddressToCarAudioDeviceInfo.get(halDeviceInfo.getAddress());
            if (info == null) {
                Slogf.w(CarLog.TAG_AUDIO, "No matching car audio device info found for address: %s",
                        halDeviceInfo.getAddress());
                return;
            }
            info.updateAudioDeviceInfo(halDeviceInfo);
        }
    }

    /**
     * Calculates the new gain stages from list of assigned audio device infos
     *
     * <p>Used to update audio device gain stages dynamically.
     *
     * @return  one or more of {@link EventTypeEnum}. Or 0 if dynamic updates are not supported
     */
    int calculateNewGainStageFromDeviceInfos() {
        return 0;
    }
}
