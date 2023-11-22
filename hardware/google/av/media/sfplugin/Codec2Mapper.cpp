/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "Codec2Mapper"
#include <utils/Log.h>

#include <media/stagefright/MediaCodecConstants.h>
#include <media/stagefright/SurfaceUtils.h>
#include <media/stagefright/foundation/ALookup.h>
#include <media/stagefright/foundation/MediaDefs.h>

#include <stdint.h>  // for INT32_MAX

#include "Codec2Mapper.h"

using namespace android;

namespace {

ALookup<C2Config::profile_t, int32_t> sAacProfiles = {
    { C2Config::PROFILE_AAC_LC,         AACObjectLC },
    { C2Config::PROFILE_AAC_MAIN,       AACObjectMain },
    { C2Config::PROFILE_AAC_SSR,        AACObjectSSR },
    { C2Config::PROFILE_AAC_LTP,        AACObjectLTP },
    { C2Config::PROFILE_AAC_HE,         AACObjectHE },
    { C2Config::PROFILE_AAC_SCALABLE,   AACObjectScalable },
    { C2Config::PROFILE_AAC_ER_LC,      AACObjectERLC },
    { C2Config::PROFILE_AAC_ER_SCALABLE, AACObjectERScalable },
    { C2Config::PROFILE_AAC_LD,         AACObjectLD },
    { C2Config::PROFILE_AAC_HE_PS,      AACObjectHE_PS },
    { C2Config::PROFILE_AAC_ELD,        AACObjectELD },
    { C2Config::PROFILE_AAC_XHE,        AACObjectXHE },
};

ALookup<C2Config::level_t, int32_t> sAvcLevels = {
    { C2Config::LEVEL_AVC_1,    AVCLevel1 },
    { C2Config::LEVEL_AVC_1B,   AVCLevel1b },
    { C2Config::LEVEL_AVC_1_1,  AVCLevel11 },
    { C2Config::LEVEL_AVC_1_2,  AVCLevel12 },
    { C2Config::LEVEL_AVC_1_3,  AVCLevel13 },
    { C2Config::LEVEL_AVC_2,    AVCLevel2 },
    { C2Config::LEVEL_AVC_2_1,  AVCLevel21 },
    { C2Config::LEVEL_AVC_2_2,  AVCLevel22 },
    { C2Config::LEVEL_AVC_3,    AVCLevel3 },
    { C2Config::LEVEL_AVC_3_1,  AVCLevel31 },
    { C2Config::LEVEL_AVC_3_2,  AVCLevel32 },
    { C2Config::LEVEL_AVC_4,    AVCLevel4 },
    { C2Config::LEVEL_AVC_4_1,  AVCLevel41 },
    { C2Config::LEVEL_AVC_4_2,  AVCLevel42 },
    { C2Config::LEVEL_AVC_5,    AVCLevel5 },
    { C2Config::LEVEL_AVC_5_1,  AVCLevel51 },
    { C2Config::LEVEL_AVC_5_2,  AVCLevel52 },

};

ALookup<C2Config::profile_t, int32_t> sAvcProfiles = {
    // treat restricted profiles as full profile if there is no equivalent - which works for
    // decoders, but not for encoders
    { C2Config::PROFILE_AVC_BASELINE,               AVCProfileBaseline },
    { C2Config::PROFILE_AVC_CONSTRAINED_BASELINE,   AVCProfileConstrainedBaseline },
    { C2Config::PROFILE_AVC_MAIN,                   AVCProfileMain },
    { C2Config::PROFILE_AVC_EXTENDED,               AVCProfileExtended },
    { C2Config::PROFILE_AVC_HIGH,                   AVCProfileHigh },
    { C2Config::PROFILE_AVC_PROGRESSIVE_HIGH,       AVCProfileHigh },
    { C2Config::PROFILE_AVC_CONSTRAINED_HIGH,       AVCProfileConstrainedHigh },
    { C2Config::PROFILE_AVC_HIGH_10,                AVCProfileHigh10 },
    { C2Config::PROFILE_AVC_PROGRESSIVE_HIGH_10,    AVCProfileHigh10 },
    { C2Config::PROFILE_AVC_HIGH_422,               AVCProfileHigh422 },
    { C2Config::PROFILE_AVC_HIGH_444_PREDICTIVE,    AVCProfileHigh444 },
    { C2Config::PROFILE_AVC_HIGH_10_INTRA,          AVCProfileHigh10 },
    { C2Config::PROFILE_AVC_HIGH_422_INTRA,         AVCProfileHigh422 },
    { C2Config::PROFILE_AVC_HIGH_444_INTRA,         AVCProfileHigh444 },
    { C2Config::PROFILE_AVC_CAVLC_444_INTRA,        AVCProfileHigh444 },
};

ALookup<C2Config::bitrate_mode_t, int32_t> sBitrateModes = {
    { C2Config::BITRATE_CONST,      BITRATE_MODE_CBR },
    { C2Config::BITRATE_VARIABLE,   BITRATE_MODE_VBR },
    { C2Config::BITRATE_IGNORE,     BITRATE_MODE_CQ },
};

ALookup<C2Config::level_t, int32_t> sDolbyVisionLevels = {
    { C2Config::LEVEL_DV_MAIN_HD_24,  DolbyVisionLevelHd24 },
    { C2Config::LEVEL_DV_MAIN_HD_30,  DolbyVisionLevelHd30 },
    { C2Config::LEVEL_DV_MAIN_FHD_24, DolbyVisionLevelFhd24 },
    { C2Config::LEVEL_DV_MAIN_FHD_30, DolbyVisionLevelFhd30 },
    { C2Config::LEVEL_DV_MAIN_FHD_60, DolbyVisionLevelFhd60 },
    { C2Config::LEVEL_DV_MAIN_UHD_24, DolbyVisionLevelUhd24 },
    { C2Config::LEVEL_DV_MAIN_UHD_30, DolbyVisionLevelUhd30 },
    { C2Config::LEVEL_DV_MAIN_UHD_48, DolbyVisionLevelUhd48 },
    { C2Config::LEVEL_DV_MAIN_UHD_60, DolbyVisionLevelUhd60 },

    // high tiers are not yet supported on android, for now map them to main tier
    { C2Config::LEVEL_DV_HIGH_HD_24,  DolbyVisionLevelHd24 },
    { C2Config::LEVEL_DV_HIGH_HD_30,  DolbyVisionLevelHd30 },
    { C2Config::LEVEL_DV_HIGH_FHD_24, DolbyVisionLevelFhd24 },
    { C2Config::LEVEL_DV_HIGH_FHD_30, DolbyVisionLevelFhd30 },
    { C2Config::LEVEL_DV_HIGH_FHD_60, DolbyVisionLevelFhd60 },
    { C2Config::LEVEL_DV_HIGH_UHD_24, DolbyVisionLevelUhd24 },
    { C2Config::LEVEL_DV_HIGH_UHD_30, DolbyVisionLevelUhd30 },
    { C2Config::LEVEL_DV_HIGH_UHD_48, DolbyVisionLevelUhd48 },
    { C2Config::LEVEL_DV_HIGH_UHD_60, DolbyVisionLevelUhd60 },
};

ALookup<C2Config::profile_t, int32_t> sDolbyVisionProfiles = {
    { C2Config::PROFILE_DV_AV_PER, DolbyVisionProfileDvavPer },
    { C2Config::PROFILE_DV_AV_PEN, DolbyVisionProfileDvavPen },
    { C2Config::PROFILE_DV_HE_DER, DolbyVisionProfileDvheDer },
    { C2Config::PROFILE_DV_HE_DEN, DolbyVisionProfileDvheDen },
    { C2Config::PROFILE_DV_HE_04, DolbyVisionProfileDvheDtr },
    { C2Config::PROFILE_DV_HE_05, DolbyVisionProfileDvheStn },
    { C2Config::PROFILE_DV_HE_DTH, DolbyVisionProfileDvheDth },
    { C2Config::PROFILE_DV_HE_07, DolbyVisionProfileDvheDtb },
    { C2Config::PROFILE_DV_HE_08, DolbyVisionProfileDvheSt },
    { C2Config::PROFILE_DV_AV_09, DolbyVisionProfileDvavSe },
};

ALookup<C2Config::level_t, int32_t> sH263Levels = {
    { C2Config::LEVEL_H263_10, H263Level10 },
    { C2Config::LEVEL_H263_20, H263Level20 },
    { C2Config::LEVEL_H263_30, H263Level30 },
    { C2Config::LEVEL_H263_40, H263Level40 },
    { C2Config::LEVEL_H263_45, H263Level45 },
    { C2Config::LEVEL_H263_50, H263Level50 },
    { C2Config::LEVEL_H263_60, H263Level60 },
    { C2Config::LEVEL_H263_70, H263Level70 },
};

ALookup<C2Config::profile_t, int32_t> sH263Profiles = {
    { C2Config::PROFILE_H263_BASELINE,          H263ProfileBaseline },
    { C2Config::PROFILE_H263_H320,              H263ProfileH320Coding },
    { C2Config::PROFILE_H263_V1BC,              H263ProfileBackwardCompatible },
    { C2Config::PROFILE_H263_ISWV2,             H263ProfileISWV2 },
    { C2Config::PROFILE_H263_ISWV3,             H263ProfileISWV3 },
    { C2Config::PROFILE_H263_HIGH_COMPRESSION,  H263ProfileHighCompression },
    { C2Config::PROFILE_H263_INTERNET,          H263ProfileInternet },
    { C2Config::PROFILE_H263_INTERLACE,         H263ProfileInterlace },
    { C2Config::PROFILE_H263_HIGH_LATENCY,      H263ProfileHighLatency },
};

ALookup<C2Config::level_t, int32_t> sHevcLevels = {
    { C2Config::LEVEL_HEVC_MAIN_1,      HEVCMainTierLevel1 },
    { C2Config::LEVEL_HEVC_MAIN_2,      HEVCMainTierLevel2 },
    { C2Config::LEVEL_HEVC_MAIN_2_1,    HEVCMainTierLevel21 },
    { C2Config::LEVEL_HEVC_MAIN_3,      HEVCMainTierLevel3 },
    { C2Config::LEVEL_HEVC_MAIN_3_1,    HEVCMainTierLevel31 },
    { C2Config::LEVEL_HEVC_MAIN_4,      HEVCMainTierLevel4 },
    { C2Config::LEVEL_HEVC_MAIN_4_1,    HEVCMainTierLevel41 },
    { C2Config::LEVEL_HEVC_MAIN_5,      HEVCMainTierLevel5 },
    { C2Config::LEVEL_HEVC_MAIN_5_1,    HEVCMainTierLevel51 },
    { C2Config::LEVEL_HEVC_MAIN_5_2,    HEVCMainTierLevel52 },
    { C2Config::LEVEL_HEVC_MAIN_6,      HEVCMainTierLevel6 },
    { C2Config::LEVEL_HEVC_MAIN_6_1,    HEVCMainTierLevel61 },
    { C2Config::LEVEL_HEVC_MAIN_6_2,    HEVCMainTierLevel62 },

    { C2Config::LEVEL_HEVC_HIGH_4,      HEVCHighTierLevel4 },
    { C2Config::LEVEL_HEVC_HIGH_4_1,    HEVCHighTierLevel41 },
    { C2Config::LEVEL_HEVC_HIGH_5,      HEVCHighTierLevel5 },
    { C2Config::LEVEL_HEVC_HIGH_5_1,    HEVCHighTierLevel51 },
    { C2Config::LEVEL_HEVC_HIGH_5_2,    HEVCHighTierLevel52 },
    { C2Config::LEVEL_HEVC_HIGH_6,      HEVCHighTierLevel6 },
    { C2Config::LEVEL_HEVC_HIGH_6_1,    HEVCHighTierLevel61 },
    { C2Config::LEVEL_HEVC_HIGH_6_2,    HEVCHighTierLevel62 },

    // map high tier levels below 4 to main tier
    { C2Config::LEVEL_HEVC_MAIN_1,      HEVCHighTierLevel1 },
    { C2Config::LEVEL_HEVC_MAIN_2,      HEVCHighTierLevel2 },
    { C2Config::LEVEL_HEVC_MAIN_2_1,    HEVCHighTierLevel21 },
    { C2Config::LEVEL_HEVC_MAIN_3,      HEVCHighTierLevel3 },
    { C2Config::LEVEL_HEVC_MAIN_3_1,    HEVCHighTierLevel31 },
};

ALookup<C2Config::profile_t, int32_t> sHevcProfiles = {
    { C2Config::PROFILE_HEVC_MAIN, HEVCProfileMain },
    { C2Config::PROFILE_HEVC_MAIN_10, HEVCProfileMain10 },
    { C2Config::PROFILE_HEVC_MAIN_STILL, HEVCProfileMainStill },
    { C2Config::PROFILE_HEVC_MAIN_INTRA, HEVCProfileMain },
    { C2Config::PROFILE_HEVC_MAIN_10_INTRA, HEVCProfileMain10 },
};

ALookup<C2Config::level_t, int32_t> sMpeg2Levels = {
    { C2Config::LEVEL_MP2V_LOW,         MPEG2LevelLL },
    { C2Config::LEVEL_MP2V_MAIN,        MPEG2LevelML },
    { C2Config::LEVEL_MP2V_HIGH_1440,   MPEG2LevelH14 },
    { C2Config::LEVEL_MP2V_HIGH,        MPEG2LevelHL },
    { C2Config::LEVEL_MP2V_HIGHP,       MPEG2LevelHP },
};

ALookup<C2Config::profile_t, int32_t> sMpeg2Profiles = {
    { C2Config::PROFILE_MP2V_SIMPLE,                MPEG2ProfileSimple },
    { C2Config::PROFILE_MP2V_MAIN,                  MPEG2ProfileMain },
    { C2Config::PROFILE_MP2V_SNR_SCALABLE,          MPEG2ProfileSNR },
    { C2Config::PROFILE_MP2V_SPATIALLY_SCALABLE,    MPEG2ProfileSpatial },
    { C2Config::PROFILE_MP2V_HIGH,                  MPEG2ProfileHigh },
    { C2Config::PROFILE_MP2V_422,                   MPEG2Profile422 },
};

ALookup<C2Config::level_t, int32_t> sMpeg4Levels = {
    { C2Config::LEVEL_MP4V_0,   MPEG4Level0 },
    { C2Config::LEVEL_MP4V_0B,  MPEG4Level0b },
    { C2Config::LEVEL_MP4V_1,   MPEG4Level1 },
    { C2Config::LEVEL_MP4V_2,   MPEG4Level2 },
    { C2Config::LEVEL_MP4V_3,   MPEG4Level3 },
    { C2Config::LEVEL_MP4V_3B,  MPEG4Level3b },
    { C2Config::LEVEL_MP4V_4,   MPEG4Level4 },
    { C2Config::LEVEL_MP4V_4A,  MPEG4Level4a },
    { C2Config::LEVEL_MP4V_5,   MPEG4Level5 },
    { C2Config::LEVEL_MP4V_6,   MPEG4Level6 },
};

ALookup<C2Config::profile_t, int32_t> sMpeg4Profiles = {
    { C2Config::PROFILE_MP4V_SIMPLE,            MPEG4ProfileSimple },
    { C2Config::PROFILE_MP4V_SIMPLE_SCALABLE,   MPEG4ProfileSimpleScalable },
    { C2Config::PROFILE_MP4V_CORE,              MPEG4ProfileCore },
    { C2Config::PROFILE_MP4V_MAIN,              MPEG4ProfileMain },
    { C2Config::PROFILE_MP4V_NBIT,              MPEG4ProfileNbit },
    { C2Config::PROFILE_MP4V_ARTS,              MPEG4ProfileAdvancedRealTime },
    { C2Config::PROFILE_MP4V_CORE_SCALABLE,     MPEG4ProfileCoreScalable },
    { C2Config::PROFILE_MP4V_ACE,               MPEG4ProfileAdvancedCoding },
    { C2Config::PROFILE_MP4V_ADVANCED_CORE,     MPEG4ProfileAdvancedCore },
    { C2Config::PROFILE_MP4V_ADVANCED_SIMPLE,   MPEG4ProfileAdvancedSimple },
};

ALookup<C2Config::pcm_encoding_t, int32_t> sPcmEncodings = {
    { C2Config::PCM_8, kAudioEncodingPcm8bit },
    { C2Config::PCM_16, kAudioEncodingPcm16bit },
    { C2Config::PCM_FLOAT, kAudioEncodingPcmFloat },
};

ALookup<C2Config::level_t, int32_t> sVp9Levels = {
    { C2Config::LEVEL_VP9_1,    VP9Level1 },
    { C2Config::LEVEL_VP9_1_1,  VP9Level11 },
    { C2Config::LEVEL_VP9_2,    VP9Level2 },
    { C2Config::LEVEL_VP9_2_1,  VP9Level21 },
    { C2Config::LEVEL_VP9_3,    VP9Level3 },
    { C2Config::LEVEL_VP9_3_1,  VP9Level31 },
    { C2Config::LEVEL_VP9_4,    VP9Level4 },
    { C2Config::LEVEL_VP9_4_1,  VP9Level41 },
    { C2Config::LEVEL_VP9_5,    VP9Level5 },
    { C2Config::LEVEL_VP9_5_1,  VP9Level51 },
    { C2Config::LEVEL_VP9_5_2,  VP9Level52 },
    { C2Config::LEVEL_VP9_6,    VP9Level6 },
    { C2Config::LEVEL_VP9_6_1,  VP9Level61 },
    { C2Config::LEVEL_VP9_6_2,  VP9Level62 },
};

ALookup<C2Config::profile_t, int32_t> sVp9Profiles = {
    { C2Config::PROFILE_VP9_0, VP9Profile0 },
    { C2Config::PROFILE_VP9_1, VP9Profile1 },
    { C2Config::PROFILE_VP9_2, VP9Profile2 },
    { C2Config::PROFILE_VP9_3, VP9Profile3 },
};

/**
 * A helper that passes through vendor extension profile and level values.
 */
struct ProfileLevelMapperHelper : C2Mapper::ProfileLevelMapper {
    virtual bool simpleMap(C2Config::level_t from, int32_t *to) = 0;
    virtual bool simpleMap(int32_t from, C2Config::level_t *to) = 0;
    virtual bool simpleMap(C2Config::profile_t from, int32_t *to) = 0;
    virtual bool simpleMap(int32_t from, C2Config::profile_t *to) = 0;

