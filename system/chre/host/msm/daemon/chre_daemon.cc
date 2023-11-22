/*
 * Copyright (C) 2017 The Android Open Source Project
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

/**
 * @file
 * The daemon that hosts CHRE on the SLPI via FastRPC.
 *
 * Several threads are required for this functionality:
 *   - Main thread: blocked waiting on SIGINT/SIGTERM, and requests graceful
 *     shutdown of CHRE when caught
 *   - Monitor thread: persistently blocked in a FastRPC call to the SLPI that
 *     only returns when CHRE exits or the SLPI crashes
 *     - TODO: see whether we can merge this with the RX thread
 *   - Reverse monitor thread: after initializing the SLPI-side monitor for this
 *     process, blocks on a condition variable. If this thread exits, CHRE on
 *     the SLPI side will be notified and shut down (this is only possible if
 *     this thread is not blocked in a FastRPC call).
 *     - TODO: confirm this and see whether we can merge this responsibility
 *       into the TX thread
 *   - Message to host (RX) thread: blocks in FastRPC call, waiting on incoming
 *     message from CHRE
 *   - Message to CHRE (TX) thread: blocks waiting on outbound queue, delivers
 *     messages to CHRE over FastRPC
 *
 * TODO: This file originated from an implementation for another device, and was
 * written in C, but then it was converted to C++ when adding socket support. It
 * should be fully converted to C++.
 */

// Disable verbose logging
// TODO: use property_get_bool to make verbose logging runtime configurable
// #define LOG_NDEBUG 0

#include <ctype.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "chre/platform/slpi/fastrpc.h"
#include "chre_host/log.h"
#include "chre_host/host_protocol_host.h"
#include "chre_host/socket_server.h"
#include "generated/chre_slpi.h"

#include <utils/SystemClock.h>

//! The format string to use for logs from the CHRE implementation.
#define HUB_LOG_FORMAT_STR "Hub (t=%.6f): %s"

using android::chre::HostProtocolHost;
using android::elapsedRealtimeNano;

// Aliased for consistency with the way these symbols are referenced in
// CHRE-side code
namespace fbs = ::chre::fbs;

typedef void *(thread_entry_point_f)(void *);

struct reverse_monitor_thread_data {
  pthread_t       thread;
  pthread_mutex_t mutex;
  pthread_cond_t  cond;
};

static void *chre_message_to_host_thread(void *arg);
static void *chre_monitor_thread(void *arg);
static void *chre_reverse_monitor_thread(void *arg);
static bool init_reverse_monitor(struct reverse_monitor_thread_data *data);
static bool start_thread(pthread_t *thread_handle,
                         thread_entry_point_f *thread_entry,
                         void *arg);

//! Set to true when we request a graceful shutdown of CHRE
static volatile bool chre_shutdown_requested = false;

#if !defined(LOG_NDEBUG) || LOG_NDEBUG != 0
static void log_buffer(const uint8_t * /*buffer*/, size_t /*size*/) {}
#else
static void log_buffer(const uint8_t *buffer, size_t size) {
  char line[32];
  int offset = 0;
  char line_chars[32];
  int offset_chars = 0;

  size_t orig_size = size;
  if (size > 128) {
    size = 128;
    LOGV("Dumping first 128 bytes of buffer of size %zu", orig_size);
  } else {
    LOGV("Dumping buffer of size %zu bytes", size);
  }
  for (size_t i = 1; i <= size; ++i) {
    offset += snprintf(&line[offset], sizeof(line) - offset, "%02x ",
                       buffer[i - 1]);
    offset_chars += snprintf(
        &line_chars[offset_chars], sizeof(line_chars) - offset_chars,
        "%c", (isprint(buffer[i - 1])) ? buffer[i - 1] : '.');
    if ((i % 8) == 0) {
      LOGV("  %s\t%s", line, line_chars);
      offset = 0;
      offset_chars = 0;
    } else if ((i % 4) == 0) {
      offset += snprintf(&line[offset], sizeof(line) - offset, " ");
    }
  }

  if (offset > 0) {
    char tabs[8];
    char *pos = tabs;
    while (offset < 28) {
      *pos++ = '\t';
      offset += 8;
    }
    *pos = '\0';
    LOGV("  %s%s%s", line, tabs, line_chars);
  }
}
#endif

