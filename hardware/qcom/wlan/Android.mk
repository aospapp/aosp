#set TARGET_USES_HARDWARE_QCOM_WLAN to false to disable this project.
ifneq ($(TARGET_USES_HARDWARE_QCOM_WLAN),false)
include $(call all-subdir-makefiles)
endif
