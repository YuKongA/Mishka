# Mishka ROOT 模式约束

只在 ROOT 路径（`MishkaRootService` / `RootHelper` / `RootTetherHijacker` / `RootTproxyApplier` /
`MihomoRunner` 的 root 分支）上生效的约束。**改这些文件、写任何 `su -c` 命令、动 iptables 或
ip rule 之前先读本文件**；三模式共有的启动链、override 注入、状态桥接等仍在
[AGENTS.md](../AGENTS.md)。
**iptables 锁争用 ≠ 内核不支持**：所有 iptables/ip6tables 调用必须带 **`-w <秒>`**——裸 `-w` 是无限等待，撞锁会让启动链永久挂起。秒数已并进工具令牌（`IPT4`/`IPT6`/`IPT_BINS`），新增命令一律用令牌拼；`runWithOutput` 自动注入，只认带数字的 `-w`，超时须放宽到 `IPT_WAIT_SECONDS + 5`，否则等锁那几秒会被当成 su 错误。抢不到 xtables.lock 时退出码是 **4**（resource problem），与「内核没有该 target」在返回码上无法区分，故 `probeTproxySupport()` 必须：等锁 → 撞锁退避重试 → 重试耗尽仍是锁问题时**返回 true**，把结论交给真正的 apply 验证。误判「支持」最多让 apply 装不上规则并留日志，误判「不支持」会让 ROOT TPROXY 直接中止启动。

**ROOT TPROXY 的 IPv6 数据注入必须门控**：`RootTproxyApplier.apply(ipv6Enabled)` 由 `VPN_ALLOW_IPV6` 决定。关闭时普通 IPv6 数据仍走内核原生路由，不能把 mihomo 无法处理的 IPv6 数据强行送入 TPROXY，造成重试风暴；teardown 清 v4+v6。**这不适用于 DNS 控制面**：系统仍可能通过 IPv6 DNS 服务器查询 A 记录，必须由下述共享 DNS 规则独立接管。

**`xt_addrtype` 缺失不得阻断 ROOT TPROXY**：Android GKI 与模拟器内核常未编译或加载 `xt_addrtype.ko`。`IptablesIntranet` 已完整覆盖 loopback（`127.0.0.0/8` / `::1/128`）、私网（`10/8`、`172.16/12`、`192.168/16`）、链路本地、组播与广播地址；`RootTproxyApplier` 对 `-m addrtype --dst-type LOCAL/BROADCAST` 的注入必须容错（`2>/dev/null || true`），不得让可选的地址类型扩展在严格 `set -e` 下触发启动回滚。

**ROOT DNS 是独立控制面**：`RootDnsApplier` 在两个 ROOT 子模式接管 TCP/UDP 53，使用 bit 25 (`0x02000000/0x02000000`)、table 2025、priority 7998 和 `local default dev lo`，优先于私网绕过及数据 TPROXY；不依赖 IPv6 NAT 或 TPROXY。本机 DNS 依赖 IPv4 `nat` 表做 DNAT，启动前必须预检；原生 `--root-dns` 在 loopback 上监听 `--root-dns-port`（Kotlin 启动前动态选择、持久化并传入，attach 复用同一端口），本机被 mark 的 DNS 在 `nat OUTPUT` 被 DNAT 到该端口；热点网关的 53 留给 Android tethering 的 dnsmasq，否则 dnsmasq 无法启动、SoftAp 会被 Tethering 关闭。IPv6 内核没有 `ip6_tables` nat 表时只跳过 IPv6 DNAT，不阻断 IPv4 Root DNS 启动。核心按独立 UID/GID 身份豁免，Mishka 按 app UID 豁免，避免上游 DNS 回环。**先安装 INPUT 保护，再启动 listener**；只放行 lo 与配置的热点接口，BYPASS 客户端也必须能查询网关 DNS，但其转发 DNS 不打代理标记。入口规则用完整临时链先挂后换，捕获安装失败时保留入口保护，只有 listener 停止后才完整拆除，避免 app 被杀留下开放 DNS 服务器。加密的 Private DNS/DoH 不属于明文 53 接管范围，不擅自修改系统设置。

**DNS 自绕不能豁免整个 UID 0**：Android `netd` 的 DNS socket 也属于 `0:0`，且可能带 `net_admin` 附加组；整 UID 放行会让普通应用与系统联网探测继续使用外部错误答案，出现 HTTP 204 成功但 HTTPS 握手失败、网络只有 PARTIAL_CONNECTIVITY。核心通过 `su -g 3005` 以 `0:3005` 启动，DNS OUTPUT 只同时匹配 UID 0 与主 GID 3005，不匹配 supplementary groups；Mishka UID 仍独立排除。start/attach 验证真实 UID/GID，不能把旧 `0:0` 核心接到新规则上，否则核心上游 DNS 会被重定向回自己。

