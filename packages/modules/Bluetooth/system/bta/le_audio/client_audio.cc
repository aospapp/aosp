/******************************************************************************
 *
 * Copyright 2019 HIMSA II K/S - www.himsa.com. Represented by EHIMA -
 * www.ehima.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

#include "client_audio.h"

#include "audio_hal_interface/le_audio_software.h"
#include "bta/le_audio/codec_manager.h"
#include "btu.h"
#include "common/time_util.h"
#include "osi/include/wakelock.h"

using bluetooth::audio::le_audio::LeAudioClientInterface;
using ::le_audio::CodecManager;
using ::le_audio::types::CodecLocation;

namespace {
LeAudioClientInterface* leAudioClientInterface = nullptr;

enum {
  HAL_UNINITIALIZED,
  HAL_STOPPED,
  HAL_STARTED,
} le_audio_sink_hal_state,
    le_audio_source_hal_state;

struct AudioHalStats {
  size_t media_read_total_underflow_bytes;
  size_t media_read_total_underflow_count;
  uint64_t media_read_last_underflow_us;

  AudioHalStats() { Reset(); }

  void Reset() {
    media_read_total_underflow_bytes = 0;
    media_read_total_underflow_count = 0;
    media_read_last_underflow_us = 0;
  }
};

AudioHalStats stats;

bool le_audio_source_on_metadata_update_req(
    const sink_metadata_t& sink_metadata) {
  // TODO: update microphone configuration based on sink metadata
  return true;
}

}  // namespace

bool LeAudioClientAudioSource::SinkOnResumeReq(bool start_media_task) {
  std::lock_guard<std::mutex> guard(sinkInterfaceMutex_);
  if (audioSinkReceiver_ == nullptr) {
    LOG(ERROR) << __func__ << ": audioSinkReceiver is nullptr";
    return false;
  }
  bt_status_t status = do_in_main_thread(
      FROM_HERE, base::BindOnce(&LeAudioClientAudioSinkReceiver::OnAudioResume,
                                base::Unretained(audioSinkReceiver_)));
  if (status != BT_STATUS_SUCCESS) {
    LOG(ERROR) << __func__
               << ": LE_AUDIO_CTRL_CMD_START: do_in_main_thread err=" << status;
    return false;
  }

  return true;
}

void LeAudioClientAudioSource::SendAudioData() {
  // 24 bit audio is aligned to 32bit
  int bytes_per_sample = (source_codec_config_.bits_per_sample == 24)
                             ? 4
                             : (source_codec_config_.bits_per_sample / 8);

  uint32_t bytes_per_tick =
      (source_codec_config_.num_channels * source_codec_config_.sample_rate *
       source_codec_config_.data_interval_us / 1000 * bytes_per_sample) /
      1000;

  std::vector<uint8_t> data(bytes_per_tick);

  uint32_t bytes_read = 0;
  if (sinkClientInterface_ != nullptr) {
    bytes_read = sinkClientInterface_->Read(data.data(), bytes_per_tick);
  } else {
    LOG(ERROR) << __func__ << ", no LE Audio sink client interface - aborting.";
    return;
  }

  // LOG(INFO) << __func__ << ", bytes_read: " << static_cast<int>(bytes_read)
  //          << ", bytes_per_tick: " << static_cast<int>(bytes_per_tick);

  if (bytes_read < bytes_per_tick) {
    stats.media_read_total_underflow_bytes += bytes_per_tick - bytes_read;
    stats.media_read_total_underflow_count++;
    stats.media_read_last_underflow_us =
        bluetooth::common::time_get_os_boottime_us();
  }

  std::lock_guard<std::mutex> guard(sinkInterfaceMutex_);
  if (audioSinkReceiver_ != nullptr) {
    audioSinkReceiver_->OnAudioDataReady(data);
  }
}

bool LeAudioClientAudioSource::InitAudioSinkThread(const std::string name) {
  worker_thread_ = new bluetooth::common::MessageLoopThread(name);
  worker_thread_->StartUp();
  if (!worker_thread_->IsRunning()) {
    LOG(ERROR) << __func__ << ", unable to start up media thread";
    return false;
  }

  /* Schedule the rest of the operations */
  if (!worker_thread_->EnableRealTimeScheduling()) {
#if defined(OS_ANDROID)
    LOG(FATAL) << __func__ << ", Failed to increase media thread priority";
#endif
  }

  return true;
}

