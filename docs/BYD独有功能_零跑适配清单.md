# DiPlay BYD 独有功能清单 —— 待适配零跑车型

> 统计对象：`DiPlay-PSA-beta`（PSA 线 beta 分支）
> 统计时间：2026-10-11（2026-10-11 晚更新：CAN 信号总线 + 来电卡片已落 beta）
> 统计范围：`shared/src/main/java/com/shilapi/xcertplay/hud/**`、`common/src/main/java/com/shilapi/xcertplay/**`、`shared/.../media/**`、`common/.../setup/**`
> 目的：逐项列出目前**只有 BYD 通路、零跑无任何替代实现**的功能，作为零跑适配的待办清单。
> **用户明确排除：投屏（ClusterMapProjection / 显示通路）与 HUD。本文档不把它们列为待办。**

---

## 0. 结论速览

| 类别 | BYD 独有功能数 | 已有零跑通路 |
|---|---|---|
| 仪表盘（Cluster）导航箭头 | 1 套（DiLink 3/4/5 三变体） | ❌ 无 |
| 风挡 HUD 导航（SOME/IP + 独立接收器） | 2 套 | 🚫 **用户排除，不做** |
| 仪表盘歌曲卡片 | 1 | ❌ 无 |
| 仪表盘/HUD 来电卡片 | 1 | ✅ **已接（`LeapmotorCarPlayCallOutput`）** |
| 原生来电弹窗屏蔽 | 1 | ❌ 无（零跑无 adbd，见 §6） |
| 车辆数据（电量/续航/充电/车速/档位/胎压） | 6 项 | ✅ **5 项已接**（CAN 总线），⚠️ 胎压待做 |
| 车型/能力探测与设置后端 | 2 | ✅ 已接（`LmCarProbe` + `BydOutputSettings.leapmotorHeadUnit()`） |
| OEM 仪表盘地图抑制 | 1 | 🚫 **属投屏范畴，用户排除** |
| 蓝牙挂起 / 开机自启修复 / Wi-Fi 扫描暂停 | 3 | ❌ 无（但机制是通用 AOSP） |
| 方控/媒体按键 | 1 套 | ✅ 已有（`LeapmotorMediaKeys`） |
| 氛围灯 | 1 套 | ✅ 已有（本次已接，运行时识别） |

**一句话**：**来电卡片、车辆数据（除胎压）、车型识别**已在 2026-10-11 落到 beta；
剩下真正要做的是 **仪表盘导航箭头**、**仪表盘歌曲卡片**、**胎压**，以及三个系统类辅助功能。

### 仍未替换的 BYD 功能（排除投屏 / HUD）—— 待办全清单

| 优先级 | 功能 | 零跑通路现状 | 估工 |
|---|---|---|---|
| **P1** | 仪表盘导航箭头（§1） | ❌ 需逆向零跑导航广播（`car.hmi.sender.BROADCAST` `type=navi` 有线索） | 大 |
| **P1** | 仪表盘歌曲卡片（§4） | ❌ 同上，`type=music` 格式**已解出**（`MeterPacket`），可行性高 | 中 |
| **P2** | 胎压/胎温（§7.3） | ❌ 零跑有导出服务 `Leapmotor.HealthInterface`（配套文档 §6.1） | 中 |
| **P2** | 蓝牙挂起（§9.1） | ❌ 零跑无 adbd → 需找广播替代（或放弃） | 中 |
| **P3** | 开机自启修复（§9.2） | ❌ 同上；零跑 Android 7 无 `cmd deviceidle` | 小 |
| **P4** | Wi-Fi 扫描暂停（§9.3） | ❌ 需按 API 25 重查 txn，收益不确定 | 小 |
| **放弃** | 原生来电弹窗屏蔽（§6） | ❌ 零跑无 `com.byd.bluetoothcall`；且无 adbd | — |
| **排除** | 投屏、HUD、OEM 仪表盘地图抑制 | 🚫 用户明确不做 | — |

### 架构层面的根因

代码里**没有一个通用的 `CarOem` / `VehicleBackend` 抽象**。整个 `hud/` 目录以 BYD 为硬编码默认；零跑支持是**逐个功能平行插入**的（氛围灯、档位、车辆信号、来电卡片），而不是共享接口。因此每缺一个功能，都要单独写一份零跑实现。

> ⚠️ **跨模块可见性坑（2026-10-11 踩到）**：`LmCarProbe` 是 `shared` 的 `internal`，
> **`common` 模块看不见它**（`internal` 是模块级）。`common` 里要判车型，必须走
> `BydOutputSettings.leapmotorHeadUnit()`（`shared` 的 public 包装）—— 不要再直接 `import LmCarProbe`。

### 关键基础设施差异（决定零跑能不能复用）

