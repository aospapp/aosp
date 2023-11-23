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

#define LOG_NAMESPACE "TeX.tag."
#define LOG_TAG "TeX"

#include <log/log.h>
#include <nativehelper/scoped_local_ref.h>
#include <nativehelper/scoped_utf_chars.h>
#include <utils/hash/farmhash.h>

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

static jclass gStringClass = nullptr;

/**
 * Class:     com_android_modules_expresslog_Utils
 * Method:    hashString
 * Signature: (Ljava/lang/String;)J
 */
static jlong hashString(JNIEnv* env, jclass /*class*/, jstring metricNameObj) {
    ScopedUtfChars name(env, metricNameObj);
    if (name.c_str() == nullptr) {
        return 0;
    }

    return static_cast<jlong>(farmhash::Fingerprint64(name.c_str(), name.size()));
}

static const JNINativeMethod gMethods[] = {
        {"hashString", "(Ljava/lang/String;)J", (void*)hashString},
};

namespace android {

int register_com_android_modules_expresslog_Utils(JNIEnv* env) {
    static const char* const kUtilsClassName = "com/android/modules/expresslog/Utils";
    static const char* const kStringClassName = "java/lang/String";

    ScopedLocalRef<jclass> utilsCls(env, env->FindClass(kUtilsClassName));
    if (utilsCls.get() == nullptr) {
        ALOGE("jni expresslog registration failure, class not found '%s'", kUtilsClassName);
        return JNI_ERR;
    }

    jclass stringClass = env->FindClass(kStringClassName);
    if (stringClass == nullptr) {
        ALOGE("jni expresslog registration failure, class not found '%s'", kStringClassName);
        return JNI_ERR;
    }
    gStringClass = static_cast<jclass>(env->NewGlobalRef(stringClass));
    if (gStringClass == nullptr) {
        ALOGE("jni expresslog Unable to create global reference '%s'", kStringClassName);
        return JNI_ERR;
    }

    const jint count = sizeof(gMethods) / sizeof(gMethods[0]);
    int status = env->RegisterNatives(utilsCls.get(), gMethods, count);
    if (status < 0) {
        ALOGE("jni expresslog registration failure, status: %d", status);
        return JNI_ERR;
    }
    return JNI_VERSION_1_4;
}

}  // namespace android
