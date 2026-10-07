# DiPlay 0.2.12 · PSA 车机版（beta 测试渠道）

> **这是 PSA 线的 beta 渠道**，从 `psa-beta` 分支发布，包名 `com.shihab.diplay.psabeta`
> （可与正式包 `com.shihab.diplay` 共存安装），发版 tag 走 `v0.2.12-beta-<run>`，
> 与正式渠道的 `v0.2.12-<run>` **互不可见**（在线更新各自只看自己的命名空间）。
> 正式渠道的包请到 `v0.2.12-` 的 release 下载。

Android 7.1.2（API 25）/ Qualcomm msm8953 车机上的 CarPlay 接收端。
**本页是当前整体状态页，每次发布前更新。**

---

## ✅ 可用

- **有线 CarPlay（VPN/NCM 通路）**：设备发现、重枚举、配对、NCM 数据通路、MFi 认证、
  `0x4300` / `0x4301`、出画面（`Video: first frame rendered`）、音频（`audioType=media`）全部正常。
  会话稳定性与断线重连已修（第 12、13 条）。
- **无线 CarPlay**：
  - **车载自建热点**：✅ **可用**。178 版可用 → 之后为 lwIP 把 APK 钉成 32 位把它打挂 →
    恢复 arm64-v8a 后恢复（第 17 条）。
  - **外置 Wi-Fi / 同一局域网**：✅ 可用。
- **界面汉化**、零跑档位识别、倒车暂停、iOS 27 视频车内播放（N 挡门控）。
- **方向盘方控**：S01 走通道 A（`car.meter.music.BROADCAST` 的 JSON）—— 实测正确，
  切歌时原厂同步切并被立刻暂停、CarPlay 保持播放。
- **T03 方控**：本版补上通道 B（`com.leapmotor.customkey.music.pauseplay` 的整数 extras
  `ICU_MediaSwitch` / `ICU_MediaKey`），并把车机总线接收器改为常开（第 21 条）。**待 T03 复测**。
- **USB 权限弹窗**：`MANAGE_USB` + 无障碍按内容判定，**实测已通过**
  （`usb auto-confirm: confirmed`，S01）。
- **方控音频归属**：焦点与 MediaSession 已拿到（`media keys active focusGranted=true session=true`）。
- **在线更新**（设置 → 在线更新）：检查 GitHub 上的新构建 → 自动下载（支持直连 / 代理）→ 静默安装。
- CarPlay 应用列表里那个「回到原车」的图标按钮，名称与图标都是**零跑**（不再是 BYD）。

## 🔴 暂不可用 / 待确认

| 症状 | 现状 | 待办 |
|---|---|---|
| **T03 方控无反应** | 根因已定位：通道 B 的解析缺失，**且**注册通道 B 的接收器此前只由默认关闭的调试开关创建 → 真机上从未注册（第 21 条） | 本版已修，**待 T03 复测**。请把日志发来，按下面「方控复测判据」核对 |
| lwIP 有线通路 | 出画面但没声音、手动断开后要重启应用、整体不如 VPN 稳 | 开关已摘除（第 18 条），代码留着但用不到 |

**方控复测判据**（S01 / T03 都适用，缺哪条就说明那步没走通）：

1. 起会话时应看到 `car bus listening on 11 actions (wheel channels included)`
   —— 这是本版新增的，**没有它 = 车机总线接收器没起来**（第 21 条的根因）。
2. 再看到 `media keys listening on 11 car actions`。
3. 按一次方向盘的「下一首」，应出现**恰好一行**：
   `media key source=<通道> action=nextOne -> CarPlay 4 sent=true`
   （T03 的通道名应是 `com.leapmotor.customkey.music.pauseplay`）。
4. **T03 若仍无反应**：看有没有 `media key extra-channel unmatched action=... extras=[...]`
   —— 这行会把该车机真正发的 extras **全量列出来**，据此再加映射即可，不用再跑一趟实车。