| 能力 | BYD 车机 | 零跑车机 | 影响 |
|---|---|---|---|
| **本地 ADB（`127.0.0.1:5555`）** | 可用（`adbd` 常开） | ❌ **不可用**（车机没有 adbd，已逆向确认 `VehicleSetting` 全包不用 ADB） | **所有走 `service call` / `app_process` 的 BYD 功能在零跑上都无法复用**，必须改用广播/AIDL |
| 车机厂商 SDK 反射 | `android.hardware.bydauto.*` | 无对应类 | 需换零跑自己的 API |
| CAN 信号获取 | `service call autoservice`（ADB）+ 部分广播 | **广播为主**（`leapmotor.cantoapp.update` + `apptocan.update` 一问一答） | 零跑走广播，需按信号码逐条逆向 |
| OEM 应用包名 | `com.byd.*` | `com.leapmotor.*` | 需重新定位对应包/Activity |

> ★ **重要更正（2026-10-11）**：本文档 §0 原写"零跑本地 ADB 未知"，**实为零跑车机没有 adbd**。
> 零跑侧已确认的可用通道及逐项可行性，见配套文档 **`零跑车机数据来源分析.md`**
> —— 该文档给出了 CAN 广播的信号码对照表（SOC/车速/充电/电压…）、`carcanserver` 的 `ICloudData` AIDL、
> 以及零跑导出的胎压服务 `Leapmotor.HealthInterface`。**做零跑适配请以那份文档为准。**

> **核心判断**：零跑适配**不能照搬** BYD 的 ADB 方案。零跑的可用通路是**系统广播**（已在档位、氛围灯、媒体键上验证），所以每个 BYD 功能的零跑版都要**重新逆向对应的广播协议**，而不是改改包名。

---

## 1. 仪表盘导航箭头（Cluster Navigation）

- **驾驶体验**：CarPlay 的转向箭头 + 距离显示在仪表盘上。
- **主要文件**
  - `hud/BydClusterBridge.kt`、`BydClusterFrame.kt`、`BydAmapAdapter.kt`
  - `hud/BydDiLink3ClusterOutput.kt`、`DiLink3ClusterModeSession.kt`、`DiLink3ClusterRecoveryJournal.kt`
  - `hud/BydClusterMapPause.kt`、`BydClusterNaviMode.kt`
  - `hud/BydFactoryNavigationOutput.kt`、`BydFactoryTurnCode.kt`、`BydManeuverCodes.kt`、`BydHudRouteState.kt`
- **通信机制**
  1. **广播 Intent**（DiLink 4/5）：action `AUTONAVI_STANDARD_BROADCAST_SEND`，显式发往 `com.byd.amapservice`（DiLink 3 为 `com.example.amapservice`），带隐藏 flag `0x01000000`，extras 含 `KEY_TYPE`(10001 引导 / 10019 状态)、`IS_BYD_MAP=true`、`NEW_ICON`、`ROUNG_ABOUT_NUM`、`SEG_REMAIN_DIS`、`NEXT_ROAD_NAME`、`ROUTE_REMAIN_*`。
  2. **ADB shell**（DiLink 3 模式切换）：`service call AutoContainer 2 i32 1000 i32 <16|17|18|35|39> s16 ""`
  3. **ADB shell**（导航态读取）：`service call autoservice 5 i32 1007 i32 1086337074`
  4. **反射**（仅 HUD 测试）：`android.hardware.bydauto.instrument.BYDAutoInstrumentDevice`
- **目标机型**：DiLink 3（简单导航模式 + 投影 `fission_bg_xdjaVirtualSurface`）/ 4 / 5
- **接线位置**：`hud/BydNavigationOutputs.kt`（start / onFrame），由 `BydOutputSettings.enabled` + `BydAmapAdapter.find{}` 命中来开启
- **零跑兜底**：❌ 无。（零跑侧另有一条**通用**的 `ClusterMapPresentation` / `DiLink51ClusterLayout` 显示通路，但那是"投屏"而非"箭头协议"，见 §8）
- **零跑适配建议**：需要逆向零跑仪表盘接收的导航广播（类似 `car.meter.*`），或走 `com.leapmotor.ICU2MMICtrl` 一类的通道；`BydManeuverCodes` 的图标码需重新映射。

---

## 2. 风挡 HUD 导航 —— SOME/IP 通道

- **驾驶体验**：CarPlay 引导信息投到风挡 HUD。
- **主要文件**：`hud/BydHudBridge.kt`、`BydHudPayload.kt`、`BydHudProtocol.kt`、`BydHudRouteState.kt`
- **通信机制**：**手写 Binder `transact`**（不用生成的 AIDL stub）绑定
  - 包 `com.ts.car.someip.service` / 类 `com.ts.car.someip.service.manager.SomeIpServerService`
  - action `com.ts.car.someip.SomeIpServerService`
  - 接口 token `ts.car.someip.sdk.ISomeIpServerInterface`
  - transactions：`1 registerCallback`、`4 startService(writeLong SERVICE_TOPIC)`、`6 fireEvent`
  - TOPIC：`SERVICE_TOPIC = 0x000B010A00010000`、`NAVIGATION_TOPIC = 0x0004010A00018001`
  - 载荷：`BydHudPayload` 手搓类 protobuf（字段 2/6/7/8/9/10/16/26/28），300 ms 重发
  - 图标：assets 目录 `byd-hud-icons/0x<code>.png`
