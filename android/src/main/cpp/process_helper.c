#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <signal.h>
#include <sys/wait.h>
#include <android/log.h>

#define TAG "ProcessHelper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * fork + exec 启动子进程，不关闭继承的 fd。
 * Android 的 ProcessBuilder 会在 fork 后关闭所有非标准 fd，
 * 这个函数绕过该限制，让子进程能继承 VPN 的 TUN fd。
 *
 * 返回子进程 PID，失败返回 -1。
 */
JNIEXPORT jint JNICALL
Java_top_yukonga_mishka_service_ProcessHelper_nativeForkExec(
        JNIEnv *env, jclass clazz,
        jstring jBinary, jobjectArray jArgs, jstring jWorkDir) {

    const char *binary = (*env)->GetStringUTFChars(env, jBinary, NULL);
    const char *workDir = (*env)->GetStringUTFChars(env, jWorkDir, NULL);

    int argc = (*env)->GetArrayLength(env, jArgs);
    // argv: [binary, args..., NULL]
    char **argv = (char **) calloc(argc + 2, sizeof(char *));
    argv[0] = strdup(binary);
    for (int i = 0; i < argc; i++) {
        jstring jArg = (jstring) (*env)->GetObjectArrayElement(env, jArgs, i);
        const char *arg = (*env)->GetStringUTFChars(env, jArg, NULL);
        argv[i + 1] = strdup(arg);
        (*env)->ReleaseStringUTFChars(env, jArg, arg);
    }
    argv[argc + 1] = NULL;

    LOGI("fork+exec: %s, workDir=%s", binary, workDir);

    pid_t pid = fork();

    if (pid == 0) {
        // 子进程：不关闭任何 fd，直接 exec
        if (chdir(workDir) != 0) {
            LOGE("chdir failed: %s", strerror(errno));
        }

        // 重定向 stdout/stderr 合并
        dup2(STDERR_FILENO, STDOUT_FILENO);

        execv(binary, argv);
        // exec 失败
        LOGE("execv failed: %s", strerror(errno));
        _exit(127);
    }

    // 父进程：清理
    int result = (pid > 0) ? pid : -1;
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
    } else {
        LOGI("child pid=%d", pid);
    }

    for (int i = 0; argv[i] != NULL; i++) {
        free(argv[i]);
    }
    free(argv);
    (*env)->ReleaseStringUTFChars(env, jBinary, binary);
    (*env)->ReleaseStringUTFChars(env, jWorkDir, workDir);

    return result;
}

/**
 * 向子进程发送 SIGTERM 信号。
 */
JNIEXPORT void JNICALL
Java_top_yukonga_mishka_service_ProcessHelper_nativeKill(
        JNIEnv *env, jclass clazz, jint pid) {
    if (pid > 0) {
        LOGI("killing pid=%d", pid);
        kill(pid, SIGTERM);
    }
}

/**
 * 等待子进程结束。
 */
JNIEXPORT jint JNICALL
Java_top_yukonga_mishka_service_ProcessHelper_nativeWaitpid(
        JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    waitpid(pid, &status, 0);
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}
