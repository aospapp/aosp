/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless requied by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

#define LOG_TAG "BpfTest"

#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <inttypes.h>
#include <linux/pfkeyv2.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>

#include <thread>

#include <android-base/file.h>
#include <android-base/stringprintf.h>
#include <android-base/unique_fd.h>
#include <gtest/gtest.h>
#include <utils/Log.h>

#include "bpf/BpfMap.h"
#include "bpf/BpfUtils.h"
#include "kern.h"
#include "libbpf_android.h"

using android::base::unique_fd;
using namespace android::bpf;

namespace android {

TEST(BpfTest, bpfMapPinTest) {
  SKIP_IF_BPF_NOT_SUPPORTED;

  const char* bpfMapPath = "/sys/fs/bpf/testMap";
  int ret = access(bpfMapPath, F_OK);
  if (!ret) {
    ASSERT_EQ(0, remove(bpfMapPath));
  } else {
    ASSERT_EQ(errno, ENOENT);
  }

  android::base::unique_fd mapfd(createMap(BPF_MAP_TYPE_HASH, sizeof(uint32_t),
                                           sizeof(uint32_t), 10,
                                           BPF_F_NO_PREALLOC));
  ASSERT_LT(0, mapfd) << "create map failed with error: " << strerror(errno);
  ASSERT_EQ(0, bpfFdPin(mapfd, bpfMapPath))
      << "pin map failed with error: " << strerror(errno);
  ASSERT_EQ(0, access(bpfMapPath, F_OK));
  ASSERT_EQ(0, remove(bpfMapPath));
}

#define BPF_SRC_PATH "/data/local/tmp"

#if defined(__aarch64__) || defined(__x86_64__)
#define BPF_SRC_NAME "/64/kern.o"
#else
#define BPF_SRC_NAME "/32/kern.o"
#endif

#define BPF_PATH "/sys/fs/bpf"
#define TEST_PROG_PATH BPF_PATH "/prog_kern_skfilter_test"
#define TEST_STATS_MAP_A_PATH BPF_PATH "/map_kern_test_stats_map_A"
#define TEST_STATS_MAP_B_PATH BPF_PATH "/map_kern_test_stats_map_B"
#define TEST_CONFIGURATION_MAP_PATH BPF_PATH "/map_kern_test_configuration_map"

constexpr int ACTIVE_MAP_KEY = 1;

class BpfRaceTest : public ::testing::Test {
 protected:
  BpfRaceTest() {}
  BpfMap<uint64_t, stats_value> cookieStatsMap[2];
  BpfMap<uint32_t, uint32_t> configurationMap;
  BpfProgInfo program;
  bool stop;
  std::thread tds[NUM_SOCKETS];

  static void workerThread(int prog_fd, bool *stop) {
    struct sockaddr_in6 remote = {.sin6_family = AF_INET6};
    struct sockaddr_in6 local;
    uint64_t j = 0;
    int recvSock, sendSock, recv_len;
    char buf[strlen("msg: 18446744073709551615")];
    int res;
    socklen_t slen = sizeof(remote);

    recvSock = socket(PF_INET6, SOCK_DGRAM, IPPROTO_UDP);
    EXPECT_NE(-1, recvSock);
    std::string address = android::base::StringPrintf("::1");
    EXPECT_NE(0, inet_pton(AF_INET6, address.c_str(), &remote.sin6_addr));
    EXPECT_NE(-1, bind(recvSock, (struct sockaddr *)&remote, sizeof(remote)));
    EXPECT_EQ(0, getsockname(recvSock, (struct sockaddr *)&remote, &slen));
    sendSock = socket(PF_INET6, SOCK_DGRAM, IPPROTO_UDP);
    EXPECT_NE(-1, sendSock) << "send socket create failed!\n";
    EXPECT_NE(-1, setsockopt(recvSock, SOL_SOCKET, SO_ATTACH_BPF, &prog_fd,
                             sizeof(prog_fd)))
        << "attach bpf program failed"
        << android::base::StringPrintf("%s\n", strerror(errno));

    // Keep sending and receiving packet until test end.
    while (!*stop) {
      std::string id = android::base::StringPrintf("msg: %" PRIu64 "\n", j);
      res = sendto(sendSock, &id, id.length(), 0, (struct sockaddr *)&remote,
                   slen);
      EXPECT_EQ(id.size(), res);
      recv_len = recvfrom(recvSock, &buf, sizeof(buf), 0,
                          (struct sockaddr *)&local, &slen);
      EXPECT_EQ(id.size(), recv_len);
    }
  }

