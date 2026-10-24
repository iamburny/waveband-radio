// JNI bridge between com.matt.dabtuner.backend.omri.OmriNative and the omri-usb C API.

#include <android/log.h>
#include <jni.h>

#include <cstring>
#include <string>

#include "jni_util.h"
#include "omri_api.h"

using dabtuner::clearException;
using dabtuner::envForCurrentThread;
using dabtuner::newByteArray;

namespace {

/* Owns the Java callbacks object for one open tuner; passed to the library as its user pointer. */
struct TunerContext {
    jobject callbacks = nullptr;
    jmethodID onState = nullptr;
    jmethodID onScanProgress = nullptr;
    jmethodID onServiceFound = nullptr;
    jmethodID onServiceStarted = nullptr;
    jmethodID onServiceStopped = nullptr;
    jmethodID onReception = nullptr;
    jmethodID onAudio = nullptr;
    jmethodID onDynamicLabel = nullptr;
    jmethodID onSlideshow = nullptr;
    jmethodID onAnnouncementSupport = nullptr;
    jmethodID onAnnouncement = nullptr;
    omri_tuner* tuner = nullptr;
};

TunerContext* context(jlong handle) {
    return reinterpret_cast<TunerContext*>(handle);
}

bool lookupMethods(JNIEnv* env, jobject callbacks, TunerContext& ctx) {
    struct Lookup {
        jmethodID* id;
        const char* name;
        const char* signature;
    };
    const Lookup lookups[] = {
            {&ctx.onState, "onState", "(I)V"},
            {&ctx.onScanProgress, "onScanProgress", "(II)V"},
            {&ctx.onServiceFound, "onServiceFound", "(JIII[B[B[BIZIIII[B)V"},
            {&ctx.onServiceStarted, "onServiceStarted", "(J)V"},
            {&ctx.onServiceStopped, "onServiceStopped", "(J)V"},
            {&ctx.onReception, "onReception", "(ZI)V"},
            {&ctx.onAudio, "onAudio", "([BIIIZZ)V"},
            {&ctx.onDynamicLabel, "onDynamicLabel", "([B)V"},
            {&ctx.onSlideshow, "onSlideshow", "([BI)V"},
            {&ctx.onAnnouncementSupport, "onAnnouncementSupport", "(IJI[B)V"},
            {&ctx.onAnnouncement, "onAnnouncement", "(IIIZ)V"},
    };
    jclass cls = env->GetObjectClass(callbacks);
    bool ok = true;
    for(const Lookup& lookup : lookups) {
        *lookup.id = env->GetMethodID(cls, lookup.name, lookup.signature);
        if(clearException(env, lookup.name) || *lookup.id == nullptr) {
            ok = false;
            break;
        }
    }
    env->DeleteLocalRef(cls);
    return ok;
}

jbyteArray utf8Bytes(JNIEnv* env, const char* text) {
    return newByteArray(env, text != nullptr ? text : "", text != nullptr ? std::strlen(text) : 0);
}

void cbState(void* user, int state) {
    auto* ctx = static_cast<TunerContext*>(user);
    if(JNIEnv* env = envForCurrentThread()) {
        env->CallVoidMethod(ctx->callbacks, ctx->onState, state);
        clearException(env, "onState");
    }
}

void cbScanProgress(void* user, int index, int count) {
    auto* ctx = static_cast<TunerContext*>(user);
    if(JNIEnv* env = envForCurrentThread()) {
        env->CallVoidMethod(ctx->callbacks, ctx->onScanProgress, index, count);
        clearException(env, "onScanProgress");
    }
}

void cbServiceFound(void* user, const omri_service_info* info) {
    auto* ctx = static_cast<TunerContext*>(user);
    JNIEnv* env = envForCurrentThread();
    if(env == nullptr) {
        return;
    }
    jbyteArray label = utf8Bytes(env, info->label);
    jbyteArray shortLabel = utf8Bytes(env, info->short_label);
    jbyteArray ensembleLabel = utf8Bytes(env, info->ensemble_label);
    jbyteArray clusterIds = newByteArray(env, info->cluster_ids, info->cluster_count);
    if(label != nullptr && shortLabel != nullptr && ensembleLabel != nullptr && clusterIds != nullptr) {
        env->CallVoidMethod(ctx->callbacks, ctx->onServiceFound,
                            static_cast<jlong>(info->service_id),
                            static_cast<jint>(info->ensemble_id),
                            static_cast<jint>(info->ensemble_ecc),
                            static_cast<jint>(info->frequency_khz),
                            label, shortLabel, ensembleLabel,
                            static_cast<jint>(info->programme_type),
                            static_cast<jboolean>(info->is_programme_service != 0),
                            static_cast<jint>(info->audio_codec),
                            static_cast<jint>(info->bitrate_kbps),
                            static_cast<jint>(info->subchannel_id),
                            static_cast<jint>(info->announcement_flags),
                            clusterIds);
        clearException(env, "onServiceFound");
    }
    if(label != nullptr) env->DeleteLocalRef(label);
    if(shortLabel != nullptr) env->DeleteLocalRef(shortLabel);
    if(ensembleLabel != nullptr) env->DeleteLocalRef(ensembleLabel);
    if(clusterIds != nullptr) env->DeleteLocalRef(clusterIds);
}

void cbServiceStarted(void* user, uint32_t serviceId) {
    auto* ctx = static_cast<TunerContext*>(user);
    if(JNIEnv* env = envForCurrentThread()) {
        env->CallVoidMethod(ctx->callbacks, ctx->onServiceStarted, static_cast<jlong>(serviceId));
        clearException(env, "onServiceStarted");
    }
}

void cbServiceStopped(void* user, uint32_t serviceId) {
    auto* ctx = static_cast<TunerContext*>(user);
    if(JNIEnv* env = envForCurrentThread()) {
        env->CallVoidMethod(ctx->callbacks, ctx->onServiceStopped, static_cast<jlong>(serviceId));
        clearException(env, "onServiceStopped");
    }
}

void cbReception(void* user, int locked, int level) {
    auto* ctx = static_cast<TunerContext*>(user);
    if(JNIEnv* env = envForCurrentThread()) {
        env->CallVoidMethod(ctx->callbacks, ctx->onReception, static_cast<jboolean>(locked != 0), level);
        clearException(env, "onReception");
    }
}

void cbAudio(void* user, const uint8_t* data, size_t length, int codec, int channels, int sampleRate, int sbr, int ps) {
    auto* ctx = static_cast<TunerContext*>(user);
    JNIEnv* env = envForCurrentThread();
    if(env == nullptr) {
        return;
    }
    jbyteArray frame = newByteArray(env, data, length);
    if(frame == nullptr) {
        return;
    }
    env->CallVoidMethod(ctx->callbacks, ctx->onAudio, frame, codec, channels, sampleRate,
                        static_cast<jboolean>(sbr != 0), static_cast<jboolean>(ps != 0));
    clearException(env, "onAudio");
    env->DeleteLocalRef(frame);
}

void cbDynamicLabel(void* user, const char* utf8) {
    auto* ctx = static_cast<TunerContext*>(user);
    JNIEnv* env = envForCurrentThread();
    if(env == nullptr) {
        return;
    }
    jbyteArray text = utf8Bytes(env, utf8);
    if(text == nullptr) {
        return;
    }
    env->CallVoidMethod(ctx->callbacks, ctx->onDynamicLabel, text);
    clearException(env, "onDynamicLabel");
    env->DeleteLocalRef(text);
}

void cbSlideshow(void* user, const uint8_t* data, size_t length, int subType) {
    auto* ctx = static_cast<TunerContext*>(user);
    JNIEnv* env = envForCurrentThread();
    if(env == nullptr) {
        return;
    }
    jbyteArray image = newByteArray(env, data, length);
    if(image == nullptr) {
        return;
    }
    env->CallVoidMethod(ctx->callbacks, ctx->onSlideshow, image, subType);
    clearException(env, "onSlideshow");
    env->DeleteLocalRef(image);
}

void cbAnnouncementSupport(void* user, uint16_t ensembleId, uint32_t serviceId, uint16_t flags, const uint8_t* clusterIds, size_t clusterCount) {
    auto* ctx = static_cast<TunerContext*>(user);
    JNIEnv* env = envForCurrentThread();
    if(env == nullptr) {
        return;
    }
    jbyteArray clusters = newByteArray(env, clusterIds, clusterCount);
    if(clusters == nullptr) {
        return;
    }
    env->CallVoidMethod(ctx->callbacks, ctx->onAnnouncementSupport, static_cast<jint>(ensembleId),
                        static_cast<jlong>(serviceId), static_cast<jint>(flags), clusters);
    clearException(env, "onAnnouncementSupport");
    env->DeleteLocalRef(clusters);
}

void cbAnnouncement(void* user, uint8_t clusterId, uint16_t flags, uint8_t subchannelId, int active) {
    auto* ctx = static_cast<TunerContext*>(user);
    if(JNIEnv* env = envForCurrentThread()) {
        env->CallVoidMethod(ctx->callbacks, ctx->onAnnouncement, static_cast<jint>(clusterId), static_cast<jint>(flags),
                            static_cast<jint>(subchannelId), static_cast<jboolean>(active != 0));
        clearException(env, "onAnnouncement");
    }
}

}

