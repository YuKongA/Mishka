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
  - **VPN** —— 无需 Root，基于系统 `VpnService`
  - **ROOT TUN** —— Root 自建 TUN，`auto-route` + 大包 GSO 聚合
  - **ROOT TPROXY** —— 内核态透明代理（iptables + fwmark），性能接近直连
- **分应用代理** —— 白/黑名单，三模式各自走 VpnService / `include-package` / iptables uid-owner
- **热点流量处置**（ROOT）—— 支持绕过代理或透明代理（TPROXY）两种模式

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

外部工具（Tasker、自动化 App、快捷方式、adb）可经 intent 直接控制代理，无需打开 Mishka。向 `ExternalControlActivity` 发送以下 action 之一：

- `top.yukonga.mishka.action.START_PROXY` —— 启动代理
- `top.yukonga.mishka.action.STOP_PROXY` —— 停止代理
- `top.yukonga.mishka.action.TOGGLE_PROXY` —— 在启动 / 停止之间切换
- `top.yukonga.mishka.action.RESTART_PROXY` —— 重启代理

```bash
adb shell am start -a top.yukonga.mishka.action.TOGGLE_PROXY \
    -n top.yukonga.mishka/.ExternalControlActivity
```

入口是一个透明、不显示的 Activity：执行完动作立即结束，**不会把 Mishka 的界面带到前台**，也不影响你正在使用的应用。是否已在运行、订阅是否可用等判定与界面按钮完全一致（已在运行时再发 `START_PROXY` 不会重复启动）。

`RESTART_PROXY` 与界面上的重启按钮同条件：代理运行时才生效，未运行时只提示、不会顺手启动。

订阅也可经 URL Scheme 导入：

- `clash://install-config?url=<encoded URI>`
- `clashmeta://install-config?url=<encoded URI>`

可选参数：`name`（订阅名称）、`update-interval`（自动更新间隔，单位分钟）。

## 构建

### 环境要求

- JDK 21
- Android SDK + NDK（含 clang），SDK 版本见 `buildSrc/ProjectConfig.kt`
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
