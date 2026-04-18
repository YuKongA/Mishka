# Mishka

Compose Multiplatform + miuix + mihomo 跨平台代理客户端，首先完整支持 Android。

## 技术栈

| 组件                  | 版本          | 用途                                 |
| --------------------- | ------------- | ------------------------------------ |
| Kotlin                | 2.3.20        | 语言                                 |
| AGP                   | 9.1.1         | Android 构建                         |
| KSP                   | 2.3.6         | 注解处理（Room）                     |
| Compose Multiplatform | 1.10.3        | 跨平台 UI 框架                       |
| miuix                 | 0.9.0         | UI 组件库 + 导航                     |
| miuix-blur            | 0.9.0         | 模糊/着色器效果                      |
| androidx.navigation3  | 1.1.0         | 类型安全路由                         |
| Room                  | 3.0.0-alpha03 | 跨平台数据库（KMP）                  |
| Ktor                  | 3.4.2         | HTTP/WebSocket 客户端                |
| kotlinx-serialization | 1.11.0        | JSON 序列化                          |
| kotlinx-coroutines    | 1.10.2        | 异步/并发                            |
| androidx.lifecycle    | 2.10.0        | ViewModel                            |
| quickie               | 1.11.0        | QR Code 扫描                         |
| hiddenapibypass       | 6.1           | 隐藏 API 访问（预测性返回）          |
| mihomo                | v1.19.23 fork | 代理核心（含 --override-json patch） |

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
│   │   │   ├── database/             Room 3.0 KMP（AppDatabase + 3 Entity + 3 DAO + ProfileTypeConverter）
│   │   │   ├── model/                @Serializable 数据模型 + ProfileType enum + ConfigurationOverride
│   │   │   └── repository/           MihomoRepository + SubscriptionRepository + SubscriptionFetcher + ProfileProcessor + OverrideJsonStore
│   │   ├── platform/                 expect 声明（含 Toast）+ ProfileFileManager 接口 + ProxyServiceBridge
│   │   ├── ui/
│   │   │   ├── navigation/           AppNavigation（主导航树 + HorizontalPager）
│   │   │   ├── navigation3/          Route（17 路由）+ Navigator（自定义栈）
│   │   │   ├── component/            SearchBar + SearchStatus + MenuPositionProvider + TriStatePreference + NullablePortPreference + ListEditDialog + RestartRequiredHint
│   │   │   │   └── effect/           BgEffectBackground（OS3 动态渐变着色器背景）
│   │   │   └── screen/               19 个页面（home/ proxy/ subscription/ settings/ log/ provider/ dns/ connection/）
│   │   ├── viewmodel/                9 个 ViewModel
│   │   └── util/                     FormatUtils + ThrowableExt
│   ├── commonMain/composeResources/
│   │   ├── values/strings.xml        英文默认字符串（244 key）
│   │   └── values-zh-rCN/strings.xml 中文字符串
│   ├── androidMain/                  actual 实现 + AppDatabaseBuilder
│   └── desktopMain/                  actual 桩实现 + AppDatabaseBuilder
├── android/src/main/
│   ├── kotlin/.../mishka/
│   │   ├── MainActivity.kt           应用入口
│   │   ├── MishkaApplication.kt      全局初始化（通知渠道 + GeoIP 提取 + 预测性返回手势）
│   │   └── service/                  16 个服务组件（含 ROOT 模式 + RuntimeOverrideBuilder）
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
- **数据持久化**：Room 3.0 KMP（结构化数据）+ PlatformStorage（简单偏好设置）+ StorageKeys（通用 key 常量）+ OverrideJsonStore（`override.user.json` 单文件 + ConfigurationOverride @Serializable）
- **订阅管理**：Pending → Processing → Imported 三阶段，由 `ProfileProcessor` 编排：snapshot 锁内 prepareProcessing → 锁外 fetch+validate（mihomo -t 子进程，自带 provider 下载）→ 锁内 commitProcessingToImported；processing/ 是单例沙箱，processLock 串行
- **订阅 HTTP**：Ktor + `HttpTimeout`（connect 30s / request 60s），User-Agent `ClashMetaForAndroid/{version}`（CFMA 兼容订阅服务白名单）；状态码与空 body 检查 → 抛 `ImportError`；不在客户端做 base64/V2Ray 转换，原始 YAML 直接交 mihomo（V2Ray 订阅需用 wrapper config + proxy-providers 引用）
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