**ROOT 系统 DNS 不返回 fake-IP**：Android 系统 DNS 缓存跨应用共享，排除应用与 Mishka 自身可能拿到代理应用缓存的 fake-IP，但它们的数据路由明确绕过 TUN/TPROXY。两个 ROOT 模式统一 `redir-host`，保留订阅上游与策略；`dns.ipv6` 服从实际 IPv6 接管开关，不能在没有 IPv6 数据接管时返回 fake-IPv6。**真实地址模式必须在解析后的 runtime Option 设置**：解析前覆盖 enhanced-mode 会跳过旧 fake-IP 反查池的加载，VPN 切 ROOT 后仍缓存旧地址的连接便无法还原域名。DNS 未启用的订阅也先走核心既有默认上游补齐，再强制真实地址映射及关闭普通 DNS listener，不能提前强制 enable 导致 nameserver 为空而校验失败。当前池之外的错误缓存无法凭空还原，需由客户端刷新。

**AAAA 依据实际接管能力**：核心可能因 IPv6 可用性检查清掉 TUN 的 IPv6 地址，即使用户开关与顶层 ipv6 仍为 true。ROOT TUN 的 runtime Option 必须检查解析后的 `Tun.Inet6Address`，为空时关闭 DNS AAAA；ROOT TPROXY 没有 TUN，不得套用此条件。验证需包含 Android 系统 resolver 与 NetworkMonitor 的 HTTPS 204，不能只用纯 Go DNS client 或核心测速证明系统网络可用。

**DNS 就绪与清理属于启动合同**：controller 鉴权内的 `GET /dns/root` 仅在 UDP/TCP listener 和当前 resolver 均可用时返回 204；ROOT start/attach 都需通过。规则安装逐项校验，失败回滚、停止核心并发布 Error；缺少内核匹配能力不能继续假报 Running。stop/restart/核心死亡/失败 attach/切 VPN 均撤销共享 DNS 规则，清理失败保留 Error。attach 始终完整重建 DNS 与 TPROXY 规则，不能以单个存活锚点判断完整。原生停止先取消 resolver 并关闭所有已接受 TCP 连接，再等待 DNS handler 收敛；只关闭 listener 或只设读 deadline 无法解除慢客户端造成的写阻塞。

**ROOT runtime/ 沙箱**：ROOT mihomo 工作目录是独立 `runtime/{uuid}/`（从 imported/ 复制），不以 root 直写 imported/。停机、重启、进程死亡、切回 VPN，以及下一次新鲜启动准备沙箱之前，由 [RootRuntimeCache](../app/src/main/kotlin/top/yukonga/mishka/service/RootRuntimeCache.kt) 把 provider 缓存以 app UID 回写 imported/ 再删 runtime/。存量旧 root:root 遗孤由 `MishkaApplication` 后台线程一次性 `su chown -R $APP_UID imported/` 迁移。

- **回写什么**：目录里实际落下的普通文件（含脚本新增的 path 与 `cache.db`），不重跑覆写脚本，不删 imported/ 里不再引用的旧文件——与 VPN 直接写在 imported/ 的结果一致。工作目录根上的 `config.yaml`、日志、geodata 不回写，子目录里的同名文件要回写；符号链接（含祖先）不回写。`config.yaml` 与启动时不一致则整单跳过，避免盖掉运行期间的新提交。
- **怎么写**：保留缓存文件与订阅根目录的 mtime（后者是界面 updatedAt 的来源，恢复时不跟随符号链接）；文件和新建目录的 SELinux 标签改成与 `imported/{uuid}/` 相同，否则 app 拷不回下一轮沙箱。toybox `chcon` / `restorecon` 为何只能「读出上下文再 `chcon CONTEXT`」见 `RootHelper.syncRegularFiles` 的 KDoc。属主或标签没改成 app 能用的文件不发布、保留旧缓存，其它文件照常；SELinux 关闭且读不到上下文时只 chown。
- **锁**：processLock → 沙箱锁 → profileLock。回写、`deleteProfileDirs` 删 runtime/、prepare 共用沙箱锁；删除订阅从主线程协程进来，等锁必须挂起，不能 `runBlocking`。`releaseAll` 在 runtime/ 为空时不取锁直接返回——每次 ROOT 新鲜启动与 root 设备上的每次 VPN 启动都会调它，而订阅下载会占 processLock 几分钟。
- **启停钩子**：新鲜启动杀进程后 `releaseAll` 再 `prepare`；stop 与进程监控先发布终态（Stopped / Error）并 `stopSelf`，再 `release`，没写完被回收由下次启动的 `releaseAll` 补；restart 先 `release` 再启动；attach 分支**不重建、不回写**。
- **代次**：清理只认启动时记下的沙箱代次，已被新 prepare 或删除订阅取代就不再 `rm -rf`，否则会删掉刚建好的沙箱。`release` 等锁阶段可取消，实际回写和清理在 `NonCancellable` 里；stop/restart 取消还在等锁的那次并自己接手，接手后的回写挂在 `cleanupScope` 而非 Service scope，`onDestroy` 只取消还在轮询的监控。stop/restart 在取消启动协程前记下代次，被取消的启动若已 prepare，按启动 job 认领它那份沙箱（订阅 ID 可能还没持久化）。

