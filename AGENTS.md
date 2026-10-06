# Mishka

miuix + mihomo 的 Android 代理客户端。单模块 `:app`（`com.android.application`，AGP 9 内置 Kotlin，源码全在 `src/main`）+ `:baselineprofile`（`com.android.test`，只产 Baseline Profile 不进 APK）。UI 用 AndroidX Compose，miuix 走其 `-android` 发布件。

本文件是 agent 指南的入口。`CLAUDE.md` 只有一行 `@AGENTS.md`——Claude Code 只自动读 `CLAUDE.md`。两块只在特定改动里才需要的约束拆了出去，**按需读、不自动加载**：[docs/root-mode.md](docs/root-mode.md)（改 ROOT / iptables / `su` 相关代码前）、[docs/ui-guidelines.md](docs/ui-guidelines.md)（改 `ui/` 下任何文件前）。其余长期约束都在本文件。

## 工作规程

- 每次改动至少跑 `git diff --check` + 与变更匹配的 Gradle 任务。
- 改 Kotlin 用 `./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a`（秒级，跳过 Go cgo）；验证 native / 打包才 `:app:assembleDebug`（分钟级）；真机 `:app:installDebug`。
- 新增 composable 后临时加 `composeCompiler { reportsDestination.set(layout.buildDirectory.dir("compose_reports")) }` + `--rerun-tasks` 跑报告，确认 restartable 全部 skippable、0 unstable 参数（当前 101 个），验完删掉临时配置。
- `mihomo/` 是 submodule、`scripta/` 是 includeBuild 复合构建，改前先确认确需触及；首次 clone 后跑 `git submodule update --init --recursive`。
- 保留用户已有的未提交改动；不用破坏性 reset/checkout；不修改或输出 `local.properties`。
- 完成后先报告变更与验证结果。**除非用户在当前请求中明确授权，不执行 `git add`/`commit`/`push`**。
- Commit 用 `<scope>: <summary>`，scope 取 `home`/`proxy`/`subscription`/`settings`/`service`/`native`/`build`/`docs`/`fix`/`chore(deps)`；主题行 ≤ 72 字符、sentence case、无句尾句号；**body 简洁**，只讲代码里看不出的根因与取舍，绝不逐文件复述 diff。

## 技术栈

Kotlin（AGP 9 内置，不加独立 kotlin 插件）+ KSP。UI：Compose（经 miuix `-android` 件传递）+ miuix + navigation3 + material-icons-extended。数据：Room 3（反射 builder）+ Ktor + kotlinx-* + Koin。其他：quickie 扫码、hiddenapibypass 预测性返回。核心：mihomo（Mishka fork）。

**版本与坐标唯一真源 = `gradle/libs.versions.toml`**（含 `[bundles]`），mihomo 版本在 `gradle.properties`，坐标/SDK 在 `buildSrc/ProjectConfig.kt`；**文档不复述版本号**，版本信息走 `BuildConfig.VERSION_NAME`/`VERSION_CODE`。`scripta:editor` 经 `includeBuild("scripta")` 引入，插件由 scripta 自己的 `pluginManagement` 解析。

**Compose 稳定性**走 [compose_compiler_config.conf](app/compose_compiler_config.conf)，**只保留实测起作用的条目**（加之前先跑报告确认确有 unstable 参数），新增 unstable 的三方/平台字段优先进该文件而非散落 `@Stable`。三条易踩：① 条目对**子类生效**——`androidx.lifecycle.ViewModel` 一行覆盖全部 ViewModel，其内部字段稳定性因此完全不影响 composable 参数；② FQN 须与实际依赖一致，Room 3 是 `androidx.room3.*`（写 `androidx.room.*` 静默失配）；③ 只认整行 `//` 注释，行尾注释会被当成 matcher 内容。

**注释写什么**：只写读代码看不出来的——踩坑根因、内核/并发时序约束、为什么不能改成另一种写法。**不写**复述下一行的标签、外部参考来源（「参考 xx example」）、版本沿革（「旧版…」「不再…」）。`// === X ===` 用于给 200 行以上的文件分组**多个**声明，不给单个函数当标题。删注释前先判断它是不是唯一记录某条不变式的地方——本仓有十余处「删掉不会编译报错、但会静默出 bug」的注释。

## 代码地图

分层靠**包名**，跨层即普通包引用。约定（非 Gradle 强制）：`domain.model` 只放 `@Serializable` 模型、`domain.repository` 只放仓库接口，二者不引 android/compose/ktor/room。

```
buildSrc/  ProjectConfig（坐标/SDK）+ GoBuildTask（Go 交叉编译）
mihomo/    submodule（YuKongA/mihomo branch Mishka，5 patch）
scripta/   includeBuild 复合构建（YAML 编辑器），app 依赖 scripta:editor
app/src/main/
├── kotlin/.../mishka/  App / MainActivity / MishkaApplication（startKoin + 全局初始化）
│   ├── domain/{model,repository}
│   ├── data/{api（REST/WS + MihomoConnectionManager）,bridge（MishkaCoreBridge）,database,repository（*Impl + ProfileProcessor + OverrideJsonStore + SubscriptionProxyResolver）,backup}
│   ├── platform/  service/  viewmodel/  util/  di/（4 个 Koin 模块）
│   └── ui/{navigation,navigation3,component,platform,theme,screen}
├── res/values{,-zh-rCN,-zh-rTW}/   assets/（构建时下载 GeoIP）   schemas/（Room）
├── cpp/  process_helper.c + mishka_jni.c + mihomo_wrapper.c + CMakeLists.txt
├── jniLibs/arm64-v8a/libmihomo.so   native/mishka_core/（Go cgo 源）
└── src/release/generated/baselineProfiles/（生成产物，需提交）
```

路由清单（`ui/navigation/Route.kt`，均实现 `NavKey`）、屏幕↔ViewModel 对应、`platform`/`ui.platform`/`service` 三个包内各组件的名字与职责都能从文件名读出，此处不复述；名字不自明的那些，约束写在下方对应条目里。

## 架构

```
MishkaApplication.startKoin ─ Koin（dataModule + androidPlatformModule + androidAppModule + viewModelModule）
  MainActivity（Koin get 取图）→ App → AppNavigation → HorizontalPager(4 Tab) + NavDisplay(二级页)
    → Screen → ViewModel → domain.repository 接口 → data.repository.*Impl
        ├→ MihomoApiClient(Ktor HTTP) + MihomoWebSocket(WS) → mihomo 进程 127.0.0.1:9090
        └→ Room（ImportedDao / PendingDao / SelectionDao）
```

**Koin**（4 模块按职责拆分，均在 `di/`）：`dataModule` = appScope、DAO、OverrideJsonStore、SubscriptionProxyResolver、RuleLatencyTester、MihomoConnectionManager、`SubscriptionRepositoryImpl` + 接口绑定、`factory { ProfileProcessor }`；`androidPlatformModule`（绑 `androidContext()`）= AppDatabase、PlatformStorage、ProxyServiceController、AppListProvider、WifiPolicyController、BootStartManager、BackupManager、ProfileUpdateScheduler；`androidAppModule` = `single<ProfileFileManager>`；`viewModelModule` = 12 个 ViewModel（单 Activity 用 `single`）。**组合根注入**：MainActivity `get()` 取图后透传给 `App(...)`，屏幕保持参数化、不用 koinViewModel；仅需 Activity 上下文的 FilePicker / VPN 授权 launcher 不入 Koin。**repo 实现必配接口**，ViewModel 依赖接口；`ProfileProcessor` 需实体级方法故依赖具体类。