- **目标机型**：BYD SOME/IP HUD（DiLink 5.0 实测）
- **接线位置**：`BydNavigationOutputs.start()`（当独立 HUD 通道不可用时）
- **零跑兜底**：❌ 无（零跑车机没有 SOME/IP 网关）
- **零跑适配建议**：**大概率无法复刻** —— SOME/IP 是 BYD 的以太网服务框架。若零跑有 HUD，需要先摸清它自己的 HUD 数据源（多为 CAN 信号），再另起一份实现。

---

## 3. 风挡 HUD 导航 —— 独立接收器广播通道

- **主要文件**：`hud/BydStandaloneHudOutput.kt`、`BydStandaloneNavigationBridge.kt`、`BydStandalonePackets.kt`、`BydStandaloneSession.kt`、`BydStarterBridge.kt`
- **通信机制**：**广播 Intent 到显式组件**
  - action `byd.hud.NAVIGATION` → component `com.byd.clusterdebug/com.byd.clusterdebug.BroadcastReceiverCAN`
  - extra `"normal"`（逗号分隔的 hex 记录）
  - **强门槛**：只在**唯一一台实测固件**上激活 —— 精确 fingerprint `BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys` + 包 versionCode `10601004` + **硬编码签名证书 SHA-256** `efe3ca8ada0d…33efc`
- **目标机型**：单一 DiLink 5.1 固件
- **接线位置**：`BydNavigationOutputs.start()` 优先于 SOME/IP
- **零跑兜底**：❌ 无，`available()` 在该固件外**直接硬失败**
- **零跑适配建议**：**直接放弃**。这是给一台特定测试机的补丁，不是可移植功能。零跑若要 HUD，建议从 §2 的思路另立一份。

---

## 4. 仪表盘歌曲卡片（CarPlay Now-Playing）

- **驾驶体验**：CarPlay 正在播放的歌显示在仪表盘音乐卡片上。
- **主要文件**：`hud/BydClusterSong.kt`（工具类 `BydClusterSongTool`）、`ClusterSongState`
- **通信机制**：**ADB shell → `app_process` 反射** `android.hardware.bydauto.instrument.BYDAutoInstrumentDevice`
  - `setMediaState(1007, 0x33F00030|0x43E0000A, v)`、`setMediaInfo(1007, 0x43FB1008, UTF-16LE bytes)`
  - `getInstance` 被拒 → 回退**私有构造器反射**
  - 启动方式：`CLASSPATH=<apk> app_process /system/bin <ToolClass>`
  - 设置项：`clusterSong` + `clusterSongOnChange`（prefs `diplay_byd_outputs`）
- **接线位置**：`BydNavigationOutputs.clusterSongChanged()`
- **零跑兜底**：❌ 无
- **零跑适配建议**：零跑仪表盘歌曲大概率也是广播驱动（参考 `car.meter.music.BROADCAST` 一族）。需要抓包确认零跑"仪表盘音乐卡片"的写入通道。

---

## 5. CarPlay 来电显示到仪表盘 / HUD（含方控接挂）

- **驾驶体验**：CarPlay 来电显示在仪表盘/HUD，方控键可接听/挂断。
- **BYD 侧文件**：`hud/BydCarPlayCall.kt`（工具 `BydCarPlayCallTool`）、`hud/CarPlayCallKeyPolicy.kt`、`common/CarPlayCallKeys.kt`、`hud/CarPlayCallWatchOwnership.kt`、`hud/CarPlayCallWrites.kt`
- **BYD 通信机制**
  1. **ADB shell → `app_process` 反射** `BYDAutoInstrumentDevice`（`setMediaState`/`setMediaInfo`）
     - feature id 来自 `android.hardware.bydauto.BYDAutoFeatureIds` / `$Audio` / `$Setting`：
       `INSTRUMENT_CALL_STATE_SET`、`SET_CALL_STATE_SET`、`SET_CMD_BTCALL_STATE_SET`、`AUDIO_CARPLAY_CALL_STATUS`、`INSTRUMENT_CALL_INFO_SET`、`INSTRUMENT_CALL_TIME_{HOUR,MINUTE,SECOND}_SET`
     - 独立 watcher（`setsid nohup app_process`）每秒发一次通话时长；所有权 token 落在 `/data/local/tmp`
  2. **广播接收**（方控通话键）：`com.byd.btcall.action.CLOSE_BLUETOOTHSETTING`（extra `keycode`）；BYD keycode `313`（接听/拨号）、`314`（挂断）、`309`（复用）
- **目标机型**：**明确要求 DiLink 3**（`BydCallPopupTool` 里有 SDK 32 检查；`SetupGuide` 把 CALL_KEYS / CALLS_ON_DASHBOARD 标为**仅 DiLink 3 实验性**）
- **接线位置**：`BydNavigationOutputs.carPlaySessionStarted/onFrame`，由 `BydOutputSettings.carPlayCalls` + `carPlayCallControls`（默认都 false）开启

### ✅ 零跑兜底（2026-10-11 已实现）

