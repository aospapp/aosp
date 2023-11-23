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

#ifndef LOG_TAG
#define LOG_TAG "bpfloader"
#endif

#include <arpa/inet.h>
#include <dirent.h>
#include <elf.h>
#include <error.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/bpf.h>
#include <linux/unistd.h>
#include <net/if.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <android-base/logging.h>
#include <android-base/macros.h>
#include <android-base/properties.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <libbpf_android.h>
#include <log/log.h>
#include <netdutils/Misc.h>
#include <netdutils/Slice.h>
#include "BpfSyscallWrappers.h"
#include "bpf/BpfUtils.h"

using android::base::EndsWith;
using android::bpf::domain;
using std::string;

bool exists(const char* const path) {
    int v = access(path, F_OK);
    if (!v) {
        ALOGI("%s exists.", path);
        return true;
    }
    if (errno == ENOENT) return false;
    ALOGE("FATAL: access(%s, F_OK) -> %d [%d:%s]", path, v, errno, strerror(errno));
    abort();  // can only hit this if permissions (likely selinux) are screwed up
}

constexpr unsigned long long kTetheringApexDomainBitmask =
        domainToBitmask(domain::tethering) |
        domainToBitmask(domain::net_private) |
        domainToBitmask(domain::net_shared) |
        domainToBitmask(domain::netd_readonly) |
        domainToBitmask(domain::netd_shared);

// Programs shipped inside the tethering apex should be limited to networking stuff,
// as KPROBE, PERF_EVENT, TRACEPOINT are dangerous to use from mainline updatable code,
// since they are less stable abi/api and may conflict with platform uses of bpf.
constexpr bpf_prog_type kTetheringApexAllowedProgTypes[] = {
        BPF_PROG_TYPE_CGROUP_SKB,
        BPF_PROG_TYPE_CGROUP_SOCK,
        BPF_PROG_TYPE_CGROUP_SOCKOPT,
        BPF_PROG_TYPE_CGROUP_SOCK_ADDR,
        BPF_PROG_TYPE_CGROUP_SYSCTL,
        BPF_PROG_TYPE_LWT_IN,
        BPF_PROG_TYPE_LWT_OUT,
        BPF_PROG_TYPE_LWT_SEG6LOCAL,
        BPF_PROG_TYPE_LWT_XMIT,
        BPF_PROG_TYPE_SCHED_ACT,
        BPF_PROG_TYPE_SCHED_CLS,
        BPF_PROG_TYPE_SOCKET_FILTER,
        BPF_PROG_TYPE_SOCK_OPS,
        BPF_PROG_TYPE_XDP,
};

// Networking-related program types are limited to the Tethering Apex
// to prevent things from breaking due to conflicts on mainline updates
// (exception made for socket filters, ie. xt_bpf for potential use in iptables,
// or for attaching to sockets directly)
constexpr bpf_prog_type kPlatformAllowedProgTypes[] = {
        BPF_PROG_TYPE_KPROBE,
        BPF_PROG_TYPE_PERF_EVENT,
        BPF_PROG_TYPE_SOCKET_FILTER,
        BPF_PROG_TYPE_TRACEPOINT,
        BPF_PROG_TYPE_UNSPEC,  // Will be replaced with fuse bpf program type
};

// see b/162057235. For arbitrary program types, the concern is that due to the lack of
// SELinux access controls over BPF program attachpoints, we have no way to control the
// attachment of programs to shared resources (or to detect when a shared resource
// has one BPF program replace another that is attached there)
constexpr bpf_prog_type kVendorAllowedProgTypes[] = {
        BPF_PROG_TYPE_SOCKET_FILTER,
};


