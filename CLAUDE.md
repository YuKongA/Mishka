# Mishka

Compose Multiplatform + miuix + mihomo 跨平台代理客户端，首先完整支持 Android。

## 技术栈

| 组件                  | 版本          | 用途                        |
| --------------------- | ------------- | --------------------------- |
| Kotlin                | 2.3.20        | 语言                        |
| AGP                   | 9.1.1         | Android 构建                |
| KSP                   | 2.3.6         | 注解处理（Room）            |
| Compose Multiplatform | 1.10.3        | 跨平台 UI 框架              |
| miuix                 | 0.9.0         | UI 组件库 + 导航            |
| miuix-blur            | 0.9.0         | 模糊/着色器效果             |
| androidx.navigation3  | 1.1.0         | 类型安全路由                |
| Room                  | 3.0.0-alpha03 | 跨平台数据库（KMP）         |
| Ktor                  | 3.4.2         | HTTP/WebSocket 客户端       |
| kotlinx-serialization | 1.11.0        | JSON 序列化                 |
| kotlinx-coroutines    | 1.10.2        | 异步/并发                   |
| androidx.lifecycle    | 2.10.0        | ViewModel                   |
| quickie               | 1.11.0        | QR Code 扫描                |
| snakeyaml-engine      | 3.0.1         | YAML 1.2 AST 解析           |
| hiddenapibypass       | 6.1           | 隐藏 API 访问（预测性返回） |
| mihomo                | v1.19.23      | 代理核心（预编译二进制）    |

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
│   │   │   ├── database/             Room 3.0 KMP（AppDatabase + 3 Entity + 3 DAO + DataMigration）
│   │   │   ├── model/                12 个 @Serializable 数据模型
│   │   │   └── repository/           MihomoRepository + SubscriptionRepository + SubscriptionFetcher + ConfigProcessor + V2RayConverter + OverrideStorageHelper
│   │   ├── platform/                 9 个 expect 声明 + ProfileFileManager 接口 + ProxyServiceBridge
│   │   ├── ui/
│   │   │   ├── navigation/           AppNavigation（主导航树 + HorizontalPager）
│   │   │   ├── navigation3/          Route（14 路由）+ Navigator（自定义栈）
│   │   │   ├── component/            SearchBar + SearchStatus + MenuPositionProvider + TriStatePreference + NullablePortPreference + ListEditDialog + RestartRequiredHint
│   │   │   │   └── effect/           BgEffectBackground（OS3 动态渐变着色器背景）
│   │   │   └── screen/               17 个页面（home/ proxy/ subscription/ settings/ log/ provider/ dns/ connection/）
│   │   ├── viewmodel/                9 个 ViewModel
│   │   └── util/                     FormatUtils
│   ├── commonMain/composeResources/
│   │   ├── values/strings.xml        英文默认字符串（244 key）
│   │   └── values-zh-rCN/strings.xml 中文字符串
│   ├── androidMain/                  actual 实现 + AppDatabaseBuilder
│   └── desktopMain/                  actual 桩实现 + AppDatabaseBuilder
├── android/src/main/
│   ├── kotlin/.../mishka/
│   │   ├── MainActivity.kt           应用入口
│   │   ├── MishkaApplication.kt      全局初始化（通知渠道 + GeoIP 提取 + 预测性返回手势）
│   │   └── service/                  15 个服务组件（含 ROOT 模式）
│   ├── res/
│   │   ├── values/strings.xml        Android 层英文字符串（通知/Tile）
│   │   └── values-zh-rCN/strings.xml Android 层中文字符串
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
          ├→ MihomoApiClient（Ktor HTTP）+ MihomoWebSocket（Ktor WS）
          │   → mihomo 进程 http://127.0.0.1:9090
          └→ Room Database（ImportedDao / PendingDao / SelectionDao）