- **新文件 `shared/.../hud/LeapmotorCarPlayCallOutput.kt`**
  - 数据源：CarPlay 的 iAP2 `CallStateUpdate`(0x4155) —— 与平台无关，**复用 `CarPlayCallState`**
    （BYD/零跑通用的解析器，`BydNavigationOutputs.onFrame` 里按车型分派）
  - 出口：**广播** `car.hmi.sender.BROADCAST`，extra `sender` = `[u16 大端长度][UTF-8 JSON]`，
    JSON = `{"type":"call","data":{"state":"ring|answer|end","name":"…"}}`
  - **无 ADB、无反射、无 root** —— 零跑车机没有 adbd，这是唯一可行通路
  - 同时监听 `com.leapmotor.phonecall`（`MMI_PhoneSts`）以知晓车机原生通话状态
- **接线**：`BydNavigationOutputs.start/onFrame/carPlayCallsChanged/carPlaySessionStarted/carPlayCall/endNow`
  全部按 `BydOutputSettings.leapmotorHeadUnit()` 分支；设置项 `carplay_calls` 在零跑上**无需 ADB**
  （`applyCarPlayCalls` 用 `lmHeadUnit()` 跳过 `checkAdbState`）
- **方控接挂**：`CarPlayCallKeyPolicy` 本身平台无关，接挂走 iAP2（`controller.answerCall()/endCall()`），
  **零跑上直接可用**；原生蓝牙通话的方控由车机自己处理（`WheelKeyService.inCall()` 会把键让回去）
- **单测**：`shared/src/test/.../LeapmotorCarPlayCallOutputTest.kt` 锁定 JSON + 线路格式
- **待实车确认**：`data.state` 拼写与仪表实际渲染效果

- **零跑适配建议（遗留）**：若零跑仪表盘不渲染 `type=call`，则需按 `小零助手` 的思路改为"让位"策略
  （通话中抑制 DiPlay 自己的仪表卡片，保留车机原生来电 UI）。

---

## 6. 原生来电弹窗屏蔽（Hide BYD call popup）

- **驾驶体验**：CarPlay 前台时，隐藏 BYD 原生蓝牙来电弹窗（避免遮挡）。
- **主要文件**：`hud/BydCallPopupGuard.kt`、`hud/BydCallPopupLease.java`、`hud/BydCallPopupTool.java`
- **通信机制**：**ADB shell → `app_process`**，helper 以 **uid 2000 (shell)** 运行，**仅 SDK 32**
  - **AppOpsManager 反射**：`getOpsForPackage` / `setMode`，目标包**硬编码 `com.byd.bluetoothcall`**，把 `OP_SYSTEM_ALERT_WINDOW` 设为 `MODE_IGNORED`
  - 10 秒心跳租约；退出时恢复原 mode
  - 控制通道：**文件读写** `/data/local/tmp/diplay-call-popup/<token>.{control,status}`
- **目标机型**：有 `com.byd.bluetoothcall` 的 BYD 固件（SDK 32）
- **接线位置**：设置项 `settings_hide_byd_call_popup`（`DiPlayActivity.kt:1841`），由 `AirPlayPersistence.loadHideBydCallPopup` 持久化
  - ⚠️ **当前实现缺陷**：该开关**没有车型/包名前置判断**，只判 `SDK_INT == 32`。零跑（Android 7，SDK 25）上虽然不满足 SDK 32 所以不显示；但**若将来零跑升到 SDK 32，这个 BYD 专属开关会误显示**。
- **零跑兜底**：❌ 无，且**不建议做** —— 零跑既没有 `com.byd.bluetoothcall`，也没有 adbd（AppOps 反射改不了别人）。
  **结论：此项在零跑上放弃**（用户已排除投屏/HUD，本项不在必做清单内）。
- **零跑适配建议**：唯一该做的是**修门控** —— 把 `SDK_INT == 32` 改成 `isBydHeadUnit() && SDK_INT == 32`，
  避免将来误显示。这是顺手要修的实现问题。

---

## 7. 车辆数据（Battery / Range / Charging / Speed / Gear / Tyres）

> ✅ **2026-10-11：电量/续航/充电/车速/档位已全部落到零跑**（`LeapmotorVehicleSignals` CAN 信号总线，
> 见配套文档 `零跑车机数据来源分析.md` §2）。本节保留 BYD 侧原貌，**只有 §7.3 胎压仍是待办**。

> 这是 BYD 独有功能里**体量最大**的一块 —— 6 个子功能，几乎全部走 ADB `service call`。

### 7.1 电量 / 续航 / 充电状态

- **文件**：`hud/BydBattery.kt`（`BydBatteryProtocol` / `BydBatteryStatus` / `BydBatteryCache`）、`hud/BydVehicleFields.kt`
- **机制**：**ADB shell** `service call autoservice <txn> i32 <device> i32 <fid>`
  - SOC：`service call autoservice 7 i32 1014 i32 <id>`（CAN `1033543720` / CANFD `1246777400`，float）
  - 续航：`service call autoservice 5 i32 1014 i32 <id>`（`1033203771` / `1246765118`）
  - 剩余 kWh：`service call autoservice 7 i32 1005 i32 882901008`（仅 CANFD）
  - 充电中：`service call autoservice 5 i32 1009 i32 <id>`（`876611608` / `876609560`）
  - **系统属性** `getprop ro.car.protocol` / `sys.car.protocol` 选 CAN vs CANFD
  - 30 秒轮询，喂给 CarPlay `VehicleStatusSnapshot`（Apple 地图低电量/充电桩提醒）
