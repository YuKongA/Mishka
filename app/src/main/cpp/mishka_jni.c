// JNI 桥：jstring/jint ↔ C ABI 转译；不做业务。
// Go 返回的 *C.char 内存属于 cgo runtime，转 jstring 后必须 mishkaFreeString，不能用 free()。

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "libmihomo.h"

// 两种指针在同一个函数体里混用，别弄反：本函数返回的是 strdup 出来的**本地**副本，
// 调用方用 free()；go_cstr_to_jstring 收的才是 Go 那边的内存，只能 mishkaFreeString。
static char *jstring_to_cstr(JNIEnv *env, jstring s) {
    if (!s) return NULL;
    const char *tmp = (*env)->GetStringUTFChars(env, s, NULL);
    if (!tmp) return NULL;
    char *copy = strdup(tmp);
    (*env)->ReleaseStringUTFChars(env, s, tmp);
    return copy;
}

static jstring go_cstr_to_jstring(JNIEnv *env, char *s) {
    if (!s) return NULL;
    jstring out = (*env)->NewStringUTF(env, s);
    mishkaFreeString(s);
    return out;
}

JNIEXPORT void JNICALL
Java_top_yukonga_mishka_data_bridge_MishkaCoreBridge_nativeCoreInit(
        JNIEnv *env, jclass clazz, jstring jHomeDir, jstring jUserAgent) {
    char *homeDir = jstring_to_cstr(env, jHomeDir);
    char *userAgent = jstring_to_cstr(env, jUserAgent);
    mishkaCoreInit(homeDir ? homeDir : "", userAgent ? userAgent : "");
    free(homeDir);
    free(userAgent);
}

JNIEXPORT jstring JNICALL
Java_top_yukonga_mishka_data_bridge_MishkaCoreBridge_nativeFetchAndValid(
        JNIEnv *env, jclass clazz,
        jstring jWorkDir, jstring jUrl, jboolean jForce, jstring jHttpProxy, jstring jUserAgent,
        jint jToken) {
    char *workDir = jstring_to_cstr(env, jWorkDir);
    char *url = jstring_to_cstr(env, jUrl);
    char *httpProxy = jstring_to_cstr(env, jHttpProxy);
    char *userAgent = jstring_to_cstr(env, jUserAgent);

    char *result = mishkaFetchAndValid(
            workDir ? workDir : "",
            url ? url : "",
            jForce ? 1 : 0,
            httpProxy ? httpProxy : "",
            userAgent ? userAgent : "",
            (int) jToken);

    free(workDir);
    free(url);
    free(httpProxy);
    free(userAgent);

    return go_cstr_to_jstring(env, result);
}

JNIEXPORT void JNICALL
Java_top_yukonga_mishka_data_bridge_MishkaCoreBridge_nativeCancel(
        JNIEnv *env, jclass clazz, jint jToken) {
    mishkaCancel((int) jToken);
}

JNIEXPORT jstring JNICALL
Java_top_yukonga_mishka_data_bridge_MishkaCoreBridge_nativeQueryProgress(
        JNIEnv *env, jclass clazz, jint jToken) {
    char *progress = mishkaQueryProgress((int) jToken);
    return go_cstr_to_jstring(env, progress);
}

JNIEXPORT void JNICALL
Java_top_yukonga_mishka_data_bridge_MishkaCoreBridge_nativeSetAgeSecretKey(
        JNIEnv *env, jclass clazz, jstring jKey) {
    char *key = jstring_to_cstr(env, jKey);
    mishkaSetAgeSecretKey(key ? key : "");
    free(key);
}

JNIEXPORT jstring JNICALL
Java_top_yukonga_mishka_data_bridge_MishkaCoreBridge_nativeGenAgeKeyPair(
        JNIEnv *env, jclass clazz) {
    char *result = mishkaGenAgeKeyPair();
    return go_cstr_to_jstring(env, result);
}

JNIEXPORT jstring JNICALL
Java_top_yukonga_mishka_data_bridge_MishkaCoreBridge_nativeGenAgeHybridKeyPair(
        JNIEnv *env, jclass clazz) {
    char *result = mishkaGenAgeHybridKeyPair();
    return go_cstr_to_jstring(env, result);
}

JNIEXPORT jstring JNICALL
Java_top_yukonga_mishka_data_bridge_MishkaCoreBridge_nativeValidateTransform(
        JNIEnv *env, jclass clazz, jstring jWorkDir, jstring jTransform, jstring jKey) {
    char *workDir = jstring_to_cstr(env, jWorkDir);
    char *transform = jstring_to_cstr(env, jTransform);
    char *key = jstring_to_cstr(env, jKey);
    char *result = mishkaValidateTransform(
            workDir ? workDir : "",
            transform ? transform : "",
            key ? key : "");
    free(workDir);
    free(transform);
    free(key);
    return go_cstr_to_jstring(env, result);
}
