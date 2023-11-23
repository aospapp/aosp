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

#include "chre_host/preloaded_nanoapp_loader.h"
#include <chre_host/host_protocol_host.h>
#include <fstream>
#include "chre_host/config_util.h"
#include "chre_host/file_stream.h"
#include "chre_host/fragmented_load_transaction.h"
#include "chre_host/log.h"
#include "hal_client_id.h"

namespace android::chre {

using android::chre::readFileContents;
using android::hardware::contexthub::common::implementation::kHalId;

void PreloadedNanoappLoader::getPreloadedNanoappIds(
    std::vector<uint64_t> &out_preloadedNanoappIds) {
  std::vector<std::string> nanoappNames;
  std::string directory;
  out_preloadedNanoappIds.clear();
  bool success =
      getPreloadedNanoappsFromConfigFile(mConfigPath, directory, nanoappNames);
  if (!success) {
    LOGE("Failed to parse preloaded nanoapps config file");
  }
  for (const std::string &nanoappName : nanoappNames) {
    std::string headerFile = directory + "/" + nanoappName + ".napp_header";
    std::vector<uint8_t> headerBuffer;
    if (!readFileContents(headerFile.c_str(), headerBuffer)) {
      LOGE("Cannot read header file: %s", headerFile.c_str());
      continue;
    }
    if (headerBuffer.size() != sizeof(NanoAppBinaryHeader)) {
      LOGE("Header size mismatch");
      continue;
    }
    const auto *appHeader =
        reinterpret_cast<const NanoAppBinaryHeader *>(headerBuffer.data());
    out_preloadedNanoappIds.emplace_back(appHeader->appId);
  }
}

void PreloadedNanoappLoader::loadPreloadedNanoapps() {
  std::string directory;
  std::vector<std::string> nanoapps;

  bool success =
      getPreloadedNanoappsFromConfigFile(mConfigPath, directory, nanoapps);
  if (!success) {
    LOGE("Failed to load any preloaded nanoapp");
  } else {
    mIsPreloadingOngoing = true;
    for (uint32_t i = 0; i < nanoapps.size(); ++i) {
      loadPreloadedNanoapp(directory, nanoapps[i], i);
    }
    mIsPreloadingOngoing = false;
  }
}

void PreloadedNanoappLoader::loadPreloadedNanoapp(const std::string &directory,
                                                  const std::string &name,
                                                  uint32_t transactionId) {
  std::vector<uint8_t> headerBuffer;
  std::vector<uint8_t> nanoappBuffer;

  std::string headerFilename = directory + "/" + name + ".napp_header";
  std::string nanoappFilename = directory + "/" + name + ".so";

  if (!readFileContents(headerFilename.c_str(), headerBuffer) ||
      !readFileContents(nanoappFilename.c_str(), nanoappBuffer) ||
      !loadNanoapp(headerBuffer, nanoappBuffer, transactionId)) {
    LOGE("Failed to load nanoapp: '%s'", name.c_str());
  }
}

bool PreloadedNanoappLoader::loadNanoapp(const std::vector<uint8_t> &header,
                                         const std::vector<uint8_t> &nanoapp,
                                         uint32_t transactionId) {
  if (header.size() != sizeof(NanoAppBinaryHeader)) {
    LOGE("Nanoapp binary's header size is incorrect");
    return false;
  }
  const auto *appHeader =
      reinterpret_cast<const NanoAppBinaryHeader *>(header.data());

  // Build the target API version from major and minor.
  uint32_t targetApiVersion = (appHeader->targetChreApiMajorVersion << 24) |
                              (appHeader->targetChreApiMinorVersion << 16);
  return sendFragmentedLoadAndWaitForEachResponse(
      appHeader->appId, appHeader->appVersion, appHeader->flags,
      targetApiVersion, nanoapp.data(), nanoapp.size(), transactionId);
}

bool PreloadedNanoappLoader::sendFragmentedLoadAndWaitForEachResponse(
    uint64_t appId, uint32_t appVersion, uint32_t appFlags,
    uint32_t appTargetApiVersion, const uint8_t *appBinary, size_t appSize,
    uint32_t transactionId) {
  std::vector<uint8_t> binary(appSize);
  std::copy(appBinary, appBinary + appSize, binary.begin());

  FragmentedLoadTransaction transaction(transactionId, appId, appVersion,
                                        appFlags, appTargetApiVersion, binary);
  while (!transaction.isComplete()) {
    auto nextRequest = transaction.getNextRequest();
    auto future = sendFragmentedLoadRequest(nextRequest);
    if (!waitAndVerifyFuture(future, nextRequest)) {
      return false;
    }
  }
  return true;
}

bool PreloadedNanoappLoader::waitAndVerifyFuture(
    std::future<bool> &future, const FragmentedLoadRequest &request) {
  if (!future.valid()) {
    LOGE("Failed to send out the fragmented load fragment");
    return false;
  }
  if (future.wait_for(kTimeoutInMs) != std::future_status::ready) {
    LOGE(
        "Waiting for response of fragment %zu transaction %d times out "
        "after %lld ms",
        request.fragmentId, request.transactionId, kTimeoutInMs.count());
    return false;
  }
  if (!future.get()) {
    LOGE(
        "Received a failure result for loading fragment %zu of "
        "transaction %d",
        request.fragmentId, request.transactionId);
    return false;
  }
  return true;
}

bool PreloadedNanoappLoader::verifyFragmentLoadResponse(
    const ::chre::fbs::LoadNanoappResponseT &response) const {
  if (!response.success) {
    LOGE("Loading nanoapp binary fragment %d of transaction %u failed.",
         response.fragment_id, response.transaction_id);
    // TODO(b/247124878): Report metrics.
    return false;
  }
  if (mPreloadedNanoappPendingTransaction.transactionId !=
      response.transaction_id) {
    LOGE(
        "Fragmented load response with transactionId %u but transactionId "
        "%u is expected",
        response.transaction_id,
        mPreloadedNanoappPendingTransaction.transactionId);
    return false;
  }
  if (mPreloadedNanoappPendingTransaction.fragmentId != response.fragment_id) {
    LOGE(
        "Fragmented load response with unexpected fragment id %u while "
        "%zu is expected",
        response.fragment_id, mPreloadedNanoappPendingTransaction.fragmentId);
    return false;
  }
  return true;
}

bool PreloadedNanoappLoader::onLoadNanoappResponse(
    const ::chre::fbs::LoadNanoappResponseT &response, HalClientId clientId) {
  std::unique_lock<std::mutex> lock(mPreloadedNanoappsMutex);
  if (clientId != kHalId || !mFragmentedLoadPromise.has_value()) {
    LOGE(
        "Received an unexpected preload nanoapp %s response for client %d "
        "transaction %u fragment %u",
        response.success ? "success" : "failure", clientId,
        response.transaction_id, response.fragment_id);
    return false;
  }
  // set value for the future instance
  mFragmentedLoadPromise->set_value(verifyFragmentLoadResponse(response));
  // reset the promise as the value can only be retrieved once from it
  mFragmentedLoadPromise = std::nullopt;
  return true;
}

std::future<bool> PreloadedNanoappLoader::sendFragmentedLoadRequest(
    ::android::chre::FragmentedLoadRequest &request) {
  flatbuffers::FlatBufferBuilder builder(request.binary.size() + 128);
  // TODO(b/247124878): Confirm if respondBeforeStart can be set to true on all
  //  the devices.
  HostProtocolHost::encodeFragmentedLoadNanoappRequest(
      builder, request, /* respondBeforeStart= */ true);
  HostProtocolHost::mutateHostClientId(builder.GetBufferPointer(),
                                       builder.GetSize(), kHalId);
  std::unique_lock<std::mutex> lock(mPreloadedNanoappsMutex);
  if (!mConnection->sendMessage(builder.GetBufferPointer(),
                                builder.GetSize())) {
    // Returns an invalid future to indicate the failure
    return std::future<bool>{};
  }
  mPreloadedNanoappPendingTransaction = {
      .transactionId = request.transactionId,
      .fragmentId = request.fragmentId,
  };
  mFragmentedLoadPromise = std::make_optional<std::promise<bool>>();
  return mFragmentedLoadPromise->get_future();
}
}  // namespace android::chre