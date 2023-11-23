/*
 * Copyright (C) 2022 The Android Open Source Project
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
#ifndef ANDROID_HARDWARE_CONTEXTHUB_COMMON_HAL_CLIENT_MANAGER_H_
#define ANDROID_HARDWARE_CONTEXTHUB_COMMON_HAL_CLIENT_MANAGER_H_

#include <aidl/android/hardware/contexthub/ContextHubMessage.h>
#include <aidl/android/hardware/contexthub/IContextHub.h>
#include <aidl/android/hardware/contexthub/IContextHubCallback.h>
#include <chre_host/fragmented_load_transaction.h>
#include <chre_host/preloaded_nanoapp_loader.h>
#include <sys/types.h>
#include <cstddef>
#include <unordered_map>
#include <unordered_set>
#include "chre_host/log.h"
#include "hal_client_id.h"

using aidl::android::hardware::contexthub::ContextHubMessage;
using aidl::android::hardware::contexthub::HostEndpointInfo;
using aidl::android::hardware::contexthub::IContextHubCallback;
using android::chre::FragmentedLoadTransaction;
using HostEndpointId = uint16_t;

namespace android::hardware::contexthub::common::implementation {

/**
 * A class managing clients for Context Hub HAL.
 *
 * A HAL client is defined as a user calling the IContextHub API. The main
 * purpose of this class are:
 *   - to assign a unique HalClientId identifying each client;
 *   - to maintain a mapping between client ids and HalClientInfos;
 *   - to maintain a mapping between client ids and their endpoint ids.
 *
 * There are two types of ids HalClientManager will track, host endpoint id and
 * client id. A host endpoint id, which is defined at
 * hardware/interfaces/contexthub/aidl/android/hardware/contexthub/ContextHubMessage.aidl,
 * identifies a host app that communicates with a HAL client. A client id
 * identifies a HAL client, which is the layer beneath the host apps, such as
 * ContextHubService. Multiple apps with different host endpoint IDs can have
 * the same client ID.
 *
 * For a host endpoint connected to ContextHubService, its endpoint id is kept
 *in the form below during the communication with CHRE.
 *
 *  0                   1
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |0|      endpoint_id            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * For vendor host endpoints,  the client id is embedded into the endpoint id
 * before sending a message to CHRE. When that happens, the highest bit is set
 * to 1 and the endpoint id is mutated to the format below:
 *
 *  0                   1
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |1|   client_id     |endpoint_id|
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * Note that HalClientManager is not responsible for generating endpoint ids,
 * which should be managed by HAL clients themselves.
 */
class HalClientManager {
 public:
  HalClientManager();
  virtual ~HalClientManager() = default;

  /** Disable copy constructor and copy assignment to avoid duplicates. */
  HalClientManager(HalClientManager &) = delete;
  void operator=(const HalClientManager &) = delete;

  /**
   * Gets the client id allocated to the current HAL client.
   *
   * The current HAL client is identified by its process id, which is retrieved
   * by calling AIBinder_getCallingPid(). If the process doesn't have any client
   * id assigned, HalClientManager will create one mapped to its process id.
   *
   * @return client id assigned to the calling process, or kDefaultHalClientId
   * if the process id is not found.
   */
  HalClientId getClientId();

  /**
   * Gets the callback for the current HAL client identified by the clientId.
   *
   * @return callback previously registered. nullptr is returned if the clientId
   * is not found.
   */
  std::shared_ptr<IContextHubCallback> getCallback(HalClientId clientId);

  /**
   * Registers a IContextHubCallback function mapped to the current client's
   * client id. @p deathRecipient and @p deathRecipientCookie are used to unlink
   * the previous registered callback for the same client, if any.
   *
   * @param callback a function incurred to handle the client death event.
   * @param deathRecipient a handle on the death notification.
   * @param deathRecipientCookie the data used by the callback.
   *
   * @return true if success, otherwise false.
   */
  bool registerCallback(
      const std::shared_ptr<IContextHubCallback> &callback,
      const ndk::ScopedAIBinder_DeathRecipient &deathRecipient,
      void *deathRecipientCookie);

  /**
   * Registers a FragmentedLoadTransaction for the current HAL client.
   *
   * At this moment only one active transaction, either load or unload, is
   * supported.
   *
   * @return true if success, otherwise false.
   */
  bool registerPendingLoadTransaction(
      std::unique_ptr<FragmentedLoadTransaction> transaction);

  /**
   * Returns true if the load transaction matches the arguments provided.
   */
  bool isPendingLoadTransactionExpected(HalClientId clientId,
                                        uint32_t transactionId,
                                        uint32_t currentFragmentId) {
    const std::lock_guard<std::mutex> lock(mLock);
    return isPendingLoadTransactionMatchedLocked(clientId, transactionId,
                                                 currentFragmentId);
  }

  /**
   * Clears the pending load transaction.
   *
   * This function is called to proactively clear out a pending load transaction
   * that is not timed out yet.
   *
   */
  void resetPendingLoadTransaction();

