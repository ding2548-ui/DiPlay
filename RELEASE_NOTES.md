# DiPlay 0.2.12 · PSA 车机版

Android 7.1.2（API 25）/ Qualcomm msm8953 车机上的 CarPlay 接收端。
**本页是当前整体状态页，每次发布前更新。**

---

## ✅ 可用

- **无线 CarPlay**（外部 Wi-Fi 方案）：iAP2 全链路正常，出画面、可交互。
- **有线 CarPlay 的 USB / iAP2 控制通道**：设备发现、重枚举、配对、NCM 数据通路、
  MFi 认证、`0x4300` / `0x4301` 全部通过。
- **有线 CarPlay 出画面**：lwIP 与 VPN **两条通路都能进界面并出画面**（192 报告实测
  `Video: first frame rendered`，`shown=24.6fps` / `26.0fps`，音频 `audioType=media` 也通了）。
- **界面汉化**、零跑档位识别、方向盘按键、倒车暂停、iOS 27 视频车内播放（N 挡门控）。
- **方控学习**（设置 → 方控学习）：按一次车上的键绑定到 上一首 / 下一首 / 播放 / 暂停 / 播放暂停；
  未学习的键一律忽略。另有「监听方控广播日志」，按 action 记录最近 12 条方控广播，点选即绑定。
- **在线更新**（设置 → 在线更新）：检查 GitHub 上的新构建 → 自动下载（支持直连 / 代理）→ 静默安装。
- CarPlay 应用列表里那个「回到原车」的图标按钮，名称与图标都是**零跑**（不再是 BYD）。

## 🔴 暂不可用

- **有线 CarPlay 出画面**：lwIP 与 VPN **两条通路都已出画面**（192 报告：`Video: first frame
  rendered`，`shown=24.6fps` / `26.0fps`），灰屏与「VPN 进不去」都已解决。
- **有线会话稳定性**：192 报告里每次断开都是同一行 `Invalid NTB16 short-packet pad`，
  15～63 秒一次。已修（第 12 条）。
- **断线后的重试卡死**：lwIP 通路断线后重建报 `USB IPv6 socket error errno=16`（EBUSY），
  只能手动切模式才恢复。已修（第 13 条）。
- **方控压制原厂音乐**：音频归属已生效（`media keys active focusGranted=true session=true`），
  但 192 报告里**没有任何一次按键记录**，所以「原厂也同时切歌」还无法定论 —— 见下。

## 🔴 暂不可用

| 症状 | 现状 | 待办 |
|---|---|---|
| **外置 Wi-Fi / 同一局域网** | 193 被**我自己改坏**了（见第 14 条），本版修回 | 复测 |
| 车载自建热点无线连不上 | 热点起得来、iAP2 认证通过，但手机不拨 7000 | 本版恢复 64 位 ABI 验证（第 17 条） |
| 方控切歌时原厂也切歌并同时播放 | 焦点/会话已拿到，但日志里没有按键记录 | **需要一份「连着的时候按方控」的日志** |

**外置 Wi-Fi 这条是我上一版的回归，先说清楚**：193 报告（402）里每一次无线尝试都是
`wireless bring-up failed: No common AirPlay port available for the selected interface addresses`，
70ms 就拆。原因是我把「link-local IPv6 → `::` 通配符」的替换套到了**多地址**分支上，
而 `::` 会覆盖同组里的其他地址，`bindAll` 要求所有地址绑同一个端口 → 必然 EADDRINUSE →
所有候选端口试完就抛。现只在**单地址**（即 VPN 通路）分支做替换，多地址分支恢复原样。

**车载热点这条是另一回事**：热点确实起来了（`Manual hotspot` / wlan0 / IPv4 /
`HotspotReady`），iAP2 也认证通过（`authenticated=true startRequests=2`），
AirPlay 监听也绑上了（`airplay listener ready family=IPv4`），但手机始终不发起 TCP
（`tcpAccepted=0` → `FIRST_TCP_TIMEOUT`）。这一条自 lwIP 引入以来没变过，
需要一份**能用时的日志**对照才能继续。

