# Mishka

Compose Multiplatform + miuix + mihomo 跨平台代理客户端，首先完整支持 Android。

## 技术栈

| 组件                          | 版本          | 用途                        |
| ----------------------------- | ------------- | --------------------------- |
| Kotlin                        | 2.3.21        | 语言                        |
| AGP                           | 9.2.1         | Android 构建                |
| KSP                           | 2.3.6         | 注解处理（Room）            |
| Compose Multiplatform         | 1.11.0        | 跨平台 UI 框架              |
| miuix                         | 0.9.1         | UI 组件库 + 导航            |
| miuix-blur                    | 0.9.1         | 模糊/着色器效果             |
| androidx.navigation3          | 1.1.1         | 类型安全路由                |
| Room                          | 3.0.0-alpha04 | 跨平台数据库（KMP）         |
| Ktor                          | 3.4.3         | HTTP/WebSocket 客户端       |
| kotlinx-coroutines            | 1.11.0        | 异步/并发                   |
| kotlinx-collections-immutable | 0.4.0         | UI state 持久不可变集合     |
| kotlinx-datetime              | 0.8.0         | 日期时间处理                |
| kotlinx-serialization         | 1.11.0        | JSON 序列化                 |
| androidx.lifecycle            | 2.10.0        | ViewModel + lifecycle       |
| quickie                       | 1.11.0        | QR Code 扫描                |
| hiddenapibypass               | 6.1           | 隐藏 API 访问（预测性返回） |
| mihomo                        | v1.19.25+     | 代理核心（Mishka patch）    |

版本统一管理：`gradle/libs.versions.toml`（依赖）、`gradle.properties`（mihomo）、`buildSrc/ProjectConfig.kt`（应用）

Compose 稳定性配置：[shared/compose_compiler_config.conf](shared/compose_compiler_config.conf) 列出 kotlinx.coroutines Flow/Mutex/Semaphore、kotlinx.serialization.Json、Ktor HttpClient/WebSocket session、AndroidX ViewModel/RoomDatabase、以及 Mishka 自身长生命周期容器为 stable；由 [shared/build.gradle.kts](shared/build.gradle.kts) 的 `composeCompiler.stabilityConfigurationFiles` 加载。新增推断 unstable 的三方/平台字段时优先加进该文件，而非散落 `@Stable` 注解。

## 项目结构

