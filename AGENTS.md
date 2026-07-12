# Mishka Agent Guide

`CLAUDE.md` 是本仓库完整的架构约束和历史踩坑记录。开始改动前先读与任务相关的章节；这里仅保留日常工作中必须遵守的摘要。新增长期有效的架构、并发或平台约束时，同步更新 `CLAUDE.md`。

## 项目边界

- 默认把跨平台业务、ViewModel、数据模型和 Compose UI 放在 `shared/src/commonMain`；仅把 Android 或 Desktop 的实际实现放进对应 source set。
- `android/` 负责 Android 入口、Service、JNI/CMake 与平台资源；`mihomo/` 是 submodule，修改前先确认任务确实需要触及它。
- 文案使用 Compose Resources，并同时维护英文默认值和 `values-zh-rCN`。Android Service/通知文案使用 `android/src/main/res` 的对应资源。

## 架构与并发

- `MihomoConnectionManager` 是 runtime `MihomoRepository` 及其 Ktor client 的唯一所有者。消费方只 collect `connectionManager.repository`，不得直接构造或关闭 `MihomoApiClient`、`MihomoWebSocket` 或共享 repository。
- ViewModel 切换 repository、订阅或一次性 HTTP 请求时，先取消旧 Job；异步结果落 UI/Repository 前校验当前 repository identity 与归属 subscription。具体模式见 `CLAUDE.md` 的“ViewModel setRepository”和“订阅流量数据合并”。
- `SubscriptionRepository` 在前台由 `MainActivity` 创建并注入相关 ViewModel；不要在 ViewModel 内重新创建。`processing/` 是进程级共享沙箱，必须继续由 `ProfileProcessor` 的进程级锁保护。
- 所有启动或重启代理的入口都经过 `ProxyServiceController.start/restart`，不可直接启动 VPN/ROOT Service。

## Compose 约定

- UI state 保持不可变；commonMain 的 Flow 使用 `collectAsStateWithLifecycle()`。
- 遵循现有 miuix 风格：二级页使用 `AdaptiveTopAppBar`，长列表维持 Lazy item 粒度，复杂多行卡片使用 `groupedCardItems`。具体 inset、宽屏和 squircle 规则见 `CLAUDE.md`。
- 跨平台代码不要直接依赖 Android `R`、Android Context 或 Android-only API；通过 expect/actual 或 platform 接口隔离。

## 验证

- 每次改动至少运行 `git diff --check`，并执行与变更匹配的 Gradle 任务。
- 共享 Kotlin/UI 变更优先运行 `./gradlew :shared:compileKotlinDesktop`；Android 变更至少运行 `./gradlew :android:assembleDebug`。需要真机验证时再执行 `./gradlew :android:installDebug`。
- 首次 clone 或更新含 native 变更的提交后，先执行 `git submodule update --init --recursive`。完整 APK 构建会触发 mihomo、Geo 文件和 CMake 任务。

## Git 与工作区

- 保留用户已有的未提交改动；不要用破坏性 reset/checkout 清理工作区，也不要修改或输出 `local.properties` 中的敏感内容。
- 完成修改后先报告变更与验证结果。除非用户在当前请求中明确授权，不执行 `git add`、`git commit` 或 `git push`。
