/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com. Represented by EHIMA -
 * www.ehima.com
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

/*
 * This file contains definitions for Basic Audio Profile / Audio Stream Control
 * and Published Audio Capabilities definitions, structures etc.
 */

#include "le_audio_types.h"

#include <base/strings/string_number_conversions.h>

#include "bt_types.h"
#include "bta_api.h"
#include "bta_le_audio_api.h"
#include "client_audio.h"
#include "client_parser.h"

namespace le_audio {
using types::acs_ac_record;
using types::LeAudioContextType;

namespace set_configurations {
using set_configurations::CodecCapabilitySetting;
using types::CodecLocation;
using types::kLeAudioCodingFormatLC3;
using types::kLeAudioDirectionSink;
using types::kLeAudioDirectionSource;
using types::LeAudioLc3Config;

static uint8_t min_req_devices_cnt(
    const AudioSetConfiguration* audio_set_conf) {
  std::pair<uint8_t /* sink */, uint8_t /* source */> snk_src_pair(0, 0);

  for (auto ent : (*audio_set_conf).confs) {
    if (ent.direction == kLeAudioDirectionSink)
      snk_src_pair.first += ent.device_cnt;
    if (ent.direction == kLeAudioDirectionSource)
      snk_src_pair.second += ent.device_cnt;
  }

  return std::max(snk_src_pair.first, snk_src_pair.second);
}

static uint8_t min_req_devices_cnt(
    const AudioSetConfigurations* audio_set_confs) {
  uint8_t curr_min_req_devices_cnt = 0xff;

  for (auto ent : *audio_set_confs) {
    uint8_t req_devices_cnt = min_req_devices_cnt(ent);
    if (req_devices_cnt < curr_min_req_devices_cnt)
      curr_min_req_devices_cnt = req_devices_cnt;
  }

  return curr_min_req_devices_cnt;
}

bool check_if_may_cover_scenario(const AudioSetConfigurations* audio_set_confs,
                                 uint8_t group_size) {
  if (!audio_set_confs) {
    LOG(ERROR) << __func__ << ", no audio requirements for group";
    return false;
  }

  return group_size >= min_req_devices_cnt(audio_set_confs);
}

bool check_if_may_cover_scenario(const AudioSetConfiguration* audio_set_conf,
                                 uint8_t group_size) {
  if (!audio_set_conf) {
    LOG(ERROR) << __func__ << ", no audio requirement for group";
    return false;
  }

  return group_size >= min_req_devices_cnt(audio_set_conf);
}

uint8_t get_num_of_devices_in_configuration(
    const AudioSetConfiguration* audio_set_conf) {
  return min_req_devices_cnt(audio_set_conf);
}

static bool IsCodecConfigurationSupported(const types::LeAudioLtvMap& pacs,
                                          const LeAudioLc3Config& lc3_config) {
  const auto& reqs = lc3_config.GetAsLtvMap();
  uint8_t u8_req_val, u8_pac_val;
  uint16_t u16_req_val, u16_pac_val;

  /* Sampling frequency */
  auto req = reqs.Find(codec_spec_conf::kLeAudioCodecLC3TypeSamplingFreq);
  auto pac = pacs.Find(codec_spec_caps::kLeAudioCodecLC3TypeSamplingFreq);
  if (!req || !pac) {
    DLOG(ERROR) << __func__ << ", lack of sampling frequency fields";
    return false;
  }

  u8_req_val = VEC_UINT8_TO_UINT8(req.value());
  u16_pac_val = VEC_UINT8_TO_UINT16(pac.value());

  /*
   * Note: Requirements are in the codec configuration specification which
   * are values coming from BAP Appendix A1.2.1
   */
  DLOG(INFO) << __func__ << " Req:SamplFreq=" << loghex(u8_req_val);
  /* NOTE: Below is Codec specific cababilities comes form BAP Appendix A A1.1.1
   * Note this is a bitfield
   */
  DLOG(INFO) << __func__ << " Pac:SamplFreq=" << loghex(u16_pac_val);

  /* TODO: Integrate with codec capabilities */
  if (!(u16_pac_val &
        codec_spec_caps::SamplingFreqConfig2Capability(u8_req_val))) {
    DLOG(ERROR) << __func__ << ", sampling frequency not supported";
    return false;
  }

  /* Frame duration */
  req = reqs.Find(codec_spec_conf::kLeAudioCodecLC3TypeFrameDuration);
  pac = pacs.Find(codec_spec_caps::kLeAudioCodecLC3TypeFrameDuration);
  if (!req || !pac) {
    DLOG(ERROR) << __func__ << ", lack of frame duration fields";
    return false;
  }

  u8_req_val = VEC_UINT8_TO_UINT8(req.value());
  u8_pac_val = VEC_UINT8_TO_UINT8(pac.value());
  DLOG(INFO) << __func__ << " Req:FrameDur=" << loghex(u8_req_val);
  DLOG(INFO) << __func__ << " Pac:FrameDur=" << loghex(u8_pac_val);

  if ((u8_req_val != codec_spec_conf::kLeAudioCodecLC3FrameDur7500us &&
       u8_req_val != codec_spec_conf::kLeAudioCodecLC3FrameDur10000us) ||
      !(u8_pac_val &
        (codec_spec_caps::FrameDurationConfig2Capability(u8_req_val)))) {
    DLOG(ERROR) << __func__ << ", frame duration not supported";
    return false;
  }

  uint8_t required_audio_chan_num = lc3_config.GetChannelCount();
  pac = pacs.Find(codec_spec_caps::kLeAudioCodecLC3TypeAudioChannelCounts);

  /*
   * BAP_Validation_r07 1.9.2 Audio channel support requirements
   * "The Unicast Server shall support an Audio_Channel_Counts value of 0x01
   * (0b00000001 = one channel) and may support other values defined by an
   * implementation or by a higher-layer specification."
   *
   * Thus if Audio_Channel_Counts is not present in PAC LTV structure, we assume
   * the Unicast Server supports mandatory one channel.
   */
  if (!pac) {
    DLOG(WARNING) << __func__ << ", no Audio_Channel_Counts field in PAC";
    u8_pac_val = 0x01;
  } else {
    u8_pac_val = VEC_UINT8_TO_UINT8(pac.value());
  }

  DLOG(INFO) << __func__ << " Pac:AudioChanCnt=" << loghex(u8_pac_val);
  if (!((1 << (required_audio_chan_num - 1)) & u8_pac_val)) {
    DLOG(ERROR) << __func__ << ", channel count warning";
    return false;
  }

  /* Octets per frame */
  req = reqs.Find(codec_spec_conf::kLeAudioCodecLC3TypeOctetPerFrame);
  pac = pacs.Find(codec_spec_caps::kLeAudioCodecLC3TypeOctetPerFrame);

  if (!req || !pac) {
    DLOG(ERROR) << __func__ << ", lack of octet per frame fields";
    return false;
  }

  u16_req_val = VEC_UINT8_TO_UINT16(req.value());
  DLOG(INFO) << __func__ << " Req:OctetsPerFrame=" << int(u16_req_val);

  /* Minimal value 0-1 byte */
  u16_pac_val = VEC_UINT8_TO_UINT16(pac.value());
  DLOG(INFO) << __func__ << " Pac:MinOctetsPerFrame=" << int(u16_pac_val);
  if (u16_req_val < u16_pac_val) {
    DLOG(ERROR) << __func__ << ", octet per frame below minimum";
    return false;
  }

  /* Maximal value 2-3 byte */
  u16_pac_val = OFF_VEC_UINT8_TO_UINT16(pac.value(), 2);
  DLOG(INFO) << __func__ << " Pac:MaxOctetsPerFrame=" << int(u16_pac_val);
  if (u16_req_val > u16_pac_val) {
    DLOG(ERROR) << __func__ << ", octet per frame above maximum";
    return false;
  }

  return true;
}

bool IsCodecCapabilitySettingSupported(
    const acs_ac_record& pac,
    const CodecCapabilitySetting& codec_capability_setting) {
  const auto& codec_id = codec_capability_setting.id;

  if (codec_id != pac.codec_id) return false;

  DLOG(INFO) << __func__ << ": Settings for format " << +codec_id.coding_format;

  switch (codec_id.coding_format) {
    case kLeAudioCodingFormatLC3:
      return IsCodecConfigurationSupported(
          pac.codec_spec_caps,
          std::get<LeAudioLc3Config>(codec_capability_setting.config));
    default:
      return false;
  }
}

uint32_t CodecCapabilitySetting::GetConfigSamplingFrequency() const {
  switch (id.coding_format) {
    case kLeAudioCodingFormatLC3:
      return std::get<types::LeAudioLc3Config>(config).GetSamplingFrequencyHz();
    default:
      DLOG(WARNING) << __func__ << ", invalid codec id";
      return 0;
  }
};

uint32_t CodecCapabilitySetting::GetConfigDataIntervalUs() const {
  switch (id.coding_format) {
    case kLeAudioCodingFormatLC3:
      return std::get<types::LeAudioLc3Config>(config).GetFrameDurationUs();
    default:
      DLOG(WARNING) << __func__ << ", invalid codec id";
      return 0;
  }
};

uint8_t CodecCapabilitySetting::GetConfigBitsPerSample() const {
  switch (id.coding_format) {
    case kLeAudioCodingFormatLC3:
      /* XXX LC3 supports 16, 24, 32 */
      return 16;
    default:
      DLOG(WARNING) << __func__ << ", invalid codec id";
      return 0;
  }
};

uint8_t CodecCapabilitySetting::GetConfigChannelCount() const {
  switch (id.coding_format) {
    case kLeAudioCodingFormatLC3:
      DLOG(INFO) << __func__ << ", count = "
                 << static_cast<int>(std::get<types::LeAudioLc3Config>(config)
                                         .channel_count);
      return std::get<types::LeAudioLc3Config>(config).channel_count;
    default:
      DLOG(WARNING) << __func__ << ", invalid codec id";
      return 0;
  }
}
}  // namespace set_configurations

namespace types {
/* Helper map for matching various frequency notations */
const std::map<uint8_t, uint32_t> LeAudioLc3Config::sampling_freq_map = {
    {codec_spec_conf::kLeAudioSamplingFreq8000Hz,
     LeAudioCodecConfiguration::kSampleRate8000},
    {codec_spec_conf::kLeAudioSamplingFreq16000Hz,
     LeAudioCodecConfiguration::kSampleRate16000},
    {codec_spec_conf::kLeAudioSamplingFreq24000Hz,
     LeAudioCodecConfiguration::kSampleRate24000},
    {codec_spec_conf::kLeAudioSamplingFreq32000Hz,
     LeAudioCodecConfiguration::kSampleRate32000},
    {codec_spec_conf::kLeAudioSamplingFreq44100Hz,
     LeAudioCodecConfiguration::kSampleRate44100},
    {codec_spec_conf::kLeAudioSamplingFreq48000Hz,
     LeAudioCodecConfiguration::kSampleRate48000}};

/* Helper map for matching various frame durations notations */
const std::map<uint8_t, uint32_t> LeAudioLc3Config::frame_duration_map = {
    {codec_spec_conf::kLeAudioCodecLC3FrameDur7500us,
     LeAudioCodecConfiguration::kInterval7500Us},
    {codec_spec_conf::kLeAudioCodecLC3FrameDur10000us,
     LeAudioCodecConfiguration::kInterval10000Us}};

std::optional<std::vector<uint8_t>> LeAudioLtvMap::Find(uint8_t type) const {
  auto iter =
      std::find_if(values.cbegin(), values.cend(),
                   [type](const auto& value) { return value.first == type; });

  if (iter == values.cend()) return std::nullopt;

  return iter->second;
}

uint8_t* LeAudioLtvMap::RawPacket(uint8_t* p_buf) const {
  for (auto const& value : values) {
    UINT8_TO_STREAM(p_buf, value.second.size() + 1);
    UINT8_TO_STREAM(p_buf, value.first);
    ARRAY_TO_STREAM(p_buf, value.second.data(),
                    static_cast<int>(value.second.size()));
  }

  return p_buf;
}

std::vector<uint8_t> LeAudioLtvMap::RawPacket() const {
  std::vector<uint8_t> data(RawPacketSize());
  RawPacket(data.data());
  return data;
}

void LeAudioLtvMap::Append(const LeAudioLtvMap& other) {
  /* This will override values for the already existing keys */
  for (auto& el : other.values) {
    values[el.first] = el.second;
  }
}

LeAudioLtvMap LeAudioLtvMap::Parse(const uint8_t* p_value, uint8_t len,
                                   bool& success) {
  LeAudioLtvMap ltv_map;

  if (len > 0) {
    const auto p_value_end = p_value + len;

    while ((p_value_end - p_value) > 0) {
      uint8_t ltv_len;
      STREAM_TO_UINT8(ltv_len, p_value);

      // Unusual, but possible case
      if (ltv_len == 0) continue;

      if (p_value_end < (p_value + ltv_len)) {
        LOG(ERROR) << __func__
                   << " Invalid ltv_len: " << static_cast<int>(ltv_len);
        success = false;
        return LeAudioLtvMap();
      }

      uint8_t ltv_type;
      STREAM_TO_UINT8(ltv_type, p_value);
      ltv_len -= sizeof(ltv_type);

      const auto p_temp = p_value;
      p_value += ltv_len;

      std::vector<uint8_t> ltv_value(p_temp, p_value);
      ltv_map.values.emplace(ltv_type, std::move(ltv_value));
    }
  }

  success = true;
  return ltv_map;
}

size_t LeAudioLtvMap::RawPacketSize() const {
  size_t bytes = 0;

  for (auto const& value : values) {
    bytes += (/* ltv_len + ltv_type */ 2 + value.second.size());
  }

  return bytes;
}

std::string LeAudioLtvMap::ToString() const {
  std::string debug_str;

  for (const auto& value : values) {
    std::stringstream sstream;

    sstream << "\ttype: " << std::to_string(value.first)
            << "\tlen: " << std::to_string(value.second.size()) << "\tdata: "
            << base::HexEncode(value.second.data(), value.second.size()) + "\n";

    debug_str += sstream.str();
  }

  return debug_str;
}

}  // namespace types

void AppendMetadataLtvEntryForCcidList(std::vector<uint8_t>& metadata,
                                       int ccid) {
  if (ccid < 0) return;

  std::vector<uint8_t> ccid_ltv_entry;
  std::vector<uint8_t> ccid_value = {static_cast<uint8_t>(ccid)};

  ccid_ltv_entry.push_back(
      static_cast<uint8_t>(types::kLeAudioMetadataTypeLen + ccid_value.size()));
  ccid_ltv_entry.push_back(
      static_cast<uint8_t>(types::kLeAudioMetadataTypeCcidList));
  ccid_ltv_entry.insert(ccid_ltv_entry.end(), ccid_value.begin(),
                        ccid_value.end());

  metadata.insert(metadata.end(), ccid_ltv_entry.begin(), ccid_ltv_entry.end());
}

void AppendMetadataLtvEntryForStreamingContext(
    std::vector<uint8_t>& metadata, LeAudioContextType context_type) {
  std::vector<uint8_t> streaming_context_ltv_entry;

  streaming_context_ltv_entry.resize(
      types::kLeAudioMetadataTypeLen + types::kLeAudioMetadataLenLen +
      types::kLeAudioMetadataStreamingAudioContextLen);
  uint8_t* streaming_context_ltv_entry_buf = streaming_context_ltv_entry.data();

  UINT8_TO_STREAM(streaming_context_ltv_entry_buf,
                  types::kLeAudioMetadataTypeLen +
                      types::kLeAudioMetadataStreamingAudioContextLen);
  UINT8_TO_STREAM(streaming_context_ltv_entry_buf,
                  types::kLeAudioMetadataTypeStreamingAudioContext);
  UINT16_TO_STREAM(streaming_context_ltv_entry_buf,
                   static_cast<uint16_t>(context_type));

  metadata.insert(metadata.end(), streaming_context_ltv_entry.begin(),
                  streaming_context_ltv_entry.end());
}

uint8_t GetMaxCodecFramesPerSduFromPac(const acs_ac_record* pac) {
  auto tlv_ent = pac->codec_spec_caps.Find(
      codec_spec_caps::kLeAudioCodecLC3TypeMaxCodecFramesPerSdu);

  if (tlv_ent) return VEC_UINT8_TO_UINT8(tlv_ent.value());

  return 1;
}

namespace types {
std::ostream& operator<<(std::ostream& os, const types::CigState& state) {
  static const char* char_value_[4] = {"NONE", "CREATING", "CREATED",
                                       "REMOVING"};

  os << char_value_[static_cast<uint8_t>(state)] << " ("
     << "0x" << std::setfill('0') << std::setw(2) << static_cast<int>(state)
     << ")";
  return os;
}
std::ostream& operator<<(std::ostream& os, const types::AseState& state) {
  static const char* char_value_[7] = {
      "IDLE",      "CODEC_CONFIGURED", "QOS_CONFIGURED", "ENABLING",
      "STREAMING", "DISABLING",        "RELEASING",
  };

  os << char_value_[static_cast<uint8_t>(state)] << " ("
     << "0x" << std::setfill('0') << std::setw(2) << static_cast<int>(state)
     << ")";
  return os;
}

std::ostream& operator<<(std::ostream& os,
                         const types::LeAudioLc3Config& config) {
  os << " LeAudioLc3Config(SamplFreq=" << loghex(*config.sampling_frequency)
     << ", FrameDur=" << loghex(*config.frame_duration)
     << ", OctetsPerFrame=" << int(*config.octets_per_codec_frame)
     << ", CodecFramesBlocksPerSDU=" << int(*config.codec_frames_blocks_per_sdu)
     << ", AudioChanLoc=" << loghex(*config.audio_channel_allocation) << ")";
  return os;
}
}  // namespace types

}  // namespace le_audio