### 类型安全与时间语义

- **ProfileType enum**（`File`/`Url`/`External`，对齐 CFMA `Profile.Type`）通过 `ProfileTypeConverter` 透明映射为 TEXT 列
- **订阅 UUID 完整 36 字符**（`Uuid.random().toString()`，UUID v4 碰撞概率 ~2^-122，不做循环冲突检测）
- **updatedAt 动态计算**：`ImportedEntity` 无 updatedAt 字段，`SubscriptionRepository.resolveProfile` 走 `ProfileFileManager.getDirectoryLastModified(uuid, pending)` 读 pending→imported 目录 mtime，fallback `imported.createdAt`（对齐 CFMA `ProfileManager.resolveUpdatedAt`）；订阅 commit/update 自然更新文件系统 mtime，无需主动写 DB

### 三阶段流程（ProfileProcessor）

```
CREATE → Pending ✓, Imported ∅
  → APPLY（processLock 串行，4 阶段）：
      ① snapshot（profileLock 内）：query Pending + enforceFieldValid + prepareProcessing（清 processing/ + 复制 pending/{uuid}/ → processing/）
      ② fetch（锁外，仅 Url）：HTTP 下载 → writeProcessingConfig → subscription-userinfo 头解析
      ③ validate（锁外）：ensureGeodataAvailable → `mihomo -t -f processing/config.yaml`（不传 --override-json；订阅原文直接校验，providers 由 mihomo 自身按需下载）
      ④ commit（profileLock 内）：snapshot 一致性检查 → commitProcessingToImported（清 imported/{uuid}/ + 复制 processing/ → imported/{uuid}/ + 删 pending/{uuid}/）→ DB 更新
  → 失败：cleanupProcessing；pending/{uuid}/ 与 imported/{uuid}/ 都不动，可 retry
  → RELEASE（放弃）：删 Pending DB + 删 pending/{uuid}/

PATCH（编辑已导入）→ Imported ✓, Pending ✓
  → APPLY → Imported ✓（更新）, Pending ∅

UPDATE（手动/自动更新）→ 等价 APPLY，但 snapshot 取自 Imported（不经过 Pending DB），processing 基准为 imported/{uuid}/config.yaml
DELETE → Imported + Pending + Selection 三表清理 + imported/{uuid}/ + pending/{uuid}/ 删除
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

`Route.kt` 中定义 17 个路由，均实现 `NavKey`：

| 路由               | 类型        | 页面                          | 入口               |
| ------------------ | ----------- | ----------------------------- | ------------------ |
| Main               | data object | 主页（HorizontalPager 4 Tab） | 根路由             |
| Subscription       | data object | SubscriptionScreen            | 主页 Tab 2 导航    |
| SubscriptionAdd    | data object | SubscriptionAddScreen         | 订阅页             |
| SubscriptionAddUrl | data class  | SubscriptionAddUrlScreen      | 添加订阅页         |
| SubscriptionEdit   | data class  | SubscriptionEditScreen        | 订阅项编辑按钮     |
| Log                | data object | LogScreen                     | QuickEntries       |
| Provider           | data object | ProviderScreen                | QuickEntries       |
| DnsQuery           | data object | DnsQueryScreen                | QuickEntries       |
| Connection         | data object | ConnectionScreen              | QuickEntries       |
| VpnSettings        | data object | VpnSettingsScreen             | 设置页             |
| NetworkSettings    | data object | NetworkSettingsScreen         | 设置页             |
| MetaSettings       | data object | MetaSettingsScreen            | 设置页             |
| ExternalControl    | data object | ExternalControlScreen         | 设置页             |
| AppProxy           | data object | AppProxyScreen                | 设置页             |
| FileManager        | data object | FileManagerScreen             | 设置页             |
| FileManagerEditor  | data class  | FileManagerEditorScreen       | FileManager 点击项 |
| About              | data object | AboutScreen                   | 设置页             |

## 页面与 ViewModel

| Screen                       | ViewModel             | 说明                                                            |
| ---------------------------- | --------------------- | --------------------------------------------------------------- |
| HomeScreen（6 个子 Section） | HomeViewModel         | 状态/ActionButtons/NetworkInfo/QuickEntries/Latency/BottomCards |
| ProxyScreen                  | ProxyViewModel        | 代理组 Tab + 节点选择 + 延迟测试 + 选择记忆                     |
| SubscriptionScreen           | SubscriptionViewModel | 订阅列表 + 增删改 + 全部更新 + 编辑 + 复制                      |
| SubscriptionEditScreen       | SubscriptionViewModel | 编辑名称/URL/更新间隔                                           |
| SettingsScreen               | —                     | 设置入口页（TUN 模式/主题/开机自启）                            |
| LogScreen                    | LogViewModel          | 实时日志流 + 级别过滤                                           |
| ConnectionScreen             | ConnectionViewModel   | 活跃连接列表 + 关闭                                             |
| ProviderScreen               | ProviderViewModel     | Provider 列表 + 刷新                                            |
| DnsQueryScreen               | DnsQueryViewModel     | DNS 查询（A/AAAA/CNAME/MX/TXT/NS）                              |
| AppProxyScreen               | AppProxyViewModel     | 应用代理白/黑名单（返回时 applyIfChanged 有变更则 Toast）       |
| VpnSettingsScreen            | —                     | VPN 设置（系统代理/排除路由等）                                 |
| NetworkSettingsScreen        | OverrideSettingsVM    | 端口/局域网/IPv6/DNS（external-controller 已移到独立页）        |
| MetaSettingsScreen           | OverrideSettingsVM    | 统一延迟/Geodata/TCP 并发/嗅探器                                |
| ExternalControlScreen        | OverrideSettingsVM    | mihomo HTTP API external-controller + API secret 独立配置页     |
| FileManagerScreen            | SubscriptionViewModel | imported 订阅目录浏览（复用 SubscriptionViewModel.fileManager） |
| FileManagerEditorScreen      | SubscriptionViewModel | 多行 TextField 编辑 YAML，保存前 mihomo -t 校验，失败回滚       |
| AboutScreen                  | —                     | 版本信息（OS3 动态背景 + 视差滚动）                             |
| SubscriptionAddScreen        | —                     | 添加方式选择（文件/URL/QR Code）                                |
| SubscriptionAddUrlScreen     | SubscriptionViewModel | URL 导入订阅                                                    |

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

| 组件                       | 用途                                                                                  |
| -------------------------- | ------------------------------------------------------------------------------------- |
| MishkaTunService           | VpnService + JNI fork+exec 启动 mihomo                                                |
| MishkaRootService          | ROOT TUN 模式前台服务（su 启动 mihomo，进程重连）                                     |
| RootHelper                 | root 检测/启动/终止/存活检查/残留清理                                                 |
| DynamicNotificationManager | 动态通知（WebSocket 流量），两个 Service 共用                                         |
| MishkaTileService          | Quick Settings Tile 一键启停代理（双模式路由）                                        |
| BootReceiver               | 开机自启（默认 disabled，动态启用）                                                   |
| ConfigGenerator            | mihomo 工作目录/secret 生成工具（getWorkDir/getConfigFile/generateSecret）            |
| RuntimeOverrideBuilder     | 运行时 override.run.json 装配（ConfigurationOverride + TUN fd + rootMode + AppProxy） |
| ProfileFileOps             | 订阅文件操作（imported/pending/processing 目录管理 + GeoIP 共享）                     |
| AndroidProfileFileManager  | ProfileFileManager 接口的 Android 实现                                                |
| MihomoRunner               | mihomo 进程管理（VPN: JNI fork+exec / ROOT: su）                                      |
| MihomoValidator            | mihomo -t 配置校验（ProcessBuilder，超时 90s）                                        |
| ProcessHelper              | JNI 包装（nativeForkExec/nativeKill/nativeWaitpid）                                   |
| NotificationHelper         | 三层通知渠道（VPN/更新进度/更新结果）                                                 |
| ProfileReceiver            | AlarmManager 调度自动更新                                                             |
| ProfileWorker              | 前台服务执行后台配置更新                                                              |

## 数据模型

`data/model/` 下 12 个 `@Serializable` 数据类：

ConnectionInfo, DelayResult, DnsQuery, LogMessage, MemoryData, MihomoConfig, ProviderInfo, ProxyGroup, ProxyNode, RuleInfo, Subscription, TrafficData

## 构建命令

```bash
# 编译 mihomo 二进制（必须用 Mishka fork 源码：含 --override-json flag、RawTun.AutoDetectInterface json tag 修复、patch_mishka.go DNS fallback）
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
- 订阅导入完全对齐 CFMA：User-Agent `ClashMetaForAndroid/{version}`（订阅服务白名单匹配）；不在客户端做 base64/V2Ray 转 YAML（CFMA 也没有），用户用 V2Ray 订阅必须自己写 wrapper config 用 `proxy-providers` 引用 —— mihomo `adapter/provider/provider.go:372` `convert.ConvertsV2Ray` 在 provider 加载阶段自动转
- GeoIP 文件构建时通过 DownloadGeoFilesTask 下载到 assets/，启动时 MishkaApplication.extractGeoFiles() 提取到 geodata/ 共享目录，各订阅通过符号链接（失败则复制）引用
- Override 注入完全对齐 CFMA 内存方案（但走子进程）：mihomo fork 加 `--override-json <path>` CLI flag + `config.OverrideJSONPath` 全局变量 + `Parse()` 内 `json.Unmarshal` 钩子（见 D:/GitHub/mihomo/config/config.go Parse 和 main.go）。用户设置通过 `ConfigurationOverride` @Serializable 写到 `files/mihomo/override.user.json`；启动时 `RuntimeOverrideBuilder.buildAndWriteForRun` 叠加 TUN fd / AppProxy / rootMode 字段输出到 `files/mihomo/override.run.json`，mihomo 启动时 `--override-json` 读取。secret / external-controller **不进 override.json**，走 mihomo 已有 `--secret` / `--ext-ctl` CLI flag。**Kotlin 侧零 YAML 库依赖**，订阅 YAML 全程不被 Mishka 改写
- `RawTun.AutoDetectInterface` 在 mihomo 上游漏了 json tag（yaml 有 json 无），fork 已补 `json:"auto-detect-interface"`；否则 ROOT 模式的 `auto-detect-interface: true` 注入会被 Go `encoding/json` 静默丢弃
- mihomo fork `config/patch_mishka.go`（`//go:build mishka`）对齐 CFMA `patchDns`：订阅 `DNS.Enable=false` 时注入 fake-ip 模式 + 4 组国内外 nameserver（223.5.5.5/119.29.29.29/8.8.4.4/1.0.0.1）+ STUN/Xbox/bilibili 等 fake-ip-filter + `FakeIPRange=28.0.0.0/8`；`ClashForAndroid.AppendSystemDNS=true` 时追加 `system://` nameserver 作为 Android 系统 DNS fallback。Parse 顺序：Unmarshal → override decode → mishkaPatch → ParseRawConfig；非 mishka tag 编译时 `mishkaPatch` 保持 nil，零影响
- secret 解析优先级（`MishkaTunService` / `MishkaRootService` startProxy）：用户在 ExternalControlScreen 显式设置 > 订阅 `config.yaml` 顶层 `secret:` 字段（`ConfigGenerator.readSubscriptionSecret` 轻量行扫描，不 parse 完整 YAML）> `ConfigGenerator.generateSecret()` 随机 UUID 前 16 字节。externalController 通过 `ConfigurationOverride.resolveExternalController()` 扩展函数统一解析（用户值 > `127.0.0.1:9090`，`0.0.0.0` 自动替换为 `127.0.0.1`）。ROOT attach 分支仍用 storage 持久化的 `existingSecret`，不走用户当前值（防止运行中修改 override 导致 attach secret 漂移）
- TUN 段组装差异（VPN vs ROOT，由 `RuntimeOverrideBuilder.buildTunOverride` 决定）：VPN 注入 `file-descriptor` + `auto-route=false` + `auto-detect-interface=false` + `inet6-address=[]`（VPN 由 VpnService 处理 v6）；ROOT 注入 `auto-route=true` + `auto-detect-interface=true` + `device` 从 `StorageKeys.ROOT_TUN_DEVICE` 读 + `include/exclude-package`（VPN 模式**不**输出 include/exclude，mihomo TUN fd 路径不读这两字段，分应用代理由 VpnService.Builder.addAllowed/DisallowedApplication 管）。`tun.stack` 按用户值透传（null 不输出 → mihomo 用订阅值或默认 gvisor），`tun.enable` / `dns-hijack=[0.0.0.0:53]` / `profile.store-selected=false`、`store-fake-ip=true` 为硬编码覆盖
- 导入订阅不自动切换活跃：`SubscriptionViewModel.addSubscription` / `addFromFile` 成功后**不**调 `repository.setActive(sub.id)`；仅首次导入（`importedDao.count() == 1`）由 `SubscriptionRepository.commitProcessingToImported` 自动激活为默认值。用户需要手动在订阅列表里切换活跃配置
- 订阅管理采用 Pending → Processing → Imported 三阶段（对齐 CFMA `ProfileProcessor.apply`）：fetch+校验在 processing 单例沙箱完成，成功后 swap 进 imported/{uuid}/；失败 imported/ 完全不动，pending/ 保留可 retry。`ProfileProcessor` 用 `processLock` 串行，`SubscriptionRepository.withProfileLock` 守护 DB snapshot 一致性
- 错误信息防御：所有用户面向异常都走 `Throwable.describe()` 兜底（`message ?: simpleName ?: "Unknown error"`），避免 Ktor `ConnectException()` 等无参异常 `e.message == null` 漏到 UI。`SubscriptionFetcher` 显式检查 `response.status.isSuccess` + 空 body，抛 typed `ImportError`
- Room 3.0 KMP 跨平台数据库，BundledSQLiteDriver 统一 Android/Desktop，DCL 单例
- 代理组选择通过 SelectionEntity 持久化，切换订阅时自动恢复 Selector 类型组的选择
- 后台自动更新通过 AlarmManager + ProfileReceiver + ProfileWorker 前台服务实现，最小间隔 15 分钟。`ProfileWorker.jobs` 使用 `java.util.concurrent.ConcurrentLinkedQueue<Job>`（非 `mutableListOf`），`onStartCommand` 用 `offer` 提交、消费协程用 `while (true) { jobs.poll()?.join() ?: break }`——对齐 CFMA 挂起消费模式（非 `while(isActive) delay(1s)` 轮询），同时 `onStartCommand`（主线程）与 scope 协程（Dispatchers.IO）跨线程访问需要线程安全容器（ArrayList 会竞态）
- network_security_config.xml 允许 localhost 明文通信（mihomo API 用 HTTP）
- Activity 声明 `configChanges="uiMode"` 避免系统深浅色切换时重建，防止导航栈丢失
- 预测性返回手势通过 HiddenApiBypass 反射调用 `ApplicationInfo.setEnableOnBackInvokedCallback`，Android 14+ 可选启用
- ROOT 模式重连校验：`attachToExisting` 做三重验证（`kill -0` 存活 + `/proc/$pid/cmdline` 含 libmihomo.so + stored secret 通过 `/configs` 带 Bearer 鉴权 2xx），防 PID 复用与 secret 漂移；订阅一致性由 `startProxy` 在 attach 之前比对 persisted vs 请求的 subscriptionId，不一致直接走 cleanup + 全新启动
- 孤儿 mihomo 清理：`RootHelper.cleanupOrphanedMihomo(tunDevice)` 用**单次 su shell** 完成 pkill + 兜底 `ip link delete <tunDevice>`，Kotlin 侧零 `Thread.sleep`（孤儿非当前 App 子进程，waitpid 不适用，只能 shell 内轮询）。TUN 清理必须同步：sing-tun `tun.New()` 遇已存在设备返回 EEXIST，叠加 TUN init silent failure 会级联。device name 来自用户 storage，`escapeShellSingleQuoted` 单引号 + `'\''` POSIX 转义防命令注入
- VPN 启动清理触发：`MishkaTunService.startProxy` 在 `hadRootPid || HAS_ROOT` 时调用 cleanupOrphanedMihomo + 清 ROOT 持久化 key（PID/SECRET/ACTIVE_SUBSCRIPTION_ID）。`HAS_ROOT` 分支覆盖两个漏清场景：ROOT 崩溃后 storage 清了但进程仍活；VPN mihomo `setsid()` 脱离 App 进程组、App 崩溃后被 init 收养继续占端口
- WebSocket 重连：`MihomoWebSocket.webSocketFlow` 传输层加无限重连循环 + 指数退避（1s→30s 封顶）+ `pingIntervalMillis = 20_000` 心跳。Ktor graceful close 时 `for (frame in incoming)` 静默退出不抛异常，消费者 `.catch` 无法感知，无内置重连只能手搓。`CancellationException` 必须显式 rethrow 否则 cancel 被吞导致死循环。连接状态通过 `connectionState: StateFlow<Boolean>` 粗粒度暴露（mihomo API server 单点，4 flow 共享）
- startForeground 防御：`MishkaTunService` / `MishkaRootService` / `ProfileWorker` 的 onCreate 内 `startForeground()` 必须 `try { ... } catch (e: Exception) { ... }`。真实风险是 API 31+ `ForegroundServiceStartNotAllowedException`（BootReceiver/ProfileReceiver 后台触发）与 API 34+ FGS type 异常，**不是** POST_NOTIFICATIONS 拒绝（仅隐藏通知，FGS 照常运行）。catch 用通用 `Exception` 兜底（各 OEM ROM 抛出类不同）。失败路径：Tun/Root 上报 `ProxyServiceBridge.Error` + `stopSelf()`（让 UI 退出 Starting）；ProfileWorker 仅 `stopSelf()`。**不可降级为普通 Service**（Android 12+ 迅速回收）
- TUN init silent failure 兜底：mihomo `listener.ReCreateTun` TUN inbound 初始化失败走 `log.Errorln + tunConf.Enable=false` 正常返回，**进程不退出**（其他 inbound 如 mixed-port 继续响应 `/version`）。仅靠进程存活 + API 可达无法检测。检测规则：① `MishkaTunService` 清 O_CLOEXEC (`fcntlInt(F_SETFD)`) 失败必须视为致命（`closeTunFd` + `ProxyState.Error` + `stopSelf`）——fd 未清则 exec 后必然被关；② `MihomoRunner.waitForReady` API ready 后 delay 500ms 再 `scanLogForTunError`，匹配 pattern：`Start TUN listening error` / `configure tun interface` / `create NetworkUpdateMonitor`
- 配置校验直接跑订阅原文：`mihomo -t -f processing/config.yaml`（不传 `--override-json`）。mihomo `-t` 只做 parse + provider 校验，不 bind 端口也不 init TUN，订阅内 `tun.enable: true` 同样能通过。用户 override 字段由 UI 做边界校验（端口范围、DNS URL 语法）并显示 `RestartRequiredHint` 提示用户重启代理；运行期再合并为 override.run.json 由 mihomo json.Decode。回退预案：若真机 gVisor 在 `-t` 路径触发初始化，可在校验前写极简 `{"tun":{"enable":false}}` override 临时文件
- CMFA embed mode 禁用全部 HTTP 配置 API：mihomo 上游 `patch_android.go`（`//go:build android && cmfa`）init `SetEmbedMode(true)`，`PATCH/PUT /configs` / `POST /restart` / `POST /configs/geo` / `PUT/PATCH /rules` / `POST /upgrade` 全部 404（configs.go:28 / server.go:135 / rules.go:17 / upgrade.go:18）。Mishka fork 未改该文件。Ktor `HttpClient` 默认不对 404 抛异常，`runCatching` 包装会返回"幻觉成功"（UI 以为切换，mihomo 未动）。约束：**不要**添加 `apiClient.patchConfig` / `reloadConfig` / `restart` 方法。所有 mihomo 配置修改（mode/tun.stack/DNS/port/...）走 `OverrideJsonStore.save(ConfigurationOverride)` + `serviceController.restart(subscriptionId)`（Service Intent 重启，非 HTTP）+ `RuntimeOverrideBuilder` 叠加 runtime 字段生成 override.run.json；UI 用 `RestartRequiredHint` Card 统一告知"需重启生效"

