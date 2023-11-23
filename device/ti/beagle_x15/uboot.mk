#
# Copyright 2015 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Targets for building U-Boot
#
# The following must be set before including this file:
# TARGET_UBOOT_SRC must be set the base of a U-Boot tree.
# TARGET_UBOOT_DEFCONFIG must name a base U-Boot config.
# TARGET_UBOOT_ARCH must be set to match U-Boot arch.
#
# The following may be set:
# TARGET_UBOOT_CROSS_COMPILE_PREFIX to override toolchain.
# TARGET_UBOOT_CONFIGS to specify a set of additional U-Boot config files.
# TARGET_UBOOT_ENV to specify an environment to be compiled into uboot.env.
# TARGET_UBOOT_ENV_SIZE to specify the size reserved in U-Boot for the env.

ifeq ($(TARGET_UBOOT_SRC),)
$(error TARGET_UBOOT_SRC not defined but uboot.mk included)
endif

ifeq ($(TARGET_UBOOT_DEFCONFIG),)
$(error TARGET_UBOOT_DEFCONFIG not defined)
endif

ifeq ($(TARGET_UBOOT_ARCH),)
$(error TARGET_UBOOT_ARCH not defined)
endif

ifeq ($(TARGET_UBOOT_ENV_SIZE),)
ifneq ($(TARGET_UBOOT_ENV),)
$(error If TARGET_UBOOT_ENV is set TARGET_UBOOT_ENV_SIZE must also be set. See\
 CONFIG_ENV_SIZE in the selected U-Boot board config file.)
endif
endif

# These may be assigned in target BoardConfig.mk.
TARGET_UBOOT_MAKE_TARGET ?= u-boot.bin
TARGET_UBOOT_COPY_TARGETS ?= $(TARGET_UBOOT_MAKE_TARGET)

# Check target arch.
TARGET_UBOOT_ARCH := $(strip $(TARGET_UBOOT_ARCH))
UBOOT_ARCH := $(TARGET_UBOOT_ARCH)
UBOOT_CC_WRAPPER := $(CC_WRAPPER)
UBOOT_AFLAGS :=
UBOOT_DTC := $(HOST_OUT_EXECUTABLES)/dtc
UBOOT_DTC_ABS := $(abspath $(UBOOT_DTC))

ifeq ($(TARGET_UBOOT_ARCH), arm)
UBOOT_TOOLCHAIN_ABS := $(realpath prebuilts/gcc/$(HOST_PREBUILT_TAG)/arm/arm-linux-androideabi-4.9/bin)
UBOOT_CROSS_COMPILE := $(UBOOT_TOOLCHAIN_ABS)/arm-linux-androidkernel-
UBOOT_SRC_ARCH := arm
UBOOT_CFLAGS :=
else ifeq ($(TARGET_UBOOT_ARCH), arm64)
UBOOT_TOOLCHAIN_ABS := $(realpath prebuilts/gcc/$(HOST_PREBUILT_TAG)/aarch64/aarch64-linux-android-4.9/bin)
UBOOT_CROSS_COMPILE := $(UBOOT_TOOLCHAIN_ABS)/aarch64-linux-androidkernel-
UBOOT_SRC_ARCH := arm64
UBOOT_CFLAGS :=
else ifeq ($(TARGET_UBOOT_ARCH), i386)
UBOOT_TOOLCHAIN_ABS := $(realpath prebuilts/gcc/$(HOST_PREBUILT_TAG)/x86/x86_64-linux-android-4.9/bin)
UBOOT_CROSS_COMPILE := $(UBOOT_TOOLCHAIN_ABS)/x86_64-linux-androidkernel-
UBOOT_SRC_ARCH := x86
UBOOT_CFLAGS := -mstack-protector-guard=tls
else ifeq ($(TARGET_UBOOT_ARCH), x86_64)
UBOOT_TOOLCHAIN_ABS := $(realpath prebuilts/gcc/$(HOST_PREBUILT_TAG)/x86/x86_64-linux-android-4.9/bin)
UBOOT_CROSS_COMPILE := $(UBOOT_TOOLCHAIN_ABS)/x86_64-linux-androidkernel-
UBOOT_SRC_ARCH := x86
UBOOT_CFLAGS := -mstack-protector-guard=tls
else ifeq ($(TARGET_UBOOT_ARCH), mips)
UBOOT_TOOLCHAIN_ABS := $(realpath prebuilts/gcc/$(HOST_PREBUILT_TAG)/mips/mips64el-linux-android-4.9/bin)
UBOOT_CROSS_COMPILE := $(UBOOT_TOOLCHAIN_ABS)/mips64el-linux-androidkernel-
UBOOT_SRC_ARCH := mips
UBOOT_CFLAGS :=
else
$(error U-Boot arch not supported at present)
endif

# Allow caller to override toolchain.
TARGET_UBOOT_CROSS_COMPILE_PREFIX := $(strip $(TARGET_UBOOT_CROSS_COMPILE_PREFIX))
ifneq ($(TARGET_UBOOT_CROSS_COMPILE_PREFIX),)
UBOOT_CROSS_COMPILE := $(TARGET_UBOOT_CROSS_COMPILE_PREFIX)
endif

# Use ccache if requested by USE_CCACHE variable
UBOOT_CROSS_COMPILE_WRAPPER := $(realpath $(UBOOT_CC_WRAPPER)) $(UBOOT_CROSS_COMPILE)