- **接线**：`BydNavigationOutputs.batteryStatus()`；设置 `batteryToIphone`（prefs `diplay_byd_outputs`）
- **零跑兜底**：❌ 无

### 7.2 车速 + 档位（航位推算）

- **文件**：`hud/BydWheelSpeed.kt`、`hud/BydParkedState.kt`
- **机制**：**ADB shell**
  - 车速：`service call autoservice 7 i32 1013 i32 -1807745016`（float km/h，device 1013=S 速，4 次/秒）
  - 档位：`service call autoservice 5 i32 1011 i32 555745336`（device 1011=GEARBOX：1 P、2 R、3 N、4/5/6 D，1 次/秒）
- **接线**：`BydNavigationOutputs.wheelSpeed()` / `parked()`；设置 `wheelSpeedToIphone` / `videoWhileParked`
- **零跑兜底**：
  - ✅ **档位已有** —— `common/LeapmotorGearMonitor.kt`，走广播 `leapmotor.apptocan.update` → 响应 `leapmotor.cantoapp.update`，extra key `"1010105"`（`0xf69b9` VCU 档位），`1=R 2=N 3=D 其它=P`。用于视频门控 + 倒车暂停。
  - ❌ **车速无兜底** —— 零跑侧没有车速采样。**待逆向**：零跑 CAN 广播里应存在车速信号码，需抓包补齐。
- **注意**：零跑**没有 P 挡读数**，视频门控改为 **N 挡（raw==2）** 放行；收到任何档位数据前一律返回 null（禁止视频），避免把"无数据"当可用。

### 7.3 胎压 / 胎温

- **文件**：`hud/BydTyres.kt`（`BydTyres` / `BydTyreStatus`）
- **机制**：**ADB shell**
  - 胎压：`service call autoservice 5 i32 1016 i32 <id>`（device 1016=TYRE，id `-1728052956/-952/-948/-944`）
  - 胎温：`service call autoservice 5 i32 1007 i32 <id>`（device 1007=INSTRUMENT，CAN-FD，id `1246797848..1246797884`）
  - 侧栏显示时每 10 秒读一次；回复里没有 `Parcel(` 就永久停止（无 BYD autoservice 即放弃）
- **接线**：UI 侧栏，由 `BydTyreStatus.start()` 启动
- **零跑兜底**：❌ 无

### 7.4 车辆能力探测（DiLink 3 / 控制器-13 目录扫描）

- **文件**：`hud/BydVehicleCapabilityProbe.kt`、`hud/BydVehicleFields.kt`（`BydVehicleFieldStore`）、`hud/Byd13CatalogProbeMain.kt`、`common/BydVehicleSettingsBackend.kt`
- **机制**：**ADB shell → `app_process` 反射**（`Byd13CatalogProbeMain` 扫描 `BYDAutoFeatureIds` / `BYDAutoConstants` 里的 6 个符号与 device id），再逐个 `service call autoservice …` 实测；结果存 prefs `diplay_byd_vehicle_fields`；`legacyVehicleProbe` 模式改用存档地址
- **接线**：`BydVehicleSettingsBackend`；设置里"高级车辆数据"卡片（`bydVehicleAdvancedExpanded` / `showVehicleProbeResults`）
- **零跑兜底**：❌ 无
- **零跑适配建议**：**可能需要一份等价的"能力探测"**。零跑不同配置（S01 380/460、标准/PRO）暴露的 CAN 信号码可能不同；`LmCarProbe` 已能读到车型码（`/data/leapmotor/CarType` 偏移 7），可作为能力分档的依据。

---

## 8. OEM 仪表盘地图抑制（OEM Cluster Hold）

- **驾驶体验**：隐藏车机自带仪表盘地图（避免与 CarPlay 投屏叠图）。
- **文件**：`hud/BydOemClusterNavi.kt`、`hud/BydOemClusterHold.kt`、`hud/OemClusterHoldSession.kt`
- **机制**：**ADB shell `pm`**
  - `pm disable-user/enable/default-state --user 0 com.byd.automap`（整包）
  - 或 `com.byd.automap/com.byd.automap.extra.MeterActivity`（单组件）
  - journal prefs `diplay_oem_cluster`；设置 `oemClusterHold`（默认 OFF）
- **目标机型**：DiLink 4（包 `com.byd.automap`）
- **接线**：`BydNavigationOutputs.onAppOpened()`、`DiPlayActivity.kt:1947`
- **零跑兜底**：❌ 无（依赖已安装的 `com.byd.automap`）
- **零跑适配建议**：需定位零跑仪表盘地图的应用/Activity 名（`com.leapmotor.*`），机制本身（`pm disable-user`）通用。

---

## 9. 蓝牙挂起 / 开机自启修复 / Wi-Fi 扫描暂停

> 机制是**通用 AOSP**，但只挂在 BYD 门控的代码路径上。零跑上"能不能用"取决于**本地 ADB 是否可用**。

