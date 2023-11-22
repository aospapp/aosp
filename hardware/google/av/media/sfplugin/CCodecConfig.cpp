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

//#define LOG_NDEBUG 0
#define LOG_TAG "CCodecConfig"
#include <cutils/properties.h>
#include <log/log.h>

#include <C2Component.h>
#include <C2Debug.h>
#include <C2Param.h>

#include <media/stagefright/MediaCodecConstants.h>

#include "CCodecConfig.h"
#include "Codec2Mapper.h"

namespace android {

// CCodecConfig

namespace {

/**
 * mapping between SDK and Codec 2.0 configurations.
 */
struct ConfigMapper {
    /**
     * Value mapper (C2Value => C2Value)
     */
    typedef std::function<C2Value(C2Value)> Mapper;

    /// shorthand
    typedef CCodecConfig::Domain Domain;

    ConfigMapper(std::string mediaKey, C2String c2struct, C2String c2field)
        : mDomain(Domain::ALL), mMediaKey(mediaKey), mStruct(c2struct), mField(c2field) { }

    /// Limits this parameter to the given domain
    ConfigMapper &limitTo(uint32_t domain) {
        C2_CHECK(domain & Domain::GUARD_BIT);
        mDomain = Domain(mDomain & domain);
        return *this;
    }

    /// Adds SDK => Codec 2.0 mapper (should not be in the SDK format)
    ConfigMapper &withMapper(Mapper mapper) {
        C2_CHECK(!mMapper);
        C2_CHECK(!mReverse);
        mMapper = mapper;
        return *this;
    }

    /// Adds SDK <=> Codec 2.0 value mappers
    ConfigMapper &withMappers(Mapper mapper, Mapper reverse) {
        C2_CHECK(!mMapper);
        C2_CHECK(!mReverse);
        mMapper = mapper;
        mReverse = reverse;
        return *this;
    }

    /// Maps from SDK values in an AMessage to a suitable C2Value.
    C2Value mapFromMessage(const AMessage::ItemData &item) const {
        C2Value value;
        int32_t int32Value;
        int64_t int64Value;
        float floatValue;
        double doubleValue;
        if (item.find(&int32Value)) {
            value = int32Value;
        } else if (item.find(&int64Value)) {
            value = int64Value;
        } else if (item.find(&floatValue)) {
            value = floatValue;
        } else if (item.find(&doubleValue)) {
            value = (float)doubleValue;
        }
        if (value.type() != C2Value::NO_INIT && mMapper) {
            value = mMapper(value);
        }
        return value;
    }

    /// Maps from a C2Value to an SDK value in an AMessage.
    AMessage::ItemData mapToMessage(C2Value value) const {
        AMessage::ItemData item;
        int32_t int32Value;
        uint32_t uint32Value;
        int64_t int64Value;
        uint64_t uint64Value;
        float floatValue;
        if (value.type() != C2Value::NO_INIT && mReverse) {
            value = mReverse(value);
        }
        if (value.get(&int32Value)) {
            item.set(int32Value);
        } else if (value.get(&uint32Value) && uint32Value <= uint32_t(INT32_MAX)) {
            // SDK does not support unsigned values
            item.set((int32_t)uint32Value);
        } else if (value.get(&int64Value)) {
            item.set(int64Value);
        } else if (value.get(&uint64Value) && uint64Value <= uint64_t(INT64_MAX)) {
            // SDK does not support unsigned values
            item.set((int64_t)uint64Value);
        } else if (value.get(&floatValue)) {
            item.set(floatValue);
        }
        return item;
    }

    Domain domain() const { return mDomain; }
    std::string mediaKey() const { return mMediaKey; }
    std::string path() const { return mStruct + '.' + mField; }
    Mapper mapper() const { return mMapper; }
    Mapper reverse() const { return mReverse; }

private:
    Domain mDomain;         ///< parameter domain
    std::string mMediaKey;  ///< SDK key
    C2String mStruct;       ///< Codec 2.0 struct name
    C2String mField;        ///< Codec 2.0 field name
    Mapper mMapper;         ///< optional SDK => Codec 2.0 value mapper
    Mapper mReverse;        ///< optional Codec 2.0 => SDK value mapper
};

}

/**
 * Set of standard parameters used by CCodec that are exposed to MediaCodec.
 */
struct StandardParams {
    typedef CCodecConfig::Domain Domain;

    // standard (MediaCodec) params are keyed by media format key
    typedef std::string SdkKey;

    /// used to return reference to no config mappers in getConfigMappersForSdkKey
    static const std::vector<ConfigMapper> NO_MAPPERS;

    /// Returns Codec 2.0 equivalent parameters for an SDK format key.
    const std::vector<ConfigMapper> &getConfigMappersForSdkKey(std::string key) const {
        auto it = mConfigMappers.find(key);
        if (it == mConfigMappers.end()) {
            ALOGD("no c2 equivalents for %s", key.c_str());
            return NO_MAPPERS;
        }
        ALOGV("found %zu eqs for %s", it->second.size(), key.c_str());
        return it->second;
    }