```

### 核心模式

- **通信方案**：mihomo RESTful API + WebSocket（非 JNI），代码全部在 commonMain 跨平台共享
- **导航**：miuix NavDisplay + 自定义 Navigator（push/pop/popUntil + navigateForResult 结果通信）+ LocalNavigator
- **主页 Tab**：HorizontalPager + MainPagerState + NavigationBar（4 Tab）
- **TUN 双模式**：VPN 模式（VpnService 创建 TUN fd）和 ROOT 模式（mihomo 自行创建 TUN + auto-route）
  - VPN 模式：VpnService.establish() → fd → `tun.file-descriptor` + `auto-route: false`
  - ROOT 模式：`su -c mihomo` → 无 fd → `auto-route: true` + `auto-detect-interface: true`
  - ROOT 模式下分应用代理通过 mihomo 的 `include/exclude-package` 实现（VPN 模式用 VpnService API）
  - ROOT 进程 app 被杀后仍存活，重启 app 通过持久化的 PID/secret 重连
  - ROOT 不可用时（卸载 Magisk 等）自动回退 VPN 模式
- **状态桥接**：ProxyServiceBridge（全局 StateFlow + TunMode），Service 写入、ViewModel 读取
- **进程模型**：单进程（VpnService 和 UI 同进程），ROOT 模式 mihomo 为独立 root 进程
- **数据持久化**：Room 3.0 KMP（结构化数据）+ PlatformStorage（简单偏好设置）+ StorageKeys（通用 key 常量）+ OverrideStorageHelper（override key + 可空三态读写）
- **订阅管理**：Pending/Imported 两阶段编辑模型（对齐 CMFA ProfileManager）
- **订阅格式兼容**：User-Agent `clash.meta` + V2RayConverter 自动检测 base64/V2Ray 订阅并转换为 mihomo YAML（支持 vmess/vless/trojan/ss/ssr/hysteria/hysteria2/tuic）
- **GeoIP 预制 + 共享**：构建时 DownloadGeoFilesTask 下载 geoip.metadb/geosite.dat/ASN.mmdb 到 assets，启动时提取到 geodata/ 共享目录 + 符号链接（失败则复制）
- **配置校验**：mihomo -t 进程完整校验（ProcessBuilder，不需要 TUN fd，超时 90s）
- **国际化**：默认英文 + 中文（zh-rCN），Compose Resources `stringResource()` + Android `getString()`
  - Compose 层：`shared/src/commonMain/composeResources/values/strings.xml`（244 key）
  - Android 层：`android/src/main/res/values/strings.xml`（通知/Tile/错误）
  - 日志消息英文，代码注释中文

## 数据库架构（Room 3.0 KMP）

### 三表结构

| 表         | Entity          | 用途                                |
| ---------- | --------------- | ----------------------------------- |
| imported   | ImportedEntity  | 已导入的稳定订阅配置                |
| pending    | PendingEntity   | 编辑中的草稿（提交后移入 imported） |
| selections | SelectionEntity | 代理组选择记录（per 订阅）          |

### 两阶段编辑模型（SubscriptionRepository）

```
CREATE → Pending ✓, Imported ∅
  → COMMIT（下载+校验）→ Imported ✓, Pending ∅
  → RELEASE（放弃）→ 全部清除

PATCH（编辑已导入）→ Imported ✓, Pending ✓
  → COMMIT → Imported ✓（更新）, Pending ∅
  → RELEASE → Imported ✓（不变）, Pending ∅

