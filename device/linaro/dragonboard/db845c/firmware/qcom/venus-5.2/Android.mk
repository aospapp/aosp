LOCAL_PATH := $(call my-dir)

include device/linaro/dragonboard/utils.mk

firmware_files_venus :=	\
    venus.b00		\
    venus.b01		\
    venus.b02		\
    venus.b03		\
    venus.b04		\
    venus.mdt		\
    venus.mbn

$(foreach f, $(firmware_files_venus), $(call add-qcom-firmware, $(f), $(TARGET_OUT_VENDOR)/firmware/qcom/venus-5.2/))
