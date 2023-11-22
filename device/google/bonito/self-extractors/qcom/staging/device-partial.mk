# Copyright 2018 The Android Open Source Project
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

# AOSP packages required by the blobs
PRODUCT_PACKAGES := \
    QtiTelephonyService

#  blob(s) necessary for bonito hardware
PRODUCT_COPY_FILES := \
    vendor/qcom/bonito/proprietary/andsfCne.xml:system/etc/cne/andsfCne.xml:qcom \
    vendor/qcom/bonito/proprietary/cneapiclient.xml:system/etc/permissions/cneapiclient.xml:qcom \
    vendor/qcom/bonito/proprietary/com.quicinc.cne.xml:system/etc/permissions/com.quicinc.cne.xml:qcom \
    vendor/qcom/bonito/proprietary/embms.xml:system/etc/permissions/embms.xml:qcom \
    vendor/qcom/bonito/proprietary/LteDirectDiscovery.xml:system/etc/permissions/LteDirectDiscovery.xml:qcom \
    vendor/qcom/bonito/proprietary/qcrilhook.xml:system/etc/permissions/qcrilhook.xml:qcom \
    vendor/qcom/bonito/proprietary/radioconfiginterface.xml:system/etc/permissions/radioconfiginterface.xml:qcom \
    vendor/qcom/bonito/proprietary/radioconfig.xml:system/etc/permissions/radioconfig.xml:qcom \
    vendor/qcom/bonito/proprietary/telephonyservice.xml:system/etc/permissions/telephonyservice.xml:qcom \
    vendor/qcom/bonito/proprietary/uimremoteclient.xml:system/etc/permissions/uimremoteclient.xml:qcom \
    vendor/qcom/bonito/proprietary/uimremoteserver.xml:system/etc/permissions/uimremoteserver.xml:qcom \
    vendor/qcom/bonito/proprietary/UimService.xml:system/etc/permissions/UimService.xml:qcom \
    vendor/qcom/bonito/proprietary/com.android.future.usb.accessory.jar:system/framework/com.android.future.usb.accessory.jar:qcom \
    vendor/qcom/bonito/proprietary/com.android.location.provider.jar:system/framework/com.android.location.provider.jar:qcom \
    vendor/qcom/bonito/proprietary/com.android.mediadrm.signer.jar:system/framework/com.android.mediadrm.signer.jar:qcom \
    vendor/qcom/bonito/proprietary/com.qualcomm.qti.uceservice-V2.0-java.jar:system/framework/com.qualcomm.qti.uceservice-V2.0-java.jar:qcom \
    vendor/qcom/bonito/proprietary/javax.obex.jar:system/framework/javax.obex.jar:qcom \
    vendor/qcom/bonito/proprietary/qcrilhook.jar:system/framework/qcrilhook.jar:qcom \
    vendor/qcom/bonito/proprietary/QtiTelephonyServicelibrary.jar:system/framework/QtiTelephonyServicelibrary.jar:qcom \
    vendor/qcom/bonito/proprietary/uimremoteclientlibrary.jar:system/framework/uimremoteclientlibrary.jar:qcom \
    vendor/qcom/bonito/proprietary/uimremoteserverlibrary.jar:system/framework/uimremoteserverlibrary.jar:qcom \
    vendor/qcom/bonito/proprietary/vendor.qti.hardware.alarm-V1.0-java.jar:system/framework/vendor.qti.hardware.alarm-V1.0-java.jar:qcom \
    vendor/qcom/bonito/proprietary/vendor.qti.hardware.soter-V1.0-java.jar:system/framework/vendor.qti.hardware.soter-V1.0-java.jar:qcom \
    vendor/qcom/bonito/proprietary/lib64/libadsprpc_system.so:system/lib64/libadsprpc_system.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/libcdsprpc_system.so:system/lib64/libcdsprpc_system.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/libDiagService.so:system/lib64/libDiagService.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/libdiag_system.so:system/lib64/libdiag_system.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/libGPQTEEC_system.so:system/lib64/libGPQTEEC_system.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/libGPTEE_system.so:system/lib64/libGPTEE_system.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/libimscamera_jni.so:system/lib64/libimscamera_jni.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/libimsmedia_jni.so:system/lib64/libimsmedia_jni.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/lib-imsvideocodec.so:system/lib64/lib-imsvideocodec.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/lib-imsvtextutils.so:system/lib64/lib-imsvtextutils.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/lib-imsvt.so:system/lib64/lib-imsvt.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/lib-imsvtutils.so:system/lib64/lib-imsvtutils.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/libmdsprpc_system.so:system/lib64/libmdsprpc_system.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/libminui.so:system/lib64/libminui.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/libQTEEConnector_system.so:system/lib64/libQTEEConnector_system.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/librcc.so:system/lib64/librcc.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/libsdm-disp-apis.so:system/lib64/libsdm-disp-apis.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/libsdsprpc_system.so:system/lib64/libsdsprpc_system.so:qcom \
    vendor/qcom/bonito/proprietary/lib64/libsecureui_svcsock_system.so:system/lib64/libsecureui_svcsock_system.so:qcom \
    vendor/qcom/bonito/proprietary/libadsprpc_system.so:system/lib/libadsprpc_system.so:qcom \
    vendor/qcom/bonito/proprietary/libcdsprpc_system.so:system/lib/libcdsprpc_system.so:qcom \
    vendor/qcom/bonito/proprietary/libdiag_system.so:system/lib/libdiag_system.so:qcom \
    vendor/qcom/bonito/proprietary/libGPQTEEC_system.so:system/lib/libGPQTEEC_system.so:qcom \
    vendor/qcom/bonito/proprietary/libGPTEE_system.so:system/lib/libGPTEE_system.so:qcom \
    vendor/qcom/bonito/proprietary/libimscamera_jni.so:system/lib/libimscamera_jni.so:qcom \
    vendor/qcom/bonito/proprietary/libimsmedia_jni.so:system/lib/libimsmedia_jni.so:qcom \
    vendor/qcom/bonito/proprietary/lib-imsvideocodec.so:system/lib/lib-imsvideocodec.so:qcom \
    vendor/qcom/bonito/proprietary/lib-imsvtextutils.so:system/lib/lib-imsvtextutils.so:qcom \
    vendor/qcom/bonito/proprietary/lib-imsvt.so:system/lib/lib-imsvt.so:qcom \
    vendor/qcom/bonito/proprietary/lib-imsvtutils.so:system/lib/lib-imsvtutils.so:qcom \
    vendor/qcom/bonito/proprietary/libmdsprpc_system.so:system/lib/libmdsprpc_system.so:qcom \
    vendor/qcom/bonito/proprietary/libminui.so:system/lib/libminui.so:qcom \
    vendor/qcom/bonito/proprietary/libnl.so:system/lib/libnl.so:qcom \
    vendor/qcom/bonito/proprietary/libQTEEConnector_system.so:system/lib/libQTEEConnector_system.so:qcom \
    vendor/qcom/bonito/proprietary/librcc.so:system/lib/librcc.so:qcom \
    vendor/qcom/bonito/proprietary/libsdm-disp-apis.so:system/lib/libsdm-disp-apis.so:qcom \
    vendor/qcom/bonito/proprietary/libsdsprpc_system.so:system/lib/libsdsprpc_system.so:qcom \
    vendor/qcom/bonito/proprietary/libsecureui_svcsock_system.so:system/lib/libsecureui_svcsock_system.so:qcom \
    vendor/qcom/bonito/proprietary/libsns_low_lat_stream_skel.so:system/lib/rfsa/adsp/libsns_low_lat_stream_skel.so:qcom \
