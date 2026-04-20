# Mishka

Compose Multiplatform + miuix + mihomo 跨平台代理客户端，首先完整支持 Android。

## 技术栈

| 组件                  | 版本          | 用途                                              |
| --------------------- | ------------- | ------------------------------------------------- |
| Kotlin                | 2.3.20        | 语言                                              |
| AGP                   | 9.1.1         | Android 构建                                      |
| KSP                   | 2.3.6         | 注解处理（Room）                                  |
| Compose Multiplatform | 1.10.3        | 跨平台 UI 框架                                    |
| miuix                 | 0.9.0         | UI 组件库 + 导航                                  |
| miuix-blur            | 0.9.0         | 模糊/着色器效果                                   |
| androidx.navigation3  | 1.1.0         | 类型安全路由                                      |
| Room                  | 3.0.0-alpha03 | 跨平台数据库（KMP）                               |
| Ktor                  | 3.4.2         | HTTP/WebSocket 客户端                             |
| kotlinx-coroutines    | 1.10.2        | 异步/并发                                         |
| kotlinx-datetime      | 0.7.1         | 日期时间处理                                      |
| kotlinx-serialization | 1.11.0        | JSON 序列化                                       |
| androidx.lifecycle    | 2.10.0        | ViewModel                                         |
| quickie               | 1.11.0        | QR Code 扫描                                      |
| hiddenapibypass       | 6.1           | 隐藏 API 访问（预测性返回）                       |
| mihomo                | v1.19.23 fork | 代理核心（含 --override-json + --prefetch patch） |

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
│   │   │   └── repository/           MihomoRepository + SubscriptionRepository + SubscriptionFetcher + ProfileProcessor + OverrideJsonStore + SubscriptionProxyResolver
│   │   ├── platform/                 expect 声明（含 Toast）+ ProfileFileManager 接口 + ProxyServiceBridge
│   │   ├── ui/
│   │   │   ├── navigation/           AppNavigation（主导航树 + HorizontalPager）
│   │   │   ├── navigation3/          Route + Navigator（自定义栈）
│   │   │   ├── component/            SearchBar + SearchStatus + MenuPositionProvider + TriStatePreference + NullablePortPreference + ListEditDialog + RestartRequiredHint
│   │   │   │   └── effect/           BgEffectBackground（OS3 动态渐变着色器背景）
│   │   │   └── screen/               页面（home/ proxy/ subscription/ settings/ log/ provider/ dns/ connection/）
│   │   ├── viewmodel/                ViewModel
│   │   └── util/                     FormatUtils + ThrowableExt
│   ├── commonMain/composeResources/
│   │   ├── values/strings.xml        英文默认字符串
│   │   └── values-zh-rCN/strings.xml 中文字符串
│   ├── androidMain/                  actual 实现 + AppDatabaseBuilder
│   └── desktopMain/                  actual 桩实现 + AppDatabaseBuilder
├── android/src/main/
│   ├── kotlin/.../mishka/
│   │   ├── MainActivity.kt           应用入口
│   │   ├── MishkaApplication.kt      全局初始化（通知渠道 + GeoIP 提取 + 预测性返回手势 + 旧 root 文件 chown 迁移）
│   │   └── service/                  服务组件（含 ROOT 模式 + RuntimeOverrideBuilder + MihomoPrefetcher）
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

