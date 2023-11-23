# sepolicy that are shared among devices using whitechapel
BOARD_SEPOLICY_DIRS += device/google/tangorpro-sepolicy/vendor
BOARD_SEPOLICY_DIRS += device/google/tangorpro-sepolicy/tracking_denials

# fingerprint
BOARD_SEPOLICY_DIRS += device/google/tangorpro-sepolicy/fingerprint_capacitance

# for mediashell
PRODUCT_PUBLIC_SEPOLICY_DIRS += device/google/atv/audio_proxy/sepolicy/public
BOARD_VENDOR_SEPOLICY_DIRS += device/google/atv/audio_proxy/sepolicy/vendor
PRODUCT_PRIVATE_SEPOLICY_DIRS += vendor/google/gms/src/sepolicy/tv

# system_ext
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += device/google/tangorpro-sepolicy/system_ext/private
