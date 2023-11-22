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
#define LOG_TAG "Codec2InfoBuilder"
#include <log/log.h>

#include <codec2/hidl/client.h>

#include <C2Component.h>
#include <C2Config.h>
#include <C2Debug.h>
#include <C2PlatformSupport.h>
#include <C2V4l2Support.h>
#include <Codec2Mapper.h>

#include <android-base/properties.h>
#include <media/stagefright/MediaCodecConstants.h>
#include <media/stagefright/foundation/MediaDefs.h>
#include <media/stagefright/xmlparser/MediaCodecsXmlParser.h>

#include "Codec2InfoBuilder.h"

namespace android {

using Traits = C2Component::Traits;

namespace /* unnamed */ {

bool hasPrefix(const std::string& s, const char* prefix) {
    size_t prefixLen = strlen(prefix);
    return s.compare(0, prefixLen, prefix) == 0;
}

bool hasSuffix(const std::string& s, const char* suffix) {
    size_t suffixLen = strlen(suffix);
    return suffixLen > s.size() ? false :
            s.compare(s.size() - suffixLen, suffixLen, suffix) == 0;
}

} // unnamed namespace

status_t Codec2InfoBuilder::buildMediaCodecList(MediaCodecListWriter* writer) {
    // TODO: Remove run-time configurations once all codecs are working
    // properly. (Assume "full" behavior eventually.)
    //
    // debug.stagefright.ccodec supports 5 values.
    //   0 - Only OMX components are available.
    //   1 - Codec2.0 software audio decoders/encoders are available and
    //       ranked 1st.
    //       Components with "c2.vda." prefix are available with their normal
    //       ranks.
    //       Other components with ".avc.decoder" or ".avc.encoder" suffix are
    //       available, but ranked last.
    //   2 - All Codec2.0 components are available.
    //       Codec2.0 software audio decoders are ranked 1st.
    //       The other Codec2.0 components have their normal ranks.
    //   3 - All Codec2.0 components are available.
    //       Codec2.0 software components are ranked 1st.
    //       The other Codec2.0 components have their normal ranks.
    //   4 - All Codec2.0 components are available with their normal ranks.
    //
    // The default value (boot time) is 1.
    //
    // Note: Currently, OMX components have default rank 0x100, while all
    // Codec2.0 software components have default rank 0x200.
    int option = ::android::base::GetIntProperty("debug.stagefright.ccodec", 1);

    // Obtain Codec2Client
    std::vector<Traits> traits = Codec2Client::ListComponents();

    MediaCodecsXmlParser parser(
            MediaCodecsXmlParser::defaultSearchDirs,
            "media_codecs_c2.xml");
    if (parser.getParsingStatus() != OK) {
        ALOGD("XML parser no good");
        return OK;
    }
    for (const Traits& trait : traits) {
        if (parser.getCodecMap().count(trait.name.c_str()) == 0) {
            ALOGD("%s not found in xml", trait.name.c_str());
            continue;
        }

        // TODO: Remove this block once all codecs are enabled by default.
        C2Component::rank_t rank = trait.rank;
        switch (option) {
        case 0:
            continue;
        case 1:
            if (hasPrefix(trait.name, "c2.vda.")) {
                break;
            }
            if (hasPrefix(trait.name, "c2.android.")) {
                if (trait.domain == C2Component::DOMAIN_AUDIO) {
                    rank = 1;
                    break;
                }
                continue;
            }
            if (hasSuffix(trait.name, ".avc.decoder") ||
                    hasSuffix(trait.name, ".avc.encoder")) {
                rank = std::numeric_limits<decltype(rank)>::max();
                break;
            }
            continue;
        case 2:
            if (trait.domain == C2Component::DOMAIN_AUDIO &&
                    trait.kind == C2Component::KIND_DECODER) {
        case 3:
                if (hasPrefix(trait.name, "c2.android.")) {
                    rank = 1;
                }
            }
            break;
        }

        const MediaCodecsXmlParser::CodecProperties &codec = parser.getCodecMap().at(trait.name);
        std::unique_ptr<MediaCodecInfoWriter> codecInfo = writer->addMediaCodecInfo();
        codecInfo->setName(trait.name.c_str());
        codecInfo->setOwner("dummy");
        // TODO: get this from trait.kind
        bool encoder = (trait.name.find("encoder") != std::string::npos);
        codecInfo->setEncoder(encoder);
        codecInfo->setRank(rank);
        for (auto typeIt = codec.typeMap.begin(); typeIt != codec.typeMap.end(); ++typeIt) {
            const std::string &mediaType = typeIt->first;
            const MediaCodecsXmlParser::AttributeMap &attrMap = typeIt->second;
            std::unique_ptr<MediaCodecInfo::CapabilitiesWriter> caps =
                codecInfo->addMime(mediaType.c_str());
            for (auto attrIt = attrMap.begin(); attrIt != attrMap.end(); ++attrIt) {
                std::string key, value;
                std::tie(key, value) = *attrIt;
                if (key.find("feature-") == 0 && key.find("feature-bitrate-modes") != 0) {
                    caps->addDetail(key.c_str(), std::stoi(value));
                } else {
                    caps->addDetail(key.c_str(), value.c_str());
                }
            }

            bool gotProfileLevels = false;
            std::shared_ptr<Codec2Client::Interface> intf =
                Codec2Client::CreateInterfaceByName(trait.name.c_str());
            if (intf) {
                std::shared_ptr<C2Mapper::ProfileLevelMapper> mapper =
                    C2Mapper::GetProfileLevelMapper(trait.mediaType);
                // if we don't know the media type, pass through all values unmapped

                // TODO: we cannot find levels that are local 'maxima' without knowing the coding
                // e.g. H.263 level 45 and level 30 could be two values for highest level as
                // they don't include one another. For now we use the last supported value.
                C2StreamProfileLevelInfo pl(encoder /* output */, 0u);
                std::vector<C2FieldSupportedValuesQuery> profileQuery = {
                    C2FieldSupportedValuesQuery::Possible(C2ParamField(&pl, &pl.profile))
                };

                c2_status_t err = intf->querySupportedValues(profileQuery, C2_DONT_BLOCK);
                ALOGV("query supported profiles -> %s | %s",
                        asString(err), asString(profileQuery[0].status));
                if (err == C2_OK && profileQuery[0].status == C2_OK) {
                    if (profileQuery[0].values.type == C2FieldSupportedValues::VALUES) {
                        for (C2Value::Primitive profile : profileQuery[0].values.values) {
                            pl.profile = (C2Config::profile_t)profile.ref<uint32_t>();
                            std::vector<std::unique_ptr<C2SettingResult>> failures;
                            err = intf->config({&pl}, C2_DONT_BLOCK, &failures);
                            ALOGV("set profile to %u -> %s", pl.profile, asString(err));
                            std::vector<C2FieldSupportedValuesQuery> levelQuery = {
                                C2FieldSupportedValuesQuery::Current(C2ParamField(&pl, &pl.level))
                            };
                            err = intf->querySupportedValues(levelQuery, C2_DONT_BLOCK);
                            ALOGV("query supported levels -> %s | %s",
                                    asString(err), asString(levelQuery[0].status));
                            if (err == C2_OK && levelQuery[0].status == C2_OK) {
                                if (levelQuery[0].values.type == C2FieldSupportedValues::VALUES
                                        && levelQuery[0].values.values.size() > 0) {
                                    C2Value::Primitive level = levelQuery[0].values.values.back();
                                    pl.level = (C2Config::level_t)level.ref<uint32_t>();
                                    ALOGV("supporting level: %u", pl.level);
                                    int32_t sdkProfile, sdkLevel;
                                    if (mapper && mapper->mapProfile(pl.profile, &sdkProfile)
                                            && mapper->mapLevel(pl.level, &sdkLevel)) {
                                        caps->addProfileLevel(
                                                (uint32_t)sdkProfile, (uint32_t)sdkLevel);
                                        gotProfileLevels = true;
                                    } else if (!mapper) {
                                        caps->addProfileLevel(pl.profile, pl.level);
                                        gotProfileLevels = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!gotProfileLevels) {
                if (mediaType == MIMETYPE_VIDEO_VP9) {
                    if (encoder) {
                        caps->addProfileLevel(VP9Profile0,    VP9Level41);
                    } else {
                        caps->addProfileLevel(VP9Profile0,    VP9Level5);
                        caps->addProfileLevel(VP9Profile2,    VP9Level5);
                        caps->addProfileLevel(VP9Profile2HDR, VP9Level5);
                    }
                } else if (mediaType == MIMETYPE_VIDEO_HEVC && !encoder) {
                    caps->addProfileLevel(HEVCProfileMain,      HEVCMainTierLevel51);
                    caps->addProfileLevel(HEVCProfileMainStill, HEVCMainTierLevel51);
                } else if (mediaType == MIMETYPE_VIDEO_VP8) {
                    if (encoder) {
                        caps->addProfileLevel(VP8ProfileMain, VP8Level_Version0);
                    } else {
                        caps->addProfileLevel(VP8ProfileMain, VP8Level_Version0);
                    }
                } else if (mediaType == MIMETYPE_VIDEO_AVC) {
                    if (encoder) {
                        caps->addProfileLevel(AVCProfileBaseline,            AVCLevel41);
//                      caps->addProfileLevel(AVCProfileConstrainedBaseline, AVCLevel41);
                        caps->addProfileLevel(AVCProfileMain,                AVCLevel41);
                    } else {
                        caps->addProfileLevel(AVCProfileBaseline,            AVCLevel52);
                        caps->addProfileLevel(AVCProfileConstrainedBaseline, AVCLevel52);
                        caps->addProfileLevel(AVCProfileMain,                AVCLevel52);
                        caps->addProfileLevel(AVCProfileConstrainedHigh,     AVCLevel52);
                        caps->addProfileLevel(AVCProfileHigh,                AVCLevel52);
                    }
                } else if (mediaType == MIMETYPE_VIDEO_MPEG4) {
                    if (encoder) {
                        caps->addProfileLevel(MPEG4ProfileSimple,  MPEG4Level2);
                    } else {
                        caps->addProfileLevel(MPEG4ProfileSimple,  MPEG4Level3);
                    }
                } else if (mediaType == MIMETYPE_VIDEO_H263) {
                    if (encoder) {
                        caps->addProfileLevel(H263ProfileBaseline, H263Level45);
                    } else {
                        caps->addProfileLevel(H263ProfileBaseline, H263Level30);
                        caps->addProfileLevel(H263ProfileBaseline, H263Level45);
                        caps->addProfileLevel(H263ProfileISWV2,    H263Level30);
                        caps->addProfileLevel(H263ProfileISWV2,    H263Level45);
                    }
                } else if (mediaType == MIMETYPE_VIDEO_MPEG2 && !encoder) {
                    caps->addProfileLevel(MPEG2ProfileSimple, MPEG2LevelHL);
                    caps->addProfileLevel(MPEG2ProfileMain,   MPEG2LevelHL);
                }
            }

            // TODO: get this from intf() as well, but how do we map them to
            // MediaCodec color formats?
            if (mediaType.find("video") != std::string::npos) {
                // vendor video codecs prefer opaque format
                if (trait.name.find("android") == std::string::npos) {
                    caps->addColorFormat(COLOR_FormatSurface);
                }
                caps->addColorFormat(COLOR_FormatYUV420Flexible);
                caps->addColorFormat(COLOR_FormatYUV420Planar);
                caps->addColorFormat(COLOR_FormatYUV420SemiPlanar);
                caps->addColorFormat(COLOR_FormatYUV420PackedPlanar);
                caps->addColorFormat(COLOR_FormatYUV420PackedSemiPlanar);
                // framework video encoders must support surface format, though it is unclear
                // that they will be able to map it if it is opaque
                if (encoder && trait.name.find("android") != std::string::npos) {
                    caps->addColorFormat(COLOR_FormatSurface);
                }
            }
        }
    }
    return OK;
}

}  // namespace android

extern "C" android::MediaCodecListBuilderBase *CreateBuilder() {
    return new android::Codec2InfoBuilder;
}

