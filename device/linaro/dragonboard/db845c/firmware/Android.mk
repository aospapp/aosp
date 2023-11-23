ifneq ($(filter db845c, $(TARGET_BOARD_PLATFORM)),)

LOCAL_PATH := $(call my-dir)

include device/linaro/dragonboard/utils.mk

# Firmware files copied over from
# http://releases.linaro.org/96boards/dragonboard845c/qualcomm/firmware/RB3_firmware_20190529180356-v3.zip

# Adreno
firmware_files :=	\
    a630_gmu.bin	\
    a630_sqe.fw		\
    a630_zap.b00	\
    a630_zap.b01	\
    a630_zap.b02	\
    a630_zap.elf	\
    a630_zap.mdt	\
    a630_zap.mbn

# DSP (adsp+cdsp)
sdm845_firmware_files +=	\
    adsp.b00		\
    adsp.b01		\
    adsp.b02		\
    adsp.b03		\
    adsp.b04		\
    adsp.b05		\
    adsp.b06		\
    adsp.b07		\
    adsp.b08		\
    adsp.b09		\
    adsp.b10		\
    adsp.b11		\
    adsp.b12		\
    adsp.b13		\
    adsp.mdt		\
    adsp.mbn		\
    adspr.jsn		\
    adspua.jsn		\
    cdsp.b00		\
    cdsp.b01		\
    cdsp.b02		\
    cdsp.b03		\
    cdsp.b04		\
    cdsp.b05		\
    cdsp.b06		\
    cdsp.b08		\
    cdsp.mdt		\
    cdsp.mbn		\
    cdspr.jsn		\


# USB
firmware_files +=	\
    K2026090.mem

# Wlan
sdm845_firmware_files +=	\
    bdwlan.102		\
    bdwlan.104		\
    bdwlan.105		\
    bdwlan.106		\
    bdwlan.107		\
    bdwlan.108		\
    bdwlan.109		\
    bdwlan.10b		\
    bdwlan.10c		\
    bdwlan.b04		\
    bdwlan.b07		\
    bdwlan.b09		\
    bdwlan.b0a		\
    bdwlan.b0b		\
    bdwlan.b0d		\
    bdwlan.b0e		\
    bdwlan.b0f		\
    bdwlan.b14		\
    bdwlan.b15		\
    bdwlan.b30		\
    bdwlan.b31		\
    bdwlan.b32		\
    bdwlan.b33		\
    bdwlan.b34		\
    bdwlan.b35		\
    bdwlan.b36		\
    bdwlan.b37		\
    bdwlan.b38		\
    bdwlan.b39		\
    bdwlan.b3a		\
    bdwlan.b3c		\
    bdwlan.b3d		\
    bdwlan.b3e		\
    bdwlan.b3f		\
    bdwlan.b41		\
    bdwlan.b42		\
    bdwlan.b45		\
    bdwlan.b70		\
    bdwlan.bin		\
    bdwlan.txt		\
    wlanmdsp.mbn

ath10k_firmware_files += \
    board-2.bin		\
    firmware-5.bin	\
    notice.txt_wlanmdsp

# I2C/SPI fix
firmware_files +=	\
    devcfg.mbn

# wifi/modem/mba
sdm845_firmware_files +=	\
    mba.mbn		\
    modem.mbn		\
    modemuw.jsn

# License
firmware_files +=	\
    LICENSE.qcom.txt

$(foreach f, $(firmware_files), $(call add-qcom-firmware, $(f), $(TARGET_OUT_VENDOR)/firmware/))
$(foreach f, $(sdm845_firmware_files), $(call add-qcom-firmware, $(f), $(TARGET_OUT_VENDOR)/firmware/qcom/sdm845/))
$(foreach f, $(ath10k_firmware_files), $(call add-qcom-firmware, $(f), $(TARGET_OUT_VENDOR)/firmware/ath10k/WCN3990/hw1.0/))

include $(call all-makefiles-under,$(LOCAL_PATH))
endif
