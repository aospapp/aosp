/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "DeviceAsWebcamServiceManager.h"
#include <DeviceAsWebcamNative.h>
#include <UVCProvider.h>
#include <android/hardware_buffer_jni.h>
#include <log/log.h>

namespace android {
namespace webcam {

DeviceAsWebcamServiceManager* DeviceAsWebcamServiceManager::kInstance =
        new DeviceAsWebcamServiceManager();

bool DeviceAsWebcamServiceManager::shouldStartService() {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> l(mSerializationLock);
    if (mServiceRunning) {
        ALOGW("Service already running, don't start it again.");
        return false;
    }

    return (UVCProvider::getVideoNode().length() != 0);
}

int DeviceAsWebcamServiceManager::setupServicesAndStartListening(JNIEnv* env, jobject javaService) {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> l(mSerializationLock);
    if (mUVCProvider == nullptr) {
        mUVCProvider = std::make_shared<UVCProvider>();
    }
    // Set up UVC stack
    if ((mUVCProvider->init() != Status::OK) || (mUVCProvider->startService() != Status::OK)) {
        ALOGE("%s: Unable to init/ start service", __FUNCTION__);
        return -1;
    }
    mJavaService = env->NewGlobalRef(javaService);
    mServiceRunning = true;
    return 0;
}

int DeviceAsWebcamServiceManager::encodeImage(JNIEnv* env, jobject hardwareBuffer,
                                              jlong timestamp) {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> l(mSerializationLock);
    if (!mServiceRunning) {
        ALOGE("%s called, but native service is not running. Ignoring call.", __FUNCTION__);
        return -1;
    }
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    return mUVCProvider->encodeImage(buffer, timestamp);
}

void DeviceAsWebcamServiceManager::setStreamConfig(bool mjpeg, uint32_t width, uint32_t height,
                                                   uint32_t fps) {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> l(mSerializationLock);
    if (!mServiceRunning) {
        ALOGE("%s called but java foreground service is not running. No-op-ing out", __FUNCTION__);
        return;
    }
    DeviceAsWebcamNative::setStreamConfig(mJavaService, mjpeg, width, height, fps);
}

void DeviceAsWebcamServiceManager::startStreaming() {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> l(mSerializationLock);
    if (!mServiceRunning) {
        ALOGE("%s called but java foreground service is not running. No-op-ing out", __FUNCTION__);
        return;
    }
    DeviceAsWebcamNative::startStreaming(mJavaService);
}

void DeviceAsWebcamServiceManager::stopStreaming() {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> l(mSerializationLock);
    if (!mServiceRunning) {
        ALOGE("%s called but java foreground service is not running. No-op-ing out", __FUNCTION__);
        return;
    }
    DeviceAsWebcamNative::stopStreaming(mJavaService);
}

void DeviceAsWebcamServiceManager::returnImage(long timestamp) {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> l(mSerializationLock);
    if (!mServiceRunning) {
        ALOGE("%s called but java foreground service is not running. No-op-ing out", __FUNCTION__);
        return;
    }
    DeviceAsWebcamNative::returnImage(mJavaService, timestamp);
}

void DeviceAsWebcamServiceManager::stopService() {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> l(mSerializationLock);
    if (!mServiceRunning) {
        ALOGE("%s called but java foreground service is not running. No-op-ing out", __FUNCTION__);
        return;
    }
    // Wait for previous stopService java call to finish (note that this should almost
    // never actually block unless something goes really wrong).
    if (mJniThread.joinable()) {
        mJniThread.join();
    }

    // Send off the event on a background thread. This prevents the caller thread being stuck on
    // Java logic.
    mJniThread = DeviceAsWebcamNative::createJniAttachedThread(&DeviceAsWebcamNative::stopService,
                                                               mJavaService);
    // Don't reset any state as the Java service's onDestroy callback will handle that for us.
}

// Called by the Java Foreground service when it is being destroyed. The UVCProvider may or may not
// be running at this point.
void DeviceAsWebcamServiceManager::onDestroy() {
    ALOGV("%s", __FUNCTION__);
    std::lock_guard<std::mutex> l(mSerializationLock);
    if (!mServiceRunning) {
        ALOGE("%s called after Java Service was already considered destroyed. No-op-ing out.",
              __FUNCTION__);
        return;
    }

    JNIEnv* env = DeviceAsWebcamNative::getJNIEnvOrAbort();
    // reset all non-static state
    mUVCProvider = nullptr;
    env->DeleteGlobalRef(mJavaService);  // let Java Service be GC'ed by the JVM
    mJavaService = nullptr;
    mServiceRunning = false;
}

}  // namespace webcam
}  // namespace android