static void parseAndEmitLogMessages(unsigned char *message) {
  const fbs::MessageContainer *container = fbs::GetMessageContainer(message);
  const auto *logMessage = static_cast<const fbs::LogMessage *>(
      container->message());

  constexpr size_t kLogMessageHeaderSize = 2 + sizeof(uint64_t);
  const flatbuffers::Vector<int8_t>& logData = *logMessage->buffer();
  for (size_t i = 0; i <= (logData.size() - kLogMessageHeaderSize);) {
    // Parse out the log level.
    const char *log = reinterpret_cast<const char *>(&logData.data()[i]);
    char logLevel = *log;
    log++;

    // Parse out the timestampNanos.
    uint64_t timestampNanos;
    memcpy(&timestampNanos, log, sizeof(uint64_t));
    timestampNanos = le64toh(timestampNanos);
    log += sizeof(uint64_t);

    float timestampSeconds = timestampNanos / 1e9;

    // Log the message.
    switch (logLevel) {
      case 1:
        LOGE(HUB_LOG_FORMAT_STR, timestampSeconds, log);
        break;
      case 2:
        LOGW(HUB_LOG_FORMAT_STR, timestampSeconds, log);
        break;
      case 3:
        LOGI(HUB_LOG_FORMAT_STR, timestampSeconds, log);
        break;
      case 4:
        LOGD(HUB_LOG_FORMAT_STR, timestampSeconds, log);
        break;
      default:
        LOGE("Invalid CHRE hub log level, omitting log");
    }

    // Advance the log pointer.
    size_t strLen = strlen(log);
    i += kLogMessageHeaderSize + strLen;
  }
}

static int64_t getTimeOffset(bool *success) {
  int64_t timeOffset = 0;

#if defined(__aarch64__)
  // Reads the system time counter (CNTPCT) and its frequency (CNTFRQ)
  // CNTPCT is used in the SLPI uTimetick API to compute the CHRE time
  // More information can be found in the ARM reference manual
  // (http://infocenter.arm.com/help/index.jsp?topic=
  // /com.arm.doc.100048_0002_05_en/jfa1406793266982.html)
  // Use uint64_t to store since the MRS instruction uses 64 bit (X) registers
  // (http://infocenter.arm.com/help/topic/
  // com.arm.doc.den0024a/ch06s05s02.html)
  uint64_t qTimerCount = 0, qTimerFreq = 0;
  uint64_t hostTimeNano = elapsedRealtimeNano();
  asm volatile("mrs %0, cntpct_el0" : "=r"(qTimerCount));
  asm volatile("mrs %0, cntfrq_el0" : "=r"(qTimerFreq));

  constexpr uint64_t kOneSecondInNanoseconds = 1000000000;
  if (qTimerFreq != 0) {
    // Get the seconds part first, then convert the remainder to prevent
    // overflow
    uint64_t qTimerNanos = (qTimerCount / qTimerFreq);
    if (qTimerNanos > UINT64_MAX / kOneSecondInNanoseconds) {
      LOGE("CNTPCT_EL0 conversion to nanoseconds overflowed during time sync."
           " Aborting time sync.");
      *success = false;
    } else {
      qTimerNanos *= kOneSecondInNanoseconds;

      // Round the remainder portion to the nearest nanosecond
      uint64_t remainder = (qTimerCount % qTimerFreq);
      qTimerNanos +=
          (remainder * kOneSecondInNanoseconds + qTimerFreq / 2) / qTimerFreq;

      timeOffset = hostTimeNano - qTimerNanos;
      *success = true;
    }
  } else {
    LOGE("CNTFRQ_EL0 had 0 value. Aborting time sync.");
    *success = false;
  }
#else
#error "Unsupported CPU architecture type"
#endif

  return timeOffset;
}

static void sendTimeSyncMessage() {
  bool timeSyncSuccess = true;
  int64_t timeOffset = getTimeOffset(&timeSyncSuccess);

  if (timeSyncSuccess) {
    flatbuffers::FlatBufferBuilder builder(64);
    HostProtocolHost::encodeTimeSyncMessage(builder, timeOffset);
    int success = chre_slpi_deliver_message_from_host(
        static_cast<const unsigned char *>(builder.GetBufferPointer()),
        static_cast<int>(builder.GetSize()));

    if (success != 0) {
      LOGE("Failed to deliver timestamp message from host to CHRE: %d", success);
    }
  }
}

