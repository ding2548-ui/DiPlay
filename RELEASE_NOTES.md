# DiPlay 0.2.12 · PSA 车机版

Android 7.1.2（API 25）/ Qualcomm msm8953 车机上的 CarPlay 接收端。
**本页是当前整体状态页，每次发布前更新。**

---

## ✅ 可用

- **无线 CarPlay**（外部 Wi-Fi 方案）：iAP2 全链路正常，出画面、可交互。
- **有线 CarPlay 的 USB / iAP2 控制通道**：设备发现、重枚举、配对、NCM 数据通路、
  MFi 认证、`0x4300` / `0x4301` 全部通过。
- **有线 CarPlay 进 CarPlay 界面**：lwIP 通路的地址通告与中继已通，手机能连上、能进界面。
- **界面汉化**、零跑档位识别、方向盘按键、倒车暂停、iOS 27 视频车内播放（N 挡门控）。
- **方控学习**（设置 → 方控学习）：按一次车上的键绑定到 上一首 / 下一首 / 播放 / 暂停 / 播放暂停；
  未学习的键一律忽略。另有「监听方控广播日志」，按 action 记录最近 12 条方控广播，点选即绑定。
- **在线更新**（设置 → 在线更新）：检查 GitHub 上的新构建 → 自动下载（支持直连 / 代理）→ 静默安装。
- CarPlay 应用列表里那个「回到原车」的图标按钮，名称与图标都是**零跑**（不再是 BYD）。

## 🔴 暂不可用

- **有线 CarPlay 出画面（灰屏）**。上一版报告的两个根因都已修，**待真机复测**：

  | 症状 | 根因 | 本版 |
  |---|---|---|
  | lwIP 模式进得了界面但**灰屏** | 事件通道中继被 `Connection refused`（环回地址族不一致） | 已修（第 9 条） |
  | 关闭 lwIP 走 VPN **直接进不去** | `attach()` 抛 `Invalid argument`（API 25 平台拒收，Android 16 同一份包正常） | 已改（第 10 条） |
  | 方控不压制原厂音乐，两边同时切歌播放 | 音频归属在 API 25 上是死代码，从未申请焦点 | 已修（第 11 条） |

  复测时报告里应出现（缺哪条就说明对应那步没走通）：
  - lwIP：`airplay event connection accepted from ...` → `airplay video event ready` →
    `Video recovery: requested keyframe sent=true` → `Video: first frame rendered`
  - VPN：成功则 `vpn tun established variant=... address=fe80::2` 且
    `airplay listener ready family=IPv6 port=7000 bind=...`；失败则 `attach failed stage=...`
    加每个 `vpn establish variant=... rejected ...`
  - 方控：`media keys active focusGranted=true session=true`，之后每次按键一行
    `media key source=... action=... -> CarPlay ... sent=true`

## ⚠️ 已知限制

- **CI 不再跑 lint 与单元测试**，只出 release 包（原先的 check job 太慢）。
  这意味着 lint 这道「防止 API 26+ 调用混进 API 25 构建」的自动防线没有了，
  改运行时代码时请手动跑一次 `python D:\Launcher\kotlin_static_check.py <改动的 .kt 文件>`。
- VPN 通路历史上在 PSA 线上从未成功过，本版才修掉它的 `Invalid argument`；
  首次成功与否仍需真机确认。
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

---

## 装包说明

- 包名 `com.shihab.diplay`，平台签名（与 debug 签名不同）。
  **从 debug 包切到本包必须先卸载**；之后 release → release 可覆盖安装。
- 诊断报告第一行带真实版本名（`DiPlay 0.2.12（NN-sha）`），贴日志前先核对这一行。
