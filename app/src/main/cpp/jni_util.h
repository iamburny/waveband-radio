#pragma once

#include <jni.h>

#include <cstddef>
#include <cstdint>

namespace dabtuner {

constexpr const char* LOG_TAG = "DabTunerNative";

JavaVM* javaVm();

/*
 * JNIEnv for the calling thread. Native worker threads are attached on first use and
 * detached automatically when they exit. Returns nullptr if attaching fails.
 */
JNIEnv* envForCurrentThread();

/* Logs and clears a pending Java exception; returns true if there was one. */
bool clearException(JNIEnv* env, const char* where);

/* New byte[] holding a copy of data, or nullptr on allocation failure. */
jbyteArray newByteArray(JNIEnv* env, const void* data, size_t length);

}
