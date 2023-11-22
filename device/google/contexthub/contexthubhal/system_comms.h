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

#ifndef _NANOHUB_SYSTEM_COMMS_H_
#define _NANOHUB_SYSTEM_COMMS_H_

#include <utils/Condition.h>
#include <utils/Mutex.h>

#include <map>
#include <vector>

#include <hardware/context_hub.h>
#include "nanohubhal.h"
#include "message_buf.h"

//rx: return 0 if handled, > 0 if not handled, < 0 if error happened

#define MSG_HANDLED 0

//messages to the HostIf nanoapp & their replies (mesages and replies both begin with u8 message_type)
#define NANOHUB_EXT_APPS_ON        0 // () -> (char success)
#define NANOHUB_EXT_APPS_OFF       1 // () -> (char success)
#define NANOHUB_EXT_APP_DELETE     2 // (u64 name) -> (char success)    //idempotent
#define NANOHUB_QUERY_MEMINFO      3 // () -> (mem_info)
#define NANOHUB_QUERY_APPS         4 // (u32 idxStart) -> (app_info[idxStart] OR EMPTY IF NO MORE)
#define NANOHUB_QUERY_RSA_KEYS     5 // (u32 byteOffset) -> (u8 data[1 or more bytes] OR EMPTY IF NO MORE)
#define NANOHUB_START_UPLOAD       6 // (char isOs, u32 totalLenToTx) -> (char success)
#define NANOHUB_CONT_UPLOAD        7 // (u32 offset, u8 data[]) -> (char success)
#define NANOHUB_FINISH_UPLOAD      8 // () -> (char success)
#define NANOHUB_REBOOT             9 // () -> (char success)

// Custom defined private messages
#define CONTEXT_HUB_LOAD_OS (CONTEXT_HUB_TYPE_PRIVATE_MSG_BASE + 1)


#define NANOHUB_APP_NOT_LOADED  (-1)
#define NANOHUB_APP_LOADED      (0)

#define NANOHUB_UPLOAD_CHUNK_SZ_MAX 64
#define NANOHUB_MEM_SZ_UNKNOWN      0xFFFFFFFFUL

namespace android {

namespace nanohub {

int system_comms_handle_rx(const nano_message *msg);
int system_comms_handle_tx(const hub_message_t *outMsg);

struct NanohubAppInfo {
    hub_app_name_t name;
    uint32_t version, flashUse, ramUse;
} __attribute__((packed));

struct NanohubMemInfo {
    //sizes
    uint32_t flashSz, blSz, osSz, sharedSz, eeSz;
    uint32_t ramSz;

    //use
    uint32_t blUse, osUse, sharedUse, eeUse;
    uint32_t ramUse;
} __attribute__((packed));

struct NanohubRsp {
    uint32_t cmd;
    int32_t status;
    NanohubRsp(MessageBuf &buf, bool no_status = false);
};

inline bool operator == (const hub_app_name_t &a, const hub_app_name_t &b) {
    return a.id == b.id;
}

inline bool operator != (const hub_app_name_t &a, const hub_app_name_t &b) {
    return !(a == b);
}

class SystemComm {
private:

    /*
     * Nanohub HAL sessions
     *
     * Session is an object that can group several message exchanges with FW,
     * maintain state, and be waited for completion by someone else.
     *
     * As of this moment, since all sessions are triggered by client thread,
     * and all the exchange is happening in local worker thread, it is only possible
     * for client thread to wait on session completion.
     * Allowing sessions to wait on each other will require a worker thread pool.
     * It is now unnecessary, and not implemented.
     */
    class ISession {
    public:
        virtual int setup(const hub_message_t *app_msg) = 0;
        virtual int handleRx(MessageBuf &buf) = 0;
        virtual int getState() const = 0; // FSM state
        virtual int getStatus() const = 0; // execution status (result code)
        virtual ~ISession() {}
    };

    class SessionManager;

    class Session : public ISession {
        friend class SessionManager;

        mutable Mutex mDoneLock; // controls condition and state transitions
        Condition mDoneWait;
        volatile int mState;

    protected:
        mutable Mutex mLock; // serializes message handling
        int32_t mStatus;

        enum {
            SESSION_INIT = 0,
            SESSION_DONE = 1,
            SESSION_USER = 2,
        };

