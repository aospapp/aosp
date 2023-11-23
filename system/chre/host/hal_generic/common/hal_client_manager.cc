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
#include "hal_client_manager.h"
#include <aidl/android/hardware/contexthub/AsyncEventType.h>
#include <android-base/strings.h>
#include <json/json.h>
#include <utils/SystemClock.h>
#include <fstream>

namespace android::hardware::contexthub::common::implementation {

using aidl::android::hardware::contexthub::AsyncEventType;
using aidl::android::hardware::contexthub::ContextHubMessage;
using aidl::android::hardware::contexthub::HostEndpointInfo;
using aidl::android::hardware::contexthub::IContextHubCallback;

namespace {
bool getClientMappingsFromFile(const char *filePath, Json::Value &mappings) {
  std::fstream file(filePath);
  Json::CharReaderBuilder builder;
  return file.good() &&
         Json::parseFromStream(builder, file, &mappings, /* errs= */ nullptr);
}
}  // namespace

std::optional<HalClientId> HalClientManager::createClientIdLocked(
    const std::string &processName) {
  if (mPIdsToClientIds.size() > kMaxNumOfHalClients ||
      mNextClientId > kMaxHalClientId) {
    LOGE("Too many HAL clients registered which should never happen.");
    return std::nullopt;
  }
  if (mProcessNamesToClientIds.find(processName) !=
      mProcessNamesToClientIds.end()) {
    return mProcessNamesToClientIds[processName];
  }
  // Update the json list with the new mapping
  mProcessNamesToClientIds.emplace(processName, mNextClientId);
  Json::Value mappings;
  for (const auto &[name, clientId] : mProcessNamesToClientIds) {
    Json::Value mapping;
    mapping[kJsonProcessName] = name;
    mapping[kJsonClientId] = clientId;
    mappings.append(mapping);
  }
  // write to the file; Create the file if it doesn't exist
  Json::StreamWriterBuilder factory;
  std::unique_ptr<Json::StreamWriter> const writer(factory.newStreamWriter());
  std::ofstream fileStream(kClientMappingFilePath);
  writer->write(mappings, &fileStream);
  fileStream << std::endl;
  return {mNextClientId++};
}

HalClientId HalClientManager::getClientId() {
  pid_t pid = AIBinder_getCallingPid();
  const std::lock_guard<std::mutex> lock(mLock);
  if (isKnownPIdLocked(pid)) {
    return mPIdsToClientIds[pid];
  }
  LOGE("Failed to find the client id for pid %d", pid);
  return kDefaultHalClientId;
}

std::shared_ptr<IContextHubCallback> HalClientManager::getCallback(
    HalClientId clientId) {
  const std::lock_guard<std::mutex> lock(mLock);
  if (isAllocatedClientIdLocked(clientId)) {
    return mClientIdsToClientInfo.at(clientId).callback;
  }
  LOGE("Failed to find the callback for the client id %" PRIu16, clientId);
  return nullptr;
}

bool HalClientManager::registerCallback(
    const std::shared_ptr<IContextHubCallback> &callback,
    const ndk::ScopedAIBinder_DeathRecipient &deathRecipient,
    void *deathRecipientCookie) {
  pid_t pid = AIBinder_getCallingPid();
  const std::lock_guard<std::mutex> lock(mLock);
  if (AIBinder_linkToDeath(callback->asBinder().get(), deathRecipient.get(),
                           deathRecipientCookie) != STATUS_OK) {
    LOGE("Failed to link client binder to death recipient.");
    return false;
  }
  if (isKnownPIdLocked(pid)) {
    LOGW("The pid %d has already registered. Overriding its callback.", pid);
    return overrideCallbackLocked(pid, callback, deathRecipient,
                                  deathRecipientCookie);
  }
  std::string processName = getProcessName(pid);
  std::optional<HalClientId> clientIdOptional =
      createClientIdLocked(processName);
  if (clientIdOptional == std::nullopt) {
    LOGE("Failed to generate a valid client id for process %s",
         processName.c_str());
    return false;
  }
  HalClientId clientId = clientIdOptional.value();
  if (mClientIdsToClientInfo.find(clientId) != mClientIdsToClientInfo.end()) {
    LOGE("Process %s already has a connection to HAL.", processName.c_str());
    return false;
  }
  mPIdsToClientIds[pid] = clientId;
  mClientIdsToClientInfo.emplace(clientId,
                                 HalClientInfo(callback, deathRecipientCookie));
  if (mFrameworkServiceClientId == kDefaultHalClientId &&
      processName == kSystemServerName) {
    mFrameworkServiceClientId = clientId;
  }
  return true;
}

bool HalClientManager::overrideCallbackLocked(
    pid_t pid, const std::shared_ptr<IContextHubCallback> &callback,
    const ndk::ScopedAIBinder_DeathRecipient &deathRecipient,
    void *deathRecipientCookie) {
  LOGI("Overriding the callback for pid %d", pid);
  HalClientInfo &clientInfo =
      mClientIdsToClientInfo.at(mPIdsToClientIds.at(pid));
  if (AIBinder_unlinkToDeath(clientInfo.callback->asBinder().get(),
                             deathRecipient.get(),
                             clientInfo.deathRecipientCookie) != STATUS_OK) {
    LOGE("Unable to unlink the old callback for pid %d", pid);
    return false;
  }
  clientInfo.callback.reset();
  clientInfo.callback = callback;
  clientInfo.deathRecipientCookie = deathRecipientCookie;
  return true;
}

void HalClientManager::handleClientDeath(
    pid_t pid, const ndk::ScopedAIBinder_DeathRecipient &deathRecipient) {
  const std::lock_guard<std::mutex> lock(mLock);
  if (!isKnownPIdLocked(pid)) {
    LOGE("Failed to locate the dead pid %d", pid);
    return;
  }
  HalClientId clientId = mPIdsToClientIds[pid];
  mPIdsToClientIds.erase(mPIdsToClientIds.find(pid));
  if (!isAllocatedClientIdLocked(clientId)) {
    LOGE("Failed to locate the dead client id %" PRIu16, clientId);
    return;
  }

  for (const auto &[procName, id] : mProcessNamesToClientIds) {
    if (id == clientId && procName == kSystemServerName) {
      LOGE("System server is disconnected");
      mIsFirstClient = true;
    }
  }

  HalClientInfo &clientInfo = mClientIdsToClientInfo.at(clientId);
  if (AIBinder_unlinkToDeath(clientInfo.callback->asBinder().get(),
                             deathRecipient.get(),
                             clientInfo.deathRecipientCookie) != STATUS_OK) {
    LOGE("Unable to unlink the old callback for pid %d in death handler", pid);
  }
  clientInfo.callback.reset();
  if (mPendingLoadTransaction.has_value() &&
      mPendingLoadTransaction->clientId == clientId) {
    mPendingLoadTransaction.reset();
  }
  if (mPendingUnloadTransaction.has_value() &&
      mPendingUnloadTransaction->clientId == clientId) {
    mPendingLoadTransaction.reset();
  }
  mClientIdsToClientInfo.erase(clientId);
  if (mFrameworkServiceClientId == clientId) {
    mFrameworkServiceClientId = kDefaultHalClientId;
  }
  LOGI("Process %" PRIu32 " is disconnected from HAL.", pid);
}

bool HalClientManager::registerPendingLoadTransaction(
    std::unique_ptr<chre::FragmentedLoadTransaction> transaction) {
  if (transaction->isComplete()) {
    LOGW("No need to register a completed load transaction.");
    return false;
  }
  pid_t pid = AIBinder_getCallingPid();

  const std::lock_guard<std::mutex> lock(mLock);
  if (!isKnownPIdLocked(pid)) {
    LOGE("Unknown HAL client when registering its pending load transaction.");
    return false;
  }
  auto clientId = mPIdsToClientIds[pid];
  if (!isNewTransactionAllowedLocked(clientId)) {
    return false;
  }
  mPendingLoadTransaction.emplace(
      clientId, /* registeredTimeMs= */ android::elapsedRealtime(),
      /* currentFragmentId= */ 0, std::move(transaction));
  return true;
}

std::optional<chre::FragmentedLoadRequest>
HalClientManager::getNextFragmentedLoadRequest() {
  const std::lock_guard<std::mutex> lock(mLock);
  if (mPendingLoadTransaction->transaction->isComplete()) {
    LOGI("Pending load transaction %" PRIu32
         " is finished with client %" PRIu16,
         mPendingLoadTransaction->transaction->getTransactionId(),
         mPendingLoadTransaction->clientId);
    mPendingLoadTransaction.reset();
    return std::nullopt;
  }
  auto request = mPendingLoadTransaction->transaction->getNextRequest();
  mPendingLoadTransaction->currentFragmentId = request.fragmentId;
  LOGD("Client %" PRIu16 " has fragment #%zu ready",
       mPendingLoadTransaction->clientId, request.fragmentId);
  return request;
}

bool HalClientManager::registerPendingUnloadTransaction(
    uint32_t transactionId) {
  pid_t pid = AIBinder_getCallingPid();
  const std::lock_guard<std::mutex> lock(mLock);
  if (!isKnownPIdLocked(pid)) {
    LOGE("Unknown HAL client when registering its pending unload transaction.");
    return false;
  }
  auto clientId = mPIdsToClientIds[pid];
  if (!isNewTransactionAllowedLocked(clientId)) {
    return false;
  }
  mPendingUnloadTransaction.emplace(
      clientId, transactionId,
      /* registeredTimeMs= */ android::elapsedRealtime());
  return true;
}

bool HalClientManager::isNewTransactionAllowedLocked(HalClientId clientId) {
  if (mPendingLoadTransaction.has_value()) {
    auto timeElapsedMs =
        android::elapsedRealtime() - mPendingLoadTransaction->registeredTimeMs;
    if (timeElapsedMs < kTransactionTimeoutThresholdMs) {
      LOGE("Rejects client %" PRIu16
           "'s transaction because an active load "
           "transaction %" PRIu32 " with current fragment id %" PRIu32
           " from client %" PRIu16 " exists. Try again later.",
           clientId, mPendingLoadTransaction->transaction->getTransactionId(),
           mPendingLoadTransaction->currentFragmentId,
           mPendingLoadTransaction->clientId);
      return false;
    }
    LOGE("Client %" PRIu16 "'s pending load transaction %" PRIu32
         " with current fragment id %" PRIu32
         " is overridden by client %" PRIu16
         " after holding the slot for %" PRIu64 " ms",
         mPendingLoadTransaction->clientId,
         mPendingLoadTransaction->transaction->getTransactionId(),
         mPendingLoadTransaction->currentFragmentId, clientId, timeElapsedMs);
    mPendingLoadTransaction.reset();
    return true;
  }
  if (mPendingUnloadTransaction.has_value()) {
    auto timeElapsedMs = android::elapsedRealtime() -
                         mPendingUnloadTransaction->registeredTimeMs;
    if (timeElapsedMs < kTransactionTimeoutThresholdMs) {
      LOGE("Rejects client %" PRIu16
           "'s transaction because an active unload "
           "transaction %" PRIu32 " from client %" PRIu16
           " exists. Try again later.",
           clientId, mPendingUnloadTransaction->transactionId,
           mPendingUnloadTransaction->clientId);
      return false;
    }
    LOGE("A pending unload transaction %" PRIu32
         " registered by client %" PRIu16
         " is overridden by a new transaction from client %" PRIu16
         " after holding the slot for %" PRIu64 "ms",
         mPendingUnloadTransaction->transactionId,
         mPendingUnloadTransaction->clientId, clientId, timeElapsedMs);
    mPendingUnloadTransaction.reset();
    return true;
  }
  return true;
}

bool HalClientManager::registerEndpointId(const HostEndpointId &endpointId) {
  pid_t pid = AIBinder_getCallingPid();
  const std::lock_guard<std::mutex> lock(mLock);
  if (!isKnownPIdLocked(pid)) {
    LOGE(
        "Unknown HAL client (pid %d). Register the callback before registering "
        "an endpoint.",
        pid);
    return false;
  }
  HalClientId clientId = mPIdsToClientIds[pid];
  if (!isValidEndpointId(clientId, endpointId)) {
    LOGE("Endpoint id %" PRIu16 " from process %d is out of range.", endpointId,
         pid);
    return false;
  }
  if (mClientIdsToClientInfo[clientId].endpointIds.find(endpointId) !=
      mClientIdsToClientInfo[clientId].endpointIds.end()) {
    LOGW("The endpoint %" PRIu16 " is already connected.", endpointId);
    return false;
  }
  mClientIdsToClientInfo[clientId].endpointIds.insert(endpointId);
  LOGI("Endpoint id %" PRIu16 " is connected to client %" PRIu16, endpointId,
       clientId);
  return true;
}

bool HalClientManager::removeEndpointId(const HostEndpointId &endpointId) {
  pid_t pid = AIBinder_getCallingPid();
  const std::lock_guard<std::mutex> lock(mLock);
  if (!isKnownPIdLocked(pid)) {
    LOGE(
        "Unknown HAL client (pid %d). A callback should have been registered "
        "before removing an endpoint.",
        pid);
    return false;
  }
  HalClientId clientId = mPIdsToClientIds[pid];
  if (!isValidEndpointId(clientId, endpointId)) {
    LOGE("Endpoint id %" PRIu16 " from process %d is out of range.", endpointId,
         pid);
    return false;
  }
  if (mClientIdsToClientInfo[clientId].endpointIds.find(endpointId) ==
      mClientIdsToClientInfo[clientId].endpointIds.end()) {
    LOGW("The endpoint %" PRIu16 " is not connected.", endpointId);
    return false;
  }
  mClientIdsToClientInfo[clientId].endpointIds.erase(endpointId);
  LOGI("Endpoint id %" PRIu16 " is removed from client %" PRIu16, endpointId,
       clientId);
  return true;
}

std::shared_ptr<IContextHubCallback> HalClientManager::getCallbackForEndpoint(
    const HostEndpointId &endpointId) {
  const std::lock_guard<std::mutex> lock(mLock);
  HalClientId clientId = getClientIdFromEndpointId(endpointId);
  if (!isAllocatedClientIdLocked(clientId)) {
    LOGE("Unknown endpoint id %" PRIu16 ". Please register the callback first.",
         endpointId);
    return nullptr;
  }
  return mClientIdsToClientInfo[clientId].callback;
}

void HalClientManager::sendMessageForAllCallbacks(
    const ContextHubMessage &message,
    const std::vector<std::string> &messageParams) {
  const std::lock_guard<std::mutex> lock(mLock);
  for (const auto &[_, clientInfo] : mClientIdsToClientInfo) {
    if (clientInfo.callback != nullptr) {
      clientInfo.callback->handleContextHubMessage(message, messageParams);
    }
  }
}

const std::unordered_set<HostEndpointId>
    *HalClientManager::getAllConnectedEndpoints(pid_t pid) {
  const std::lock_guard<std::mutex> lock(mLock);
  if (!isKnownPIdLocked(pid)) {
    LOGE("Unknown HAL client with pid %d", pid);
    return nullptr;
  }
  HalClientId clientId = mPIdsToClientIds[pid];
  if (mClientIdsToClientInfo.find(clientId) == mClientIdsToClientInfo.end()) {
    LOGE("Can't find any information for client id %" PRIu16, clientId);
    return nullptr;
  }
  return &mClientIdsToClientInfo[clientId].endpointIds;
}

bool HalClientManager::mutateEndpointIdFromHostIfNeeded(
    const pid_t &pid, HostEndpointId &endpointId) {
  const std::lock_guard<std::mutex> lock(mLock);
  if (!isKnownPIdLocked(pid)) {
    LOGE("Unknown HAL client with pid %d", pid);
    return false;
  }
  // no need to mutate client id for framework service
  if (mPIdsToClientIds[pid] != mFrameworkServiceClientId) {
    HalClientId clientId = mPIdsToClientIds[pid];
    endpointId = kVendorEndpointIdBitMask |
                 clientId << kNumOfBitsForEndpointId | endpointId;
  }
  return true;
}

HostEndpointId HalClientManager::convertToOriginalEndpointId(
    const HostEndpointId &endpointId) {
  if (endpointId & kVendorEndpointIdBitMask) {
    return endpointId & kMaxVendorEndpointId;
  }
  return endpointId;
}

HalClientManager::HalClientManager() {
  // Parses the file to construct a mapping from process names to client ids.
  Json::Value mappings;
  if (!getClientMappingsFromFile(kClientMappingFilePath, mappings)) {
    // TODO(b/247124878): When the device was firstly booted up the file doesn't
    //   exist which is expected. Consider to create a default file to avoid
    //   confusions.
    LOGW("Unable to find and read %s.", kClientMappingFilePath);
    return;
  }
  for (int i = 0; i < mappings.size(); i++) {
    Json::Value mapping = mappings[i];
    if (!mapping.isMember(kJsonClientId) ||
        !mapping.isMember(kJsonProcessName)) {
      LOGE("Unable to find expected key name for the entry %d", i);
      continue;
    }
    std::string processName = mapping[kJsonProcessName].asString();
    auto clientId = static_cast<HalClientId>(mapping[kJsonClientId].asUInt());
    mProcessNamesToClientIds[processName] = clientId;
    // mNextClientId should always hold the next available client id
    if (mNextClientId <= clientId) {
      mNextClientId = clientId + 1;
    }
  }
}

bool HalClientManager::isPendingLoadTransactionMatchedLocked(
    HalClientId clientId, uint32_t transactionId, uint32_t currentFragmentId) {
  bool success =
      isPendingTransactionMatchedLocked(clientId, transactionId,
                                        mPendingLoadTransaction) &&
      mPendingLoadTransaction->currentFragmentId == currentFragmentId;
  if (!success) {
    if (mPendingLoadTransaction.has_value()) {
      LOGE("Transaction of client %" PRIu16 " transaction %" PRIu32
           " fragment %" PRIu32
           " doesn't match the current pending transaction (client %" PRIu16
           " transaction %" PRIu32 " fragment %" PRIu32 ").",
           clientId, transactionId, currentFragmentId,
           mPendingLoadTransaction->clientId,
           mPendingLoadTransaction->transactionId,
           mPendingLoadTransaction->currentFragmentId);
    } else {
      LOGE("Transaction of client %" PRIu16 " transaction %" PRIu32
           " fragment %" PRIu32 " doesn't match any pending transaction.",
           clientId, transactionId, currentFragmentId);
    }
  }
  return success;
}

void HalClientManager::resetPendingLoadTransaction() {
  const std::lock_guard<std::mutex> lock(mLock);
  mPendingLoadTransaction.reset();
}

bool HalClientManager::resetPendingUnloadTransaction(HalClientId clientId,
                                                     uint32_t transactionId) {
  const std::lock_guard<std::mutex> lock(mLock);
  // Only clear a pending transaction when the client id and the transaction id
  // are both matched
  if (isPendingTransactionMatchedLocked(clientId, transactionId,
                                        mPendingUnloadTransaction)) {
    LOGI("Clears out the pending unload transaction: client id %" PRIu16
         ", transaction id %" PRIu32,
         clientId, transactionId);
    mPendingUnloadTransaction.reset();
    return true;
  }
  LOGW("Client %" PRIu16 " doesn't have a pending unload transaction %" PRIu32
       ". Skip resetting",
       clientId, transactionId);
  return false;
}

void HalClientManager::handleChreRestart() {
  {
    const std::lock_guard<std::mutex> lock(mLock);
    mPendingLoadTransaction.reset();
    mPendingUnloadTransaction.reset();
    for (auto &[_, clientInfo] : mClientIdsToClientInfo) {
      clientInfo.endpointIds.clear();
    }
  }
  // Incurs callbacks without holding the lock to avoid deadlocks.
  for (auto &[_, clientInfo] : mClientIdsToClientInfo) {
    clientInfo.callback->handleContextHubAsyncEvent(AsyncEventType::RESTARTED);
  }
}
}  // namespace android::hardware::contexthub::common::implementation