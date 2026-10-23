<div align="center">

# Mishka

**基于 [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) 和 [mihomo](https://github.com/MetaCubeX/mihomo) 的 Android 代理客户端**

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![minSdk](https://img.shields.io/badge/minSdk-31-3DDC84?logo=android&logoColor=white)](#)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)

</div>

---

## 简介

采用 [mihomo](https://github.com/MetaCubeX/mihomo)（Clash.Meta）内核的 Android 代理客户端，UI 使用 [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) + Jetpack Compose 构建，数据层 Room + Ktor + Koin。内核以子进程 + JNI 双通道集成，支持 **VPN**、**ROOT TUN**、**ROOT TPROXY** 三种隧道模式

## 特性

**代理与内核**

- 内置 [mihomo](https://github.com/MetaCubeX/mihomo) 内核（Mishka fork），统一为单个 `libmihomo.so` 同时承担 runtime 入口与订阅导入 JNI 门面
- 三种隧道模式，随时切换：
  - **VPN** —— 无需 Root，基于系统 `VpnService`；IPv4/IPv6 地址与核心同步，独立系统 HTTP 代理不受订阅端口和认证影响，支持按包名进程规则
  - **ROOT TUN** —— Root 自建 TUN，`auto-route` + 大包 GSO 聚合
  - **ROOT TPROXY** —— 内核态透明代理（iptables + fwmark），性能接近直连
- **分应用代理** —— 白/黑名单，三模式各自走 VpnService / `include-package` / iptables uid-owner；非“允许全部应用”模式支持下拉刷新已安装应用列表，并按当前勾选项优先重排
- VPN“仅允许选中应用”需至少一个已安装且不是 Mishka 的应用；空名单拒绝启动，不会退化为全应用代理
- **热点流量处置**（ROOT）—— 支持绕过代理或透明代理（TPROXY）两种模式
- **系统 DNS 同步** —— 启动时初始化转发子进程；监听非 VPN 网络的 DNS 变化，同步更新 JNI 与运行时核心并刷新缓存，保留 IPv6 link-local DNS 的数字 scope ID
- Android 跨用户/工作资料共用 loopback 入口时，可通过 ADB 显式授予 `android.permission.INTERACT_ACROSS_USERS`；普通单用户使用无需该权限

**订阅管理**

- 支持 URL / 本地文件 / 二维码扫码导入订阅
- 三阶段沙箱式导入流程（Pending → Processing → Imported），可取消、可回滚
- **age 加密订阅** —— per-profile 密钥，加密原样落盘、运行时解密；内置 X25519 / 抗量子密钥对生成
- **per-profile User-Agent** 覆写，订阅下载可选走本机代理
- 自动更新（AlarmManager 调度）+ 手动全部更新

**界面与体验**

- 平滑圆角 + 高级材质底栏/顶栏（或液态玻璃底栏）
- 深浅色模式 + 跟随系统 + Monet 动态取色
- 宽屏适配（NavigationRail 侧边栏）
- 内置 YAML 配置编辑器（[scripta](https://github.com/YuKongA/scripta)）
- 实时流量、连接、日志、DNS 查询、Provider 管理
- 动态流量通知（VPN 模式）+ Quick Settings 磁贴一键启停
- **Wi-Fi 自动切换** —— 匹配指定 SSID 时自动停止代理或切换 Direct 模式
- 开机自启、隐藏最近任务卡片等实用开关

## 自动化

Tasker、快捷方式、adb 等外部工具可经 intent 启停代理，无需打开 Mishka。

**默认关闭**：需在 设置 → 自动化 打开「允许外部应用控制」，否则下面这些 intent 一律静默返回 `RESULT_CANCELED`（不弹提示，避免被调用方轮询时反复打扰）。开关只作用于四个导出 action —— 磁贴、通知等用户主动操作的入口不经过它，关掉也不受影响。

- **启动 / 停止 / 切换 / 重启代理** —— 向 `ExternalControlActivity` 发送显式 intent，action 取以下之一：
  - `top.yukonga.mishka.action.START_PROXY`
  - `top.yukonga.mishka.action.STOP_PROXY`
  - `top.yukonga.mishka.action.TOGGLE_PROXY`
  - `top.yukonga.mishka.action.RESTART_PROXY` —— 仅在代理运行中生效（与界面上的重启按钮一致）；未运行时只提示、不顺手启动

```bash
adb shell am start -a top.yukonga.mishka.action.TOGGLE_PROXY \
    -n top.yukonga.mishka/.ExternalControlActivity
```

跳板不显示界面，也不会把 Mishka 的界面带到前台。是否已在运行、订阅是否可用等判定与界面按钮完全一致。

## 构建

### 环境要求

- JDK 21
- Android SDK + NDK（含 clang），SDK 版本见 `build-logic/src/main/kotlin/ProjectConfig.kt`
- Go（版本见 `mihomo/go.mod`）
- Git（用于 submodule 与版本号生成）

### 步骤

```bash
# 1. 克隆仓库
git clone https://github.com/YuKongA/Mishka.git
cd Mishka

# 2. 初始化子模块（mihomo fork + scripta 编辑器）
git submodule update --init --recursive

# 3. 首次构建前下载 GeoIP 数据（预置进 assets）
./gradlew :app:downloadGeoFiles

# 4. 构建 APK（assemble 自动触发 mihomo cgo 交叉编译 + CMake）
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

# 5. 只改 Kotlin 时可跳过 Go 构建（秒级验证）
./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a
```

> `mihomo` 通过 git submodule 引入 [YuKongA/mihomo](https://github.com/YuKongA/mihomo) 的 `Mishka` 分支（含 5 个针对 Android fd/TUN 的 patch）。Gradle 会自动驱动 Go 交叉编译，产物位于 `app/src/main/jniLibs/<ABI>/`

## 致谢

- [mihomo](https://github.com/MetaCubeX/mihomo) —— 代理核心
- [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) —— UI 组件库
- [sparkle](https://github.com/xishang0128/sparkle) / [Clash Meta for Android](https://github.com/MetaCubeX/ClashMetaForAndroid) —— 实现参考
- [scripta](https://github.com/YuKongA/scripta) —— 代码编辑器

## 许可证

本项目以 [GPL-3.0](LICENSE) 许可证开源；与内核 [mihomo](https://github.com/MetaCubeX/mihomo) 一致