void LeAudioClientAudioSource::StartAudioTicks() {
  wakelock_acquire();
  audio_timer_.SchedulePeriodic(
      worker_thread_->GetWeakPtr(), FROM_HERE,
      base::Bind(&LeAudioClientAudioSource::SendAudioData,
                 base::Unretained(this)),
#if BASE_VER < 931007
      base::TimeDelta::FromMicroseconds(source_codec_config_.data_interval_us));
#else
      base::Microseconds(source_codec_config_.data_interval_us));
#endif
}

void LeAudioClientAudioSource::StopAudioTicks() {
  audio_timer_.CancelAndWait();
  wakelock_release();
}

bool LeAudioClientAudioSource::SinkOnSuspendReq() {
  std::lock_guard<std::mutex> guard(sinkInterfaceMutex_);
  if (CodecManager::GetInstance()->GetCodecLocation() == CodecLocation::HOST) {
    StopAudioTicks();
  }
  if (audioSinkReceiver_ != nullptr) {
    // Call OnAudioSuspend and block till it returns.
    std::promise<void> do_suspend_promise;
    std::future<void> do_suspend_future = do_suspend_promise.get_future();
    bt_status_t status = do_in_main_thread(
        FROM_HERE,
        base::BindOnce(&LeAudioClientAudioSinkReceiver::OnAudioSuspend,
                       base::Unretained(audioSinkReceiver_),
                       std::move(do_suspend_promise)));
    if (status == BT_STATUS_SUCCESS) {
      do_suspend_future.wait();
      return true;
    } else {
      LOG(ERROR) << __func__
                 << ": LE_AUDIO_CTRL_CMD_SUSPEND: do_in_main_thread err="
                 << status;
    }
  } else {
    LOG(ERROR) << __func__
               << ": LE_AUDIO_CTRL_CMD_SUSPEND: audio receiver not started";
  }
  return false;
}

bool LeAudioClientAudioSource::SinkOnMetadataUpdateReq(
    const source_metadata_t& source_metadata) {
  std::lock_guard<std::mutex> guard(sinkInterfaceMutex_);
  if (audioSinkReceiver_ == nullptr) {
    LOG(ERROR) << __func__ << ", audio receiver not started";
    return false;
  }

  // Call OnAudioSuspend and block till it returns.
  std::promise<void> do_update_metadata_promise;
  std::future<void> do_update_metadata_future =
      do_update_metadata_promise.get_future();
  bt_status_t status = do_in_main_thread(
      FROM_HERE,
      base::BindOnce(&LeAudioClientAudioSinkReceiver::OnAudioMetadataUpdate,
                     base::Unretained(audioSinkReceiver_),
                     std::move(do_update_metadata_promise), source_metadata));

  if (status == BT_STATUS_SUCCESS) {
    do_update_metadata_future.wait();
    return true;
  }

  LOG(ERROR) << __func__ << ", do_in_main_thread err=" << status;

  return false;
}

bool LeAudioUnicastClientAudioSink::SourceOnResumeReq(bool start_media_task) {
  if (audioSourceReceiver_ == nullptr) {
    LOG(ERROR) << __func__ << ": audioSourceReceiver is nullptr";
    return false;
  }

  bt_status_t status = do_in_main_thread(
      FROM_HERE,
      base::BindOnce(&LeAudioClientAudioSourceReceiver::OnAudioResume,
                     base::Unretained(audioSourceReceiver_)));
  if (status != BT_STATUS_SUCCESS) {
    LOG(ERROR) << __func__
               << ": LE_AUDIO_CTRL_CMD_START: do_in_main_thread err=" << status;
    return false;
  }

  return true;
}