### 9.1 蓝牙挂起（CarPlay 期间停用车机蓝牙）

- **文件**：`hud/BydBluetoothSuspend.kt`
- **机制**：**ADB shell** `svc bluetooth enable` / `svc bluetooth disable`；用 `BluetoothAdapter.state` 校验；journal prefs `diplay_bt_suspend`
- **接线**：`BydNavigationOutputs.onAppOpened()`、无线握手前 `resumeAndWait`
- **零跑兜底**：❌ 无（但**命令通用**，若零跑本地 ADB 可用则大概率能直接跑）

### 9.2 开机自启修复

- **文件**：`hud/BydBootStartRepair.kt`
- **机制**：**ADB shell** `appops set <pkg> RUN_ANY_IN_BACKGROUND allow`、`appops set <pkg> RUN_IN_BACKGROUND allow`、`cmd deviceidle whitelist +<pkg>`；用 `appops get` / `dumpsys deviceidle whitelist` 校验
- **接线**：`DiPlayActivity.kt:2662 repairBootStart()`、ADB 授权后 `:2670`
- **零跑兜底**：❌ 无（命令通用，但**零跑 Android 7 上 `cmd deviceidle` 可能不存在**，需实测降级）

### 9.3 Wi-Fi 扫描暂停（无线 CarPlay 稳定性）

- **文件**：`shared/network/WifiScanPause.kt`
- **机制**：**ADB shell** `service call wifi <txn> i32 <0|1>`，txn 由**反射** `android.net.wifi.IWifiManager$Stub` 查得（**仅 Android Q / DiLink 3**）
- **接线**：`BydNavigationOutputs.onAppOpened()` → `WifiScanPause.restoreIfNeeded`
- **零跑兜底**：❌ 无，`eligible()` 在非 SDK Q 直接 false → **零跑（SDK 25）永久不生效**
- **零跑适配建议**：零跑是 Android 7（SDK 25），`IWifiManager` 的 txn 表完全不同。若零跑无线 CarPlay 受扫描干扰，需**按 API 25 重新查 txn**。

---

## 10. 方控 / 媒体按键（已有零跑通路 ✅ 参考实现）

> 列在这里是因为它是**唯一一个被完整移植的"按键类"功能**，可作为其他功能移植的模板。

- **BYD 侧文件**：`common/WheelKeyService.kt`、`shared/airplay/CarPlayMediaButton.kt`、`hud/CarPlayCallKeyPolicy.kt`
  - BYD 键码来自 `simulate-keys`（注入侧）：`WheelKey(305,300) MODE`、`(291,115) ZOOM_IN`、`(292,114) ZOOM_OUT`、`(289,89) JOYSTICK`、`(88,268) PREVIOUS`、`(87,270) NEXT`、`(353,505) SELECT`
  - 另有 `KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE=353`、`..._VOICE=304`、`..._VOICE_LONG=312`、`..._PLAY_PAUSE_DILINK3=331`
- **零跑侧文件**：`common/LeapmotorMediaKeys.kt`（`LeapmotorMediaProtocol`）
  - 机制：**广播 + byte[] 解码**。每次按键到达一个并行广播，extra `receiver` 是 byte[]，前两字节是长度 `(b0*0x64)+b1`，从 offset 2 起是 UTF-8 JSON，例：`{"data":{"action":"nextOne","type":-1},"type":"music"}`
  - 频道：`car.meter.music.BROADCAST`（**S01 实测**）、`car.hmi.music.BROADCAST`、`com.leapmotor.command.music`、`com.leapmotor.command.multimedia`
  - ⚠️ `data.type` 实车恒为 -1，**任何逻辑都不得用"媒体源"值做门控**
- **接线**：`CarPlayHostActivity.kt:5013-5016`（`attach`）、`:5398` / `:5596`（`detach`）

---

## 11. 氛围灯（已有零跑通路 ✅ 本次刚接）

- **BYD 侧文件**：`hud/BydAmbientLightClient.kt`、`BydAmbientLightPolicy.kt`、`BydAmbientLightWorker.kt`、`shared/media/AmbientLampSinks.kt:BydAdbLampSink`
  - 机制：**ADB shell → `app_process` 反射** `android.hardware.bydauto.BYDAutoDeviceManager`，feature id 来自 `BYDAutoFeatureIds$Setting`（`SET_INTERIOR_ATMOSPHERE_LAMP_*`），device type 1023；域：颜色 1..31 / 亮度 1..6 / 区域 1..3
- **零跑侧文件**：`shared/LmCarProbe.kt`、`shared/LmAmbientLightProtocol.kt`、`shared/media/LmAmbientMusicFlow.kt`、`shared/media/AmbientLampSinks.kt:LeapmotorLampSink`
  - 机制：**广播 Intent** `leapmotor.carapp.CARCANCONTROL`，extra `appCAN="Atmos_ctrl"` + 6 个 int：`lampSW/lampAuto/lampDeg/lampR/lampG/lampB`；单向无回执
  - 开关映射：`SW=0,Auto=1`=自动；`SW=1,Auto=0`=开；`SW=2,Auto=0`=关
  - `lampDeg` 是**亮度/角度共用**字段（ACache key `fendeng`），范围 **0..10**，`0` 是正常档位**不是**哨兵；**协议里没有独立亮度项**
