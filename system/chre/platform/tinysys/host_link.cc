/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include "FreeRTOS.h"
#include "task.h"

#include "chre/core/event_loop_manager.h"
#include "chre/core/host_comms_manager.h"
#include "chre/platform/host_link.h"
#include "chre/platform/log.h"
#include "chre/platform/shared/host_protocol_chre.h"
#include "chre/platform/shared/log_buffer_manager.h"
#include "chre/platform/shared/nanoapp_load_manager.h"
#include "chre/platform/system_time.h"
#include "chre/platform/system_timer.h"
#include "chre/util/flatbuffers/helpers.h"
#include "chre/util/nested_data_ptr.h"

// TODO(b/265046038): MTK headers are not c++ friendly,
// should be modified to wrap function proto with "extern C"
#include "dma_api.h"
#include "ipi.h"
#include "ipi_id.h"
#include "scp_dram_region.h"

// Because the LOGx macros are being redirected to logcat through
// HostLink::sendLogMessageV2 and HostLink::send, calling them from
// inside HostLink impl could result in endless recursion.
// So redefine them to just printf function to SCP console.
#if CHRE_MINIMUM_LOG_LEVEL >= CHRE_LOG_LEVEL_ERROR
#undef LOGE
#define LOGE(fmt, arg...) PRINTF_E("[CHRE]" fmt "\n", ##arg)
#endif

#if CHRE_MINIMUM_LOG_LEVEL >= CHRE_LOG_LEVEL_WARN
#undef LOGW
#define LOGW(fmt, arg...) PRINTF_W("[CHRE]" fmt "\n", ##arg)
#endif

#if CHRE_MINIMUM_LOG_LEVEL >= CHRE_LOG_LEVEL_INFO
#undef LOGI
#define LOGI(fmt, arg...) PRINTF_I("[CHRE]" fmt "\n", ##arg)
#endif

#if CHRE_MINIMUM_LOG_LEVEL >= CHRE_LOG_LEVEL_DEBUG
#undef LOGD
#define LOGD(fmt, arg...) PRINTF_D("[CHRE]" fmt "\n", ##arg)
#endif

#if CHRE_MINIMUM_LOG_LEVEL >= CHRE_LOG_LEVEL_VERBOSE
#undef LOGV
#define LOGV(fmt, arg...) PRINTF_D("[CHRE]" fmt "\n", ##arg)
#endif

namespace chre {
namespace {

struct UnloadNanoappCallbackData {
  uint64_t appId;
  uint32_t transactionId;
  uint16_t hostClientId;
  bool allowSystemNanoappUnload;
};

uint32_t gChreIpiRecvData[2];
uint32_t gChreIpiAckToHost[2];  // SCP reply ack data (AP to SCP)
int gChreIpiAckFromHost[2];     // SCP get ack data from AP (SCP to AP)

void *gChreSubregionRecvAddr;
size_t gChreSubregionRecvSize;
void *gChreSubregionSendAddr;
size_t gChreSubregionSendSize;

// TODO(b/263958729): move it to HostLinkBase, and revisit buffer size
// payload buffers
#define CHRE_IPI_RECV_BUFFER_SIZE (CHRE_MESSAGE_TO_HOST_MAX_SIZE + 128)
uint32_t gChreRecvBuffer[CHRE_IPI_RECV_BUFFER_SIZE / sizeof(uint32_t)]
    __attribute__((aligned(CACHE_LINE_SIZE)));

#ifdef SCP_CHRE_USE_DMA
static inline uint32_t align(uint32_t target, uint32_t size) {
  return (target + size - 1) & ~(size - 1);
}
#endif

#define SCP_CHRE_MAGIC 0x67728269
struct ScpChreIpiMsg {
  uint32_t magic;
  uint32_t size;
};

struct NanoappListData {
  ChreFlatBufferBuilder *builder;
  DynamicVector<NanoappListEntryOffset> nanoappEntries;
  uint16_t hostClientId;
};

enum class PendingMessageType {
  Shutdown,
  NanoappMessageToHost,
  HubInfoResponse,
  NanoappListResponse,
  LoadNanoappResponse,
  UnloadNanoappResponse,
  DebugDumpData,
  DebugDumpResponse,
  TimeSyncRequest,
  LowPowerMicAccessRequest,
  LowPowerMicAccessRelease,
  EncodedLogMessage,
  SelfTestResponse,
  MetricLog,
  NanConfigurationRequest,
};

struct PendingMessage {
  PendingMessage(PendingMessageType msgType, uint16_t hostClientId) {
    type = msgType;
    data.hostClientId = hostClientId;
  }