bool LeAudioUnicastClientAudioSink::SourceOnSuspendReq() {
  if (audioSourceReceiver_ != nullptr) {
    // Call OnAudioSuspend and block till it returns.
    std::promise<void> do_suspend_promise;
    std::future<void> do_suspend_future = do_suspend_promise.get_future();
    bt_status_t status = do_in_main_thread(
        FROM_HERE,
        base::BindOnce(&LeAudioClientAudioSourceReceiver::OnAudioSuspend,
                       base::Unretained(audioSourceReceiver_),
                       std::move(do_suspend_promise)));
    if (status == BT_STATUS_SUCCESS) {
      do_suspend_future.wait();
      return true;
    } else {
      LOG(ERROR) << __func__
                 << ": LE_AUDIO_CTRL_CMD_SUSPEND: do_in_main_thread err="
                 << status;
    }
  } else {
    LOG(ERROR) << __func__
               << ": LE_AUDIO_CTRL_CMD_SUSPEND: audio receiver not started";
  }
  return false;
}

bool LeAudioUnicastClientAudioSink::SourceOnMetadataUpdateReq(
    const sink_metadata_t& sink_metadata) {
  if (audioSourceReceiver_ == nullptr) {
    LOG(ERROR) << __func__ << ", audio receiver not started";
    return false;
  }

  // Call OnAudioSuspend and block till it returns.
  std::promise<void> do_update_metadata_promise;
  std::future<void> do_update_metadata_future =
      do_update_metadata_promise.get_future();
  bt_status_t status = do_in_main_thread(
      FROM_HERE,
      base::BindOnce(&LeAudioClientAudioSourceReceiver::OnAudioMetadataUpdate,
                     base::Unretained(audioSourceReceiver_),
                     std::move(do_update_metadata_promise), sink_metadata));

  if (status == BT_STATUS_SUCCESS) {
    do_update_metadata_future.wait();
    return true;
  }

  LOG(ERROR) << __func__ << ", do_in_main_thread err=" << status;

  return false;
}

bool LeAudioClientAudioSource::Start(
    const LeAudioCodecConfiguration& codec_configuration,
    LeAudioClientAudioSinkReceiver* audioReceiver) {
  LOG(INFO) << __func__;

  if (!sinkClientInterface_) {
    LOG(ERROR) << "sinkClientInterface is not Acquired!";
    return false;
  }

  if (le_audio_sink_hal_state == HAL_STARTED) {
    LOG(ERROR) << "LE audio device HAL is already in use!";
    return false;
  }

  LOG(INFO) << __func__ << ": Le Audio Source Open, bits per sample: "
            << int{codec_configuration.bits_per_sample}
            << ", num channels: " << int{codec_configuration.num_channels}
            << ", sample rate: " << codec_configuration.sample_rate
            << ", data interval: " << codec_configuration.data_interval_us;

  stats.Reset();

  /* Global config for periodic audio data */
  source_codec_config_ = codec_configuration;
  LeAudioClientInterface::PcmParameters pcmParameters = {
      .data_interval_us = codec_configuration.data_interval_us,
      .sample_rate = codec_configuration.sample_rate,
      .bits_per_sample = codec_configuration.bits_per_sample,
      .channels_count = codec_configuration.num_channels};

  sinkClientInterface_->SetPcmParameters(pcmParameters);
  sinkClientInterface_->StartSession();

  std::lock_guard<std::mutex> guard(sinkInterfaceMutex_);
  audioSinkReceiver_ = audioReceiver;
  le_audio_sink_hal_state = HAL_STARTED;

  return true;
}

void LeAudioClientAudioSource::Stop() {
  LOG(INFO) << __func__;
  if (!sinkClientInterface_) {
    LOG(ERROR) << __func__ << " sinkClientInterface stopped";
    return;
  }

  if (le_audio_sink_hal_state != HAL_STARTED) {
    LOG(ERROR) << "LE audio device HAL was not started!";
    return;
  }

  LOG(INFO) << __func__ << ": Le Audio Source Close";

  sinkClientInterface_->StopSession();
  le_audio_sink_hal_state = HAL_STOPPED;

  if (CodecManager::GetInstance()->GetCodecLocation() == CodecLocation::HOST) {
    StopAudioTicks();
  }

  std::lock_guard<std::mutex> guard(sinkInterfaceMutex_);
  audioSinkReceiver_ = nullptr;
}

