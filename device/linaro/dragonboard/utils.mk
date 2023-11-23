# Put common makefile/build helper Macros here

# $(1): The source file name in LOCAL_PATH.
#       It also serves as the module name and the dest file name.
# $(2): Module installation path.
define add-qcom-firmware
$(eval include $(CLEAR_VARS))\
$(eval LOCAL_MODULE := $(1))\
$(eval LOCAL_SRC_FILES := $(1))\
$(eval LOCAL_MODULE_STEM := $(1))\
$(eval LOCAL_MODULE_CLASS := DATA)\
$(eval LOCAL_MODULE_TAGS := optional)\
$(eval LOCAL_MODULE_PATH := $(2))\
$(eval include $(BUILD_PREBUILT))
endef