- **车型识别（关键）**：`LmCarProbe.isLeapmotor()` 读普通文件 `/data/leapmotor/CarType`（存在即零跑），运行时选渠道，**不按构建渠道写死**；BYD 通路逐字节保留
- **接线**：`shared/media/AmbientMusicController.kt:lampChannel`（`by lazy`）

---

## 12. 车型识别 / 设置后端 / 设置门控

- **`setup/DiLinkGeneration.kt`**：判定 DiLink 3/4/5/UNKNOWN，用于设置向导
  - 机制：正则 `DiLink\s*([345])` 匹配 `Build.PRODUCT/FINGERPRINT/DISPLAY`；回退**系统属性** `apps.setting.product.outswver`（反射 `android.os.SystemProperties`）；再按控制器版本前缀映射（13/15/18→3，16/17/21→4，23/34/57/59→5）；`BYD-AUTO/IVI/` + SDK≥33 → DiLink 5
  - 持久化 prefs `diplay` / `setup_dilink_generation`
  - **零跑兜底**：❌ 返回 `UNKNOWN`。**纯 BYD**
- **`hud/BydOutputSettings.kt`**：所有 BYD 输出的总开关 + 可用性门控
  - `available()` = 有 BYD 接收器包（`com.ts.car.someip.service` / `com.byd.amapservice` 等）**或** 装了 BYD 包（`com.byd.carsettings` / `com.byd.appmgr` / `com.byd.deviceinfo` / `com.byd.service`）**或** `Build.FINGERPRINT/BRAND/MANUFACTURER/PRODUCT/DEVICE` 含 `BYD`
  - `navigationAvailable()` = 独立 HUD / AMap 适配器 / `com.ts.car.someip.service`
  - **零跑兜底**：❌ 全部失败 → BYD 导航卡片直接隐藏（`DiPlayActivity.kt:1301`）
  - ✅ **2026-10-11 新增 `leapmotorHeadUnit()`**：`shared` 的 public 包装，转发 `LmCarProbe.isLeapmotor()`，
    供 `common` 模块判车型（`LmCarProbe` 是 `shared` 的 `internal`，`common` 看不见）
- **`common/CarHotspotSetup.kt:isBydHeadUnit()`**：= `BydOutputSettings.navigationAvailable()` **或** `com.byd.carsettings` 是系统包
  - **接线**：`DiPlayActivity.kt` 的 `bydAdbSettings()`（`:2489`）、`wheelKeysSettings()`（`:3466`）
  - **零跑兜底**：❌ 无

### 设置门控现状（`DiPlayActivity.kt`）

| 位置 | 门控 | 零跑上表现 |
|---|---|---|
| `:1301` `navigationSettings` | `!BydOutputSettings.available()` | 显示"BYD 导航不可用"提示 |
| `:1564` `clusterSongSwitch` | `!BydOutputSettings.available()` | 隐藏 |
| `:2236` `SettingsSection.BYD_NAVIGATION` | `BydOutputSettings.available()` | 整段隐藏 |
| `:2489` `bydAdbSettings` | `isBydHeadUnit()` | 整段隐藏（记 log `settings hidden: BYD head unit not detected`） |
| `:2856` `SetupGuide.Feature.BYD_NAVIGATION` | `BydOutputSettings.available()` | 向导不显示 |
| `:3466` `wheelKeysSettings` | `isBydHeadUnit()` | 方控段降级（仅保留外部控制器开关） |
| `:1841` `settings_hide_byd_call_popup` | ⚠️ **仅 `SDK_INT == 32`** | 零跑 SDK 25 → 隐藏（**但门控不严谨，见 §6**） |
| `SettingsSection.AMBIENT_LIGHTING` (`:1915`) | **无门控** | ✅ 两车型都显示（正确，因为已接零跑） |

---

## 13. 纯 BYD、零兜底的文件清单（供批量排期）

**HUD**
`BydHudBridge.kt` `BydHudPayload.kt` `BydHudProtocol.kt` `BydHudRouteState.kt`
`BydStandaloneHudOutput.kt` `BydStandaloneNavigationBridge.kt` `BydStandalonePackets.kt` `BydStandaloneSession.kt` `BydStarterBridge.kt`

**仪表盘导航**
`BydClusterBridge.kt` `BydAmapAdapter.kt` `BydClusterFrame.kt` `BydDiLink3ClusterOutput.kt`
`DiLink3ClusterModeSession.kt` `DiLink3ClusterRecoveryJournal.kt` `BydClusterMapPause.kt` `BydClusterNaviMode.kt`
`BydFactoryNavigationOutput.kt` `BydFactoryTurnCode.kt` `BydManeuverCodes.kt`

**仪表盘歌曲 / 来电**
`BydClusterSong.kt` `BydCarPlayCall.kt` `CarPlayCallKeyPolicy.kt` `CarPlayCallWrites.kt` `CarPlayCallWatchOwnership.kt`

