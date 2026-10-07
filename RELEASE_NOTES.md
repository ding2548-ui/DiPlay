# DiPlay 0.2.12 · PSA 车机版

Android 7.1.2（API 25）/ Qualcomm msm8953 车机上的 CarPlay 接收端。
**本页是当前整体状态页，每次发布前更新。**

---

## ✅ 可用

### 连接

- **有线 CarPlay（VPN/NCM 通路）**：设备发现、重枚举、配对、NCM 数据通路、MFi 认证、
  `0x4300` / `0x4301`、出画面（`Video: first frame rendered`）、音频（`audioType=media`）全部正常。
  会话稳定性与断线重连均已验证。
- **无线 CarPlay** —— 三种模式**都可用**，按车机条件任选：
  - **Wi-Fi Direct**：✅ 可用。车机自建 P2P 组，iPhone 直连，不依赖车机热点。
    API 25 上信道由系统选择（见「已知限制」）。
  - **车载自建热点**：✅ 可用。走车机自己的热点，需要车机 Wi-Fi 开关打开。
  - **外置 Wi-Fi / 同一局域网**：✅ 可用。车机与 iPhone 连同一个网络。

### 功能

- **方向盘方控**：
  - **S01**：通道 A（`car.meter.music.BROADCAST` 的 JSON）—— 实测正确，
    切歌时原厂同步切并被立刻暂停、CarPlay 保持播放。
  - **T03**：通道 B（`com.leapmotor.customkey.music.pauseplay` 的整数 extras）—— **可用**。
- **方控音频归属**：焦点与 MediaSession 已拿到，原厂播放器会让出方控
  （`media keys active focusGranted=true session=true`）。
- **媒体信息投送**：曲名 / 艺术家 / 封面经 MediaSession 提供给车机仪表等读取方，
  暂停或切歌之后仍持续更新。
- **USB 权限弹窗**：`MANAGE_USB` + 无障碍按内容判定自动确认（`usb auto-confirm: confirmed`）。
- **界面汉化**、零跑档位识别、倒车暂停、iOS 27 视频车内播放（N 挡门控）。
- **在线更新**（设置 → 在线更新）：检查 GitHub 上的新构建 → 自动下载（支持直连 / 代理）→ 静默安装。
- CarPlay 应用列表里那个「回到原车」的图标按钮，名称与图标都是**零跑**（不再是 BYD）。

## ⚠️ 已知限制

- **Wi-Fi Direct 在 API 25 上无法指定信道**：`WifiP2pGroup.getFrequency()` 与带 config 的
  `createGroup` 都是 API 29 才有的，所以连接设置页的信道项只做说明、由系统选择信道。
  升到 Android 10 以上才能手动指定。
- **CI 不再跑 lint 与单元测试**，只出 release 包。这意味着 lint 这道「防止 API 26+ 调用混进
  API 25 构建」的自动防线没有了，改运行时代码时请手动跑一次
  `python D:\Launcher\kotlin_static_check.py <改动的 .kt 文件>`。
- **APK 必须带 `arm64-v8a`**：为某个 native 库钉 ABI 会把**整个进程变成 32 位**，
  而 32 位会打挂车载热点。CI 里已加断言。
- **开无线 CarPlay 时车机自身没有网络**（msm8953 单射频，不支持 STA+GO 并发）。
- 无线握手完成后会尝试把 iAP2 从蓝牙引导通道切到隧道通道；若隧道未就绪会
  `wireless handoff fallback ... preserving AirPlay`，**AirPlay 与画面不受影响**，
  只是继续走蓝牙引导通道。

## 复测判据

方向盘方控（S01 / T03 都适用，缺哪条就说明那步没走通）：

1. 起会话时应看到 `media keys listening on 9 car actions`（通道 A 侧）
   与 `car bus listening on 2 extra wheel channels`（通道 B 侧）—— 两个接收器各报各的。
2. 按一次方向盘的「下一首」，应出现**恰好一行**
   `media key source=<通道> action=nextOne -> CarPlay 4 sent=true`
   （T03 的通道名应是 `com.leapmotor.customkey.music.pauseplay`）。
3. 若某台车无反应：看有没有 `media key extra-channel unmatched action=... extras=[...]`
   —— 这行会把该车机真正发的 extras **全量列出来**，据此再加映射即可。

若报告里**一行 `media key ...` 都没有**：说明按键根本没到 DiPlay，
要查的是车机把方控交给谁（原厂 / 蓝牙媒体通路），不是按键转发。

无线连接成功时报告里应出现（缺哪条就说明对应那步没走通）：

- Wi-Fi Direct：`wireless hotspot backend=Wi-Fi P2P iface=p2p0` →
  `wireless Bonjour services started mode=interface iface=p2p0` → `AirPlay session active`
- 车载热点：`wireless hotspot backend=Manual hotspot` → `AirPlay session active`
- 出画面：`Video: first frame rendered`
- 稳定性：`AirPlay session ended` 只在真正断开时出现

## 装包说明

- 包名 `com.shihab.diplay`，平台签名（与 debug 签名不同）。
  **从 debug 包切到本包必须先卸载**；之后 release → release 可覆盖安装。
- 诊断报告第一行带真实版本名（`DiPlay 0.2.12（NN-sha）`），贴日志前先核对这一行。
