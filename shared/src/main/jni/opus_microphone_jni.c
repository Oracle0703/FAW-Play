#include <jni.h>
#include <stdint.h>
#include "opus.h"

JNIEXPORT jlong JNICALL Java_com_shilapi_xcertplay_media_NativeOpusEncoder_nativeCreate(
        JNIEnv *env, jclass type, jint bitrate) {
    (void)env; (void)type;
    if (bitrate < 6000 || bitrate > 128000) return 0;
    int error;
    OpusEncoder *encoder = opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &error);
    if (!encoder || error != OPUS_OK) return 0;
    if (opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(5)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)) != OPUS_OK) {
        opus_encoder_destroy(encoder);
        return 0;
    }
    return (jlong)(intptr_t)encoder;
}

JNIEXPORT jbyteArray JNICALL Java_com_shilapi_xcertplay_media_NativeOpusEncoder_nativeEncode(
        JNIEnv *env, jclass type, jlong handle, jbyteArray pcm) {
    (void)type;
    if (!handle || !pcm || (*env)->GetArrayLength(env, pcm) != 1920) return NULL;
    jbyte bytes[1920];
    opus_int16 samples[960];
    unsigned char packet[1275];
    (*env)->GetByteArrayRegion(env, pcm, 0, 1920, bytes);
    if ((*env)->ExceptionCheck(env)) return NULL;
    for (int i = 0; i < 960; ++i)
        samples[i] = (opus_int16)((uint16_t)(uint8_t)bytes[2*i] |
                                 ((uint16_t)(uint8_t)bytes[2*i+1] << 8));
    int size = opus_encode((OpusEncoder *)(intptr_t)handle, samples, 960, packet, sizeof(packet));
    if (size <= 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, size);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, size, (const jbyte *)packet);
    return result;
}

JNIEXPORT void JNICALL Java_com_shilapi_xcertplay_media_NativeOpusEncoder_nativeDestroy(
        JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    if (handle) opus_encoder_destroy((OpusEncoder *)(intptr_t)handle);
}
