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

#include <linux/netfilter/nfnetlink_log.h>

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "NetlinkManager.h"
#include "WakeupController.h"

using ::testing::StrictMock;
using ::testing::Test;
using ::testing::DoAll;
using ::testing::SaveArg;
using ::testing::Return;
using ::testing::_;

namespace android {
namespace net {

using netdutils::status::ok;

class MockNetdEventListener {
  public:
    MOCK_METHOD4(onWakeupEvent,
                 void(const std::string& prefix, uid_t uid, gid_t gid, uint64_t timestampNs));
};

class MockIptablesRestore : public IptablesRestoreInterface {
  public:
    ~MockIptablesRestore() override = default;
    MOCK_METHOD3(execute, int(const IptablesTarget target, const std::string& commands,
                              std::string* output));
};

class MockNFLogListener : public NFLogListenerInterface {
  public:
    ~MockNFLogListener() override = default;
    MOCK_METHOD2(subscribe, netdutils::Status(uint16_t nfLogGroup, const DispatchFn& fn));
    MOCK_METHOD1(unsubscribe, netdutils::Status(uint16_t nfLogGroup));
};

class WakeupControllerTest : public Test {
  protected:
    WakeupControllerTest() {
        EXPECT_CALL(mListener, subscribe(NetlinkManager::NFLOG_WAKEUP_GROUP, _))
            .WillOnce(DoAll(SaveArg<1>(&mMessageHandler), Return(ok)));
        EXPECT_CALL(mListener, unsubscribe(NetlinkManager::NFLOG_WAKEUP_GROUP)).WillOnce(Return(ok));
        mController.init(&mListener);
    }