- **通信方案**：runtime（traffic/logs/connections/proxy select/provider 刷新）走 subprocess + Ktor REST + WS，三模式共用；订阅导入（fetch + provider prefetch + Parse）走 JNI in-process，由 [MishkaCoreBridge](app/src/main/kotlin/top/yukonga/mishka/data/bridge/MishkaCoreBridge.kt) 调 libmihomo.so 的 cgo 导出。
- **统一 .so**：libmihomo.so（cgo c-shared，~56MB）同时承担 JNI 导出与 `mihomoEntry(argc, argv)` runtime 入口；libmihomo_runner.so（C PIE，~6KB）由 MihomoRunner fork+exec 后 dlopen 它调 mihomoEntry。一份 mihomo 代码两条路径共用。
- **mihomo 客户端共享**：`MihomoConnectionManager`（`dataModule` single）订阅 `ProxyServiceBridge.state`，Running 时造新 `MihomoRepositoryImpl`、其他状态置 null，切换前同步 close 旧实例。消费方一律经 `connectionManager.repository: StateFlow<MihomoRepository?>`：HomeViewModel 与 `DynamicNotificationManager` 自行 collect，MainActivity collect 后 `setRepository` 转发给 Proxy/Log/Provider/Connection/DnsQuery 五个 VM。
- **MishkaCoreBridge**：`init(homeDir, userAgent)` 在 `MishkaApplication.onCreate` 一次性调用，homeDir 指向共享 GeoIP 目录 `files/mihomo/geodata/`；`fetchAndValid` 内部分 token、150ms 轮询进度。
- **导航**：miuix NavDisplay + 自定义 Navigator（push/replace/pop/popUntil）+ LocalNavigator；back stack 经 Route sealed 多态序列化持久化（`NavBackStackSaver`），新增路由只需 `@Serializable` 即获得进程死亡恢复；**`sealed interface Route` 自身必须保留 `@Serializable`**——缺失编译通过但恢复时运行时 `SerializationException`。
- **深色判定单点**：`ThemeConfig.resolveIsDark(systemDark)` 是 colorMode→isDark 的唯一实现，组合树内一律读 `LocalAppDarkMode.current`；**屏幕/组件禁止直接 `isSystemInDarkTheme()`**，否则用户强制深/浅色时该处不跟随（AboutScreen OS3 背景 / YAML 编辑器配色都栽过）。主题枚举的用户可见名走 [ThemeLabels.kt](app/src/main/kotlin/top/yukonga/mishka/ui/theme/ThemeLabels.kt)，禁止各自 when 映射。**XML 主题层不认 `colorMode`**：splash 与 windowBackground 走资源限定符、只认系统 uiMode，`values-night/themes.xml` 必须与 `values/` 成对（缺失即深色冷启动闪白帧）。冷启动的背景交接交给 core-splashscreen：MainActivity 挂 `Theme.Mishka.Starting`，`installSplashScreen()` 必须先于 `super.onCreate`（`postSplashScreenTheme` 靠它换回 `Theme.Mishka`，漏了就停在 splash 主题上），`setKeepOnScreenCondition { !contentReady }` 把 splash 留到 Compose 首帧，中间那层窗口背景不再露脸。用户在 app 内强制深色而系统为浅色时 XML 层仍对不齐，只有 `UiModeManager.setApplicationNightMode` 能根治。`LocalAppMonetEnabled` 标记 Monet：StatusColors 仅 Running 态跟随动态取色，Pending/Stopped 警示黄/红固定。底栏毛玻璃无独立开关，跟随全局 `blurEnabled`。
- **隧道三模式**（`TunMode { Vpn, RootTun, RootTproxy }`）：
  - **VPN**：VpnService 创建 TUN fd，mihomo 写 `tun.file-descriptor` + `auto-route=false`，工作目录 `imported/{uuid}/`（app UID）。
  - **ROOT TUN**：mihomo 以 root 自建 TUN，`auto-route=true` + `auto-detect-interface=true`，工作目录独立 `runtime/{uuid}/` 沙箱（启动前从 imported/ 拷贝，停止前把 HTTP provider 缓存以 app UID 回写再 `su rm -rf`）；imported/ 永远 app UID。
  - **ROOT TPROXY**：`tun.enable=false`，`tproxy-port=7895` 入站 + `dns.listen=0.0.0.0:1053`；`RootTproxyApplier` 装 mangle/nat 规则与 fwmark 策略路由劫持本机与热点流量，常量与 chain 结构见 [docs/root-mode.md](docs/root-mode.md)。
  - **分应用代理**：VPN 走 VpnService API；ROOT TUN 走 mihomo `include/exclude-package`（sing-tun 翻译为 uidrange）；ROOT TPROXY 走 iptables `-m owner --uid-owner`（`AppListProvider.resolveUids` 解析包名）。**Mishka 自身始终排除**；**不**用 `routing-mark`/SO_MARK 自绕——Android Netd 用 fwmark 低 16 位编码 netId，自定义 SO_MARK 会让路由命中无默认路由的 legacy_system 表，出站全部 `network unreachable`（box_for_magisk / Surfing / box4magisk 三家同款教训）。
  - ROOT 两子模式共享 MishkaRootService，Intent 经 `EXTRA_SUBMODE = "tun"/"tproxy"` 区分；attach 前比对 `ROOT_SUBMODE_ACTIVE` 与请求 submode，不一致则 fresh restart。ROOT 进程在 app 被杀后仍存活，重开 app 靠持久化 PID/secret **attach-only** 重连。ROOT 不可用自动回退 VPN。
