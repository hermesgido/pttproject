#include <jni.h>
#include <string>

static JavaVM* g_vm = nullptr;
static jobject g_callback = nullptr;

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static void callOnReady(JNIEnv* env, const std::string& dtlsJson, const std::string& rtpJson) {
    if (!g_callback) return;
    jclass cbCls = env->GetObjectClass(g_callback);
    jmethodID mid = env->GetMethodID(cbCls, "onReady", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (!mid) return;
    jstring jDtls = env->NewStringUTF(dtlsJson.c_str());
    jstring jRtp = env->NewStringUTF(rtpJson.c_str());
    env->CallVoidMethod(g_callback, mid, jDtls, jRtp);
    env->DeleteLocalRef(jDtls);
    env->DeleteLocalRef(jRtp);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ropex_pptapp_mediasoup_MediasoupController_nativeSetOnDtlsRtpReadyCallback(
        JNIEnv* env, jobject /*thiz*/, jobject callback) {
    if (g_callback) {
        env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }
    if (callback) {
        g_callback = env->NewGlobalRef(callback);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_ropex_pptapp_mediasoup_MediasoupController_nativeInitDevice(
        JNIEnv* /*env*/, jobject /*thiz*/, jstring /*rtpCapsJson*/) {
}

extern "C" JNIEXPORT void JNICALL
Java_com_ropex_pptapp_mediasoup_MediasoupController_nativeCreateTransport(
        JNIEnv* /*env*/, jobject /*thiz*/, jstring /*direction*/, jstring /*transportId*/, jstring /*iceParamsJson*/, jstring /*iceCandidatesJson*/, jstring /*dtlsParamsJson*/) {
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ropex_pptapp_mediasoup_MediasoupController_nativePrepareProducer(
        JNIEnv* env, jobject /*thiz*/, jstring /*audioTrackId*/) {
    const std::string dtls = "{\"role\":\"auto\",\"fingerprints\":[{\"algorithm\":\"sha-256\",\"value\":\"00\"}]}";
    const std::string rtp = "{\"codecs\":[{\"mimeType\":\"audio/opus\",\"clockRate\":48000,\"channels\":1}],\"headerExtensions\":[],\"encodings\":[{\"ssrc\":1111}],\"rtcp\":{\"cname\":\"ptt\"}}";
    callOnReady(env, dtls, rtp);
    return JNI_TRUE;
}