**方控这一条为什么要日志**：402/192 报告里 `media key ...`（两条路都会记）**一次都没出现**，
而音频是走 CarPlay 的。若下次日志仍然是「按了键但 DiPlay 一行都没有，CarPlay 却切了歌」，
那就说明按键根本没经过 DiPlay —— 最可能是车机把方向盘键交给原厂/蓝牙媒体通路，
再由 AVRCP 通知 iPhone 切歌（同一个 iPhone，所以 CarPlay 界面也跟着变，声音还从蓝牙出来
一遍）。那种情况下要修的是**手机的车机蓝牙音频链路**（零跑线用 `BluetoothAudioHandoff`
断开 A2DP/HFP 档位解决），不是按键转发。

复测时报告里应出现（缺哪条就说明对应那步没走通）：
- lwIP：`airplay event connection accepted from ...` → `airplay video event ready` →
  `Video recovery: requested keyframe sent=true` → `Video: first frame rendered`
- VPN：成功则 `vpn tun established variant=... address=fe80::2` 且
  `airplay listener ready family=IPv6 port=7000 bind=...`；失败则 `attach failed stage=...`
  加每个 `vpn establish variant=... rejected ...`
- 稳定性：整场**不再出现** `Invalid NTB16 short-packet pad`；`AirPlay session ended` 只在
  真正拔线时出现
- 方控：`media keys active focusGranted=true session=true`，之后每次按键一行
  `media key source=... action=... -> CarPlay ... sent=true`

## ⚠️ 已知限制

- **CI 不再跑 lint 与单元测试**，只出 release 包（原先的 check job 太慢）。
  这意味着 lint 这道「防止 API 26+ 调用混进 API 25 构建」的自动防线没有了，
  改运行时代码时请手动跑一次 `python D:\Launcher\kotlin_static_check.py <改动的 .kt 文件>`。
- **有线默认走 VPN/NCM**，lwIP 要手动开（见第 15 条），而且**本版在 arm64 进程里根本用不了**
  （见第 17 条，`LwipNative.available=false`，自动回退 VPN）。lwIP 模式的已知缺陷：
  出画面但**没有声音**；**手动断开后必须重启应用**才能再次连上；整体不如 VPN 稳。
- **车载自建热点无线仍连不上**（热点起得来、iAP2 通过，手机不拨 7000）。
  本版恢复了 178 的 ABI（64 位）来验证这一点，见第 17 条。
  无线可用方案仍是**外置 Wi-Fi / 同一局域网**（已确认可用）。
- 开无线 CarPlay 时车机自身没有网络（msm8953 单射频，不支持 STA+GO 并发）。

---

## 本版改动（0.2.12）

1. **有线 lwIP：地址通告改用真实地址。**
   原来把常量 `fe80::2` 作为有线端点地址，通过 iAP2 `0x4301 carplay-start-session`
   下发给 iPhone；而 lwIP 接口上的 link-local 地址是启动时按 EUI-64 从网卡 MAC 推导的，
   两者不同 → 手机邻居发现（NDP）解析失败 → **永远不发起 AirPlay TCP**。
   现改为读取 lwIP 自己的真实 link-local 地址。
   （VPN 通路不受影响：它的 `fe80::2` 是真的被加到 TUN 接口上的。）

2. **有线 lwIP：回环中继地址族修正。**
   中继原来硬编码 `java.net.Socket("127.0.0.1", ...)`（IPv4），而 AirPlay 监听绑定在
   `getLoopbackAddress()` = `::1`（IPv6）。手机即使连进来，中继也会以 Connection refused 断开。
   现与同文件的 UDP 中继保持一致，统一使用 `getLoopbackAddress()`。