    template<typename T, typename U>
    bool passThroughMap(T from, U *to) {
        // allow (and pass through) vendor extensions
        if (from >= (T)C2_PROFILE_LEVEL_VENDOR_START && from < (T)INT32_MAX) {
            *to = (U)from;
            return true;
        }
        return simpleMap(from, to);
    }

    virtual bool mapLevel(C2Config::level_t from, int32_t *to) {
        return passThroughMap(from, to);
    }

    virtual bool mapLevel(int32_t from, C2Config::level_t *to) {
        return passThroughMap(from, to);
    }

    virtual bool mapProfile(C2Config::profile_t from, int32_t *to) {
        return passThroughMap(from, to);
    }

    virtual bool mapProfile(int32_t from, C2Config::profile_t *to) {
        return passThroughMap(from, to);
    }
};

// AAC only uses profiles, map all levels to unused or 0
struct AacProfileLevelMapper : ProfileLevelMapperHelper {
    virtual bool simpleMap(C2Config::level_t, int32_t *to) {
        *to = 0;
        return true;
    }
    virtual bool simpleMap(int32_t, C2Config::level_t *to) {
        *to = C2Config::LEVEL_UNUSED;
        return true;
    }
    virtual bool simpleMap(C2Config::profile_t from, int32_t *to) {
        return sAacProfiles.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::profile_t *to) {
        return sAacProfiles.map(from, to);
    }
};

struct AvcProfileLevelMapper : ProfileLevelMapperHelper {
    virtual bool simpleMap(C2Config::level_t from, int32_t *to) {
        return sAvcLevels.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::level_t *to) {
        return sAvcLevels.map(from, to);
    }
    virtual bool simpleMap(C2Config::profile_t from, int32_t *to) {
        return sAvcProfiles.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::profile_t *to) {
        return sAvcProfiles.map(from, to);
    }
};

struct DolbyVisionProfileLevelMapper : ProfileLevelMapperHelper {
    virtual bool simpleMap(C2Config::level_t from, int32_t *to) {
        return sDolbyVisionLevels.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::level_t *to) {
        return sDolbyVisionLevels.map(from, to);
    }
    virtual bool simpleMap(C2Config::profile_t from, int32_t *to) {
        return sDolbyVisionProfiles.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::profile_t *to) {
        return sDolbyVisionProfiles.map(from, to);
    }
};

struct H263ProfileLevelMapper : ProfileLevelMapperHelper {
    virtual bool simpleMap(C2Config::level_t from, int32_t *to) {
        return sH263Levels.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::level_t *to) {
        return sH263Levels.map(from, to);
    }
    virtual bool simpleMap(C2Config::profile_t from, int32_t *to) {
        return sH263Profiles.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::profile_t *to) {
        return sH263Profiles.map(from, to);
    }
};

struct HevcProfileLevelMapper : ProfileLevelMapperHelper {
    virtual bool simpleMap(C2Config::level_t from, int32_t *to) {
        return sHevcLevels.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::level_t *to) {
        return sHevcLevels.map(from, to);
    }
    virtual bool simpleMap(C2Config::profile_t from, int32_t *to) {
        return sHevcProfiles.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::profile_t *to) {
        return sHevcProfiles.map(from, to);
    }
};

struct Mpeg2ProfileLevelMapper : ProfileLevelMapperHelper {
    virtual bool simpleMap(C2Config::level_t from, int32_t *to) {
        return sMpeg2Levels.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::level_t *to) {
        return sMpeg2Levels.map(from, to);
    }
    virtual bool simpleMap(C2Config::profile_t from, int32_t *to) {
        return sMpeg2Profiles.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::profile_t *to) {
        return sMpeg2Profiles.map(from, to);
    }
};

struct Mpeg4ProfileLevelMapper : ProfileLevelMapperHelper {
    virtual bool simpleMap(C2Config::level_t from, int32_t *to) {
        return sMpeg4Levels.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::level_t *to) {
        return sMpeg4Levels.map(from, to);
    }
    virtual bool simpleMap(C2Config::profile_t from, int32_t *to) {
        return sMpeg4Profiles.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::profile_t *to) {
        return sMpeg4Profiles.map(from, to);
    }
};

// VP8 has no profiles and levels in Codec 2.0, but we use main profile and level 0 in MediaCodec
// map all profiles and levels to that.
struct Vp8ProfileLevelMapper : ProfileLevelMapperHelper {
    virtual bool simpleMap(C2Config::level_t, int32_t *to) {
        *to = VP8Level_Version0;
        return true;
    }
    virtual bool simpleMap(int32_t, C2Config::level_t *to) {
        *to = C2Config::LEVEL_UNUSED;
        return true;
    }
    virtual bool simpleMap(C2Config::profile_t, int32_t *to) {
        *to = VP8ProfileMain;
        return true;
    }
    virtual bool simpleMap(int32_t, C2Config::profile_t *to) {
        *to = C2Config::PROFILE_UNUSED;
        return true;
    }
};

struct Vp9ProfileLevelMapper : ProfileLevelMapperHelper {
    virtual bool simpleMap(C2Config::level_t from, int32_t *to) {
        return sVp9Levels.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::level_t *to) {
        return sVp9Levels.map(from, to);
    }
    virtual bool simpleMap(C2Config::profile_t from, int32_t *to) {
        return sVp9Profiles.map(from, to);
    }
    virtual bool simpleMap(int32_t from, C2Config::profile_t *to) {
        return sVp9Profiles.map(from, to);
    }
};

} // namespace