  /**
   * Gets the next FragmentedLoadRequest from PendingLoadTransaction if it's
   * available.
   *
   * @return an optional FragmentedLoadRequest, std::nullopt if unavailable.
   */

  std::optional<chre::FragmentedLoadRequest> getNextFragmentedLoadRequest();

  /**
   * Registers the current HAL client as having a pending unload transaction.
   *
   * At this moment only one active transaction, either load or unload, is
   * supported.
   *
   * @return true if success, otherwise false.
   */
  bool registerPendingUnloadTransaction(uint32_t transactionId);

  /**
   * Clears the pending unload transaction.
   *
   * This function is called to proactively clear out a pending unload
   * transaction that is not timed out yet. @p clientId and @p
   * transactionId must match the existing pending transaction.
   *
   * @param clientId the client id of the caller.
   * @param transactionId unique id of the transaction.
   *
   * @return true if the pending transaction is cleared, otherwise false.
   */
  bool resetPendingUnloadTransaction(HalClientId clientId,
                                     uint32_t transactionId);

  /**
   * Registers an endpoint id when it is connected to HAL.
   *
   * @return true if success, otherwise false.
   */
  bool registerEndpointId(const HostEndpointId &endpointId);

  /**
   * Removes an endpoint id when it is disconnected to HAL.
   *
   * @return true if success, otherwise false.
   */
  bool removeEndpointId(const HostEndpointId &endpointId);

  /**
   * Mutates the endpoint id if the hal client is not the framework service.
   *
   * @return true if success, otherwise false.
   */
  bool mutateEndpointIdFromHostIfNeeded(const pid_t &pid,
                                        HostEndpointId &endpointId);

  /** Returns the original endpoint id sent by the host client. */
  static HostEndpointId convertToOriginalEndpointId(
      const HostEndpointId &endpointId);

  /**
   * Gets all the connected endpoints for the client identified by the pid.
   *
   * @return the pointer to the endpoint id set if the client is identifiable,
   * otherwise nullptr.
   */
  const std::unordered_set<HostEndpointId> *getAllConnectedEndpoints(pid_t pid);

  /** Sends a message to every connected endpoints. */
  void sendMessageForAllCallbacks(
      const ContextHubMessage &message,
      const std::vector<std::string> &messageParams);

  std::shared_ptr<IContextHubCallback> getCallbackForEndpoint(
      const HostEndpointId &endpointId);

  /**
   * Handles the client death event.
   *
   * @param pid of the client that loses the binder connection to the HAL.
   * @param deathRecipient to be unlinked with the client's callback
   */
  void handleClientDeath(
      pid_t pid, const ndk::ScopedAIBinder_DeathRecipient &deathRecipient);

  /** Handles CHRE restart event. */
  void handleChreRestart();

 protected:
  static constexpr char kSystemServerName[] = "system_server";
  static constexpr char kClientMappingFilePath[] =
      "/data/vendor/chre/chre_hal_clients.json";
  static constexpr char kJsonClientId[] = "ClientId";
  static constexpr char kJsonProcessName[] = "ProcessName";
  static constexpr int64_t kTransactionTimeoutThresholdMs = 5000;  // 5 seconds
  static constexpr uint8_t kNumOfBitsForEndpointId = 6;
  static constexpr HostEndpointId kMaxVendorEndpointId =
      (1 << kNumOfBitsForEndpointId) - 1;
  // The endpoint id is from a vendor client if the highest bit is set to 1.
  static constexpr HostEndpointId kVendorEndpointIdBitMask = 0x8000;

  struct HalClientInfo {
    explicit HalClientInfo(const std::shared_ptr<IContextHubCallback> &callback,
                           void *cookie) {
      this->callback = callback;
      this->deathRecipientCookie = cookie;
    }
    HalClientInfo() = default;
    std::shared_ptr<IContextHubCallback> callback;
    // cookie is used by the death recipient's linked callback
    void *deathRecipientCookie{};
    std::unordered_set<HostEndpointId> endpointIds{};
  };

  struct PendingTransaction {
    PendingTransaction(HalClientId clientId, uint32_t transactionId,
                       int64_t registeredTimeMs) {
      this->clientId = clientId;
      this->transactionId = transactionId;
      this->registeredTimeMs = registeredTimeMs;
    }
    HalClientId clientId;
    uint32_t transactionId;
    int64_t registeredTimeMs;
  };

  /**
   * PendingLoadTransaction tracks ongoing load transactions.
   */
  struct PendingLoadTransaction : public PendingTransaction {
    PendingLoadTransaction(
        HalClientId clientId, int64_t registeredTimeMs,
        uint32_t currentFragmentId,
        std::unique_ptr<chre::FragmentedLoadTransaction> transaction)
        : PendingTransaction(clientId, transaction->getTransactionId(),
                             registeredTimeMs) {
      this->currentFragmentId = currentFragmentId;
      this->transaction = std::move(transaction);
    }
    uint32_t currentFragmentId;  // the fragment id being sent out.
    std::unique_ptr<chre::FragmentedLoadTransaction> transaction;

