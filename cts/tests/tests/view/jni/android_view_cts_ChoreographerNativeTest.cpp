/*
 * Copyright (C) 2016 The Android Open Source Project
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
 *
 */

#include <android/choreographer.h>

#include <jni.h>
#include <sys/time.h>
#include <time.h>

#include <chrono>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <thread>

#define LOG_TAG "ChoreographerNative"

#define ASSERT(condition, format, args...) \
        if (!(condition)) { \
            fail(env, format, ## args); \
            return; \
        }


using namespace std::chrono_literals;

static constexpr std::chrono::nanoseconds NOMINAL_VSYNC_PERIOD{16ms};
static constexpr std::chrono::nanoseconds DELAY_PERIOD{NOMINAL_VSYNC_PERIOD * 5};
static constexpr std::chrono::nanoseconds ZERO{std::chrono::nanoseconds::zero()};

static std::mutex gLock;
struct Callback {
    Callback(const char* name): name(name) {}
    const char* name;
    int count{0};
    std::chrono::nanoseconds frameTime{0LL};
};

static void frameCallback64(int64_t frameTimeNanos, void* data) {
    std::lock_guard<std::mutex> _l(gLock);
    Callback* cb = static_cast<Callback*>(data);
    cb->count++;
    cb->frameTime = std::chrono::nanoseconds{frameTimeNanos};
}

static void frameCallback(long frameTimeNanos, void* data) {
    frameCallback64((int64_t) frameTimeNanos, data);
}

static std::chrono::nanoseconds now() {
    return std::chrono::steady_clock::now().time_since_epoch();
}

static void fail(JNIEnv* env, const char* format, ...) {
    va_list args;

    va_start(args, format);
    char *msg;
    int rc = vasprintf(&msg, format, args);
    va_end(args);

    jclass exClass;
    const char *className = "java/lang/AssertionError";
    exClass = env->FindClass(className);
    env->ThrowNew(exClass, msg);
    free(msg);
}

static void verifyCallback(JNIEnv* env, Callback* cb, int expectedCount,
                           std::chrono::nanoseconds startTime, std::chrono::nanoseconds maxTime) {
    std::lock_guard<std::mutex> _l{gLock};
    ASSERT(cb->count == expectedCount, "Choreographer failed to invoke '%s' %d times - actual: %d",
            cb->name, expectedCount, cb->count);
    if (maxTime > ZERO) {
        auto duration = cb->frameTime - startTime;
        ASSERT(duration < maxTime, "Callback '%s' has incorrect frame time in invocation %d",
                cb->name, expectedCount);
    }
}

static jlong android_view_cts_ChoreographerNativeTest_getChoreographer(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> _l{gLock};
    return reinterpret_cast<jlong>(AChoreographer_getInstance());
}

static jboolean android_view_cts_ChoreographerNativeTest_prepareChoreographerTests(JNIEnv*, jclass,
        jlong choreographerPtr) {
    std::lock_guard<std::mutex> _l{gLock};
    AChoreographer* choreographer = reinterpret_cast<AChoreographer*>(choreographerPtr);
    return choreographer != nullptr;
}

static void android_view_cts_ChoreographerNativeTest_testPostCallback64WithoutDelayEventuallyRunsCallback(
        JNIEnv* env, jclass, jlong choreographerPtr) {
    AChoreographer* choreographer = reinterpret_cast<AChoreographer*>(choreographerPtr);
    Callback* cb1 = new Callback("cb1");
    Callback* cb2 = new Callback("cb2");
    auto start = now();

    AChoreographer_postFrameCallback64(choreographer, frameCallback64, cb1);
    AChoreographer_postFrameCallback64(choreographer, frameCallback64, cb2);
    std::this_thread::sleep_for(NOMINAL_VSYNC_PERIOD * 3);

    verifyCallback(env, cb1, 1, start, NOMINAL_VSYNC_PERIOD * 3);
    verifyCallback(env, cb2, 1, start, NOMINAL_VSYNC_PERIOD * 3);
    {
        std::lock_guard<std::mutex> _l{gLock};
        auto delta = cb2->frameTime - cb1->frameTime;
        ASSERT(delta == ZERO || delta > ZERO && delta < NOMINAL_VSYNC_PERIOD * 2,
                "Callback 1 and 2 have frame times too large of a delta in frame times");
    }

    AChoreographer_postFrameCallback64(choreographer, frameCallback64, cb1);
    start = now();
    std::this_thread::sleep_for(NOMINAL_VSYNC_PERIOD * 3);
    verifyCallback(env, cb1, 2, start, NOMINAL_VSYNC_PERIOD * 3);
    verifyCallback(env, cb2, 1, start, ZERO);
}

static void android_view_cts_ChoreographerNativeTest_testPostCallback64WithDelayEventuallyRunsCallback(
        JNIEnv* env, jclass, jlong choreographerPtr) {
    AChoreographer* choreographer = reinterpret_cast<AChoreographer*>(choreographerPtr);
    Callback* cb1 = new Callback("cb1");
    auto start = now();

    auto delay = std::chrono::duration_cast<std::chrono::milliseconds>(DELAY_PERIOD).count();
    AChoreographer_postFrameCallbackDelayed64(choreographer, frameCallback64, cb1, delay);

    std::this_thread::sleep_for(NOMINAL_VSYNC_PERIOD * 3);
    verifyCallback(env, cb1, 0, start, ZERO);

    std::this_thread::sleep_for(DELAY_PERIOD);
    verifyCallback(env, cb1, 1, start, DELAY_PERIOD + NOMINAL_VSYNC_PERIOD * 3);
}

static void android_view_cts_ChoreographerNativeTest_testPostCallbackWithoutDelayEventuallyRunsCallback(
        JNIEnv* env, jclass, jlong choreographerPtr) {
    AChoreographer* choreographer = reinterpret_cast<AChoreographer*>(choreographerPtr);
    Callback* cb1 = new Callback("cb1");
    Callback* cb2 = new Callback("cb2");
    auto start = now();
    const auto delay = NOMINAL_VSYNC_PERIOD * 3;
    // Delay calculations are known to be broken on 32-bit systems (overflow),
    // so we skip testing the delay on such systems by setting this to ZERO.
    const auto delayToTest = sizeof(long) == sizeof(int64_t) ? delay : ZERO;

    AChoreographer_postFrameCallback(choreographer, frameCallback, cb1);
    AChoreographer_postFrameCallback(choreographer, frameCallback, cb2);
    std::this_thread::sleep_for(delay);

    verifyCallback(env, cb1, 1, start, delayToTest);
    verifyCallback(env, cb2, 1, start, delayToTest);

    // This delta can only be reliably calculated on 64-bit systems.  We skip this
    // part of the test on systems known to be broken.
    if (sizeof(long) == sizeof(int64_t)) {
        std::lock_guard<std::mutex> _l{gLock};
        auto delta = cb2->frameTime - cb1->frameTime;
        ASSERT(delta == ZERO || delta > ZERO && delta < NOMINAL_VSYNC_PERIOD * 2,
                "Callback 1 and 2 have frame times too large of a delta in frame times");
    }

    AChoreographer_postFrameCallback(choreographer, frameCallback, cb1);
    start = now();
    std::this_thread::sleep_for(delay);

    verifyCallback(env, cb1, 2, start, delayToTest);
    verifyCallback(env, cb2, 1, start, ZERO);
}

static void android_view_cts_ChoreographerNativeTest_testPostCallbackWithDelayEventuallyRunsCallback(
        JNIEnv* env, jclass, jlong choreographerPtr) {
    if (sizeof(long) != sizeof(int64_t)) {
        // skip test for known broken states.
        return;
    }

    AChoreographer* choreographer = reinterpret_cast<AChoreographer*>(choreographerPtr);
    Callback* cb1 = new Callback("cb1");
    auto start = now();

    auto delay = std::chrono::duration_cast<std::chrono::milliseconds>(DELAY_PERIOD).count();
    AChoreographer_postFrameCallbackDelayed(choreographer, frameCallback, cb1, delay);

    std::this_thread::sleep_for(NOMINAL_VSYNC_PERIOD * 3);
    verifyCallback(env, cb1, 0, start, ZERO);

    std::this_thread::sleep_for(DELAY_PERIOD);
    const auto delayToTest =
            sizeof(long) == sizeof(int64_t) ? DELAY_PERIOD + NOMINAL_VSYNC_PERIOD * 3 : ZERO;
    verifyCallback(env, cb1, 1, start, delayToTest);
}

static void android_view_cts_ChoreographerNativeTest_testPostCallbackMixedWithoutDelayEventuallyRunsCallback(
        JNIEnv* env, jclass, jlong choreographerPtr) {
    AChoreographer* choreographer = reinterpret_cast<AChoreographer*>(choreographerPtr);
    Callback* cb1 = new Callback("cb1");
    Callback* cb64 = new Callback("cb64");
    auto start = now();

    AChoreographer_postFrameCallback(choreographer, frameCallback, cb1);
    AChoreographer_postFrameCallback64(choreographer, frameCallback64, cb64);
    std::this_thread::sleep_for(NOMINAL_VSYNC_PERIOD * 3);

    verifyCallback(env, cb1, 1, start, ZERO);
    verifyCallback(env, cb64, 1, start, NOMINAL_VSYNC_PERIOD * 3);

    // This delta can only be reliably calculated on 64-bit systems.  We skip this
    // part of the test on systems known to be broken.
    if (sizeof(long) == sizeof(int64_t)) {
        std::lock_guard<std::mutex> _l{gLock};
        auto delta = cb64->frameTime - cb1->frameTime;
        ASSERT(delta == ZERO || delta > ZERO && delta < NOMINAL_VSYNC_PERIOD * 2,
                "Callback 1 and 2 have frame times too large of a delta in frame times");
    }

    AChoreographer_postFrameCallback64(choreographer, frameCallback64, cb64);
    start = now();
    std::this_thread::sleep_for(NOMINAL_VSYNC_PERIOD * 3);
    verifyCallback(env, cb1, 1, start, ZERO);
    verifyCallback(env, cb64, 2, start, NOMINAL_VSYNC_PERIOD * 3);
}

static void android_view_cts_ChoreographerNativeTest_testPostCallbackMixedWithDelayEventuallyRunsCallback(
        JNIEnv* env, jclass, jlong choreographerPtr) {
    AChoreographer* choreographer = reinterpret_cast<AChoreographer*>(choreographerPtr);
    Callback* cb1 = new Callback("cb1");
    Callback* cb64 = new Callback("cb64");
    auto start = now();

    auto delay = std::chrono::duration_cast<std::chrono::milliseconds>(DELAY_PERIOD).count();
    AChoreographer_postFrameCallbackDelayed(choreographer, frameCallback, cb1, delay);
    AChoreographer_postFrameCallbackDelayed64(choreographer, frameCallback64, cb64, delay);

    std::this_thread::sleep_for(NOMINAL_VSYNC_PERIOD * 3);
    verifyCallback(env, cb1, 0, start, ZERO);
    verifyCallback(env, cb64, 0, start, ZERO);

    std::this_thread::sleep_for(DELAY_PERIOD);
    verifyCallback(env, cb64, 1, start, DELAY_PERIOD + NOMINAL_VSYNC_PERIOD * 3);
    const auto delayToTestFor32Bit =
            sizeof(long) == sizeof(int64_t) ? DELAY_PERIOD + NOMINAL_VSYNC_PERIOD * 3 : ZERO;
    verifyCallback(env, cb1, 1, start, delayToTestFor32Bit);
}

static JNINativeMethod gMethods[] = {
    {  "nativeGetChoreographer", "()J",
            (void *) android_view_cts_ChoreographerNativeTest_getChoreographer},
    {  "nativePrepareChoreographerTests", "(J)Z",
            (void *) android_view_cts_ChoreographerNativeTest_prepareChoreographerTests},
    {  "nativeTestPostCallback64WithoutDelayEventuallyRunsCallbacks", "(J)V",
            (void *) android_view_cts_ChoreographerNativeTest_testPostCallback64WithoutDelayEventuallyRunsCallback},
    {  "nativeTestPostCallback64WithDelayEventuallyRunsCallbacks", "(J)V",
            (void *) android_view_cts_ChoreographerNativeTest_testPostCallback64WithDelayEventuallyRunsCallback},
    {  "nativeTestPostCallbackWithoutDelayEventuallyRunsCallbacks", "(J)V",
            (void *) android_view_cts_ChoreographerNativeTest_testPostCallbackWithoutDelayEventuallyRunsCallback},
    {  "nativeTestPostCallbackWithDelayEventuallyRunsCallbacks", "(J)V",
            (void *) android_view_cts_ChoreographerNativeTest_testPostCallbackWithDelayEventuallyRunsCallback},
    {  "nativeTestPostCallbackMixedWithoutDelayEventuallyRunsCallbacks", "(J)V",
            (void *) android_view_cts_ChoreographerNativeTest_testPostCallbackMixedWithoutDelayEventuallyRunsCallback},
    {  "nativeTestPostCallbackMixedWithDelayEventuallyRunsCallbacks", "(J)V",
            (void *) android_view_cts_ChoreographerNativeTest_testPostCallbackMixedWithDelayEventuallyRunsCallback},
};

int register_android_view_cts_ChoreographerNativeTest(JNIEnv* env)
{
    jclass clazz = env->FindClass("android/view/cts/ChoreographerNativeTest");
    return env->RegisterNatives(clazz, gMethods,
            sizeof(gMethods) / sizeof(JNINativeMethod));
}