// static
std::shared_ptr<C2Mapper::ProfileLevelMapper>
C2Mapper::GetProfileLevelMapper(std::string mediaType) {
    std::transform(mediaType.begin(), mediaType.begin(), mediaType.end(), ::tolower);
    if (mediaType == MIMETYPE_AUDIO_AAC) {
        return std::make_shared<AacProfileLevelMapper>();
    } else if (mediaType == MIMETYPE_VIDEO_AVC) {
        return std::make_shared<AvcProfileLevelMapper>();
    } else if (mediaType == MIMETYPE_VIDEO_DOLBY_VISION) {
        return std::make_shared<DolbyVisionProfileLevelMapper>();
    } else if (mediaType == MIMETYPE_VIDEO_H263) {
        return std::make_shared<H263ProfileLevelMapper>();
    } else if (mediaType == MIMETYPE_VIDEO_HEVC) {
        return std::make_shared<HevcProfileLevelMapper>();
    } else if (mediaType == MIMETYPE_VIDEO_MPEG2) {
        return std::make_shared<Mpeg2ProfileLevelMapper>();
    } else if (mediaType == MIMETYPE_VIDEO_MPEG4) {
        return std::make_shared<Mpeg4ProfileLevelMapper>();
    } else if (mediaType == MIMETYPE_VIDEO_VP8) {
        return std::make_shared<Vp8ProfileLevelMapper>();
    } else if (mediaType == MIMETYPE_VIDEO_VP9) {
        return std::make_shared<Vp9ProfileLevelMapper>();
    }
    return nullptr;
}

// static
bool C2Mapper::map(C2Config::bitrate_mode_t from, int32_t *to) {
    return sBitrateModes.map(from, to);
}

// static
bool C2Mapper::map(int32_t from, C2Config::bitrate_mode_t *to) {
    return sBitrateModes.map(from, to);
}

// static
bool C2Mapper::map(C2Config::pcm_encoding_t from, int32_t *to) {
    return sPcmEncodings.map(from, to);
}

// static
bool C2Mapper::map(int32_t from, C2Config::pcm_encoding_t *to) {
    return sPcmEncodings.map(from, to);
}