- **Wi-Fi 自动切换**：`WifiPolicyMonitorService` 前台监控 active Wi-Fi SSID（精确匹配、去 Android 外层引号、忽略 `<unknown ssid>`），权限不足不触发。两种动作：**停止服务**（进入匹配 Wi-Fi 且运行中时记 `WIFI_POLICY_PENDING_RESTART` 后停止，离开时仅在 pending 存在时自动启动一次）；**Direct 模式**（进入写 `WIFI_POLICY_RUNTIME_MODE=direct`，离开清 override 回退用户持久 mode，统一经 `ProxyServiceController.restart()` 热重载，三模式行为一致；runtime mode 由 `RuntimeOverrideBuilder` 优先于 `override.user.json` 注入，不污染持久配置）。Starting 窗口内的切换排队待 Running 后补一次 restart；关闭功能时恢复被策略改动的状态。监控通知与切换通知用独立 channel。开机/包替换后 `WifiPolicyBootReceiver`（默认 disabled，随开关动态启用）恢复监控。
- **状态桥接**：ProxyServiceBridge（全局 StateFlow + TunMode），Service 写、ViewModel 读。**进程模型**：单进程（VpnService 与 UI 同进程），ROOT 模式 mihomo 为独立 root 进程。
- **数据持久化**：Room 3（结构化）+ PlatformStorage（简单偏好）+ StorageKeys（key 常量）+ OverrideJsonStore（`override.user.json` + `ConfigurationOverride`）；store 自带 `state: StateFlow` + `update(transform)`，Settings 三个切片 VM 共享同一实例。**OverrideJsonStore 的内存 state 是权威值**：`load()` 返回内存值不读盘，落盘由 appScope 串行异步完成（排队期间被新值取代的快照直接放弃）。因此 **Service / ProfileWorker 必须注入 Koin 单例，不得各自 `new`**——自建实例读的是盘上旧值，用户改完设置立刻启动就会用到改前的配置。
- **订阅管理**：Pending → Processing → Imported 三阶段沙箱，`ProfileProcessor` 编排 snapshot → fetchAndValid（JNI 一次完成 fetch + provider prefetch + Parse）→ commit；processLock 串行，profileLock 守护 DB 一致性。
- **深链一键导入**：`clash://install-config?url=&name=&update-interval=`（兼容 `clashmeta://`）由 ExternalImportActivity（透明 + noHistory + excludeFromRecents）承接，校验 url 为 http(s) 后带随机 nonce 转发 MainActivity，经 `deepLinkImport` 驱动 popUntil Main → pager 切订阅 Tab → push 预填 `Route.SubscriptionAddUrl`。**不静默导入**，用户确认后走常规 Pending → APPLY 管线。两个防御点：① 用 **nonce 去重**而非 `savedInstanceState` 判空——进程死亡恢复会重放旧 intent（需跳过），而「任务存活但进程被杀」时新深链可能带恢复态送达（需接受），判空无法区分；② 跳板 exported，`intent.data` 先 `takeIf { isHierarchical }` 再取 query——非层级 URI 上 `getQueryParameter` 直接抛异常。
- **订阅 HTTP**：mihomo `component/http.HttpRequest`（in-process cgo），60s timeout；UA 默认 `ClashMetaForAndroid/{version}`，用户可在订阅 Add/Edit 页填自定义 UA 持久化到 `ImportedEntity.userAgent`/`PendingEntity.userAgent`，经 PendingSnapshot 透传到 Go `runFetchAndValid` 内 `effectiveUA = trim(userAgent) ?: currentUserAgent()`；非 2xx / 空 body → `MishkaCoreError`；不做 base64/V2Ray 转换。**名字留空自动命名**：Url 型允许名字留空（`enforceFieldValid` 放行），fetch 响应的 `Content-Disposition` filename（Go 侧 `mime.ParseMediaType` 原生解 RFC 5987，去 .yaml/.yml 后缀）经 `FetchResult.FileName` 回传，`commitPending(fallbackName)` 仅在 **commit 时刻** `pending.name` 仍为空才采用——用户输入（含深链 `name` 参数）永远优先，兜底链 disposition > URL host > 调用方注入的默认名；更新订阅（isUpdate）不走此路径、绝不改名。
- **age 加密订阅**：per-profile `ageSecretKey`（DB v3）经 `PendingSnapshot` 透传到 `fetchAndValid`。**加密原样落盘、运行时解密**：导入校验时 Kotlin 侧 fetch 前 `nativeSetAgeSecretKey`、fetch 后清空（processLock 串行保证不串），Go 侧在内存中解密校验，config.yaml 与 provider 文件保持加密落盘；运行时由 Service 从 DB 读密钥经 `MihomoRunner.start(ageSecretKey)` 加 `--age-secret-key` CLI flag，`runtime.go` 在 `hub.Parse` 前 `age.SetGlobalSecretKeys`；ROOT attach 路径进程已带密钥不重传。**副作用**：加密订阅对 app 不透明，`ConfigGenerator.readSubscriptionSecret`/`readSubscriptionMixedPort` 行扫描扫不到 → 退回默认值。密钥生成走 Meta 设置 → `mishkaGenAgeKeyPair`/`mishkaGenAgeHybridKeyPair`（X25519 / mlkem768-x25519）。
- **app 内下载一律走 mixed-port**：Mishka 自身永远绕过 TUN，直连境外资源极慢——这是「图标加载慢」的根因模式，**任何新增的外网下载都要走 mixed-port**。`SubscriptionProxyResolver` 按「开关 + 代理运行中 + 可解析 mixed-port」返回 proxy URL 或 null，`resolve(requireUserToggle)` 控制是否受用户开关约束（订阅下载传 true，图标等通用资源传 false）。订阅侧由 native glue 在 fetchAndValid 入口 `os.Setenv("HTTPS_PROXY"/"HTTP_PROXY")` defer Unsetenv，覆盖 fetch + provider prefetch + GeoIP 自动下载，processLock 串行保证 set/unset 并发安全。[IconLoader](app/src/main/kotlin/top/yukonga/mishka/ui/platform/IconLoader.kt)：内存 LRU(64) → 磁盘缓存 → 网络（限流 3 并发 + 5s/15s 超时），失败 URL 负缓存 60s，磁盘读取/解码在 `Dispatchers.IO`，proxy 解析结果缓存 10s、随 URL 变化 close 旧建新。
- **GeoIP 预制**：构建时 DownloadGeoFilesTask 下载 geoip.metadb/geosite.dat/ASN.mmdb 到 assets，启动时提取到 `files/mihomo/geodata/`。JNI 路径用 `mishkaCoreInit(geodataDir)` 把 mihomo 全局 homeDir 指到这里；subprocess runtime 按 `-d workDir` + symlink 复用同一份。
- **WebDAV / 本地备份恢复**（设置 →「备份与恢复」）：固定文件名覆盖式。zip = `backup.json`（三表 **JSON 导出重放**而非拷 db 文件，绕开 WAL 一致性、跨 schema 由字段默认值兜底）+ imported/、pending/ 目录树 + override.user.json。备份与恢复都持 `ProfileProcessor.withProcessLock`，**文件换入与三表重放另包一层 `withProfileLock`**——processLock 挡不住只持 profileLock 的 create/patch/delete；三表重放走 `withWriteTransaction` 整体原子，逐条 insert 各自一个隐式事务，中途撞上 `ImportedDao` 的 ABORT 就会留下半张表而文件已经换完。恢复前置校验代理已停止。见 [BackupManager](app/src/main/kotlin/top/yukonga/mishka/data/backup/BackupManager.kt) / [WebDavClient](app/src/main/kotlin/top/yukonga/mishka/data/backup/WebDavClient.kt)。
  - **重定向手动跟随**：Ktor 默认只对 GET/HEAD 跟 3xx，而服务器常把无尾斜杠目录 301 到带斜杠版本，MKCOL/PUT 收到 301 直接失败。集合 URL 一律带尾斜杠 + `davRequest` 手动跟随 301/302/307/308 并保持方法与 body，**仅限同主机**——Basic 凭据跨主机跟随会泄露密码。
  - **恢复两阶段落地**：先全写进 `.restore/`（zip-slip 基准也是它），正式目录直到 rename 换入才被触碰，失败从 `.restore-old/` 回滚。**绝不能先删再逐条校验/写盘**——非法归档或 ENOSPC 会让订阅目录永久消失。版本过新的校验排在解包之后（单遍读归档才知道 backup.json 内容），此时只有 staging 被写过。
  - **全程流式**：zip 直接写进目标流、边读边落 staging，只有 `backup.json`（KB 级）进内存。provider 缓存能让备份到几十 MB，「先攒完整 zip」+「全量解压进 `Map<String, ByteArray>`」两头叠加正是最容易 OOM 的组合。SAF 侧只传 `Uri`（读写在 data 层的 IO 上做，不在主线程回调里搬内容），WebDAV 走 cacheDir 中转文件以带上 Content-Length。
  - **排除项**：geodata 符号链接与实体拷贝不进备份（名单 + isSymbolicLink 双重排除，否则 readBytes 追链接把几十 MB 实体化进 zip）；prefs 黑名单排除设备/运行时态与 WebDAV 凭据自身。
  - **恢复后强制重启进程**（内存热状态不随磁盘刷新）。开机自启是 PackageManager 组件位而非 pref，走快照独立字段；Wi-Fi 策略组件位与监控服务由重启后 MainActivity 按 `WIFI_POLICY_ENABLED` 幂等 reconcile。
  - **本地备份**复用同一 zip 与恢复管线：SAF `CreateDocument`（`"wt"` 截断写防旧文档尾部残留）+ `OpenDocument`（不按 MIME 过滤——网盘流转后常报 octet-stream）。
- **国际化**：英文 + 简体中文（zh-rCN）+ 繁体中文（zh-rTW，台湾用语：設定/檔案/匯入/連線/連接埠/快取/伺服器/金鑰/還原/套用/群組/逾時，非简转繁），Composable 用 `stringResource`、非 Composable 用 `context.getString`；日志英文，代码注释中文。**data 层拿不到资源**：会被用户看见的兜底值（如订阅自动命名的最后一环）由调用方按 locale 注入，别在 data 层写字面量，也别为此把 Context 拉进去。

## 数据库（Room 3）

三表：`imported`（已导入的稳定订阅）/ `pending`（编辑中草稿，提交后移入 imported）/ `selections`（代理组选择记录，per 订阅）。

- **ProfileType enum**（`File`/`Url`/`External`）经 `ProfileTypeConverter` 映射为 TEXT 列；订阅 UUID 完整 36 字符（UUID v4，不做冲突检测）。
- **updatedAt 动态计算**：`ImportedEntity` 无此字段，`resolveProfile` 读 pending→imported 目录 mtime，fallback `createdAt`；commit/update 自然更新 mtime，无需主动写 DB。
- **Schema 版本**：v1→v2 `MIGRATION_1_2` 加 `userAgent`；v2→v3 `MIGRATION_2_3` 加 `ageSecretKey`（均 `TEXT NOT NULL DEFAULT ''`）。新增列时 schema 须由 KSP 导出到 [app/schemas](app/schemas/)（跑一次 `:app:assembleDebug` 落盘），且 **MIGRATION 必须在 `AppDatabaseBuilder` 的 `addMigrations(...)` 注册，遗漏不影响全新安装、升级用户首启即 crash**。

**三阶段流程**（ProfileProcessor）：

```
CREATE → Pending ✓, Imported ∅
  → APPLY（processLock 串行）：
      ① snapshot（profileLock 内）：query Pending + enforceFieldValid + prepareProcessing（清 processing/ + 复制 pending/{uuid}/ → processing/）
      ② fetchAndValid（锁外、可取消、JNI in-process）：Url 型 force=true 重新下载 config.yaml；File 型 force=false 跳过 fetch；两者均 prefetch provider（**并发度 5**、每源 60s 超时、无总时限）后 Parse 校验；httpProxy 由 SubscriptionProxyResolver 决定
      ③ commit（profileLock 内，`withContext(NonCancellable)` 原子）：一致性检查 → commitProcessingToImported（拷到 commit.new/ 再 rename 换入）→ DB 更新
  → 失败：cleanupProcessing（NonCancellable）；pending/ 与 imported/ 都不动，可 retry
  → RELEASE：删 Pending DB + pending/{uuid}/
PATCH（编辑已导入）→ APPLY → Imported 更新, Pending ∅
UPDATE（手动/自动）→ 等价 APPLY，snapshot 取自 Imported
DELETE → 三表清理 + imported/{uuid}/ + pending/{uuid}/ 删除
```

**目录**：`files/mihomo/` 下 `geodata/`（共享 GeoIP + 符号链接）、`imported/{uuid}/`、`pending/{uuid}/`、`processing/`（临时校验沙箱，单例）、`runtime/{uuid}/`（ROOT 运行时沙箱，停机前 HTTP provider 缓存回写 imported/）、`override.user.json`（用户设置）、`override.run.json`（启动时合并 TUN fd + AppProxy + rootMode）。换入用的临时目录：`commit.new` / `commit.old.{uuid}`、`.restore` / `.restore-old`。

