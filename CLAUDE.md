# Mishka

Compose Multiplatform + miuix + mihomo 跨平台代理客户端，首先完整支持 Android。

## 技术栈

| 组件                  | 版本          | 用途                     |
| --------------------- | ------------- | ------------------------ |
| Kotlin                | 2.3.20        | 语言                     |
| AGP                   | 9.1.1         | Android 构建             |
| KSP                   | 2.3.6         | 注解处理（Room）         |
| Compose Multiplatform | 1.10.3        | 跨平台 UI 框架           |
| miuix                 | 0.9.0         | UI 组件库 + 导航         |
| androidx.navigation3  | 1.1.0         | 类型安全路由             |
| Room                  | 3.0.0-alpha03 | 跨平台数据库（KMP）      |
| Ktor                  | 3.4.2         | HTTP/WebSocket 客户端    |
| kotlinx-serialization | 1.11.0        | JSON 序列化              |
| kotlinx-coroutines    | 1.10.2        | 异步/并发                |
| androidx.lifecycle    | 2.10.0        | ViewModel                |
| quickie               | 1.11.0        | QR Code 扫描             |
| mihomo                | v1.19.23      | 代理核心（预编译二进制） |

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
│   │   │   └── repository/           MihomoRepository + SubscriptionRepository + SubscriptionFetcher + ConfigProcessor
│   │   ├── platform/                 9 个 expect 声明 + ProfileFileManager 接口
│   │   ├── ui/
│   │   │   ├── navigation/           AppNavigation（主导航树 + HorizontalPager）
│   │   │   ├── navigation3/          Route（13 路由）+ Navigator（自定义栈）
│   │   │   ├── component/            SearchBar + SearchStatus + MenuPositionProvider
│   │   │   └── screen/               16 个页面（home/ proxy/ subscription/ settings/ log/ provider/ dns/ connection/）
│   │   ├── viewmodel/                10 个 ViewModel
│   │   └── util/                     FormatUtils
│   ├── androidMain/                  actual 实现 + AppDatabaseBuilder
│   └── desktopMain/                  actual 桩实现 + AppDatabaseBuilder
├── android/src/main/
│   ├── kotlin/.../mishka/
│   │   ├── MainActivity.kt           应用入口
│   │   ├── MishkaApplication.kt      全局初始化（通知渠道）
│   │   └── service/                  12 个服务组件
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
- **状态桥接**：ProxyServiceBridge（全局 StateFlow），TunService 写入、ViewModel 读取
- **进程模型**：单进程（VpnService 和 UI 同进程）
- **数据持久化**：Room 3.0 KMP（结构化数据）+ PlatformStorage（简单偏好设置）
- **订阅管理**：Pending/Imported 两阶段编辑模型（对齐 CMFA ProfileManager）
- **配置校验**：mihomo -t 进程完整校验（ProcessBuilder，不需要 TUN fd）

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
├── imported/{uuid}/            已验证的稳定配置
│   ├── config.yaml
│   └── providers/
├── pending/{uuid}/             编辑中的草稿
│   ├── config.yaml
│   └── providers/
└── processing/                 临时校验沙箱
```

## 路由清单

`Route.kt` 中定义 13 个路由，均实现 `NavKey`：

| 路由               | 类型        | 页面                          | 入口            |
| ------------------ | ----------- | ----------------------------- | --------------- |
| Main               | data object | 主页（HorizontalPager 4 Tab） | 根路由          |
| Subscription       | data object | SubscriptionScreen            | 主页 Tab 2 导航 |
| SubscriptionAdd    | data object | SubscriptionAddScreen         | 订阅页          |
| SubscriptionAddUrl | data object | SubscriptionAddUrlScreen      | 添加订阅页      |
| SubscriptionEdit   | data class  | SubscriptionEditScreen        | 订阅项编辑按钮  |
| Log                | data object | LogScreen                     | QuickEntries    |
| Provider           | data object | ProviderScreen                | QuickEntries    |
| DnsQuery           | data object | DnsQueryScreen                | QuickEntries    |
| Connection         | data object | ConnectionScreen              | QuickEntries    |
| NetworkSettings    | data object | NetworkSettingsScreen         | 设置页          |
| MetaSettings       | data object | MetaSettingsScreen            | 设置页          |
| AppProxy           | data object | AppProxyScreen                | 设置页          |
| About              | data object | AboutScreen                   | 设置页          |

## 页面与 ViewModel

| Screen                       | ViewModel             | 说明                                        |
| ---------------------------- | --------------------- | ------------------------------------------- |
| HomeScreen（7 个子 Section） | HomeViewModel         | 主页：状态/流量/网速/延迟/快速入口          |
| ProxyScreen                  | ProxyViewModel        | 代理组 Tab + 节点选择 + 延迟测试 + 选择记忆 |
| SubscriptionScreen           | SubscriptionViewModel | 订阅列表 + 增删改 + 全部更新 + 编辑 + 复制  |
| SubscriptionEditScreen       | SubscriptionViewModel | 编辑名称/URL/更新间隔                       |
| SettingsScreen               | SettingsViewModel     | 设置入口页                                  |
| LogScreen                    | LogViewModel          | 实时日志流 + 级别过滤                       |
| ConnectionScreen             | ConnectionViewModel   | 活跃连接列表 + 关闭                         |
| ProviderScreen               | ProviderViewModel     | Provider 列表 + 刷新                        |
| DnsQueryScreen               | DnsQueryViewModel     | DNS 查询（A/AAAA/CNAME/MX/TXT/NS）          |
| AppProxyScreen               | AppProxyViewModel     | 应用代理白/黑名单                           |
| NetworkSettingsScreen        | OverrideSettingsVM    | 端口/局域网/IPv6 设置                       |
| MetaSettingsScreen           | OverrideSettingsVM    | 统一延迟/Geodata/TCP 并发/嗅探器            |
| AboutScreen                  | —                     | 版本信息                                    |
| SubscriptionAddScreen        | —                     | 添加方式选择（文件/URL/QR Code）            |
| SubscriptionAddUrlScreen     | SubscriptionViewModel | URL 导入订阅                                |

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

| 组件                      | 用途                                                 |
| ------------------------- | ---------------------------------------------------- |
| MishkaTunService          | VpnService + JNI fork+exec 启动 mihomo               |
| MishkaTileService         | Quick Settings Tile 一键启停代理                     |
| BootReceiver              | 开机自启（默认 disabled，动态启用）                  |
| ConfigGenerator           | 运行配置生成（writeRunConfig + YAML 行过滤）         |
| ProfileFileOps            | 订阅文件操作（imported/pending/processing 目录管理） |
| AndroidProfileFileManager | ProfileFileManager 接口的 Android 实现               |
| MihomoRunner              | mihomo 进程生命周期管理（JNI fork+exec）             |
| MihomoValidator           | mihomo -t 配置校验（ProcessBuilder）                 |
| ProcessHelper             | JNI 包装（nativeForkExec/nativeKill/nativeWaitpid）  |
| NotificationHelper        | 三层通知渠道（VPN/更新进度/更新结果）                |
| ProfileReceiver           | AlarmManager 调度自动更新                            |
| ProfileWorker             | 前台服务执行后台配置更新                             |

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

- mihomo 二进制放在 jniLibs 中（命名为 libmihomo.so），通过 `jniLibs.useLegacyPackaging = true` 确保解压到 nativeLibraryDir
- Android 的 ProcessBuilder 会 fork 后关闭所有非标准 fd，需用 JNI fork+exec 绕过（`process_helper.c`），保留 VPN TUN fd 继承
- 配置校验使用 `mihomo -t -d <workDir>`（ProcessBuilder，不需要 TUN fd），解析 level=error/fatal 提取错误信息
- ConfigGenerator 使用行过滤合并 YAML（移除 external-controller/secret 后追加），避免引入 YAML 解析库
- 订阅管理采用 Pending/Imported 两阶段模型（对齐 CMFA），每个订阅有独立目录（imported/{uuid}/），mihomo -d 指向此处
- Room 3.0 KMP 跨平台数据库，BundledSQLiteDriver 统一 Android/Desktop，DCL 单例
- 代理组选择通过 SelectionEntity 持久化，切换订阅时自动恢复 Selector 类型组的选择
- 后台自动更新通过 AlarmManager + ProfileReceiver + ProfileWorker 前台服务实现，最小间隔 15 分钟
- network_security_config.xml 允许 localhost 明文通信（mihomo API 用 HTTP）

## UI 规范

- 所有 UI 组件使用 miuix（Card、TopAppBar、NavigationBar、SmallTitle、TextButton 等）
- 返回按钮使用 MiuixIcons.Back
- Card 间距：水平 12.dp，垂直 12.dp
- 首页状态卡片参考 KernelSU（CheckCircleOutline 170dp，offset(38,45)）
- 深色模式：StatusCard 背景 #1A3825，按钮使用 isDark 条件色
- 底栏图标：Sidebar / Tune / Settings
- LazyColumn 必须加 `.scrollEndHaptic().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)`
- Badge：`clip(miuixShape(3.dp))` + 9.sp Bold Monospace
- 操作 IconButton：`minHeight/minWidth = 35.dp, backgroundColor = secondaryContainer`
