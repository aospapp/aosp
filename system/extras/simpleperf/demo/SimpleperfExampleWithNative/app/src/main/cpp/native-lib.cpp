#include <jni.h>

#include <pthread.h>
#include <stdlib.h>

#include <string>



extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_simpleperf_simpleperfexamplewithnative_MainActivity_stringFromJNI(
        JNIEnv *env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

static void ThrowErrnoException(JNIEnv* env, const char* function_name, int err) {
    jclass cls = env->FindClass("android/system/ErrnoException");
    if (cls == nullptr) {
        return;
    }
    jmethodID cid = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;I)V");
    if (cid == nullptr) {
        return;
    }
    jstring msg = env->NewStringUTF(function_name);
    if (msg == nullptr) {
        return;
    }
    jthrowable obj = (jthrowable)env->NewObject(cls, cid, msg, err);
    if (obj == nullptr) {
        return;
    }
    env->Throw(obj);
}

int CallFunction(int a) {
    return a + atoi("1");
}

static void* BusyLoopThread(void*) {
    volatile int i = 0;
    while (true) {
        i = CallFunction(i);
    }
    return nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_simpleperf_simpleperfexamplewithnative_MainActivity_createBusyThreadFromJNI(
        JNIEnv *env,
        jobject /* this */) {
    pthread_t thread;
    int ret = pthread_create(&thread, nullptr, BusyLoopThread, nullptr);
    if (ret) {
        ThrowErrnoException(env, "pthread_create", ret);
        return;
    }
}
