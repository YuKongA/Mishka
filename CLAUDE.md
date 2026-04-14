# Mishka

Compose Multiplatform + miuix + mihomo 跨平台代理客户端，首先完整支持 Android。

## 技术栈

- Kotlin / Compose Multiplatform / AGP — 版本见 `gradle/libs.versions.toml`
- miuix（UI 组件库）+ miuix-navigation3-ui（页面导航）
- mihomo（代理核心，预编译二进制通过 `./gradlew downloadMihomo` 下载，版本见 `gradle.properties`）
- Ktor（HTTP/WebSocket 客户端）
- androidx.navigation3（类型安全路由）

## 项目结构

```
Mishka/
├── buildSrc/           ProjectConfig + GenerateVersionInfoTask
├── shared/             KMP 公共模块（UI、ViewModel、数据层、平台抽象）
│   ├── commonMain/     跨平台代码
│   ├── androidMain/    Android expect/actual 实现
│   └── desktopMain/    Desktop 桩实现
├── android/            Android 应用入口 + 服务层
└── desktop/            Desktop 预留入口
```

## 架构

- **通信方案**：mihomo RESTful API + WebSocket（非 JNI），代码全部在 commonMain 跨平台共享
- **导航**：miuix-navigation3-ui NavDisplay + 自定义 Navigator（push/pop/popUntil + 结果通信）
- **主页 Tab**：HorizontalPager + MainPagerState（参考 miuix example）
- **状态桥接**：ProxyServiceBridge（全局 StateFlow），TunService 写入、ViewModel 读取
- **进程模型**：单进程（VpnService 和 UI 同进程，通过 ProxyServiceBridge 通信）

## 构建命令

```bash
./gradlew downloadMihomo          # 下载 mihomo 二进制（首次必须）
./gradlew :android:assembleDebug  # 构建 Android Debug APK
```

## 关键设计决策

- mihomo 二进制放在 jniLibs 中（命名为 libmihomo.so），通过 `jniLibs.useLegacyPackaging = true` 确保解压到 nativeLibraryDir，用 ProcessBuilder 执行
- ConfigGenerator 使用行过滤合并 YAML（移除 external-controller/secret 后追加），避免引入 YAML 解析库
- 每个订阅有专属工作目录（profiles/{id}/），mihomo -d 指向此处，provider 缓存互不干扰
- network_security_config.xml 允许 localhost 明文通信（mihomo API 用 HTTP）

## UI 规范

- 所有 UI 组件使用 miuix（Card、TopAppBar、NavigationBar、SmallTitle、TextButton 等）
- 返回按钮使用 MiuixIcons.Back
- Card 间距：水平 12.dp，垂直 6-12.dp
- 首页状态卡片参考 KernelSU（CheckCircleOutline 170dp，offset(38,45)）
- 深色模式：StatusCard 背景 #1A3825，按钮使用 isDark 条件色
- 底栏图标：Sidebar / Tune / Settings
