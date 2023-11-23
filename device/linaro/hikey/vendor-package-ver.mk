HELPER_SCRIPT=./device/linaro/hikey/vendor-package-ver.sh
EXPECTED_LINARO_VENDOR_VERSION := $(shell $(HELPER_SCRIPT) ver)
VND_PKG_URL := $(shell $(HELPER_SCRIPT) url)
LINARO_VENDOR_PATH := vendor/linaro/