3. **CI 精简并接入 GitHub Releases。**
   移除 check job（单测 + 五个模块 lint + 三个 assembleDebug），只构建平台签名 release 包；
   构建成功后自动发布到 GitHub Releases，tag 为 `v0.2.12-<run_number>`，附 APK。

4. **移植「方控学习」**（来自零跑线 `DiPlay-main2.0`）。
   设置页新增独立区块，可把车上的按键或车机方控广播逐条绑定到 CarPlay 的五个媒体动作；
   只转发学习过的键，顺带免疫车机内部命令回环。底层 `WheelLearning.kt` 本就在本线，
   缺的是设置入口，这次补上。

5. **修好「在线更新」**（原实现是死的）。
   - `updateSection()` 建完控件后立刻把 `updateMessage` / `updateActionButton` 置空，
     按钮的进度回写全部落空 —— **点「检查更新」没有任何反应**。已去掉这两行赋值。
   - 安装对话框的「稍后」不清 `updateBusy`，点一次之后整个区块直到重进页面都是死的。已修。
   - `AppUpdater` 硬编码的是零跑线的发版规则（tag `v2.0-`、asset `-leapmotor.apk`），
     在本线永远找不到自己的包。已改为本线的 `v0.2.12-<run>` + `mobile-release.apk`。
   - 两条线共用同一个仓库，`/releases/latest` 有一半概率指向零跑线的包。现在先读
     `releases.atom`（两条线的 release 都在里面），只取 `v0.2.12-` 前缀里最大的构建号，
     `/releases/latest` 仅作兜底。
   - `currentBuild()` 的正则要求 `（数字）` 闭合，而本线版本名是 `0.2.12（189-6f275b6c）`，
     解析结果恒为 null → 检查更新会卡在「正在检查更新…」。已放宽为只认左括号。
   - 更新区块从「关于」页移到设置页顶层（零跑线也在这里），同时保证全局只有一份视图引用。

6. **「回到原车」按钮改品牌。**
   CarPlay 应用列表里的车机图标由 BYD 改为**零跑**：`DEFAULT_OEM_LABEL` 由 `"BYD"` 改为 `"零跑"`
   （并迁移一次已存的旧值），默认图标 `res/raw/ic_car_home.png` 换成零跑 logo。

7. **iOS 27 视频车内播放：修好启用链（原先整条是关着的）。**
   门控本身（N 挡 → `LeapmotorGearMonitor.videoAllowed()` → `VideoInCarGate`）在本线是完整的，
   但 `AirPlayConfig.videoInCar` 用的是上游 BYD 的开关
   `BydOutputSettings.videoWhileParkedActive()`，而那个开关只存在于 BYD 车辆数据面板里，
   在本车机上**够不着**：`BydOutputSettings.available()` 要装 BYD 包或 fingerprint 含 BYD，
   独立 HUD 探测还要 API 28+（本机 25），`BydAmapAdapter` 找的是 `com.byd.amapservice`。
   结果 `/info` 永远不带 `videoPlaybackInfo`、SETUP 不协商 `videoPlayback`，
   iPhone 压根不会把视频交给车机 —— 门控再对也没用。现改为与零跑线一致的无条件 `true`。
   **注意「提供能力」≠「允许播放」**：`VideoInCar.allowed` 初值为 false，
   仍由 N 挡轮询放开，收到任何档位数据前一律不放行。
   验证点：`airplay /info videoInCar=true ... videoPlaybackAllowed=...`。

8. **补上 `CarPlayVideo.detach()`。**
   本线移植时漏了这个函数，而零跑线在 `CarPlayHostActivity` 的重连与退出两处都会调它。
   缺它的后果是：CarPlay 重连/退出后视频播放器会**留在屏上**指向一个已经不存在的会话，
   且 `reply()` 继续往已关闭的 controller 发消息。现补上函数并在两处 teardown 调用。

