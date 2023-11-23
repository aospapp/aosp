/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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
#pragma once

#include <future>

#include "audio_hal_interface/le_audio_software.h"
#include "common/repeating_timer.h"

/* Implementations of Le Audio will also implement this interface */
class LeAudioClientAudioSinkReceiver {
 public:
  virtual ~LeAudioClientAudioSinkReceiver() = default;
  virtual void OnAudioDataReady(const std::vector<uint8_t>& data) = 0;
  virtual void OnAudioSuspend(std::promise<void> do_suspend_promise) = 0;
  virtual void OnAudioResume(void) = 0;
  virtual void OnAudioMetadataUpdate(
      std::promise<void> do_update_metadata_promise,
      const source_metadata_t& source_metadata) = 0;
};
class LeAudioClientAudioSourceReceiver {
 public:
  virtual ~LeAudioClientAudioSourceReceiver() = default;
  virtual void OnAudioSuspend(std::promise<void> do_suspend_promise) = 0;
  virtual void OnAudioResume(void) = 0;
  virtual void OnAudioMetadataUpdate(
      std::promise<void> do_update_metadata_promise,
      const sink_metadata_t& sink_metadata) = 0;
};

/* Represents configuration of audio codec, as exchanged between le audio and
 * phone.
 * It can also be passed to the audio source to configure its parameters.
 */
struct LeAudioCodecConfiguration {
  static constexpr uint8_t kChannelNumberMono =
      bluetooth::audio::le_audio::kChannelNumberMono;
  static constexpr uint8_t kChannelNumberStereo =
      bluetooth::audio::le_audio::kChannelNumberStereo;

  static constexpr uint32_t kSampleRate48000 =
      bluetooth::audio::le_audio::kSampleRate48000;
  static constexpr uint32_t kSampleRate44100 =
      bluetooth::audio::le_audio::kSampleRate44100;
  static constexpr uint32_t kSampleRate32000 =
      bluetooth::audio::le_audio::kSampleRate32000;
  static constexpr uint32_t kSampleRate24000 =
      bluetooth::audio::le_audio::kSampleRate24000;
  static constexpr uint32_t kSampleRate16000 =
      bluetooth::audio::le_audio::kSampleRate16000;
  static constexpr uint32_t kSampleRate8000 =
      bluetooth::audio::le_audio::kSampleRate8000;

  static constexpr uint8_t kBitsPerSample16 =
      bluetooth::audio::le_audio::kBitsPerSample16;
  static constexpr uint8_t kBitsPerSample24 =
      bluetooth::audio::le_audio::kBitsPerSample24;
  static constexpr uint8_t kBitsPerSample32 =
      bluetooth::audio::le_audio::kBitsPerSample32;

  static constexpr uint32_t kInterval7500Us = 7500;
  static constexpr uint32_t kInterval10000Us = 10000;

  /** number of channels */
  uint8_t num_channels;

  /** sampling rate that the codec expects to receive from audio framework */
  uint32_t sample_rate;

  /** bits per sample that codec expects to receive from audio framework */
  uint8_t bits_per_sample;

  /** Data interval determines how often we send samples to the remote. This
   * should match how often we grab data from audio source, optionally we can
   * grab data every 2 or 3 intervals, but this would increase latency.
   *
   * Value is provided in us.
   */
  uint32_t data_interval_us;

  bool operator!=(const LeAudioCodecConfiguration& other) {
    return !((num_channels == other.num_channels) &&
             (sample_rate == other.sample_rate) &&
             (bits_per_sample == other.bits_per_sample) &&
             (data_interval_us == other.data_interval_us));
  }

  bool operator==(const LeAudioCodecConfiguration& other) const {
    return ((num_channels == other.num_channels) &&
            (sample_rate == other.sample_rate) &&
            (bits_per_sample == other.bits_per_sample) &&
            (data_interval_us == other.data_interval_us));
  }

  bool IsInvalid() {
    return (num_channels == 0) || (sample_rate == 0) ||
           (bits_per_sample == 0) || (data_interval_us == 0);
  }
};

/* Represents source of audio for le audio client */
class LeAudioClientAudioSource {
 public:
  virtual ~LeAudioClientAudioSource() = default;

  virtual bool Start(const LeAudioCodecConfiguration& codecConfiguration,
                     LeAudioClientAudioSinkReceiver* audioReceiver);
  virtual void Stop();
  virtual void Release(const void* instance);
  virtual void ConfirmStreamingRequest();
  virtual void CancelStreamingRequest();
  virtual void UpdateRemoteDelay(uint16_t remote_delay_ms);
  virtual void UpdateAudioConfigToHal(const ::le_audio::offload_config& config);
  virtual void SuspendedForReconfiguration();

  static void DebugDump(int fd);

 protected:
  const void* Acquire(bool is_broadcasting_session_type);
  bool InitAudioSinkThread(const std::string name);

  bluetooth::common::MessageLoopThread* worker_thread_;

 private:
  bool SinkOnResumeReq(bool start_media_task);
  bool SinkOnSuspendReq();
  bool SinkOnMetadataUpdateReq(const source_metadata_t& source_metadata);

  void StartAudioTicks();
  void StopAudioTicks();
  void SendAudioData();

  bluetooth::common::RepeatingTimer audio_timer_;
  LeAudioCodecConfiguration source_codec_config_;
  LeAudioClientAudioSinkReceiver* audioSinkReceiver_;
  bluetooth::audio::le_audio::LeAudioClientInterface::Sink*
      sinkClientInterface_;

  /* Guard audio sink receiver mutual access from stack with internal mutex */
  std::mutex sinkInterfaceMutex_;
};

/* Represents audio sink for le audio client */
class LeAudioUnicastClientAudioSink {
 public:
  virtual ~LeAudioUnicastClientAudioSink() = default;

  virtual bool Start(const LeAudioCodecConfiguration& codecConfiguration,
                     LeAudioClientAudioSourceReceiver* audioReceiver);
  virtual void Stop();
  virtual const void* Acquire();
  virtual void Release(const void* instance);
  virtual size_t SendData(uint8_t* data, uint16_t size);
  virtual void ConfirmStreamingRequest();
  virtual void CancelStreamingRequest();
  virtual void UpdateRemoteDelay(uint16_t remote_delay_ms);
  virtual void UpdateAudioConfigToHal(const ::le_audio::offload_config& config);
  virtual void SuspendedForReconfiguration();

  static void DebugDump(int fd);

 private:
  bool SourceOnResumeReq(bool start_media_task);
  bool SourceOnSuspendReq();
  bool SourceOnMetadataUpdateReq(const sink_metadata_t& sink_metadata);

  LeAudioClientAudioSourceReceiver* audioSourceReceiver_;
  bluetooth::audio::le_audio::LeAudioClientInterface::Source*
      sourceClientInterface_;
};

class LeAudioUnicastClientAudioSource : public LeAudioClientAudioSource {
 public:
  virtual const void* Acquire();
};

class LeAudioBroadcastClientAudioSource : public LeAudioClientAudioSource {
 public:
  virtual const void* Acquire();
};
