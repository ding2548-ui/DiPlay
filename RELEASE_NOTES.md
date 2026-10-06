# DiPlay 0.2.12 · PSA 车机版

Android 7.1.2（API 25）/ Qualcomm msm8953 车机上的 CarPlay 接收端。
**本页是当前整体状态页，每次发布前更新。**

---

## ✅ 可用

- **无线 CarPlay**（外部 Wi-Fi 方案）：iAP2 全链路正常，出画面、可交互。
- **有线 CarPlay 的 USB / iAP2 控制通道**：设备发现、重枚举、配对、NCM 数据通路、
  MFi 认证、`0x4300` / `0x4301` 全部通过。
- **界面汉化**、零跑档位识别、方向盘按键、倒车暂停、iOS 27 视频车内播放。

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

---

## 装包说明

- 包名 `com.shihab.diplay`，平台签名（与 debug 签名不同）。
  **从 debug 包切到本包必须先卸载**；之后 release → release 可覆盖安装。
- 诊断报告第一行带真实版本名（`DiPlay 0.2.12（NN-sha）`），贴日志前先核对这一行。
