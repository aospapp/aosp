ifndef TARGET_KERNEL_USE
TARGET_KERNEL_USE=5.4
endif
LOCAL_KERNEL_HOME ?= device/linaro/hikey-kernel/hikey960/$(TARGET_KERNEL_USE)
TARGET_PREBUILT_KERNEL := $(LOCAL_KERNEL_HOME)/Image.gz-dtb
TARGET_PREBUILT_DTB := $(LOCAL_KERNEL_HOME)/hi3660-hikey960.dtb

ifeq ($(TARGET_KERNEL_USE), 4.4)
  HIKEY_USE_DRM_HWCOMPOSER := false
  HIKEY_USE_LEGACY_TI_BLUETOOTH := true
else
  ifeq ($(TARGET_KERNEL_USE), 4.9)
    HIKEY_USE_DRM_HWCOMPOSER := false
  else
    HIKEY_USE_DRM_HWCOMPOSER := true
  endif
  HIKEY_USE_LEGACY_TI_BLUETOOTH := false
endif

ifndef HIKEY_USES_GKI
  ifeq ($(TARGET_KERNEL_USE), 5.4)
    HIKEY_USES_GKI := true
  endif
endif

#
# Inherit the common device configuration
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, device/linaro/hikey/hikey960/device-hikey960.mk)
$(call inherit-product, device/linaro/hikey/device-common.mk)

PRODUCT_PROPERTY_OVERRIDES += ro.opengles.version=196608

#
# Overrides
PRODUCT_NAME := hikey960
PRODUCT_DEVICE := hikey960
PRODUCT_BRAND := Android
PRODUCT_MODEL := AOSP on hikey960

ifneq ($(HIKEY_USES_GKI),)
  HIKEY_MOD_DIR := $(LOCAL_KERNEL_HOME)
  HIKEY_MODS := $(wildcard $(HIKEY_MOD_DIR)/*.ko)
  SDCARDFS_KO := $(wildcard $(HIKEY_MOD_DIR)/sdcardfs*.ko)
  CMA_HEAP_KO := $(wildcard $(HIKEY_MOD_DIR)/cma_heap.ko)
  DEFERRED_FREE_KO := $(wildcard $(HIKEY_MOD_DIR)/deferred-free-helper.ko)
  PAGE_POOL_KO := $(wildcard $(HIKEY_MOD_DIR)/page_pool.ko)
  SYSTEM_HEAP_KO := $(wildcard $(HIKEY_MOD_DIR)/system_heap.ko)
  ION_CMA_HEAP_KO := $(wildcard $(HIKEY_MOD_DIR)/ion_cma_heap*.ko)
  ifneq ($(HIKEY_MODS),)
    BOARD_VENDOR_KERNEL_MODULES += $(HIKEY_MODS)
    BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
        $(CMA_HEAP_KO) \
        $(SYSTEM_HEAP_KO) \
        $(DEFERRED_FREE_KO) \
        $(PAGE_POOL_KO) \
        $(ION_CMA_HEAP_KO) \
        $(SDCARDFS_KO)
  endif
endif
