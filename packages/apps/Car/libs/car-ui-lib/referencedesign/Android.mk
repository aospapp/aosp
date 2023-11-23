LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

CAR_UI_RRO_SET_NAME := googlecarui
CAR_UI_RESOURCE_DIR := $(LOCAL_PATH)/res
CAR_UI_RRO_TARGETS := \
    com.android.car.ui.paintbooth \
    com.android.car.rotaryplayground \
    com.android.car.themeplayground \
    com.android.car.carlauncher \
    com.android.car.home \
    com.android.car.media \
    com.android.car.radio \
    com.android.car.calendar \
    com.android.car.companiondevicesupport \
    com.android.car.systemupdater \
    com.android.car.dialer \
    com.android.car.linkviewer \
    com.android.car.settings \
    com.android.car.voicecontrol \
    com.android.car.faceenroll \
    com.android.permissioncontroller \
    com.android.settings.intelligence \
    com.google.android.apps.automotive.inputmethod \
    com.google.android.apps.automotive.inputmethod.dev \
    com.google.android.embedded.projection \
    com.google.android.gms \
    com.google.android.packageinstaller \
    com.google.android.carassistant \
    com.google.android.tts \
    com.android.vending \

include packages/apps/Car/libs/car-ui-lib/generate_rros.mk