```
Mishka/
├── buildSrc/                         ProjectConfig + GenerateVersionInfoTask + GoBuildTask（mihomo/JNI 通用 Go 交叉编译）
├── mihomo/                           git submodule（YuKongA/mihomo branch Mishka，5 patch 见关键约束）
├── shared/src/
│   ├── commonMain/kotlin/.../mishka/
│   │   ├── App.kt                    根组件 + 主题配置
│   │   ├── data/
│   │   │   ├── api/                  MihomoApiClient（REST）+ MihomoWebSocket（流）+ MihomoConnectionManager（全 app 单例）
│   │   │   ├── bridge/               MishkaCoreBridge expect（订阅导入 JNI 门面 + FetchProgress / CoreFetchResult / MishkaCoreError）
│   │   │   ├── database/             Room 3.0 KMP（AppDatabase + 3 Entity + 3 DAO + ProfileTypeConverter）
│   │   │   ├── model/                @Serializable 数据模型 + ProfileType enum + ConfigurationOverride
│   │   │   └── repository/           MihomoRepository + SubscriptionRepository + ProfileProcessor（3 阶段，JNI 化）+ OverrideJsonStore + SubscriptionProxyResolver
│   │   ├── platform/                 expect 声明（含 Toast）+ ProfileFileManager 接口 + ProxyServiceBridge
│   │   ├── ui/
│   │   │   ├── navigation/           AppNavigation（主导航树 + HorizontalPager）
│   │   │   ├── navigation3/          Route + Navigator（自定义栈）
│   │   │   ├── component/            SearchBar + SearchStatus + MenuPositionProvider + TriStatePreference + NullablePortPreference + ListEditDialog + RestartRequiredHint
│   │   │   │   ├── blur/             BlurExt（BlurredBar/defaultBlurEffect/rememberBlurBackdrop）+ ColorBlendToken
│   │   │   │   └── effect/           BgEffectBackground + BgEffectModifier + BgEffectPainter + BgEffectConfig + DeviceType + OS3BgFrag（OS3 动态渐变着色器背景，ModifierNodeElement + 60FPS 限频）
│   │   │   ├── theme/                StatusColors（语义色 token：runState/delay/actionButton/...）
│   │   │   └── screen/               页面（home/ proxy/ subscription/ settings/ log/ provider/ dns/ connection/）
│   │   ├── viewmodel/                ViewModel
│   │   └── util/                     FormatUtils + ThrowableExt
│   ├── commonMain/composeResources/
│   │   ├── drawable/ic_launcher_foreground.xml  About 页 hero 图标（Android Vector，KMP 共享）
│   │   ├── values/strings.xml        英文默认字符串
│   │   └── values-zh-rCN/strings.xml 中文字符串
│   ├── androidMain/
│   │   ├── kotlin/                   actual 实现 + AppDatabaseBuilder（含 MishkaCoreBridge.android.kt 走 JNI）
│   │   └── native/mishka_core/       Go cgo c-shared 源（main.go + fetch.go + go.mod），编译为 libmishka_core.so；replace mihomo → ../../../../../mihomo
│   └── desktopMain/                  actual 桩实现 + AppDatabaseBuilder
├── android/src/main/
│   ├── kotlin/.../mishka/
│   │   ├── MainActivity.kt           应用入口
│   │   ├── MishkaApplication.kt      全局初始化（通知渠道 + GeoIP 提取 + MishkaCoreBridge.init + 预测性返回手势 + 旧 root 文件 chown 迁移）
│   │   └── service/                  服务组件（含 ROOT 模式 + RuntimeOverrideBuilder + MihomoRunner）
│   ├── res/
│   │   ├── values/strings.xml        Android 层英文字符串（通知/Tile）
│   │   └── values-zh-rCN/strings.xml Android 层中文字符串
│   ├── cpp/                          process_helper.c（JNI fork+exec）+ mishka_jni.c（薄 JNI 桥）+ mihomo_wrapper.c（薄 PIE 可执行，dlopen libmihomo）+ CMakeLists.txt
│   └── jniLibs/arm64-v8a/            libmihomo.so（统一 cgo c-shared，含 mihomo 全部代码 + JNI 导出 + mihomoEntry 入口；订阅导入与 runtime 共享）
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

- **通信方案**：runtime（traffic / logs / connections / proxy select / provider 刷新）走 subprocess + Ktor REST + WebSocket，三模式共用；订阅导入（fetch + provider prefetch + Parse）走 JNI in-process，由 [MishkaCoreBridge](shared/src/commonMain/kotlin/top/yukonga/mishka/data/bridge/MishkaCoreBridge.kt) 调 libmihomo.so 的 cgo 导出
- **统一 .so 架构**：[libmihomo.so](shared/src/androidMain/native/mishka_core/)（cgo c-shared，~56MB）同时承担 JNI 导出 + `mihomoEntry(argc, argv)` runtime 入口；[libmihomo_runner.so](android/src/main/cpp/mihomo_wrapper.c)（C PIE，~6KB）由 MihomoRunner fork+exec 后 dlopen libmihomo.so 调 mihomoEntry。一份 mihomo 代码两条路径共用
- **mihomo 客户端共享**：`MihomoConnectionManager`（application-scoped 单例，由 `MishkaApplication.connectionManager` 持有）订阅 `ProxyServiceBridge.state`，`Running` 时构造新 `MihomoRepository`，其他状态置 null；切换前同步 close 旧实例，杜绝 Ktor `HttpClient` 泄漏。所有消费方（5 个 ViewModel + `DynamicNotificationManager`）只 collect `connectionManager.repository: StateFlow<MihomoRepository?>`
- **MishkaCoreBridge**：`init(homeDir, userAgent)` 在 `MishkaApplication.onCreate` 一次性调用，homeDir 指向共享 GeoIP 目录 `files/mihomo/geodata/`；`fetchAndValid` 内部分 token、150ms 轮询进度、取消时调 `nativeCancel` 让 Go ctx 进入 Done
- **导航**：miuix NavDisplay + 自定义 Navigator（push/pop/popUntil + navigateForResult）+ LocalNavigator
- **主页 Tab**：HorizontalPager + MainPagerState + NavigationBar（4 Tab）
- **隧道三模式**：VPN / ROOT TUN / ROOT TPROXY（`TunMode { Vpn, RootTun, RootTproxy }`）
  - **VPN**：VpnService 创建 TUN fd，mihomo 写 `tun.file-descriptor` + `auto-route=false`，工作目录 `imported/{uuid}/`（app UID）
  - **ROOT TUN**：mihomo 以 root 自建 TUN，`auto-route=true` + `auto-detect-interface=true`，工作目录独立 `runtime/{uuid}/` 沙箱（启动前从 imported/ 拷贝，停止时 `su rm -rf`）；imported/ 永远 app UID
  - **ROOT TPROXY**：**`tun.enable=false`**，mihomo 用 `tproxy-port=7895` 入站 + `dns.listen=0.0.0.0:1053`；`RootTproxyApplier` 装 `mangle PREROUTING/OUTPUT` + `nat PREROUTING/OUTPUT` + `ip rule fwmark 0x1000000 lookup 2024` + `ip route add local default dev lo table 2024`，透明劫持本机与热点流量；分应用代理改走 iptables `-m owner --uid-owner`
  - **分应用代理**：VPN 走 VpnService API；ROOT TUN 走 mihomo `include/exclude-package`（sing-tun 翻译为 uidrange）；ROOT TPROXY 走 iptables `uid-owner`（`AppListProvider.resolveUids` 把包名解析为 UID）；**Mishka 自身始终排除**（VPN: disallowed；ROOT TUN: include 过滤 + exclude 叠加；ROOT TPROXY: `-m owner --uid-owner 0 RETURN` 放行 root 含 mihomo + `-m owner --uid-owner $APP_UID RETURN` 兜底；**不**用 `routing-mark`/SO_MARK 自绕——Android Netd 用 fwmark 低 16 位编码 netId，任何自定义 SO_MARK 都会让路由命中 legacy_system 表无默认路由，导致 mihomo 出站 `network unreachable`，box_for_magisk / Surfing / box4magisk 三家同款教训）
  - **ROOT 两子模式共享** MishkaRootService，Intent 通过 `EXTRA_SUBMODE = "tun"/"tproxy"` 区分；attach 路径比对 `ROOT_SUBMODE_ACTIVE` 与请求 submode，不一致 fresh restart
  - ROOT 进程 app 被杀后仍存活，重启 app 通过持久化 PID/secret 重连
  - ROOT 不可用自动回退 VPN（MainActivity 探测失败后回写 `TUN_MODE=vpn`）
- **状态桥接**：ProxyServiceBridge（全局 StateFlow + TunMode），Service 写入、ViewModel 读取
- **进程模型**：单进程（VpnService 和 UI 同进程），ROOT 模式 mihomo 为独立 root 进程
- **数据持久化**：Room 3.0 KMP（结构化数据）+ PlatformStorage（简单偏好）+ StorageKeys（key 常量）+ OverrideJsonStore（`override.user.json` + ConfigurationOverride `@Serializable`）；store 自带 `state: StateFlow<ConfigurationOverride>` + `update(transform)`，Settings 三个切片 VM 共享同一实例
- **订阅管理**：Pending → Processing → Imported 三阶段沙箱，`ProfileProcessor` 编排 snapshot → fetchAndValid（JNI 一次完成 fetch + provider prefetch + Parse 校验） → commit；processLock 串行，profileLock 守护 DB 一致性
- **订阅 HTTP**：mihomo `component/http.HttpRequest`（in-process，cgo），60s context timeout；UA 默认 `ClashMetaForAndroid/{version}`（订阅服务白名单），用户可在订阅 Add/Edit 页面填自定义 UA 持久化到 `ImportedEntity.userAgent` / `PendingEntity.userAgent`，pipeline 经 PendingSnapshot 透传到 `MishkaCoreBridge.fetchAndValid` → JNI → Go `runFetchAndValid` 内 `effectiveUA = trim(userAgent) ?: currentUserAgent()`；非 2xx / 空 body → `MishkaCoreError`；不做 base64/V2Ray 转换，原始 YAML 直接交 mihomo
- **订阅下载走代理**：`SubscriptionProxyResolver` 按「开关 + 代理运行中 + 可解析 mixed-port」返回 proxy URL 或 null；`ProfileProcessor` resolve 后传 `httpProxy` 给 bridge；native glue 在 fetchAndValid 入口 `os.Setenv("HTTPS_PROXY"/"HTTP_PROXY")` defer Unsetenv，覆盖订阅 fetch + provider prefetch + GeoIP 自动下载（mihomo `downloadToPath` 用 Go stdlib `http.Get` 读 env）。processLock 串行保证 set/unset 并发安全。无代理时直连，由 mihomo 内部 90s timeout 兜底
- **Pipeline 可取消**：协程 cancel → `nativeCancel(token)` → Go ctx Done → native 立即返回 "context canceled"；`ImportProgressDialog` 可选 `onCancel`；`cancelCurrentUpdate` 先同步 `clearProgress()` 让 UI 立即响应，再 cancel 协程
- **GeoIP 预制**：构建时 DownloadGeoFilesTask 下载 geoip.metadb/geosite.dat/ASN.mmdb 到 assets，启动时提取到 `files/mihomo/geodata/`。JNI 路径用 `mishkaCoreInit(geodataDir)` 把 mihomo 全局 homeDir 指到这里；subprocess runtime 仍按 `-d workDir` + symlink 复用同一份 GeoIP
- **配置校验**：JNI in-process `MishkaCoreBridge.fetchAndValid` 调用 mihomo `config.ParseRawConfig`，含 GEOIP/GEOSITE/IP-ASN 规则时触发数据库 init（缺失则走代理下载到 geodata/）
- **国际化**：英文 + 中文（zh-rCN），Compose Resources `stringResource()` + Android `getString()`；日志消息英文，代码注释中文

## 数据库架构（Room 3.0 KMP）

### 三表结构

| 表         | Entity          | 用途                                |
| ---------- | --------------- | ----------------------------------- |
| imported   | ImportedEntity  | 已导入的稳定订阅配置                |
| pending    | PendingEntity   | 编辑中的草稿（提交后移入 imported） |
| selections | SelectionEntity | 代理组选择记录（per 订阅）          |

### 类型安全与时间语义

- **ProfileType enum**（`File`/`Url`/`External`）通过 `ProfileTypeConverter` 透明映射为 TEXT 列
- **订阅 UUID 完整 36 字符**（`Uuid.random().toString()`，UUID v4 不做循环冲突检测）
- **updatedAt 动态计算**：`ImportedEntity` 无此字段，`resolveProfile` 读 pending→imported 目录 mtime，fallback `imported.createdAt`；订阅 commit/update 自然更新文件 mtime，无需主动写 DB

### Schema 版本

- **v1 → v2**（`MIGRATION_1_2`）：为 `imported` / `pending` 增加 `userAgent TEXT NOT NULL DEFAULT ''` 列，支持 per-profile UA 覆写。新增列时 schema 须由 KSP 自动导出到 [shared/schemas](shared/schemas/)（exportSchema 已在 build.gradle.kts `schemaDirectory("$projectDir/schemas")` 配置），跑一次 `:android:assembleDebug` 落盘；MIGRATION 必须在 `AppDatabaseBuilder.android/desktop` 的 `addMigrations(MIGRATION_1_2)` 注册，遗漏会让升级用户首次启动 crash。

### 三阶段流程（ProfileProcessor）

```
CREATE → Pending ✓, Imported ∅
  → APPLY（processLock 串行，3 阶段）：
      ① snapshot（profileLock 内）：query Pending + enforceFieldValid + prepareProcessing（清 processing/ + 复制 pending/{uuid}/ → processing/）
      ② fetchAndValid（锁外，可取消，JNI in-process 一次完成 fetch + provider prefetch + Parse）：
         - Url 类型：force=true，bridge 删旧 config.yaml 后由 mihomo `clashHttp.HttpRequest` 重新下载到 processing/config.yaml；并发 prefetch 所有 proxy-provider / rule-provider 到 processing/providers/；Parse 校验
         - File 类型：force=false，processing/config.yaml 已由 prepareProcessing 复制就位，bridge 跳过 fetch，直接 prefetch + Parse
         - httpProxy 由 SubscriptionProxyResolver.resolve() 决定（运行中代理 + 开关），透传到 native glue 的 HTTPS_PROXY env
      ③ commit（profileLock 内，`withContext(NonCancellable)` 原子）：snapshot 一致性检查 → commitProcessingToImported（清 imported/{uuid}/ + 复制 processing/ → imported/{uuid}/ + 删 pending/{uuid}/）→ DB 更新
  → 失败：cleanupProcessing（走 NonCancellable）；pending/ 与 imported/ 都不动，可 retry
  → RELEASE（放弃）：删 Pending DB + 删 pending/{uuid}/

