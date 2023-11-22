/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define LOG_TAG "NanohubHAL"

#include <cassert>
#include <cerrno>
#include <cinttypes>

#include <endian.h>

#include <vector>

#include <utils/Log.h>

#include <endian.h>

#include <hardware/context_hub.h>
#include "nanohub_perdevice.h"
#include "system_comms.h"
#include "nanohubhal.h"

namespace android {

namespace nanohub {

static void readAppName(MessageBuf &buf, hub_app_name_t &name) {
    name.id = buf.readU64();
}

static void writeAppName(MessageBuf &buf, const hub_app_name_t &name) {
    buf.writeU64(name.id);
}

static void readNanohubAppInfo(MessageBuf &buf, NanohubAppInfo &info) {
    size_t pos = buf.getPos();
    readAppName(buf, info.name);
    info.version = buf.readU32();
    info.flashUse = buf.readU32();
    info.ramUse = buf.readU32();
    if ((buf.getPos() - pos) != sizeof(info)) {
        ALOGE("%s: failed to read object", __func__);
    }
}

static void readNanohubMemInfo(MessageBuf &buf,  NanohubMemInfo &mi) {
    size_t pos = buf.getPos();
    mi.flashSz = buf.readU32();
    mi.blSz = buf.readU32();
    mi.osSz = buf.readU32();
    mi.sharedSz = buf.readU32();
    mi.eeSz = buf.readU32();
    mi.ramSz = buf.readU32();

    mi.blUse = buf.readU32();
    mi.osUse = buf.readU32();
    mi.sharedUse = buf.readU32();
    mi.eeUse = buf.readU32();
    mi.ramUse = buf.readU32();
    if ((buf.getPos() - pos) != sizeof(mi)) {
        ALOGE("%s: failed to read object", __func__);
    }
}

NanohubRsp::NanohubRsp(MessageBuf &buf, bool no_status) {
    // all responses start with command
    // most of them have 4-byte status (result code)
    cmd = buf.readU8();
    if (!buf.getSize()) {
        status = -EINVAL;
    } else if (no_status) {
        status = 0;
    } else {
        status = buf.readU32();
    }
}

int SystemComm::sendToSystem(const void *data, size_t len) {
    if (NanoHub::messageTracingEnabled()) {
        dumpBuffer("HAL -> SYS", getSystem()->mHostIfAppName, 0, data, len);
    }
    return NanoHub::sendToDevice(&getSystem()->mHostIfAppName, data, len);
}

int SystemComm::AppInfoSession::setup(const hub_message_t *) {
    Mutex::Autolock _l(mLock);
    int suggestedSize = mAppInfo.size() ? mAppInfo.size() : 20;

    mAppInfo.clear();
    mAppInfo.reserve(suggestedSize);
    setState(SESSION_USER);

    return requestNext();
}

inline hub_app_name_t deviceAppNameToHost(const hub_app_name_t src) {
    hub_app_name_t res = { .id = le64toh(src.id) };
    return res;
}

inline hub_app_name_t hostAppNameToDevice(const hub_app_name_t src) {
    hub_app_name_t res = { .id = htole64(src.id) };
    return res;
}

int SystemComm::AppInfoSession::handleRx(MessageBuf &buf)
{
    Mutex::Autolock _l(mLock);

    NanohubRsp rsp(buf, true);
    if (rsp.cmd != NANOHUB_QUERY_APPS) {
        return 1;
    }
    size_t len = buf.getRoom();
    if (len != sizeof(NanohubAppInfo) && len) {
        ALOGE("%s: Invalid data size; have %zu, need %zu", __func__,
              len, sizeof(NanohubAppInfo));
        return -EINVAL;
    }
    if (getState() != SESSION_USER) {
        ALOGE("%s: Invalid state; have %d, need %d", __func__, getState(), SESSION_USER);
        return -EINVAL;
    }
    if (len) {
        NanohubAppInfo info;
        readNanohubAppInfo(buf, info);
        hub_app_info appInfo;
        appInfo.num_mem_ranges = 0;
        if (info.flashUse != NANOHUB_MEM_SZ_UNKNOWN) {
            mem_range_t &range = appInfo.mem_usage[appInfo.num_mem_ranges++];
            range.type = HUB_MEM_TYPE_MAIN;
            range.total_bytes = info.flashUse;
        }
        if (info.ramUse != NANOHUB_MEM_SZ_UNKNOWN) {
            mem_range_t &range = appInfo.mem_usage[appInfo.num_mem_ranges++];
            range.type = HUB_MEM_TYPE_RAM;
            range.total_bytes = info.ramUse;
        }

        appInfo.app_name = info.name;
        appInfo.version = info.version;

        mAppInfo.push_back(appInfo);
        return requestNext();
    } else {
        sendToApp(CONTEXT_HUB_QUERY_APPS,
                        static_cast<const void *>(mAppInfo.data()),
                        mAppInfo.size() * sizeof(mAppInfo[0]));
        complete();
    }

    return 0;
}

int SystemComm::AppInfoSession::requestNext()
{
    char data[MAX_RX_PACKET];
    MessageBuf buf(data, sizeof(data));
    buf.writeU8(NANOHUB_QUERY_APPS);
    buf.writeU32(mAppInfo.size());
    return sendToSystem(buf.getData(), buf.getPos());
}

int SystemComm::GlobalSession::setup(const hub_message_t *) {
    Mutex::Autolock _l(mLock);

    setState(SESSION_USER);

    return 0;
}

int SystemComm::GlobalSession::handleRx(MessageBuf &buf)
{
    Mutex::Autolock _l(mLock);

    NanohubRsp rsp(buf);
    if (rsp.cmd != NANOHUB_REBOOT) {
        return 1;
    }

    ALOGW("Nanohub reboot status [UNSOLICITED]: %08" PRIX32, rsp.status);
    sendToApp(CONTEXT_HUB_OS_REBOOT, &rsp.status, sizeof(rsp.status));

    return 0;
}

int SystemComm::MemInfoSession::setup(const hub_message_t *)
{
    Mutex::Autolock _l(mLock);
    char data[MAX_RX_PACKET];
    MessageBuf buf(data, sizeof(data));
    buf.writeU8(NANOHUB_QUERY_MEMINFO);

    setState(SESSION_USER);
    return sendToSystem(buf.getData(), buf.getPos());
}

int SystemComm::MemInfoSession::handleRx(MessageBuf &buf)
{
    Mutex::Autolock _l(mLock);
    NanohubRsp rsp(buf, true);

    if (rsp.cmd != NANOHUB_QUERY_MEMINFO)
        return 1;

    size_t len = buf.getRoom();

    if (len != sizeof(NanohubMemInfo)) {
        ALOGE("%s: Invalid data size: %zu", __func__, len);
        return -EINVAL;
    }
    if (getState() != SESSION_USER) {
        ALOGE("%s: Invalid state; have %d, need %d", __func__, getState(), SESSION_USER);
        return -EINVAL;
    }

    NanohubMemInfo mi;
    readNanohubMemInfo(buf, mi);
    std::vector<mem_range_t> ranges;
    ranges.reserve(4);

    //if each is valid, copy to output area
    if (mi.sharedSz != NANOHUB_MEM_SZ_UNKNOWN &&
        mi.sharedUse != NANOHUB_MEM_SZ_UNKNOWN)
        ranges.push_back({
            .type = HUB_MEM_TYPE_MAIN,
            .total_bytes = mi.sharedSz,
            .free_bytes = mi.sharedSz - mi.sharedUse,
        });

    if (mi.osSz != NANOHUB_MEM_SZ_UNKNOWN &&
        mi.osUse != NANOHUB_MEM_SZ_UNKNOWN)
        ranges.push_back({
            .type = HUB_MEM_TYPE_OS,
            .total_bytes = mi.osSz,
            .free_bytes = mi.osSz - mi.osUse,
        });

    if (mi.eeSz != NANOHUB_MEM_SZ_UNKNOWN &&
        mi.eeUse != NANOHUB_MEM_SZ_UNKNOWN)
        ranges.push_back({
            .type = HUB_MEM_TYPE_EEDATA,
            .total_bytes = mi.eeSz,
            .free_bytes = mi.eeSz - mi.eeUse,
        });

    if (mi.ramSz != NANOHUB_MEM_SZ_UNKNOWN &&
        mi.ramUse != NANOHUB_MEM_SZ_UNKNOWN)
        ranges.push_back({
            .type = HUB_MEM_TYPE_RAM,
            .total_bytes = mi.ramSz,
            .free_bytes = mi.ramSz - mi.ramUse,
        });

    //send it out
    sendToApp(CONTEXT_HUB_QUERY_MEMORY,
              static_cast<const void *>(ranges.data()),
              ranges.size() * sizeof(ranges[0]));

    complete();

    return 0;
}

int SystemComm::AppMgmtSession::setup(const hub_message_t *appMsg)
{
    Mutex::Autolock _l(mLock);

    char data[MAX_RX_PACKET];
    MessageBuf buf(data, sizeof(data));
    const uint8_t *msgData = static_cast<const uint8_t*>(appMsg->message);

    mCmd = appMsg->message_type;
    mLen = appMsg->message_len;
    mPos = 0;

    switch (mCmd) {
    case  CONTEXT_HUB_APPS_ENABLE:
        return setupMgmt(appMsg, NANOHUB_EXT_APPS_ON);
    case  CONTEXT_HUB_APPS_DISABLE:
        return setupMgmt(appMsg, NANOHUB_EXT_APPS_OFF);
    case  CONTEXT_HUB_UNLOAD_APP:
        return setupMgmt(appMsg, NANOHUB_EXT_APP_DELETE);
    case  CONTEXT_HUB_LOAD_OS:
    case  CONTEXT_HUB_LOAD_APP:
        mData.clear();
        mData = std::vector<uint8_t>(msgData, msgData + mLen);
        setState(TRANSFER);

        buf.writeU8(NANOHUB_START_UPLOAD);
        buf.writeU8(mCmd == CONTEXT_HUB_LOAD_OS ? 1 : 0);
        buf.writeU32(mLen);
        return sendToSystem(buf.getData(), buf.getPos());

    case  CONTEXT_HUB_OS_REBOOT:
        setState(REBOOT);
        buf.writeU8(NANOHUB_REBOOT);
        return sendToSystem(buf.getData(), buf.getPos());
    }

    return -EINVAL;
}

int SystemComm::AppMgmtSession::setupMgmt(const hub_message_t *appMsg, uint32_t cmd)
{
    const hub_app_name_t &appName = *static_cast<const hub_app_name_t*>(appMsg->message);
    if (appMsg->message_len != sizeof(appName)) {
        return -EINVAL;
    }

    char data[MAX_RX_PACKET];
    MessageBuf buf(data, sizeof(data));
    buf.writeU8(cmd);
    writeAppName(buf, appName);
    setState(MGMT);

    return sendToSystem(buf.getData(), buf.getPos());
}

int SystemComm::AppMgmtSession::handleRx(MessageBuf &buf)
{
    int ret = 0;
    Mutex::Autolock _l(mLock);
    NanohubRsp rsp(buf);

    switch (getState()) {
    case TRANSFER:
        ret = handleTransfer(rsp);
        break;
    case FINISH:
        ret = handleFinish(rsp);
        break;
    case RELOAD:
        ret = handleReload(rsp);
        break;
    case REBOOT:
        ret = handleReboot(rsp);
        break;
    case MGMT:
        ret = handleMgmt(rsp);
        break;
    }

    return ret;
}

int SystemComm::AppMgmtSession::handleTransfer(NanohubRsp &rsp)
{
    if (rsp.cmd != NANOHUB_CONT_UPLOAD && rsp.cmd != NANOHUB_START_UPLOAD)
        return 1;

    char data[MAX_RX_PACKET];
    MessageBuf buf(data, sizeof(data));

    static_assert(NANOHUB_UPLOAD_CHUNK_SZ_MAX <= (MAX_RX_PACKET-5),
                  "Invalid chunk size");

    if (mPos < mLen) {
        uint32_t chunkSize = mLen - mPos;

        if (chunkSize > NANOHUB_UPLOAD_CHUNK_SZ_MAX) {
            chunkSize = NANOHUB_UPLOAD_CHUNK_SZ_MAX;
        }

        buf.writeU8(NANOHUB_CONT_UPLOAD);
        buf.writeU32(mPos);
        buf.writeRaw(&mData[mPos], chunkSize);
        mPos += chunkSize;
    } else {
        buf.writeU8(NANOHUB_FINISH_UPLOAD);
        setState(FINISH);
    }

    return sendToSystem(buf.getData(), buf.getPos());
}

int SystemComm::AppMgmtSession::handleFinish(NanohubRsp &rsp)
{
    if (rsp.cmd != NANOHUB_FINISH_UPLOAD)
        return 1;

    int ret = 0;
    const bool success = rsp.status != 0;
    mData.clear();

    if (success) {
        char data[MAX_RX_PACKET];
        MessageBuf buf(data, sizeof(data));
        // until app header is passed, we don't know who to start, so we reboot
        buf.writeU8(NANOHUB_REBOOT);
        setState(RELOAD);
        ret = sendToSystem(buf.getData(), buf.getPos());
    } else {
        int32_t result = NANOHUB_APP_NOT_LOADED;

        sendToApp(mCmd, &result, sizeof(result));
        complete();
    }

    return ret;
}

/* reboot notification, when triggered as part of App reload sequence */
int SystemComm::AppMgmtSession::handleReload(NanohubRsp &rsp)
{
    int32_t result = NANOHUB_APP_LOADED;

    ALOGI("Nanohub reboot status [NEW APP START]: %08" PRIX32, rsp.status);

    sendToApp(mCmd, &result, sizeof(result));

    // in addition to sending response to the CONTEXT_HUB_LOAD_APP command,
    // we should send unsolicited reboot notification;
    // I choose to do it here rather than delegate it to global session
    // because I want the log to clearly differentiate between UNSOLICITED reboots
    // (meaning FW faults) and REQUESTED reboots.
    sendToApp(CONTEXT_HUB_OS_REBOOT, &rsp.status, sizeof(rsp.status));

    complete();

    return 0;
}

/* reboot notification, when triggered by App request */
int SystemComm::AppMgmtSession::handleReboot(NanohubRsp &rsp)
{
    ALOGI("Nanohub reboot status [USER REQ]: %08" PRIX32, rsp.status);

    sendToApp(mCmd, &rsp.status, sizeof(rsp.status));
    complete();

    return 0;
}

int SystemComm::AppMgmtSession::handleMgmt(NanohubRsp &rsp)
{
    Mutex::Autolock _l(mLock);
    bool valid = false;

    ALOGI("Nanohub MGMT response: CMD=%02X; STATUS=%08" PRIX32, rsp.cmd, rsp.status);

    switch (rsp.cmd) {
    case NANOHUB_EXT_APPS_OFF:
        valid = mCmd == CONTEXT_HUB_APPS_DISABLE;
        break;
    case NANOHUB_EXT_APPS_ON:
        valid = mCmd == CONTEXT_HUB_APPS_ENABLE;
        break;
    case NANOHUB_EXT_APP_DELETE:
        valid = mCmd == CONTEXT_HUB_UNLOAD_APP;
        break;
    default:
        return 1;
    }

    if (!valid) {
        ALOGE("Invalid response for this state: APP CMD=%02X", mCmd);
        return -EINVAL;
    }

    sendToApp(mCmd, &rsp.status, sizeof(rsp.status));
    complete();

    return 0;
}

int SystemComm::KeyInfoSession::setup(const hub_message_t *) {
    Mutex::Autolock _l(mLock);
    mRsaKeyData.clear();
    setState(SESSION_USER);
    mStatus = -EBUSY;
    return requestRsaKeys();
}

int SystemComm::KeyInfoSession::handleRx(MessageBuf &buf)
{
    Mutex::Autolock _l(mLock);
    NanohubRsp rsp(buf, true);

    if (getState() != SESSION_USER) {
        // invalid state
        mStatus = -EFAULT;
        return mStatus;
    }

    if (buf.getRoom()) {
        mRsaKeyData.insert(mRsaKeyData.end(),
                           buf.getData() + buf.getPos(),
                           buf.getData() + buf.getSize());
        return requestRsaKeys();
    } else {
        mStatus = 0;
        complete();
        return 0;
    }
}

int SystemComm::KeyInfoSession::requestRsaKeys(void)
{
    char data[MAX_RX_PACKET];
    MessageBuf buf(data, sizeof(data));

    buf.writeU8(NANOHUB_QUERY_APPS);
    buf.writeU32(mRsaKeyData.size());

    return sendToSystem(buf.getData(), buf.getPos());
}

int SystemComm::doHandleRx(const nano_message *msg)
{
    //we only care for messages from HostIF
    if (msg->hdr.app_name != mHostIfAppName)
        return 1;

    //they must all be at least 1 byte long
    if (!msg->hdr.len) {
        return -EINVAL;
    }
    MessageBuf buf(reinterpret_cast<const char*>(msg->data), msg->hdr.len);
    if (NanoHub::messageTracingEnabled()) {
        dumpBuffer("SYS -> HAL", mHostIfAppName, 0, buf.getData(), buf.getSize());
    }
    int status = mSessions.handleRx(buf);
    if (status) {
        // provide default handler for any system message, that is not properly handled
        dumpBuffer(status > 0 ? "HAL (not handled)" : "HAL (error)",
                   mHostIfAppName, 0, buf.getData(), buf.getSize(), status);
        status = status > 0 ? 0 : status;
    }

    return status;
}

int SystemComm::SessionManager::handleRx(MessageBuf &buf)
{
    int status = 1;

    // pass message to all active sessions, in arbitrary order
    // 1st session that handles the message terminates the loop
    for (auto pos = sessions_.begin();
         pos != sessions_.end() && status > 0; next(pos)) {
        Session *session = pos->second;
        status = session->handleRx(buf);
        if (status < 0) {
            session->complete();
        }
    }
    if (status > 0) {
        status = mGlobal.handleRx(buf);
    }

    return status;
}

int SystemComm::doHandleTx(const hub_message_t *appMsg)
{
    int status = 0;

    switch (appMsg->message_type) {
    case CONTEXT_HUB_LOAD_APP:
        if (!mKeySession.haveKeys()) {
            status = mSessions.setup_and_add(CONTEXT_HUB_LOAD_APP, &mKeySession, appMsg);
            if (status < 0) {
                break;
            }
            mKeySession.wait();
            status = mKeySession.getStatus();
            if (status < 0) {
                break;
            }
        }
        status = mSessions.setup_and_add(CONTEXT_HUB_LOAD_APP, &mAppMgmtSession, appMsg);
        break;
    case CONTEXT_HUB_APPS_ENABLE:
    case CONTEXT_HUB_APPS_DISABLE:
    case CONTEXT_HUB_UNLOAD_APP:
        // all APP-modifying commands share session key, to ensure they can't happen at the same time
        status = mSessions.setup_and_add(CONTEXT_HUB_LOAD_APP, &mAppMgmtSession, appMsg);
        break;

    case CONTEXT_HUB_QUERY_APPS:
        status = mSessions.setup_and_add(CONTEXT_HUB_QUERY_APPS, &mAppInfoSession, appMsg);
        break;

    case CONTEXT_HUB_QUERY_MEMORY:
        status = mSessions.setup_and_add(CONTEXT_HUB_QUERY_MEMORY, &mMemInfoSession, appMsg);
        break;

    default:
        ALOGW("Unknown os message type %u\n", appMsg->message_type);
        return -EINVAL;
    }

   return status;
}

}; // namespace nanohub

}; // namespace android