UBOOT_GCC_NOANDROID_CHK := $(shell (echo "int main() {return 0;}" | $(UBOOT_CROSS_COMPILE)gcc -E -mno-android - > /dev/null 2>&1 ; echo $$?))
ifeq ($(strip $(UBOOT_GCC_NOANDROID_CHK)),0)
UBOOT_CFLAGS += -mno-android
UBOOT_AFLAGS += -mno-android
endif

# Set the output for the U-Boot build products.
UBOOT_OUT := $(TARGET_OUT_INTERMEDIATES)/UBOOT_OBJ
UBOOT_ENV_OUT := $(PRODUCT_OUT)/uboot.env

# Merge all U-Boot config file sources.
UBOOT_CONFIG_DIR := device/ti/beagle_x15/uboot_config
UBOOT_CONFIG_DEFAULT := $(TARGET_UBOOT_SRC)/configs/$(TARGET_UBOOT_DEFCONFIG)
UBOOT_CONFIG_RECOMMENDED := $(UBOOT_CONFIG_DIR)/recommended.config
UBOOT_CONFIG_REQUIRED_SRC := $(UBOOT_CONFIG_DIR)/common.config
UBOOT_CONFIG_REQUIRED := $(UBOOT_OUT)/.config.required
UBOOT_CONFIG_SRC := $(UBOOT_CONFIG_DEFAULT) \
  $(UBOOT_CONFIG_RECOMMENDED) \
  $(TARGET_UBOOT_CONFIGS) \
  $(UBOOT_CONFIG_REQUIRED)
UBOOT_CONFIG := $(UBOOT_OUT)/.config
UBOOT_MERGE_CONFIG := device/ti/beagle_x15/mergeconfig.sh
# List of all the Kconfig files from the source code. Changing the Kconfig files
# requires to reassemble the .config, so we add them as dependencies.
UBOOT_SRC_KCONFIGS := \
  $(call find-files-in-subdirs,.,Kconfig,$(TARGET_UBOOT_SRC))

$(UBOOT_OUT):
	mkdir -p $@

define build_uboot
	# We don't support bundling a device tree with U-Boot yet.
	CCACHE_NODIRECT="true" PATH=$$(cd device/ti/beagle_x15/hostcc; pwd):$$PATH \
		BISON_PKGDATADIR=$$(cd prebuilts/build-tools/common/bison; pwd) \
		$(MAKE) -C $(TARGET_UBOOT_SRC) \
		O=$(realpath $(UBOOT_OUT)) \
		CROSS_COMPILE="$(UBOOT_CROSS_COMPILE_WRAPPER)" \
		DTC="$(UBOOT_DTC_ABS)" \
		KCFLAGS="$(UBOOT_CFLAGS)" \
		KAFLAGS="$(UBOOT_AFLAGS)" \
		SOURCE_DATE_EPOCH="$(BUILD_DATETIME_FROM_FILE)" \
		$(1)
endef

# Merge the required U-Boot config elements into a single file.
$(UBOOT_CONFIG_REQUIRED): $(UBOOT_CONFIG_REQUIRED_SRC) | $(UBOOT_OUT)
	$(hide) cat $^ > $@

# Merge the final target U-Boot config.
$(UBOOT_CONFIG): $(UBOOT_CONFIG_SRC) $(UBOOT_SRC_KCONFIGS) | $(UBOOT_OUT)
	$(hide) echo Merging U-Boot config
	$(call build_uboot,mrproper)
	PATH=$$(cd device/ti/beagle_x15/hostcc; pwd):$$PATH \
		BISON_PKGDATADIR=$$(cd prebuilts/build-tools/common/bison; pwd) \
		$(UBOOT_MERGE_CONFIG) \
		$(TARGET_UBOOT_SRC) $(realpath $(UBOOT_OUT)) \
		$(UBOOT_ARCH) $(UBOOT_CROSS_COMPILE) \
		$(UBOOT_CONFIG_SRC)

$(UBOOT_OUT)/$(TARGET_UBOOT_MAKE_TARGET): $(UBOOT_CONFIG) | $(UBOOT_OUT) $(UBOOT_DTC)
	$(hide) echo "Building $(UBOOT_ARCH) U-Boot ..."
	$(call build_uboot,$(TARGET_UBOOT_MAKE_TARGET))

$(addprefix $(UBOOT_OUT)/,$(TARGET_UBOOT_COPY_TARGETS)): $(UBOOT_OUT)/$(TARGET_UBOOT_MAKE_TARGET)

ifneq ($(TARGET_UBOOT_ENV),)
$(UBOOT_ENV_OUT): $(TARGET_UBOOT_ENV) | $(addprefix $(UBOOT_OUT)/,$(TARGET_UBOOT_COPY_TARGETS))
	$(UBOOT_OUT)/tools/mkenvimage -s $(TARGET_UBOOT_ENV_SIZE) -o $@ $<
endif

ALL_DEFAULT_INSTALLED_MODULES += $(addprefix $(PRODUCT_OUT)/,$(TARGET_UBOOT_COPY_TARGETS))

# Produces the actual U-Boot image!
define copy_uboot
$$(PRODUCT_OUT)/$(1): $$(UBOOT_OUT)/$(1) | $$(ACP)
	$$(ACP) -fp $$< $$@
endef
$(foreach _t,$(TARGET_UBOOT_COPY_TARGETS),$(eval $(call copy_uboot,$(_t))))
