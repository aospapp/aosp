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

#ifndef ANDROID_HARDWARE_CONTEXTHUB_COMMON_MULTICLIENTS_HAL_BASE_H_
#define ANDROID_HARDWARE_CONTEXTHUB_COMMON_MULTICLIENTS_HAL_BASE_H_

#ifndef LOG_TAG
#define LOG_TAG "CHRE.HAL"
#endif

#include <aidl/android/hardware/contexthub/BnContextHub.h>
#include <chre_host/generated/host_messages_generated.h>

#include "chre_connection_callback.h"
#include "chre_host/napp_header.h"
#include "chre_host/preloaded_nanoapp_loader.h"
#include "hal_client_id.h"
#include "hal_client_manager.h"

namespace android::hardware::contexthub::common::implementation {

using namespace aidl::android::hardware::contexthub;
using namespace android::chre;
using ::ndk::ScopedAStatus;

/**
 * The base class of multiclients HAL.
 *
 * A subclass should initiate mConnection, mHalClientManager and
 * mPreloadedNanoappLoader in its constructor.
 *
 * TODO(b/247124878): A few things are pending:
 *   - Some APIs of IContextHub are not implemented yet;
 *   - onHostEndpointConnected/Disconnected now returns an error if the endpoint
 *     id is illegal or already connected/disconnected. The doc of
 *     IContextHub.aidl should be updated accordingly.
 *   - registerCallback() can fail if mHalClientManager sees an error during
 *     registration. The doc of IContextHub.aidl should be updated accordingly.
 *   - Involve EventLogger to log API calls;
 *   - extends DebugDumpHelper to ease debugging
 */
class MultiClientContextHubBase
    : public BnContextHub,
      public ::android::hardware::contexthub::common::implementation::
          ChreConnectionCallback {
 public:
  /** The entry point of death recipient for a disconnected client. */
  static void onClientDied(void *cookie);

  // functions implementing IContextHub
  ScopedAStatus getContextHubs(
      std::vector<ContextHubInfo> *contextHubInfos) override;
  ScopedAStatus loadNanoapp(int32_t contextHubId,
                            const NanoappBinary &appBinary,
                            int32_t transactionId) override;
  ScopedAStatus unloadNanoapp(int32_t contextHubId, int64_t appId,
                              int32_t transactionId) override;
  ScopedAStatus disableNanoapp(int32_t contextHubId, int64_t appId,
                               int32_t transactionId) override;
  ScopedAStatus enableNanoapp(int32_t contextHubId, int64_t appId,
                              int32_t transactionId) override;
  ScopedAStatus onSettingChanged(Setting setting, bool enabled) override;
  ScopedAStatus queryNanoapps(int32_t contextHubId) override;
  ScopedAStatus registerCallback(
      int32_t contextHubId,
      const std::shared_ptr<IContextHubCallback> &callback) override;
  ScopedAStatus sendMessageToHub(int32_t contextHubId,
                                 const ContextHubMessage &message) override;
  ScopedAStatus onHostEndpointConnected(const HostEndpointInfo &info) override;
  ScopedAStatus onHostEndpointDisconnected(char16_t in_hostEndpointId) override;
  ScopedAStatus getPreloadedNanoappIds(int32_t contextHubId,
                                       std::vector<int64_t> *result) override;
  ScopedAStatus onNanSessionStateChanged(
      const NanSessionStateUpdate &in_update) override;
  ScopedAStatus setTestMode(bool enable) override;

  // The callback function implementing ChreConnectionCallback
  void handleMessageFromChre(const unsigned char *messageBuffer,
                             size_t messageLen) override;
  void onChreRestarted() override;

 protected:
  // The data needed by the death client to clear states of a client.
  struct HalDeathRecipientCookie {
    MultiClientContextHubBase *hal;
    pid_t clientPid;
    HalDeathRecipientCookie(MultiClientContextHubBase *hal, pid_t pid) {
      this->hal = hal;
      this->clientPid = pid;
    }
  };
  MultiClientContextHubBase() = default;

  bool sendFragmentedLoadRequest(HalClientId clientId,
                                 FragmentedLoadRequest &fragmentedLoadRequest);

  // Functions handling various types of messages
  void handleHubInfoResponse(const ::chre::fbs::HubInfoResponseT &message);
  void onNanoappListResponse(const ::chre::fbs::NanoappListResponseT &response,
                             HalClientId clientid);
  void onNanoappLoadResponse(const ::chre::fbs::LoadNanoappResponseT &response,
                             HalClientId clientId);
  void onNanoappUnloadResponse(
      const ::chre::fbs::UnloadNanoappResponseT &response,
      HalClientId clientId);
  void onNanoappMessage(const ::chre::fbs::NanoappMessageT &message);

  void handleClientDeath(pid_t pid);

  inline bool isSettingEnabled(Setting setting) {
    return mSettingEnabled.find(setting) != mSettingEnabled.end() &&
           mSettingEnabled[setting];
  }

  // HAL is the unique owner of the communication channel to CHRE.
  std::unique_ptr<ChreConnection> mConnection{};

  // HalClientManager maintains states of hal clients. Each HAL should only have
  // one instance of a HalClientManager.
  std::unique_ptr<HalClientManager> mHalClientManager{};

  std::unique_ptr<PreloadedNanoappLoader> mPreloadedNanoappLoader{};

  std::unique_ptr<ContextHubInfo> mContextHubInfo;

  // Mutex and CV are used to get context hub info synchronously.
  std::mutex mHubInfoMutex;
  std::condition_variable mHubInfoCondition;

  // Death recipient handling clients' disconnections
  ndk::ScopedAIBinder_DeathRecipient mDeathRecipient;

  // States of settings
  std::unordered_map<Setting, bool> mSettingEnabled;
  std::optional<bool> mIsWifiAvailable;
  std::optional<bool> mIsBleAvailable;

  // A mutex to synchronize access to the list of preloaded nanoapp IDs.
  std::mutex mPreloadedNanoappIdsMutex;
  std::optional<std::vector<uint64_t>> mPreloadedNanoappIds{};
};
}  // namespace android::hardware::contexthub::common::implementation
#endif  // ANDROID_HARDWARE_CONTEXTHUB_COMMON_MULTICLIENTS_HAL_BASE_H_
