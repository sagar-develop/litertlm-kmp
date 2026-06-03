/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Minimal JNI bridge to whisper.cpp for on-device speech-to-text. Adapted from the
 * whisper.cpp Android example (MIT) and trimmed to the few calls we need, with a
 * language parameter added on fullTranscribe so dictation can target the user's
 * chosen language (or "auto" to detect). Symbol names match the Kotlin class
 * com.nativelm.app.voice.WhisperNative (companion object).
 */
#include <jni.h>
#include <android/log.h>
#include <string.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define UNUSED(x) (void)(x)

JNIEXPORT jlong JNICALL
Java_com_nativelm_app_voice_WhisperNative_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path) {
    UNUSED(thiz);
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU is the reliable, portable path on Android
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    if (ctx == NULL) {
        LOGI("Failed to init whisper context from %s", "model");
    }
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_nativelm_app_voice_WhisperNative_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    whisper_free((struct whisper_context *) ctx_ptr);
}

JNIEXPORT void JNICALL
Java_com_nativelm_app_voice_WhisperNative_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong ctx_ptr, jint n_threads, jfloatArray audio, jstring lang) {
    UNUSED(thiz);
    struct whisper_context *ctx = (struct whisper_context *) ctx_ptr;
    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);
    const jsize n = (*env)->GetArrayLength(env, audio);

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.translate = false;
    p.n_threads = n_threads;
    p.no_context = true;
    p.single_segment = false;

    // Language: a BCP-47 code (e.g. "hi") targets that language; "auto" lets whisper
    // detect. The C string must live for the whole whisper_full call.
    const char *lang_chars = NULL;
    if (lang != NULL) {
        lang_chars = (*env)->GetStringUTFChars(env, lang, NULL);
        if (lang_chars != NULL && lang_chars[0] != '\0') {
            p.language = lang_chars;
        }
    }

    whisper_reset_timings(ctx);
    if (whisper_full(ctx, p, samples, n) != 0) {
        LOGI("whisper_full failed");
    }

    if (lang_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, lang, lang_chars);
    }
    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_nativelm_app_voice_WhisperNative_00024Companion_segmentCount(
        JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_full_n_segments((struct whisper_context *) ctx_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_nativelm_app_voice_WhisperNative_00024Companion_segmentText(
        JNIEnv *env, jobject thiz, jlong ctx_ptr, jint index) {
    UNUSED(thiz);
    const char *text = whisper_full_get_segment_text((struct whisper_context *) ctx_ptr, index);
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jstring JNICALL
Java_com_nativelm_app_voice_WhisperNative_00024Companion_systemInfo(
        JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