extern "C" {

JNIEXPORT void JNICALL
Java_com_matt_dabtuner_backend_omri_OmriNative_nativeSetLogging(JNIEnv*, jclass, jboolean enabled) {
    omri_set_logging(enabled ? 1 : 0);
}

JNIEXPORT jlong JNICALL
Java_com_matt_dabtuner_backend_omri_OmriNative_nativeOpen(JNIEnv* env, jclass, jint fd, jstring deviceName,
                                                          jobject callbacks, jobjectArray infoOut) {
    auto* ctx = new TunerContext();
    if(!lookupMethods(env, callbacks, *ctx)) {
        delete ctx;
        return 0;
    }
    ctx->callbacks = env->NewGlobalRef(callbacks);

    omri_callbacks cb{};
    cb.user = ctx;
    cb.state = cbState;
    cb.scan_progress = cbScanProgress;
    cb.service_found = cbServiceFound;
    cb.service_started = cbServiceStarted;
    cb.service_stopped = cbServiceStopped;
    cb.reception = cbReception;
    cb.audio = cbAudio;
    cb.dynamic_label = cbDynamicLabel;
    cb.slideshow = cbSlideshow;
    cb.announcement_support = cbAnnouncementSupport;
    cb.announcement = cbAnnouncement;

    const char* name = env->GetStringUTFChars(deviceName, nullptr);
    if(name == nullptr) {
        env->ExceptionClear();
    }
    char info[256] = {0};
    ctx->tuner = omri_tuner_open(fd, name != nullptr ? name : "", &cb, info, sizeof(info));
    if(name != nullptr) {
        env->ReleaseStringUTFChars(deviceName, name);
    }

    __android_log_print(ANDROID_LOG_INFO, dabtuner::LOG_TAG, "omri_tuner_open(fd=%d): %s (%s)", fd,
                        ctx->tuner != nullptr ? "ok" : "failed", info);
    if(infoOut != nullptr && env->GetArrayLength(infoOut) > 0) {
        jstring text = env->NewStringUTF(info);
        if(text != nullptr) {
            env->SetObjectArrayElement(infoOut, 0, text);
            env->DeleteLocalRef(text);
        }
        clearException(env, "nativeOpen info");
    }

    if(ctx->tuner == nullptr) {
        env->DeleteGlobalRef(ctx->callbacks);
        delete ctx;
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_matt_dabtuner_backend_omri_OmriNative_nativeClose(JNIEnv* env, jclass, jlong handle) {
    TunerContext* ctx = context(handle);
    if(ctx == nullptr) {
        return;
    }
    // Returns only after every library thread has stopped, so no callback can use ctx afterwards.
    omri_tuner_close(ctx->tuner);
    env->DeleteGlobalRef(ctx->callbacks);
    delete ctx;
}

JNIEXPORT void JNICALL
Java_com_matt_dabtuner_backend_omri_OmriNative_nativeStartScan(JNIEnv*, jclass, jlong handle) {
    if(TunerContext* ctx = context(handle)) omri_tuner_start_scan(ctx->tuner);
}

JNIEXPORT void JNICALL
Java_com_matt_dabtuner_backend_omri_OmriNative_nativeStopScan(JNIEnv*, jclass, jlong handle) {
    if(TunerContext* ctx = context(handle)) omri_tuner_stop_scan(ctx->tuner);
}

JNIEXPORT void JNICALL
Java_com_matt_dabtuner_backend_omri_OmriNative_nativeStartService(JNIEnv*, jclass, jlong handle, jint frequencyKHz,
                                                                  jint ensembleId, jint ensembleEcc, jlong serviceId) {
    if(TunerContext* ctx = context(handle)) {
        omri_tuner_start_service(ctx->tuner, static_cast<uint32_t>(frequencyKHz), static_cast<uint16_t>(ensembleId),
                                 static_cast<uint8_t>(ensembleEcc), static_cast<uint32_t>(serviceId));
    }
}

JNIEXPORT void JNICALL
Java_com_matt_dabtuner_backend_omri_OmriNative_nativeStopService(JNIEnv*, jclass, jlong handle) {
    if(TunerContext* ctx = context(handle)) omri_tuner_stop_service(ctx->tuner);
}

}
