#include "jni_util.h"

#include <android/log.h>
#include <pthread.h>

#include "omri_api.h"
#include "mpg123.h"

namespace dabtuner {
namespace {

JavaVM* g_vm = nullptr;
pthread_key_t g_detachKey;

void detachThread(void*) {
    if(g_vm != nullptr) {
        g_vm->DetachCurrentThread();
    }
}

}

JavaVM* javaVm() {
    return g_vm;
}

JNIEnv* envForCurrentThread() {
    JNIEnv* env = nullptr;
    jint state = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if(state == JNI_OK) {
        return env;
    }
    if(state != JNI_EDETACHED) {
        return nullptr;
    }
    JavaVMAttachArgs args{JNI_VERSION_1_6, "omri-worker", nullptr};
    if(g_vm->AttachCurrentThreadAsDaemon(&env, &args) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Could not attach native thread to the VM");
        return nullptr;
    }
    // Any non-null value makes the key's destructor run when the thread exits.
    pthread_setspecific(g_detachKey, env);
    return env;
}

bool clearException(JNIEnv* env, const char* where) {
    if(!env->ExceptionCheck()) {
        return false;
    }
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Java exception in %s", where);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

jbyteArray newByteArray(JNIEnv* env, const void* data, size_t length) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(length));
    if(array == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    if(length > 0) {
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(length), static_cast<const jbyte*>(data));
    }
    return array;
}

}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    dabtuner::g_vm = vm;
    pthread_key_create(&dabtuner::g_detachKey, dabtuner::detachThread);

    unsigned major = 0, minor = 0, patch = 0;
    const char* mpg123Version = mpg123_distversion(&major, &minor, &patch);
    __android_log_print(ANDROID_LOG_INFO, dabtuner::LOG_TAG, "Native DAB backend loaded: %s, mpg123 %s",
                        omri_version(), mpg123Version != nullptr ? mpg123Version : "?");
    return JNI_VERSION_1_6;
}
