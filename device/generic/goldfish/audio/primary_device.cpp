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

#include <log/log.h>
#include <system/audio.h>
#include "primary_device.h"
#include "stream_in.h"
#include "stream_out.h"
#include "util.h"

namespace android {
namespace hardware {
namespace audio {
namespace V6_0 {
namespace implementation {

constexpr size_t kInBufferDurationMs = 15;
constexpr size_t kOutBufferDurationMs = 15;

using ::android::hardware::Void;

PrimaryDevice::PrimaryDevice()
        : mMixer(talsa::mixerOpen(talsa::kPcmDevice)) {
    if (mMixer) {
        mMixerMasterVolumeCtl = mixer_get_ctl_by_name(mMixer.get(), "Master Playback Volume");
        mMixerCaptureVolumeCtl = mixer_get_ctl_by_name(mMixer.get(), "Capture Volume");
        mMixerMasterPaybackSwitchCtl = mixer_get_ctl_by_name(mMixer.get(), "Master Playback Switch");
        mMixerCaptureSwitchCtl = mixer_get_ctl_by_name(mMixer.get(), "Capture Switch");

        talsa::mixerSetPercentAll(mMixerMasterVolumeCtl, 100);
        talsa::mixerSetPercentAll(mMixerCaptureVolumeCtl, 100);
        talsa::mixerSetValueAll(mMixerMasterPaybackSwitchCtl, 1);
        talsa::mixerSetValueAll(mMixerCaptureSwitchCtl, 1);
    }
}

Return<Result> PrimaryDevice::initCheck() {
    return mMixer ? Result::OK : Result::NOT_INITIALIZED;
}

Return<Result> PrimaryDevice::setMasterVolume(float volume) {
    if (volume < 0 || volume > 1.0) {
        return Result::INVALID_ARGUMENTS;
    }

    if (!mMixerMasterVolumeCtl) {
        return Result::INVALID_STATE;
    }

    talsa::mixerSetPercentAll(mMixerMasterVolumeCtl, int(100 * volume));
    return Result::OK;
}

Return<void> PrimaryDevice::getMasterVolume(getMasterVolume_cb _hidl_cb) {
    if (mMixerMasterVolumeCtl) {
        _hidl_cb(Result::OK,
                 mixer_ctl_get_percent(mMixerMasterVolumeCtl, 0) / 100.0);
    } else {
        _hidl_cb(Result::INVALID_STATE, 0);
    }

    return Void();
}

Return<Result> PrimaryDevice::PrimaryDevice::setMicMute(bool mute) {
    if (mMixerCaptureSwitchCtl) {
        talsa::mixerSetValueAll(mMixerCaptureSwitchCtl, mute ? 0 : 1);
        return Result::OK;
    } else {
        return Result::INVALID_STATE;
    }
}

Return<void> PrimaryDevice::getMicMute(getMicMute_cb _hidl_cb) {
    if (mMixerCaptureSwitchCtl) {
        const int value = mixer_ctl_get_value(mMixerCaptureSwitchCtl, 0);
        _hidl_cb(Result::OK, value == 0);
    } else {
        _hidl_cb(Result::INVALID_STATE, 0);
    }
    return Void();
}

Return<Result> PrimaryDevice::setMasterMute(bool mute) {
    if (mMixerMasterPaybackSwitchCtl) {
        talsa::mixerSetValueAll(mMixerMasterPaybackSwitchCtl, mute ? 0 : 1);
        return Result::OK;
    } else {
        return Result::INVALID_STATE;
    }
}

Return<void> PrimaryDevice::getMasterMute(getMasterMute_cb _hidl_cb) {
    if (mMixerMasterPaybackSwitchCtl) {
        const int value = mixer_ctl_get_value(mMixerMasterPaybackSwitchCtl, 0);
        _hidl_cb(Result::OK, value == 0);
    } else {
        _hidl_cb(Result::NOT_SUPPORTED, 0);
    }

    return Void();
}

Return<void> PrimaryDevice::getInputBufferSize(const AudioConfig& config,
                                               getInputBufferSize_cb _hidl_cb) {
    AudioConfig suggestedConfig;
    if (util::checkAudioConfig(false, kInBufferDurationMs, config, suggestedConfig)) {
        const size_t sz =
            suggestedConfig.frameCount
            * util::countChannels(suggestedConfig.channelMask)
            * util::getBytesPerSample(suggestedConfig.format);

        _hidl_cb(Result::OK, sz);
    } else {
        ALOGE("PrimaryDevice::%s:%d failed", __func__, __LINE__);
        _hidl_cb(Result::INVALID_ARGUMENTS, 0);
    }

    return Void();
}

Return<void> PrimaryDevice::openOutputStream(int32_t ioHandle,
                                             const DeviceAddress& device,
                                             const AudioConfig& config,
                                             hidl_bitfield<AudioOutputFlag> flags,
                                             const SourceMetadata& sourceMetadata,
                                             openOutputStream_cb _hidl_cb) {
    AudioConfig suggestedConfig;
    if (util::checkAudioConfig(true, kOutBufferDurationMs, config, suggestedConfig)) {
        ++mNStreams;
        _hidl_cb(Result::OK,
                 new StreamOut(this, &unrefDevice,
                               ioHandle, device, suggestedConfig, flags, sourceMetadata),
                 config);
    } else {
        ALOGE("PrimaryDevice::%s:%d failed", __func__, __LINE__);
        _hidl_cb(Result::INVALID_ARGUMENTS, nullptr, suggestedConfig);
    }

    return Void();
}

Return<void> PrimaryDevice::openInputStream(int32_t ioHandle,
                                            const DeviceAddress& device,
                                            const AudioConfig& config,
                                            hidl_bitfield<AudioInputFlag> flags,
                                            const SinkMetadata& sinkMetadata,
                                            openInputStream_cb _hidl_cb) {
    AudioConfig suggestedConfig;
    if (util::checkAudioConfig(false, kInBufferDurationMs, config, suggestedConfig)) {
        ++mNStreams;
        _hidl_cb(Result::OK,
                 new StreamIn(this, &unrefDevice,
                              ioHandle, device, suggestedConfig, flags, sinkMetadata),
                 config);
    } else {
        ALOGE("PrimaryDevice::%s:%d failed", __func__, __LINE__);
        _hidl_cb(Result::INVALID_ARGUMENTS, nullptr, suggestedConfig);
    }

    return Void();
}

Return<bool> PrimaryDevice::supportsAudioPatches() {
    return false;
}

Return<void> PrimaryDevice::createAudioPatch(const hidl_vec<AudioPortConfig>& sources,
                                             const hidl_vec<AudioPortConfig>& sinks,
                                             createAudioPatch_cb _hidl_cb) {
    (void)sources;
    (void)sinks;
    _hidl_cb(Result::NOT_SUPPORTED, 0);
    return Void();
}

Return<void> PrimaryDevice::updateAudioPatch(int32_t previousPatch,
                                             const hidl_vec<AudioPortConfig>& sources,
                                             const hidl_vec<AudioPortConfig>& sinks,
                                             updateAudioPatch_cb _hidl_cb) {
    (void)previousPatch;
    (void)sources;
    (void)sinks;
    _hidl_cb(Result::NOT_SUPPORTED, 0);
    return Void();
}

Return<Result> PrimaryDevice::releaseAudioPatch(int32_t patch) {
    (void)patch;
    return Result::NOT_SUPPORTED;
}

Return<void> PrimaryDevice::getAudioPort(const AudioPort& port, getAudioPort_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, port);
    return Void();
}

Return<Result> PrimaryDevice::setAudioPortConfig(const AudioPortConfig& config) {
    (void)config;
    return Result::NOT_SUPPORTED;
}

Return<Result> PrimaryDevice::setScreenState(bool turnedOn) {
    (void)turnedOn;
    return Result::OK;
}

Return<void> PrimaryDevice::getHwAvSync(getHwAvSync_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, {});
    return Void();
}

Return<void> PrimaryDevice::getParameters(const hidl_vec<ParameterValue>& context,
                                          const hidl_vec<hidl_string>& keys,
                                          getParameters_cb _hidl_cb) {
    (void)context;
    if (keys.size() == 0) {
        _hidl_cb(Result::OK, {});
    } else {
        _hidl_cb(Result::NOT_SUPPORTED, {});
    }
    return Void();
}

Return<Result> PrimaryDevice::setParameters(const hidl_vec<ParameterValue>& context,
                                            const hidl_vec<ParameterValue>& parameters) {
    (void)context;
    (void)parameters;
    return Result::OK;
}

Return<void> PrimaryDevice::getMicrophones(getMicrophones_cb _hidl_cb) {
    _hidl_cb(Result::OK, {util::getMicrophoneInfo()});
    return Void();
}

Return<Result> PrimaryDevice::setConnectedState(const DeviceAddress& dev_addr, bool connected) {
    (void)dev_addr;
    (void)connected;
    return Result::NOT_SUPPORTED;
}

Return<Result> PrimaryDevice::close() {
    if (mNStreams > 0) {
        return Result::INVALID_STATE;
    } else if (mMixer) {
        mMixerMasterVolumeCtl = nullptr;
        mMixerCaptureVolumeCtl = nullptr;
        mMixerMasterPaybackSwitchCtl = nullptr;
        mMixerCaptureSwitchCtl = nullptr;
        mMixer.reset();
        return Result::OK;
    } else {
        return Result::INVALID_STATE;
    }
}

Return<Result> PrimaryDevice::addDeviceEffect(AudioPortHandle device, uint64_t effectId) {
    (void)device;
    (void)effectId;
    return Result::NOT_SUPPORTED;
}

Return<Result> PrimaryDevice::removeDeviceEffect(AudioPortHandle device, uint64_t effectId) {
    (void)device;
    (void)effectId;
    return Result::NOT_SUPPORTED;
}

Return<Result> PrimaryDevice::setVoiceVolume(float volume) {
    return (volume >= 0 && volume <= 1.0) ? Result::OK : Result::INVALID_ARGUMENTS;
}

Return<Result> PrimaryDevice::setMode(AudioMode mode) {
    switch (mode) {
    case AudioMode::NORMAL:
    case AudioMode::RINGTONE:
    case AudioMode::IN_CALL:
    case AudioMode::IN_COMMUNICATION:
        return Result::OK;

    default:
        return Result::INVALID_ARGUMENTS;
    }
}

Return<Result> PrimaryDevice::setBtScoHeadsetDebugName(const hidl_string& name) {
    (void)name;
    return Result::NOT_SUPPORTED;
}

Return<void> PrimaryDevice::getBtScoNrecEnabled(getBtScoNrecEnabled_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, false);
    return Void();
}

Return<Result> PrimaryDevice::setBtScoNrecEnabled(bool enabled) {
    (void)enabled;
    return Result::NOT_SUPPORTED;
}

Return<void> PrimaryDevice::getBtScoWidebandEnabled(getBtScoWidebandEnabled_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, false);
    return Void();
}

Return<Result> PrimaryDevice::setBtScoWidebandEnabled(bool enabled) {
    (void)enabled;
    return Result::NOT_SUPPORTED;
}

Return<void> PrimaryDevice::getTtyMode(getTtyMode_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, TtyMode::OFF);
    return Void();
}

