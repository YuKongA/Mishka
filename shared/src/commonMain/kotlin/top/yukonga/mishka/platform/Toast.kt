package top.yukonga.mishka.platform

/**
 * 简易平台 Toast 提示。
 *
 * Android 上映射到 `android.widget.Toast.makeText`，Desktop 目前空实现。
 * 调用前需由 Application 初始化：`initToastPlatform(applicationContext)`。
 */
expect fun showToast(message: String, long: Boolean = false)

/** 初始化平台 Toast 所需的上下文。通常在 Application.onCreate() 调用。 */
expect fun initToastPlatform(context: PlatformContext)