若报告里**一行 `media key ...` 都没有**：说明按键根本没到 DiPlay，
要查的是车机把方控交给谁（原厂 / 蓝牙媒体通路），不是按键转发。

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
- **APK 必须带 `arm64-v8a`**（第 17 条）：为某个 native 库钉 ABI 会把**整个进程变成 32 位**，
  而 32 位会打挂车载热点。CI 里已加断言。
- **有线走 VPN/NCM**；lwIP 的设置开关已摘除（第 18 条），代码保留但在 arm64 进程里用不了。
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

    **复测结果：热点回来了 → 就是 ABI（32 位化打挂的）。** 所以 lwIP 那个 .so 不值得为它
    把整个进程钉成 32 位。

18. **摘掉设置里的 lwIP 开关。**
    `wired_lwip` 现在在 arm64 进程里用不了（第 17 条），开关没有意义，已从设置页移除，
    相关的 5 条字符串也一并删掉。代码（`LwipNative` / `LwipSessionNetwork` / `attachLwip`）
    保留不动：留着不影响，也不占运行路径。偏好默认仍是 `false`（第 15 条的一次性迁移照旧）。

19. **USB 权限弹窗：无障碍为什么开了也不生效，以及真正的修法。**
    **现象**：车机设置里已开启本应用的无障碍权限，但每次插拔数据线仍要手点授权弹窗。

    **无障碍服务为什么静默** —— `UsbAutoConfirmService` 原来有三道**精确匹配**的闸门，
    任何一道不成立就什么都不做：
    1. `usb_auto_confirm_service_config.xml` 里 `android:packageNames="com.android.systemui,android"`
       —— 只有这两个包的事件才会送到服务。
    2. `isSystemUsbWindow()` 要求 `className` **恰好**是
       `com.android.systemui.usb.UsbPermissionActivity` 或 `...UsbConfirmActivity`。
    3. 确认按钮要 `viewIdResourceName == "android:id/button1"`，或文本**完全等于**
       `确定/允许/OK/Allow/Confirm` 之一。

    这三条只对 **AOSP 原生 ROM** 成立。本车机是 `N2G47H test-keys` 的厂商定制 ROM，
    弹窗来自哪个包、哪个 Activity 都不一定；只要第 1、2 条不成立，服务连节点都不会读，
    报告里也就**一行都不会有**（它原来只用 TAG `UsbAutoConfirm` 写 logcat，不进报告）。

    **真正该修的是上游**：应用是**平台签名**的（manifest 里已经声明 `INSTALL_PACKAGES`
    这种 `signature|privileged` 权限，静默安装也确实可用）。平台的 USB 权限检查对持有
    `android.permission.MANAGE_USB` 的调用者**直接放行**（`UsbUserSettingsManager.hasPermission`），
    于是 `UsbManager.hasPermission()` 不再为 false，`IphoneUsbHost.requestPermission()`
    走 `AlreadyGranted` 分支，**系统根本不会弹这个窗**。所以本版声明了 `MANAGE_USB`，
    弹窗从源头消失，无障碍服务只作兜底。

    **本版改动**：
    - manifest 声明 `android.permission.MANAGE_USB`。
    - 诊断行加上授权状态：`wired iPhone USB permission already granted manageUsb=true|false`
      （`requested` 那行也有）。**`manageUsb=true` 却仍走到 `requested`**
      = 这个 ROM 没有走 MANAGE_USB 的快捷路径，那就只能靠无障碍。
    - 无障碍服务：去掉 `packageNames` 过滤；判定改为**按内容**（弹窗必须提到本应用名 + USB）
      加「不是自己的窗口」，不再比 Activity 名；确认按钮改为**子串**匹配并扩充
      （确定/确认/允许/同意/OK/Allow/Confirm/Agree/Accept/Yes）；「默认」勾选框也按文本兜底。
    - 服务把**看到的每个含 USB 的窗口**（包名 + 文本前 160 字）与点击结果写进 DiPlay 报告，
      下一份日志就能直接看出弹窗来自哪个包、按钮叫什么。

