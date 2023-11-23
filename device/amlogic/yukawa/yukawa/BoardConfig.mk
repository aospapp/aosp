include device/amlogic/yukawa/BoardConfigCommon.mk

ifeq ($(TARGET_VIM3L),)
TARGET_BOOTLOADER_BOARD_NAME := sei610
else
TARGET_BOOTLOADER_BOARD_NAME := vim3l
endif

BOARD_USERDATAIMAGE_PARTITION_SIZE := 13416529920

TARGET_BOARD_INFO_FILE := device/amlogic/yukawa/sei610/board-info.txt