**imported/ 一律「先拷后 rename」，不得删了再拷**：拷进 `commit.new/` 再两次 rename 换入，删除全排在拷贝之后——失败时 `imported/{uuid}/` 仍完整（update 路径下它是唯一副本）。rename 只要父目录可写，不受 root:root 残留影响。两次 rename 之间的窗口由 `commit.old.{uuid}` 兜底，`cleanupProcessing` 启动时按它还原。

## 构建

mihomo 经 submodule 引入 Mishka fork（branch `Mishka`）。Gradle 按 ABI 驱动 Go 构建（当前仅 arm64-v8a），产物落 `app/src/main/jniLibs/<ABI>/`；`assemble` 自动触发 buildMihomo / CMake，`downloadGeoFiles` 需手动或 CI 跑。刷新 Baseline Profile：`./gradlew :app:generateReleaseBaselineProfile`（需 adb 连 arm64 真机，产物提交进仓库）。

**`downloadGeoFiles` 是 `outputs.upToDateWhen { false }`**：上游 `latest` tag 原地重发布，URL 与本地文件都不变，Gradle 只会一直判 UP-TO-DATE，本地永远停在第一次抓到的那份（CI 全新 clone 没有任务历史，察觉不到）。它没有下游依赖、只在被点名时才跑，「调了就去取最新」正是该有的语义。任务本身：URL 表是 `@Input`、连接与读取都有超时、响应必须 200 且体积过下限（404 / 限流的 HTML 会被原样写成 `geoip.metadb`，构建全绿而运行时加载失败）、先落 `.part` 再 rename。它的 `@OutputDirectory` 就是 `src/main/assets`，同时是 `mergeAssets` 的输入源，故对 `merge*Assets` 声明 `mustRunAfter`——否则两者出现在同一次调用里，Gradle 会以「消费了未声明依赖的任务输出」中止。

[GoBuildTask](buildSrc/src/main/kotlin/GoBuildTask.kt) 产 `libmihomo.so`（CGO_ENABLED=1，需 NDK clang，从 `androidComponents.sdkComponents.ndkDirectory` 读）。三条硬约束：

- **mihomo submodule 必须整棵声明进 `replacedModuleSources`**——它经 go.mod `replace` 引入，被编译的代码绝大部分在那里而不在 `goSourceDir`；漏声明会让 rebase / 改 patch 后任务判 UP-TO-DATE、`go build` 根本不执行，静默产出陈旧 .so，现象与「patch 没生效」无法区分（`mihomo.version` 是 gradle.properties 里的手写字面量，指望它兜底就是指望每次都记得手改）。过滤用**反向排除**而非扩展名白名单：`component/ca` 有 `go:embed` 嵌 `.crt`，白名单漏一种后缀就是同一个坑；宁可多收（多重建一次）也不能漏（错误发不出信号）。
- **`libmihomo.h` 必须与 .so 一起声明成任务输出**——它由 c-shared 一并生成、被 CMake 的 `target_include_directories` 消费；不声明则 stale-output 清理可以删掉它，报错是 CMake 的 `No such file`、与真实原因隔了一层。
- **`-buildvcs=false`** 与 `-trimpath` 同源：VCS stamp 让产物不可复现，且在没有 git / 仓库属主不匹配的容器里直接构建失败。

CMake `dependsOn(buildMihomo)`，产两个轻量件链 libmihomo.so（IMPORTED + IMPORTED_SONAME）：`libmihomo_runner.so`（PIE wrapper）与 `libmishka_jni.so`（薄 JNI 桥）。

## 关键架构约束

**启动校验单点**：所有「启动代理」路径必须经 [ProxyServiceController.start / restart](app/src/main/kotlin/top/yukonga/mishka/platform/ProxyServiceController.kt)，两个 Service 因此不暴露 `start`/`stop` 静态入口——那是绕过校验的现成口子。`resolveStartSubscriptionId()` 统一校验 active 订阅 + `imported/{uuid}/config.yaml` 落盘，失败时一次完成 toast + `updateState(Error)` + 清 `SERVICE_WAS_RUNNING` + Running 时发 STOP。Intent 一律用类对象 + Service 自己的 action / extra 常量拼，**别写 FQCN 与 extra 字面量**：重命名时编译器不报错，故障表现是「attach-only 静默退化成全新启动」。新增入口（Wear / shortcut / 自动化）严禁直拉 Service；Service 内 `ProfileFileOps.hasValidConfig` 是针对 ADB / 三方 Intent 的兜底。Tile / 通知等无 Activity 上下文要在 VPN 模式弹授权，必须经 [VpnPermissionActivity](app/src/main/kotlin/top/yukonga/mishka/service/VpnPermissionActivity.kt)（`VpnService.prepare()` 要求 Activity context）。

**Service 启动必须幂等串行**：`MishkaRootService` / `MishkaTunService` 用 `startJob`（`@Volatile`，跨主线程与 IO 协程访问）守门，`ACTION_START` 撞上进行中的启动直接忽略；`stopProxy` / `restartProxy` / `onRevoke` 必须先 `startJob?.cancelAndJoin()` 再往下走，否则被幂等检查挡掉而静默失效。ACTION_START 会在数百毫秒内到达两次：「打开应用自动连接」发一次，[BootReceiver](app/src/main/kotlin/top/yukonga/mishka/service/BootReceiver.kt) 收到补发的 BOOT_COMPLETED 再发一次（HyperOS 等 ROM 拒绝向未运行的 app 投递，改为等进程起来后补发，正好和自动连接撞车；BootReceiver 另 `new` 了一个 controller，绕过 `launchAutoConnectConsumed` 这个进程级去重标记）；两条启动协程并发跑 iptables 会互抢 `/system/etc/xtables.lock`，ROOT TPROXY 下直接打死启动。BootReceiver 只在 `state == Running` 时跳过（Starting 仍要发，好让下面的抢占生效），去重责任单点落在 Service。**症状指纹**：同一 Service 里两条 `Starting proxy` 日志、线程号不同。

**唯一例外是 fresh START 抢占进行中的 attach-only**（cancel 后在新协程里 `join()` 等其收敛）：attach-only 失败只保持停止，而 fresh 请求表达的是「必须跑起来」；漏了这条，`AUTO_CONNECT_ON_LAUNCH` 关 + 开机自启开时 `reattachRoot` 会抢先占住 startJob 让开机自启失效。**抢占要靠显式 `isActive` 检查兜底**：attach-only 从入口到 `stopSelf()` 全程没有 suspend 点，`cancel()` 打断不了，必须在那个分支里自查 `isActive` 主动让位，否则它会 stopSelf 掉 Service、连带杀死接替的协程。

**停止态一律走 `ProxyServiceBridge.markStopped(tunMode)` / `markStoppedUnlessError(tunMode)`**：**别再手写 `updateState(ProxyServiceStatus(Stopped))`**——那样 tunMode 落回默认 `Vpn`，终态谎称模式（消费方只能回读 storage，而 storage 是「用户当前选择」不是「刚才在跑的那个」）。**onDestroy 不得覆盖 Error**：失败路径都是 `updateState(Error) + stopSelf()`，紧接着就走 onDestroy，无条件写 Stopped 会立刻抹掉刚写入的 errorMessage，用户只看到「启动中 → 未运行」。Error 是终态，该规则封在 `markStoppedUnlessError`（CAS，非读后写）里，两个 Service 不可能各写错一份。配套：`HomeViewModel` 的 Error 分支弹 toast（errorMessage 不在首页渲染，Error 与 Stopped 视觉上无差别），同一条只弹一次、回到 Stopped 时清标记；发布方已自行 toast 过的（如 `resolveStartSubscriptionId`，它要覆盖 Tile / 通知这类没有 HomeViewModel 在场的入口）置 `errorNotified = true`，UI 层据此跳过。

**Ktor HttpClient 所有权**：禁止任何模块直接 `MihomoApiClient(...)` / `MihomoWebSocket(...)`，统一从 `connectionManager.repository` 订阅。`MihomoConnectionManager` 是唯一持 `close()` 责任方，按 bridge state 自动 connect/disconnect、原子 close 旧 + new 新——不做 endpoint 比对（attach 重连多一次重建 < 50ms，胜过状态机比对出 race）。ViewModel 的 `setRepository` 仅传信号、不担 close。例外：`SubscriptionProxyResolver` / `RuleLatencyTester` 因探测场景独立于实时连接，可自建短生命周期 client，但必须 `use{}` 或 try/finally close。订阅 fetch 走 JNI 不经 Ktor。

