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

#define LOG_TAG "TrafficControllerJni"

#include "TrafficController.h"

#include "netd.h"

#include <jni.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <netjniutils/netjniutils.h>
#include <net/if.h>
#include <private/android_filesystem_config.h>
#include <unistd.h>
#include <vector>


using android::net::TrafficController;
using android::netdutils::Status;

using UidOwnerMatchType::PENALTY_BOX_MATCH;
using UidOwnerMatchType::HAPPY_BOX_MATCH;

static android::net::TrafficController mTc;

namespace android {

#define CHECK_LOG(status) \
  do { \
    if (!isOk(status)) \
      ALOGE("%s failed, error code = %d", __func__, status.code()); \
  } while (0)

static void native_init(JNIEnv* env, jclass clazz, jboolean startSkDestroyListener) {
  Status status = mTc.start(startSkDestroyListener);
  CHECK_LOG(status);
  if (!isOk(status)) {
    uid_t uid = getuid();
    ALOGE("BpfNetMaps jni init failure as uid=%d", uid);
    // TODO: Fix tests to not use this jni lib, so we can unconditionally abort()
    if (uid == AID_SYSTEM || uid == AID_NETWORK_STACK) abort();
  }
}

static jint native_addNaughtyApp(JNIEnv* env, jobject self, jint uid) {
  const uint32_t appUids = static_cast<uint32_t>(abs(uid));
  Status status = mTc.updateUidOwnerMap(appUids, PENALTY_BOX_MATCH,
      TrafficController::IptOp::IptOpInsert);
  CHECK_LOG(status);
  return (jint)status.code();
}

static jint native_removeNaughtyApp(JNIEnv* env, jobject self, jint uid) {
  const uint32_t appUids = static_cast<uint32_t>(abs(uid));
  Status status = mTc.updateUidOwnerMap(appUids, PENALTY_BOX_MATCH,
      TrafficController::IptOp::IptOpDelete);
  CHECK_LOG(status);
  return (jint)status.code();
}

static jint native_addNiceApp(JNIEnv* env, jobject self, jint uid) {
  const uint32_t appUids = static_cast<uint32_t>(abs(uid));
  Status status = mTc.updateUidOwnerMap(appUids, HAPPY_BOX_MATCH,
      TrafficController::IptOp::IptOpInsert);
  CHECK_LOG(status);
  return (jint)status.code();
}

static jint native_removeNiceApp(JNIEnv* env, jobject self, jint uid) {
  const uint32_t appUids = static_cast<uint32_t>(abs(uid));
  Status status = mTc.updateUidOwnerMap(appUids, HAPPY_BOX_MATCH,
      TrafficController::IptOp::IptOpDelete);
  CHECK_LOG(status);
  return (jint)status.code();
}

static jint native_setChildChain(JNIEnv* env, jobject self, jint childChain, jboolean enable) {
  auto chain = static_cast<ChildChain>(childChain);
  int res = mTc.toggleUidOwnerMap(chain, enable);
  if (res) ALOGE("%s failed, error code = %d", __func__, res);
  return (jint)res;
}

static jint native_replaceUidChain(JNIEnv* env, jobject self, jstring name, jboolean isAllowlist,
                                   jintArray jUids) {
    const ScopedUtfChars chainNameUtf8(env, name);
    if (chainNameUtf8.c_str() == nullptr) return -EINVAL;
    const std::string chainName(chainNameUtf8.c_str());

    ScopedIntArrayRO uids(env, jUids);
    if (uids.get() == nullptr) return -EINVAL;

    size_t size = uids.size();
    static_assert(sizeof(*(uids.get())) == sizeof(int32_t));
    std::vector<int32_t> data ((int32_t *)&uids[0], (int32_t*)&uids[size]);
    int res = mTc.replaceUidOwnerMap(chainName, isAllowlist, data);
    if (res) ALOGE("%s failed, error code = %d", __func__, res);
    return (jint)res;
}

static jint native_setUidRule(JNIEnv* env, jobject self, jint childChain, jint uid,
                              jint firewallRule) {
    auto chain = static_cast<ChildChain>(childChain);
    auto rule = static_cast<FirewallRule>(firewallRule);
    FirewallType fType = mTc.getFirewallType(chain);

    int res = mTc.changeUidOwnerRule(chain, uid, rule, fType);
    if (res) ALOGE("%s failed, error code = %d", __func__, res);
    return (jint)res;
}

static jint native_addUidInterfaceRules(JNIEnv* env, jobject self, jstring ifName,
                                        jintArray jUids) {
    // Null ifName is a wildcard to allow apps to receive packets on all interfaces and ifIndex is
    // set to 0.
    int ifIndex = 0;
    if (ifName != nullptr) {
        const ScopedUtfChars ifNameUtf8(env, ifName);
        const std::string interfaceName(ifNameUtf8.c_str());
        ifIndex = if_nametoindex(interfaceName.c_str());
    }

    ScopedIntArrayRO uids(env, jUids);
    if (uids.get() == nullptr) return -EINVAL;

    size_t size = uids.size();
    static_assert(sizeof(*(uids.get())) == sizeof(int32_t));
    std::vector<int32_t> data ((int32_t *)&uids[0], (int32_t*)&uids[size]);
    Status status = mTc.addUidInterfaceRules(ifIndex, data);
    CHECK_LOG(status);
    return (jint)status.code();
}

static jint native_removeUidInterfaceRules(JNIEnv* env, jobject self, jintArray jUids) {
    ScopedIntArrayRO uids(env, jUids);
    if (uids.get() == nullptr) return -EINVAL;

    size_t size = uids.size();
    static_assert(sizeof(*(uids.get())) == sizeof(int32_t));
    std::vector<int32_t> data ((int32_t *)&uids[0], (int32_t*)&uids[size]);
    Status status = mTc.removeUidInterfaceRules(data);
    CHECK_LOG(status);
    return (jint)status.code();
}

static jint native_updateUidLockdownRule(JNIEnv* env, jobject self, jint uid, jboolean add) {
    Status status = mTc.updateUidLockdownRule(uid, add);
    CHECK_LOG(status);
    return (jint)status.code();
}

static jint native_swapActiveStatsMap(JNIEnv* env, jobject self) {
    Status status = mTc.swapActiveStatsMap();
    CHECK_LOG(status);
    return (jint)status.code();
}

static void native_setPermissionForUids(JNIEnv* env, jobject self, jint permission,
                                        jintArray jUids) {
    ScopedIntArrayRO uids(env, jUids);
    if (uids.get() == nullptr) return;

    size_t size = uids.size();
    static_assert(sizeof(*(uids.get())) == sizeof(uid_t));
    std::vector<uid_t> data ((uid_t *)&uids[0], (uid_t*)&uids[size]);
    mTc.setPermissionForUids(permission, data);
}

static void native_dump(JNIEnv* env, jobject self, jobject javaFd, jboolean verbose) {
    int fd = netjniutils::GetNativeFileDescriptor(env, javaFd);
    if (fd < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid file descriptor");
        return;
    }
    mTc.dump(fd, verbose);
}

static jint native_synchronizeKernelRCU(JNIEnv* env, jobject self) {
    return -bpf::synchronizeKernelRCU();
}

/*
 * JNI registration.
 */
// clang-format off
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    {"native_init", "(Z)V",
    (void*)native_init},
    {"native_addNaughtyApp", "(I)I",
    (void*)native_addNaughtyApp},
    {"native_removeNaughtyApp", "(I)I",
    (void*)native_removeNaughtyApp},
    {"native_addNiceApp", "(I)I",
    (void*)native_addNiceApp},
    {"native_removeNiceApp", "(I)I",
    (void*)native_removeNiceApp},
    {"native_setChildChain", "(IZ)I",
    (void*)native_setChildChain},
    {"native_replaceUidChain", "(Ljava/lang/String;Z[I)I",
    (void*)native_replaceUidChain},
    {"native_setUidRule", "(III)I",
    (void*)native_setUidRule},
    {"native_addUidInterfaceRules", "(Ljava/lang/String;[I)I",
    (void*)native_addUidInterfaceRules},
    {"native_removeUidInterfaceRules", "([I)I",
    (void*)native_removeUidInterfaceRules},
    {"native_updateUidLockdownRule", "(IZ)I",
    (void*)native_updateUidLockdownRule},
    {"native_swapActiveStatsMap", "()I",
    (void*)native_swapActiveStatsMap},
    {"native_setPermissionForUids", "(I[I)V",
    (void*)native_setPermissionForUids},
    {"native_dump", "(Ljava/io/FileDescriptor;Z)V",
    (void*)native_dump},
    {"native_synchronizeKernelRCU", "()I",
    (void*)native_synchronizeKernelRCU},
};
// clang-format on

int register_com_android_server_BpfNetMaps(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "android/net/connectivity/com/android/server/BpfNetMaps",
                                    gMethods, NELEM(gMethods));
}

}; // namespace android
