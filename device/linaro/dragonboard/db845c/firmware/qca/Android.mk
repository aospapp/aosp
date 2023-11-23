LOCAL_PATH := $(call my-dir)

include device/linaro/dragonboard/utils.mk

# QCA firmware files copied from
# https://git.kernel.org/pub/scm/linux/kernel/git/firmware/linux-firmware.git/tree/qca
firmware_files_bt := \
    crbtfw21.tlv \
    crnv21.bin

$(foreach f, $(firmware_files_bt), $(call add-qcom-firmware, $(f), $(TARGET_OUT_VENDOR)/firmware/qca/))
