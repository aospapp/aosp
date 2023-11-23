# Install firmware files copied over from
# http://releases.linaro.org/96boards/dragonboard845c/qualcomm/firmware/RB3_firmware_20190529180356-v3.zip

# Adreno
PRODUCT_PACKAGES :=	\
    a630_gmu.bin	\
    a630_sqe.fw	\
    a630_zap.b00	\
    a630_zap.b01	\
    a630_zap.b02	\
    a630_zap.elf	\
    a630_zap.mdt	\
    a630_zap.mbn

# DSP (adsp+cdsp)
PRODUCT_PACKAGES +=	\
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
    cdspr.jsn

# USB (USB Host to PCIE)
# For Ethernet and one of the USB-A host port to work
PRODUCT_PACKAGES +=	\
    K2026090.mem

# I2C/SPI fix
PRODUCT_PACKAGES +=	\
    devcfg.mbn


PRODUCT_PACKAGES +=	\
    mba.mbn		\
    modem.mbn		\
    modemuw.jsn

# Venus
# Video encoder/decoder accelerator
PRODUCT_PACKAGES +=	\
    venus.b00		\
    venus.b01		\
    venus.b02		\
    venus.b03		\
    venus.b04		\
    venus.mdt		\
    venus.mbn

# Wlan
PRODUCT_PACKAGES +=	\
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

#ath10k
PRODUCT_PACKAGES +=	\
    board-2.bin		\
    firmware-5.bin	\
    notice.txt_wlanmdsp

# License
# Necessary to bundle license with firmware files
PRODUCT_PACKAGES +=	\
    LICENSE.qcom.txt

# Bluetooth
# Firmware files (qca/cr*) copied from
# https://git.kernel.org/pub/scm/linux/kernel/git/firmware/linux-firmware.git/tree/qca
PRODUCT_PACKAGES +=	\
    crbtfw21.tlv	\
    crnv21.bin

#Have to duplicate the zap as path changed between kernels
PRODUCT_COPY_FILES += \
    device/linaro/dragonboard/db845c/firmware/a630_gmu.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/qcom/sdm845/a630_gmu.bin \
    device/linaro/dragonboard/db845c/firmware/a630_sqe.fw:$(TARGET_COPY_OUT_VENDOR)/firmware/qcom/sdm845/a630_sqe.fw \
    device/linaro/dragonboard/db845c/firmware/a630_zap.b00:$(TARGET_COPY_OUT_VENDOR)/firmware/qcom/sdm845/a630_zap.b00 \
    device/linaro/dragonboard/db845c/firmware/a630_zap.b01:$(TARGET_COPY_OUT_VENDOR)/firmware/qcom/sdm845/a630_zap.b01 \
    device/linaro/dragonboard/db845c/firmware/a630_zap.b02:$(TARGET_COPY_OUT_VENDOR)/firmware/qcom/sdm845/a630_zap.b02 \
    device/linaro/dragonboard/db845c/firmware/a630_zap.elf:$(TARGET_COPY_OUT_VENDOR)/firmware/qcom/sdm845/a630_zap.elf \
    device/linaro/dragonboard/db845c/firmware/a630_zap.mdt:$(TARGET_COPY_OUT_VENDOR)/firmware/qcom/sdm845/a630_zap.mbn