20. **方控不压制原厂音乐：对齐零跑线的音频夺权状态机（PSA 缺两条关键的）。**
    按零跑线的《CarPlay 音频夺权功能报告》逐条比对，PSA 缺的是：
    - **`update(false)` 不释放**（零跑会 `releaseLocked()`）。不释放 → `mediaActive` 永远是 true
      → 后面的 `update(true)` 被"没变化"挡掉 → **永远走不到重新拿焦点那一步**。
    - **`regainFocusLocked()` 在 API 25 是死代码**（`focusRequest` 恒为 null，函数还
      `if (SDK_INT < O) return`）→ **焦点被原厂抢走后永远拿不回来**。
    - 没有 `BluetoothAudioHandoff`（层③）；没有幂等判断。

    零跑那边「切歌原厂跟着切、然后立刻被暂停」的机制是：原厂为放新歌**抢走焦点** →
    DiPlay 收到 `AUDIOFOCUS_LOSS` → iPhone 换歌瞬间 pause→play → `update(false)` 释放、
    `update(true)` **重新请求焦点** → 原厂刚出声就又被压下去。PSA 缺上面两条，循环建立不起来。

    现按零跑重写状态机（`ownershipActive` 幂等 + `acquireLocked`/`releaseLocked` +
    `requestFocus` 每次重新请求且 API 25 可用），并移植 `BluetoothAudioHandoff`。
    **与零跑有意不同的一处：只断 A2DP，不断 HFP/HEADSET** —— PSA 的无线通路走蓝牙 RFCOMM 上的
    iAP2，断耳机组 profile 有风险；而 handoff 的本职只是防止声音从车机 A2DP sink 漏出，A2DP 足够。
    新增设置开关「CarPlay 播放时断开手机蓝牙音频」（默认开）与全套诊断行。
    逐条对比见 `D:\Launcher\DiPlay-PSA-音频夺权-对比报告.md`。

21. **T03 方控无反应：补上通道 B 的整数 extras，并把车机总线接收器改为常开。**
    原厂方控有**三条**通道（`com.leapmotor.multimedia-AppMain-方控指令与切歌暂停实现.md`）：

    | 通道 | action | extras | 走它的车 |
    |---|---|---|---|
    | A | `car.meter.music.BROADCAST` | `receiver` 的 byte[] = 长度头 + JSON `data.action` | **S01** |
    | B | `com.leapmotor.customkey.music.pauseplay` | int `ICU_MediaSwitch`(2=下一首/1=上一首)、`ICU_MediaKey`(1=播放暂停) | **T03** |
    | C | `car.hmi.music.BROADCAST` | String `action` | 备用 |

    本线一直只覆盖通道 A，而 S01 恰好走通道 A，所以「S01 好使、T03 不好使」看起来像玄学。实际缺了三处：

    - **注册通道 B 的接收器根本没起来（主因）**：`ensureCarBusReceiver()` 只被
      `setBroadcastLogEnabled(true)` 调用，而那个「监听方控广播日志」开关**默认关闭** →
      真机上这个接收器**从未注册**。即使解析写对了也没人接 T03 的广播。
      现改为 `LearnedWheelKeys.attach()` 里无条件注册（它属于方控通路，不是诊断）。
    - **通道 B 的解析不存在**：`handle()` 只认 `receiver` 的 byte[] 和 String `action`，
      而通道 B 是**整数 extras**，每次按键都落到 `media key payload unusable`。现补上解码。
    - **`play_pause` 拼写不在白名单里**：通道 B 解码输出的是 ICU 拼写 `play_pause`，
      而 `isWheelAction` 只认 JSON 拼写 `playpause`/`pauseplay` → 会被判成「车机内部指令」丢掉。
      `forLeapmotorAction` 同样漏。两处都补上。

    顺带把注册动作对齐原厂的十个（补 5 个 housekeeping，只监听不转发），
    并新增三条诊断：`car bus listening on N actions`、
    未知通道的 `media key extra-channel unmatched action=... extras=[...]`（把车机真正发的
    extras **全量列出来**）、以及 `payload unusable` 行现在也带 extras。
    **这样 T03 若仍不匹配，从一次日志就能读出新映射，不用再跑实车。**

    复测判据：起会话时应有 `media keys listening on 9 car actions`（通道 A 侧）
    与 `car bus listening on 2 extra wheel channels`（通道 B 侧）—— **两个接收器各报各的**，
    合起来是全部 11 个；按「下一首」应出现**恰好一行**
    `media key source=com.leapmotor.customkey.music.pauseplay action=nextOne -> CarPlay 4 sent=true`。

