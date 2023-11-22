/*
 * Copyright 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "if_monitor.h"

#include <errno.h>
#include <linux/rtnetlink.h>
#include <net/if.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#define LOG_TAG "RIL-IFMON"
#include <utils/Log.h>

static const size_t kReadBufferSize = 32768;

static const size_t kControlServer = 0;
static const size_t kControlClient = 1;

// A list of commands that can be sent to the monitor. These should be one
// character long as that is all that the monitor will read and process.
static const char kMonitorStopCommand[] = "\1";
static const char kMonitorAckCommand[] = "\2";

static size_t addrLength(int addrFamily) {
    switch (addrFamily) {
        case AF_INET:
            return 4;
        case AF_INET6:
            return 16;
        default:
            return 0;
    }
}

bool operator==(const struct ifAddress& left, const struct ifAddress& right) {
    // The prefix length does not factor in to whether two addresses are the
    // same or not. Only the family and the address data. This matches the
    // kernel behavior when attempting to add the same address with different
    // prefix lengths, those changes are rejected because the address already
    // exists.
    return left.family == right.family &&
           memcmp(&left.addr, &right.addr, addrLength(left.family)) == 0;
}

class InterfaceMonitor {
public:
    InterfaceMonitor() : mSocketFd(-1) {
        mControlSocket[kControlServer] = -1;
        mControlSocket[kControlClient] = -1;
    }

    ~InterfaceMonitor() {
        if (mControlSocket[kControlClient] != -1) {
            ::close(mControlSocket[kControlClient]);
            mControlSocket[kControlClient] = -1;
        }
        if (mControlSocket[kControlServer] != -1) {
            ::close(mControlSocket[kControlServer]);
            mControlSocket[kControlServer] = -1;
        }

        if (mSocketFd != -1) {
            ::close(mSocketFd);
            mSocketFd = -1;
        }
    }

    bool init() {
        if (mSocketFd != -1) {
            RLOGE("InterfaceMonitor already initialized");
            return false;
        }

        mSocketFd = ::socket(AF_NETLINK,
                             SOCK_DGRAM | SOCK_CLOEXEC,
                             NETLINK_ROUTE);
        if (mSocketFd == -1) {
            RLOGE("InterfaceMonitor failed to open socket: %s", strerror(errno));
            return false;
        }

        if (::socketpair(AF_UNIX, SOCK_DGRAM, 0, mControlSocket) != 0) {
            RLOGE("Unable to create control socket pair: %s", strerror(errno));
            return false;
        }

        struct sockaddr_nl addr;
        memset(&addr, 0, sizeof(addr));
        addr.nl_family = AF_NETLINK;
        addr.nl_groups = (1 << (RTNLGRP_IPV4_IFADDR - 1)) |
                         (1 << (RTNLGRP_IPV6_IFADDR - 1));

        struct sockaddr* sa = reinterpret_cast<struct sockaddr*>(&addr);
        if (::bind(mSocketFd, sa, sizeof(addr)) != 0) {
            RLOGE("InterfaceMonitor failed to bind socket: %s",
                  strerror(errno));
            return false;
        }

        return true;
    }

    void setCallback(ifMonitorCallback callback) {
        mOnAddressChangeCallback = callback;
    }

    void runAsync() {
        std::unique_lock<std::mutex> lock(mThreadMutex);
        mThread = std::make_unique<std::thread>([this]() { run(); });
    }

    void requestAddress() {
        struct {
            struct nlmsghdr hdr;
            struct ifaddrmsg msg;
            char padding[16];
        } request;

        memset(&request, 0, sizeof(request));
        request.hdr.nlmsg_len = NLMSG_LENGTH(sizeof(request.msg));
        request.hdr.nlmsg_flags = NLM_F_REQUEST | NLM_F_ROOT;
        request.hdr.nlmsg_type = RTM_GETADDR;

        int status = ::send(mSocketFd, &request, request.hdr.nlmsg_len, 0);
        if (status < 0 ||
            static_cast<unsigned int>(status) != request.hdr.nlmsg_len) {
            if (status < 0) {
                RLOGE("Failed to send netlink request: %s", strerror(errno));
            } else {
                RLOGE("Short send only sent %d out of %d bytes",
                      status, (int)request.hdr.nlmsg_len);
            }
        }
    }

    void run() {
        requestAddress();

        std::vector<struct pollfd> fds(2);
        fds[0].events = POLLIN;
        fds[0].fd = mControlSocket[kControlServer];
        fds[1].events = POLLIN;
        fds[1].fd = mSocketFd;
        while (true) {
            int status = ::poll(fds.data(), fds.size(), -1);
            if (status < 0) {
                if (errno == EINTR) {
                    // Interrupted, just keep going
                    continue;
                }
                // Actual error, time to quit
                RLOGE("Polling failed: %s", strerror(errno));
                break;
            } else if (status == 0) {
                // Timeout
                continue;
            }

            if (fds[0].revents & POLLIN) {
                // Control message received
                char command = -1;
                if (::read(mControlSocket[kControlServer],
                           &command,
                           sizeof(command)) == 1) {
                    if (command == kMonitorStopCommand[0]) {
                        break;
                    }
                }
            } else if (fds[1].revents & POLLIN) {
                onReadAvailable();
            }
        }
        ::write(mControlSocket[kControlServer], kMonitorAckCommand, 1);
    }

    void stop() {
        std::unique_lock<std::mutex> lock(mThreadMutex);
        if (mThread) {
            ::write(mControlSocket[kControlClient], kMonitorStopCommand, 1);
            char ack = -1;
            while (ack != kMonitorAckCommand[0]) {
                ::read(mControlSocket[kControlClient], &ack, sizeof(ack));
            }
            mThread->join();
            mThread.reset();
        }
    }

private:
    void onReadAvailable() {
        char buffer[kReadBufferSize];
        struct sockaddr_storage storage;

        while (true) {
            socklen_t addrSize = sizeof(storage);
            int status = ::recvfrom(mSocketFd,
                                    buffer,
                                    sizeof(buffer),
                                    MSG_DONTWAIT,
                                    reinterpret_cast<struct sockaddr*>(&storage),
                                    &addrSize);
            if (status < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                // Nothing to receive, everything is fine
                return;
            } else if (status < 0 && errno == EINTR) {
                // Caught interrupt, try again
                continue;
            } else if (status < 0) {
                RLOGE("InterfaceMonitor receive failed: %s", strerror(errno));
                return;
            } else if (addrSize < 0 ||
                       static_cast<size_t>(addrSize) != sizeof(struct sockaddr_nl)) {
                RLOGE("InterfaceMonitor received invalid address size");
                return;
            }

            size_t length = static_cast<size_t>(status);

            auto hdr = reinterpret_cast<struct nlmsghdr*>(buffer);
            while (NLMSG_OK(hdr, length) && hdr->nlmsg_type != NLMSG_DONE) {
                switch (hdr->nlmsg_type) {
                    case RTM_NEWADDR:
                    case RTM_DELADDR:
                        handleAddressChange(hdr);
                        break;
                    default:
                        RLOGE("Received message type %d", (int)hdr->nlmsg_type);
                        break;
                }
                NLMSG_NEXT(hdr, length);
            }
        }
    }

    void handleAddressChange(const struct nlmsghdr* hdr) {
        if (!mOnAddressChangeCallback) {
            return;
        }

        auto msg = reinterpret_cast<const struct ifaddrmsg*>(NLMSG_DATA(hdr));
        std::vector<ifAddress>& ifAddrs = mAddresses[msg->ifa_index];

        auto attr = reinterpret_cast<const struct rtattr*>(IFA_RTA(msg));
        int attrLen = IFA_PAYLOAD(hdr);

        bool somethingChanged = false;
        for (;attr && RTA_OK(attr, attrLen); attr = RTA_NEXT(attr, attrLen)) {
            if (attr->rta_type != IFA_LOCAL && attr->rta_type != IFA_ADDRESS) {
                continue;
            }

            ifAddress addr;
            memset(&addr, 0, sizeof(addr));

            // Ensure that the payload matches the expected address length
            if (RTA_PAYLOAD(attr) >= addrLength(msg->ifa_family)) {
                addr.family = msg->ifa_family;
                addr.prefix = msg->ifa_prefixlen;
                memcpy(&addr.addr, RTA_DATA(attr), addrLength(addr.family));
            } else {
                RLOGE("Invalid address family (%d) and size (%d) combination",
                      int(msg->ifa_family), int(RTA_PAYLOAD(attr)));
                continue;
            }

            auto it = std::find(ifAddrs.begin(), ifAddrs.end(), addr);
            if (hdr->nlmsg_type == RTM_NEWADDR && it == ifAddrs.end()) {
                // New address does not exist, add it
                ifAddrs.push_back(addr);
                somethingChanged = true;
            } else if (hdr->nlmsg_type == RTM_DELADDR && it != ifAddrs.end()) {
                // Address was removed and it exists, remove it
                ifAddrs.erase(it);
                somethingChanged = true;
            }
        }

        if (somethingChanged) {
            mOnAddressChangeCallback(msg->ifa_index,
                                     ifAddrs.data(),
                                     ifAddrs.size());
        }
    }

    ifMonitorCallback mOnAddressChangeCallback;
    std::unordered_map<unsigned int, std::vector<ifAddress>> mAddresses;
    std::unique_ptr<std::thread> mThread;
    std::mutex mThreadMutex;
    int mSocketFd;
    int mControlSocket[2];
};

extern "C"
struct ifMonitor* ifMonitorCreate() {
    auto monitor = std::make_unique<InterfaceMonitor>();
    if (!monitor || !monitor->init()) {
        return nullptr;
    }
    return reinterpret_cast<struct ifMonitor*>(monitor.release());
}

extern "C"
void ifMonitorFree(struct ifMonitor* ifMonitor) {
    InterfaceMonitor* monitor = reinterpret_cast<InterfaceMonitor*>(ifMonitor);
    delete monitor;
}

extern "C"
void ifMonitorSetCallback(struct ifMonitor* ifMonitor,
                          ifMonitorCallback callback) {
    InterfaceMonitor* monitor = reinterpret_cast<InterfaceMonitor*>(ifMonitor);
    monitor->setCallback(callback);
}

extern "C"
void ifMonitorRunAsync(struct ifMonitor* ifMonitor) {
    InterfaceMonitor* monitor = reinterpret_cast<InterfaceMonitor*>(ifMonitor);

    monitor->runAsync();
}

extern "C"
void ifMonitorStop(struct ifMonitor* ifMonitor) {
    InterfaceMonitor* monitor = reinterpret_cast<InterfaceMonitor*>(ifMonitor);

    monitor->stop();
}

