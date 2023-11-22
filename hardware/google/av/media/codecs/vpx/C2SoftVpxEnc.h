/*
 * Copyright 2018 The Android Open Source Project
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

#ifndef ANDROID_C2_SOFT_VPX_ENC_H__
#define ANDROID_C2_SOFT_VPX_ENC_H__

#include <media/stagefright/foundation/MediaDefs.h>

#include <C2PlatformSupport.h>
#include <Codec2BufferUtils.h>
#include <SimpleC2Component.h>
#include <SimpleC2Interface.h>
#include <util/C2InterfaceHelper.h>

#include "vpx/vpx_encoder.h"
#include "vpx/vpx_codec.h"
#include "vpx/vpx_image.h"
#include "vpx/vp8cx.h"

namespace android {

// TODO: These defs taken from deprecated OMX_VideoExt.h. Move these definitions
// to a new header file and include it.

/** Maximum number of temporal layers */
#define MAXTEMPORALLAYERS 3

/** temporal layer patterns */
typedef enum TemporalPatternType {
    VPXTemporalLayerPatternNone = 0,
    VPXTemporalLayerPatternWebRTC = 1,
    VPXTemporalLayerPatternMax = 0x7FFFFFFF
} TemporalPatternType;

// Base class for a VPX Encoder Component
//
// Only following encoder settings are available (codec specific settings might
// be available in the sub-classes):
//    - video resolution
//    - target bitrate
//    - rate control (constant / variable)
//    - frame rate
//    - error resilience
//    - reconstruction & loop filters (g_profile)
//
// Only following color formats are recognized
//    - C2PlanarLayout::TYPE_RGB
//    - C2PlanarLayout::TYPE_RGBA
//
// Following settings are not configurable by the client
//    - encoding deadline is realtime
//    - multithreaded encoding utilizes a number of threads equal
// to online cpu's available
//    - the algorithm interface for encoder is decided by the sub-class in use
//    - fractional bits of frame rate is discarded
//    - timestamps are in microseconds, therefore encoder timebase is fixed
// to 1/1000000

struct C2SoftVpxEnc : public SimpleC2Component {
    class IntfImpl;

    C2SoftVpxEnc(const char* name, c2_node_id_t id,
                 const std::shared_ptr<IntfImpl>& intfImpl);

    // From SimpleC2Component
    c2_status_t onInit() override final;
    c2_status_t onStop() override final;
    void onReset() override final;
    void onRelease() override final;
    c2_status_t onFlush_sm() override final;

    void process(
            const std::unique_ptr<C2Work> &work,
            const std::shared_ptr<C2BlockPool> &pool) override final;
    c2_status_t drain(
            uint32_t drainMode,
            const std::shared_ptr<C2BlockPool> &pool) override final;

 protected:
     std::shared_ptr<IntfImpl> mIntf;
     virtual ~C2SoftVpxEnc();

     // Initializes vpx encoder with available settings.
     status_t initEncoder();

     // Populates mCodecInterface with codec specific settings.
     virtual void setCodecSpecificInterface() = 0;

     // Sets codec specific configuration.
     virtual void setCodecSpecificConfiguration() = 0;

     // Sets codec specific encoder controls.
     virtual vpx_codec_err_t setCodecSpecificControls() = 0;

     // Get current encode flags.
     virtual vpx_enc_frame_flags_t getEncodeFlags();

