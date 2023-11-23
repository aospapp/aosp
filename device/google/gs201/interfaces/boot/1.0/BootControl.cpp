/*
 * Copyright (C) 2019 The Android Open Source Project
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

#define LOG_TAG "bootcontrolhal"

#include "BootControl.h"
#include "GptUtils.h"

#include <android-base/file.h>
#include <cutils/properties.h>
#include <log/log.h>

namespace android {
namespace hardware {
namespace boot {
namespace V1_0 {
namespace implementation {

namespace {

#define BOOT_A_PATH     "/dev/block/by-name/boot_a"
#define BOOT_B_PATH     "/dev/block/by-name/boot_b"

// slot flags
#define AB_ATTR_PRIORITY_SHIFT      52
#define AB_ATTR_PRIORITY_MASK       (3UL << AB_ATTR_PRIORITY_SHIFT)
#define AB_ATTR_ACTIVE_SHIFT        54
#define AB_ATTR_ACTIVE              (1UL << AB_ATTR_ACTIVE_SHIFT)
#define AB_ATTR_RETRY_COUNT_SHIFT   (55)
#define AB_ATTR_RETRY_COUNT_MASK    (7UL << AB_ATTR_RETRY_COUNT_SHIFT)
#define AB_ATTR_SUCCESSFUL          (1UL << 58)
#define AB_ATTR_UNBOOTABLE          (1UL << 59)

#define AB_ATTR_MAX_PRIORITY        3UL
#define AB_ATTR_MAX_RETRY_COUNT     3UL

static std::string getDevPath(uint32_t slot) {
    char real_path[PATH_MAX];

    const char *path = slot == 0 ? BOOT_A_PATH : BOOT_B_PATH;

    int ret = readlink(path, real_path, sizeof real_path);
    if (ret < 0) {
        ALOGE("readlink failed for boot device %s\n", strerror(errno));
        return std::string();
    }

    std::string dp(real_path);
    // extract /dev/sda.. part
    return dp.substr(0, sizeof "/dev/block/sdX" - 1);
}

static bool isSlotFlagSet(uint32_t slot, uint64_t flag) {
    std::string dev_path = getDevPath(slot);
    if (dev_path.empty()) {
        ALOGI("Could not get device path for slot %d\n", slot);
        return false;
    }

    GptUtils gpt(dev_path);
    if (gpt.Load()) {
        ALOGI("failed to load gpt data\n");
        return false;
    }

    gpt_entry *e = gpt.GetPartitionEntry(slot ? "boot_b" : "boot_a");
    if (e == nullptr) {
        ALOGI("failed to get gpt entry\n");
        return false;
    }

    return !!(e->attr & flag);
}

static int setSlotFlag(uint32_t slot, uint64_t flag) {
    std::string dev_path = getDevPath(slot);
    if (dev_path.empty()) {
        ALOGI("Could not get device path for slot %d\n", slot);
        return -1;
    }

    GptUtils gpt(dev_path);
    if (gpt.Load()) {
        ALOGI("failed to load gpt data\n");
        return -1;
    }

    gpt_entry *e = gpt.GetPartitionEntry(slot ? "boot_b" : "boot_a");
    if (e == nullptr) {
        ALOGI("failed to get gpt entry\n");
        return -1;
    }

    e->attr |= flag;
    gpt.Sync();

    return 0;
}

}

// Methods from ::android::hardware::boot::V1_0::IBootControl follow.
Return<uint32_t> BootControl::getNumberSlots() {
    uint32_t slots = 0;

    if (access(BOOT_A_PATH, F_OK) == 0)
      slots++;

    if (access(BOOT_B_PATH, F_OK) == 0)
      slots++;

    return slots;
}

Return<uint32_t> BootControl::getCurrentSlot() {
    char suffix[PROPERTY_VALUE_MAX];
    property_get("ro.boot.slot_suffix", suffix, "_a");
    return std::string(suffix) == "_b" ? 1 : 0;
}

Return<void> BootControl::markBootSuccessful(markBootSuccessful_cb _hidl_cb) {
    if (getNumberSlots() == 0) {
        // no slots, just return true otherwise Android keeps trying
        _hidl_cb({true, ""});
        return Void();
    }
    int ret = setSlotFlag(getCurrentSlot(), AB_ATTR_SUCCESSFUL);
    ret ? _hidl_cb({false, "Failed to set successfull flag"}) : _hidl_cb({true, ""});
    return Void();
}

Return<void> BootControl::setActiveBootSlot(uint32_t slot, setActiveBootSlot_cb _hidl_cb) {
    if (slot >= 2) {
        _hidl_cb({false, "Invalid slot"});
        return Void();
    }

    std::string dev_path = getDevPath(slot);
    if (dev_path.empty()) {
        _hidl_cb({false, "Could not get device path for slot"});
        return Void();
    }

    GptUtils gpt(dev_path);
    if (gpt.Load()) {
        _hidl_cb({false, "failed to load gpt data"});
        return Void();
    }

    gpt_entry *active_entry = gpt.GetPartitionEntry(slot == 0 ? "boot_a" : "boot_b");
    gpt_entry *inactive_entry = gpt.GetPartitionEntry(slot == 0 ? "boot_b" : "boot_a");
    if (active_entry == nullptr || inactive_entry == nullptr) {
        _hidl_cb({false, "failed to get entries for boot partitions"});
        return Void();
    }

    ALOGV("slot active attributes %lx\n", active_entry->attr);
    ALOGV("slot inactive attributes %lx\n", inactive_entry->attr);

    char boot_dev[PROPERTY_VALUE_MAX];
    property_get("ro.boot.bootdevice", boot_dev, "");
    if (boot_dev[0] == '\0') {
        _hidl_cb({false, "invalid ro.boot.bootdevice prop"});
        return Void();
    }

    std::string boot_lun_path = std::string("/sys/devices/platform/") +
                                boot_dev + "/pixel/boot_lun_enabled";
    int fd = open(boot_lun_path.c_str(), O_RDWR);
    if (fd < 0) {
        // Try old path for kernels < 5.4
        // TODO: remove once kernel 4.19 support is deprecated
        std::string boot_lun_path = std::string("/sys/devices/platform/") +
                                    boot_dev + "/attributes/boot_lun_enabled";
        fd = open(boot_lun_path.c_str(), O_RDWR);
        if (fd < 0) {
            _hidl_cb({false, "failed to open ufs attr boot_lun_enabled"});
            return Void();
        }
    }

    // update attributes for active and inactive
    inactive_entry->attr &= ~AB_ATTR_ACTIVE;
    active_entry->attr = AB_ATTR_ACTIVE | (AB_ATTR_MAX_PRIORITY << AB_ATTR_PRIORITY_SHIFT) |
                         (AB_ATTR_MAX_RETRY_COUNT << AB_ATTR_RETRY_COUNT_SHIFT);

    //
    // bBootLunEn
    // 0x1  => Boot LU A = enabled, Boot LU B = disable
    // 0x2  => Boot LU A = disable, Boot LU B = enabled
    //
    int ret = android::base::WriteStringToFd(slot == 0 ? "1" : "2", fd);
    close(fd);
    if (ret < 0) {
        _hidl_cb({false, "faied to write boot_lun_enabled attribute"});
        return Void();
    }

    _hidl_cb({true, ""});
    return Void();
}

Return<void> BootControl::setSlotAsUnbootable(uint32_t slot, setSlotAsUnbootable_cb _hidl_cb) {
    if (slot >= 2) {
        _hidl_cb({false, "Invalid slot"});
        return Void();
    }

    std::string dev_path = getDevPath(slot);
    if (dev_path.empty()) {
        _hidl_cb({false, "Could not get device path for slot"});
        return Void();
    }

    GptUtils gpt(dev_path);
    gpt.Load();

    gpt_entry *e = gpt.GetPartitionEntry(slot ? "boot_b" : "boot_a");
    e->attr |= AB_ATTR_UNBOOTABLE;

    gpt.Sync();

    _hidl_cb({true, ""});
    return Void();
}

Return<::android::hardware::boot::V1_0::BoolResult> BootControl::isSlotBootable(uint32_t slot) {
    if (getNumberSlots() == 0)
        return BoolResult::FALSE;
    if (slot >= getNumberSlots())
        return BoolResult::INVALID_SLOT;
    return isSlotFlagSet(slot, AB_ATTR_UNBOOTABLE) ? BoolResult::FALSE : BoolResult::TRUE;
}

Return<::android::hardware::boot::V1_0::BoolResult> BootControl::isSlotMarkedSuccessful(uint32_t slot) {
    if (getNumberSlots() == 0) {
        // just return true so that we don't we another call trying to mark it as successful
        // when there is no slots
        return BoolResult::TRUE;
    }
    if (slot >= getNumberSlots())
        return BoolResult::INVALID_SLOT;
    return isSlotFlagSet(slot, AB_ATTR_SUCCESSFUL) ? BoolResult::TRUE : BoolResult::FALSE;
}

Return<void> BootControl::getSuffix(uint32_t slot, getSuffix_cb _hidl_cb) {
    _hidl_cb(slot == 0 ? "_a" : slot == 1 ? "_b" : "");
    return Void();
}

extern "C" IBootControl* HIDL_FETCH_IBootControl(const char*) {
    return new BootControl();
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace boot
}  // namespace hardware
}  // namespace android
