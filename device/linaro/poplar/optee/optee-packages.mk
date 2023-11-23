# 1. Configration for optee OS
OPTEE_PLATFORM ?= poplar
OPTEE_PLATFORM_FLAVOR ?= poplar
OPTEE_CFG_ARM64_CORE ?= n 
OPTEE_TA_TARGETS ?= ta_arm32
OPTEE_OS_DIR ?= optee/optee_os
OPTEE_EXTRA_FLAGS ?= CFG_TEE_CORE_LOG_LEVEL=3 CFG_TEE_TA_LOG_LEVEL=3 DEBUG=1
#CROSS_COMPILE64 ?= $(ANDROID_BUILD_TOP)/prebuilts/gcc/linux-x86/aarch64/aarch64-linux-android-4.9/bin/aarch64-linux-android-
# BUILD_OPTEE_MK will be used by client app ,e.g xtest and optee-widevine-ref
BUILD_OPTEE_MK := $(OPTEE_OS_DIR)/mk/aosp_optee.mk


# 2. packages

# OP TEE client library and service
PRODUCT_PACKAGES += libteec \
                    tee-supplicant \

# optee_test and TA
PRODUCT_PACKAGES += xtest
# os_test
PRODUCT_PACKAGES += 5b9e0e40-2636-11e1-ad9e-0002a5d5c51b.ta
# concurrent_large
PRODUCT_PACKAGES += 5ce0c432-0ab0-40e5-a056-782ca0e6aba2.ta
# sha_perf
PRODUCT_PACKAGES += 614789f2-39c0-4ebf-b235-92b32ac107ed.ta
# storage2
PRODUCT_PACKAGES += 731e279e-aafb-4575-a771-38caa6f0cca6.ta
# storage
PRODUCT_PACKAGES += b689f2a7-8adf-477a-9f99-32e90c0ad0a2.ta
# create_fail_test
PRODUCT_PACKAGES += c3f6e2c0-3548-11e1-b86c-0800200c9a66.ta
# crypt
PRODUCT_PACKAGES += cb3e5ba0-adf1-11e0-998b-0002a5d5c51b.ta
# rpc_test
PRODUCT_PACKAGES += d17f73a0-36ef-11e1-984a-0002a5d5c51b.ta
# concurrent
PRODUCT_PACKAGES += e13010e0-2ae1-11e5-896a-0002a5d5c51b.ta
# aes_perf
PRODUCT_PACKAGES += e626662e-c0e2-485c-b8c8-09fbce6edf3d.ta
# sims
PRODUCT_PACKAGES += e6a33ed4-562b-463a-bb7e-ff5e15a493c8.ta
# storage_benchmark
PRODUCT_PACKAGES += f157cda0-550c-11e5-a6fa-0002a5d5c51b.ta
# socket
PRODUCT_PACKAGES += 873bcd08-c2c3-11e6-a937-d0bf9c45c61c.ta
# sdp-basic - atm default disabled for hikey
PRODUCT_PACKAGES += 12345678-5b69-11e4-9dbb-101f74f00099.ta

# optee examples
PRODUCT_PACKAGES += optee_example_helloworld
PRODUCT_PACKAGES += 8aaaf200-2450-11e4-abe2-0002a5d5c51b.ta
PRODUCT_PACKAGES += optee_example_random
PRODUCT_PACKAGES += b6c53aba-9669-4668-a7f2-205629d00f86.ta
PRODUCT_PACKAGES += optee_example_aes
PRODUCT_PACKAGES += 5dbac793-f574-4871-8ad3-04331ec17f24.ta
PRODUCT_PACKAGES += optee_example_hotp
PRODUCT_PACKAGES += 484d4143-2d53-4841-3120-4a6f636b6542.ta
