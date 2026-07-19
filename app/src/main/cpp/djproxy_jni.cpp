// ==========================================================================
// djproxy_jni.cpp  (engine lane)
//
// Thin JNI bridge from ai.darshj.djproxy.engine.HevBridge (a Kotlin `object`,
// so the native methods are instance methods of the singleton -> the JNI
// symbols carry a jobject `thiz`) to the vendored, MIT-licensed
// hev-socks5-tunnel C library.
//
// The whole surface is three calls:
//   runBlocking(yaml, tunFd) -> int   : runs hev's loop, BLOCKS until quit/error
//   quit()                            : asks the loop to stop (any thread)
//   statsRaw()               -> long[4]: [tx_packets, tx_bytes, rx_packets, rx_bytes]
// ==========================================================================

#include <jni.h>
#include <string.h>
#include <stddef.h>
#include <android/log.h>

extern "C" {
#include "hev-main.h"
}

#define LOG_TAG "djproxy-engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_ai_darshj_djproxy_engine_HevBridge_runBlocking(
        JNIEnv *env, jobject /*thiz*/, jstring configYaml, jint tunFd) {
    if (configYaml == nullptr) {
        LOGE("runBlocking: null config");
        return -1;
    }
    const char *cfg = env->GetStringUTFChars(configYaml, nullptr);
    if (cfg == nullptr) {
        LOGE("runBlocking: GetStringUTFChars failed");
        return -1;
    }
    const unsigned int len = (unsigned int) strlen(cfg);
    LOGI("hev loop starting (tunFd=%d, cfg=%u bytes)", (int) tunFd, len);

    // Blocks until hev_socks5_tunnel_quit() is called or the loop errors.
    // hev dups nothing: it reads/writes the fd we pass and does not close the
    // caller's copy, so lifecycle of the fd stays owned by the main process.
    const int ret = hev_socks5_tunnel_main_from_str(
            reinterpret_cast<const unsigned char *>(cfg), len, (int) tunFd);

    env->ReleaseStringUTFChars(configYaml, cfg);
    LOGI("hev loop exited (ret=%d)", ret);
    return (jint) ret;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_darshj_djproxy_engine_HevBridge_quit(JNIEnv * /*env*/, jobject /*thiz*/) {
    LOGI("quit requested");
    hev_socks5_tunnel_quit();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ai_darshj_djproxy_engine_HevBridge_statsRaw(JNIEnv *env, jobject /*thiz*/) {
    size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;
    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);

    jlong values[4];
    values[0] = (jlong) tx_packets;
    values[1] = (jlong) tx_bytes;
    values[2] = (jlong) rx_packets;
    values[3] = (jlong) rx_bytes;

    jlongArray out = env->NewLongArray(4);
    if (out == nullptr) return nullptr; // OOM pending
    env->SetLongArrayRegion(out, 0, 4, values);
    return out;
}