**ViewModel `setRepository` 必须 cancel 旧拉取协程**：mihomo 重启 / 切订阅时 manager 会 close 旧 client 并 emit 新 repo，消费方必须先 `loadJob?.cancel()` 再切字段，协程内再用 `if (repository !== repo) return@launch` 双保险——Ktor `client.close()` 让 in-flight 请求抛异常**但不取消协程**，旧响应的 onSuccess 仍会跑到末尾把 `_uiState` 写成旧订阅数据。WS 流更甚：无限重连且吞掉非取消异常，`close()` 后只进入退避循环，cancel 是唯一终止手段，漏 cancel 等于留一条僵尸协程。ProxyViewModel / ProviderViewModel / LogViewModel / ConnectionViewModel / DnsQueryViewModel 均按此模式（一次性请求同样要——DNS 查询会连 `isQuerying` 一起卡住）。**切订阅后 ProxyScreen 显示旧组**就是这条 race。

**Override 注入**：所有 override 走 `--override-json` CLI flag + JSON 文件，Kotlin 侧零 YAML 改写。用户设置 `OverrideJsonStore.update{}` → `override.user.json`，启动时 `RuntimeOverrideBuilder` 叠加 TUN fd / AppProxy / rootMode → `override.run.json`；`secret` / `external-controller` 走 `--secret` / `--ext-ctl` 不进 JSON。`ConfigurationOverride` 全树 `val`，改动一律 `copy()`——store 把同一个实例当作 StateFlow 的值发布，就地改字段会让相等去重把更新静默吞掉。

**RuntimeOverrideBuilder 默认注入**（用户未显式设置时）：`tcp-concurrent=true`、`find-process-mode=off`（分应用已由 sing-tun / VpnService / iptables uid-owner 处理，运行期遍历 `/proc` 纯冗余）。ROOT TUN 额外默认 `tun.mtu=9000 + gso=true + gso-max-size=65535`（大包聚合减少 read syscall），由 `ROOT_TUN_JUMBO_MTU`（默认 true）控制，关闭回退 1500/false；VPN 不注入 MTU/GSO（由 `VpnService.Builder` 管）。TUN stack 优先级：用户 override 显式设置 > 订阅最终 YAML（含脚本变换）显式设置 > Go runtime 默认 MIPS；ROOT TPROXY 不启用 TUN。mixed-port 优先级：① 用户 override 显式设置 → 用用户值；② 订阅 yaml 自带（`ConfigGenerator.readSubscriptionMixedPort` 行扫描）→ 不注入，避免兜底值覆盖订阅原值；③ `SUBSCRIPTION_UPDATE_VIA_PROXY` 启用 → 注入 7890 兜底；④ 其余不注入。

**硬编码覆盖订阅（按 submode）**：`profile.store-selected=false` / `store-fake-ip=true` 三模式共用。VPN：`tun.enable=true` + `file-descriptor` + `dns-hijack=[0.0.0.0:53]` + `auto-route=false`，透传 stack/device。ROOT TUN：`auto-route=true` + `auto-detect-interface=true` + `iproute2-table-index=2022` + `iproute2-rule-index=9000` + dns-hijack + `include/exclude-package` + **`route-exclude-address`**（私网 + 组播 + 保留段，复用 `IptablesIntranet.V4`，IPv6 开启时叠加 `.V6`）——sing-tun `auto_route` 在该项为空时铺满 `0.0.0.0/0`，会把 LAN 单播与 224/4 组播一起吸进 TUN，破坏同 LAN 设备发现 / P2P 直连（妙享投屏）；VPN 由 `bypass_private_route` 分流、ROOT TPROXY 由 iptables RETURN 处理，唯独 ROOT TUN 缺这层。ROOT TPROXY：`tun.enable=false` + `tproxy-port=7895` + `dns.listen=0.0.0.0:1053`；**不写** `routing-mark`（Netd 冲突）、**不写** `include/exclude-package`（走 uid-owner）。

**secret 优先级**：用户设置 > 订阅 `config.yaml` 顶层 `secret:`（`readSubscriptionSecret` 行扫描）> 随机 UUID 前 16 字节；ROOT attach 分支走 storage 持久化的 `existingSecret`。实现单点是 `ConfigGenerator.resolveSecret`，两个 Service 都从那里取。

**`/proxies` 不含 provider 节点**：mihomo 的 `GET /proxies` 与 `findProxyByName` 中间件只覆盖 runtime proxies（`proxies:` 段 + 代理组），proxy-provider 节点在这套命名空间里**查不到 / 404**。故节点详情需把 `/providers/proxies` 各 provider 的 `proxies` 合并进结果（runtime 优先补缺，`ProxyViewModel` 维护 `nodeProviderMap`）；provider 节点单测走 `GET /providers/proxies/{provider}/{node}/healthcheck`；组测速与组选择不受影响。仅 provider 型（模板）订阅命中。

**GLOBAL 组常驻代理页**：`GET /group` 里 GLOBAL 与普通组同级（Selector），`loadProxies` 既拿它的 `all` 当组排序基准，也把它本身排进列表——`mode == "global"` 时置顶（唯一生效出口），其余沉底；mode 从 `GET /configs` 现取。非全局模式下是否留在列表由 `PROXY_SHOW_GLOBAL_GROUP`（默认开）控制，**全局模式无视该开关强制显示**，否则退回「生效出口不可选」的老 bug。关掉只影响展示：`orderMap` 仍从 `globalGroup.all` 算，过滤只在 UI 层做。**默认开的理由**：rule 模式下 GLOBAL 虽不参与路由，但其 `all` 是全部节点 + 全部组，`/group/GLOBAL/delay` 因此是唯一的一键全量测速入口，且提前选好的出口切到 global 立即生效。

**出站模式提示而非隐藏代理组**：`GET /group` / `GET /proxies` 的返回与 `mode` 无关；direct 模式 mihomo 在 `resolveMetadata` 直接返回 DIRECT、根本不查组，global 模式只有 GLOBAL 决定出口。两种模式仍显示全量组是**正确**的：`PUT /proxies/{group}` 在任何模式下都被接受并记住，切回 rule 立即生效，延迟测试照常有效——隐藏会砍掉「先挑好节点再切回规则模式」的正常用法。缺的只是告知，故 `ProxyUiState.mode` 携带小写 mode，`ProxyScreen` 据此在首个 lazy item 渲染提示卡；mode 常量在 `ProxyViewModel.companion`，禁止屏幕里裸写字符串。

**主页延迟测试按规则走 mixed-port，不用 `/proxies/{name}/delay`**：后者对指定组的**当前选中节点直接拨测、绕过规则引擎**，测出的数字与该域名实际命中的分流规则无关；且该 API 必须收一个 proxy 名，这个实现约束会泄露到 UI 上变成「代理组选择器」。正确做法是经本机 mixed-port 发真实 HTTP 请求（[RuleLatencyTester](app/src/main/kotlin/top/yukonga/mishka/data/api/RuleLatencyTester.kt)），出口由规则引擎决定——**Mishka 自身流量始终绕过 TUN，mixed-port 是唯一能让自己的请求经过 mihomo 的入口**。三条实现约束：① 每次测量新建 client，复用连接池会让重复刷新逐次偏低；② `followRedirects = false`，跟随 3xx 会把跳转往返计进耗时；③ mixed-port 未必存在，解析不到时退回 `GLOBAL` 组拨测并置 `latencyViaRules = false`，UI 标注「未走规则」——**不静默给误导性数字**。代理页的节点/组测速仍该用 `/proxies/{name}/delay`。

**连接速率必须自行差分**：`/connections`（WS，1Hz 全量列表）每条只给**累计** `upload`/`download`，按累计量排序会让长连接永远压在前面。[SpeedDetailSheet](app/src/main/kotlin/top/yukonga/mishka/ui/screen/home/SpeedDetailSheet.kt) + `updateConnectionRates` 对相邻两次快照按 `id` 差分再除以实际间隔，1Hz 推送正是天然差分周期。三条约束：① **首轮只留基准不出结果**，否则每条长连接的历史总量会被当成瞬时速率；② `topConnectionRates` 用 **null 区分「首轮未完成」与「确实无活跃连接」**，UI 分别显示 loading 与空态；③ **订阅只在详情打开期间存续**（`DisposableEffect` start/stop），几百条连接的全量列表每秒推一次，常驻代价不小；`disconnectStreams` 里也要 stop（旧 client close 后差分基准已失效）。**按应用/进程聚合做不到**：`metadata.process` 依赖 `find-process-mode`，而默认注入 `off`，该字段恒为空。同理，**连接页自己的 `/connections` 订阅也只在本页存续**：`ConnectionViewModel` 是 Koin `single`，`setRepository` 无条件开采集会让代理一跑就常驻解析全量列表，故 start/stop 由 `ConnectionScreen` 的 `DisposableEffect` 驱动，`setRepository` 只在 `observing` 时接上。

