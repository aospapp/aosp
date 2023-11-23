#
# This is a workaround for Bluetooth not working on OnePlus7 Pro. See b/139486342
#

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := BluetoothQtiSymlink

lib_dir := $(PRODUCT_OUT)/system/lib
bluetooth_qti := libbluetooth_qti.so
bluetooth := libbluetooth.so
bluetooth_qti_path := $(lib_dir)/$(bluetooth_qti)
bluetooth_path := $(lib_dir)/$(bluetooth)

$(bluetooth_qti_path): $(bluetooth_path)
	cd $(lib_dir) && ln -sf $(bluetooth) $(bluetooth_qti)

droid: $(bluetooth_qti_path)