- **通信方案**：mihomo RESTful API + WebSocket（非 JNI），代码在 commonMain 跨平台共享
- **导航**：miuix NavDisplay + 自定义 Navigator（push/pop/popUntil + navigateForResult）+ LocalNavigator
- **主页 Tab**：HorizontalPager + MainPagerState + NavigationBar（4 Tab）
- **隧道三模式**：VPN / ROOT TUN / ROOT TPROXY（`TunMode { Vpn, RootTun, RootTproxy }`；旧 storage 值 `"root"` 自动迁移为 `"root_tun"`）
  - **VPN**：VpnService 创建 TUN fd，mihomo 写 `tun.file-descriptor` + `auto-route=false`，工作目录 `imported/{uuid}/`（app UID）
  - **ROOT TUN**：mihomo 以 root 自建 TUN，`auto-route=true` + `auto-detect-interface=true`，工作目录独立 `runtime/{uuid}/` 沙箱（启动前从 imported/ 拷贝，停止时 `su rm -rf`）；imported/ 永远 app UID
  - **ROOT TPROXY**：**`tun.enable=false`**，mihomo 用 `tproxy-port=7895` 入站 + `dns.listen=0.0.0.0:1053`；`RootTproxyApplier` 装 `mangle PREROUTING/OUTPUT` + `nat PREROUTING/OUTPUT` + `ip rule fwmark 0x1000000 lookup 2024` + `ip route add local default dev lo table 2024`，透明劫持本机与热点流量；分应用代理改走 iptables `-m owner --uid-owner`
  - **分应用代理**：VPN 走 VpnService API；ROOT TUN 走 mihomo `include/exclude-package`（sing-tun 翻译为 uidrange）；ROOT TPROXY 走 iptables `uid-owner`（`AppListProvider.resolveUids` 把包名解析为 UID）；**Mishka 自身始终排除**（VPN: disallowed；ROOT TUN: include 过滤 + exclude 叠加；ROOT TPROXY: `-m owner --uid-owner 0 RETURN` 放行 root 含 mihomo + `-m owner --uid-owner $APP_UID RETURN` 兜底；**不**用 `routing-mark`/SO_MARK 自绕——Android Netd 用 fwmark 低 16 位编码 netId，任何自定义 SO_MARK 都会让路由命中 legacy_system 表无默认路由，导致 mihomo 出站 `network unreachable`，box_for_magisk / Surfing / box4magisk 三家同款教训）
  - **ROOT 两子模式共享** MishkaRootService，Intent 通过 `EXTRA_SUBMODE = "tun"/"tproxy"` 区分；attach 路径比对 `ROOT_SUBMODE_ACTIVE` 与请求 submode，不一致 fresh restart
  - ROOT 进程 app 被杀后仍存活，重启 app 通过持久化 PID/secret 重连
  - ROOT 不可用自动回退 VPN（MainActivity 探测失败后回写 `TUN_MODE=vpn`）
- **状态桥接**：ProxyServiceBridge（全局 StateFlow + TunMode），Service 写入、ViewModel 读取
- **进程模型**：单进程（VpnService 和 UI 同进程），ROOT 模式 mihomo 为独立 root 进程
- **数据持久化**：Room 3.0 KMP（结构化数据）+ PlatformStorage（简单偏好）+ StorageKeys（key 常量）+ OverrideJsonStore（`override.user.json` + ConfigurationOverride @Serializable）
- **订阅管理**：Pending → Processing → Imported 三阶段沙箱，`ProfileProcessor` 编排 snapshot → fetch → validate → prefetch → commit 五阶段；processLock 串行，profileLock 守护 DB 一致性
- **订阅 HTTP**：Ktor + HttpTimeout（connect 30s / request 60s），UA `ClashMetaForAndroid/{version}`（订阅服务白名单）；状态码 / 空 body 检查 → `ImportError`；不做 base64/V2Ray 转换，原始 YAML 直接交 mihomo
- **订阅下载走代理**：`SubscriptionProxyResolver` 按「开关 + 代理运行中 + 可解析 mixed-port」返回 proxy URL 或 null；`SubscriptionFetcher` 按需配 proxy，网络层异常自动 fallback 直连；`MihomoPrefetcher` 子进程注入 `HTTPS_PROXY`/`HTTP_PROXY` env
- **Pipeline 可取消**：子进程 200ms 轮询 + `ensureActive()` 响应取消，finally `destroyForcibly`；`ImportProgressDialog` 可选 `onCancel`；`cancelCurrentUpdate` 先同步 `clearProgress()` 让 UI 立即响应，再 cancel 协程
- **GeoIP 预制**：构建时 DownloadGeoFilesTask 下载 geoip.metadb/geosite.dat/ASN.mmdb 到 assets，启动时提取到 geodata/ 共享目录 + 符号链接
- **配置校验**：`mihomo -t -f processing/config.yaml`（不传 `--override-json`，只 parse 不碰网，超时 90s）
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

### 三阶段流程（ProfileProcessor）