const android::bpf::Location locations[] = {
        // S+ Tethering mainline module (network_stack): tether offload
        {
                .dir = "/apex/com.android.tethering/etc/bpf/",
                .prefix = "tethering/",
                .allowedDomainBitmask = kTetheringApexDomainBitmask,
                .allowedProgTypes = kTetheringApexAllowedProgTypes,
                .allowedProgTypesLength = arraysize(kTetheringApexAllowedProgTypes),
        },
        // T+ Tethering mainline module (shared with netd & system server)
        // netutils_wrapper (for iptables xt_bpf) has access to programs
        {
                .dir = "/apex/com.android.tethering/etc/bpf/netd_shared/",
                .prefix = "netd_shared/",
                .allowedDomainBitmask = kTetheringApexDomainBitmask,
                .allowedProgTypes = kTetheringApexAllowedProgTypes,
                .allowedProgTypesLength = arraysize(kTetheringApexAllowedProgTypes),
        },
        // T+ Tethering mainline module (shared with netd & system server)
        // netutils_wrapper has no access, netd has read only access
        {
                .dir = "/apex/com.android.tethering/etc/bpf/netd_readonly/",
                .prefix = "netd_readonly/",
                .allowedDomainBitmask = kTetheringApexDomainBitmask,
                .allowedProgTypes = kTetheringApexAllowedProgTypes,
                .allowedProgTypesLength = arraysize(kTetheringApexAllowedProgTypes),
        },
        // T+ Tethering mainline module (shared with system server)
        {
                .dir = "/apex/com.android.tethering/etc/bpf/net_shared/",
                .prefix = "net_shared/",
                .allowedDomainBitmask = kTetheringApexDomainBitmask,
                .allowedProgTypes = kTetheringApexAllowedProgTypes,
                .allowedProgTypesLength = arraysize(kTetheringApexAllowedProgTypes),
        },
        // T+ Tethering mainline module (not shared, just network_stack)
        {
                .dir = "/apex/com.android.tethering/etc/bpf/net_private/",
                .prefix = "net_private/",
                .allowedDomainBitmask = kTetheringApexDomainBitmask,
                .allowedProgTypes = kTetheringApexAllowedProgTypes,
                .allowedProgTypesLength = arraysize(kTetheringApexAllowedProgTypes),
        },
        // Core operating system
        {
                .dir = "/system/etc/bpf/",
                .prefix = "",
                .allowedDomainBitmask = domainToBitmask(domain::platform),
                .allowedProgTypes = kPlatformAllowedProgTypes,
                .allowedProgTypesLength = arraysize(kPlatformAllowedProgTypes),
        },
        // Vendor operating system
        {
                .dir = "/vendor/etc/bpf/",
                .prefix = "vendor/",
                .allowedDomainBitmask = domainToBitmask(domain::vendor),
                .allowedProgTypes = kVendorAllowedProgTypes,
                .allowedProgTypesLength = arraysize(kVendorAllowedProgTypes),
        },
};

int loadAllElfObjects(const android::bpf::Location& location) {
    int retVal = 0;
    DIR* dir;
    struct dirent* ent;

    if ((dir = opendir(location.dir)) != NULL) {
        while ((ent = readdir(dir)) != NULL) {
            string s = ent->d_name;
            if (!EndsWith(s, ".o")) continue;

            string progPath(location.dir);
            progPath += s;

            bool critical;
            int ret = android::bpf::loadProg(progPath.c_str(), &critical, location);
            if (ret) {
                if (critical) retVal = ret;
                ALOGE("Failed to load object: %s, ret: %s", progPath.c_str(), std::strerror(-ret));
            } else {
                ALOGI("Loaded object: %s", progPath.c_str());
            }
        }
        closedir(dir);
    }
    return retVal;
}

int createSysFsBpfSubDir(const char* const prefix) {
    if (*prefix) {
        mode_t prevUmask = umask(0);

        string s = "/sys/fs/bpf/";
        s += prefix;

        errno = 0;
        int ret = mkdir(s.c_str(), S_ISVTX | S_IRWXU | S_IRWXG | S_IRWXO);
        if (ret && errno != EEXIST) {
            const int err = errno;
            ALOGE("Failed to create directory: %s, ret: %s", s.c_str(), std::strerror(err));
            return -err;
        }

        umask(prevUmask);
    }
    return 0;
}

// Technically 'value' doesn't need to be newline terminated, but it's best
// to include a newline to match 'echo "value" > /proc/sys/...foo' behaviour,
// which is usually how kernel devs test the actual sysctl interfaces.
int writeProcSysFile(const char *filename, const char *value) {
    android::base::unique_fd fd(open(filename, O_WRONLY | O_CLOEXEC));
    if (fd < 0) {
        const int err = errno;
        ALOGE("open('%s', O_WRONLY | O_CLOEXEC) -> %s", filename, strerror(err));
        return -err;
    }
    int len = strlen(value);
    int v = write(fd, value, len);
    if (v < 0) {
        const int err = errno;
        ALOGE("write('%s', '%s', %d) -> %s", filename, value, len, strerror(err));
        return -err;
    }
    if (v != len) {
        // In practice, due to us only using this for /proc/sys/... files, this can't happen.
        ALOGE("write('%s', '%s', %d) -> short write [%d]", filename, value, len, v);
        return -EINVAL;
    }
    return 0;
}