        void complete() {
            Mutex::Autolock _l(mDoneLock);
            if (mState != SESSION_DONE) {
                mState = SESSION_DONE;
                mDoneWait.broadcast();
            }
        }
        void setState(int state) {
            if (state == SESSION_DONE) {
                complete();
            } else {
                Mutex::Autolock _l(mDoneLock);
                mState = state;
            }
        }
    public:
        Session() { mState = SESSION_INIT; mStatus = -1; }
        int getStatus() const {
            Mutex::Autolock _l(mLock);
            return mStatus;
        }
        int wait() {
            Mutex::Autolock _l(mDoneLock);
            while (mState != SESSION_DONE) {
                mDoneWait.wait(mDoneLock);
            }
            return 0;
        }
        virtual int getState() const override {
            Mutex::Autolock _l(mDoneLock);
            return mState;
        }
        virtual bool isDone() const {
            Mutex::Autolock _l(mDoneLock);
            return mState == SESSION_DONE;
        }
        virtual bool isRunning() const {
            Mutex::Autolock _l(mDoneLock);
            return mState > SESSION_DONE;
        }
    };

    class AppMgmtSession : public Session {
        enum {
            TRANSFER = SESSION_USER,
            FINISH,
            RELOAD,
            REBOOT,
            MGMT,
        };
        uint32_t mCmd; // UPLOAD_APP | UPPLOAD_OS
        uint32_t mResult;
        std::vector<uint8_t> mData;
        uint32_t mLen;
        uint32_t mPos;

        int setupMgmt(const hub_message_t *appMsg, uint32_t cmd);
        int handleTransfer(NanohubRsp &rsp);
        int handleFinish(NanohubRsp &rsp);
        int handleReload(NanohubRsp &rsp);
        int handleReboot(NanohubRsp &rsp);
        int handleMgmt(NanohubRsp &rsp);
    public:
        AppMgmtSession() {
            mCmd = 0;
            mResult = 0;
            mPos = 0;
            mLen = 0;
        }
        virtual int handleRx(MessageBuf &buf) override;
        virtual int setup(const hub_message_t *app_msg) override;
    };

    class MemInfoSession : public Session {
    public:
        virtual int setup(const hub_message_t *app_msg) override;
        virtual int handleRx(MessageBuf &buf) override;
    };

    class KeyInfoSession  : public Session {
        std::vector<uint8_t> mRsaKeyData;
        int requestRsaKeys(void);
    public:
        virtual int setup(const hub_message_t *) override;
        virtual int handleRx(MessageBuf &buf) override;
        bool haveKeys() const {
            Mutex::Autolock _l(mLock);
            return mRsaKeyData.size() > 0 && !isRunning();
        }
    };

    class AppInfoSession : public Session {
        std::vector<hub_app_info> mAppInfo;
        int requestNext();
    public:
        virtual int setup(const hub_message_t *) override;
        virtual int handleRx(MessageBuf &buf) override;
    };

    class GlobalSession : public Session {
    public:
        virtual int setup(const hub_message_t *) override;
        virtual int handleRx(MessageBuf &buf) override;
    };

    class SessionManager {
        typedef std::map<int, Session* > SessionMap;

        Mutex lock;
        SessionMap sessions_;
        GlobalSession mGlobal;

        void next(SessionMap::iterator &pos)
        {
            Mutex::Autolock _l(lock);
            pos->second->isDone() ? pos = sessions_.erase(pos) : ++pos;
        }

    public:
        SessionManager() {
            mGlobal.setup(nullptr);
        }
        int handleRx(MessageBuf &buf);
        int setup_and_add(int id, Session *session, const hub_message_t *appMsg) {
            Mutex::Autolock _l(lock);
            if (sessions_.count(id) == 0 && !session->isRunning()) {
                int ret = session->setup(appMsg);
                if (ret < 0) {
                    session->complete();
                } else {
                    sessions_[id] = session;
                }
                return ret;
            }
            return -EBUSY;
        }

    } mSessions;

    const hub_app_name_t mHostIfAppName = {
        .id = NANO_APP_ID(NANOAPP_VENDOR_GOOGLE, 0)
    };

    static SystemComm *getSystem() {
        // this is thread-safe in c++11
        static SystemComm theInstance;
        return &theInstance;
    }

    SystemComm () = default;
    ~SystemComm() = default;

    int doHandleTx(const hub_message_t *txMsg);
    int doHandleRx(const nano_message *rxMsg);

    static void sendToApp(uint32_t typ, const void *data, uint32_t len) {
        if (NanoHub::messageTracingEnabled()) {
            dumpBuffer("HAL -> APP", get_hub_info()->os_app_name, typ, data, len);
        }
        NanoHub::sendToApp(&get_hub_info()->os_app_name, typ, data, len);
    }
    static int sendToSystem(const void *data, size_t len);

    KeyInfoSession mKeySession;
    AppMgmtSession mAppMgmtSession;
    AppInfoSession mAppInfoSession;
    MemInfoSession mMemInfoSession;

public:
    static int handleTx(const hub_message_t *txMsg) {
        return getSystem()->doHandleTx(txMsg);
    }
    static int handleRx(const nano_message *rxMsg) {
        return getSystem()->doHandleRx(rxMsg);
    }
};

}; // namespace nanohub

}; // namespace android

#endif