```
CREATE → Pending ✓, Imported ∅
  → APPLY（processLock 串行，5 阶段）：
      ① snapshot（profileLock 内）：query Pending + enforceFieldValid + prepareProcessing（清 processing/ + 复制 pending/{uuid}/ → processing/）
      ② fetch（锁外，可取消，仅 Url）：HTTP 下载 → writeProcessingConfig → subscription-userinfo 头解析；按需配 proxy + 网络层异常 fallback 直连
      ③ validate（锁外，可取消）：ensureGeodataAvailable → `mihomo -t -f processing/config.yaml`（只 parse 不碰网）
      ④ prefetch（锁外，可取消，best-effort）：`mihomo -prefetch` 并发下载 HTTP provider 到 `processing/` 相对路径下；失败仅记日志不阻塞 commit
      ⑤ commit（profileLock 内，`withContext(NonCancellable)` 原子）：snapshot 一致性检查 → commitProcessingToImported（清 imported/{uuid}/ + 复制 processing/ → imported/{uuid}/ + 删 pending/{uuid}/）→ DB 更新
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
| NetworkSettingsScreen    | OverrideSettingsVM    | 端口/局域网/IPv6/DNS                                            |
| MetaSettingsScreen       | OverrideSettingsVM    | 统一延迟/Geodata/TCP 并发/嗅探器                                |
| ExternalControlScreen    | OverrideSettingsVM    | mihomo HTTP API external-controller + API secret                |
| FileManagerScreen        | SubscriptionViewModel | imported 订阅目录浏览                                           |
| FileManagerEditorScreen  | SubscriptionViewModel | 多行 TextField 编辑 YAML，保存前 mihomo -t 校验，失败回滚       |
| AboutScreen              | —                     | 版本信息（OS3 动态背景 + 视差滚动）                             |
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
| DynamicNotificationManager | 动态通知（WebSocket 流量），两个 Service 共用                                                                                                                |
| MishkaTileService          | Quick Settings Tile 一键启停代理（双模式路由）                                                                                                               |
| BootReceiver               | 开机自启（默认 disabled，动态启用）                                                                                                                          |
| ConfigGenerator            | mihomo 工作目录/secret 生成工具（getWorkDir/getConfigFile/generateSecret）                                                                                   |
| RuntimeOverrideBuilder     | 运行时 override.run.json 装配（按 Submode = Vpn/RootTun/RootTproxy 分支：TUN fd / auto-route+include/exclude-package / tproxy-port+routing-mark+dns.listen） |
| ProfileFileOps             | 订阅目录管理（imported/pending/processing/runtime + GeoIP + ROOT 沙箱）                                                                                      |
| AndroidProfileFileManager  | ProfileFileManager 接口的 Android 实现                                                                                                                       |
| MihomoRunner               | mihomo 进程管理（VPN: JNI fork+exec / ROOT: su）                                                                                                             |
| MihomoValidator            | mihomo -t 配置校验（ProcessBuilder，超时 90s，200ms 轮询响应协程取消）                                                                                       |
| MihomoPrefetcher           | mihomo -prefetch provider 预下载（HTTPS_PROXY env，代理 60s/直连 120s，可取消）                                                                              |
| ProcessHelper              | JNI 包装（nativeForkExec/nativeKill/nativeWaitpid）                                                                                                          |
| NotificationHelper         | 三层通知渠道（VPN/更新进度/更新结果）                                                                                                                        |
| ProfileReceiver            | AlarmManager 调度自动更新                                                                                                                                    |
| ProfileWorker              | 前台服务执行后台配置更新                                                                                                                                     |

## 数据模型

`data/model/` 下的 `@Serializable` 数据类：

ConnectionInfo, DelayResult, DnsQuery, LogMessage, MemoryData, MihomoConfig, ProviderInfo, ProxyGroup, ProxyNode, RuleInfo, Subscription, TrafficData

另有 `ProfileType` enum + `ConfigurationOverride` override 数据类。

## 构建命令

```bash
# 编译 mihomo 二进制（必须用 Mishka fork 源码：含 --override-json / --prefetch flag、RawTun.AutoDetectInterface json tag、patch_mishka.go DNS fallback）
cd D:/GitHub/mihomo
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build \
  -tags "cmfa,mishka,with_gvisor" -trimpath \
  -ldflags "-s -w -X 'github.com/metacubex/mihomo/constant.Version=v1.19.23'" \
  -o /path/to/Mishka/android/src/main/jniLibs/arm64-v8a/libmihomo.so .