const void* LeAudioClientAudioSource::Acquire(
    bool is_broadcasting_session_type) {
  LOG(INFO) << __func__;

  /* Get pointer to singleton LE audio client interface */
  if (leAudioClientInterface == nullptr) {
    leAudioClientInterface = LeAudioClientInterface::Get();

    if (leAudioClientInterface == nullptr) {
      LOG(ERROR) << __func__ << ", can't get LE audio client interface";
      return nullptr;
    }
  }

  auto sink_stream_cb = bluetooth::audio::le_audio::StreamCallbacks{
      .on_resume_ = std::bind(&LeAudioClientAudioSource::SinkOnResumeReq, this,
                              std::placeholders::_1),
      .on_suspend_ =
          std::bind(&LeAudioClientAudioSource::SinkOnSuspendReq, this),
      .on_metadata_update_ =
          std::bind(&LeAudioClientAudioSource::SinkOnMetadataUpdateReq, this,
                    std::placeholders::_1),
      .on_sink_metadata_update_ = le_audio_source_on_metadata_update_req,
  };

  sinkClientInterface_ = leAudioClientInterface->GetSink(
      sink_stream_cb, get_main_thread(), is_broadcasting_session_type);

  if (sinkClientInterface_ == nullptr) {
    LOG(ERROR) << __func__ << ", can't get LE audio sink client interface";
    return nullptr;
  }

  le_audio_sink_hal_state = HAL_STOPPED;
  return sinkClientInterface_;
}

const void* LeAudioUnicastClientAudioSource::Acquire() {
  const void* sinkClientInterface = LeAudioClientAudioSource::Acquire(false);

  if (!sinkClientInterface) return nullptr;
  if (!InitAudioSinkThread("bt_le_audio_unicast_sink_worker_thread_"))
    return nullptr;

  return sinkClientInterface;
}

const void* LeAudioBroadcastClientAudioSource::Acquire() {
  const void* sinkClientInterface = LeAudioClientAudioSource::Acquire(true);

  if (!sinkClientInterface) return nullptr;
  if (!InitAudioSinkThread("bt_le_audio_sink_broadcast_worker_thread_"))
    return nullptr;

  return sinkClientInterface;
}

void LeAudioClientAudioSource::Release(const void* instance) {
  LOG(INFO) << __func__;
  if (sinkClientInterface_ != instance) {
    LOG(WARNING) << "Trying to release not own session";
    return;
  }

  if (le_audio_sink_hal_state == HAL_UNINITIALIZED) {
    LOG(WARNING) << "LE audio device HAL is not running.";
    return;
  }

  worker_thread_->ShutDown();
  sinkClientInterface_->Cleanup();
  leAudioClientInterface->ReleaseSink(sinkClientInterface_);
  le_audio_sink_hal_state = HAL_UNINITIALIZED;
  sinkClientInterface_ = nullptr;
}

void LeAudioClientAudioSource::ConfirmStreamingRequest() {
  LOG(INFO) << __func__;
  if ((sinkClientInterface_ == nullptr) ||
      (le_audio_sink_hal_state != HAL_STARTED)) {
    LOG(ERROR) << "LE audio device HAL was not started!";
    return;
  }

  sinkClientInterface_->ConfirmStreamingRequest();
  if (CodecManager::GetInstance()->GetCodecLocation() != CodecLocation::HOST)
    return;

  StartAudioTicks();
}

void LeAudioClientAudioSource::SuspendedForReconfiguration() {
  LOG(INFO) << __func__;
  if ((sinkClientInterface_ == nullptr) ||
      (le_audio_sink_hal_state != HAL_STARTED)) {
    LOG(ERROR) << "LE audio device HAL was not started!";
    return;
  }

  sinkClientInterface_->SuspendedForReconfiguration();
}

void LeAudioClientAudioSource::CancelStreamingRequest() {
  LOG(INFO) << __func__;
  if ((sinkClientInterface_ == nullptr) ||
      (le_audio_sink_hal_state != HAL_STARTED)) {
    LOG(ERROR) << "LE audio device HAL was not started!";
    return;
  }

  sinkClientInterface_->CancelStreamingRequest();
}

void LeAudioClientAudioSource::UpdateRemoteDelay(uint16_t remote_delay_ms) {
  LOG(INFO) << __func__;
  if ((sinkClientInterface_ == nullptr) ||
      (le_audio_sink_hal_state != HAL_STARTED)) {
    LOG(ERROR) << "LE audio device HAL was not started!";
    return;
  }

  sinkClientInterface_->SetRemoteDelay(remote_delay_ms);
}