PATCH（编辑已导入）→ Imported ✓, Pending ✓ → APPLY → Imported ✓（更新）, Pending ∅
UPDATE（手动/自动）→ 等价 APPLY，snapshot 取自 Imported，processing 基准为 imported/{uuid}/config.yaml
DELETE → Imported + Pending + Selection 三表清理 + imported/{uuid}/ + pending/{uuid}/ 删除
```

### 目录结构

```
files/mihomo/
├── geodata/                    共享 GeoIP（启动时从 assets 提取 + 符号链接到各订阅目录）
├── imported/{uuid}/            已验证的稳定配置（app UID）
├── pending/{uuid}/             编辑中的草稿（app UID）
├── processing/                 临时校验沙箱（单例）
├── runtime/{uuid}/             ROOT 模式 mihomo 运行时沙箱（从 imported/ 复制 + provider 缓存）
├── override.user.json          用户设置的 override（ConfigurationOverride）
└── override.run.json           启动时合并 TUN fd + AppProxy + rootMode 后的运行时 override
```

## 路由清单

`Route.kt` 中定义的路由，均实现 `NavKey`：

| 路由               | 类型        | 页面                          | 入口                   |
| ------------------ | ----------- | ----------------------------- | ---------------------- |
| Main               | data object | 主页（HorizontalPager 4 Tab） | 根路由                 |
| Subscription       | data object | SubscriptionScreen            | 主页 Tab 2 导航        |
| SubscriptionAdd    | data object | SubscriptionAddScreen         | 订阅页                 |
| SubscriptionAddUrl | data class  | SubscriptionAddUrlScreen      | 添加订阅页             |
| SubscriptionEdit   | data class  | SubscriptionEditScreen        | 订阅项编辑按钮         |
| Log                | data object | LogScreen                     | QuickEntries           |
| Provider           | data object | ProviderScreen                | QuickEntries           |
| DnsQuery           | data object | DnsQueryScreen                | QuickEntries           |
| Connection         | data object | ConnectionScreen              | QuickEntries           |
| VpnSettings        | data object | VpnSettingsScreen             | 设置页（仅 VPN 模式）  |
| RootSettings       | data object | RootSettingsScreen            | 设置页（仅 ROOT 模式） |
| NetworkSettings    | data object | NetworkSettingsScreen         | 设置页                 |
| MetaSettings       | data object | MetaSettingsScreen            | 设置页                 |
| ExternalControl    | data object | ExternalControlScreen         | 设置页                 |
| AppProxy           | data object | AppProxyScreen                | 设置页                 |
| FileManager        | data object | FileManagerScreen             | 设置页                 |
| FileManagerEditor  | data class  | FileManagerEditorScreen       | FileManager 点击项     |
| About              | data object | AboutScreen                   | 设置页                 |

## 页面与 ViewModel

| Screen                   | ViewModel             | 说明                                                            |
| ------------------------ | --------------------- | --------------------------------------------------------------- |
| HomeScreen               | HomeViewModel         | 状态/ActionButtons/NetworkInfo/QuickEntries/Latency/BottomCards |
| ProxyScreen              | ProxyViewModel        | 代理组 Tab + 节点选择 + 延迟测试 + 选择记忆                     |
| SubscriptionScreen       | SubscriptionViewModel | 订阅列表 + 增删改 + 全部更新 + 编辑 + 复制（可取消 Pipeline）   |
| SubscriptionEditScreen   | SubscriptionViewModel | 编辑名称/URL/更新间隔                                           |
| SettingsScreen           | —                     | 设置入口（TUN 模式/主题/开机自启）                              |
| LogScreen                | LogViewModel          | 实时日志流 + 级别过滤                                           |
| ConnectionScreen         | ConnectionViewModel   | 活跃连接列表 + 关闭                                             |
| ProviderScreen           | ProviderViewModel     | Provider 列表 + 刷新                                            |
| DnsQueryScreen           | DnsQueryViewModel     | DNS 查询（A/AAAA/CNAME/MX/TXT/NS）                              |
| AppProxyScreen           | AppProxyViewModel     | 应用代理白/黑名单                                               |
| VpnSettingsScreen        | —                     | VPN 设置（系统代理/排除路由等），仅 VPN 模式可见                |
| RootSettingsScreen       | —                     | ROOT 设置（TUN 设备名 + 热点客户端处置），仅 ROOT 模式可见      |
| NetworkSettingsScreen    | NetworkSettingsVM     | 端口/局域网/IPv6/DNS                                            |
| MetaSettingsScreen       | MetaSettingsVM        | 统一延迟/Geodata/TCP 并发/嗅探器                                |
| ExternalControlScreen    | ExternalControlVM     | mihomo HTTP API external-controller + API secret                |
| FileManagerScreen        | SubscriptionViewModel | imported 订阅目录浏览                                           |
| FileManagerEditorScreen  | SubscriptionViewModel | 多行 TextField 编辑 YAML，保存前 mihomo -t 校验，失败回滚       |
| AboutScreen              | —                     | 版本信息（hero 图标 + 3 阶段视差 + OS3 动态背景）               |
| SubscriptionAddScreen    | —                     | 添加方式选择（文件/URL/QR Code）                                |
| SubscriptionAddUrlScreen | SubscriptionViewModel | URL 导入订阅                                                    |

## 平台抽象（expect/actual）

| expect 声明                   | 类型           | Android 实现                       | Desktop     |
| ----------------------------- | -------------- | ---------------------------------- | ----------- |
| PlatformContext               | abstract class | typealias Context                  | 空对象      |
| PlatformStorage               | class          | SharedPreferences                  | Preferences |
| PlatformSystemInfo            | class          | ConnectivityManager + /proc        | 空实现      |
| ProxyServiceController        | class          | Intent 启停 VPN                    | 空实现      |
| AppListProvider               | class          | PackageManager                     | 空列表      |
| BootStartManager              | class          | BroadcastReceiver                  | 空实现      |
| FilePicker                    | class          | SAF                                | 文件对话框  |
| AppIcon                       | fun            | BitmapFactory                      | 资源加载    |
| IconDiskCache                 | object         | 磁盘缓存                           | 空实现      |
| showToast / initToastPlatform | fun            | android.widget.Toast（主线程派发） | 空实现      |

## Android 服务层

| 组件                       | 用途                                                                                                                                                         |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| MishkaTunService           | VpnService + JNI fork+exec 启动 mihomo                                                                                                                       |
| MishkaRootService          | ROOT 模式前台服务（ROOT TUN + ROOT TPROXY 两 submode 共享；EXTRA_SUBMODE 分支）                                                                              |
| RootTproxyApplier          | ROOT TPROXY 规则装配（mangle/nat chains + fwmark ip rule + local default dev lo，iptables + uid-owner 分应用）                                               |
| RootHelper                 | root 检测/启动/终止/存活检查/残留清理 + rmRfAsRoot + chownRecursiveAsRoot + runAsRootReturnCode                                                              |
| RootTetherHijacker         | ROOT 模式热点流量处置（ip rule 导向 main/TUN 表，绕过代理或走代理）                                                                                          |
| DynamicNotificationManager | 动态通知（订阅 `MihomoApplication.connectionManager.repository` 的 traffic 流，不持有自己的 HttpClient），两个 Service 共用                                  |
| MishkaTileService          | Quick Settings Tile 一键启停代理（双模式路由）                                                                                                               |
| BootReceiver               | 开机自启（默认 disabled，动态启用）                                                                                                                          |
| ConfigGenerator            | mihomo 工作目录/secret 生成工具（getWorkDir/getConfigFile/generateSecret）                                                                                   |
| RuntimeOverrideBuilder     | 运行时 override.run.json 装配（按 Submode = Vpn/RootTun/RootTproxy 分支：TUN fd / auto-route+include/exclude-package / tproxy-port+routing-mark+dns.listen） |
| ProfileFileOps             | 订阅目录管理（imported/pending/processing/runtime + GeoIP + ROOT 沙箱）                                                                                      |
| AndroidProfileFileManager  | ProfileFileManager 接口的 Android 实现                                                                                                                       |
| MihomoRunner               | mihomo runtime 进程管理（VPN: JNI fork+exec / ROOT: su）                                                                                                     |
| MishkaCoreBridge           | 订阅导入 JNI 门面：libmishka_jni.so（C 桥）+ libmishka_core.so（cgo c-shared，含 mihomo）；fetch + provider prefetch + Parse 一次完成                       |
| ProcessHelper              | JNI 包装（nativeForkExec/nativeKill/nativeWaitpid，仅 runtime 用）                                                                                           |
| NotificationHelper         | 三层通知渠道（VPN/更新进度/更新结果）                                                                                                                        |
| ProfileReceiver            | AlarmManager 调度自动更新                                                                                                                                    |
| ProfileWorker              | 前台服务执行后台配置更新                                                                                                                                     |

## 数据模型

`data/model/` 下的 `@Serializable` 数据类：

ConnectionInfo, DelayResult, DnsQuery, LogMessage, MemoryData, MihomoConfig, ProviderInfo, ProxyGroup, ProxyNode, RuleInfo, Subscription, TrafficData

另有 `ProfileType` enum + `ConfigurationOverride` override 数据类。

## 构建命令

mihomo 通过 git submodule 引入 Mishka fork（`mihomo/`，branch `Mishka`）。Gradle 自动驱动两条 Go 构建任务，产物落到 `android/src/main/jniLibs/<ABI>/`：

```bash
# 首次 clone 后或拉新 commit 后同步 submodule
git submodule update --init --recursive