  PendingMessage(PendingMessageType msgType,
                 const HostMessage *msgToHost = nullptr) {
    type = msgType;
    data.msgToHost = msgToHost;
  }

  PendingMessage(PendingMessageType msgType, ChreFlatBufferBuilder *builder) {
    type = msgType;
    data.builder = builder;
  }

  PendingMessageType type;
  union {
    const HostMessage *msgToHost;
    uint16_t hostClientId;
    ChreFlatBufferBuilder *builder;
  } data;
};

constexpr size_t kOutboundQueueSize = 100;
FixedSizeBlockingQueue<PendingMessage, kOutboundQueueSize> gOutboundQueue;

typedef void(MessageBuilderFunction)(ChreFlatBufferBuilder &builder,
                                     void *cookie);

inline HostCommsManager &getHostCommsManager() {
  return EventLoopManagerSingleton::get()->getHostCommsManager();
}

bool generateMessageFromBuilder(ChreFlatBufferBuilder *builder) {
  CHRE_ASSERT(builder != nullptr);
  LOGV("%s: message size %d", __func__, builder->GetSize());
  bool result =
      HostLinkBase::send(builder->GetBufferPointer(), builder->GetSize());

  // clean up
  builder->~ChreFlatBufferBuilder();
  memoryFree(builder);
  return result;
}

bool generateMessageToHost(const HostMessage *message) {
  LOGV("%s: message size %zu", __func__, message->message.size());
  // TODO(b/263958729): ideally we'd construct our flatbuffer directly in the
  // host-supplied buffer
  constexpr size_t kFixedReserveSize = 80;
  ChreFlatBufferBuilder builder(message->message.size() + kFixedReserveSize);
  HostProtocolChre::encodeNanoappMessage(
      builder, message->appId, message->toHostData.messageType,
      message->toHostData.hostEndpoint, message->message.data(),
      message->message.size(), message->toHostData.appPermissions,
      message->toHostData.messagePermissions, message->toHostData.wokeHost);
  bool result =
      HostLinkBase::send(builder.GetBufferPointer(), builder.GetSize());

  // clean up
  getHostCommsManager().onMessageToHostComplete(message);
  return result;
}

int generateHubInfoResponse(uint16_t hostClientId) {
  constexpr size_t kInitialBufferSize = 192;

  constexpr char kHubName[] = "CHRE on Tinysys";
  constexpr char kVendor[] = "Google";
  constexpr char kToolchain[] =
      "Clang " STRINGIFY(__clang_major__) "." STRINGIFY(
          __clang_minor__) "." STRINGIFY(__clang_patchlevel__);
  constexpr uint32_t kLegacyPlatformVersion = 0;
  constexpr uint32_t kLegacyToolchainVersion =
      ((__clang_major__ & 0xFF) << 24) | ((__clang_minor__ & 0xFF) << 16) |
      (__clang_patchlevel__ & 0xFFFF);
  constexpr float kPeakMips = 350;
  constexpr float kStoppedPower = 0;
  constexpr float kSleepPower = 1;
  constexpr float kPeakPower = 15;

  // Note that this may execute prior to EventLoopManager::lateInit() completing
  ChreFlatBufferBuilder builder(kInitialBufferSize);
  HostProtocolChre::encodeHubInfoResponse(
      builder, kHubName, kVendor, kToolchain, kLegacyPlatformVersion,
      kLegacyToolchainVersion, kPeakMips, kStoppedPower, kSleepPower,
      kPeakPower, CHRE_MESSAGE_TO_HOST_MAX_SIZE, chreGetPlatformId(),
      chreGetVersion(), hostClientId);

  return HostLinkBase::send(builder.GetBufferPointer(), builder.GetSize());
}

bool dequeueMessage(PendingMessage pendingMsg) {
  LOGV("%s: message type %d", __func__, pendingMsg.type);
  bool result = false;
  switch (pendingMsg.type) {
    case PendingMessageType::NanoappMessageToHost:
      result = generateMessageToHost(pendingMsg.data.msgToHost);
      break;

    case PendingMessageType::HubInfoResponse:
      result = generateHubInfoResponse(pendingMsg.data.hostClientId);
      break;

    case PendingMessageType::NanoappListResponse:
    case PendingMessageType::LoadNanoappResponse:
    case PendingMessageType::UnloadNanoappResponse:
    case PendingMessageType::DebugDumpData:
    case PendingMessageType::DebugDumpResponse:
    case PendingMessageType::TimeSyncRequest:
    case PendingMessageType::LowPowerMicAccessRequest:
    case PendingMessageType::LowPowerMicAccessRelease:
    case PendingMessageType::EncodedLogMessage:
    case PendingMessageType::SelfTestResponse:
    case PendingMessageType::MetricLog:
    case PendingMessageType::NanConfigurationRequest:
      result = generateMessageFromBuilder(pendingMsg.data.builder);
      break;

    default:
      CHRE_ASSERT_LOG(false, "Unexpected pending message type");
  }
  return result;
}

/**
 * Wrapper function to enqueue a message on the outbound message queue. All
 * outgoing message to the host must be called through this function.
 *
 * @param message The message to send to host.
 *
 * @return true if the message was successfully added to the queue.
 */
bool enqueueMessage(PendingMessage pendingMsg) {
  return gOutboundQueue.push(pendingMsg);
}

/**
 * Helper function that takes care of the boilerplate for allocating a
 * ChreFlatBufferBuilder on the heap and adding it to the outbound message
 * queue.
 *
 * @param msgType Identifies the message while in the outbound queue
 * @param initialBufferSize Number of bytes to reserve when first allocating the
 *        ChreFlatBufferBuilder
 * @param buildMsgFunc Synchronous callback used to encode the FlatBuffer
 *        message. Will not be invoked if allocation fails.
 * @param cookie Opaque pointer that will be passed through to buildMsgFunc
 *
 * @return true if the message was successfully added to the queue
 */
bool buildAndEnqueueMessage(PendingMessageType msgType,
                            size_t initialBufferSize,
                            MessageBuilderFunction *msgBuilder, void *cookie) {
  LOGV("%s: message type %d, size %zu", __func__, msgType, initialBufferSize);
  bool pushed = false;

  auto builder = MakeUnique<ChreFlatBufferBuilder>(initialBufferSize);
  if (builder.isNull()) {
    LOGE("Couldn't allocate memory for message type %d",
         static_cast<int>(msgType));
  } else {
    msgBuilder(*builder, cookie);

    // TODO(b/263958729): if this fails, ideally we should block for some
    // timeout until there's space in the queue
    if (!enqueueMessage(PendingMessage(msgType, builder.get()))) {
      LOGE("Couldn't push message type %d to outbound queue",
           static_cast<int>(msgType));
    } else {
      builder.release();
      pushed = true;
    }
  }

  return pushed;
}

/**
 * FlatBuffer message builder callback used with handleNanoappListRequest()
 */
void buildNanoappListResponse(ChreFlatBufferBuilder &builder, void *cookie) {
  LOGV("%s", __func__);
  auto nanoappAdderCallback = [](const Nanoapp *nanoapp, void *data) {
    auto *cbData = static_cast<NanoappListData *>(data);
    HostProtocolChre::addNanoappListEntry(
        *(cbData->builder), cbData->nanoappEntries, nanoapp->getAppId(),
        nanoapp->getAppVersion(), true /*enabled*/, nanoapp->isSystemNanoapp(),
        nanoapp->getAppPermissions(), nanoapp->getRpcServices());
  };

  // Add a NanoappListEntry to the FlatBuffer for each nanoapp
  auto *cbData = static_cast<NanoappListData *>(cookie);
  cbData->builder = &builder;
  EventLoop &eventLoop = EventLoopManagerSingleton::get()->getEventLoop();
  eventLoop.forEachNanoapp(nanoappAdderCallback, cbData);
  HostProtocolChre::finishNanoappListResponse(builder, cbData->nanoappEntries,
                                              cbData->hostClientId);
}

void handleUnloadNanoappCallback(uint16_t /*type*/, void *data,
                                 void * /*extraData*/) {
  auto *cbData = static_cast<UnloadNanoappCallbackData *>(data);
  bool success = false;
  uint16_t instanceId;
  EventLoop &eventLoop = EventLoopManagerSingleton::get()->getEventLoop();
  if (!eventLoop.findNanoappInstanceIdByAppId(cbData->appId, &instanceId)) {
    LOGE("Couldn't unload app ID 0x%016" PRIx64 ": not found", cbData->appId);
  } else {
    success =
        eventLoop.unloadNanoapp(instanceId, cbData->allowSystemNanoappUnload);
  }

  constexpr size_t kInitialBufferSize = 52;
  auto builder = MakeUnique<ChreFlatBufferBuilder>(kInitialBufferSize);
  HostProtocolChre::encodeUnloadNanoappResponse(*builder, cbData->hostClientId,
                                                cbData->transactionId, success);

  if (!enqueueMessage(PendingMessage(PendingMessageType::UnloadNanoappResponse,
                                     builder.get()))) {
    LOGE("Failed to send unload response to host: %x transactionID: 0x%x",
         cbData->hostClientId, cbData->transactionId);
  } else {
    builder.release();
  }

  memoryFree(data);
}

}  // anonymous namespace

void sendDebugDumpResultToHost(uint16_t hostClientId, const char * /*debugStr*/,
                               size_t /*debugStrSize*/, bool /*complete*/,
                               uint32_t /*dataCount*/) {
  LOGV("%s: host client id %d", __func__, hostClientId);
  // TODO(b/263958729): Implement this.
}

HostLinkBase::HostLinkBase() {
  LOGV("HostLinkBase::%s", __func__);
  initializeIpi();
}

HostLinkBase::~HostLinkBase() {
  LOGV("HostLinkBase::%s", __func__);
}

void HostLinkBase::vChreReceiveTask(void *pvParameters) {
  int i = 0;
  int ret = 0;

  LOGV("%s", __func__);
  while (true) {
    LOGV("%s calling ipi_recv_reply(), Cnt=%d", __func__, i++);
    ret = ipi_recv_reply(IPI_IN_C_HOST_SCP_CHRE, (void *)&gChreIpiAckToHost[0],
                         1);
    if (ret != IPI_ACTION_DONE)
      LOGE("%s ipi_recv_reply() ret = %d", __func__, ret);
    LOGV("%s reply_end", __func__);
  }
}

void HostLinkBase::vChreSendTask(void *pvParameters) {
  while (true) {
    auto msg = gOutboundQueue.pop();
    dequeueMessage(msg);
  }
}

void HostLinkBase::chreIpiHandler(unsigned int id, void *prdata, void *data,
                                  unsigned int len) {
  /* receive magic and cmd */
  struct ScpChreIpiMsg msg = *(struct ScpChreIpiMsg *)data;

  // check the magic number and payload size need to be copy(if need) */
  LOGV("%s: msg.magic=0x%x, msg.size=%u", __func__, msg.magic, msg.size);
  if (msg.magic != SCP_CHRE_MAGIC) {
    LOGE("Invalid magic number, skip message");
    gChreIpiAckToHost[0] = IPI_NO_MEMORY;
    gChreIpiAckToHost[1] = 0;
    return;
  }

  // Mapping the physical address of share memory for SCP
  uint32_t srcAddr =
      ap_to_scp(reinterpret_cast<uint32_t>(gChreSubregionRecvAddr));

#ifdef SCP_CHRE_USE_DMA
  // Using SCP DMA HW to copy the data from share memory to SCP side, ex:
  // gChreRecvBuffer gChreRecvBuffer could be a global variables or a SCP heap
  // memory at SRAM/DRAM
  scp_dma_transaction_dram(reinterpret_cast<uint32_t>(&gChreRecvBuffer[0]),
                           srcAddr, msg.size, DMA_MEM_ID, NO_RESERVED);

  // Invalid cache to update the newest data before using
  mrv_dcache_invalid_multi_addr(reinterpret_cast<uint32_t>(&gChreRecvBuffer[0]),
                                align(msg.size, CACHE_LINE_SIZE));
#else
  memcpy(static_cast<void *>(gChreRecvBuffer),
         reinterpret_cast<void *>(srcAddr), msg.size);
#endif

  // process the message
  LOGV("chre_rcvbuf: 0x%x 0x%x 0x%x 0x%x", gChreRecvBuffer[0],
       gChreRecvBuffer[1], gChreRecvBuffer[2], gChreRecvBuffer[3]);
  receive(static_cast<HostLinkBase *>(prdata), gChreRecvBuffer, msg.size);

  // After finishing the job, akc the message to host
  gChreIpiAckToHost[0] = IPI_ACTION_DONE;
  gChreIpiAckToHost[1] = msg.size;
}

void HostLinkBase::initializeIpi(void) {
  LOGV("%s", __func__);
  bool success = false;
  int ret;
  constexpr size_t kBackgroundTaskStackSize = 1024;
  constexpr UBaseType_t kBackgroundTaskPriority = 2;

  // prepared share memory information and register the callback functions
  if (!(ret = scp_get_reserve_mem_by_id(SCP_CHRE_FROM_MEM_ID,
                                        &gChreSubregionRecvAddr,
                                        &gChreSubregionRecvSize))) {
    LOGE("%s: get SCP_CHRE_FROM_MEM_ID memory fail", __func__);
  } else if (!(ret = scp_get_reserve_mem_by_id(SCP_CHRE_TO_MEM_ID,
                                               &gChreSubregionSendAddr,
                                               &gChreSubregionSendSize))) {
    LOGE("%s: get SCP_CHRE_TO_MEM_ID memory fail", __func__);
  } else if (pdPASS != xTaskCreate(vChreReceiveTask, "CHRE_RECEIVE",
                                   kBackgroundTaskStackSize, (void *)0,
                                   kBackgroundTaskPriority, NULL)) {
    LOGE("%s failed to create ipi receiver task", __func__);
  } else if (pdPASS != xTaskCreate(vChreSendTask, "CHRE_SEND",
                                   kBackgroundTaskStackSize, (void *)0,
                                   kBackgroundTaskPriority, NULL)) {
    LOGE("%s failed to create ipi outbound message queue task", __func__);
  } else if (IPI_ACTION_DONE !=
             (ret = ipi_register(IPI_IN_C_HOST_SCP_CHRE, (void *)chreIpiHandler,
                                 (void *)this, (void *)&gChreIpiRecvData[0]))) {
    LOGE("ipi_register IPI_IN_C_HOST_SCP_CHRE failed, %d", ret);
  } else if (IPI_ACTION_DONE !=
             (ret = ipi_register(IPI_OUT_C_SCP_HOST_CHRE, NULL, (void *)this,
                                 (void *)&gChreIpiAckFromHost[0]))) {
    LOGE("ipi_register IPI_OUT_C_SCP_HOST_CHRE failed, %d", ret);
  } else {
    success = true;
  }

  if (!success) {
    FATAL_ERROR("HostLinkBase::initializeIpi() failed");
  }
}

void HostLinkBase::receive(HostLinkBase *instance, void *message,
                           int messageLen) {
  LOGV("%s: message len %d", __func__, messageLen);

  // TODO(b/263958729): A crude way to initially determine daemon's up - set
  // a flag on the first message received. This is temporary until a better
  // way to do this is available.
  instance->setInitialized(true);

  if (!HostProtocolChre::decodeMessageFromHost(message, messageLen)) {
    LOGE("Failed to decode msg %p of len %u", message, messageLen);
  }
}

bool HostLinkBase::send(uint8_t *data, size_t dataLen) {
#ifndef HOST_LINK_IPI_SEND_TIMEOUT_MS
#define HOST_LINK_IPI_SEND_TIMEOUT_MS 100
#endif
#ifndef HOST_LINK_IPI_RESPONSE_TIMEOUT_MS
#define HOST_LINK_IPI_RESPONSE_TIMEOUT_MS 100
#endif
  LOGV("HostLinkBase::%s: %zu, %p", __func__, dataLen, data);
  struct ScpChreIpiMsg msg;
  msg.magic = SCP_CHRE_MAGIC;
  msg.size = dataLen;

  // Mapping the physical address of share memory for SCP
  void *dstAddr = reinterpret_cast<void *>(
      ap_to_scp(reinterpret_cast<uint32_t>(gChreSubregionSendAddr)));

#ifdef SCP_CHRE_USE_DMA
  // TODO(b/263958729): use DMA for larger payload
  // No need cache operation, because src_dst handled by SCP CPU and dstAddr is
  // non-cacheable
#else
  memcpy(dstAddr, data, dataLen);
#endif

  // NB: len param for ipi_send is in number of 32-bit words
  int ret = ipi_send_compl(
      IPI_OUT_C_SCP_HOST_CHRE, &msg, sizeof(msg) / sizeof(uint32_t),
      HOST_LINK_IPI_SEND_TIMEOUT_MS, HOST_LINK_IPI_RESPONSE_TIMEOUT_MS);
  if (ret) {
    LOGE("chre ipi send fail(%d)", ret);
  } else {
    /* check ack data for make sure IPI wasn't busy */
    LOGV("chre ipi send, check ack data: 0x%x", gChreIpiAckFromHost[0]);
    if (gChreIpiAckFromHost[0] == IPI_ACTION_DONE) {
      LOGV("chre ipi send done, you can send another IPI");
    } else if (gChreIpiAckFromHost[0] == IPI_PIN_BUSY) {
      /* you may have to re-send the IPI, or drop this one */
      LOGV(
          "chre ipi send busy, user thread has not wait the IPI until job "
          "finished");
    } else if (gChreIpiAckFromHost[0] == IPI_NO_MEMORY) {
      LOGV("chre ipi send with wrong size(%zu)", dataLen);
    } else {
      LOGV("chre ipi send unknown case");
    }
  }

  return ret == IPI_ACTION_DONE;
}

void HostLinkBase::sendTimeSyncRequest() {
  LOGV("%s", __func__);
  // TODO(b/263958729): Implement this.
}

void HostLinkBase::sendLogMessageV2(const uint8_t *logMessage,
                                    size_t logMessageSize,
                                    uint32_t numLogsDropped) {
  LOGV("%s: size %zu", __func__, logMessageSize);
  struct LogMessageData {
    const uint8_t *logMsg;
    size_t logMsgSize;
    uint32_t numLogsDropped;
  };

  LogMessageData logMessageData{logMessage, logMessageSize, numLogsDropped};

  auto msgBuilder = [](ChreFlatBufferBuilder &builder, void *cookie) {
    const auto *data = static_cast<const LogMessageData *>(cookie);
    HostProtocolChre::encodeLogMessagesV2(
        builder, data->logMsg, data->logMsgSize, data->numLogsDropped);
  };

  constexpr size_t kInitialSize = 128;
  bool result = false;
  if (isInitialized()) {
    result = buildAndEnqueueMessage(
        PendingMessageType::EncodedLogMessage,
        kInitialSize + logMessageSize + sizeof(numLogsDropped), msgBuilder,
        &logMessageData);
  }

#ifdef CHRE_USE_BUFFERED_LOGGING
  if (LogBufferManagerSingleton::isInitialized()) {
    LogBufferManagerSingleton::get()->onLogsSentToHost(result);
  }
#else
  UNUSED_VAR(result);
#endif
}

bool HostLink::sendMessage(HostMessage const *message) {
  LOGV("HostLink::%s size(%zu)", __func__, message->message.size());
  bool success = false;

  if (isInitialized()) {
    success = enqueueMessage(
        PendingMessage(PendingMessageType::NanoappMessageToHost, message));
  } else {
    LOGW("Dropping outbound message: host link not initialized yet");
  }
  return success;
}

// TODO(b/263958729): HostMessageHandlers member function implementations are
// expected to be (mostly) identical for any platform that uses flatbuffers
// to encode messages - refactor the host link to merge the multiple copies
// we currently have.
void HostMessageHandlers::handleNanoappMessage(uint64_t appId,
                                               uint32_t messageType,
                                               uint16_t hostEndpoint,
                                               const void *messageData,
                                               size_t messageDataLen) {
  LOGV("Parsed nanoapp message from host: app ID 0x%016" PRIx64
       ", endpoint "
       "0x%" PRIx16 ", msgType %" PRIu32 ", payload size %zu",
       appId, hostEndpoint, messageType, messageDataLen);

  // TODO(b/263958729): Implement this.
  getHostCommsManager().sendMessageToNanoappFromHost(
      appId, messageType, hostEndpoint, messageData, messageDataLen);
}

void HostMessageHandlers::handleHubInfoRequest(uint16_t hostClientId) {
  LOGV("%s: host client id %d", __func__, hostClientId);
  enqueueMessage(
      PendingMessage(PendingMessageType::HubInfoResponse, hostClientId));
}

void HostMessageHandlers::handleNanoappListRequest(uint16_t hostClientId) {
  auto callback = [](uint16_t /*type*/, void *data, void * /*extraData*/) {
    uint16_t cbHostClientId = NestedDataPtr<uint16_t>(data);

    NanoappListData cbData = {};
    cbData.hostClientId = cbHostClientId;

    size_t expectedNanoappCount =
        EventLoopManagerSingleton::get()->getEventLoop().getNanoappCount();
    if (!cbData.nanoappEntries.reserve(expectedNanoappCount)) {
      LOG_OOM();
    } else {
      constexpr size_t kFixedOverhead = 48;
      constexpr size_t kPerNanoappSize = 32;
      size_t initialBufferSize =
          (kFixedOverhead + expectedNanoappCount * kPerNanoappSize);

      buildAndEnqueueMessage(PendingMessageType::NanoappListResponse,
                             initialBufferSize, buildNanoappListResponse,
                             &cbData);
    }
  };

  LOGD("Nanoapp list request from client ID %" PRIu16, hostClientId);
  EventLoopManagerSingleton::get()->deferCallback(
      SystemCallbackType::NanoappListResponse,
      NestedDataPtr<uint16_t>(hostClientId), callback);
}

void HostMessageHandlers::sendFragmentResponse(uint16_t hostClientId,
                                               uint32_t transactionId,
                                               uint32_t fragmentId,
                                               bool success) {
  struct FragmentedLoadInfoResponse {
    uint16_t hostClientId;
    uint32_t transactionId;
    uint32_t fragmentId;
    bool success;
  };

  auto msgBuilder = [](ChreFlatBufferBuilder &builder, void *cookie) {
    auto *cbData = static_cast<FragmentedLoadInfoResponse *>(cookie);
    HostProtocolChre::encodeLoadNanoappResponse(
        builder, cbData->hostClientId, cbData->transactionId, cbData->success,
        cbData->fragmentId);
  };

  FragmentedLoadInfoResponse response = {
      .hostClientId = hostClientId,
      .transactionId = transactionId,
      .fragmentId = fragmentId,
      .success = success,
  };
  constexpr size_t kInitialBufferSize = 52;
  buildAndEnqueueMessage(PendingMessageType::LoadNanoappResponse,
                         kInitialBufferSize, msgBuilder, &response);
}

void HostMessageHandlers::handleLoadNanoappRequest(
    uint16_t hostClientId, uint32_t transactionId, uint64_t appId,
    uint32_t appVersion, uint32_t appFlags, uint32_t targetApiVersion,
    const void *buffer, size_t bufferLen, const char *appFileName,
    uint32_t fragmentId, size_t appBinaryLen, bool respondBeforeStart) {
  UNUSED_VAR(appFileName);

  loadNanoappData(hostClientId, transactionId, appId, appVersion, appFlags,
                  targetApiVersion, buffer, bufferLen, fragmentId, appBinaryLen,
                  respondBeforeStart);
}

void HostMessageHandlers::handleUnloadNanoappRequest(
    uint16_t hostClientId, uint32_t transactionId, uint64_t appId,
    bool allowSystemNanoappUnload) {
  LOGD("Unload nanoapp request from client %" PRIu16 " (txnID %" PRIu32
       ") for appId 0x%016" PRIx64 " system %d",
       hostClientId, transactionId, appId, allowSystemNanoappUnload);
  auto *cbData = memoryAlloc<UnloadNanoappCallbackData>();
  if (cbData == nullptr) {
    LOG_OOM();
  } else {
    cbData->appId = appId;
    cbData->transactionId = transactionId;
    cbData->hostClientId = hostClientId;
    cbData->allowSystemNanoappUnload = allowSystemNanoappUnload;

    EventLoopManagerSingleton::get()->deferCallback(
        SystemCallbackType::HandleUnloadNanoapp, cbData,
        handleUnloadNanoappCallback);
  }
}

void HostLink::flushMessagesSentByNanoapp(uint64_t /* appId */) {
  // Not implemented
}

void HostMessageHandlers::handleTimeSyncMessage(int64_t offset) {
  LOGE("%s unsupported.", __func__);
}

void HostMessageHandlers::handleDebugDumpRequest(uint16_t hostClientId) {
  LOGV("%s: host client id %d", __func__, hostClientId);
  // TODO(b/263958729): Implement this.
}

void HostMessageHandlers::handleSettingChangeMessage(fbs::Setting setting,
                                                     fbs::SettingState state) {
  // TODO(b/267207477): Refactor handleSettingChangeMessage to shared code
  Setting chreSetting;
  bool chreSettingEnabled;
  if (HostProtocolChre::getSettingFromFbs(setting, &chreSetting) &&
      HostProtocolChre::getSettingEnabledFromFbs(state, &chreSettingEnabled)) {
    EventLoopManagerSingleton::get()->getSettingManager().postSettingChange(
        chreSetting, chreSettingEnabled);
  }
}

void HostMessageHandlers::handleSelfTestRequest(uint16_t hostClientId) {
  LOGV("%s: host client id %d", __func__, hostClientId);
  // TODO(b/263958729): Implement this.
}

void HostMessageHandlers::handleNanConfigurationUpdate(bool /* enabled */) {
  LOGE("%s NAN unsupported.", __func__);
}

void sendAudioRequest() {
  auto msgBuilder = [](ChreFlatBufferBuilder &builder, void * /*cookie*/) {
    HostProtocolChre::encodeLowPowerMicAccessRequest(builder);
  };
  constexpr size_t kInitialSize = 32;
  buildAndEnqueueMessage(PendingMessageType::LowPowerMicAccessRequest,
                         kInitialSize, msgBuilder, /* cookie= */ nullptr);
}

void sendAudioRelease() {
  auto msgBuilder = [](ChreFlatBufferBuilder &builder, void * /*cookie*/) {
    HostProtocolChre::encodeLowPowerMicAccessRelease(builder);
  };
  constexpr size_t kInitialSize = 32;
  buildAndEnqueueMessage(PendingMessageType::LowPowerMicAccessRelease,
                         kInitialSize, msgBuilder, /* cookie= */ nullptr);
}

}  // namespace chre