**Koin single VM 的重初始化挂订阅、不挂 `init{}`**：ViewModel 随冷启动构造、`onCleared` 永不触发，`init { load() }` 等于每次启动都替一个多数会话不会打开的页面付账。分应用代理的 PackageManager 全量枚举（加逐包 `loadLabel`，几百毫秒 CPU + 常驻全量列表）因此挂在 `filteredAppsFlow` 的 `onStart` 上，`stateIn` 用 `WhileSubscribed`。同理**分应用列表禁止 `getInstalledPackages(GET_PERMISSIONS)`**——完整权限数组过 Binder 是 `TransactionTooLargeException` 的经典触发点，改用「持 INTERNET 权限的包名集合 + `getInstalledApplications`」两次窄查询。

**viewModelScope 的轮询循环必须门控 UI 可见性**：`HomeViewModel` 的系统信息采样（`NetworkInterface` 枚举 + `/proc/<pid>/stat`，均阻塞、须在 IO）、`/configs` 轮询、uptime 计数都挂在 `viewModelScope` 上，不感知生命周期。三者收敛到 `pollWhileVisible(interval)`，由 `MainActivity.onStart/onStop` 经 `setUiVisible` 驱动——不门控就是后台每天数万次本地 HTTP 与 `/proc` 读。

**CMFA embed mode 禁 HTTP 配置 API**：`PATCH/PUT /configs`、`POST /restart`、`POST /configs/geo`、`PUT/PATCH /rules`、`POST /upgrade` 全 404。**绝不添加** `patchConfig`/`restart` 方法，配置修改一律走 `OverrideJsonStore.update{}` + `serviceController.restart()`，UI 用 `RestartRequiredHint` 提示。

**订阅导入走 JNI in-process**：fetch + provider prefetch + Parse 三步走 `MishkaCoreBridge.fetchAndValid`，禁止再起 mihomo 子进程做这些事。`MishkaApplication.onCreate` 必须先 `extractGeoFiles()` 再 `MishkaCoreBridge.init(...)`——后者 `constant.SetHomeDir` 必须指向已就位的 GeoIP 目录。

**导入管线的取消语义**：外层 `runProcess` 可取消，仅 commit 阶段包 `withContext(NonCancellable)` 保证文件 swap + DB 更新原子；catch 块的 `cleanupProcessing` 同样 NonCancellable。阻塞 JNI 调用不响应协程取消，`withContext` 要等它返回才抛 `CancellationException`——那时 Go 侧 defer 已清空 `cancelRegistry`，`nativeCancel` 成 no-op，processLock 会被占到 60s 超时。故 `fetchAndValid` 在**阻塞调用之前**挂一条停在 `awaitCancellation()` 的 watcher 协程，被取消的瞬间就调 `nativeCancel`；`nativeDone` 标记避免正常返回后多余调用，`finally` 里必须 cancel 掉它，否则 `coroutineScope` 永不返回。**不能改用 `invokeOnCompletion`**——`coroutineScope` 的 Job 要等子协程收敛才算完成，同样太晚。`cancelCurrentUpdate` 先同步 `clearProgress()` 让 Dialog 立即消失再 cancel。

**native 五条**：① **JNI 库加载顺序**——`libmishka_jni.so` 链接依赖 libmihomo.so 导出符号，`System.loadLibrary("mihomo")` 必须先于 `loadLibrary("mishka_jni")`。② **libmihomo.so 必须显式设 SONAME**——cgo c-shared 默认不写，消费方会把构建期绝对路径烙进 DT_NEEDED，运行时 `UnsatisfiedLinkError`；GoBuildTask 的 `-extldflags=-Wl,-soname,libmihomo.so` 与 CMake `IMPORTED_SONAME` 两边必须对齐。③ **libmihomo_runner.so 是 PIE wrapper**——读 `/proc/self/exe` 推同目录 → dlopen → dlsym `mihomoEntry` → 透传 argv；新加 CLI flag 必须同步注册到 `mishka_core/runtime.go` 的 `flag.NewFlagSet`，否则被 ExitOnError 拦截；`cleanupOrphanedMihomo` 按其 cmdline 匹配孤儿进程。④ **cgo `*C.char` 必须 Go 侧释放**——`//export` 返回的字符串内存属 Go runtime，C 侧只能调 `mishkaFreeString()`，`free()` 会导致 cgo 堆损坏。⑤ **`//export` 必须收住 panic**——panic 逸出 cgo 边界会终止宿主进程，JNI 是 in-process、死的是整个 app；返回字符串的导出函数走 `guardString` 降级成 `"error: "`，只覆盖同一 goroutine。

**JNI fork+exec**：Android `ProcessBuilder` fork 后强制关闭非标准 fd（无论 O_CLOEXEC），VPN 模式必须用 JNI `fork()+exec()`（`process_helper.c`）保留 TUN fd 继承。fork 与 exec 之间只能调 async-signal-safe 函数，子进程分支**不要加日志**。

**Mishka 自身包名必须绕过 TUN/VPN**：`ProcessBuilder` 子进程 HTTP 被代理捕获会永久阻塞。ROOT 三种 AppProxyMode 都把 `packageName` 从 include 剔除或塞进 exclude；VPN `AllowSelected` 分支先过滤 self 再 addAllowed，过滤后空列表退化到 `addDisallowedApplication(self)`。

**协程锁规则**：`kotlinx.coroutines.sync.Mutex` **不可重入**。`updateImported`/`commitPending`/`queryImported`/`queryPending` 被 `ProfileProcessor` 在 `withProfileLock{}` 内调用，**不能自己加锁**；`create`/`patch`/`release`/`delete` 直接被 ViewModel 调用，**保留自身锁**。锁顺序 processLock → profileLock，全仓只在 commit 一处嵌套。

**processing/ 单例目录必须进程级串行**：`processing/` 是进程内单例沙箱（路径不带 uuid），`prepareProcessing` 每次清空后重填、`commitProcessingToImported(uuid)` 再换入 imported/。因此 `ProfileProcessor.processLock` 必须是 **companion 进程级** Mutex——前台 `SubscriptionViewModel.processor` 与后台 `ProfileWorker.processor`（每个 `ACTION_UPDATE_PROFILE` 都新建）是不同实例；锁若实例级，两个并发 update 会交错清空同一 `processing/`，把 B 下载的 config 提交进 `imported/A/`，造成「界面显示订阅 A、点击启动实际运行 B」的偶发 Bug。启动清理走 `ProfileProcessor.cleanupResidual()`（持同一把锁），不能直接 `ProfileFileOps.cleanupProcessing`；它同时按 DB 现存 uuid 反扫 `imported/`、`pending/` 删孤儿目录（删除订阅是「先删 DB 行 → 再删目录」两步，中间进程死亡会留下无人认领的目录），顺序必须排在 `cleanupProcessing` 之后——后者会把 `commit.old.{uuid}` 还原回 imported/。

**「配置变了就重启」的决策单点**：一律走 [ProxyServiceController.restartWhenReady](app/src/main/kotlin/top/yukonga/mishka/platform/ProxyServiceController.kt)，**必须读 ProxyServiceBridge 而非调用方自己的 UI 标志**——`uiState.isRunning` 在代理 Starting 窗口（约 10s）内仍为 false，据此决策会漏掉重启，表现成「界面已是新配置、代理仍跑旧的」。Starting/Stopping 过渡态不能直接重启（会与 Service 内进行中的启动协程并发），挂起等状态收敛：落到 Running 补一次，落到 Stopped/Error 放弃（用户中途手动停了就不该被拉起）。两个调用方：`onActiveSubscriptionChanged()`（切换/删除 active；删光最后一条时改为 stop + `cancelPendingRestart()`，没有配置可切时 restart 只会失败成 Error）与 `restartAfterProfileUpdate(uuid)`（见下）。重启本身是**整进程重来**而非热重载——VPN 杀进程重建 TUN fd，ROOT 先把 HTTP provider 缓存回写 imported/ 再清 `runtime/{uuid}/` 沙箱与持久化 PID 让 attach 分支失效、强制全新启动读新配置。

**订阅更新后自动重启**：mihomo 只在进程启动时读一次 config.yaml，embed mode 又禁掉了 `/configs`/`/restart`，**「让更新生效」除了重启进程没有别的手段**。`RESTART_AFTER_PROFILE_UPDATE`（默认开）控制，三个更新入口成功后调 `restartAfterProfileUpdate(uuid)`：`fetchSubscription` / `updateAllSubscriptions` / `ProfileWorker.runUpdate`；编辑订阅不触发。**active 判定收在 controller 内**（读 storage），别让调用方各自判——前台手边是 DB、后台 Worker 只有 storage，两套判定源迟早不一致；非 active 订阅的更新不影响正在跑的配置，直接忽略。批量更新的重启**排在整轮结束之后且只发一次**：逐条重启会断流，后续订阅走 mixed-port 的下载会跟着一起失败。