# 构建 APK（assemble 自动触发 buildMihomo / downloadGeoFiles / CMake）
./gradlew :android:assembleDebug
./gradlew :android:assembleRelease
```

Go 构建任务（[GoBuildTask](buildSrc/src/main/kotlin/GoBuildTask.kt)）：
- `buildMihomo_arm64_v8a` → `libmihomo.so`（cgo c-shared，单一统一库 ~56MB）。CGO_ENABLED=1，需 NDK clang（从 `androidComponents.sdkComponents.ndkDirectory` 读）。ldflags 注入 `-Wl,-soname,libmihomo.so` 让消费方 DT_NEEDED 不烙构建路径

CMake 任务自动 `dependsOn(buildMihomo)`，CMake 产出两个轻量 native 文件链 libmihomo.so（IMPORTED + IMPORTED_SONAME）：
- `libmihomo_runner.so` (~6KB)：PIE 可执行 wrapper（[mihomo_wrapper.c](android/src/main/cpp/mihomo_wrapper.c)），dlopen 同目录 libmihomo.so + dlsym `mihomoEntry` + 透传 argv；MihomoRunner fork+exec 它启 runtime
- `libmishka_jni.so` (~6KB)：薄 JNI 桥（[mishka_jni.c](android/src/main/cpp/mishka_jni.c)），翻译 Kotlin `MishkaCoreBridge.nativeXxx` 到 libmihomo 的 C ABI

手动构建只在调试 native 出错时需要；版本字符串由 `gradle.properties` 的 `mihomo.version` 注入。

## 关键架构约束

不读代码看不出来的约束。违反会直接踩坑。

**启动校验单点**：所有"启动代理"路径必须经过 [ProxyServiceController.start / restart](shared/src/androidMain/kotlin/top/yukonga/mishka/platform/ProxyServiceController.android.kt)。`resolveStartSubscriptionId()` 统一校验 active 订阅 + `imported/{uuid}/config.yaml` 落盘，失败时一次完成：toast 提示用户、`ProxyServiceBridge.updateState(Error)`、清 `SERVICE_WAS_RUNNING`、在 Running 时发 STOP 让状态自洽。`HomeUiState.errorMessage` 当前未在 UI 展示，因此 Toast 必要。新增入口（Wear OS / shortcut / 自动化）严禁绕过 controller 直接 `startService(MishkaTunService/MishkaRootService)`；Service 内 `ProfileFileOps.hasValidConfig` 是兜底防御层，针对 ADB / 第三方 Intent 直拉 Service。Tile / 通知等无 Activity 上下文要在 VPN 模式启动时弹系统授权对话框，必须经 [VpnPermissionActivity](android/src/main/kotlin/top/yukonga/mishka/service/VpnPermissionActivity.kt) 透明跳板（`VpnService.prepare()` 要求 Activity context，TileService 不是 Activity）；UI 路径仍走 MainActivity 自身处理。shared/androidMain 不能直接依赖 :android 的 R 类，controller 错误文案走 `resources.getIdentifier` 反射查 `error_no_active_profile`。

**Ktor HttpClient 所有权**：禁止任何模块直接 `MihomoApiClient(...)` / `MihomoWebSocket(...)`；统一从 `MishkaApplication.instance.connectionManager.repository: StateFlow<MihomoRepository?>` 订阅。`MihomoConnectionManager` 是唯一持有 `close()` 责任的方，按 `ProxyServiceBridge.state` 自动 connect/disconnect、原子 close 旧 + new 新——不做 endpoint 比对（attach 重连多一次重建 < 50ms，胜过状态机比对出 race 的代价）。新增消费方仅 collect repository 即可；ViewModel 的 `setRepository(repo)` 仅做信号传递，不承担 close 责任。例外：`SubscriptionProxyResolver` 因 mixed-port 探测场景独立于 mihomo 实时连接，可自建短生命周期 HttpClient，但必须 `client.use{}` 或 try/finally close。订阅 fetch 自身已 in-process 化（[MishkaCoreBridge.fetchAndValid](shared/src/commonMain/kotlin/top/yukonga/mishka/data/bridge/MishkaCoreBridge.kt)），不再用 Ktor。

**Override 注入**：所有 override 走 `--override-json` CLI flag + JSON 文件，Kotlin 侧零 YAML 改写。用户设置 `OverrideJsonStore.update { ... }` → `override.user.json`，启动时 `RuntimeOverrideBuilder` 叠加 TUN fd / AppProxy / rootMode → `override.run.json`。`secret` / `external-controller` 走 `--secret` / `--ext-ctl` CLI flag 不进 JSON。

**RuntimeOverrideBuilder 默认注入**（用户未显式设置时生效）：`tcp-concurrent=true`（代理并发拨号降低首包延迟）、`find-process-mode=off`（分应用已由 sing-tun `include/exclude-package` / VpnService / iptables uid-owner 处理，mihomo 运行期遍历 `/proc` 纯冗余）。ROOT TUN 分支额外默认 `tun.mtu=9000 + gso=true + gso-max-size=65535`（大包聚合减少 sing-tun read syscall），由 `StorageKeys.ROOT_TUN_JUMBO_MTU` 开关（默认 true）控制；关闭时回退 `mtu=1500 + gso=false`，兼容上游链路拒绝大包的极端 ROM。VPN 模式不注入 MTU/GSO（MTU 由 `VpnService.Builder` 系统管）。mixed-port 决策按以下优先级：① 用户 override 显式设置 → 用用户值；② 订阅 yaml 自带 `mixed-port` → 不注入，mihomo 沿用订阅原值；③ `StorageKeys.SUBSCRIPTION_UPDATE_VIA_PROXY` 启用 → 注入 7890 兜底，确保 `SubscriptionProxyResolver` 能稳定解析到代理端口；④ 其余情况不注入。订阅 yaml 的 mixed-port 由 `ConfigGenerator.readSubscriptionMixedPort` 行级扫描得出，避免兜底值意外覆盖订阅自带的非默认端口。用户 `override.user.json` 里的同名字段优先级最高。

**硬编码覆盖订阅（按 submode 分）**：`profile.store-selected=false` / `profile.store-fake-ip=true` 三模式共用。

- **VPN**：`tun.enable=true` + `tun.file-descriptor=fd` + `tun.dns-hijack=[0.0.0.0:53]`，`auto-route=false`；透传 `tun.stack`、`tun.device`
- **ROOT TUN**：`tun.enable=true` + `auto-route=true` + `auto-detect-interface=true` + `iproute2-table-index=2022` + `iproute2-rule-index=9000` + `tun.dns-hijack=[0.0.0.0:53]` + `include/exclude-package`（按 AppProxyMode）
- **ROOT TPROXY**：`tun.enable=false`、`tproxy-port=7895`、`dns.listen=0.0.0.0:1053`；**不写** `routing-mark`（Android Netd 冲突，见下）、**不写** `include/exclude-package`（AppProxy 走 iptables uid-owner）；`dns.enhanced-mode` 透传用户设置

**三模式段差异**：VPN 注入 `file-descriptor` + `auto-route=false`；ROOT TUN 注入 `auto-route=true` + `auto-detect-interface=true` + `include/exclude-package`；ROOT TPROXY 仅 `tun.enable=false`，所有分应用/DNS/路由逻辑交 `RootTproxyApplier`（iptables + uid-owner 放行 root）

**ROOT TPROXY 的 IPv6 注入开关**：`RootTproxyApplier.apply` 接受 `ipv6Enabled` 参数，由 `MishkaRootService.applyTproxyRules` 读 `StorageKeys.VPN_ALLOW_IPV6` 决定（与 VPN/ROOT TUN 的 `inet6-address` 完全同一开关）。默认 false：跳过所有 ip6tables / `ip -6` 规则注入，IPv6 出站走内核原生主路由表（设备有 native IPv6 时直连，无时 ENETUNREACH 让 App 快速放弃）。开启 true 才装 v6 mangle/nat 规则。teardown 永远尝试清 v4+v6（`2>/dev/null` 幂等），保证开关切换路径无残留。**为什么必要**：mihomo 默认 `ipv6: false` 时无法 dial IPv6 目的，TPROXY 无差别拦截 IPv6 → mihomo accept → 拨号 "ip version error" → App 重试，会形成 600 conn/s 紧密循环（实测 95s 产生 56k 失败 + 25MB 日志），mihomo 进程内存随连接跟踪表 + 日志 buffer + GC 压力快速增长。VPN/ROOT TUN 由 `inet6-address` 控制 TUN 是否注册 v6 默认路由，关闭时内核直接 ENETUNREACH，本身就有这层过滤；TPROXY 的 iptables 拦截不区分应用是否能用 v6，必须显式门控。

**secret 优先级**：用户设置 > 订阅 `config.yaml` 顶层 `secret:`（`ConfigGenerator.readSubscriptionSecret` 轻量行扫描）> 随机 UUID 前 16 字节；ROOT attach 分支走 storage 持久化的 `existingSecret`

**CMFA embed mode 禁 HTTP 配置 API**：`PATCH/PUT /configs` / `POST /restart` / `POST /configs/geo` / `PUT/PATCH /rules` / `POST /upgrade` 全部 404。**绝不添加** `patchConfig`/`restart` 方法，所有配置修改走 `OverrideJsonStore.update { ... }` + `serviceController.restart()`，UI 用 `RestartRequiredHint` Card 提示。

**订阅导入走 JNI in-process**：fetch + provider prefetch + Parse 三步走 [MishkaCoreBridge.fetchAndValid](shared/src/commonMain/kotlin/top/yukonga/mishka/data/bridge/MishkaCoreBridge.kt)，禁止再起 mihomo 子进程做这些事。`MishkaApplication.onCreate` 必须先 `extractGeoFiles()` 再 `MishkaCoreBridge.init(geodataDir, userAgent)`——后者 `constant.SetHomeDir` 必须指向已就位的 GeoIP 目录。runtime 仍是 subprocess 路径，与 JNI 路径互不干扰

**JNI 库加载顺序**：`libmishka_jni.so` 链接依赖 libmihomo.so 的导出符号，`System.loadLibrary("mihomo")` 必须先于 `loadLibrary("mishka_jni")`，否则 jni 库找不到符号

**libmihomo.so 必须显式设 SONAME**：cgo c-shared 默认不写 SONAME，消费方链接器会把构建期 .so 绝对路径烙进 DT_NEEDED，运行时 dlopen 报 `UnsatisfiedLinkError: library "..." not found`。两边对齐：[GoBuildTask](buildSrc/src/main/kotlin/GoBuildTask.kt) 给 ldflags 加 `-extldflags=-Wl,-soname,libmihomo.so`，CMake `IMPORTED_SONAME` 一致

**libmihomo_runner.so 是 PIE wrapper**：[mihomo_wrapper.c](android/src/main/cpp/mihomo_wrapper.c) 产出的 ~6KB 可执行，读 `/proc/self/exe` 推同目录 → dlopen libmihomo.so → dlsym `mihomoEntry` → 透传 argv。新加 CLI flag 必须同步注册到 mishka_core/runtime.go 的 `flag.NewFlagSet`，否则 fork+exec 时被 ExitOnError 拦截。`RootHelper.cleanupOrphanedMihomo` 按 `libmihomo_runner.so` cmdline 匹配孤儿进程

**cgo `*C.char` 必须 Go 侧释放**：libmihomo.so 通过 `//export` 返回的字符串内存属于 Go runtime，C 侧只能调 `mishkaFreeString()`，绝不能 `free()`，否则 cgo 堆损坏