    StrictMock<MockNetdEventListener> mEventListener;
    StrictMock<MockIptablesRestore> mIptables;
    StrictMock<MockNFLogListener> mListener;
    WakeupController mController{
        [this](const std::string& prefix, uid_t uid, gid_t gid, uint64_t timestampNs) {
            mEventListener.onWakeupEvent(prefix, uid, gid, timestampNs);
        },
        &mIptables};
    NFLogListenerInterface::DispatchFn mMessageHandler;
};

TEST_F(WakeupControllerTest, msgHandler) {
    const char kPrefix[] = "test:prefix";
    const uid_t kUid = 8734;
    const gid_t kGid = 2222;
    const uint64_t kNsPerS = 1000000000ULL;
    const uint64_t kTsNs = 9999 + (34 * kNsPerS);

    struct Msg {
        nlmsghdr nlmsg;
        nfgenmsg nfmsg;
        nlattr uidAttr;
        uid_t uid;
        nlattr gidAttr;
        gid_t gid;
        nlattr tsAttr;
        timespec ts;
        nlattr prefixAttr;
        char prefix[sizeof(kPrefix)];
    } msg = {};

    msg.uidAttr.nla_type = NFULA_UID;
    msg.uidAttr.nla_len = sizeof(msg.uidAttr) + sizeof(msg.uid);
    msg.uid = htobe32(kUid);

    msg.gidAttr.nla_type = NFULA_GID;
    msg.gidAttr.nla_len = sizeof(msg.gidAttr) + sizeof(msg.gid);
    msg.gid = htobe32(kGid);

    msg.tsAttr.nla_type = NFULA_TIMESTAMP;
    msg.tsAttr.nla_len = sizeof(msg.tsAttr) + sizeof(msg.ts);
    msg.ts.tv_sec = htobe32(kTsNs / kNsPerS);
    msg.ts.tv_nsec = htobe32(kTsNs % kNsPerS);

    msg.prefixAttr.nla_type = NFULA_PREFIX;
    msg.prefixAttr.nla_len = sizeof(msg.prefixAttr) + sizeof(msg.prefix);
    memcpy(msg.prefix, kPrefix, sizeof(kPrefix));

    auto payload = drop(netdutils::makeSlice(msg), offsetof(Msg, uidAttr));
    EXPECT_CALL(mEventListener, onWakeupEvent(kPrefix, kUid, kGid, kTsNs));
    mMessageHandler(msg.nlmsg, msg.nfmsg, payload);
}

TEST_F(WakeupControllerTest, badAttr) {
    const char kPrefix[] = "test:prefix";
    const uid_t kUid = 8734;
    const gid_t kGid = 2222;
    const uint64_t kNsPerS = 1000000000ULL;
    const uint64_t kTsNs = 9999 + (34 * kNsPerS);

    struct Msg {
        nlmsghdr nlmsg;
        nfgenmsg nfmsg;
        nlattr uidAttr;
        uid_t uid;
        nlattr invalid0;
        nlattr invalid1;
        nlattr gidAttr;
        gid_t gid;
        nlattr tsAttr;
        timespec ts;
        nlattr prefixAttr;
        char prefix[sizeof(kPrefix)];
    } msg = {};

    msg.uidAttr.nla_type = 999;
    msg.uidAttr.nla_len = sizeof(msg.uidAttr) + sizeof(msg.uid);
    msg.uid = htobe32(kUid);

    msg.invalid0.nla_type = 0;
    msg.invalid0.nla_len = 0;
    msg.invalid1.nla_type = 0;
    msg.invalid1.nla_len = 1;

    msg.gidAttr.nla_type = NFULA_GID;
    msg.gidAttr.nla_len = sizeof(msg.gidAttr) + sizeof(msg.gid);
    msg.gid = htobe32(kGid);

    msg.tsAttr.nla_type = NFULA_TIMESTAMP;
    msg.tsAttr.nla_len = sizeof(msg.tsAttr) - 2;
    msg.ts.tv_sec = htobe32(kTsNs / kNsPerS);
    msg.ts.tv_nsec = htobe32(kTsNs % kNsPerS);

    msg.prefixAttr.nla_type = NFULA_UID;
    msg.prefixAttr.nla_len = sizeof(msg.prefixAttr) + sizeof(msg.prefix);
    memcpy(msg.prefix, kPrefix, sizeof(kPrefix));

    auto payload = drop(netdutils::makeSlice(msg), offsetof(Msg, uidAttr));
    EXPECT_CALL(mEventListener, onWakeupEvent("", 1952805748, kGid, 0));
    mMessageHandler(msg.nlmsg, msg.nfmsg, payload);
}

TEST_F(WakeupControllerTest, unterminatedString) {
    char ones[20] = {};
    memset(ones, 1, sizeof(ones));

    struct Msg {
        nlmsghdr nlmsg;
        nfgenmsg nfmsg;
        nlattr prefixAttr;
        char prefix[sizeof(ones)];
    } msg = {};

    msg.prefixAttr.nla_type = NFULA_PREFIX;
    msg.prefixAttr.nla_len = sizeof(msg.prefixAttr) + sizeof(msg.prefix);
    memcpy(msg.prefix, ones, sizeof(ones));

    const auto expected = std::string(ones, sizeof(ones) - 1);
    auto payload = drop(netdutils::makeSlice(msg), offsetof(Msg, prefixAttr));
    EXPECT_CALL(mEventListener, onWakeupEvent(expected, -1, -1, -1));
    mMessageHandler(msg.nlmsg, msg.nfmsg, payload);
}

TEST_F(WakeupControllerTest, addInterface) {
    const char kPrefix[] = "test:prefix";
    const char kIfName[] = "wlan8";
    const uint32_t kMark = 0x12345678;
    const uint32_t kMask = 0x0F0F0F0F;
    const char kExpected[] =
        "*mangle\n-A wakeupctrl_mangle_INPUT -i test:prefix"
        " -j NFLOG --nflog-prefix wlan8 --nflog-group 3 --nflog-threshold 8"
        " -m mark --mark 0x12345678/0x0f0f0f0f -m limit --limit 10/s\nCOMMIT\n";
    EXPECT_CALL(mIptables, execute(V4V6, kExpected, _)).WillOnce(Return(0));
    mController.addInterface(kPrefix, kIfName, kMark, kMask);
}

TEST_F(WakeupControllerTest, delInterface) {
    const char kPrefix[] = "test:prefix";
    const char kIfName[] = "wlan8";
    const uint32_t kMark = 0x12345678;
    const uint32_t kMask = 0xF0F0F0F0;
    const char kExpected[] =
        "*mangle\n-D wakeupctrl_mangle_INPUT -i test:prefix"
        " -j NFLOG --nflog-prefix wlan8 --nflog-group 3 --nflog-threshold 8"
        " -m mark --mark 0x12345678/0xf0f0f0f0 -m limit --limit 10/s\nCOMMIT\n";
    EXPECT_CALL(mIptables, execute(V4V6, kExpected, _)).WillOnce(Return(0));
    mController.delInterface(kPrefix, kIfName, kMark, kMask);
}

}  // namespace net
}  // namespace android
