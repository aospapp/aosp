PRODUCT_SOONG_NAMESPACES += vendor/samsung_slsi/codec2

PRODUCT_PACKAGES += \
	samsung.hardware.media.c2@1.2-service \
	codec2.vendor.base.policy \
	codec2.vendor.ext.policy \
	libExynosC2ComponentStore \
	libExynosC2H264Dec \
	libExynosC2H264Enc \
	libExynosC2HevcDec \
	libExynosC2HevcEnc \
	libExynosC2Mpeg4Dec \
	libExynosC2Mpeg4Enc \
	libExynosC2H263Dec \
	libExynosC2H263Enc \
	libExynosC2Vp8Dec \
	libExynosC2Vp8Enc \
	libExynosC2Vp9Dec \
	libExynosC2Vp9Enc

BOARD_VENDOR_SEPOLICY_DIRS += device/google/gs-common/mediacodec/samsung/sepolicy
