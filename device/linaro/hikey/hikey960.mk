ifndef TARGET_KERNEL_USE
TARGET_KERNEL_USE=5.10
endif
LOCAL_KERNEL_HOME ?= device/linaro/hikey-kernel/hikey960/$(TARGET_KERNEL_USE)
TARGET_PREBUILT_KERNEL := $(LOCAL_KERNEL_HOME)/Image.gz-dtb
TARGET_PREBUILT_DTB := $(LOCAL_KERNEL_HOME)/hi3660-hikey960.dtb

ifndef HIKEY_USES_GKI
  ifeq ($(TARGET_KERNEL_USE), mainline)
    HIKEY_USES_GKI := true
  else
    KERNEL_MAJ := $(word 1, $(subst ., ,$(TARGET_KERNEL_USE)))
    # kernel since 5.X should support GKI
    # only 4.X kernels do not support GKI
    ifneq ($(KERNEL_MAJ), 4)
      HIKEY_USES_GKI := true
    endif
  endif
endif

# only kernels after 5.10 support KVM
ifndef HIKEY960_ENABLE_AVF
  ifeq ($(TARGET_KERNEL_USE), mainline)
    HIKEY960_ENABLE_AVF := true
  else
    KERNEL_MAJ := $(word 1, $(subst ., ,$(TARGET_KERNEL_USE)))
    KERNEL_MIN := $(word 2, $(subst ., ,$(TARGET_KERNEL_USE)))
    KER_GT_5 := $(shell [ $(KERNEL_MAJ) -gt 5 ] && echo true)
    KER_GE_5_10 := $(shell [ $(KERNEL_MIN) -ge 10 ] && echo true)

    ifeq ($(KER_GT_5), true)
      HIKEY960_ENABLE_AVF := true
    else
      ifeq ($(KERNEL_MAJ), 5)
        # for kernel after 5.10
        ifeq ($(KER_GE_5_10),true)
          HIKEY960_ENABLE_AVF := true
        endif
      endif # end for 5.10
    endif # end for 5.X
  endif # end for mainline
endif # end for HIKEY960_ENABLE_AVF

include $(LOCAL_PATH)/vendor-package-ver.mk

# Inherit the common device configuration
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, device/linaro/hikey/hikey960/device-hikey960.mk)
$(call inherit-product, device/linaro/hikey/device-common.mk)
$(call inherit-product-if-exists, vendor/linaro/hikey960/$(EXPECTED_LINARO_VENDOR_VERSION)/hikey960.mk)

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

PRODUCT_SOONG_NAMESPACES += \
  vendor/linaro/hikey960/$(EXPECTED_LINARO_VENDOR_VERSION)/mali/bifrost