**任何进入 `su -c` 的外部值必须 `escapeShellSingleQuoted`**：双引号挡不住 `$(...)`。`--secret` 来自远端 config.yaml 行扫描、`--age-secret-key` 用户手填、device name 只 trim，上游均无字符校验，转义是唯一防线。启动日志只打 flag 名，密钥不进 logcat。

**孤儿 mihomo 清理**：`RootHelper.cleanupOrphanedMihomo(tunDevice)` 单次 su shell 完成 pkill + `ip link delete <tunDevice>`（防 sing-tun EEXIST）。VPN 启动在 `hadRootPid || HAS_ROOT` 时触发，随后撤销热点、TPROXY 和共享 DNS 规则，清 ROOT 持久化 key + 兜底 `RootRuntimeCache.releaseAll`。失败的 attach-only 同样撤销失效规则，不得留下指向已退出 DNS listener 的路由。

**ROOT 模式重连校验**：`attachToExisting` 验证 PID 存活、`/proc/$pid/cmdline` 属于 libmihomo_runner.so、实际身份为 `0:3005`、stored secret 通过 `/configs` 鉴权且 `/dns/root` 返回 204；订阅一致性由 `startProxy` 在 attach 前比对 persisted vs 请求 subscriptionId，不一致走 cleanup + 全新启动。

**reopen 重连必须 attach-only + boot-session 门控**：app reopen（`onResume` → `verifyAndSyncState`）在 ROOT 模式走 `reattachRoot()` 发带 `EXTRA_ATTACH_ONLY=true` 的 START intent；`startProxy(attachOnly=true)` 在 attach 失败时**绝不回退全新启动**，而是清状态置 Stopped + `stopSelf`。`SERVICE_WAS_RUNNING` / `ROOT_MIHOMO_PID` 存在 SharedPreferences 跨重启保留，但重启会杀死 root mihomo → PID 过期；只凭「PID 非空」判活会造成「未开开机自启，重启后打开 app 却看到 ROOT 代理自动跑起来」。两层防护：① `verifyAndSyncState` 经 [BootSession](app/src/main/kotlin/top/yukonga/mishka/platform/BootSession.kt) 预门控（重启过 ⇒ 进程必死 ⇒ 清标志保持停止）；② attach-only 兜底覆盖预门控漏判的边界。**判据是 `Settings.Global.BOOT_COUNT`**（公开 API、免权限、每次开机递增）：精确、无时间窗口、不受时钟调整影响。**不要改回比较 `elapsedRealtime`**——那只有「重启后经过时间 < 上次启动代理时的 uptime」才识别得出重启，代理若在开机 30s 时启动，之后重启过了 30s 再打开 app 就漏判；也别用 `currentTimeMillis - elapsedRealtime` 推算开机时刻，NTP 校时会让它跳变。**宁可漏判不可误判**：漏判只多走一次 attach 尝试、由三重校验挡下过期 PID；误判会清掉仍有效的 PID，让活着的 mihomo 变成孤儿而 UI 显示未运行——故取不到 BOOT_COUNT 一律按「没重启」处理。`ROOT_BOOT_COUNT` 是运行时态，必须在 `BackupManager.EXCLUDED_PREF_KEYS` 里排除。**boot-start 例外**：BootReceiver 走 `start()`（全新启动）不受影响，若与 `reattachRoot()` 撞上，由 AGENTS.md「唯一例外是 fresh START 抢占进行中的 attach-only」裁决（fresh 赢）；「绝不回退」约束的是 attach-only 请求**自身**。

**ROOT 模式热点处置**：sing-tun `auto_route` 的 catch-all ip rule（priority 9002）不区分本机 vs 转发流量，热点客户端包 iif=wlan2/ap0 也命中被导进 TUN，但 mihomo 对非本机源 IP 处理不稳。`RootTetherHijacker` 在 sing-tun 之前插队两种处置：

