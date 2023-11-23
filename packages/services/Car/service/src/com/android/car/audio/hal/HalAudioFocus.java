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

package com.android.car.audio.hal;

import static android.car.builtin.media.AudioManagerHelper.usageToString;
import static android.media.AudioManager.AUDIOFOCUS_LOSS;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.NonNull;
import android.car.builtin.util.Slogf;
import android.car.media.CarAudioManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.annotation.AttributeUsage;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Manages focus requests from the HAL on a per-zone per-usage basis
 */
public final class HalAudioFocus implements HalFocusListener {
    private static final String TAG = CarLog.tagFor(HalAudioFocus.class);

    private final AudioManager mAudioManager;
    private final AudioControlWrapper mAudioControlWrapper;

    private final Object mLock = new Object();

    // Map of Maps. Top level keys are ZoneIds. Second level keys are usages.
    // Values are HalAudioFocusRequests
    @GuardedBy("mLock")
    private final SparseArray<SparseArray<HalAudioFocusRequest>> mHalFocusRequestsByZoneAndUsage;

    public HalAudioFocus(@NonNull AudioManager audioManager,
            @NonNull AudioControlWrapper audioControlWrapper,
            @NonNull int[] audioZoneIds) {
        mAudioManager = Objects.requireNonNull(audioManager);
        mAudioControlWrapper = Objects.requireNonNull(audioControlWrapper);
        Objects.requireNonNull(audioZoneIds);

        mHalFocusRequestsByZoneAndUsage = new SparseArray<>(audioZoneIds.length);
        for (int zoneId : audioZoneIds) {
            mHalFocusRequestsByZoneAndUsage.append(zoneId, new SparseArray<>());
        }
    }

    /**
     * Registers {@code IFocusListener} on {@code AudioControlWrapper} to receive HAL audio focus
     * request and abandon calls.
     */
    public void registerFocusListener() {
        mAudioControlWrapper.registerFocusListener(this);
    }

    /**
     * Unregisters {@code IFocusListener} from {@code AudioControlWrapper}.
     */
    public void unregisterFocusListener() {
        mAudioControlWrapper.unregisterFocusListener();
    }

    /**
     * See {@link HalFocusListener#requestAudioFocus(int, int, int)}
     */
    public void requestAudioFocus(@AttributeUsage int usage, int zoneId, int focusGain) {
        synchronized (mLock) {
            Preconditions.checkArgument(mHalFocusRequestsByZoneAndUsage.contains(zoneId),
                    "Invalid zoneId %d provided in requestAudioFocus", zoneId);
            if (Slogf.isLoggable(TAG, Log.DEBUG)) {
                Slogf.d(TAG, "Requesting focus gain " + focusGain + " with usage "
                        + usageToString(usage) + " and zoneId " + zoneId);
            }
            HalAudioFocusRequest currentRequest = mHalFocusRequestsByZoneAndUsage.get(zoneId).get(
                    usage);
            if (currentRequest != null) {
                if (Slogf.isLoggable(TAG, Log.DEBUG)) {
                    Slogf.d(TAG, "A request already exists for zoneId " + zoneId + " and usage "
                            + usage);
                }
                mAudioControlWrapper.onAudioFocusChange(usage, zoneId, currentRequest.mFocusStatus);
            } else {
                makeAudioFocusRequestLocked(usage, zoneId, focusGain);
            }
        }
    }

    /**
     * See {@link HalFocusListener#abandonAudioFocus(int, int)}
     */
    public void abandonAudioFocus(@AttributeUsage int usage, int zoneId) {
        synchronized (mLock) {
            Preconditions.checkArgument(mHalFocusRequestsByZoneAndUsage.contains(zoneId),
                    "Invalid zoneId %d provided in abandonAudioFocus", zoneId);
            if (Slogf.isLoggable(TAG, Log.DEBUG)) {
                Slogf.d(TAG, "Abandoning focus with usage " + usageToString(usage)
                        + " for zoneId " + zoneId);
            }
            abandonAudioFocusLocked(usage, zoneId);
        }
    }

    /**
     * Clear out all existing focus requests. Called when HAL dies.
     */
    public void reset() {
        Slogf.d(TAG, "Resetting HAL Audio Focus requests");
        synchronized (mLock) {
            for (int i = 0; i < mHalFocusRequestsByZoneAndUsage.size(); i++) {
                int zoneId = mHalFocusRequestsByZoneAndUsage.keyAt(i);
                SparseArray<HalAudioFocusRequest> requestsByUsage =
                        mHalFocusRequestsByZoneAndUsage.valueAt(i);
                int usageCount = requestsByUsage.size();
                for (int j = 0; j < usageCount; j++) {
                    int usage = requestsByUsage.keyAt(j);
                    abandonAudioFocusLocked(usage, zoneId);
                }
            }
        }
    }

    /**
     * Returns the currently active {@code AttributeUsage}'s for an audio zone
     */
    public @AttributeUsage int[]  getActiveUsagesForZone(int audioZoneId) {
        synchronized (mLock) {
            SparseArray<HalAudioFocusRequest> halFocusRequestsForZone =
                    mHalFocusRequestsByZoneAndUsage.get(audioZoneId);
            int [] activeUsages = new int[halFocusRequestsForZone.size()];
            for (int index = 0; index < halFocusRequestsForZone.size(); index++) {
                activeUsages[index] = halFocusRequestsForZone.keyAt(index);
            }
            return activeUsages;
        }
    }