9. **有线 lwIP 灰屏：事件通道的中继地址族不一致（上一版第 2 条的残留半截）。**
   上一版把**中继侧**改成了 `InetAddress.getLoopbackAddress()`（本机解析为 `::1`），
   但**监听侧**仍绑着字面量 `127.0.0.1`（`AirPlaySession` 里的 `LOOPBACK` 常量）。
   于是主控口 7000 通（它两边都用 `getLoopbackAddress()`），
   而 `eventPort` / timing / keepalive 三条全部 `Connection refused`：

   ```
   18:02:55.588  wired lwip proxy accepted port=44738 fd=5     ← 手机连进来了
   18:02:55.598  wired lwip relay ended: Connection refused     ← 10ms 后中继失败
   （整场没有 airplay event connection accepted）
   ```

   事件通道建不起来 → `sendCommand` 恒返回 false → 每秒一行
   `Video recovery: requested keyframe sent=false` → 视频 backlog 超 250ms 后等不到关键帧
   → `shown=0.0fps` → **灰屏**。
   现三处监听统一走新的 `listenerBindAddress()`，与中继同一个调用；
   UDP 中继的目标地址也从硬编码 IPv4 改成 `getLoopbackAddress()`。

   **Android 16 的报告独立印证了这条**（报告 876，同一份 APK 的 lwIP 会话）：
   ```
   20:28:05.402  wired lwip proxy accepted port=37591 fd=5
   20:28:05.406  wired lwip relay ended: failed to connect to ip6-localhost/[ip] (port 37591)
                 ... connect failed: ECONNREFUSED
   ```
   `ip6-localhost` 就是 `::1` —— 中继拨 IPv6 环回、监听绑 IPv4 字面量，一眼可见。
   同一份报告切到 VPN 通路后 `airplay event connection accepted` 立刻正常，
   也说明问题只出在环回这一处，不在事件通道本身。

10. **VPN 通路 `Invalid argument`：API 25 专有的平台拒绝，本版自证 + 自愈。**
    同一份 APK 在 **Android 16 上 VPN 通路是通的**（报告 876：`wired VPN service bound` 之后
    48ms 就 `attach result=started`，随后 `airplay event connection accepted`、
    `Video: first frame rendered`）。所以这不是配置写错，是 **Android 7 的平台拒收**，
    而它只回一个裸 EINVAL —— 既不说哪一步，也不说哪个参数。之前查到这里只能靠
    `adb logcat -s xcertplay-usb` 抓 `stage=`。
    本版把这条路做成**自己会说话**：

    - `establish()` 按 `full` → `no-allow-family` → `minimal` 三种配置依次尝试，
      每次结果（接受/拒绝 + 异常类型 + message）都写进报告：
      `vpn tun established variant=...` / `vpn establish variant=... rejected ...`。
      哪一档被接受，说明被拒的就是它比上一档多出来的那个开关；一档被接受，有线通路当轮就活了。
    - `attach failed stage=...` 现在进报告（`Log.w` 只进 logcat，报告只收 `onDiagnostic`）。
    - `airplay listener ready` 现在带 `bind=<地址>`，一眼看出监听到底绑在 `fe80::2` 还是 `::`。

    同时保留一处独立修正：监听地址是 link-local IPv6 时改用 `::` 通配符
    （裸 link-local 字面量 `scope_id` 为 0，`bind()` 会 EINVAL；零跑线一直绑 `::` 所以没这问题）。