    [[nodiscard]] std::string toString() const {
      using android::internal::ToString;
      return "[Load transaction: client id " + ToString(clientId) +
             ", Transaction id " + ToString(transaction->getTransactionId()) +
             ", fragment id " + ToString(currentFragmentId) + "]";
    }
  };

  /**
   * Creates a client id to uniquely identify a HAL client.
   *
   * A file is maintained on the device for the mappings between client names
   * and client ids so that if a client has connected to HAL before the same
   * client id is always assigned to it.
   *
   * mLock must be held when this function is called.
   *
   * @param processName the process name of the client
   */
  virtual std::optional<HalClientId> createClientIdLocked(
      const std::string &processName);

  /**
   * Returns true if @p clientId and @p transactionId match the
   * corresponding values in @p transaction.
   *
   * mLock must be held when this function is called.
   */
  static bool isPendingTransactionMatchedLocked(
      HalClientId clientId, uint32_t transactionId,
      const std::optional<PendingTransaction> &transaction) {
    return transaction.has_value() && transaction->clientId == clientId &&
           transaction->transactionId == transactionId;
  }

  /**
   * Returns true if the load transaction is expected.
   *
   * mLock must be held when this function is called.
   */
  bool isPendingLoadTransactionMatchedLocked(HalClientId clientId,
                                             uint32_t transactionId,
                                             uint32_t currentFragmentId);

  /**
   * Checks if the transaction registration is allowed and clears out any stale
   * pending transaction if possible.
   *
   * This function is called when registering a new transaction. The reason that
   * we still proceed when there is already a pending transaction is because we
   * don't want a stale one, for whatever reason, to block future transactions.
   * However, every transaction is guaranteed to have up to
   * kTransactionTimeoutThresholdMs to finish.
   *
   * mLock must be held when this function is called.
   *
   * @param clientId id of the client trying to register the transaction
   *
   * @return true if registration is allowed, otherwise false.
   */
  bool isNewTransactionAllowedLocked(HalClientId clientId);

  /**
   * Returns true if the clientId is being used.
   *
   * mLock must be held when this function is called.
   */
  inline bool isAllocatedClientIdLocked(HalClientId clientId) {
    return mClientIdsToClientInfo.find(clientId) !=
           mClientIdsToClientInfo.end();
  }

  /**
   * Returns true if the pid is being used to identify a client.
   *
   * mLock must be held when this function is called.
   */
  inline bool isKnownPIdLocked(pid_t pid) {
    return mPIdsToClientIds.find(pid) != mPIdsToClientIds.end();
  }

  /** Returns true if the endpoint id is within the accepted range. */
  [[nodiscard]] inline bool isValidEndpointId(
      const HalClientId &clientId, const HostEndpointId &endpointId) const {
    if (clientId != mFrameworkServiceClientId) {
      return endpointId <= kMaxVendorEndpointId;
    }
    return true;
  }

  /**
   * Overrides the old callback registered with the client.
   *
   * @return true if success, otherwise false
   */
  bool overrideCallbackLocked(
      pid_t pid, const std::shared_ptr<IContextHubCallback> &callback,
      const ndk::ScopedAIBinder_DeathRecipient &deathRecipient,
      void *deathRecipientCookie);

  /**
   * Extracts the client id from the endpoint id.
   *
   * @param endpointId the endpoint id received from CHRE, before any conversion
   */
  [[nodiscard]] inline HalClientId getClientIdFromEndpointId(
      const HostEndpointId &endpointId) const {
    if (endpointId & kVendorEndpointIdBitMask) {
      return endpointId >> kNumOfBitsForEndpointId & kMaxHalClientId;
    }
    return mFrameworkServiceClientId;
  }

  std::string getProcessName(pid_t /*pid*/) {
    // TODO(b/274597758): this is a temporary solution that should be updated
    //   after b/274597758 is resolved.
    if (mIsFirstClient) {
      mIsFirstClient = false;
      return kSystemServerName;
    }
    return "the_vendor_client";
  }

  bool mIsFirstClient = true;

  // next available client id
  HalClientId mNextClientId = kDefaultHalClientId + 1;
  // framework service client id
  HalClientId mFrameworkServiceClientId = kDefaultHalClientId;

  // The lock guarding the access to clients' states and pending transactions
  std::mutex mLock;

  // Map from process name to client id which stays consistent with the file
  // stored at kClientMappingFilePath
  std::unordered_map<std::string, HalClientId> mProcessNamesToClientIds{};
  // Map from pids to client ids
  std::unordered_map<pid_t, HalClientId> mPIdsToClientIds{};
  // Map from client ids to ClientInfos
  std::unordered_map<HalClientId, HalClientInfo> mClientIdsToClientInfo{};

  // States tracking pending transactions
  std::optional<PendingLoadTransaction> mPendingLoadTransaction = std::nullopt;
  std::optional<PendingTransaction> mPendingUnloadTransaction = std::nullopt;
};
}  // namespace android::hardware::contexthub::common::implementation

#endif  // ANDROID_HARDWARE_CONTEXTHUB_COMMON_HAL_CLIENT_MANAGER_H_