**Mishka 自身包名必须绕过 TUN/VPN**：`ProcessBuilder` 子进程 HTTP 被代理捕获会永久阻塞；ROOT 三种 AppProxyMode 都把 `packageName` 从 include 剔除或塞进 exclude，VPN `AllowSelected` 分支先过滤 self 再 addAllowed，过滤后空列表退化到 `addDisallowedApplication(self)`。

**协程锁规则**：`kotlinx.coroutines.sync.Mutex` **不可重入**。`updateImported`/`commitPending`/`queryImported`/`queryPending` 被 `ProfileProcessor` 在 `withProfileLock { ... }` 内调用，**不能自己加** `profileLock.withLock`；`create`/`patch`/`release`/`clone`/`delete` 直接被 ViewModel 调用，**保留自身** `profileLock.withLock`。

**Pipeline 协程取消语义**：外层 `runProcess` 可取消，仅 commit 阶段包 `withContext(NonCancellable)` 保证文件 swap + DB 更新原子；catch 块 `cleanupProcessing` 也走 NonCancellable。`cancelCurrentUpdate` 先同步 `clearProgress()` 让 Dialog 立即消失，再 `currentJob?.cancel()` 让协程后台收尾。`MishkaCoreBridge.fetchAndValid` 内部 try/catch 任何 Throwable 时调 `nativeCancel(token)`，让 Go ctx 进入 Done，native 函数立即返回 `error: context canceled`（HTTP read 中的请求要等到下一次 ctx 检查，通常 <100ms）

**ROOT runtime/ 沙箱**：ROOT mihomo 工作目录是独立 `runtime/{uuid}/`（从 imported/ 复制），不碰 imported/。启停钩子：`startProxy` 新鲜启动前 `prepareRootRuntime`；stop/restart/进程监控三条死亡路径都在 `clearPersistedState` 之前 `cleanupRootRuntime`；attach 分支**不重建** runtime/。存量旧 root:root 遗孤由 `MishkaApplication` 后台线程一次性 `su chown -R $APP_UID imported/` 迁移（`StorageKeys.MIGRATION_ROOT_RECLAIM_DONE` 打标）