int main(int argc, char** argv) {
    (void)argc;
    android::base::InitLogging(argv, &android::base::KernelLogger);

    // Ensure we can determine the Android build type.
    if (!android::bpf::isEng() && !android::bpf::isUser() && !android::bpf::isUserdebug()) {
        ALOGE("Failed to determine the build type: got %s, want 'eng', 'user', or 'userdebug'",
              android::bpf::getBuildType().c_str());
        return 1;
    }

    // Linux 5.16-rc1 changed the default to 2 (disabled but changeable), but we need 0 (enabled)
    // (this writeFile is known to fail on at least 4.19, but always defaults to 0 on pre-5.13,
    // on 5.13+ it depends on CONFIG_BPF_UNPRIV_DEFAULT_OFF)
    if (writeProcSysFile("/proc/sys/kernel/unprivileged_bpf_disabled", "0\n") &&
        android::bpf::isAtLeastKernelVersion(5, 13, 0)) return 1;

    // Enable the eBPF JIT -- but do note that on 64-bit kernels it is likely
    // already force enabled by the kernel config option BPF_JIT_ALWAYS_ON.
    // (Note: this (open) will fail with ENOENT 'No such file or directory' if
    //  kernel does not have CONFIG_BPF_JIT=y)
    // BPF_JIT is required by R VINTF (which means 4.14/4.19/5.4 kernels),
    // but 4.14/4.19 were released with P & Q, and only 5.4 is new in R+.
    if (writeProcSysFile("/proc/sys/net/core/bpf_jit_enable", "1\n") &&
        android::bpf::isAtLeastKernelVersion(5, 4, 0)) return 1;

    // Enable JIT kallsyms export for privileged users only
    // (Note: this (open) will fail with ENOENT 'No such file or directory' if
    //  kernel does not have CONFIG_HAVE_EBPF_JIT=y)
    if (writeProcSysFile("/proc/sys/net/core/bpf_jit_kallsyms", "1\n") &&
        android::bpf::isAtLeastKernelVersion(5, 4, 0)) return 1;

    // Create all the pin subdirectories
    // (this must be done first to allow selinux_context and pin_subdir functionality,
    //  which could otherwise fail with ENOENT during object pinning or renaming,
    //  due to ordering issues)
    for (const auto& location : locations) {
        if (createSysFsBpfSubDir(location.prefix)) return 1;
    }

    // Note: there's no actual src dir for fs_bpf_loader .o's,
    // so it is not listed in 'locations[].prefix'.
    // This is because this is primarily meant for triggering genfscon rules,
    // and as such this will likely always be the case.
    // Thus we need to manually create the /sys/fs/bpf/loader subdirectory.
    if (createSysFsBpfSubDir("loader")) return 1;

    // Load all ELF objects, create programs and maps, and pin them
    for (const auto& location : locations) {
        if (loadAllElfObjects(location) != 0) {
            ALOGE("=== CRITICAL FAILURE LOADING BPF PROGRAMS FROM %s ===", location.dir);
            ALOGE("If this triggers reliably, you're probably missing kernel options or patches.");
            ALOGE("If this triggers randomly, you might be hitting some memory allocation "
                  "problems or startup script race.");
            ALOGE("--- DO NOT EXPECT SYSTEM TO BOOT SUCCESSFULLY ---");
            sleep(20);
            return 2;
        }
    }

    int key = 1;
    int value = 123;
    android::base::unique_fd map(
            android::bpf::createMap(BPF_MAP_TYPE_ARRAY, sizeof(key), sizeof(value), 2, 0));
    if (android::bpf::writeToMapEntry(map, &key, &value, BPF_ANY)) {
        ALOGE("Critical kernel bug - failure to write into index 1 of 2 element bpf map array.");
        return 1;
    }

    if (android::base::SetProperty("bpf.progs_loaded", "1") == false) {
        ALOGE("Failed to set bpf.progs_loaded property");
        return 1;
    }

    return 0;
}