    /**
     * Adds a SDK <=> Codec 2.0 parameter mapping. Multiple Codec 2.0 parameters may map to a
     * single SDK key, in which case they shall be ordered from least authoritative to most
     * authoritative. When constructing SDK formats, the last mapped Codec 2.0 parameter that
     * is supported by the component will determine the exposed value. (TODO: perhaps restrict this
     * by domain.)
     */
    void add(const ConfigMapper &cm) {
        auto it = mConfigMappers.find(cm.mediaKey());
        ALOGV("%c%c%c%c %04x %9s %s => %s",
              ((cm.domain() & Domain::IS_INPUT) ? 'I' : ' '),
              ((cm.domain() & Domain::IS_OUTPUT) ? 'O' : ' '),
              ((cm.domain() & Domain::IS_CODED) ? 'C' : ' '),
              ((cm.domain() & Domain::IS_RAW) ? 'R' : ' '),
              cm.domain(),
              it == mConfigMappers.end() ? "adding" : "extending",
              cm.mediaKey().c_str(), cm.path().c_str());
        if (it == mConfigMappers.end()) {
            std::vector<ConfigMapper> eqs = { cm };
            mConfigMappers.emplace(cm.mediaKey(), eqs);
        } else {
            it->second.push_back(cm);
        }
    }

    /**
     * Returns all paths for a specific domain.
     *
     * \param any maximum domain mask. Returned parameters must match at least one of the domains
     *            in the mask.
     * \param all minimum domain mask. Returned parameters must match all of the domains in the
     *            mask. This is restricted to the bits of the maximum mask.
     */
    std::vector<std::string> getPathsForDomain(
            Domain any, Domain all = Domain::ALL) const {
        std::vector<std::string> res;
        for (const std::pair<std::string, std::vector<ConfigMapper>> &el : mConfigMappers) {
            for (const ConfigMapper &cm : el.second) {
                ALOGV("filtering %s %x %x %x %x", cm.path().c_str(), cm.domain(), any,
                        (cm.domain() & any), (cm.domain() & any & all));
                if ((cm.domain() & any) && ((cm.domain() & any & all) == (any & all))) {
                    res.push_back(cm.path());
                }
            }
        }
        return res;
    }