     enum TemporalReferences {
         // For 1 layer case: reference all (last, golden, and alt ref), but only
         // update last.
         kTemporalUpdateLastRefAll = 12,
         // First base layer frame for 3 temporal layers, which updates last and
         // golden with alt ref dependency.
         kTemporalUpdateLastAndGoldenRefAltRef = 11,
         // First enhancement layer with alt ref dependency.
         kTemporalUpdateGoldenRefAltRef = 10,
         // First enhancement layer with alt ref dependency.
         kTemporalUpdateGoldenWithoutDependencyRefAltRef = 9,
         // Base layer with alt ref dependency.
         kTemporalUpdateLastRefAltRef = 8,
         // Highest enhacement layer without dependency on golden with alt ref
         // dependency.
         kTemporalUpdateNoneNoRefGoldenRefAltRef = 7,
         // Second layer and last frame in cycle, for 2 layers.
         kTemporalUpdateNoneNoRefAltref = 6,
         // Highest enhancement layer.
         kTemporalUpdateNone = 5,
         // Second enhancement layer.
         kTemporalUpdateAltref = 4,
         // Second enhancement layer without dependency on previous frames in
         // the second enhancement layer.
         kTemporalUpdateAltrefWithoutDependency = 3,
         // First enhancement layer.
         kTemporalUpdateGolden = 2,
         // First enhancement layer without dependency on previous frames in
         // the first enhancement layer.
         kTemporalUpdateGoldenWithoutDependency = 1,
         // Base layer.
         kTemporalUpdateLast = 0,
     };
     enum {
         kMaxTemporalPattern = 8
     };

     // vpx specific opaque data structure that
     // stores encoder state
     vpx_codec_ctx_t* mCodecContext;

     // vpx specific data structure that
     // stores encoder configuration
     vpx_codec_enc_cfg_t* mCodecConfiguration;

     // vpx specific read-only data structure
     // that specifies algorithm interface (e.g. vp8)
     vpx_codec_iface_t* mCodecInterface;

     // align stride to the power of 2
     int32_t mStrideAlign;

     // target bitrate set for the encoder, in bits per second
     uint32_t mBitrate;

     // target framerate set for the encoder
     uint32_t mFramerate;

     // Color format for the input port
     vpx_img_fmt_t mColorFormat;

     // If a request for a change it bitrate has been received.
     bool mBitrateUpdated;

     // Bitrate control mode, either constant or variable
     vpx_rc_mode mBitrateControlMode;

     // Parameter that denotes whether error resilience
     // is enabled in encoder
     bool mErrorResilience;

     // Key frame interval in frames
     uint32_t mKeyFrameInterval;

     // Minimum (best quality) quantizer
     uint32_t mMinQuantizer;

     // Maximum (worst quality) quantizer
     uint32_t mMaxQuantizer;

     // Number of coding temporal layers to be used.
     size_t mTemporalLayers;

     // Temporal layer bitrare ratio in percentage
     uint32_t mTemporalLayerBitrateRatio[MAXTEMPORALLAYERS];

     // Temporal pattern type
     TemporalPatternType mTemporalPatternType;

     // Temporal pattern length
     size_t mTemporalPatternLength;

     // Temporal pattern current index
     size_t mTemporalPatternIdx;

     // Frame type temporal pattern
     TemporalReferences mTemporalPattern[kMaxTemporalPattern];

     // Last input buffer timestamp
     uint64_t mLastTimestamp;

     // Number of input frames
     int64_t mNumInputFrames;

     // Conversion buffer is needed to input to
     // yuv420 planar format.
     MemoryBlock mConversionBuffer;

     // Request Key Frame
     bool mKeyFrameRequested;

     // Signalled EOS
     bool mSignalledOutputEos;

     // Signalled Error
     bool mSignalledError;

     C2_DO_NOT_COPY(C2SoftVpxEnc);
};