**订阅导入不自动切换活跃**：`addSubscription`/`addFromFile` 成功后**不**调 `setActive(sub.id)`；仅首次导入（`importedDao.count() == 1`）由 `commitProcessingToImported` 自动激活

**SubscriptionRepository 单例 + 订阅流量数据合并**：`SubscriptionRepository` 由 [MainActivity](android/src/main/kotlin/top/yukonga/mishka/MainActivity.kt) 创建一份并注入 `SubscriptionViewModel` 与 `HomeViewModel`（`ProfileWorker` 例外，后台调度独立进程构建临时实例）；禁止 ViewModel 内部 new 自建。订阅页（`SubscriptionScreen` 看 `subscription.total > 0`）与主页流量栏的数据语义必须**强一致**——`SubscriptionRepository.resolveProfile` 在 combine 内合并三层数据：`pending > live provider snapshot > imported DB`。`_liveProvider: MutableStateFlow<LiveProviderSnapshot?>` 携带 `subscriptionId` 做归属校验，避免用户切 active 不 restart 时把旧 active 的 runtime 数据归属到新 active。[HomeViewModel.loadConfig](shared/src/commonMain/kotlin/top/yukonga/mishka/viewmodel/HomeViewModel.kt) 是唯一 producer：拿到 mihomo `/providers/proxies` 后 `aggregateProviderInfo`（所有 `subscriptionInfo.Total > 0` 的 provider Upload/Download/Total sum、Expire 取最近非零）→ `onLiveProviderInfo(uuid, info)` 推回 Repository；`disconnectStreams` 时推 `(null, null)` 清空。订阅页只 collect `repository.subscriptions`、主页只 collect `activeSubscription`，两边自动看到同一份合并后的视图模型——杜绝「订阅页显示『未获取到流量信息』、主页显示聚合流量」的语义冲突。**为什么聚合**：mihomo `/providers/proxies` 的 `subscriptionInfo` 是 per-`proxy-provider` 解析 `subscription-userinfo` header 得来，多源 yaml 下用 `values.firstOrNull()` 会取到 Map 迭代顺序的随机 provider；聚合保证多源场景"总用量 + 最近过期"语义正确，单源退化为单值。**为什么 DB 仍然必要**：模板订阅（顶层 URL 无 userinfo header）DB.total=0 但 yaml 内 proxy-provider 各自有 header → live 覆盖；常规单源订阅（节点直接写 `proxies:`）mihomo providers 为空 → live=null → fallback DB；File 类型订阅两边都为 0 → UI 显示 "--"。三处触发 subscription 字段刷新：`ProxyState.Starting/Running` 状态切换、`connectToMihomo` 入口、`activeSubscription` Flow emit（用户切 active 或 Repository merge 新 live 后即时反映）。

**Active 订阅名缓存同步**：通知栏（`DynamicNotificationManager.startOrFallbackStatic`）启动时一次性读 storage `ACTIVE_PROFILE_NAME` snapshot，不订阅 DB Flow。`SubscriptionRepository` 在 `commitPending`（编辑/首次激活）与 `updateImported`（手动/自动更新拉到新 `profile-title`）末尾必须调 `syncActiveNameIfActive(uuid, name)`，否则编辑/更新的就是 active 订阅时通知栏标题会停在旧名，直到用户切换 active 才间接修复。辅助函数内部短路 active 检查 + 同名短路（避免周期性流量更新打断通知动画），仅当 uuid 是 active 且 name 实际变化时写 storage + `ProxyServiceBridge.requestNotificationRefresh()` 让 service 重读。`updateImported` 调用方还需在 `name != null && name != existing.name` 时才调，防止 `name = null` 的纯流量字段刷新触发冗余 emit。

**JNI fork+exec**：Android `ProcessBuilder` fork 后强制关闭非标准 fd（无论 O_CLOEXEC），VPN 模式必须用 JNI `fork()+exec()`（`process_helper.c`）保留 TUN fd 继承

**TUN init silent failure 兜底**：mihomo `ReCreateTun` 失败仅 log 不退出。检测规则：① `MishkaTunService` 清 O_CLOEXEC 失败必须视为致命（`closeTunFd` + `ProxyState.Error` + `stopSelf`）② `MihomoRunner.waitForReady` API ready 后 delay 500ms 扫日志匹配 `Start TUN listening error` / `configure tun interface` / `create NetworkUpdateMonitor`