    /**
     * Returns SDK <=> Codec 2.0 mappings.
     *
     * TODO: replace these with better methods as this exposes the inner structure.
     */
    const std::map<SdkKey, std::vector<ConfigMapper>> getKeys() const {
        return mConfigMappers;
    }

private:
    std::map<SdkKey, std::vector<ConfigMapper>> mConfigMappers;
};

const std::vector<ConfigMapper> StandardParams::NO_MAPPERS;


CCodecConfig::CCodecConfig()
    : mInputFormat(new AMessage),
      mOutputFormat(new AMessage) { }

void CCodecConfig::initializeStandardParams() {
    typedef Domain D;
    mStandardParams = std::make_shared<StandardParams>();
    std::function<void(const ConfigMapper &)> add =
        [params = mStandardParams](const ConfigMapper &cm) {
            params->add(cm);
    };
    std::function<void(const ConfigMapper &)> deprecated = add;

    // allow int32 or float SDK values and represent them as float
    ConfigMapper::Mapper makeFloat = [](C2Value v) -> C2Value {
        // convert from i32 to float
        int32_t i32Value;
        float fpValue;
        if (v.get(&i32Value)) {
            return (float)i32Value;
        } else if (v.get(&fpValue)) {
            return fpValue;
        }
        return C2Value();
    };

    ConfigMapper::Mapper negate = [](C2Value v) -> C2Value {
        int32_t value;
        if (v.get(&value)) {
            return -value;
        }
        return C2Value();
    };

    add(ConfigMapper(KEY_MIME,     C2_PARAMKEY_INPUT_MEDIA_TYPE,    "value")
        .limitTo(D::INPUT & D::READ));
    add(ConfigMapper(KEY_MIME,     C2_PARAMKEY_OUTPUT_MEDIA_TYPE,   "value")
        .limitTo(D::OUTPUT & D::READ));

    add(ConfigMapper(KEY_BIT_RATE, C2_PARAMKEY_BITRATE, "value").limitTo(D::ENCODER));
    add(ConfigMapper(PARAMETER_KEY_VIDEO_BITRATE, C2_PARAMKEY_BITRATE, "value")
        .limitTo(D::ENCODER & D::VIDEO & D::PARAM));
    add(ConfigMapper(KEY_BITRATE_MODE, C2_PARAMKEY_BITRATE_MODE, "value")
        .limitTo(D::ENCODER & D::CODED)
        .withMapper([](C2Value v) -> C2Value {
            int32_t value;
            C2Config::bitrate_mode_t mode;
            if (v.get(&value) && C2Mapper::map(value, &mode)) {
                return mode;
            }
            return C2Value();
        }));
    // remove when codecs switch to PARAMKEY and new modes
    deprecated(ConfigMapper(KEY_BITRATE_MODE, "coded.bitrate-mode", "value")
               .limitTo(D::ENCODER));
    add(ConfigMapper(KEY_FRAME_RATE, C2_PARAMKEY_FRAME_RATE, "value")
        .limitTo(D::VIDEO)
        .withMappers(makeFloat, [](C2Value v) -> C2Value {
            // read back always as int
            float value;
            if (v.get(&value)) {
                return (int32_t)value;
            }
            return C2Value();
        }));

    add(ConfigMapper(KEY_MAX_INPUT_SIZE, C2_PARAMKEY_INPUT_MAX_BUFFER_SIZE, "value")
        .limitTo(D::INPUT));
    // remove when codecs switch to PARAMKEY
    deprecated(ConfigMapper(KEY_MAX_INPUT_SIZE, "coded.max-frame-size", "value")
               .limitTo(D::INPUT));
    // SDK rotation is clock-wise
    add(ConfigMapper(KEY_ROTATION, C2_PARAMKEY_VUI_ROTATION, "value")
        .limitTo(D::VIDEO & D::CODED)
        .withMappers(negate, negate));
    add(ConfigMapper(KEY_ROTATION, C2_PARAMKEY_ROTATION, "value")
        .limitTo(D::VIDEO & D::RAW)
        .withMappers(negate, negate));

    add(ConfigMapper(std::string(KEY_FEATURE_) + FEATURE_SecurePlayback,
                     C2_PARAMKEY_SECURE_MODE, "value"));

    add(ConfigMapper("prepend-sps-pps-to-idr-frames",
                     C2_PARAMKEY_PREPEND_HEADER_MODE, "value")
        .limitTo(D::ENCODER & D::VIDEO)
        .withMapper([](C2Value v) -> C2Value {
            int32_t value;
            if (v.get(&value) && value) {
                return C2Value(C2Config::PREPEND_HEADER_TO_ALL_SYNC);
            } else {
                return C2Value(C2Config::PREPEND_HEADER_TO_NONE);
            }
        }));
    // remove when codecs switch to PARAMKEY
    deprecated(ConfigMapper("prepend-sps-pps-to-idr-frames",
                            "coding.add-csd-to-sync-frames", "value")
               .limitTo(D::ENCODER & D::VIDEO));
    add(ConfigMapper(C2_PARAMKEY_SYNC_FRAME_PERIOD, C2_PARAMKEY_SYNC_FRAME_PERIOD, "value"));
    // remove when codecs switch to PARAMKEY
    deprecated(ConfigMapper(C2_PARAMKEY_SYNC_FRAME_PERIOD, "coding.gop", "intra-period")
               .limitTo(D::ENCODER & D::VIDEO));
    add(ConfigMapper(KEY_INTRA_REFRESH_PERIOD, C2_PARAMKEY_INTRA_REFRESH, "period")
        .limitTo(D::VIDEO & D::ENCODER));
    add(ConfigMapper(KEY_QUALITY, C2_PARAMKEY_QUALITY, "value"));
    add(ConfigMapper(PARAMETER_KEY_REQUEST_SYNC_FRAME,
                     "coding.request-sync", "value")
        .limitTo(D::PARAM & D::ENCODER));
    add(ConfigMapper(PARAMETER_KEY_REQUEST_SYNC_FRAME,
                     C2_PARAMKEY_REQUEST_SYNC_FRAME, "value")
        .limitTo(D::PARAM & D::ENCODER));

    add(ConfigMapper(KEY_OPERATING_RATE,   C2_PARAMKEY_OPERATING_RATE,     "value"));
    // C2 priorities are inverted
    add(ConfigMapper(KEY_PRIORITY,         C2_PARAMKEY_PRIORITY,           "value")
        .withMappers(negate, negate));
    // remove when codecs switch to PARAMKEY
    deprecated(ConfigMapper(KEY_OPERATING_RATE,   "ctrl.operating-rate",     "value")
               .withMapper(makeFloat));
    deprecated(ConfigMapper(KEY_PRIORITY,         "ctrl.priority",           "value"));

    add(ConfigMapper(KEY_WIDTH,         C2_PARAMKEY_PICTURE_SIZE,       "width")
        .limitTo(D::VIDEO | D::IMAGE));
    add(ConfigMapper(KEY_HEIGHT,        C2_PARAMKEY_PICTURE_SIZE,       "height")
        .limitTo(D::VIDEO | D::IMAGE));

    add(ConfigMapper("crop-left",       C2_PARAMKEY_CROP_RECT,       "left")
        .limitTo(D::VIDEO | D::IMAGE));
    add(ConfigMapper("crop-top",        C2_PARAMKEY_CROP_RECT,       "top")
        .limitTo(D::VIDEO | D::IMAGE));
    add(ConfigMapper("crop-width",      C2_PARAMKEY_CROP_RECT,       "width")
        .limitTo(D::VIDEO | D::IMAGE));
    add(ConfigMapper("crop-height",     C2_PARAMKEY_CROP_RECT,       "height")
        .limitTo(D::VIDEO | D::IMAGE));

    add(ConfigMapper(KEY_MAX_WIDTH,     C2_PARAMKEY_MAX_PICTURE_SIZE,    "width")
        .limitTo((D::VIDEO | D::IMAGE) & D::RAW));
    add(ConfigMapper(KEY_MAX_HEIGHT,    C2_PARAMKEY_MAX_PICTURE_SIZE,    "height")
        .limitTo((D::VIDEO | D::IMAGE) & D::RAW));

    add(ConfigMapper("csd-0",           C2_PARAMKEY_INIT_DATA,       "value")
        .limitTo(D::OUTPUT & D::READ));

    // Pixel Format (use local key for actual pixel format as we don't distinguish between
    // SDK layouts for flexible format and we need the actual SDK color format in the media format)
    add(ConfigMapper("android._color-format",  C2_PARAMKEY_PIXEL_FORMAT, "value")
        .limitTo((D::VIDEO | D::IMAGE) & D::RAW)
        .withMappers([](C2Value v) -> C2Value {
            int32_t value;
            if (v.get(&value)) {
                switch (value) {
                    case COLOR_FormatSurface:
                        return (uint32_t)HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED;
                    case COLOR_FormatYUV420Flexible:
                        return (uint32_t)HAL_PIXEL_FORMAT_YCBCR_420_888;
                    case COLOR_FormatYUV420Planar:
                    case COLOR_FormatYUV420SemiPlanar:
                    case COLOR_FormatYUV420PackedPlanar:
                    case COLOR_FormatYUV420PackedSemiPlanar:
                        return (uint32_t)HAL_PIXEL_FORMAT_YV12;
                    default:
                        // TODO: support some sort of passthrough
                        break;
                }
            }
            return C2Value();
        }, [](C2Value v) -> C2Value {
            uint32_t value;
            if (v.get(&value)) {
                switch (value) {
                    case HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED:
                        return COLOR_FormatSurface;
                    case HAL_PIXEL_FORMAT_YV12:
                    case HAL_PIXEL_FORMAT_YCBCR_420_888:
                        return COLOR_FormatYUV420Flexible;
                    default:
                        // TODO: support some sort of passthrough
                        break;
                }
            }
            return C2Value();
        }));

    add(ConfigMapper(KEY_CHANNEL_COUNT, C2_PARAMKEY_CHANNEL_COUNT,       "value")
        .limitTo(D::AUDIO)); // read back to both formats
    add(ConfigMapper(KEY_CHANNEL_COUNT, C2_PARAMKEY_CODED_CHANNEL_COUNT, "value")
        .limitTo(D::AUDIO & D::CODED));

    add(ConfigMapper(KEY_SAMPLE_RATE,   C2_PARAMKEY_SAMPLE_RATE,        "value")
        .limitTo(D::AUDIO)); // read back to both port formats
    add(ConfigMapper(KEY_SAMPLE_RATE,   C2_PARAMKEY_CODED_SAMPLE_RATE,  "value")
        .limitTo(D::AUDIO & D::CODED));

    add(ConfigMapper(KEY_PCM_ENCODING,  C2_PARAMKEY_PCM_ENCODING,       "value")
        .limitTo(D::AUDIO));

    add(ConfigMapper(KEY_IS_ADTS, C2_PARAMKEY_AAC_PACKAGING, "value")
        .limitTo(D::AUDIO & D::CODED)
        .withMappers([](C2Value v) -> C2Value {
            int32_t value;
            if (v.get(&value) && value) {
                return C2Config::AAC_PACKAGING_ADTS;
            }
            return C2Value();
        }, [](C2Value v) -> C2Value {
            uint32_t value;
            if (v.get(&value) && value == C2Config::AAC_PACKAGING_ADTS) {
                return (int32_t)1;
            }
            return C2Value();
        }));

    /* still to do
    constexpr char KEY_AAC_DRC_ATTENUATION_FACTOR[] = "aac-drc-cut-level";
    constexpr char KEY_AAC_DRC_BOOST_FACTOR[] = "aac-drc-boost-level";
    constexpr char KEY_AAC_DRC_EFFECT_TYPE[] = "aac-drc-effect-type";
    constexpr char KEY_AAC_DRC_HEAVY_COMPRESSION[] = "aac-drc-heavy-compression";
    constexpr char KEY_AAC_DRC_TARGET_REFERENCE_LEVEL[] = "aac-target-ref-level";
    constexpr char KEY_AAC_ENCODED_TARGET_LEVEL[] = "aac-encoded-target-level";
    constexpr char KEY_AAC_MAX_OUTPUT_CHANNEL_COUNT[] = "aac-max-output-channel_count";

    constexpr char KEY_AAC_SBR_MODE[] = "aac-sbr-mode";
    constexpr char KEY_AUDIO_SESSION_ID[] = "audio-session-id";

    constexpr char KEY_CAPTURE_RATE[] = "capture-rate";
    constexpr char KEY_CHANNEL_MASK[] = "channel-mask";
    constexpr char KEY_COLOR_RANGE[] = "color-range";
    constexpr char KEY_COLOR_STANDARD[] = "color-standard";
    constexpr char KEY_COLOR_TRANSFER[] = "color-transfer";
    constexpr char KEY_FLAC_COMPRESSION_LEVEL[] = "flac-compression-level";
    constexpr char KEY_GRID_COLUMNS[] = "grid-cols";
    constexpr char KEY_GRID_ROWS[] = "grid-rows";
    constexpr char KEY_HDR_STATIC_INFO[] = "hdr-static-info";
    constexpr char KEY_LATENCY[] = "latency";
    constexpr char KEY_LEVEL[] = "level";
    constexpr char KEY_MAX_BIT_RATE[] = "max-bitrate";
    constexpr char KEY_OUTPUT_REORDER_DEPTH[] = "output-reorder-depth";
    constexpr char KEY_PROFILE[] = "profile";
    constexpr char KEY_PUSH_BLANK_BUFFERS_ON_STOP[] = "push-blank-buffers-on-shutdown";
    constexpr char KEY_QUALITY[] = "quality";
    constexpr char KEY_REPEAT_PREVIOUS_FRAME_AFTER[] = "repeat-previous-frame-after";
    constexpr char KEY_SLICE_HEIGHT[] = "slice-height";
    constexpr char KEY_STRIDE[] = "stride";
    constexpr char KEY_TEMPORAL_LAYERING[] = "ts-schema";
    constexpr char KEY_TILE_HEIGHT[] = "tile-height";
    constexpr char KEY_TILE_WIDTH[] = "tile-width";
    constexpr char KEY_TRACK_ID[] = "track-id";

    */
}

status_t CCodecConfig::initialize(
        const std::shared_ptr<Codec2Client> &client,
        const std::shared_ptr<Codec2Client::Component> &component) {
    C2ComponentDomainSetting domain(C2Component::DOMAIN_OTHER);
    C2ComponentKindSetting kind(C2Component::KIND_OTHER);

    std::vector<std::unique_ptr<C2Param>> queried;
    c2_status_t c2err = component->query({ &domain, &kind }, {}, C2_DONT_BLOCK, &queried);
    if (c2err != C2_OK) {
        ALOGD("Query domain & kind failed => %s", asString(c2err));
        // TEMP: determine kind from component name
        if (kind.value == C2Component::KIND_OTHER) {
            if (component->getName().find("encoder") != std::string::npos) {
                kind.value = C2Component::KIND_ENCODER;
            } else if (component->getName().find("decoder") != std::string::npos) {
                kind.value = C2Component::KIND_DECODER;
            }
        }

        // TEMP: determine domain from media type (port (preferred) or stream #0)
        if (domain.value == C2Component::DOMAIN_OTHER) {
            c2err = component->query(
                    {}, { C2PortMediaTypeSetting::input::PARAM_TYPE,
                          C2StreamMediaTypeSetting::input::PARAM_TYPE }, C2_DONT_BLOCK, &queried);
            if (c2err != C2_OK && queried.size() == 0) {
                ALOGD("Query media type failed => %s", asString(c2err));
            } else {
                AString mediaType;
                C2PortMediaTypeSetting::input *portMediaType =
                    C2PortMediaTypeSetting::input::From(queried[0].get());
                if (portMediaType) {
                    mediaType = AString(portMediaType->m.value, portMediaType->flexCount());
                } else {
                    C2StreamMediaTypeSetting::input *streamMediaType =
                        C2StreamMediaTypeSetting::input::From(queried[0].get());
                    if (streamMediaType) {
                        mediaType = AString(streamMediaType->m.value, streamMediaType->flexCount());
                    }
                }
                ALOGD("read media type: %s", mediaType.c_str());
                if (mediaType.startsWith("audio/")) {
                    domain.value = C2Component::DOMAIN_AUDIO;
                } else if (mediaType.startsWith("video/")) {
                    domain.value = C2Component::DOMAIN_VIDEO;
                } else if (mediaType.startsWith("image/")) {
                    domain.value = C2Component::DOMAIN_IMAGE;
                }
            }
        }
    }

    mDomain = (domain.value == C2Component::DOMAIN_VIDEO ? Domain::IS_VIDEO :
               domain.value == C2Component::DOMAIN_IMAGE ? Domain::IS_IMAGE :
               domain.value == C2Component::DOMAIN_AUDIO ? Domain::IS_AUDIO : Domain::OTHER_DOMAIN)
            | (kind.value == C2Component::KIND_DECODER ? Domain::IS_DECODER :
               kind.value == C2Component::KIND_ENCODER ? Domain::IS_ENCODER : Domain::OTHER_KIND);

    mInputDomain = Domain(((mDomain & IS_DECODER) ? IS_CODED : IS_RAW) | IS_INPUT);
    mOutputDomain = Domain(((mDomain & IS_ENCODER) ? IS_CODED : IS_RAW) | IS_OUTPUT);

    ALOGV("domain is %#x (%u %u)", mDomain, domain.value, kind.value);

    c2err = component->querySupportedParams(&mParamDescs);
    if (c2err != C2_OK) {
        ALOGD("Query supported params failed after returning %zu values => %s",
                mParamDescs.size(), asString(c2err));
        // TODO: return error once we complete implementation.
        return UNKNOWN_ERROR;
    }
    for (const std::shared_ptr<C2ParamDescriptor> &desc : mParamDescs) {
        mSupportedIndices.emplace(desc->index());
    }

    mReflector = client->getParamReflector();
    if (mReflector == nullptr) {
        ALOGE("Failed to get param reflector");
        // TODO: report error once we complete implementation.
        return UNKNOWN_ERROR;
    }

    // enumerate all fields
    mParamUpdater = std::make_shared<ReflectedParamUpdater>();
    mParamUpdater->clear();
    mParamUpdater->addParamDesc(mReflector, mParamDescs);

    // TEMP: add some standard fields even if not reflected
    if (kind.value == C2Component::KIND_ENCODER) {
        mParamUpdater->addStandardParam<C2StreamInitDataInfo::output>(C2_PARAMKEY_INIT_DATA);
    }
    if (kind.value != C2Component::KIND_ENCODER
            && (domain.value == C2Component::DOMAIN_IMAGE
                    || domain.value == C2Component::DOMAIN_VIDEO)) {
        addLocalParam<C2StreamPictureSizeInfo::output>(C2_PARAMKEY_PICTURE_SIZE);
        addLocalParam<C2StreamCropRectInfo::output>(C2_PARAMKEY_CROP_RECT);
        addLocalParam(
                new C2StreamPixelAspectRatioInfo::output(0u, 1u, 1u),
                C2_PARAMKEY_PIXEL_ASPECT_RATIO);
        addLocalParam(new C2StreamRotationInfo::output(0u, 0), C2_PARAMKEY_ROTATION);
        addLocalParam(new C2StreamColorAspectsInfo::output(0u), C2_PARAMKEY_COLOR_ASPECTS);
        addLocalParam<C2StreamHdrStaticInfo::output>(C2_PARAMKEY_HDR_STATIC_INFO);
        addLocalParam<C2StreamDataSpaceInfo::output>(C2_PARAMKEY_DATA_SPACE);
        addLocalParam<C2StreamSurfaceScalingInfo::output>(C2_PARAMKEY_SURFACE_SCALING_MODE);
    }

    initializeStandardParams();

    // subscribe to all supported standard (exposed) params
    // TODO: limit this to params that are actually in the domain
    std::vector<std::string> formatKeys = mStandardParams->getPathsForDomain(Domain(1 << 30));
    std::vector<C2Param::Index> indices;
    mParamUpdater->getParamIndicesForKeys(formatKeys, &indices);
    mSubscribedIndices.insert(indices.begin(), indices.end());

    // also subscribe to some non-SDK standard parameters
    // for number of input/output buffers
    mSubscribedIndices.emplace(C2PortSuggestedBufferCountTuning::input::PARAM_TYPE);
    mSubscribedIndices.emplace(C2PortSuggestedBufferCountTuning::output::PARAM_TYPE);
    mSubscribedIndices.emplace(C2ActualPipelineDelayTuning::PARAM_TYPE);
    mSubscribedIndices.emplace(C2PortActualDelayTuning::input::PARAM_TYPE);
    mSubscribedIndices.emplace(C2PortActualDelayTuning::output::PARAM_TYPE);
    // for output buffer array allocation
    mSubscribedIndices.emplace(C2StreamMaxBufferSizeInfo::output::PARAM_TYPE);
    // init data (CSD)
    mSubscribedIndices.emplace(C2StreamInitDataInfo::output::PARAM_TYPE);

    return OK;
}

status_t CCodecConfig::subscribeToConfigUpdate(
        const std::shared_ptr<Codec2Client::Component> &component,
        const std::vector<C2Param::Index> &indices,
        c2_blocking_t blocking) {
    mSubscribedIndices.insert(indices.begin(), indices.end());
    // TODO: enable this when components no longer crash on this config
    if (mSubscribedIndices.size() != mSubscribedIndicesSize && false) {
        std::vector<uint32_t> indices;
        for (C2Param::Index ix : mSubscribedIndices) {
            indices.push_back(ix);
        }
        std::unique_ptr<C2SubscribedParamIndicesTuning> subscribeTuning =
            C2SubscribedParamIndicesTuning::AllocUnique(indices);
        std::vector<std::unique_ptr<C2SettingResult>> results;
        c2_status_t c2Err = component->config({ subscribeTuning.get() }, blocking, &results);
        if (c2Err != C2_OK && c2Err != C2_BAD_INDEX) {
            ALOGD("Failed to subscribe to parameters => %s", asString(c2Err));
            // TODO: error
        }
        ALOGV("Subscribed to %zu params", mSubscribedIndices.size());
        mSubscribedIndicesSize = mSubscribedIndices.size();
    }
    return OK;
}

status_t CCodecConfig::queryConfiguration(
        const std::shared_ptr<Codec2Client::Component> &component) {
    // query all subscribed parameters
    std::vector<C2Param::Index> indices(mSubscribedIndices.begin(), mSubscribedIndices.end());
    std::vector<std::unique_ptr<C2Param>> queried;
    c2_status_t c2Err = component->query({}, indices, C2_MAY_BLOCK, &queried);
    if (c2Err != OK) {
        ALOGI("query failed after returning %zu values (%s)", queried.size(), asString(c2Err));
        // TODO: error
    }

    updateConfiguration(queried, ALL);
    return OK;
}

bool CCodecConfig::updateConfiguration(
        std::vector<std::unique_ptr<C2Param>> &configUpdate, Domain domain) {
    ALOGV("updating configuration with %zu params", configUpdate.size());
    bool changed = false;
    for (std::unique_ptr<C2Param> &p : configUpdate) {
        if (p && *p) {
            auto insertion = mCurrentConfig.emplace(p->index(), nullptr);
            if (insertion.second || *insertion.first->second != *p) {
                changed = true;
            }
            insertion.first->second = std::move(p);
        }
    }

    ALOGV("updated configuration has %zu params (%s)", mCurrentConfig.size(),
            changed ? "CHANGED" : "no change");
    if (changed) {
        return updateFormats(domain);
    }
    return false;
}

bool CCodecConfig::updateFormats(Domain domain) {
    // get addresses of params in the current config
    std::vector<C2Param*> paramPointers;
    for (const auto &it : mCurrentConfig) {
        paramPointers.push_back(it.second.get());
    }

    ReflectedParamUpdater::Dict reflected = mParamUpdater->getParams(paramPointers);
    ALOGD("c2 config is %s", reflected.debugString().c_str());

    bool changed = false;
    if (domain & mInputDomain) {
        sp<AMessage> oldFormat = mInputFormat;
        mInputFormat->extend(getSdkFormatForDomain(reflected, mInputDomain));
        if (mInputFormat->countEntries() != oldFormat->countEntries()
                || mInputFormat->changesFrom(oldFormat)->countEntries() > 0) {
            changed = true;
        }
    }
    if (domain & mOutputDomain) {
        sp<AMessage> oldFormat = mOutputFormat;
        mOutputFormat->extend(getSdkFormatForDomain(reflected, mOutputDomain));
        if (!changed &&
                (mOutputFormat->countEntries() != oldFormat->countEntries()
                        || mOutputFormat->changesFrom(oldFormat)->countEntries() > 0)) {
            changed = true;
        }
    }
    ALOGV_IF(changed, "format(s) changed");
    return changed;
}

sp<AMessage> CCodecConfig::getSdkFormatForDomain(
        const ReflectedParamUpdater::Dict &reflected, Domain domain) const {
    sp<AMessage> msg = new AMessage;
    for (const std::pair<std::string, std::vector<ConfigMapper>> &el : mStandardParams->getKeys()) {
        for (const ConfigMapper &cm : el.second) {
            if ((cm.domain() & domain) == 0 || (cm.domain() & mDomain) == 0) {
                continue;
            }
            auto it = reflected.find(cm.path());
            if (it == reflected.end()) {
                continue;
            }
            C2Value c2Value;
            sp<ABuffer> bufValue;
            AString strValue;
            AMessage::ItemData item;
            if (it->second.find(&c2Value)) {
                item = cm.mapToMessage(c2Value);
            } else if (it->second.find(&bufValue)) {
                item.set(bufValue);
            } else if (it->second.find(&strValue)) {
                item.set(strValue);
            } else {
                ALOGD("unexpected untyped query value for key: %s", cm.path().c_str());
                continue;
            }
            msg->setItem(el.first.c_str(), item);
        }
    }

    { // convert from Codec 2.0 rect to MediaFormat rect and add crop rect if not present
        int32_t left, top, width, height;
        if (msg->findInt32("crop-left", &left) && msg->findInt32("crop-width", &width)
                && msg->findInt32("crop-top", &top) && msg->findInt32("crop-height", &height)
                && left >= 0 && width >=0 && width <= INT32_MAX - left
                && top >= 0 && height >=0 && height <= INT32_MAX - top) {
            msg->removeEntryAt(msg->findEntryByName("crop-left"));
            msg->removeEntryAt(msg->findEntryByName("crop-top"));
            msg->removeEntryAt(msg->findEntryByName("crop-width"));
            msg->removeEntryAt(msg->findEntryByName("crop-height"));
            msg->setRect("crop", left, top, left + width - 1, top + height - 1);
        } else if (msg->findInt32("width", &width) && msg->findInt32("height", &height)) {
            msg->setRect("crop", 0, 0, width - 1, height - 1);
        }
    }

    ALOGV("converted to SDK values as %s", msg->debugString().c_str());
    return msg;
}

/// converts an AMessage value to a ParamUpdater value
static void convert(const AMessage::ItemData &from, ReflectedParamUpdater::Value *to) {
    int32_t int32Value;
    int64_t int64Value;
    sp<ABuffer> bufValue;
    AString strValue;
    float floatValue;
    double doubleValue;

    if (from.find(&int32Value)) {
        to->set(int32Value);
    } else if (from.find(&int64Value)) {
        to->set(int64Value);
    } else if (from.find(&bufValue)) {
        to->set(bufValue);
    } else if (from.find(&strValue)) {
        to->set(strValue);
    } else if (from.find(&floatValue)) {
        to->set(C2Value(floatValue));
    } else if (from.find(&doubleValue)) {
        // convert double to float
        to->set(C2Value((float)doubleValue));
    }
    // ignore all other AMessage types
}

/// relaxes Codec 2.0 specific value types to SDK types (mainly removes signedness and counterness
/// from 32/64-bit values.)
static void relaxValues(ReflectedParamUpdater::Value &item) {
    C2Value c2Value;
    int32_t int32Value;
    int64_t int64Value;
    (void)item.find(&c2Value);
    if (c2Value.get(&int32Value) || c2Value.get((uint32_t*)&int32Value)
            || c2Value.get((c2_cntr32_t*)&int32Value)) {
        item.set(int32Value);
    } else if (c2Value.get(&int64Value)
            || c2Value.get((uint64_t*)&int64Value)
            || c2Value.get((c2_cntr64_t*)&int64Value)) {
        item.set(int64Value);
    }
}

ReflectedParamUpdater::Dict CCodecConfig::getReflectedFormat(
        const sp<AMessage> &params_, Domain domain) const {
    // create a modifiable copy of params
    sp<AMessage> params = params_->dup();
    ALOGV("filtering with domain %x", domain);

    // convert some macro parameters to Codec 2.0 specific expressions

    { // make i-frame-interval time based
        int32_t iFrameInterval;
        if (params->findInt32("i-frame-interval", &iFrameInterval)) {
            int32_t intFrameRate;
            float frameRate;
            if (params->findInt32("frame-rate", &intFrameRate)) {
                params->setInt32(C2_PARAMKEY_SYNC_FRAME_PERIOD, iFrameInterval * intFrameRate + 0.5);
            } else if (params->findFloat("frame-rate", &frameRate)) {
                params->setInt32(C2_PARAMKEY_SYNC_FRAME_PERIOD, iFrameInterval * frameRate + 0.5);
            }
        }
    }

    { // convert from MediaFormat rect to Codec 2.0 rect
        int32_t offset;
        int32_t end;
        AMessage::ItemData item;
        if (params->findInt32("crop-left", &offset) && params->findInt32("crop-right", &end)
                && offset >= 0 && end >= offset - 1) {
            size_t ix = params->findEntryByName("crop-right");
            params->setEntryNameAt(ix, "crop-width");
            item.set(end - offset + 1);
            params->setEntryAt(ix, item);
        }
        if (params->findInt32("crop-top", &offset) && params->findInt32("crop-bottom", &end)
                && offset >= 0 && end >= offset - 1) {
            size_t ix = params->findEntryByName("crop-bottom");
            params->setEntryNameAt(ix, "crop-height");
            item.set(end - offset + 1);
            params->setEntryAt(ix, item);
        }
    }

    // this is to verify that we set proper signedness for standard parameters
    bool beVeryStrict = property_get_bool("debug.stagefright.ccodec_strict_type", false);
    // this is to allow vendors to use the wrong signedness for standard parameters
    bool beVeryLax = property_get_bool("debug.stagefright.ccodec_lax_type", false);

    ReflectedParamUpdater::Dict filtered;
    for (size_t ix = 0; ix < params->countEntries(); ++ix) {
        AMessage::Type type;
        AString name = params->getEntryNameAt(ix, &type);
        AMessage::ItemData msgItem = params->getEntryAt(ix);
        ReflectedParamUpdater::Value item;
        convert(msgItem, &item); // convert item to param updater item

        if (name.startsWith("vendor.")) {
            // vendor params pass through as is
            filtered.emplace(name.c_str(), item);
            continue;
        }
        // standard parameters may get modified, filtered or duplicated
        for (const ConfigMapper &cm : mStandardParams->getConfigMappersForSdkKey(name.c_str())) {
            if (cm.domain() & domain & mDomain) {
                // map arithmetic values, pass through string or buffer
                switch (type) {
                    case AMessage::kTypeBuffer:
                    case AMessage::kTypeString:
                        break;
                    case AMessage::kTypeInt32:
                    case AMessage::kTypeInt64:
                    case AMessage::kTypeFloat:
                    case AMessage::kTypeDouble:
                        // for now only map settings with mappers as we are not creating
                        // signed <=> unsigned mappers
                        // TODO: be precise about signed unsigned
                        if (beVeryStrict || cm.mapper()) {
                            item.set(cm.mapFromMessage(params->getEntryAt(ix)));
                            // also allow to relax type strictness
                            if (beVeryLax) {
                                relaxValues(item);
                            }
                        }
                        break;
                    default:
                        continue;
                }
                filtered.emplace(cm.path(), item);
            }
        }
    }
    ALOGV("filtered %s to %s", params->debugString(4).c_str(),
            filtered.debugString(4).c_str());
    return filtered;
}

status_t CCodecConfig::getConfigUpdateFromSdkParams(
        std::shared_ptr<Codec2Client::Component> component,
        const sp<AMessage> &sdkParams, Domain domain,
        c2_blocking_t blocking,
        std::vector<std::unique_ptr<C2Param>> *configUpdate) const {
    ReflectedParamUpdater::Dict params = getReflectedFormat(sdkParams, domain);

    std::vector<C2Param::Index> indices;
    mParamUpdater->getParamIndicesFromMessage(params, &indices);
    if (indices.empty()) {
        ALOGD("no recognized params in: %s", params.debugString().c_str());
        return OK;
    }

    configUpdate->clear();
    std::vector<C2Param::Index> supportedIndices;
    for (C2Param::Index ix : indices) {
        if (mSupportedIndices.count(ix)) {
            supportedIndices.push_back(ix);
        } else if (mLocalParams.count(ix)) {
            // query local parameter here
            auto it = mCurrentConfig.find(ix);
            if (it != mCurrentConfig.end()) {
                configUpdate->emplace_back(C2Param::Copy(*it->second));
            }
        }
    }

    c2_status_t err = component->query({ }, supportedIndices, blocking, configUpdate);
    if (err != C2_OK) {
        ALOGD("query failed after returning %zu params => %s", configUpdate->size(), asString(err));
    }

    if (configUpdate->size()) {
        mParamUpdater->updateParamsFromMessage(params, configUpdate);
    }
    return OK;
}

status_t CCodecConfig::setParameters(
        std::shared_ptr<Codec2Client::Component> component,
        std::vector<std::unique_ptr<C2Param>> &configUpdate,
        c2_blocking_t blocking) {
    status_t result = OK;
    if (configUpdate.empty()) {
        return OK;
    }

    std::vector<C2Param::Index> indices;
    std::vector<C2Param *> paramVector;
    for (const std::unique_ptr<C2Param> &param : configUpdate) {
        if (mSupportedIndices.count(param->index())) {
            // component parameter
            paramVector.push_back(param.get());
            indices.push_back(param->index());
        } else if (mLocalParams.count(param->index())) {
            // handle local parameter here
            LocalParamValidator validator = mLocalParams.find(param->index())->second;
            c2_status_t err = C2_OK;
            std::unique_ptr<C2Param> copy = C2Param::Copy(*param);
            if (validator) {
                err = validator(copy);
            }
            if (err == C2_OK) {
                ALOGV("updated local parameter value for %s",
                        mParamUpdater->getParamName(param->index()).c_str());

                mCurrentConfig[param->index()] = std::move(copy);
            } else {
                ALOGD("failed to set parameter value for %s => %s",
                        mParamUpdater->getParamName(param->index()).c_str(), asString(err));
                result = BAD_VALUE;
            }
        }
    }
    // update subscribed param indices
    subscribeToConfigUpdate(component, indices, blocking);

    std::vector<std::unique_ptr<C2SettingResult>> failures;
    c2_status_t err = component->config(paramVector, blocking, &failures);
    if (err != C2_OK) {
        ALOGD("config failed => %s", asString(err));
        // This is non-fatal.
    }
    for (const std::unique_ptr<C2SettingResult> &failure : failures) {
        switch (failure->failure) {
            case C2SettingResult::BAD_VALUE:
                ALOGD("Bad parameter value");
                result = BAD_VALUE;
                break;
            default:
                ALOGV("failure = %d", int(failure->failure));
                break;
        }
    }

    // Re-query parameter values in case config could not update them and update the current
    // configuration.
    configUpdate.clear();
    err = component->query({}, indices, blocking, &configUpdate);
    if (err != C2_OK) {
        ALOGD("query failed after returning %zu params => %s", configUpdate.size(), asString(err));
    }
    (void)updateConfiguration(configUpdate, ALL);

    // TODO: error value
    return result;
}

const C2Param *CCodecConfig::getConfigParameterValue(C2Param::Index index) const {
    auto it = mCurrentConfig.find(index);
    if (it == mCurrentConfig.end()) {
        return nullptr;
    } else {
        return it->second.get();
    }
}

}  // namespace android

