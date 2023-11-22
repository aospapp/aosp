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

#ifndef _NANOHUB_HAL_H_
#define _NANOHUB_HAL_H_

#include <pthread.h>

#include <hardware/context_hub.h>
#include <utils/Mutex.h>

#define NANOAPP_VENDOR_GOOGLE NANOAPP_VENDOR("Googl")

//as per protocol
#define MAX_RX_PACKET           128
#define APP_FROM_HOST_EVENT_ID  0x000000F8

namespace android {

namespace nanohub {

void dumpBuffer(const char *pfx, const hub_app_name_t &appId, uint32_t evtId, const void *data, size_t len, int status = 0);

struct nano_message_hdr {
    uint32_t event_id;
    hub_app_name_t app_name;
    uint8_t len;
} __attribute__((packed));

struct nano_message {
    nano_message_hdr hdr;
    uint8_t data[MAX_RX_PACKET];
} __attribute__((packed));

class NanoHub {
    Mutex mLock;
    context_hub_callback *mMsgCbkFunc;
    int mThreadClosingPipe[2];
    int mFd; // [0] is read end
    void * mMsgCbkData;
    pthread_t mWorkerThread;

    NanoHub() {
        reset();
    }

    void reset() {
        mThreadClosingPipe[0] = -1;
        mThreadClosingPipe[1] = -1;
        mFd = -1;
        mMsgCbkData = nullptr;
        mMsgCbkFunc = nullptr;
        mWorkerThread = 0;
    }

    static void* run(void *);
    void* doRun();

    int openHub();
    int closeHub();

    static NanoHub *hubInstance() {
        static NanoHub theHub;
        return &theHub;
    }

    int doSubscribeMessages(uint32_t hub_id, context_hub_callback *cbk, void *cookie);
    int doSendToNanohub(uint32_t hub_id, const hub_message_t *msg);
    int doSendToDevice(const hub_app_name_t *name, const void *data, uint32_t len);
    void doSendToApp(const hub_app_name_t *name, uint32_t typ, const void *data, uint32_t len);

    static constexpr unsigned int FL_MESSAGE_TRACING = 1;

    unsigned int mFlags = 0;

public:

    // debugging interface

    static bool messageTracingEnabled() {
        return hubInstance()->mFlags & FL_MESSAGE_TRACING;
    }
    static unsigned int getDebugFlags() {
        return hubInstance()->mFlags;
    }
    static void setDebugFlags(unsigned int flags) {
        hubInstance()->mFlags = flags;
    }

    // messaging interface

    // define callback to invoke for APP messages
    static int subscribeMessages(uint32_t hub_id, context_hub_callback *cbk, void *cookie) {
        return hubInstance()->doSubscribeMessages(hub_id, cbk, cookie);
    }
    // all messages from APP go here
    static int sendToNanohub(uint32_t hub_id, const hub_message_t *msg) {
        return hubInstance()->doSendToNanohub(hub_id, msg);
    }
    // passes message to kernel driver directly
    static int sendToDevice(const hub_app_name_t *name, const void *data, uint32_t len) {
        return hubInstance()->doSendToDevice(name, data, len);
    }
    // passes message to APP via callback
    static void sendToApp(const hub_app_name_t *name, uint32_t typ, const void *data, uint32_t len) {
        hubInstance()->doSendToApp(name, typ, data, len);
    }
};

}; // namespace nanohub

}; // namespace android

#endif
