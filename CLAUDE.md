# Mishka

Compose Multiplatform + miuix + mihomo 跨平台代理客户端，首先完整支持 Android。

## 技术栈

| 组件                  | 版本     | 用途                     |
| --------------------- | -------- | ------------------------ |
| Kotlin                | 2.3.20   | 语言                     |
| AGP                   | 9.1.1    | Android 构建             |
| Compose Multiplatform | 1.10.3   | 跨平台 UI 框架           |
| miuix                 | 0.9.0    | UI 组件库 + 导航         |
| androidx.navigation3  | 1.1.0    | 类型安全路由             |
| Ktor                  | 3.4.2    | HTTP/WebSocket 客户端    |
| kotlinx-serialization | 1.11.0   | JSON 序列化              |
| kotlinx-coroutines    | 1.10.2   | 异步/并发                |
| androidx.lifecycle    | 2.10.0   | ViewModel                |
| mihomo                | v1.19.23 | 代理核心（预编译二进制） |

版本统一管理：`gradle/libs.versions.toml`（依赖）、`gradle.properties`（mihomo）、`buildSrc/ProjectConfig.kt`（应用）

## 项目结构

```
Mishka/
├── buildSrc/                         ProjectConfig + GenerateVersionInfoTask
├── shared/src/
│   ├── commonMain/kotlin/.../mishka/
│   │   ├── App.kt                    根组件 + 主题配置
│   │   ├── data/
│   │   │   ├── api/                  MihomoApiClient（REST）+ MihomoWebSocket（流）
│   │   │   ├── model/                12 个 @Serializable 数据模型
│   │   │   └── repository/           MihomoRepository + SubscriptionRepository + SubscriptionFetcher + ConfigProcessor
│   │   ├── platform/                 8 个 expect 声明（平台抽象层）
│   │   ├── ui/
│   │   │   ├── navigation/           AppNavigation（主导航树 + HorizontalPager）
│   │   │   ├── navigation3/          Route（12 路由）+ Navigator（自定义栈）
│   │   │   ├── component/            SearchBar + SearchStatus + MenuPositionProvider
│   │   │   └── screen/               14 个页面（home/ proxy/ subscription/ settings/ log/ provider/ dns/ connection/）
│   │   ├── viewmodel/                9 个 ViewModel
│   │   └── util/                     FormatUtils
│   ├── androidMain/                  8 个 actual 实现
│   └── desktopMain/                  8 个 actual 桩实现
├── android/src/main/
│   ├── kotlin/.../mishka/
│   │   ├── MainActivity.kt           应用入口
│   │   ├── MishkaApplication.kt      全局初始化
│   │   └── service/                  MishkaTunService + MishkaTileService + BootReceiver + ConfigGenerator + MihomoRunner + NotificationHelper
│   ├── cpp/                          process_helper.c（JNI fork+exec）
│   └── jniLibs/arm64-v8a/            libmihomo.so
└── desktop/                          Desktop 预留入口
```

## 架构

### 依赖层级

```
MainActivity → App → AppNavigation
  → HorizontalPager（4 Tab）+ NavDisplay（二级页面）
    → Screen Composable
      → ViewModel
        → Repository（MihomoRepository / SubscriptionRepository）
          → MihomoApiClient（Ktor HTTP）+ MihomoWebSocket（Ktor WS）
            → mihomo 进程 http://127.0.0.1:9090
```

### 核心模式

- **通信方案**：mihomo RESTful API + WebSocket（非 JNI），代码全部在 commonMain 跨平台共享
- **导航**：miuix NavDisplay + 自定义 Navigator（push/pop/popUntil + navigateForResult 结果通信）+ LocalNavigator
- **主页 Tab**：HorizontalPager + MainPagerState + NavigationBar（4 Tab）
- **状态桥接**：ProxyServiceBridge（全局 StateFlow），TunService 写入、ViewModel 读取
- **进程模型**：单进程（VpnService 和 UI 同进程）

## 路由清单

`Route.kt` 中定义 12 个 `@Serializable data object`，均实现 `NavKey`：

