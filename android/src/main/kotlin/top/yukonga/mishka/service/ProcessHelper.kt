package top.yukonga.mishka.service

/**
 * JNI 辅助：使用 fork+exec 启动子进程，不关闭继承的 fd。
 * 绕过 Android ProcessBuilder 强制关闭非标准 fd 的安全机制。
 */
object ProcessHelper {

    init {
        System.loadLibrary("mishka")
    }

    /**
     * fork+exec 启动进程，子进程继承所有 fd。
     * @return 子进程 PID，失败返回 -1
     */
    external fun nativeForkExec(binary: String, args: Array<String>, workDir: String): Int

    /** 发送 SIGTERM */
    external fun nativeKill(pid: Int)

    /** 等待子进程结束 */
    external fun nativeWaitpid(pid: Int): Int
}