UPDATE（手动/自动更新）→ 直接更新 Imported
DELETE → 两表都删除 + 清理文件目录
```

### 目录结构

```
files/mihomo/
├── config.yaml                 最终运行配置（ConfigGenerator 生成）
├── geodata/                    共享 GeoIP 文件（启动时从 assets 提取 + 符号链接到各订阅目录）
│   ├── geoip.metadb
│   ├── geosite.dat
│   └── ASN.mmdb
├── imported/{uuid}/            已验证的稳定配置
│   ├── config.yaml
│   ├── Country.mmdb → ../../geodata/Country.mmdb
│   └── providers/
├── pending/{uuid}/             编辑中的草稿
│   ├── config.yaml
│   └── providers/
└── processing/                 临时校验沙箱
```

## 路由清单

`Route.kt` 中定义 14 个路由，均实现 `NavKey`：

| 路由               | 类型        | 页面                          | 入口            |
| ------------------ | ----------- | ----------------------------- | --------------- |
| Main               | data object | 主页（HorizontalPager 4 Tab） | 根路由          |
| Subscription       | data object | SubscriptionScreen            | 主页 Tab 2 导航 |
| SubscriptionAdd    | data object | SubscriptionAddScreen         | 订阅页          |
| SubscriptionAddUrl | data class  | SubscriptionAddUrlScreen      | 添加订阅页      |
| SubscriptionEdit   | data class  | SubscriptionEditScreen        | 订阅项编辑按钮  |
| Log                | data object | LogScreen                     | QuickEntries    |
| Provider           | data object | ProviderScreen                | QuickEntries    |
| DnsQuery           | data object | DnsQueryScreen                | QuickEntries    |
| Connection         | data object | ConnectionScreen              | QuickEntries    |
| VpnSettings        | data object | VpnSettingsScreen             | 设置页          |
| NetworkSettings    | data object | NetworkSettingsScreen         | 设置页          |
| MetaSettings       | data object | MetaSettingsScreen            | 设置页          |
| AppProxy           | data object | AppProxyScreen                | 设置页          |
| About              | data object | AboutScreen                   | 设置页          |

## 页面与 ViewModel

| Screen                       | ViewModel             | 说明                                          |
| ---------------------------- | --------------------- | --------------------------------------------- |
| HomeScreen（6 个子 Section） | HomeViewModel         | 状态/ActionButtons/NetworkInfo/QuickEntries/Latency/BottomCards |
| ProxyScreen                  | ProxyViewModel        | 代理组 Tab + 节点选择 + 延迟测试 + 选择记忆   |
| SubscriptionScreen           | SubscriptionViewModel | 订阅列表 + 增删改 + 全部更新 + 编辑 + 复制    |
| SubscriptionEditScreen       | SubscriptionViewModel | 编辑名称/URL/更新间隔                         |
| SettingsScreen               | —                     | 设置入口页（TUN 模式/主题/开机自启）          |
| LogScreen                    | LogViewModel          | 实时日志流 + 级别过滤                         |
| ConnectionScreen             | ConnectionViewModel   | 活跃连接列表 + 关闭                           |
| ProviderScreen               | ProviderViewModel     | Provider 列表 + 刷新                          |
| DnsQueryScreen               | DnsQueryViewModel     | DNS 查询（A/AAAA/CNAME/MX/TXT/NS）            |
| AppProxyScreen               | AppProxyViewModel     | 应用代理白/黑名单                             |
| VpnSettingsScreen            | —                     | VPN 设置（系统代理/排除路由等）               |
| NetworkSettingsScreen        | OverrideSettingsVM    | 端口/局域网/IPv6/external-controller/DNS      |
| MetaSettingsScreen           | OverrideSettingsVM    | 统一延迟/Geodata/TCP 并发/嗅探器              |
| AboutScreen                  | —                     | 版本信息（OS3 动态背景 + 视差滚动）           |
| SubscriptionAddScreen        | —                     | 添加方式选择（文件/URL/QR Code）              |
| SubscriptionAddUrlScreen     | SubscriptionViewModel | URL 导入订阅                                  |

## 平台抽象（expect/actual）

| expect 声明            | 类型           | Android 实现                | Desktop     |
| ---------------------- | -------------- | --------------------------- | ----------- |
| PlatformContext        | abstract class | typealias Context           | 空对象      |
| PlatformStorage        | class          | SharedPreferences           | Preferences |
| PlatformSystemInfo     | class          | ConnectivityManager + /proc | 空实现      |
| ProxyServiceController | class          | Intent 启停 VPN             | 空实现      |
| AppListProvider        | class          | PackageManager              | 空列表      |
| BootStartManager       | class          | BroadcastReceiver           | 空实现      |
| FilePicker             | class          | SAF                         | 文件对话框  |
| AppIcon                | fun            | BitmapFactory               | 资源加载    |
| IconDiskCache          | object         | 磁盘缓存                    | 空实现      |

## Android 服务层

| 组件                       | 用途                                                              |
| -------------------------- | ----------------------------------------------------------------- |
| MishkaTunService           | VpnService + JNI fork+exec 启动 mihomo                            |
| MishkaRootService          | ROOT TUN 模式前台服务（su 启动 mihomo，进程重连）                 |
| RootHelper                 | root 检测/启动/终止/存活检查/残留清理                             |
| DynamicNotificationManager | 动态通知（WebSocket 流量），两个 Service 共用                     |
| MishkaTileService          | Quick Settings Tile 一键启停代理（双模式路由）                    |
| BootReceiver               | 开机自启（默认 disabled，动态启用）                               |
| ConfigGenerator            | 运行配置生成（buildRunConfig/writeRunConfig/writeValidationConfig，snakeyaml AST 合并 override） |
| ProfileFileOps             | 订阅文件操作（imported/pending/processing 目录管理 + GeoIP 共享） |
| AndroidProfileFileManager  | ProfileFileManager 接口的 Android 实现                            |
| MihomoRunner               | mihomo 进程管理（VPN: JNI fork+exec / ROOT: su）                  |
| MihomoValidator            | mihomo -t 配置校验（ProcessBuilder，超时 90s）                    |
| ProcessHelper              | JNI 包装（nativeForkExec/nativeKill/nativeWaitpid）               |
| NotificationHelper         | 三层通知渠道（VPN/更新进度/更新结果）                             |
| ProfileReceiver            | AlarmManager 调度自动更新                                         |
| ProfileWorker              | 前台服务执行后台配置更新                                          |

## 数据模型

`data/model/` 下 12 个 `@Serializable` 数据类：

ConnectionInfo, DelayResult, DnsQuery, LogMessage, MemoryData, MihomoConfig, ProviderInfo, ProxyGroup, ProxyNode, RuleInfo, Subscription, TrafficData

## 构建命令

```bash
# 编译 mihomo 二进制（首次必须，需要 Go 环境）
cd D:/GitHub/mihomo
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build \
  -tags "cmfa,mishka,with_gvisor" -trimpath \
  -ldflags "-s -w -X 'github.com/metacubex/mihomo/constant.Version=v1.19.23'" \
  -o /path/to/Mishka/android/src/main/jniLibs/arm64-v8a/libmihomo.so .

