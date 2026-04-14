object ProjectConfig {
    const val APP_NAME = "Mishka"
    const val PACKAGE_NAME = "top.yukonga.mishka"
    const val VERSION_NAME = "1.0.0"

    object Android {
        const val TARGET_SDK = 37
        const val MIN_SDK = 26
        const val COMPILE_SDK = 37
        const val COMPILE_SDK_MINOR = 0
    }
}

fun org.gradle.api.Project.getGitVersionCode(): Int {
    return providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}
