// JNI wrapper around libmpg123 for DAB's MPEG-1/2 Audio Layer II (com.matt.dabtuner.backend.omri.Mp2Decoder).

#include <android/log.h>
#include <jni.h>

#include "jni_util.h"
#include "mpg123.h"

namespace {

mpg123_handle* handleOf(jlong handle) {
    return reinterpret_cast<mpg123_handle*>(handle);
}

}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_matt_dabtuner_backend_omri_Mp2Decoder_nativeCreate(JNIEnv*, jclass) {
    int error = MPG123_OK;
    mpg123_handle* mh = mpg123_new(nullptr, &error);
    if(mh == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, dabtuner::LOG_TAG, "mpg123_new failed: %s", mpg123_plain_strerror(error));
        return 0;
    }
    mpg123_param2(mh, MPG123_ADD_FLAGS, MPG123_QUIET, 0.0);

    // DAB uses 48 kHz (MPEG-1) and 24 kHz (MPEG-2 LSF); always ask for 16-bit output.
    mpg123_format_none(mh);
    const long rates[] = {48000, 24000, 32000, 44100, 22050, 16000};
    for(long rate : rates) {
        mpg123_format(mh, rate, MPG123_MONO | MPG123_STEREO, MPG123_ENC_SIGNED_16);
    }

    if(mpg123_open_feed(mh) != MPG123_OK) {
        __android_log_print(ANDROID_LOG_ERROR, dabtuner::LOG_TAG, "mpg123_open_feed failed: %s", mpg123_strerror(mh));
        mpg123_delete(mh);
        return 0;
    }
    return reinterpret_cast<jlong>(mh);
}

/*
 * Feeds one or more complete or partial frames and writes the decoded PCM to output.
 * format receives {sampleRate, channels} once the stream format is known.
 * Returns the number of PCM bytes written, or -1 on a decoder error.
 */
JNIEXPORT jint JNICALL
Java_com_matt_dabtuner_backend_omri_Mp2Decoder_nativeDecode(JNIEnv* env, jclass, jlong handle, jbyteArray input,
                                                           jint inputLength, jbyteArray output, jintArray format) {
    mpg123_handle* mh = handleOf(handle);
    if(mh == nullptr || inputLength < 0 || inputLength > env->GetArrayLength(input)) {
        return -1;
    }

    jbyte* in = env->GetByteArrayElements(input, nullptr);
    if(in == nullptr) {
        return -1;
    }
    int result = mpg123_feed(mh, reinterpret_cast<const unsigned char*>(in), static_cast<size_t>(inputLength));
    env->ReleaseByteArrayElements(input, in, JNI_ABORT);
    if(result != MPG123_OK) {
        return -1;
    }

    const jsize capacity = env->GetArrayLength(output);
    jbyte* out = env->GetByteArrayElements(output, nullptr);
    if(out == nullptr) {
        return -1;
    }
    size_t total = 0;
    int status = MPG123_OK;
    while(total < static_cast<size_t>(capacity)) {
        size_t done = 0;
        status = mpg123_read(mh, reinterpret_cast<unsigned char*>(out) + total, static_cast<size_t>(capacity) - total, &done);
        total += done;
        if(status == MPG123_NEW_FORMAT) {
            long rate = 0;
            int channels = 0, encoding = 0;
            mpg123_getformat(mh, &rate, &channels, &encoding);
            jint values[2] = {static_cast<jint>(rate), static_cast<jint>(channels)};
            env->SetIntArrayRegion(format, 0, 2, values);
            continue;
        }
        if(status != MPG123_OK) {
            break;
        }
    }
    env->ReleaseByteArrayElements(output, out, 0);

    if(status == MPG123_NEED_MORE || status == MPG123_OK || status == MPG123_DONE) {
        return static_cast<jint>(total);
    }
    __android_log_print(ANDROID_LOG_WARN, dabtuner::LOG_TAG, "mpg123_read: %s", mpg123_strerror(mh));
    return total > 0 ? static_cast<jint>(total) : -1;
}

JNIEXPORT void JNICALL
Java_com_matt_dabtuner_backend_omri_Mp2Decoder_nativeRelease(JNIEnv*, jclass, jlong handle) {
    if(mpg123_handle* mh = handleOf(handle)) {
        mpg123_delete(mh);
    }
}

}