    /**
     * dumps the current state of the HalAudioFocus
     *
     * @param writer stream to write current state
     */
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.println("*HalAudioFocus*");

        writer.increaseIndent();
        writer.println("Current focus requests:");
        writer.increaseIndent();
        synchronized (mLock) {
            for (int i = 0; i < mHalFocusRequestsByZoneAndUsage.size(); i++) {
                int zoneId = mHalFocusRequestsByZoneAndUsage.keyAt(i);
                writer.printf("Zone %s:\n", zoneId);
                writer.increaseIndent();

                SparseArray<HalAudioFocusRequest> requestsByUsage =
                        mHalFocusRequestsByZoneAndUsage.valueAt(i);
                for (int j = 0; j < requestsByUsage.size(); j++) {
                    int usage = requestsByUsage.keyAt(j);
                    HalAudioFocusRequest request = requestsByUsage.valueAt(j);
                    writer.printf("%s - focusGain: %s\n", usageToString(usage),
                            request.mFocusStatus);
                }
                writer.decreaseIndent();
            }
        }
        writer.decreaseIndent();
        writer.decreaseIndent();
    }

    @GuardedBy("mLock")
    private void abandonAudioFocusLocked(int usage, int zoneId) {
        SparseArray<HalAudioFocusRequest> halAudioFocusRequests = mHalFocusRequestsByZoneAndUsage
                .get(zoneId);
        HalAudioFocusRequest currentRequest = halAudioFocusRequests.get(usage);

        if (currentRequest == null) {
            if (Slogf.isLoggable(TAG, Log.DEBUG)) {
                Slogf.d(TAG, "No focus to abandon for usage " + usageToString(usage)
                        + " and zoneId " + zoneId);
            }
            return;
        } else {
            // remove it from map
            halAudioFocusRequests.remove(usage);
        }

        int result = mAudioManager.abandonAudioFocusRequest(currentRequest.mAudioFocusRequest);
        if (result == AUDIOFOCUS_REQUEST_GRANTED) {
            if (Slogf.isLoggable(TAG, Log.DEBUG)) {
                Slogf.d(TAG, "Abandoned focus for usage " + usageToString(usage)
                        + "and zoneId " + zoneId);
            }
            mAudioControlWrapper.onAudioFocusChange(usage, zoneId, AUDIOFOCUS_LOSS);
        } else {
            Slogf.w(TAG, "Failed to abandon focus for usage " + usageToString(usage)
                    + " and zoneId " + zoneId);
        }
    }

    private AudioAttributes generateAudioAttributes(int usage, int zoneId) {
        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        Bundle bundle = new Bundle();
        bundle.putInt(CarAudioManager.AUDIOFOCUS_EXTRA_REQUEST_ZONE_ID, zoneId);
        builder.addBundle(bundle);

        if (AudioAttributes.isSystemUsage(usage)) {
            builder.setSystemUsage(usage);
        } else {
            builder.setUsage(usage);
        }
        return builder.build();
    }

    @GuardedBy("mLock")
    private AudioFocusRequest generateFocusRequestLocked(int usage, int zoneId, int focusGain) {
        AudioAttributes attributes = generateAudioAttributes(usage, zoneId);
        return new AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener((int focusChange) -> {
                    onAudioFocusChange(usage, zoneId, focusChange);
                })
                .build();
    }

    private void onAudioFocusChange(int usage, int zoneId, int focusChange) {
        synchronized (mLock) {
            HalAudioFocusRequest currentRequest = mHalFocusRequestsByZoneAndUsage.get(zoneId).get(
                    usage);
            if (currentRequest != null) {
                if (focusChange == AUDIOFOCUS_LOSS) {
                    mHalFocusRequestsByZoneAndUsage.get(zoneId).remove(usage);
                } else {
                    currentRequest.mFocusStatus = focusChange;
                }
                mAudioControlWrapper.onAudioFocusChange(usage, zoneId, focusChange);
            }

        }
    }

    @GuardedBy("mLock")
    private void makeAudioFocusRequestLocked(@AttributeUsage int usage, int zoneId, int focusGain) {
        AudioFocusRequest audioFocusRequest = generateFocusRequestLocked(usage, zoneId, focusGain);

        int requestResult = mAudioManager.requestAudioFocus(audioFocusRequest);

        int resultingFocusGain = focusGain;

        if (requestResult == AUDIOFOCUS_REQUEST_GRANTED) {
            HalAudioFocusRequest halAudioFocusRequest = new HalAudioFocusRequest(audioFocusRequest,
                    focusGain);
            mHalFocusRequestsByZoneAndUsage.get(zoneId).append(usage, halAudioFocusRequest);
        } else if (requestResult == AUDIOFOCUS_REQUEST_FAILED) {
            resultingFocusGain = AUDIOFOCUS_LOSS;
        } else if (requestResult == AUDIOFOCUS_REQUEST_DELAYED) {
            Slogf.w(TAG, "Delayed result for request with usage "
                    + usageToString(usage) + ", zoneId " + zoneId
                    + ", and focusGain " + focusGain);
            resultingFocusGain = AUDIOFOCUS_LOSS;
        }

        mAudioControlWrapper.onAudioFocusChange(usage, zoneId, resultingFocusGain);
    }

    private final class HalAudioFocusRequest {
        final AudioFocusRequest mAudioFocusRequest;

        int mFocusStatus;

        HalAudioFocusRequest(AudioFocusRequest audioFocusRequest, int focusStatus) {
            mAudioFocusRequest = audioFocusRequest;
            mFocusStatus = focusStatus;
        }
    }
}
