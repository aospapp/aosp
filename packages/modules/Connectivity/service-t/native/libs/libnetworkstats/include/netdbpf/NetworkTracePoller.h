/**
 * Copyright (c) 2023, The Android Open Source Project
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

#pragma once

#include <perfetto/base/task_runner.h>
#include <perfetto/tracing.h>

#include <string>
#include <unordered_map>

#include "android-base/thread_annotations.h"
#include "bpf/BpfMap.h"
#include "bpf/BpfRingbuf.h"

// For PacketTrace struct definition
#include "netd.h"

namespace android {
namespace bpf {
namespace internal {

// NetworkTracePoller is responsible for interactions with the BPF ring buffer
// including polling. This class is an internal helper for NetworkTraceHandler,
// it is not meant to be used elsewhere.
class NetworkTracePoller {
 public:
  using EventSink = std::function<void(const std::vector<PacketTrace>&)>;

  // Testonly: initialize with a callback capable of intercepting data.
  NetworkTracePoller(EventSink callback) : mCallback(std::move(callback)) {}

  // Starts tracing with the given poll interval.
  bool Start(uint32_t pollMs) EXCLUDES(mMutex);

  // Stops tracing and release any held state.
  bool Stop() EXCLUDES(mMutex);

  // Consumes all available events from the ringbuffer.
  bool ConsumeAll() EXCLUDES(mMutex);

 private:
  void SchedulePolling() REQUIRES(mMutex);
  bool ConsumeAllLocked() REQUIRES(mMutex);

  std::mutex mMutex;

  // Records the number of successfully started active sessions so that only the
  // first active session attempts setup and only the last cleans up. Note that
  // the session count will remain zero if Start fails. It is expected that Stop
  // will not be called for any trace session where Start fails.
  int mSessionCount GUARDED_BY(mMutex);

  // How often to poll the ring buffer, defined by the trace config.
  uint32_t mPollMs GUARDED_BY(mMutex);

  // The function to process PacketTrace, typically a Perfetto sink.
  EventSink mCallback GUARDED_BY(mMutex);

  // The BPF ring buffer handle.
  std::unique_ptr<BpfRingbuf<PacketTrace>> mRingBuffer GUARDED_BY(mMutex);

  // The packet tracing config map (really a 1-element array).
  BpfMap<uint32_t, bool> mConfigurationMap GUARDED_BY(mMutex);

  // This must be the last member, causing it to be the first deleted. If it is
  // not, members required for callbacks can be deleted before it's stopped.
  std::unique_ptr<perfetto::base::TaskRunner> mTaskRunner GUARDED_BY(mMutex);
};

}  // namespace internal
}  // namespace bpf
}  // namespace android