**订阅导入不自动切换活跃**：`addSubscription`/`addFromFile` 成功后**不**调 `setActive`；仅首次导入（`count() == 1`）由 `commitProcessingToImported` 自动激活。

**SubscriptionRepository 单例 + 订阅流量数据合并**：`SubscriptionRepositoryImpl` 由 Koin `single` 提供，SubscriptionViewModel 与 HomeViewModel 共用同一实例（`ProfileWorker` 例外，后台独立构建）；禁止 ViewModel 内 new。订阅页与主页流量栏的数据语义必须**强一致**——`resolveProfile` 在 combine 内合并三层 `pending > live provider snapshot > imported DB`，`_liveProvider` 携带 `subscriptionId` 做归属校验。三层缺一不可：模板订阅 DB.total=0 但 provider 各自有 header → live 覆盖；常规单源订阅 providers 为空 → fallback DB；File 型两边都为 0 → UI 显示 "--"。[HomeViewModel](app/src/main/kotlin/top/yukonga/mishka/viewmodel/HomeViewModel.kt) 是唯一 runtime producer：`refreshProviderTraffic` 取 GET 快照、`updateAllProviders` 逐 provider PUT 后再 GET（mihomo 的 `subscriptionInfo` 仅在 provider 更新时刷新，纯 GET 读到的永远是旧快照），`aggregateProviderInfo` 把所有 `Total > 0` 的 provider 求和、Expire 取最近非零后经 `onLiveProviderInfo` 推回 Repository——**必须聚合**，因为 `subscriptionInfo` 是 per-provider 解析 header 得来，多源 yaml 下 `values.firstOrNull()` 取到的是 Map 迭代顺序的随机 provider。该请求先取消前一次，并同时捕获 repository identity、active UUID 与递增 request ID，响应后每次写 UI 前重验三者；disconnect 或 UUID 改变时 cancel + 清空 + 使旧 ID 失效；失败仅更新错误态，不清空已确认的 live snapshot。

**Active 订阅名缓存同步**：通知栏启动时一次性读 storage `ACTIVE_PROFILE_NAME` snapshot，不订阅 DB Flow。`commitPending` 与 `updateImported` 末尾必须调 `syncActiveNameIfActive(uuid, name)`，否则编辑/更新 active 订阅时通知栏标题会停在旧名。辅助函数内部短路 active 检查 + 同名短路（避免周期性流量更新打断通知动画）；`updateImported` 调用方还需在 `name != null && name != existing.name` 时才调。

**VPN 子进程判活只能走 waitpid**：mihomo 是 app 经 `process_helper.c` 直接 fork 的**亲生子进程**，退出后进入僵尸态，而**僵尸的 `/proc/<pid>` 依然存在**——用 `/proc` 判活会让崩溃检测永不触发（`while (runner.isRunning)` 不退出，UI 停在 Running 而内核已死）。它表现为偶发只是因为 libcore 的 `ProcessManager` 收割线程可能恰好 `waitpid(-1)` 把僵尸带走，而那条线程在非 root 设备上未必起来——**判活正确与否取决于一条无关线程是否恰好存在**。故 `nativeIsAlive` 用 `waitpid(pid, WNOHANG)`：返回 0 = 活、返回 pid = 刚收割、返回 -1(ECHILD) = 已被别处收割，判活兼收割。ROOT 用 `kill -0` 不受影响（跨进程，非子进程），但每次调用都要 fork 一个 su（`/proc` 在 Android 10+ 是 hidepid），轮询它的地方要自己控频。配套三条：`waitpid` 必须包 EINTR 重试（被打断时返回 -1 而 `status` 保持 0，`WIFEXITED(0)` 会把「被打断」误报成「正常退出码 0」）；等待**必须带超时**（调用点在 `onDestroy` 主线程，mihomo 收 SIGTERM 后要关 TUN、断全部连接）；SIGTERM 等不到要升级 SIGKILL。

**mihomo.log 一律尾读**：debug 级别的长会话日志可达数十 MB，`readText().lines().takeLast(n)` 为拿几行把整个文件读进堆，在前台服务里直接 OOM。走 [readLastLines](app/src/main/kotlin/top/yukonga/mishka/service/LogTail.kt) 从尾部回读固定字节窗口；窗口起点会切在半行或半个 UTF-8 字符中间，未到文件开头就丢首行。ROOT 路径本就是 `su tail -n`。

**TUN init silent failure 兜底**：mihomo `ReCreateTun` 失败仅 log 不退出。① `MishkaTunService` 清 O_CLOEXEC 失败必须视为致命（`closeTunFd` + Error + `stopSelf`）；② `MihomoRunner.waitForReady` 在 API ready 后 delay 500ms 扫日志匹配 `Start TUN listening error` / `configure tun interface` / `create NetworkUpdateMonitor`。

**fd 模式 forwarderBindInterface 必须为 true**：upstream `e38aa82a` 在 Mishka VPN（gvisor stack + VpnService fd）下实测破坏 fd 路径流量——延迟测试通（mihomo 直接 dial 不经 fd），实际经 fd 流量不通。静态搜索 sing-tun 0.4.18 仅 `stack_system` 读该标志、gvisor 不读，但实测推翻该结论。fork 第 5 patch 保留 fd 模式下的旧行为；每次 rebase 上游必须验证 `listener/sing_tun/server.go` 的 `forwarderBindInterface = true` 仍 active——**验证的前提是新代码真进了 .so**，见 `replacedModuleSources`。

**VPN MTU 同步**：`VpnService.Builder.setMtu` 与 mihomo `cfg.Tun.MTU` 必须同值。sing-tun 在 fd 模式给 gvisor `fdbased.New` 用 `cfg.Tun.MTU` 设 endpoint 缓冲，0 时所有 read 失败 → 表象「延迟正常但流量不通」。两侧共用 `RuntimeOverrideBuilder.VPN_TUN_MTU` 常量，禁止任一边 hardcode。

**WebSocket 重连**：Ktor `for (frame in incoming)` graceful close 静默退出。`MihomoWebSocket.webSocketFlow` 自实现无限重连 + 指数退避（1s→30s）+ 20s 心跳；`CancellationException` 必须先于通用 catch 并 rethrow，吞掉它重连循环就再也停不下来。末尾 **`flowOn(Dispatchers.Default)` 不能删**——消费点全在 Main，不切走则每帧反序列化都占主线程；它引入的缓冲要求消费方除 cancel 外再做 `repository !== repo` 校验。**`emit` 必须留在解析的 try 之外**：一起包住会把下游抛的异常当成坏帧吞掉，下一次 emit 撞上 flow 异常透明性检查，最终表现成一次「服务端断了」的重连。反过来，消费侧**不要给这些流挂 `.catch`**——除取消外的一切都在流内被吞进重连循环，`.catch` 永远不会执行，只会让读者以为存在一条错误通路。`connectionState` 由四条流共享，语义是「**有任意一条**连着」，按引用计数发布——计数与发布必须一起原子（`@Synchronized`），否则并发增减会留下与实际相反的终值；握手失败的那次没计过数，`finally` 里不能无条件减。它**仍不能用来判断单条流**的死活。

**Flow.catch 是终结型操作**：`.catch` 捕获后流结束、不会重订阅。长生命周期 UI/通知 Flow 的瞬态异常（如 `notify()` 偶发 `RemoteServiceException`）应包到 `collect` 内部用 `runCatching` 处理；`.catch` 只留给真正需要终结的失败。DynamicNotificationManager 曾因顶层 `.catch` 让整条 trafficJob 永久死亡。

**startForeground 防御**：Tun/Root/ProfileWorker 的 onCreate 均 `try { startForeground() } catch(Exception)`。真实风险是 API 31+ `ForegroundServiceStartNotAllowedException` 和 API 34+ FGS type 异常（非 POST_NOTIFICATIONS 拒绝）。失败路径：Tun/Root 上报 Error + `stopSelf()`；ProfileWorker 置标记后 `stopSelf()`，之后到达的 start 一律拒收。**不降级为普通 Service**。

**ProfileWorker 收尾按 startId**：每件任务完成时 `stopSelfResult(自己的 startId)`——有更新的 start 已投递时它返回 false，那条请求由它自己的任务再试。**不能改回「延时 drain 队列 + stopSelf()」**：`poll()` 返回 null 与停止生效之间到达的 `ACTION_UPDATE_PROFILE` 入队后无人 join，`onDestroy` 的 `scope.cancel()` 直接把它掐掉，更新静默失败。计数用 `AtomicInteger`（onStartCommand 在主线程、完成回调在 IO 协程）。

