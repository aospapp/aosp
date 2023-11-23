// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef ANDROID_C2_ENCODER_INTERFACE_H
#define ANDROID_C2_ENCODER_INTERFACE_H

#include <vector>

#include <base/optional.h>
#include <C2.h>
#include <C2Buffer.h>
#include <C2Config.h>
#include <util/C2InterfaceHelper.h>

#include <size.h>
#include <video_codecs.h>
#include <v4l2_codec2/common/EncodeHelpers.h>

namespace android {

class C2EncoderInterface : public C2InterfaceHelper {
public:
    C2EncoderInterface(const std::shared_ptr<C2ReflectorHelper>& helper);

    // Interfaces for the C2EncoderInterface
    // Note: these getters are not thread-safe. For dynamic parameters, component should use
    // formal query API for C2ComponentInterface instead.
    c2_status_t status() const { return mInitStatus; }
    C2Config::profile_t getOutputProfile() const { return mProfileLevel->profile; }
    C2Config::level_t getOutputLevel() const { return mProfileLevel->level; }
    const media::Size getInputVisibleSize() const {
        return media::Size(mInputVisibleSize->width, mInputVisibleSize->height);
    }
    C2BlockPool::local_id_t getBlockPoolId() const { return mOutputBlockPoolIds->m.values[0]; }
    // Get sync key-frame period in frames.
    uint32_t getKeyFramePeriod() const;

protected:
    void Initialize(const C2String& name, const std::vector<VideoEncodeProfile>& supportedProfiles);

    virtual base::Optional<media::VideoCodec> getCodecFromComponentName(
            const std::string& name) const = 0;

    // Configurable parameter setters.
    static C2R ProfileLevelSetter(bool mayBlock, C2P<C2StreamProfileLevelInfo::output>& info,
                                  const C2P<C2StreamPictureSizeInfo::input>& videosize,
                                  const C2P<C2StreamFrameRateInfo::output>& frameRate,
                                  const C2P<C2StreamBitrateInfo::output>& bitrate);

    static C2R SizeSetter(bool mayBlock, C2P<C2StreamPictureSizeInfo::input>& videoSize);

    static C2R IntraRefreshPeriodSetter(bool mayBlock,
                                        C2P<C2StreamIntraRefreshTuning::output>& period);

    // Constant parameters

    // The input format kind; should be C2FormatVideo.
    std::shared_ptr<C2StreamBufferTypeSetting::input> mInputFormat;
    // The output format kind; should be C2FormatCompressed.
    std::shared_ptr<C2StreamBufferTypeSetting::output> mOutputFormat;
    // The MIME type of input port; should be MEDIA_MIMETYPE_VIDEO_RAW.
    std::shared_ptr<C2PortMediaTypeSetting::input> mInputMediaType;
    // The MIME type of output port.
    std::shared_ptr<C2PortMediaTypeSetting::output> mOutputMediaType;

    // The suggested usage of input buffer allocator ID.
    std::shared_ptr<C2PortAllocatorsTuning::input> mInputAllocatorIds;
    // The suggested usage of output buffer allocator ID.
    std::shared_ptr<C2PortAllocatorsTuning::output> mOutputAllocatorIds;

    // Initialization parameters

    // The visible size for input raw video.
    std::shared_ptr<C2StreamPictureSizeInfo::input> mInputVisibleSize;
    // The output codec profile and level.
    std::shared_ptr<C2StreamProfileLevelInfo::output> mProfileLevel;
    // The expected period for key frames in microseconds.
    std::shared_ptr<C2StreamSyncFrameIntervalTuning::output> mKeyFramePeriodUs;
    // Component uses this ID to fetch corresponding output block pool from platform.
    std::shared_ptr<C2PortBlockPoolsTuning::output> mOutputBlockPoolIds;

    // Dynamic parameters

    // The requested bitrate of the encoded output stream, in bits per second.
    std::shared_ptr<C2StreamBitrateInfo::output> mBitrate;
    // The requested framerate, in frames per second.
    std::shared_ptr<C2StreamFrameRateInfo::output> mFrameRate;
    // The switch-type parameter that will be set to true while client requests keyframe. It
    // will be reset once encoder gets the request.
    std::shared_ptr<C2StreamRequestSyncFrameTuning::output> mRequestKeyFrame;
    // The intra-frame refresh period. This is unused for the component now.
    // TODO: adapt intra refresh period to encoder.
    std::shared_ptr<C2StreamIntraRefreshTuning::output> mIntraRefreshPeriod;

    c2_status_t mInitStatus = C2_NO_INIT;
};

}  // namespace android

#endif  // ANDROID_C2_ENCODER_INTERFACE_H
