# DiPlay 0.2.15 · PSA 车机版

Android 7.1.2（API 25）/ Qualcomm msm8953 车机上的 CarPlay 接收端。
**本页是当前整体状态页，每次发布前更新。**

> **渠道说明**：本渠道从 `psa-verify` 分支发布，包名 `com.shihab.diplay`，应用名 `DiPlay`，
> 发版 tag 走 `v0.2.15-<run>`。测试渠道（`psa-beta`，包名 `com.shihab.diplay.psabeta`）
> 走 `v0.2.15-beta-<run>`，两个命名空间**互不可见**（在线更新各自只看自己的）。
>
> **大版本过渡**：0.2.12 的老安装只扫描 `v0.2.12-<run>`，因此本渠道的每个 release 都会
> **同时**在 `v0.2.12-<run>` 下发布同一份 APK —— 老安装照常收到更新提示，更新一次之后
> 就会切到 `v0.2.15-<run>` 命名空间，后续不再依赖旧前缀。
>
> **本版基于上游 0.2.15 合并**（设置页分类+侧栏、外观主题、引导页、通话按键、缓冲音频、
> 平滑视频、SpeexDSP 回声消除、USB 读队列策略、侧栏等）。**无线连接三种方式
> （车载热点 / Wi-Fi Direct / 外置 Wi-Fi）保留本线自己实车验证过的实现，未采用上游版本。**
> ⚠️ 大版本合并会更换设置存储结构：**更新后若无线或设置异常，卸载重装一次即可**
> （beta 渠道实测：卸载重装后车载热点与 Wi-Fi Direct 均正常，更新前遗留状态会导致连不上）。

---

## ✅ 可用

### 连接

- **有线 CarPlay（VPN/NCM 通路）**：设备发现、重枚举、配对、NCM 数据通路、MFi 认证、
  `0x4300` / `0x4301`、出画面（`Video: first frame rendered`）、音频（`audioType=media`）全部正常。
- **无线 CarPlay** —— 三种模式**都可用**，按车机条件任选：
  - **Wi-Fi Direct**：✅ 可用。车机自建 P2P 组，iPhone 直连，不依赖车机热点。
    API 25 上信道由系统选择（见「已知限制」）。
  - **车载自建热点**：✅ 可用。走车机自己的热点，需要车机 Wi-Fi 开关打开。
  - **外置 Wi-Fi / 同一局域网**：✅ 可用。车机与 iPhone 连同一个网络。

### 功能

- **方向盘方控**：
  - **S01**：通道 A（`car.meter.music.BROADCAST` 的 JSON）—— 实测正确。
  - **T03**：通道 B（`com.leapmotor.customkey.music.pauseplay` 的整数 extras）—— **可用**。
- **方控音频归属**：焦点与 MediaSession 已拿到，原厂播放器会让出方控。
- **媒体信息投送**：曲名 / 艺术家 / 封面经 MediaSession 提供给车机仪表等读取方。
- **USB 权限弹窗**：`MANAGE_USB` + 无障碍按内容判定自动确认。
- **界面汉化**、零跑档位识别、倒车暂停、iOS 27 视频车内播放（N 挡门控）。
- **在线更新**（首页快捷设置下方 → 在线更新）：检查 GitHub 上的新构建 → 下载 → 安装。
- CarPlay 应用列表里那个「回到原车」的图标按钮，名称与图标都是**零跑**。
- **中控屏上的仪表盘地图**（悬浮卡片）：窗口类型按 SDK 分支，API < 26 降级 `TYPE_PHONE`。
- **设置页**：分类 + 侧栏 + 搜索；「与嘟嘟桌面共享实时界面」在**高级**组、紧跟仪表盘地图；
  「方控学习」在**车辆**组、紧跟方向盘按键；「在线更新」在首页。

## ⚠️ 已知限制

- **Wi-Fi Direct 在 API 25 上无法指定信道**：`WifiP2pGroup.getFrequency()` 与带 config 的
  `createGroup` 都是 API 29 才有的，所以信道由系统选择。升到 Android 10 以上才能手动指定。
- **CI 不再跑 lint 与单元测试**，只出 release 包。改运行时代码时请手动跑一次
  `python D:\Launcher\kotlin_static_check.py <改动的 .kt 文件>`。
- **APK 必须带 `arm64-v8a`**：为某个 native 库钉 ABI 会把**整个进程变成 32 位**，
  而 32 位会打挂车载热点。CI 里已加断言。
- **开无线 CarPlay 时车机自身没有网络**（msm8953 单射频，不支持 STA+GO 并发）。
- 无线握手完成后会尝试把 iAP2 从蓝牙引导通道切到隧道通道；若隧道未就绪会
  `wireless handoff fallback ... preserving AirPlay`，**AirPlay 与画面不受影响**。

## 复测判据

方向盘方控（S01 / T03 都适用）：

1. 起会话时应看到 `media keys listening on 9 car actions`（通道 A 侧）
   与 `car bus listening on 2 extra wheel channels`（通道 B 侧）。
2. 按一次「下一首」，应出现**恰好一行**
   `media key source=<通道> action=nextOne -> CarPlay 4 sent=true`。
3. 若某台车无反应：看 `media key extra-channel unmatched action=... extras=[...]`
   —— 这行会把该车机真正发的 extras 全量列出来。

无线连接成功时报告里应出现：

- Wi-Fi Direct：`wireless hotspot backend=Wi-Fi P2P iface=p2p0` →
  `wireless Bonjour services started mode=interface iface=p2p0` → `AirPlay session active`
- 车载热点：`wireless hotspot backend=Manual hotspot` → `AirPlay session active`
- 出画面：`Video: first frame rendered`

## 装包说明

- 包名 `com.shihab.diplay`，平台签名（与 debug 签名不同）。
  **从 debug 包切到本包必须先卸载**；release → release 可覆盖安装。
- 诊断报告第一行带真实版本名（`DiPlay 0.2.15（NN-sha）`），贴日志前先核对这一行。