# 构建 APK（assemble 自动触发 downloadGeoFiles 下载 GeoIP 到 assets/）
./gradlew :android:assembleDebug  # 构建 Android Debug APK
./gradlew :android:assembleRelease # 构建 Android Release APK
```

## 设计决策

- mihomo 二进制放在 jniLibs 中（命名为 libmihomo.so），通过 `jniLibs.useLegacyPackaging = true` 确保解压到 nativeLibraryDir
- Android 的 ProcessBuilder 会 fork 后关闭所有非标准 fd，需用 JNI fork+exec 绕过（`process_helper.c`），保留 VPN TUN fd 继承
- 配置校验使用 `mihomo -t -d <workDir>`（ProcessBuilder，不需要 TUN fd，超时 90s），解析 level=error/fatal 提取错误信息
- 订阅导入兼容 V2Ray 格式：User-Agent `clash.meta` 让订阅服务返回 YAML；若仍为 base64 V2Ray 链接，V2RayConverter 自动解码转换（对齐 CMFA converter.go）
- GeoIP 文件构建时通过 DownloadGeoFilesTask 下载到 assets/，启动时 MishkaApplication.extractGeoFiles() 提取到 geodata/ 共享目录，各订阅通过符号链接（失败则复制）引用
- ConfigGenerator 使用 snakeyaml-engine 做 YAML 1.2 AST 级读写（对齐 mihomo Go yaml.v3 + CFMA 的 `yaml.Unmarshal`）：解析订阅为 `LinkedHashMap` → 按 key 删除 Mishka 管理字段 → 注入新 Map → `dump()` 输出。默认 JsonSchema 使 `secret: 0020` 保留为字符串（`^-?(0|[1-9][0-9]*)$` 不匹配 → String），`StandardConstructor.flattenMapping` 处理 `<<: *anchor` 合并键，`setDereferenceAliases(true)` 展开共享引用避免输出匿名别名
- 订阅管理采用 Pending/Imported 两阶段模型（对齐 CMFA），每个订阅有独立目录（imported/{uuid}/），mihomo -d 指向此处
- Room 3.0 KMP 跨平台数据库，BundledSQLiteDriver 统一 Android/Desktop，DCL 单例
- 代理组选择通过 SelectionEntity 持久化，切换订阅时自动恢复 Selector 类型组的选择
- 后台自动更新通过 AlarmManager + ProfileReceiver + ProfileWorker 前台服务实现，最小间隔 15 分钟
- network_security_config.xml 允许 localhost 明文通信（mihomo API 用 HTTP）
- Activity 声明 `configChanges="uiMode"` 避免系统深浅色切换时重建，防止导航栈丢失
- 预测性返回手势通过 HiddenApiBypass 反射调用 `ApplicationInfo.setEnableOnBackInvokedCallback`，Android 14+ 可选启用
- ROOT 模式重连校验：`attachToExisting` 做三重验证（`kill -0` 存活 + `/proc/$pid/cmdline` 含 libmihomo.so + stored secret 通过 `/configs` 带 Bearer 鉴权 2xx），防 PID 复用与 secret 漂移；订阅一致性由 `startProxy` 在 attach 之前比对 persisted vs 请求的 subscriptionId，不一致直接走 cleanup + 全新启动
- 孤儿 mihomo 清理：`RootHelper.cleanupOrphanedMihomo(tunDevice)` 用**单次 su shell** 完成 pkill + 兜底 `ip link delete <tunDevice>`，Kotlin 侧零 `Thread.sleep`（孤儿非当前 App 子进程，waitpid 不适用，只能 shell 内轮询）。TUN 清理必须同步：sing-tun `tun.New()` 遇已存在设备返回 EEXIST，叠加 TUN init silent failure 会级联。device name 来自用户 storage，`escapeShellSingleQuoted` 单引号 + `'\''` POSIX 转义防命令注入
- VPN 启动清理触发：`MishkaTunService.startProxy` 在 `hadRootPid || HAS_ROOT` 时调用 cleanupOrphanedMihomo + 清 ROOT 持久化 key（PID/SECRET/ACTIVE_SUBSCRIPTION_ID）。`HAS_ROOT` 分支覆盖两个漏清场景：ROOT 崩溃后 storage 清了但进程仍活；VPN mihomo `setsid()` 脱离 App 进程组、App 崩溃后被 init 收养继续占端口
- WebSocket 重连：`MihomoWebSocket.webSocketFlow` 传输层加无限重连循环 + 指数退避（1s→30s 封顶）+ `pingIntervalMillis = 20_000` 心跳。Ktor graceful close 时 `for (frame in incoming)` 静默退出不抛异常，消费者 `.catch` 无法感知，无内置重连只能手搓。`CancellationException` 必须显式 rethrow 否则 cancel 被吞导致死循环。连接状态通过 `connectionState: StateFlow<Boolean>` 粗粒度暴露（mihomo API server 单点，4 flow 共享）
- startForeground 防御：`MishkaTunService` / `MishkaRootService` / `ProfileWorker` 的 onCreate 内 `startForeground()` 必须 `try { ... } catch (e: Exception) { ... }`。真实风险是 API 31+ `ForegroundServiceStartNotAllowedException`（BootReceiver/ProfileReceiver 后台触发）与 API 34+ FGS type 异常，**不是** POST_NOTIFICATIONS 拒绝（仅隐藏通知，FGS 照常运行）。catch 用通用 `Exception` 兜底（各 OEM ROM 抛出类不同）。失败路径：Tun/Root 上报 `ProxyServiceBridge.Error` + `stopSelf()`（让 UI 退出 Starting）；ProfileWorker 仅 `stopSelf()`。**不可降级为普通 Service**（Android 12+ 迅速回收）
- TUN init silent failure 兜底：mihomo `listener.ReCreateTun` TUN inbound 初始化失败走 `log.Errorln + tunConf.Enable=false` 正常返回，**进程不退出**（其他 inbound 如 mixed-port 继续响应 `/version`）。仅靠进程存活 + API 可达无法检测。检测规则：① `MishkaTunService` 清 O_CLOEXEC (`fcntlInt(F_SETFD)`) 失败必须视为致命（`closeTunFd` + `ProxyState.Error` + `stopSelf`）——fd 未清则 exec 后必然被关；② `MihomoRunner.waitForReady` API ready 后 delay 500ms 再 `scanLogForTunError`，匹配 pattern：`Start TUN listening error` / `configure tun interface` / `create NetworkUpdateMonitor`
- 配置校验走 override 合并后的产物，不是原始订阅：`imported/{uuid}/config.yaml` 是订阅原文，`files/mihomo/config.yaml` 是 override 合并后的运行配置。若直接 `mihomo -t` 原始订阅，用户的 override 字段（external-controller/secret/tun/各 port/allow-lan/ipv6/bind-address/log-level/dns/sniffer）**零测试**，用户设错冲突端口或非法 DNS URL 时会"导入成功但运行时失败"。`ConfigGenerator.writeValidationConfig` 写入订阅目录的 `config.validate.yaml`，`stubTunForValidation=true` 使 tun 段 `{enable:false}` 占位（校验无 fd / root）；`ProfileFileManager.validate(configFileName=...)` + `generateValidationConfig` + `cleanupValidationConfig` try/finally 清理
- CMFA embed mode 禁用全部 HTTP 配置 API：mihomo 上游 `patch_android.go`（`//go:build android && cmfa`）init `SetEmbedMode(true)`，`PATCH/PUT /configs` / `POST /restart` / `POST /configs/geo` / `PUT/PATCH /rules` / `POST /upgrade` 全部 404（configs.go:28 / server.go:135 / rules.go:17 / upgrade.go:18）。Mishka fork 未改该文件。Ktor `HttpClient` 默认不对 404 抛异常，`runCatching` 包装会返回"幻觉成功"（UI 以为切换，mihomo 未动）。约束：**不要**添加 `apiClient.patchConfig` / `reloadConfig` / `restart` 方法。所有 mihomo 配置修改（mode/tun.stack/DNS/port/...）走 `OverrideStorageHelper` 写 key + `serviceController.restart(subscriptionId)`（Service Intent 重启，非 HTTP）+ `ConfigGenerator.buildRunConfig` 合并生成新 config.yaml；UI 用 `RestartRequiredHint` Card 统一告知"需重启生效"

## UI 规范

- 所有 UI 组件使用 miuix（Card、TopAppBar、NavigationBar、SmallTitle、TextButton 等）
- 返回按钮使用 MiuixIcons.Back
- Card 间距：水平 12.dp，垂直 12.dp
- 首页状态卡片参考 KernelSU（CheckCircleOutline 170dp，offset(38,45)）
- 深色模式：StatusCard 背景 #1A3825，按钮使用 isDark 条件色
- 底栏图标：Sidebar / Tune / UploadCloud / Settings
- LazyColumn 必须加 `.scrollEndHaptic().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)`
- Badge：`clip(miuixShape(3.dp))` + 9.sp Bold Monospace
- 操作 IconButton：`minHeight/minWidth = 35.dp, backgroundColor = secondaryContainer`
- **i18n**：所有面向用户的字符串必须使用 `stringResource(Res.string.xxx)`（Compose）或 `getString(R.string.xxx)`（Android Service），禁止硬编码
  - 新增字符串需同时添加到 `values/strings.xml`（英文）和 `values-zh-rCN/strings.xml`（中文）
  - key 命名：`{页面}_{描述}`，通用按钮用 `common_` 前缀
  - 日志消息用英文，代码注释保留中文