/**
 * Entry point for the thread that receives messages sent by CHRE.
 *
 * @return always returns NULL
 */
static void *chre_message_to_host_thread(void *arg) {
  unsigned char messageBuffer[4096];
  unsigned int messageLen;
  int result = 0;
  auto *server = static_cast<::android::chre::SocketServer *>(arg);

  while (true) {
    messageLen = 0;
    LOGV("Calling into chre_slpi_get_message_to_host");
    result = chre_slpi_get_message_to_host(
        messageBuffer, sizeof(messageBuffer), &messageLen);
    LOGV("Got message from CHRE with size %u (result %d)", messageLen, result);

    if (result == CHRE_FASTRPC_ERROR_SHUTTING_DOWN) {
      LOGD("CHRE shutting down, exiting CHRE->Host message thread");
      break;
    } else if (result == CHRE_FASTRPC_SUCCESS && messageLen > 0) {
      log_buffer(messageBuffer, messageLen);
      uint16_t hostClientId;
      fbs::ChreMessage messageType;
      if (!HostProtocolHost::extractHostClientIdAndType(
          messageBuffer, messageLen, &hostClientId, &messageType)) {
        LOGW("Failed to extract host client ID from message - sending "
             "broadcast");
        hostClientId = chre::kHostClientIdUnspecified;
      }

      if (messageType == fbs::ChreMessage::LogMessage) {
        parseAndEmitLogMessages(messageBuffer);
      } else if (messageType == fbs::ChreMessage::TimeSyncRequest) {
        sendTimeSyncMessage();
      } else if (hostClientId == chre::kHostClientIdUnspecified) {
        server->sendToAllClients(messageBuffer,
                                 static_cast<size_t>(messageLen));
      } else {
        server->sendToClientById(messageBuffer,
                                 static_cast<size_t>(messageLen), hostClientId);
      }
    } else if (!chre_shutdown_requested) {
      LOGE("Received an unknown result and no shutdown was requested. Quitting");
      exit(-1);
    } else {
      // Received an unknown result but a shutdown was requested. Break from the
      // loop to allow the daemon to cleanup.
      break;
    }
  }

  LOGV("Message to host thread exited");
  return NULL;
}

/**
 * Entry point for the thread that blocks in a FastRPC call to monitor for
 * abnormal exit of CHRE or reboot of the SLPI.
 *
 * @return always returns NULL
 */
static void *chre_monitor_thread(void *arg) {
  (void) arg;
  int ret = chre_slpi_wait_on_thread_exit();
  if (!chre_shutdown_requested) {
    LOGE("Detected unexpected CHRE thread exit (%d)\n", ret);
    exit(EXIT_FAILURE);
  }

  LOGV("Monitor thread exited");
  return NULL;
}

/**
 * Entry point for the "reverse" monitor thread, which invokes a FastRPC method
 * to register a thread destructor, and blocks waiting on a condition variable.
 * This allows for the code running in the SLPI to detect abnormal shutdown of
 * the host-side binary and perform graceful cleanup.
 *
 * @return always returns NULL
 */
static void *chre_reverse_monitor_thread(void *arg) {
  struct reverse_monitor_thread_data *thread_data =
      (struct reverse_monitor_thread_data *) arg;

  int ret = chre_slpi_initialize_reverse_monitor();
  if (ret != CHRE_FASTRPC_SUCCESS) {
    LOGE("Failed to initialize reverse monitor on SLPI: %d", ret);
  } else {
    // Block here on the condition variable until the main thread notifies
    // us to exit
    pthread_mutex_lock(&thread_data->mutex);
    pthread_cond_wait(&thread_data->cond, &thread_data->mutex);
    pthread_mutex_unlock(&thread_data->mutex);
  }

  LOGV("Reverse monitor thread exited");
  return NULL;
}

/**
 * Initializes the data shared with the reverse monitor thread, and starts the
 * thread.
 *
 * @param data Pointer to structure containing the (uninitialized) condition
 *        variable and associated data passed to the reverse monitor thread
 *
 * @return true on success
 */