void LeAudioClientAudioSource::DebugDump(int fd) {
  uint64_t now_us = bluetooth::common::time_get_os_boottime_us();
  std::stringstream stream;
  stream << "  Le Audio Audio HAL:"
         << "\n    Counts (underflow)                                      : "
         << stats.media_read_total_underflow_count
         << "\n    Bytes (underflow)                                       : "
         << stats.media_read_total_underflow_bytes
         << "\n    Last update time ago in ms (underflow)                  : "
         << (stats.media_read_last_underflow_us > 0
                 ? (unsigned long long)(now_us -
                                        stats.media_read_last_underflow_us) /
                       1000
                 : 0)
         << std::endl;
  dprintf(fd, "%s", stream.str().c_str());
}

void LeAudioClientAudioSource::UpdateAudioConfigToHal(
    const ::le_audio::offload_config& config) {
  LOG(INFO) << __func__;
  if ((sinkClientInterface_ == nullptr) ||
      (le_audio_sink_hal_state != HAL_STARTED)) {
    LOG(ERROR) << "LE audio device HAL was not started!";
    return;
  }

  sinkClientInterface_->UpdateAudioConfigToHal(config);
}

bool LeAudioUnicastClientAudioSink::Start(
    const LeAudioCodecConfiguration& codec_configuration,
    LeAudioClientAudioSourceReceiver* audioReceiver) {
  LOG(INFO) << __func__;
  if (!sourceClientInterface_) {
    LOG(ERROR) << "sourceClientInterface is not Acquired!";
    return false;
  }

  if (le_audio_source_hal_state == HAL_STARTED) {
    LOG(ERROR) << "LE audio device HAL is already in use!";
    return false;
  }

  LOG(INFO) << __func__ << ": Le Audio Sink Open, bit rate: "
            << int{codec_configuration.bits_per_sample}
            << ", num channels: " << int{codec_configuration.num_channels}
            << ", sample rate: " << codec_configuration.sample_rate
            << ", data interval: " << codec_configuration.data_interval_us;

  LeAudioClientInterface::PcmParameters pcmParameters = {
      .data_interval_us = codec_configuration.data_interval_us,
      .sample_rate = codec_configuration.sample_rate,
      .bits_per_sample = codec_configuration.bits_per_sample,
      .channels_count = codec_configuration.num_channels};

  sourceClientInterface_->SetPcmParameters(pcmParameters);
  sourceClientInterface_->StartSession();

  audioSourceReceiver_ = audioReceiver;
  le_audio_source_hal_state = HAL_STARTED;
  return true;
}

void LeAudioUnicastClientAudioSink::Stop() {
  LOG(INFO) << __func__;
  if (!sourceClientInterface_) {
    LOG(ERROR) << __func__ << " sourceClientInterface stopped";
    return;
  }

  if (le_audio_source_hal_state != HAL_STARTED) {
    LOG(ERROR) << "LE audio device HAL was not started!";
    return;
  }

  LOG(INFO) << __func__ << ": Le Audio Sink Close";

  sourceClientInterface_->StopSession();
  le_audio_source_hal_state = HAL_STOPPED;
  audioSourceReceiver_ = nullptr;
}

const void* LeAudioUnicastClientAudioSink::Acquire() {
  LOG(INFO) << __func__;
  if (sourceClientInterface_ != nullptr) {
    LOG(WARNING) << __func__ << ", Source client interface already initialized";
    return nullptr;
  }

  /* Get pointer to singleton LE audio client interface */
  if (leAudioClientInterface == nullptr) {
    leAudioClientInterface = LeAudioClientInterface::Get();

    if (leAudioClientInterface == nullptr) {
      LOG(ERROR) << __func__ << ", can't get LE audio client interface";
      return nullptr;
    }
  }

  auto source_stream_cb = bluetooth::audio::le_audio::StreamCallbacks{
      .on_resume_ = std::bind(&LeAudioUnicastClientAudioSink::SourceOnResumeReq,
                              this, std::placeholders::_1),
      .on_suspend_ =
          std::bind(&LeAudioUnicastClientAudioSink::SourceOnSuspendReq, this),
      .on_sink_metadata_update_ =
          std::bind(&LeAudioUnicastClientAudioSink::SourceOnMetadataUpdateReq,
                    this, std::placeholders::_1),
  };

  sourceClientInterface_ =
      leAudioClientInterface->GetSource(source_stream_cb, get_main_thread());

  if (sourceClientInterface_ == nullptr) {
    LOG(ERROR) << __func__ << ", can't get LE audio source client interface";
    return nullptr;
  }

  le_audio_source_hal_state = HAL_STOPPED;
  return sourceClientInterface_;
}