| 路由               | 页面                          | 入口            |
| ------------------ | ----------------------------- | --------------- |
| Main               | 主页（HorizontalPager 4 Tab） | 根路由          |
| Subscription       | SubscriptionAddScreen         | 主页 Tab 2 导航 |
| SubscriptionAdd    | SubscriptionAddScreen         | 订阅页          |
| SubscriptionAddUrl | SubscriptionAddUrlScreen      | 添加订阅页      |
| Log                | LogScreen                     | QuickEntries    |
| Provider           | ProviderScreen                | QuickEntries    |
| DnsQuery           | DnsQueryScreen                | QuickEntries    |
| Connection         | ConnectionScreen              | QuickEntries    |
| NetworkSettings    | NetworkSettingsScreen         | 设置页          |
| AppProxy           | AppProxyScreen                | 设置页          |
| Appearance         | AppearanceScreen              | 设置页          |
| About              | AboutScreen                   | 设置页          |

## 页面与 ViewModel

| Screen                       | ViewModel             | 说明                               |
| ---------------------------- | --------------------- | ---------------------------------- |
| HomeScreen（7 个子 Section） | HomeViewModel         | 主页：状态/流量/网速/延迟/快速入口 |
| ProxyScreen                  | ProxyViewModel        | 代理组 Tab + 节点选择 + 延迟测试   |
| SubscriptionScreen           | SubscriptionViewModel | 订阅列表 + 增删改                  |
| SettingsScreen               | SettingsViewModel     | 设置入口页                         |
| LogScreen                    | LogViewModel          | 实时日志流 + 级别过滤              |
| ConnectionScreen             | ConnectionViewModel   | 活跃连接列表 + 关闭                |
| ProviderScreen               | ProviderViewModel     | Provider 列表 + 刷新               |
| DnsQueryScreen               | DnsQueryViewModel     | DNS 查询（A/AAAA/CNAME/MX/TXT/NS） |
| AppProxyScreen               | AppProxyViewModel     | 应用代理白/黑名单                  |
| NetworkSettingsScreen        | —                     | 端口/局域网/IPv6 设置              |
| AppearanceScreen             | —                     | 主题模式切换                       |
| AboutScreen                  | —                     | 版本信息                           |
| SubscriptionAddScreen        | —                     | 添加订阅方式选择                   |
| SubscriptionAddUrlScreen     | SubscriptionViewModel | URL 导入订阅                       |

## 平台抽象（expect/actual）

| expect 声明            | 类型           | Android 实现                | Desktop    |
| ---------------------- | -------------- | --------------------------- | ---------- |
| PlatformContext        | abstract class | typealias Context           | 空对象     |
| PlatformStorage        | class          | SharedPreferences           | 内存 Map   |
| PlatformSystemInfo     | class          | ConnectivityManager + /proc | 空实现     |
| ProxyServiceController | class          | Intent 启停 VPN             | 空实现     |
| AppListProvider        | class          | PackageManager              | 空列表     |
| BootStartManager       | class          | BroadcastReceiver           | 空实现     |
| FilePicker             | class          | SAF                         | 文件对话框 |
| AppIcon                | fun            | BitmapFactory               | 资源加载   |

## 数据模型

`data/model/` 下 12 个 `@Serializable` 数据类：

ConnectionInfo, DelayResult, DnsQuery, LogMessage, MemoryData, MihomoConfig, ProviderInfo, ProxyGroup, ProxyNode, RuleInfo, Subscription, TrafficData

## 构建命令

```bash
./gradlew downloadMihomo          # 下载 mihomo 二进制（首次必须）
./gradlew :android:assembleDebug  # 构建 Android Debug APK
./gradlew :android:assembleRelease # 构建 Android Release APK
```

## 关键设计决策

- mihomo 二进制放在 jniLibs 中（命名为 libmihomo.so），通过 `jniLibs.useLegacyPackaging = true` 确保解压到 nativeLibraryDir，用 ProcessBuilder 执行
- Android 的 ProcessBuilder 会 fork 后关闭所有非标准 fd，需用 JNI fork+exec 绕过（`process_helper.c`）
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
- LazyColumn 必须加 `.scrollEndHaptic().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)`
- Badge：`clip(miuixShape(3.dp))` + 9.sp Bold Monospace
- 操作 IconButton：`minHeight/minWidth = 35.dp, backgroundColor = secondaryContainer`