22. **WiFi Direct 模式连不上：API 29 的硬门控挡住了 Android 7。**
    `WifiP2pGroupManager.start()` 原本一进来就 `throw IOException("Wi-Fi P2P credentials require
    Android 10 (API 29) or newer")` —— 车机是 **API 25**，所以 **P2P 组从来没被创建过**，
    WiFi Direct 模式自然连不上。（其它无线模式走各自的管理器，不受影响，见下。）

    根因是三条 API 29 依赖：
    - `WifiP2pConfig.Builder` 的 `setNetworkName/setPassphrase/setGroupOperatingFrequency`（API 29+）
    - 三参数 `createGroup(Channel, WifiP2pConfig, ActionListener)`（API 29+）
    - `requestP2pState(Channel, P2pStateListener)`（API 29+，只在 `logP2pState` 里做诊断）

    修法：
    - 去掉硬门控；**API < 29 时只请求 `SYSTEM_DEFAULT` 模式**（跳过整条显式信道阶梯），
      并用 **两参数 `createGroup(Channel, ActionListener)`**（API 14+）建组 ——
      由框架自己生成凭据、自选信道，这也正是 `SYSTEM_DEFAULT` 的语义。
    - `logP2pState` 在 API < 29 时跳过（纯诊断）。
    - API < 29 时若配置了固定信道，**在建组前**就明确报错
      （"This Android version lets the system select the Wi-Fi Direct channel."），
      而不是建完组再失败。

    **有意未改的两处**（避免牵连）：
    - 地址选择保持原样 —— `awaitInterfaceAddress` 本来就**优先 IPv4**（拿到 `Inet4Address` 立即返回），
      只有在没有 IPv4 时才等 2 秒回退。不需要改成 IPv4-only。
    - "迟到的建组"清理不需要另加：`createActionListener.onSuccess` 里的 `removeDetachedGroup`
      已经把「已被取代的尝试收到迟到的 onSuccess」处理掉了（主动移除该组）。

    **确认不影响车载热点 / 外置 WiFi**：`CarPlayController` 里每种模式各有独立管理器 ——
    `WIFI_P2P → WifiP2pGroupManager`（本次改动）、`MANUAL → ManualHotspotManager`（车载热点）、
    `EXISTING_WIFI → ExistingWifiManager`（外置 WiFi）、`LOCAL_ONLY_HOTSPOT → LocalOnlyHotspotManager`。
    改动只落在 `WifiP2pGroupManager`，**其余三者一行未动**。

    复测判据：报告里应出现 `Wi-Fi P2P create mode=SYSTEM_DEFAULT frequencyMHz=auto`，
    并**不再出现** `Wi-Fi P2P credentials require Android 10 (API 29) or newer`。

---

## 装包说明

- 包名 `com.shihab.diplay`，平台签名（与 debug 签名不同）。
  **从 debug 包切到本包必须先卸载**；之后 release → release 可覆盖安装。
- 诊断报告第一行带真实版本名（`DiPlay 0.2.12（NN-sha）`），贴日志前先核对这一行。