## UI 规范

- 所有 UI 组件使用 miuix（Card、TopAppBar、NavigationBar、SmallTitle、TextButton 等）
- 返回按钮使用 MiuixIcons.Back
- 首页状态卡片参考 KernelSU（CheckCircleOutline 170dp，offset(38,45)）
- 深色模式：StatusCard 背景 #1A3825，按钮使用 isDark 条件色
- 底栏图标：Sidebar / Tune / UploadCloud / Settings
- Badge：`clip(miuixShape(3.dp))` + 9.sp Bold Monospace
- 操作 IconButton：`minHeight/minWidth = 35.dp, backgroundColor = secondaryContainer`
- **页面骨架**：Scaffold + TopAppBar(scrollBehavior) + LazyColumn
  - LazyColumn 必须加 `.scrollEndHaptic().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)`
  - `contentPadding = PaddingValues(top = innerPadding.calculateTopPadding())` —— 仅设 top，不设 bottom
  - 首个 item（非 RestartRequiredHint 场景）用 `item { Spacer(Modifier.height(12.dp)) }` 顶部呼吸
  - 末尾 item 统一 `item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }` 吸收导航栏 + 24dp 留白
  - Screen 签名**不要 `bottomPadding: Dp` 参数**（旧惯例已弃用，参 AppProxy/FileManager/ExternalControl）
- **Card 间距**：水平 12.dp，每项统一 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`；不使用 `Arrangement.spacedBy`（间距由每项 padding 承担）
- **TextField 表单**：不包 Card，直接 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`（参 SubscriptionEditScreen / FileManagerEditorScreen）
- **Edit Dialog 按钮顺序**：`not_modified | cancel | confirm`（三按钮等宽 weight(1f) + `spacedBy(8.dp)`）；confirm 用 `ButtonDefaults.textButtonColorsPrimary()`，reset（`not_modified`）回调中调用 `showToast(dialog_reset_done)`
- **用户反馈**：`platform.showToast(message, long = false)` —— 轻量操作结果提示（重置/应用成功等），Android 走系统 Toast 主线程派发，Desktop 空实现
- **i18n**：所有面向用户的字符串必须使用 `stringResource(Res.string.xxx)`（Compose）或 `getString(R.string.xxx)`（Android Service），禁止硬编码
  - 新增字符串需同时添加到 `values/strings.xml`（英文）和 `values-zh-rCN/strings.xml`（中文）
  - key 命名：`{页面}_{描述}`，通用按钮用 `common_` 前缀
  - 日志消息用英文，代码注释保留中文