11. **方控不压制原厂音乐：音频归属在 API 25 上从来没被申请过。**
    本车机把方向盘键交给「持有音频焦点的那个媒体会话」，所以必须由 DiPlay 拿到焦点 +
    激活 MediaSession，原厂播放器才会让位。而本线的归属只由 `onMediaAudioChanged(true)`
    触发，那要 iPhone 把**音乐流**发过来；实测 iPhone 把音频留在车机蓝牙链路上
    （整份报告 0 行 `Audio:`，SETUP 只协商了屏幕流 `type=110`），于是永远不触发。
    另一条路 `onIphonePlaying`（iPhone 报播放，走 CarPlay 的 now-playing，**是通的**）
    却指向 `regainFocusLocked()` —— 那个函数在 API 25 上是彻底的死代码：
    `focusRequest` 只在 `start()` 里、且只在 SDK ≥ 26 时才赋值，函数本身也直接
    `if (SDK_INT < O) return`。结果焦点和 MediaSession 都没建立，方控两边都收，两边都切歌。
    现让 `onIphonePlaying` 走与零跑线一致的 `updateLocked(playing)`（会建会话、拿焦点）。
    顺带：
    - `CarPlayMediaKeys` 的诊断行以前只进 logcat（`DiPlay-MediaKeys` 这个 TAG），
      **报告里完全看不到**，所以「焦点到底拿到没有」一直无从判断；现接进报告。
    - 媒体会话路径的按键改为经 `LeapmotorMediaKeys.dispatch` 转发，与广播路径**共用**
      去重窗口 —— 否则拿到焦点后一次按键会在两条路上各转一次，变成跳两首。
      去重窗口的键也从「原始 action」改成「CarPlay 按钮」，
      因为同一按在两条路上的名字不同（广播叫 `nextOne`，媒体会话叫 `next`）。
    - 按 §58，**没有**恢复「发 pause 广播压制原厂播放器」那一招（车机会回声导致 CarPlay 自己被暂停）。

12. **有线会话 15～63 秒必断：NCM 的「短包填充字节」被当成致命错误。**
    `NcmUsbBridge.drainFrames()` 原本按 `wBlockLength % 512 == 0` 认定这一块后面**必须**跟一个
    0x00 填充字节，否则 `failSession("Invalid NTB16 short-packet pad")` —— 而 `failSession`
    会把整个 NCM 桥标记为 `DeviceUnavailable`，于是整个 AirPlay 会话被拆掉重连。
    问题在于**填充字节属于 USB 传输，不属于 NTB 块**：主机只在一个传输的长度正好是端点
    maxPacketSize 整数倍时才补一个 0x00，而这里的接收缓冲会把同一个传输里的多块拼在一起，
    于是「512 对齐的块」后面紧跟的其实是下一块的开头（NTB16 头以 `'N'` 开头，非 0）→ 误判。
    192 报告里**每一次断开**（lwIP 与 VPN 都是）就是这一行，间隔 15s / 3s / 40s / 63s / 32s。
    现改为：512 对齐时，后面那个字节是 0 就当作填充跳掉，不是 0 就不动它（留给下一轮解析）。

13. **lwIP 断线后重建报 EBUSY，只能手动切模式才恢复。**
    `LwipNative.start()` 在旧的原生栈没释放时抛 `USB IPv6 socket error errno=16`（EBUSY，
    文案在 `libdiplay_lwip.so` 里）。而 `attachLwip()` 重建会话前**没有关闭上一个
    `LwipSessionNetwork`** —— 只在 attach 失败时才关，会话正常结束的路径不会关。
    192 报告 21:36:55 与 21:37:17 两次 EBUSY，此后 lwIP 一直是死的，直到手动切到 VPN。
    现改为在 `attachLwip()` 建新会话前先关掉旧的那个。

14. **（回归修复）外置 Wi-Fi 被我改坏了，本版修回。**
    193 把「link-local IPv6 → `::` 通配符」的替换套在了 `startAirPlayServer` 的**多地址**分支上。
    `bindAll` 要求一组地址绑在同一个端口，而 `::` 会覆盖同组里的其他地址
    （无线主机地址里必然带一个 link-local IPv6）→ 每个候选端口都 EADDRINUSE →
    `BindException("No common AirPlay port available for the selected interface addresses")`。
    402 报告里每一次无线尝试都是这一句，`HotspotReady` 之后 70ms 就拆。
    现只在**单地址**分支（即 VPN 通路，地址就是 `fe80::2`）做替换，多地址分支恢复原样。