static bool init_reverse_monitor(struct reverse_monitor_thread_data *data) {
  bool success = false;
  int ret;

  if ((ret = pthread_mutex_init(&data->mutex, NULL)) != 0) {
    LOG_ERROR("Failed to initialize mutex", ret);
  } else if ((ret = pthread_cond_init(&data->cond, NULL)) != 0) {
    LOG_ERROR("Failed to initialize condition variable", ret);
  } else if (!start_thread(&data->thread, chre_reverse_monitor_thread, data)) {
    LOGE("Couldn't start reverse monitor thread");
  } else {
    success = true;
  }

  return success;
}

/**
 * Start a thread with default attributes, or log an error on failure
 *
 * @return bool true if the thread was successfully started
 */
static bool start_thread(pthread_t *thread_handle,
                         thread_entry_point_f *thread_entry,
                         void *arg) {
  int ret = pthread_create(thread_handle, NULL, thread_entry, arg);
  if (ret != 0) {
    LOG_ERROR("pthread_create failed", ret);
  }
  return (ret == 0);
}

namespace {

void onMessageReceivedFromClient(uint16_t clientId, void *data, size_t length) {
  constexpr size_t kMaxPayloadSize = 1024 * 1024;  // 1 MiB

  // This limitation is due to FastRPC, but there's no case where we should come
  // close to this limit...
  static_assert(kMaxPayloadSize <= INT32_MAX,
                "SLPI uses 32-bit signed integers to represent message size");

  if (length > kMaxPayloadSize) {
    LOGE("Message too large to pass to SLPI (got %zu, max %zu bytes)", length,
         kMaxPayloadSize);
  } else if (!HostProtocolHost::mutateHostClientId(data, length, clientId)) {
    LOGE("Couldn't set host client ID in message container!");
  } else {
    LOGV("Delivering message from host (size %zu)", length);
    log_buffer(static_cast<const uint8_t *>(data), length);
    int ret = chre_slpi_deliver_message_from_host(
        static_cast<const unsigned char *>(data), static_cast<int>(length));
    if (ret != 0) {
      LOGE("Failed to deliver message from host to CHRE: %d", ret);
    }
  }
}

}  // anonymous namespace

int main() {
  int ret = -1;
  pthread_t monitor_thread;
  pthread_t msg_to_host_thread;
  struct reverse_monitor_thread_data reverse_monitor;
  ::android::chre::SocketServer server;

  if (!init_reverse_monitor(&reverse_monitor)) {
    LOGE("Couldn't initialize reverse monitor");
  } else {
    // Send time offset message before nanoapps start
    sendTimeSyncMessage();
    if ((ret = chre_slpi_start_thread()) != CHRE_FASTRPC_SUCCESS) {
      LOGE("Failed to start CHRE on SLPI: %d", ret);
    } else {
      if (!start_thread(&monitor_thread, chre_monitor_thread, NULL)) {
        LOGE("Couldn't start monitor thread");
      } else if (!start_thread(&msg_to_host_thread, chre_message_to_host_thread,
                               &server)) {
        LOGE("Couldn't start CHRE->Host message thread");
      } else {
        LOGI("CHRE on SLPI started");
        // TODO: take 2nd argument as command-line parameter
        server.run("chre", true, onMessageReceivedFromClient);
      }

      chre_shutdown_requested = true;
      ret = chre_slpi_stop_thread();
      if (ret != CHRE_FASTRPC_SUCCESS) {
        LOGE("Failed to stop CHRE on SLPI: %d", ret);
      } else {
        // TODO: don't call pthread_join if the thread failed to start
        LOGV("Joining monitor thread");
        ret = pthread_join(monitor_thread, NULL);
        if (ret != 0) {
          LOG_ERROR("Join on monitor thread failed", ret);
        }

        LOGV("Joining reverse monitor thread");
        pthread_cond_signal(&reverse_monitor.cond);
        ret = pthread_join(reverse_monitor.thread, NULL);
        if (ret != 0) {
          LOG_ERROR("Join on reverse monitor thread failed", ret);
        }

        LOGV("Joining message to host thread");
        ret = pthread_join(msg_to_host_thread, NULL);
        if (ret != 0) {
          LOG_ERROR("Join on monitor thread failed", ret);
        }

        LOGI("Shutdown complete");
      }
    }
  }

  return ret;
}

