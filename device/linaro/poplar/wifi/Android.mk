$(eval $(call declare-1p-copy-files,device/linaro/poplar/wifi,.conf))
$(eval $(call declare-1p-copy-files,device/linaro/poplar/wifi,.rc))

ifeq ($(BOARD_WLAN_DEVICE), rtl)
    include $(call all-subdir-makefiles)
endif