15. **有线默认改成 VPN/NCM，lwIP 改为手动开启。**
    `DiPlayPreferences.wiredLwip` 默认由 `true` 改为 `false`。老版本默认开着，
    升级后会保留旧选择，所以加了一次性迁移（`wired_lwip_defaulted_v2` 标记）：
    首次读取时写回 `false`，之后设置里的开关仍然可以覆盖。
    lwIP 模式目前的已知缺陷：出画面但没声音、手动断开后必须重启应用才能重连、整体不如
    VPN 稳 —— 所以默认不用它。

16. **NCM 短包填充的修法对齐零跑线。**
    第 12 条那处逻辑与零跑线 `NcmUsbBridge` 已有实现**逐字一致**（零跑线早就修过，
    PSA 这份是移植时漏了那次修复），并补上零跑线的注释与一次性诊断行
    （`NTB16 block without the expected pad byte; accepting a ZLP terminator`）。
    注释里写明了机制：Apple 用单个 0x00 填充让传输以短包结束，而 Android 7 会把它变成
    **ZLP**，`readChunk()` 会丢掉 ZLP —— 所以那个字节常常根本不存在。

17. **恢复 APK 的 ABI（arm64-v8a 回来了），lwIP 在本机因此不可用。**
    178 之后为了 lwIP 那个 v7a-only 的 `libdiplay_lwip.so`，把 APK 钉成只有 `armeabi-v7a`
    （`shared/build.gradle` 的 abiFilters **加上** `mobile/build.gradle.kts` 的 `ndk.abiFilters`），
    于是整个进程变成 **32 位**。而 178 那份热点日志（报告 840）是 **64 位**、
    版本行 `0.2.12-hud-test`（debug 口味）。

    两份日志的**应用侧热点启动序列逐行一致**：同一个 `Manual hotspot` 后端、同一个 wlan0、
    同样绑 7000、同样起了 Bonjour、同样通过 iAP2 把 endpoint 发给手机
    （`wireless endpoint addressCount=1 family=IPv4 port=7000`）。差别只在最后一步：
    178 `tcpAccepted=1 firstTcpAfterStartMs=780`（试了两次都成功），194 `tcpAccepted=0`。

    现恢复 178 的配置：`shared/build.gradle` 的 abiFilters 恢复
    `'arm64-v8a', 'armeabi-v7a', 'x86_64'`、`APP_PLATFORM` 恢复 `android-28`，
    并删掉 `mobile/build.gradle.kts` 里那个 `ndk { abiFilters.add("armeabi-v7a") }`。
    CI 的校验步骤改成**断言 APK 里必须有 `lib/arm64-v8a/`**，以后看 CI 日志就知道 ABI。

    **lwIP 怎么处理**：代码保留。在 arm64 进程里 `libdiplay_lwip.so` 根本加载不了，
    `LwipNative.available=false` → `attachLwip()` 自动回退到 VPN 通路（这条回退本来就有），
    设置页也会显示「不可用」。效果等同于关掉 lwIP 模式，但不用删代码 ——
    万一 ABI 不是热点那件事的原因，还能退回来。确认之后再决定是否彻底删除。

    **一句实话**：我没有**证明**是 ABI 导致的。178 与现在同时变了两个变量
    （ABI 32/64 位、以及 debug→release 口味与包名后缀 `.hudtest` 的移除）。
    本版先动 ABI（顺带满足「关掉 lwIP」），复测就能把这两个变量分开：
    热点回来了 = ABI；还是不行 = 下一个变量是包名/口味。

---

## 装包说明

- 包名 `com.shihab.diplay`，平台签名（与 debug 签名不同）。
  **从 debug 包切到本包必须先卸载**；之后 release → release 可覆盖安装。
- 诊断报告第一行带真实版本名（`DiPlay 0.2.12（NN-sha）`），贴日志前先核对这一行。