**订阅自动更新闹钟是 imported 表的派生态**：唯一调度点 [ProfileUpdateScheduler](app/src/main/kotlin/top/yukonga/mishka/service/ProfileUpdateScheduler.kt)，`MishkaApplication.onCreate` 起 collector 跟 `getAllFlow()` 对账——新增订阅、改间隔立即生效，删除自动撤闹钟。**不要退回「在各增删改路径上分别调 scheduleNext」**：那样只有开机与后台更新成功后才布置，本会话新增的订阅要等下次重启才自动更新，删掉的订阅闹钟则无人撤销。开机路径 app 进程可能只为收广播而起，`ProfileReceiver` → ProfileWorker → `reconcileNow()` 借前台服务的存活窗口做一次对账。进程重启后 `armed` 为空，DB 里已不存在的孤儿闹钟无从枚举——它至多空跑一次 ProfileWorker（uuid 查不到即返回）且不会续期，不值得为此再持久化一份状态。

**打开应用时自动连接**：`AUTO_CONNECT_ON_LAUNCH`（设置 General，默认关）与开机自启是**两个独立开关**——BootReceiver 只在 `SERVICE_WAS_RUNNING=true` 时恢复，自动连接不看上次状态。实现挂在 `verifyAndSyncState` 内部而非另起入口：两者都在回答「app 打开时代理该不该跑」，拆开会与 ROOT attach 路径抢跑（`start()` 异步，attach intent 发出后 bridge 仍是 Stopped，第二个入口读到会重复发 START）。这只挡得住 app 内的入口，进程外的 BootReceiver 仍会重复投递，兜底见「Service 启动必须幂等串行」。三条约束：① **每进程只消费一次**（controller 是 Koin single），冷启动触发、回前台不触发；② 静默校验走 `startableSubscriptionId()`（无副作用版），无可用订阅时什么都不做，不能用错误 toast 打断只是打开 app 的用户；③ VPN 缺授权时 `requestVpnPermission()`，授权回调接续启动。ROOT 分支此时改走 `start()`——它同样先三重校验 attach 复用，区别只在 attach 不成时允许全新启动，而这正是开关表达的意图。

**日志列表按显示帧率发射**：日志风暴下可达数百行/秒。`appendLog` **只写 buffer + 置 `logsDirty`，不 emit**；独立 `flushJob` 每 120ms 才 `_logs.value = buffer.toPersistentList()`，把重组 + 500 条 key diff 从「日志行速率」降到「显示帧率」。**禁止**改回每行 emit。autoScroll 的 `LaunchedEffect` key 必须用 `logs.lastOrNull()?.id`（单调递增），**不能用 `logs.size`**——缓冲写满后 size 恒为 `MAX_LOGS`，跟随会永久停摆。

**错误兜底**：用户面向异常走 `Throwable.describe()`（`message ?: simpleName ?: "Unknown error"`），避免 Ktor `ConnectException()` 等无参异常漏到 UI 显示 "null"；`SubscriptionFetcher` 显式检查 `response.status.isSuccess` + 空 body 抛 typed `ImportError`。**类型化错误的 message 只写英文技术描述**（供日志），用户可见文案由 UI 层按类型映射（`SubscriptionViewModel.localizedMessage`）——data 层拿不到 locale。ViewModel 的 `error` 字段同理只存原因，「加载失败: 」这类前缀由屏幕 `stringResource` 拼。

**后台卡片隐藏**：`HIDE_TASK_CARD` 由 `MainActivity` 读取并经 `ActivityManager.AppTask.setExcludeFromRecents()` 应用，运行时切换经 callback 透传即时生效。不要写成 manifest `android:excludeFromRecents="true"`，否则失去用户可切换语义；App/屏幕暴露 callback、不直接调 Android API。当前实现依赖单 Activity task（`appTasks.firstOrNull()`）；若引入 document/multi-task 入口，必须改为按当前 `taskId` 匹配。

**Baseline Profile 只能本地真机生成**：`:baselineprofile` 采集冷启动 + 4 Tab 路径，`:app:generateReleaseBaselineProfile` 回写 `app/src/release/generated/baselineProfiles/`，**产物必须提交**——CI 只消费不生成（APK 仅 arm64-v8a 且 libmihomo.so 无 x86 产物，x86_64 模拟器装不上；GitHub ARM runner 不提供 KVM），故 `automaticGenerationDuringBuild = false`，绝不能挂到 `assembleRelease`。三条配置约束：① **`androidx.profileinstaller` 是必需依赖**——侧载分发拿不到 Play 云端 profile，没有它打进 APK 的 `baseline.prof` 不会被 ART 安装；② **必须在 `finalizeDsl` 里关掉 `nonMinifiedRelease` 的 `optimization.enable`**——插件只关旧 DSL 的 `isMinifyEnabled`，管不到 AGP 9 的新开关；不关则 generator 采集混淆后的类名、release 再按自己的 mapping 重写一遍就全部错位，构建全绿而 profile 静默失效（判据：`mapping/nonMinifiedRelease/mapping.txt` 不该存在，APK 内 `top/yukonga/mishka` 类名应有数千个）；③ **benchmark 不能降到 stable**（`1.4.1` 在 AGP 9 下 apply 即失败，需 `1.5.0-alpha07+`）。generator 里三段 workaround 都是实测根因，删任何一段都退回「Generated Profile is empty」：**`cmd package compile -f -m verify` 强制降级**（HyperOS 装包时就按 APK 内 prof AOT 成 speed-profile，benchmark 自己的 `compile --reset` 只回到这个已 AOT 状态，运行期不再 JIT）；**先 `pm grant POST_NOTIFICATIONS`**（首帧前的授权框会挡住 MainActivity，ROM 弹窗按钮没有 AOSP 的 resource-id、按文案点会随 locale 碎掉）；**`startup` 末尾等够 ART profile saver 延迟**（`-Xps-save-resolved-classes-delay-ms` 默认 5s，`startActivityAndWait()` 一返回就结束等于什么都没记）。切 Tab 走 `HorizontalPager` 横滑而非按文案定位控件，`swipe` 后 `SystemClock.sleep` 等动画收敛（Compose 动画不向 accessibility 报告 busy，`waitForIdle()` 会在切换途中返回）。收益要打折：`System.loadLibrary("mihomo")` 加载 56MB 库属 native 固定开销，Baseline Profile 只优化 Compose 首帧、Koin 图构建这类字节码路径。

**其他**：通知 id 按用途分区（1..99 固定前台通知 / 100..999 更新结果环形复用 / `0x10000+` per 订阅进度，散列 uuid 低 16 位），新增通知按区取号——不分区就会像旧的「固定 id + uuid 散列」那样跨到 Wi-Fi 监控前台通知与结果通知头上，特定 uuid 把别人的通知覆盖再取消掉；四个 `specialUse` 前台服务都要带 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`（分发审核会看）；`Activity configChanges=uiMode` 防深浅色切换重建；预测性返回走 HiddenApiBypass 反射 `setEnableOnBackInvokedCallback`；`network_security_config.xml` 全局 `cleartextTrafficPermitted=true`（订阅源常用 HTTP；CMFA 因 fetch 在 Go 侧绕过 Java 网络栈而无需此设置，Mishka 的 Ktor 走 OkHttp 必须显式放行）；`jniLibs.useLegacyPackaging = true` 让 libmihomo.so 解压到 nativeLibraryDir，同时在 APK 内保持压缩存储（实测 59 MB → 19.3 MB），是净收益而非体积代价。

## ROOT 模式约束

全部在 [docs/root-mode.md](docs/root-mode.md)：iptables 锁争用与 `-w`、TPROXY 的 IPv6 门控、`runtime/` 沙箱、`su -c` 转义、孤儿进程清理、attach 三重校验与 boot-session 门控、热点处置的两种模式与规则集、ROOT 下不做动态通知。**改 ROOT / iptables / `su` 相关代码前先读它**——那边每条都是内核或权限层面的坑，违反了不报错，只表现为「连不上」或「规则残留」。

## UI 规范

全部在 [docs/ui-guidelines.md](docs/ui-guidelines.md)：miuix 组件用法、页面骨架与毛玻璃、宽屏与刘海适配、卡片拆 lazy item、ProxyScreen 两条动画约束、Dialog / BottomSheet、语义色 token、Compose 状态形状与帧率级读取。**改 `ui/` 下任何文件前先读它**——其中十余条属于「不读就会写错、写错了编译器不报错」。