class C2SoftVpxEnc::IntfImpl : public C2InterfaceHelper {
   public:
    explicit IntfImpl(const std::shared_ptr<C2ReflectorHelper>& helper)
        : C2InterfaceHelper(helper) {
        setDerivedInstance(this);

        addParameter(
            DefineParam(mInputFormat, C2_NAME_INPUT_STREAM_FORMAT_SETTING)
                .withConstValue(
                    new C2StreamFormatConfig::input(0u, C2FormatVideo))
                .build());

        addParameter(
            DefineParam(mOutputFormat, C2_NAME_OUTPUT_STREAM_FORMAT_SETTING)
                .withConstValue(
                    new C2StreamFormatConfig::output(0u, C2FormatCompressed))
                .build());

        addParameter(
            DefineParam(mInputMediaType, C2_NAME_INPUT_PORT_MIME_SETTING)
                .withConstValue(AllocSharedString<C2PortMimeConfig::input>(
                    MEDIA_MIMETYPE_VIDEO_RAW))
                .build());

        addParameter(
            DefineParam(mOutputMediaType, C2_NAME_OUTPUT_PORT_MIME_SETTING)
                .withConstValue(AllocSharedString<C2PortMimeConfig::output>(
#ifdef VP9
                    MEDIA_MIMETYPE_VIDEO_VP9
#else
                    MEDIA_MIMETYPE_VIDEO_VP8
#endif
                    ))
                .build());

        addParameter(DefineParam(mUsage, C2_NAME_INPUT_STREAM_USAGE_SETTING)
                         .withConstValue(new C2StreamUsageTuning::input(
                             0u, (uint64_t)C2MemoryUsage::CPU_READ))
                         .build());

        addParameter(
            DefineParam(mSize, C2_NAME_STREAM_VIDEO_SIZE_SETTING)
                .withDefault(new C2VideoSizeStreamTuning::input(0u, 320, 240))
                .withFields({
                    C2F(mSize, width).inRange(2, 2048, 2),
                    C2F(mSize, height).inRange(2, 2048, 2),
                })
                .withSetter(SizeSetter)
                .build());

        addParameter(
            DefineParam(mFrameRate, C2_NAME_STREAM_FRAME_RATE_SETTING)
                .withDefault(new C2StreamFrameRateInfo::output(0u, 30.))
                // TODO: More restriction?
                .withFields({C2F(mFrameRate, value).greaterThan(0.)})
                .withSetter(
                    Setter<decltype(*mFrameRate)>::StrictValueWithNoDeps)
                .build());

        addParameter(
            DefineParam(mBitrate, C2_NAME_STREAM_BITRATE_SETTING)
                .withDefault(new C2BitrateTuning::output(0u, 64000))
                .withFields({C2F(mBitrate, value).inRange(1, 40000000)})
                .withSetter(
                    Setter<decltype(*mBitrate)>::NonStrictValueWithNoDeps)
                .build());
    }

    static C2R SizeSetter(bool mayBlock,
                          C2P<C2VideoSizeStreamTuning::input>& me) {
        (void)mayBlock;
        // TODO: maybe apply block limit?
        C2R res = C2R::Ok();
        if (!me.F(me.v.width).supportsAtAll(me.v.width)) {
            res = res.plus(C2SettingResultBuilder::BadValue(me.F(me.v.width)));
        }
        if (!me.F(me.v.height).supportsAtAll(me.v.height)) {
            res = res.plus(C2SettingResultBuilder::BadValue(me.F(me.v.height)));
        }
        return res;
    }

    uint32_t getWidth() const { return mSize->width; }
    uint32_t getHeight() const { return mSize->height; }
    float getFrameRate() const { return mFrameRate->value; }
    uint32_t getBitrate() const { return mBitrate->value; }

   private:
    std::shared_ptr<C2StreamFormatConfig::input> mInputFormat;
    std::shared_ptr<C2StreamFormatConfig::output> mOutputFormat;
    std::shared_ptr<C2PortMimeConfig::input> mInputMediaType;
    std::shared_ptr<C2PortMimeConfig::output> mOutputMediaType;
    std::shared_ptr<C2StreamUsageTuning::input> mUsage;
    std::shared_ptr<C2VideoSizeStreamTuning::input> mSize;
    std::shared_ptr<C2StreamFrameRateInfo::output> mFrameRate;
    std::shared_ptr<C2BitrateTuning::output> mBitrate;
};

}  // namespace android

#endif  // ANDROID_C2_SOFT_VPX_ENC_H__
