# sepolicy that are shared among devices using whitechapel
BOARD_SEPOLICY_DIRS += device/google/gs101-sepolicy/whitechapel/vendor/google

# unresolved SELinux error log with bug tracking
BOARD_SEPOLICY_DIRS += device/google/gs101-sepolicy/tracking_denials

PRODUCT_PRIVATE_SEPOLICY_DIRS += device/google/gs101-sepolicy/private

# Display
BOARD_SEPOLICY_DIRS += device/google/gs101-sepolicy/display/common
BOARD_SEPOLICY_DIRS += device/google/gs101-sepolicy/display/gs101

# Micro sensor framework (usf)
BOARD_SEPOLICY_DIRS += device/google/gs101-sepolicy/usf

# system_ext
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += device/google/gs101-sepolicy/system_ext/public
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += device/google/gs101-sepolicy/system_ext/private

#
# Pixel-wide
#
#   Dauntless (uses Citadel policy currently)
BOARD_SEPOLICY_DIRS += hardware/google/pixel-sepolicy/citadel

#   Wifi
BOARD_SEPOLICY_DIRS += hardware/google/pixel-sepolicy/wifi_ext

#   PowerStats HAL
BOARD_SEPOLICY_DIRS += hardware/google/pixel-sepolicy/powerstats

# sscoredump
BOARD_SEPOLICY_DIRS += hardware/google/pixel-sepolicy/sscoredump

# Sniffer Logger
BOARD_SEPOLICY_DIRS += hardware/google/pixel-sepolicy/wifi_sniffer

# Wifi Logger
BOARD_SEPOLICY_DIRS += hardware/google/pixel-sepolicy/wifi_logger

# Public
PRODUCT_PUBLIC_SEPOLICY_DIRS += device/google/gs101-sepolicy/public