void LeAudioUnicastClientAudioSink::Release(const void* instance) {
  LOG(INFO) << __func__;
  if (sourceClientInterface_ != instance) {
    LOG(WARNING) << "Trying to release not own session";
    return;
  }

  if (le_audio_source_hal_state == HAL_UNINITIALIZED) {
    LOG(WARNING) << ", LE audio device source HAL is not running.";
    return;
  }

  sourceClientInterface_->Cleanup();
  leAudioClientInterface->ReleaseSource(sourceClientInterface_);
  le_audio_source_hal_state = HAL_UNINITIALIZED;
  sourceClientInterface_ = nullptr;
}

size_t LeAudioUnicastClientAudioSink::SendData(uint8_t* data, uint16_t size) {
  size_t bytes_written;
  if (!sourceClientInterface_) {
    LOG(ERROR) << "sourceClientInterface not initialized!";
    return 0;
  }

  if (le_audio_source_hal_state != HAL_STARTED) {
    LOG(ERROR) << "LE audio device HAL was not started!";
    return 0;
  }

  /* TODO: What to do if not all data is written ? */
  bytes_written = sourceClientInterface_->Write(data, size);
  if (bytes_written != size)
    LOG(ERROR) << ", Not all data is written to source HAL. bytes written: "
               << static_cast<int>(bytes_written)
               << ", total: " << static_cast<int>(size);

  return bytes_written;
}

void LeAudioUnicastClientAudioSink::ConfirmStreamingRequest() {
  LOG(INFO) << __func__;
  if ((sourceClientInterface_ == nullptr) ||
      (le_audio_source_hal_state != HAL_STARTED)) {
    LOG(ERROR) << "LE audio device HAL was not started!";
    return;
  }

  sourceClientInterface_->ConfirmStreamingRequest();
}

void LeAudioUnicastClientAudioSink::CancelStreamingRequest() {
  LOG(INFO) << __func__;
  if ((sourceClientInterface_ == nullptr) ||
      (le_audio_source_hal_state != HAL_STARTED)) {
    LOG(ERROR) << "LE audio device HAL was not started!";
    return;
  }

  sourceClientInterface_->CancelStreamingRequest();
}

void LeAudioUnicastClientAudioSink::UpdateRemoteDelay(
    uint16_t remote_delay_ms) {
  if ((sourceClientInterface_ == nullptr) ||
      (le_audio_source_hal_state != HAL_STARTED)) {
    LOG(ERROR) << "LE audio device HAL was not started!";
    return;
  }

  sourceClientInterface_->SetRemoteDelay(remote_delay_ms);
}

void LeAudioUnicastClientAudioSink::DebugDump(int fd) {
  /* TODO: Add some statistic for source client interface */
}

void LeAudioUnicastClientAudioSink::UpdateAudioConfigToHal(
    const ::le_audio::offload_config& config) {
  LOG(INFO) << __func__;
  if ((sourceClientInterface_ == nullptr) ||
      (le_audio_source_hal_state != HAL_STARTED)) {
    LOG(ERROR) << "LE audio device HAL was not started!";
    return;
  }

  sourceClientInterface_->UpdateAudioConfigToHal(config);
}

void LeAudioUnicastClientAudioSink::SuspendedForReconfiguration() {
  LOG(INFO) << __func__;
  if ((sourceClientInterface_ == nullptr) ||
      (le_audio_source_hal_state != HAL_STARTED)) {
    LOG(ERROR) << "LE audio device HAL was not started!";
    return;
  }

  sourceClientInterface_->SuspendedForReconfiguration();
}