**fd 模式 forwarderBindInterface 必须为 true**：upstream `e38aa82a "don't force bind interface when using fd for tun"` 在 Mishka VPN（gvisor stack + VpnService fd）下实测破坏 fd 路径流量——延迟测试通（mihomo 直接 dial 节点不经 fd），实际经 fd 流量不通。静态搜索 sing-tun 0.4.18 仅 `stack_system` 读这个标志、gvisor stack 不读，但实测推翻该结论，说明 mihomo 内部或 sing-tun 间接路径仍依赖此标志。Mishka fork 第 5 patch（`feat: keep forwarder bind interface for android fd tun`）保留 fd 模式下 `forwarderBindInterface = true` 的旧行为。每次 rebase 上游必须验证 [listener/sing_tun/server.go:160](D:/GitHub/mihomo/listener/sing_tun/server.go#L160) 的 `forwarderBindInterface = true` 仍然 active。

**VPN MTU 同步**：`VpnService.Builder.setMtu` 与 mihomo `cfg.Tun.MTU` 必须同值。sing-tun 在 fd 模式给 gvisor `fdbased.New` 用 `cfg.Tun.MTU` 设 endpoint 缓冲，0 时所有 read 失败 → 表象"延迟测试正常但流量不通"。两侧共用 [RuntimeOverrideBuilder.VPN_TUN_MTU](d:/GitHub/Mishka/android/src/main/kotlin/top/yukonga/mishka/service/RuntimeOverrideBuilder.kt) 常量，禁止任一边 hardcode。

**WebSocket 重连**：Ktor `for (frame in incoming)` graceful close 静默退出。`MihomoWebSocket.webSocketFlow` 自实现无限重连 + 指数退避（1s→30s）+ 20s 心跳；`CancellationException` 必须 rethrow；`connectionState: StateFlow<Boolean>` 粗粒度暴露

**startForeground 防御**：Tun/Root/ProfileWorker 的 onCreate 均 `try { startForeground() } catch(Exception)`。真实风险是 API 31+ `ForegroundServiceStartNotAllowedException` 和 API 34+ FGS type 异常（非 POST_NOTIFICATIONS 拒绝）。失败路径：Tun/Root 上报 `ProxyServiceBridge.Error` + `stopSelf()`；ProfileWorker 仅 `stopSelf()`。**不降级为普通 Service**

**ProfileWorker.jobs**：用 `ConcurrentLinkedQueue<Job>` + `while (true) { jobs.poll()?.join() ?: break }`（非 `mutableListOf` + `while(isActive) delay(1s)` 轮询；onStartCommand 主线程 + scope 协程 IO 跨线程访问需线程安全容器）

**孤儿 mihomo 清理**：`RootHelper.cleanupOrphanedMihomo(tunDevice)` 单次 su shell 完成 pkill + `ip link delete <tunDevice>`（防 sing-tun EEXIST）；device name 走 `escapeShellSingleQuoted` POSIX 转义。VPN 启动在 `hadRootPid || HAS_ROOT` 时触发，清 ROOT 持久化 key + 兜底 `cleanupAllRootRuntime`

**ROOT 模式重连校验**：`attachToExisting` 三重验证（`kill -0` 存活 + `/proc/$pid/cmdline` 含 libmihomo.so + stored secret 通过 `/configs` Bearer 鉴权 2xx）；订阅一致性由 `startProxy` 在 attach 前比对 persisted vs 请求 subscriptionId，不一致走 cleanup + 全新启动

**ROOT 模式热点处置**：sing-tun `auto_route` 的 catch-all ip rule（priority 9002：`NOT iif lo lookup 2022`）不区分本机 vs 转发流量，热点客户端包 iif=wlan2/ap0 也命中被导进 TUN，但 mihomo 对非本机源 IP 处理不稳（黑洞/高丢包）。`RootTetherHijacker` 在 sing-tun 之前插队两种处置模式：

- **BYPASS（默认）**：`ip rule priority 8000/8002` 去程 + 回程均 action=`goto 9010`（sing-tun 自己的 nop marker，ruleStart+10）。去程越过 catch-all 后命中 Android 原生 iif forward rule（`iif <tether> lookup <upstream>`，priority ~21000）→ 走 wlan0/rmnet。回程 goto 过去命中 local_network/main 里的 `<subnet> dev <tether>` 连接路由。
- **PROXY**：内核态 TPROXY 透明代理——mihomo 启用 `tproxy-port: 7895` 入站监听（IP_TRANSPARENT socket），`iptables -t mangle -A PREROUTING -i <tether> -j mishka_tether` 把热点 TCP+UDP 劫持到 `--on-port 7895 --tproxy-mark 0x01000000/0x01000000`；`ip rule fwmark 0x01000000/0x01000000 lookup 2024 priority 7999` + `ip route add local default dev lo table 2024` 让带 mark 的包在 PREROUTING 里被判定为本机投递、命中 tproxy listener。**完全绕开 sing-tun userspace TCP stack**，延迟/吞吐接近 BYPASS。常量值（0x01000000 bit 24 / table 2024 / priority 7999）对齐 box_for_magisk 一类成熟 Magisk 模块的验证过的取值，避开 Android Netd 低 16 位 mark。`mishka_tether` chain 内部顺序：① `-m conntrack --ctstate INVALID -j DROP` 丢异常包；② [IptablesIntranet](android/src/main/kotlin/top/yukonga/mishka/service/IptablesIntranet.kt) v4/v6 CIDR `-j RETURN`（DNS 除外，让 mihomo 处理 fake-ip）；③ `-p tcp/udp -m socket -j mishka_tether_divert`（DIVERT 子 chain：ESTABLISHED 流仅打 fwmark + ACCEPT，跳过 TPROXY 重拦截，命中 fwmark 路由→`local default dev lo` 投递到已有 mihomo socket）；④ 新连接 `-j TPROXY` 到 mihomo 监听端口。apply 整体用 heredoc 单次 su 调用（~60 条命令），避免 per-cmd 风格 3-6s 累计 fork 开销。
- **PROXY 降级**：`xt_TPROXY` 内核模块不可用时（部分裁剪 ROM），退回到"ip rule 去程+回程对称 `lookup 2022`"——流量双向进 sing-tun，性能次于 TPROXY 但连接可用。`RootTetherHijacker.probeTproxySupport()` 在 `MishkaRootService.startProxy` 里 runtime 探测；结果驱动 `RuntimeOverrideBuilder.buildAndWriteForRun(tproxyForTether = ...)` 决定是否写 `tproxy-port`。
- **attach 路径约束**：mihomo 进程启动时 tproxy-port 是否监听已锁死；app 被杀期间用户若改过 tether mode，attach 上去规则会与 mihomo 实际状态错位。`StorageKeys.ROOT_TETHER_MODE_ACTIVE` 在 start 成功后写入当时的 mode 快照，`startProxy` attach 前比对 `ROOT_TETHER_MODE` 与 `ROOT_TETHER_MODE_ACTIVE`——不一致则拒绝 attach，走 fresh restart。
- **attach 条件 re-apply**：attach 成功后默认先调 `RootTetherHijacker.anyRulesPresent()` / `RootTproxyApplier.anyRulesPresent()` probe 锚点（优先按 xt_comment 前缀 `mishka:tether:` / `mishka:tproxy:` 扫 iptables，次选 chain 名，最后 priority 7999/8000），present 则 skip 重建；absent 才 re-apply。修复系统重启 / 与 box_for_magisk 共存被清残留场景。`ROOT_ATTACH_FORCE_REAPPLY=true` 强制 re-apply（诊断开关）。
- **接口识别（纯手填）**：用户在 RootSettingsScreen 的「热点接口名」多行 TextField 输入 CSV（`ROOT_TETHER_IFACES`，默认 `wlan1,wlan2`），编辑对话框提供「检测当前接口」扫描按钮辅助（[TetherInterfaceScanner](shared/src/androidMain/kotlin/top/yukonga/mishka/platform/TetherInterfaceScanner.android.kt) 走 `NetworkInterface.getNetworkInterfaces()` 列出 UP + 有 site-local IPv4 + 不在蜂窝/隧道黑名单的候选；勾选默认按 `isLikelyTetherInterface` 排除 `wlan0` 主 STA）。不做实时自动发现 / 系统回调订阅 —— 实践表明 `TETHER_STATE_CHANGED` extras 在 Android 11+ 不可靠（@hide），`NetworkInterface` 白名单 regex 又覆盖不全 OEM 命名，成本收益不划算。
- **xt_comment 标记**：所有 iptables 规则（RootTetherHijacker / RootTproxyApplier 内部）打 `-m comment --comment "mishka:tether:..."` 或 `"mishka:tproxy:..."` 前缀，用于 `anyRulesPresent` 精确区分 Mishka 规则与第三方模块残留；顶层 PREROUTING/OUTPUT jump 规则不打 comment（teardown 走 blind `-D` 需与旧版遗留无 comment 规则兼容，避免跨版本升级遗漏清理）。
- **xt_TPROXY UI 告警**：`StorageKeys.ROOT_TPROXY_KERNEL_CAPABLE` 存 probe 结果（`"true"`/`"false"`/`""`），仅 PROXY 或 ROOT TPROXY 路径写入；RootSettingsScreen 读此 key，`== "false"` 且当前模式会用到 TPROXY 时在顶部显示 `errorContainer` 配色的降级告警 Card，说明已退到兼容路径，让用户明确感知而非静默 fallback。
- **不能** 用 `lookup main`——Android main 表无 default route（default 分散在各 upstream 独立表）。
- 回程规则靠 `NetworkInterface.getByName(iface)` 读 InterfaceAddress + prefix length 算 CIDR（BYPASS / PROXY-fallback 都要）；接口未就绪（热点后开）会 WARN skip，用户需 restart 代理触发重新 apply。
- `ip rule add iif <name>` 对不存在接口内核按名注册，接口出现自动生效；`ip rule add to <subnet>` 则需 apply 时接口已有地址。
- 生命周期：startProxy（含 attach）后 apply；stop/restart/死亡三路径 teardown（NonCancellable）。`teardown()` 清 BYPASS+fallback 的 8000/8001/8002/8003 priority 规则 + TPROXY 路径的 mangle chain `mishka_tether` + `mishka_tether_divert` + fwmark rule (7999) + route table 2024，两类都跑保证任意前置状态都能清干净。teardown 末尾 `verifyClean()` 扫锚点，残留重试一轮，仍残留 Log.w 记录但不阻止 stop。TUN table/rule index 固定写入 `override.run.json`（2022/9000）锁定 goto 目标 = 9010。
- **不做本机 TPROXY**：sing-tun TUN 继续负责本机流量分应用代理 / DNS 劫持；全 TPROXY（对齐 box_for_magisk）需要重写 AppProxy（uid-owner）、DNS（nat REDIRECT）、fake-ip 交互、IPv6 DNS 防泄漏等一整条链，属独立重构范围。

**ROOT 模式不做动态通知**：[DynamicNotificationManager.startOrFallbackStatic](android/src/main/kotlin/top/yukonga/mishka/service/DynamicNotificationManager.kt) 内部判定 `tunMode != TunMode.Vpn` 时强制走静态分支，忽略用户的 `DYNAMIC_NOTIFICATION` 偏好；[SettingsScreen](shared/src/commonMain/kotlin/top/yukonga/mishka/ui/screen/settings/SettingsScreen.kt) 的开关在 ROOT 模式下 `enabled=false` + 副标题说明。原因：VPN 模式靠 `BIND_VPN_SERVICE` 隐式让进程进入 `BOUND_FOREGROUND_SERVICE` 状态自动保 CPU、不被 battery optimization 限制；ROOT 模式无任何系统 binding，Activity 进后台后整个 device 进 idle（CPU governor 降频 + idle C-state），1Hz `/traffic` WS 帧合并、`NotificationManager.notify()` 批处理，动态通知冻结。`PARTIAL_WAKE_LOCK` 实测被系统 DISABLED 救不了（它只阻 SoC suspend 不阻 CPU idle）；唯一根治是引导用户 `IGNORE_BATTERY_OPTIMIZATIONS`，权衡 UX 后选择「直接不支持」承认平台限制。**ROOT TUN 历史上看似工作**只是因为 mihomo 持续 `read(tun_fd)` 顺手撑住 device 不进 idle，不可靠且不一致——一并退化为静态。

**Flow.catch 是终结型操作**：`.catch` 捕获后流结束、不会重订阅。长生命周期 UI/通知 Flow 的瞬态异常（如 `NotificationManager.notify()` 偶发 `RemoteServiceException`）应包到 `collect` 内部用 `runCatching` 处理；`.catch` 只留给真正需要终结的失败。[DynamicNotificationManager](android/src/main/kotlin/top/yukonga/mishka/service/DynamicNotificationManager.kt) 历史曾因顶层 `.catch` 在 `notify()` 偶发抛错时让整条 trafficJob 永久死亡，回归过一次。

**错误兜底**：用户面向异常走 `Throwable.describe()`（`message ?: simpleName ?: "Unknown error"`），避免 Ktor `ConnectException()` 等无参异常漏到 UI 显示 "null"；`SubscriptionFetcher` 显式检查 `response.status.isSuccess` + 空 body 抛 typed `ImportError`

**后台卡片隐藏**：`StorageKeys.HIDE_TASK_CARD` 控制 Settings General 里的「隐藏最近任务卡片」开关；Android 侧由 `MainActivity` 在 onCreate 读取偏好并通过 `ActivityManager.AppTask.setExcludeFromRecents()` 应用，运行时切换经 `App -> AppNavigation -> SettingsScreen` 透传 nullable `onHideTaskCardChange` callback 即时生效。不要把 MainActivity 固定写成 manifest `android:excludeFromRecents="true"`，否则会失去用户可切换语义；commonMain 只能暴露 callback，不直接依赖 Android API。当前实现依赖单 Activity task（`appTasks.firstOrNull()`）；若以后引入 document/multi-task 入口，必须改为按当前 `taskId` 匹配目标 AppTask。

**其他**：`Activity configChanges=uiMode` 防深浅色切换重建；预测性返回手势走 HiddenApiBypass 反射 `setEnableOnBackInvokedCallback`（Android 14+ 可选）；`network_security_config.xml` 全局 `cleartextTrafficPermitted=true`（订阅源常用 HTTP，对齐 CMFA UX；CMFA 因订阅 fetch 在 Go 侧绕过 Java 网络栈而无需此设置，Mishka commonMain Ktor 走 OkHttp 必须显式放行）；`jniLibs.useLegacyPackaging = true` 确保 libmihomo.so 解压到 nativeLibraryDir

## UI 规范

- 所有 UI 组件使用 miuix（Card、TopAppBar、NavigationBar、SmallTitle、TextButton 等）
- 返回按钮使用 MiuixIcons.Back
- 底栏图标：Sidebar / Tune / UploadCloud / Settings
- Badge：`clip(RoundedCornerShape(3.dp))` + 9.sp Bold Monospace
- 操作 IconButton：`minHeight/minWidth = 35.dp, backgroundColor = secondaryContainer`
- **页面骨架**：Scaffold + TopAppBar(scrollBehavior) + LazyColumn
  - LazyColumn 必须加 `.scrollEndHaptic().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)`
  - `contentPadding = PaddingValues(top = innerPadding.calculateTopPadding())`——仅设 top，不设 bottom
  - 首个 item（非 RestartRequiredHint）用 `item { Spacer(Modifier.height(12.dp)) }` 顶部呼吸
  - 末尾 item 统一 `item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }` 吸收导航栏 + 留白
  - **二级页面（独立 NavDisplay entry）签名禁止 `bottomPadding: Dp` 参数**——靠 `Spacer(navigationBarsPadding())` 自适应即可
  - **4 个 Pager Tab 例外**（HomeScreen / ProxyScreen / SubscriptionScreen / SettingsScreen）：因外层 `MainPage` Scaffold 持有 `bottomBar`，必须接 `bottomPadding: Dp` 把 outer Scaffold 的 `innerPadding.calculateBottomPadding()` 透传给 LazyColumn `contentPadding`
- **顶栏 / 底栏毛玻璃**：所有页面 Scaffold 必须用 `BlurredBar` 包裹 `TopAppBar` / `NavigationBar`；MainPage 外层 Scaffold + 每个二级页面各自一份 backdrop（嵌套 layerBackdrop 是 OK 的，layer 抓取相互独立）。模式：
  - 顶层取 `val backdrop = rememberBlurBackdrop()` + `val blurActive = backdrop != null` + `val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface`
  - `topBar = { BlurredBar(backdrop, blurActive) { TopAppBar(... color = barColor ...) } }` / `bottomBar = { BlurredBar(backdrop, blurActive) { NavigationBar(color = barColor) {...} } }`
  - 内容区 LazyColumn modifier 链中追加 `.then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)` —— 让 backdrop 抓取内容 layer 给 TopAppBar/NavigationBar 的 textureBlur 用
  - 含搜索动画的页面（AppProxyScreen / ConnectionScreen）：在 BlurredBar 内套 `searchStatus.TopAppBarAnim(backgroundColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface) { TopAppBar(...) }`，让搜索切换时不挡住毛玻璃
- **Card 间距**：水平 12.dp，每项统一 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`；不使用 `Arrangement.spacedBy`
- **TextField 表单**：不包 Card，直接 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`
- **Edit Dialog 按钮顺序**：`not_modified | cancel | confirm`（三按钮 weight(1f) + `spacedBy(8.dp)`），confirm 用 `ButtonDefaults.textButtonColorsPrimary()`
- **用户反馈**：`platform.showToast(message, long = false)`——轻量操作结果提示
- **i18n**：所有用户字符串走 `stringResource(Res.string.xxx)` 或 `getString(R.string.xxx)`，禁止硬编码
  - 新增字符串同时加到 `values/strings.xml` + `values-zh-rCN/strings.xml`
  - key 命名：`{页面}_{描述}`，通用按钮 `common_` 前缀
  - 日志消息英文，代码注释中文
- **语义色 token**：状态色（运行中 / 等待 / 失败）、延迟色（优 / 可 / 差 / 未测）、按钮色（restart / stop / reload）、错误文案色，统一走 `top.yukonga.mishka.ui.theme.StatusColors`（`runState` / `delay` / `actionButton` / `danger` / `healthy` / `warning` / `neutral` / `selectedNodeContainer`）。**禁止在屏幕里散落 `Color(0xFF...)`**；仅 `MiuixTheme.colorScheme.*` 已有的 token 与 `StatusColors` 是合法颜色源
- **Flow 收集**：所有 commonMain 屏幕用 `androidx.lifecycle.compose.collectAsStateWithLifecycle()`（KMP 版 `lifecycle-runtime-compose` 已包含），不用 `androidx.compose.runtime.collectAsState`；后台时上游不再驱动 UI 重组
- **强跳过友好的状态形状**：UiState `data class` 必须 `@Immutable`；含跨节点变化的大集合字段（节点列表、连接列表、组列表）一律用 `kotlinx.collections.immutable.ImmutableList` / `ImmutableMap`（构造时 `.toPersistentList()` / `.toPersistentMap()`），避免 SSM 下重组每次都做结构性 `equals` 走 List/Map 全表
- **可复用组件 API**：`ui/component/*` 的可复用 composable 必须暴露 `modifier: Modifier = Modifier` 作为第一可选参，并应用到 root-most 节点；带表单 / 控件的 wrapper 透传到底层 miuix 组件（`ArrowPreference` / `OverlayDropdownPreference` / `Card` 等都接受 modifier）