# 构建 APK（assemble 自动触发 downloadGeoFiles 下载 GeoIP 到 assets/）
./gradlew :android:assembleDebug
./gradlew :android:assembleRelease
```

## 关键架构约束

不读代码看不出来的约束。违反会直接踩坑。详细 why 见 memory 文件。

**Override 注入**：所有 override 走 `--override-json` CLI flag + JSON 文件，Kotlin 侧零 YAML 改写。用户设置 `OverrideJsonStore.save()` → `override.user.json`，启动时 `RuntimeOverrideBuilder` 叠加 TUN fd / AppProxy / rootMode → `override.run.json`。`secret` / `external-controller` 走 `--secret` / `--ext-ctl` CLI flag 不进 JSON。详见 `feedback_override_architecture.md`。

**RuntimeOverrideBuilder 默认注入**（用户未显式设置时生效）：`tcp-concurrent=true`（代理并发拨号降低首包延迟）、`find-process-mode=off`（分应用已由 sing-tun `include/exclude-package` / VpnService / iptables uid-owner 处理，mihomo 运行期遍历 `/proc` 纯冗余）。ROOT TUN 分支额外默认 `tun.mtu=9000 + gso=true + gso-max-size=65535`（大包聚合减少 sing-tun read syscall），由 `StorageKeys.ROOT_TUN_JUMBO_MTU` 开关（默认 true）控制；关闭时回退 `mtu=1500 + gso=false`，兼容上游链路拒绝大包的极端 ROM。VPN 模式不注入 MTU/GSO（MTU 由 `VpnService.Builder` 系统管）。用户 `override.user.json` 里的同名字段优先级最高。

**硬编码覆盖订阅（按 submode 分）**：`profile.store-selected=false` / `profile.store-fake-ip=true` 三模式共用。

- **VPN**：`tun.enable=true` + `tun.file-descriptor=fd` + `tun.dns-hijack=[0.0.0.0:53]`，`auto-route=false`；透传 `tun.stack`、`tun.device`
- **ROOT TUN**：`tun.enable=true` + `auto-route=true` + `auto-detect-interface=true` + `iproute2-table-index=2022` + `iproute2-rule-index=9000` + `tun.dns-hijack=[0.0.0.0:53]` + `include/exclude-package`（按 AppProxyMode）
- **ROOT TPROXY**：`tun.enable=false`、`tproxy-port=7895`、`dns.listen=0.0.0.0:1053`；**不写** `routing-mark`（Android Netd 冲突，见下）、**不写** `include/exclude-package`（AppProxy 走 iptables uid-owner）；`dns.enhanced-mode` 透传用户设置

**三模式段差异**：VPN 注入 `file-descriptor` + `auto-route=false`；ROOT TUN 注入 `auto-route=true` + `auto-detect-interface=true` + `include/exclude-package`；ROOT TPROXY 仅 `tun.enable=false`，所有分应用/DNS/路由逻辑交 `RootTproxyApplier`（iptables + uid-owner 放行 root）

**secret 优先级**：用户设置 > 订阅 `config.yaml` 顶层 `secret:`（`ConfigGenerator.readSubscriptionSecret` 轻量行扫描）> 随机 UUID 前 16 字节；ROOT attach 分支走 storage 持久化的 `existingSecret`

**CMFA embed mode 禁 HTTP 配置 API**：`PATCH/PUT /configs` / `POST /restart` / `POST /configs/geo` / `PUT/PATCH /rules` / `POST /upgrade` 全部 404。**绝不添加** `patchConfig`/`restart` 方法，所有配置修改走 `OverrideJsonStore.save()` + `serviceController.restart()`，UI 用 `RestartRequiredHint` Card 提示。详见 `feedback_override_architecture.md`。

**mihomo 子进程规则**：Provider 预下载只走 `mihomo -prefetch`，stdout 必须独立 daemon 线程读（否则 `waitFor(timeout)` 失效），200ms 轮询 + `ensureActive()` 响应取消。详见 `feedback_mihomo_subprocess.md`。

**Mishka 自身包名必须绕过 TUN/VPN**：`ProcessBuilder` 子进程 HTTP 被代理捕获会永久阻塞；ROOT 三种 AppProxyMode 都把 `packageName` 从 include 剔除或塞进 exclude，VPN `AllowSelected` 分支先过滤 self 再 addAllowed，过滤后空列表退化到 `addDisallowedApplication(self)`。详见 `feedback_mihomo_subprocess.md`。

**协程锁规则**：`kotlinx.coroutines.sync.Mutex` **不可重入**。`updateImported`/`commitPending`/`queryImported`/`queryPending` 被 `ProfileProcessor` 在 `withProfileLock { ... }` 内调用，**不能自己加** `profileLock.withLock`；`create`/`patch`/`release`/`clone`/`delete` 直接被 ViewModel 调用，**保留自身** `profileLock.withLock`。详见 `feedback_mutex_not_reentrant.md`。

**Pipeline 协程取消语义**：外层 `runProcess` 可取消，仅 commit 阶段包 `withContext(NonCancellable)` 保证文件 swap + DB 更新原子；catch 块 `cleanupProcessing` 也走 NonCancellable。`cancelCurrentUpdate` 先同步 `clearProgress()` 让 Dialog 立即消失，再 `currentJob?.cancel()` 让协程后台收尾

**ROOT runtime/ 沙箱**：ROOT mihomo 工作目录是独立 `runtime/{uuid}/`（从 imported/ 复制），不碰 imported/。启停钩子：`startProxy` 新鲜启动前 `prepareRootRuntime`；stop/restart/进程监控三条死亡路径都在 `clearPersistedState` 之前 `cleanupRootRuntime`；attach 分支**不重建** runtime/。存量旧 root:root 遗孤由 `MishkaApplication` 后台线程一次性 `su chown -R $APP_UID imported/` 迁移（`StorageKeys.MIGRATION_ROOT_RECLAIM_DONE` 打标）

**订阅导入不自动切换活跃**：`addSubscription`/`addFromFile` 成功后**不**调 `setActive(sub.id)`；仅首次导入（`importedDao.count() == 1`）由 `commitProcessingToImported` 自动激活

**JNI fork+exec**：Android `ProcessBuilder` fork 后强制关闭非标准 fd（无论 O_CLOEXEC），VPN 模式必须用 JNI `fork()+exec()`（`process_helper.c`）保留 TUN fd 继承

**TUN init silent failure 兜底**：mihomo `ReCreateTun` 失败仅 log 不退出。检测规则：① `MishkaTunService` 清 O_CLOEXEC 失败必须视为致命（`closeTunFd` + `ProxyState.Error` + `stopSelf`）② `MihomoRunner.waitForReady` API ready 后 delay 500ms 扫日志匹配 `Start TUN listening error` / `configure tun interface` / `create NetworkUpdateMonitor`

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

**错误兜底**：用户面向异常走 `Throwable.describe()`（`message ?: simpleName ?: "Unknown error"`），避免 Ktor `ConnectException()` 等无参异常漏到 UI 显示 "null"；`SubscriptionFetcher` 显式检查 `response.status.isSuccess` + 空 body 抛 typed `ImportError`

**其他**：`Activity configChanges=uiMode` 防深浅色切换重建；预测性返回手势走 HiddenApiBypass 反射 `setEnableOnBackInvokedCallback`（Android 14+ 可选）；`network_security_config.xml` 允许 localhost 明文（mihomo API HTTP）；`jniLibs.useLegacyPackaging = true` 确保 libmihomo.so 解压到 nativeLibraryDir

## UI 规范

- 所有 UI 组件使用 miuix（Card、TopAppBar、NavigationBar、SmallTitle、TextButton 等）
- 返回按钮使用 MiuixIcons.Back
- 底栏图标：Sidebar / Tune / UploadCloud / Settings
- Badge：`clip(miuixShape(3.dp))` + 9.sp Bold Monospace
- 操作 IconButton：`minHeight/minWidth = 35.dp, backgroundColor = secondaryContainer`
- **页面骨架**：Scaffold + TopAppBar(scrollBehavior) + LazyColumn
  - LazyColumn 必须加 `.scrollEndHaptic().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)`
  - `contentPadding = PaddingValues(top = innerPadding.calculateTopPadding())`——仅设 top，不设 bottom
  - 首个 item（非 RestartRequiredHint）用 `item { Spacer(Modifier.height(12.dp)) }` 顶部呼吸
  - 末尾 item 统一 `item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }` 吸收导航栏 + 留白
  - Screen 签名**不要 `bottomPadding: Dp` 参数**
- **Card 间距**：水平 12.dp，每项统一 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`；不使用 `Arrangement.spacedBy`
- **TextField 表单**：不包 Card，直接 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`
- **Edit Dialog 按钮顺序**：`not_modified | cancel | confirm`（三按钮 weight(1f) + `spacedBy(8.dp)`），confirm 用 `ButtonDefaults.textButtonColorsPrimary()`
- **用户反馈**：`platform.showToast(message, long = false)`——轻量操作结果提示
- **i18n**：所有用户字符串走 `stringResource(Res.string.xxx)` 或 `getString(R.string.xxx)`，禁止硬编码
  - 新增字符串同时加到 `values/strings.xml` + `values-zh-rCN/strings.xml`
  - key 命名：`{页面}_{描述}`，通用按钮 `common_` 前缀
  - 日志消息英文，代码注释中文