Return<Result> PrimaryDevice::setTtyMode(IPrimaryDevice::TtyMode mode) {
    (void)mode;
    return Result::NOT_SUPPORTED;
}

Return<void> PrimaryDevice::getHacEnabled(getHacEnabled_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, false);
    return Void();
}

Return<Result> PrimaryDevice::setHacEnabled(bool enabled) {
    (void)enabled;
    return Result::NOT_SUPPORTED;
}

Return<void> PrimaryDevice::getBtHfpEnabled(getBtHfpEnabled_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, false);
    return Void();
}

Return<Result> PrimaryDevice::setBtHfpEnabled(bool enabled) {
    (void)enabled;
    return Result::NOT_SUPPORTED;
}

Return<Result> PrimaryDevice::setBtHfpSampleRate(uint32_t sampleRateHz) {
    (void)sampleRateHz;
    return Result::NOT_SUPPORTED;
}

Return<Result> PrimaryDevice::setBtHfpVolume(float volume) {
    (void)volume;
    return Result::NOT_SUPPORTED;
}

Return<Result> PrimaryDevice::updateRotation(IPrimaryDevice::Rotation rotation) {
    (void)rotation;
    return Result::NOT_SUPPORTED;
}

void PrimaryDevice::unrefDevice(IDevice *dev) {
    static_cast<PrimaryDevice *>(dev)->unrefDeviceImpl();
}

void PrimaryDevice::unrefDeviceImpl() {
    LOG_ALWAYS_FATAL_IF(--mNStreams < 0);
}

}  // namespace implementation
}  // namespace V6_0
}  // namespace audio
}  // namespace hardware
}  // namespace android