- **BYPASS（默认）**：`ip rule priority 8000/8002` 去程 + 回程均 action=`goto 9010`（sing-tun 自己的 nop marker）。去程越过 catch-all 后命中 Android 原生 iif forward rule → 走 wlan0/rmnet；回程 goto 过去命中 local_network/main 里的连接路由。
- **PROXY**：热点 TCP/UDP 数据通过 `mangle PREROUTING -i <tether> -j mishka_tether` 劫持到 mihomo 的 7895 TPROXY listener，绕开 sing-tun userspace TCP stack。bit 24 / table 2024 / priority 7999 的策略路由必须配合 `local default dev lo`，否则带 mark 的包无法本机投递。链内保留 INVALID 丢弃、LAN 放行、socket DIVERT 与新流 TPROXY；DNS 则由更早的共享 DNS hook 独立接管。
- **PROXY 降级**：`xt_TPROXY` 不可用时退回「去程+回程对称 `lookup 2022`」（双向进 sing-tun，性能次于 TPROXY）。`probeTproxySupport()` runtime 探测，结果驱动 `buildAndWriteForRun(tproxyForTether = ...)` 决定是否写 `tproxy-port`；`ROOT_TPROXY_KERNEL_CAPABLE` 存探测结果，RootSettingsScreen 据此显示降级告警 Card，让用户明确感知而非静默 fallback。
- **attach 路径约束**：mihomo 启动时 tproxy-port 是否监听已锁死，app 被杀期间用户若改过 tether mode，规则会与实际 listener 错位。`ROOT_TETHER_MODE_ACTIVE` 存启动快照，attach 前比对不一致则拒绝 attach。热点数据规则仍按 `anyRulesPresent()` 条件重建，`ROOT_ATTACH_FORCE_REAPPLY=true` 可强制重建；共享 DNS 和本机 TPROXY 则始终完整重建并校验，不能凭单个锚点跳过。
- **接口识别（纯手填）**：用户在 RootSettingsScreen 填 CSV（`ROOT_TETHER_IFACES`，默认 `wlan1,wlan2`），编辑对话框提供「检测当前接口」扫描按钮（`NetworkInterface` 列 UP + 有 site-local IPv4 + 不在蜂窝/隧道黑名单的候选，默认排除 `wlan0`）。不做实时自动发现——`TETHER_STATE_CHANGED` extras 在 Android 11+ 不可靠（@hide），regex 白名单又覆盖不全 OEM 命名。
- **不能**用 `lookup main`——Android main 表无 default route。回程规则靠 `NetworkInterface.getByName` 读 InterfaceAddress + prefix 算 CIDR；接口未就绪（热点后开）会 WARN skip，需 restart 代理重新 apply。`ip rule add iif <name>` 对不存在接口按名注册、接口出现自动生效，子网规则则需 apply 时已有地址。start/attach 后装配，stop/restart/死亡时在 NonCancellable 中清理；本节只描述 ROOT TUN 的热点数据处置，本机 ROOT TPROXY 数据由 `RootTproxyApplier` 独立管理，共享 DNS 不依赖该能力。
- **一切 root 批量操作走单次 su**：Magisk 下每次 `su` 约 30~100ms，全压在启停关键路径上。`teardown`（ip rule 删到失败为止 + `-S` 转 `-D`）、`verifyClean`、`dumpState`、apply 的 ~60 条命令都折成一次调用——**数据依赖的两步操作也要留在 shell 里做**（`iptables -S PREROUTING | grep | sed 's/^-A/-D/' | while read`），不要把中间结果拉回 Kotlin 再发第二轮。多条查询用 `echo '--- <key>'` 分段后在 Kotlin 侧切回。同理 `MihomoRunner.waitForReady` 的 ROOT 判活降到 4 轮一次（2s）：判活必须 fork su，而 API 探测是本地 HTTP、不花钱。

**ROOT 模式不做动态通知**：`DynamicNotificationManager.startOrFallbackStatic` 在 `tunMode != Vpn` 时强制走静态分支，忽略用户 `DYNAMIC_NOTIFICATION` 偏好；设置开关在 ROOT 模式下 `enabled=false` + 副标题说明。VPN 靠 `BIND_VPN_SERVICE` 隐式让进程进入 `BOUND_FOREGROUND_SERVICE` 自动保 CPU；ROOT 无任何系统 binding，Activity 进后台后整个 device 进 idle，1Hz `/traffic` WS 帧合并、`notify()` 批处理，动态通知冻结。`PARTIAL_WAKE_LOCK` 实测被系统 DISABLED 救不了（只阻 SoC suspend 不阻 CPU idle）；唯一根治是引导用户 `IGNORE_BATTERY_OPTIMIZATIONS`，权衡后选择承认平台限制。ROOT TUN 看似能工作只是因为 mihomo 持续 `read(tun_fd)` 顺手撑住 device 不进 idle，不可靠且不一致。