  void SetUp() {
    SKIP_IF_BPF_NOT_SUPPORTED;
    int ret = access(TEST_PROG_PATH, R_OK);
    // Always create a new program and remove the pinned program after program
    // loading is done.
    if (ret == 0) {
      remove(TEST_PROG_PATH);
    }
    std::string progSrcPath = BPF_SRC_PATH BPF_SRC_NAME;
    ASSERT_EQ(0, android::bpf::loadProg(progSrcPath.c_str()));

    EXPECT_TRUE(isOk(cookieStatsMap[0].init(TEST_STATS_MAP_A_PATH)));
    EXPECT_TRUE(isOk(cookieStatsMap[1].init(TEST_STATS_MAP_B_PATH)));
    EXPECT_TRUE(isOk(configurationMap.init(TEST_CONFIGURATION_MAP_PATH)));
    EXPECT_TRUE(cookieStatsMap[0].isValid());
    EXPECT_TRUE(cookieStatsMap[1].isValid());
    EXPECT_TRUE(configurationMap.isValid());
    // Start several threads to send and receive packets with an eBPF program
    // attached to the socket.
    stop = false;
    int prog_fd = bpfFdGet(TEST_PROG_PATH, 0);
    EXPECT_OK(configurationMap.writeValue(ACTIVE_MAP_KEY, 0, BPF_ANY));

    for (int i = 0; i < NUM_SOCKETS; i++) {
      tds[i] = std::thread(workerThread, prog_fd, &stop);
    }
  }

  void TearDown() {
    SKIP_IF_BPF_NOT_SUPPORTED;

    // Stop the threads and clean up the program.
    stop = true;
    for (int i = 0; i < NUM_SOCKETS; i++) {
      tds[i].join();
    }
    remove(TEST_PROG_PATH);
    remove(TEST_STATS_MAP_A_PATH);
    remove(TEST_STATS_MAP_B_PATH);
    remove(TEST_CONFIGURATION_MAP_PATH);
  }

  void swapAndCleanStatsMap(bool expectSynchronized, int seconds) {
    uint64_t i = 0;
    auto start = std::clock();
    while (((double)(std::clock() - start) / CLOCKS_PER_SEC) < seconds) {
      // Check if the vacant map is empty based on the current configuration.
      auto isEmpty = cookieStatsMap[i].isEmpty();
      EXPECT_TRUE(isOk(isEmpty));
      if (expectSynchronized) {
        // The map should always be empty because synchronizeKernelRCU should
        // ensure that the BPF programs running on all cores have seen the write
        // to the configuration map that tells them to write to the other map.
        // If it's not empty, fail.
        ASSERT_TRUE(isEmpty.value())
            << "Race problem between stats clean and updates";
      } else if (!isEmpty.value()) {
        // We found a race condition, which is expected (eventually) because
        // we're not calling synchronizeKernelRCU. Pass the test.
        break;
      }

      // Change the configuration and wait for rcu grace period.
      i ^= 1;
      EXPECT_OK(configurationMap.writeValue(ACTIVE_MAP_KEY, i, BPF_ANY));
      if (expectSynchronized) {
        EXPECT_EQ(0, synchronizeKernelRCU());
      }

      // Clean up the previous map after map swap.
      EXPECT_OK(cookieStatsMap[i].clear());
    }
    if (!expectSynchronized) {
      EXPECT_GE(seconds, (double)(std::clock() - start) / CLOCKS_PER_SEC)
          << "Race problem didn't happen before time out";
    }
  }
};

// Verify the race problem disappear when the kernel call synchronize_rcu
// after changing the active map.
TEST_F(BpfRaceTest, testRaceWithBarrier) {
  SKIP_IF_BPF_NOT_SUPPORTED;

  swapAndCleanStatsMap(true, 60);
}

// Confirm the race problem exists when the kernel doesn't call synchronize_rcu
// after changing the active map.
TEST_F(BpfRaceTest, testRaceWithoutBarrier) {
  SKIP_IF_BPF_NOT_SUPPORTED;

  swapAndCleanStatsMap(false, 20);
}

}  // namespace android
