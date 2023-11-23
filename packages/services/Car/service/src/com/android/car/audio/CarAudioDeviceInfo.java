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

import static android.media.AudioFormat.ENCODING_PCM_16BIT;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.car.builtin.media.AudioManagerHelper;
import android.car.builtin.media.AudioManagerHelper.AudioGainInfo;
import android.car.builtin.util.Slogf;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import com.android.car.CarLog;
import com.android.car.audio.hal.HalAudioDeviceInfo;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;

/**
 * A helper class wraps {@link AudioDeviceInfo}, and helps get/set the gain on a specific port
 * in terms of millibels.
 * Note to the reader. For whatever reason, it seems that AudioGain contains only configuration
 * information (min/max/step, etc) while the AudioGainConfig class contains the
 * actual currently active gain value(s).
 */
/* package */ class CarAudioDeviceInfo {

    public static final int DEFAULT_SAMPLE_RATE = 48000;
    private final AudioDeviceInfo mAudioDeviceInfo;
    private final int mSampleRate;
    private final int mEncodingFormat;
    private final int mChannelCount;
    private final AudioManager mAudioManager;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private int mDefaultGain;
    @GuardedBy("mLock")
    private int mMaxGain;
    @GuardedBy("mLock")
    private int mMinGain;
    @GuardedBy("mLock")
    private int mStepValue;
    @GuardedBy("mLock")
    private boolean mCanBeRoutedWithDynamicPolicyMixRule = true;


    /**
     * We need to store the current gain because it is not accessible from the current
     * audio engine implementation. It would be nice if AudioPort#activeConfig() would return it,
     * but in the current implementation, that function actually works only for mixer ports.
     */
    private int mCurrentGain;

    CarAudioDeviceInfo(AudioManager audioManager, AudioDeviceInfo audioDeviceInfo) {
        mAudioManager = audioManager;
        mAudioDeviceInfo = audioDeviceInfo;
        mSampleRate = getMaxSampleRate(audioDeviceInfo);
        mEncodingFormat = ENCODING_PCM_16BIT;
        mChannelCount = getMaxChannels(audioDeviceInfo);
        AudioGainInfo audioGainInfo = AudioManagerHelper.getAudioGainInfo(audioDeviceInfo);
        mDefaultGain = audioGainInfo.getDefaultGain();
        mMaxGain = audioGainInfo.getMaxGain();
        mMinGain = audioGainInfo.getMinGain();
        mStepValue = audioGainInfo.getStepValue();

        mCurrentGain = -1; // Not initialized till explicitly set
    }

    AudioDeviceInfo getAudioDeviceInfo() {
        return mAudioDeviceInfo;
    }

    /**
     * By default, considers all AudioDevice can be used to establish dynamic policy mixing rules.
     * until validation state is performed.
     * Once called, the device is marked definitively as "connot be routed with dynamic mixes".
     */
    void resetCanBeRoutedWithDynamicPolicyMix() {
        synchronized (mLock) {
            mCanBeRoutedWithDynamicPolicyMixRule = false;
        }
    }

    boolean canBeRoutedWithDynamicPolicyMix() {
        synchronized (mLock) {
            return mCanBeRoutedWithDynamicPolicyMixRule;
        }
    }

    String getAddress() {
        return mAudioDeviceInfo.getAddress();
    }

    int getDefaultGain() {
        synchronized (mLock) {
            return mDefaultGain;
        }
    }

    int getMaxGain() {
        synchronized (mLock) {
            return mMaxGain;
        }
    }

    int getMinGain() {
        synchronized (mLock) {
            return mMinGain;
        }
    }

    int getSampleRate() {
        return mSampleRate;
    }

    int getEncodingFormat() {
        return mEncodingFormat;
    }

    int getChannelCount() {
        return mChannelCount;
    }

    int getStepValue() {
        synchronized (mLock) {
            return mStepValue;
        }
    }


    // Input is in millibels
    void setCurrentGain(int gainInMillibels) {
        int gain = gainInMillibels;
        // Clamp the incoming value to our valid range.  Out of range values ARE legal input
        synchronized (mLock) {
            if (gain < mMinGain) {
                gain = mMinGain;
            } else if (gain > mMaxGain) {
                gain = mMaxGain;
            }
        }

        if (AudioManagerHelper.setAudioDeviceGain(mAudioManager,
                getAddress(), gain, true)) {
            // Since we can't query for the gain on a device port later,
            // we have to remember what we asked for
            mCurrentGain = gain;
        } else {
            Slogf.e(CarLog.TAG_AUDIO, "Failed to setAudioPortGain " + gain
                    + " for output device " + getAddress());
        }
    }

    // Updates audio device info for dynamic gain stage configurations
    void updateAudioDeviceInfo(HalAudioDeviceInfo halDeviceInfo) {
        synchronized (mLock) {
            mMinGain = halDeviceInfo.getGainMinValue();
            mMaxGain = halDeviceInfo.getGainMaxValue();
            mStepValue = halDeviceInfo.getGainStepValue();
            mDefaultGain = halDeviceInfo.getGainDefaultValue();
        }
    }

    private static int getMaxSampleRate(AudioDeviceInfo info) {
        int[] sampleRates = info.getSampleRates();
        if (sampleRates == null || sampleRates.length == 0) {
            return DEFAULT_SAMPLE_RATE;
        }
        int sampleRate = sampleRates[0];
        for (int i = 1; i < sampleRates.length; i++) {
            if (sampleRates[i] > sampleRate) {
                sampleRate = sampleRates[i];
            }
        }
        return sampleRate;
    }

    private static int getMaxChannels(AudioDeviceInfo info) {
        int numChannels = 1;
        int[] channelMasks = info.getChannelMasks();
        if (channelMasks == null) {
            return numChannels;
        }
        for (int channelMask : channelMasks) {
            int currentNumChannels = Integer.bitCount(channelMask);
            if (currentNumChannels > numChannels) {
                numChannels = currentNumChannels;
            }
        }
        return numChannels;
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
    public String toString() {
        return "address: " + mAudioDeviceInfo.getAddress()
                + " sampleRate: " + getSampleRate()
                + " encodingFormat: " + getEncodingFormat()
                + " channelCount: " + getChannelCount()
                + " currentGain: " + mCurrentGain
                + " maxGain: " + getMaxGain()
                + " minGain: " + getMinGain();
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    void dump(IndentingPrintWriter writer) {
        synchronized (mLock) {
            writer.printf("CarAudioDeviceInfo Device(%s)\n", mAudioDeviceInfo.getAddress());
            writer.increaseIndent();
            writer.printf("Routing with Dynamic Mix enabled (%b)\n",
                    mCanBeRoutedWithDynamicPolicyMixRule);
            writer.printf("sample rate / encoding format / channel count: %d %d %d\n",
                    getSampleRate(), getEncodingFormat(), getChannelCount());
            writer.printf("Gain values (min / max / default/ current): %d %d %d %d\n",
                    mMinGain, mMaxGain, mDefaultGain, mCurrentGain);
            writer.decreaseIndent();
        }
    }
}
