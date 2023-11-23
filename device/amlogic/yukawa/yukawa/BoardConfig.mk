include device/amlogic/yukawa/BoardConfigCommon.mk

ifeq ($(TARGET_VIM3), true)
TARGET_BOOTLOADER_BOARD_NAME := vim3
TARGET_BOARD_INFO_FILE := device/amlogic/yukawa/vim/board-info-vim3.txt
else ifeq ($(TARGET_VIM3L), true)
TARGET_BOOTLOADER_BOARD_NAME := vim3l
TARGET_BOARD_INFO_FILE := device/amlogic/yukawa/vim/board-info-vim3l.txt
else
TARGET_BOOTLOADER_BOARD_NAME := sei610
TARGET_BOARD_INFO_FILE := device/amlogic/yukawa/sei610/board-info.txt
endif


ifeq ($(TARGET_USE_AB_SLOT), true)
BOARD_USERDATAIMAGE_PARTITION_SIZE := 10730078208
else
BOARD_USERDATAIMAGE_PARTITION_SIZE := 12870221824
endif
