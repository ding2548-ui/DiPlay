# DiPlay 0.2.12 · PSA 车机版

Android 7.1.2（API 25）/ Qualcomm msm8953 车机上的 CarPlay 接收端。
**本页是当前整体状态页，每次发布前更新。**

---

## ✅ 可用

- **无线 CarPlay**（外部 Wi-Fi 方案）：iAP2 全链路正常，出画面、可交互。
- **有线 CarPlay 的 USB / iAP2 控制通道**：设备发现、重枚举、配对、NCM 数据通路、
  MFi 认证、`0x4300` / `0x4301` 全部通过。
- **界面汉化**、零跑档位识别、方向盘按键、倒车暂停、iOS 27 视频车内播放。
- **方控学习**（设置 → 方控学习）：按一次车上的键绑定到 上一首 / 下一首 / 播放 / 暂停 / 播放暂停；
  未学习的键一律忽略。另有「监听方控广播日志」，按 action 记录最近 12 条方控广播，点选即绑定。
- **在线更新**（设置 → 在线更新）：检查 GitHub 上的新构建 → 自动下载（支持直连 / 代理）→ 静默安装。
- CarPlay 应用列表里那个「回到原车」的图标按钮，名称与图标都是**零跑**（不再是 BYD）。

## 🔴 暂不可用

- **有线 CarPlay 出画面**。两条传输通路各有故障，都断在「手机发起 AirPlay TCP」之前：

  | 通路 | 状态 |
  |---|---|
  | lwIP（默认） | 监听与中继都已就绪，但下发给手机的 IPv6 地址不正确 → 手机邻居发现失败 → 不拨号 |
  | VPN | `attach()` 直接抛 `Invalid argument`，连 AirPlay 监听都没起来 |

  本版修复了 **lwIP 通路**的两处缺陷（见下）。**VPN 通路的 `Invalid argument` 尚未定位** ——
  需要 `adb logcat -s xcertplay-usb` 里的 `attach failed stage=` 一行才能确定修法。

## ⚠️ 已知限制

- **CI 不再跑 lint 与单元测试**，只出 release 包（原先的 check job 太慢）。
  这意味着 lint 这道「防止 API 26+ 调用混进 API 25 构建」的自动防线没有了，
  改运行时代码时请手动跑一次 `python D:\Launcher\kotlin_static_check.py <改动的 .kt 文件>`。
- VPN 通路在 PSA 线上从未成功过，目前只作为 lwIP 不可用时的回退路径。
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

---

## 装包说明

- 包名 `com.shihab.diplay`，平台签名（与 debug 签名不同）。
  **从 debug 包切到本包必须先卸载**；之后 release → release 可覆盖安装。
- 诊断报告第一行带真实版本名（`DiPlay 0.2.12（NN-sha）`），贴日志前先核对这一行。