**来电弹窗屏蔽**
`BydCallPopupGuard.kt` `BydCallPopupLease.java` `BydCallPopupTool.java`

**车辆数据**
`BydBattery.kt` `BydWheelSpeed.kt` `BydTyres.kt` `BydVehicleFields.kt` `BydVehicleCapabilityProbe.kt` `Byd13CatalogProbeMain.kt`

**系统类**
`BydBootStartRepair.kt` `BydBluetoothSuspend.kt` `WifiScanPause.kt`（通用命令但只挂 BYD 路径）
`BydOemClusterHold.kt` `BydOemClusterNavi.kt` `OemClusterHoldSession.kt`

**识别 / 设置后端**
`BydAdbAccess.kt` `BydAdbShell.kt` `setup/DiLinkGeneration.kt` `common/BydVehicleSettingsBackend.kt`
`hud/BydOutputSettings.kt` `hud/BydNavigationOutputs.kt` `common/CarHotspotSetup.kt`(BYD 分支)
`DiLink4ClusterDisplay.kt` `DiLink51ClusterLayout.kt` `DiLink51ClusterMonitor.kt` `ClusterActivityState.kt`

**已有零跑的例外（勿重复开发）**
- 氛围灯：`AmbientLampSinks.kt`、`AmbientMusicController.kt`、`LmCarProbe.kt`、`LmAmbientLightProtocol.kt`、`LmAmbientMusicFlow.kt`
- 档位：`LeapmotorGearMonitor.kt`（视频门控改 N 挡）
- 媒体键：`LeapmotorMediaKeys.kt`
- 显示通路分支：`ClusterMapPresentation.kt`、`CarPlayVideo.kt`

---

## 14. 建议的零跑适配顺序

| 优先级 | 功能 | 理由 |
|---|---|---|
| **P0** | 车速信号（§7.2 补齐） | 同道已有档位；**信号码已找到**（`1100412` / `100001` / `1100409`） |
| **P0** | 电量/续航/充电（§7.1） | **信号码已找到**（`1060115` SOC / `1030505` 快充 / `1030506` 慢充） |
| **P1** | 仪表盘导航箭头（§1） | 核心体验，但需逆向零跑导航广播，工作量大 |
| **P1** | 仪表盘歌曲卡片（§4） | 有 `car.meter.music.*` 广播线索，可行性高 |
| **P2** | OEM 仪表盘地图抑制（§8） | 机制通用，只需定位零跑包/Activity 名 |
| **P2** | 胎压/胎温（§7.3） | **零跑有导出服务** `Leapmotor.HealthInterface`（见配套文档 §6.1） |
| **P3** | 来电卡片（§5） | 需先确认零跑仪表盘是否支持显示来电 |
| **P3** | 蓝牙挂起 / 开机修复（§9.1/9.2） | 命令通用，取决于零跑本地 ADB 是否可用 |
| **P4** | Wi-Fi 扫描暂停（§9.3） | 需按 API 25 重查 txn，收益不确定 |
| **放弃** | SOME/IP HUD（§2）、独立 HUD 广播（§3）、DiLink 探测（§12） | BYD 专有框架 / 单机补丁，无移植价值 |

### 动手前必须先确认的三件事

> ⚠️ **本节已被 `零跑车机数据来源分析.md` 取代**（2026-10-11）。第 1 条原写"确认零跑本地 ADB 是否可用"——
> 已确认**不可用**。请直接看那份文档的 §7/§8。

1. ~~**零跑车机本地 ADB（`127.0.0.1:5555`）是否可用？**~~ → **不可用**。零跑没有 adbd；`VehicleSetting` 全包不用 ADB。零跑适配一律走广播 / AIDL。
2. **零跑 CAN 广播的完整信号表**：已在 `零跑车机数据来源分析.md` §2.2 解出 15 个信号码（含 SOC / 车速 / 充电 / 电压 / SOH）。
3. **实现顺序**：建议把现有 `LeapmotorGearMonitor` 升级为 `LmVehicleSignals`（一条请求解全部 key），再让各功能订阅其快照 —— 一次性补齐电量/车速/充电等多个功能。详见该文档 §8.1。

---

## 附：验证方法

改动后用 CI 构建（**严禁本地构建**），关键验证点：

```bash
# 1. 静态检查（会漏类型/可见性错误，别只信它）
python D:\Launcher\kotlin_static_check.py <改动的 .kt>

# 2. CI job 日志（返回纯文本不是 zip）
curl -s -H "Authorization: token $GH_TOKEN" \
  "https://api.github.com/repos/ding2548-ui/DiPlay/actions/jobs/<job_id>/logs"

# 3. 车型识别（运行期，报告里应出现）
#    DiPlay-Ambient: leapmotor probe file=true model=<n> path=/data/leapmotor/CarType
```

- 诊断可见性：只有 TAG `xcertplay-usb` 的 `Log.i/w` 会进报告，其余只进 logcat。
- `DiagnosticRedactor` 的 secret 正则会**整行静默丢弃**命中行 —— 打点用 `apMac=` / `nameLength=` / `pskLength=`，别用 `bssid` / `ssidLength` / `passLength`。
