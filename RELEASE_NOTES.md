# DiPlay 0.2.17 · PSA 车机版（beta 测试渠道）

> **这是 PSA 线的 beta 渠道**，从 `psa-beta` 分支发布，包名 `com.shihab.diplay.psabeta`
> （可与正式包 `com.shihab.diplay` 共存安装），发版 tag 走 `v0.2.17-beta-<run>`，
> 与正式渠道的 `v0.2.12-<run>` **互不可见**（在线更新各自只看自己的命名空间）。
> 每次发版同时创建 `v0.2.15-beta-` / `v0.2.12-beta-` 别名 release，旧版本也能自动更新。
> 正式渠道的包请到 `v0.2.12-` 的 release 下载。
>
> **本版已全量并入上游 0.2.18**（在有线 USB 稳定性、音乐丢包修复、连接阶段多语言、
> 方控、设置备份/恢复二维码、更新安装器选择之外，也把上游的厂商专属功能一并带入源码，
> 默认关闭、按车型才启用）。**唯一保留本线自己实现的是有线 / 无线连接核心**——
> 上游的连接实现在 Android 7 上有已知问题，换掉会让车机连不上，其余全部按上游。
> 发版 tag 命名空间本轮仍是 `v0.2.17-beta-`（版本号提升单独一轮做，避免与在线更新规则耦合）。

Android 7.1.2（API 25）/ Qualcomm msm8953 车机上的 CarPlay 接收端。
**本页是当前整体状态页，每次发布前更新。**

---

## ✅ 可用

### 连接

- **有线 CarPlay（VPN/NCM 通路）**：设备发现、重枚举、配对、NCM 数据通路、MFi 认证、
  `0x4300` / `0x4301`、出画面（`Video: first frame rendered`）、音频（`audioType=media`）全部正常。
- **无线 CarPlay** —— 三种模式，按车机条件任选：
  - **Wi-Fi Direct**：✅ 可用。车机自建 P2P 组，iPhone 直连，不依赖车机热点，
    **也不需要把密码交给 iPhone**。API 25 上信道由系统选择（见「已知限制」）。
  - **车载自建热点**：⚠️ 可用但**不稳定**。车机自建热点由系统持有，DiPlay 只读它的配置
    （SSID / 安全类型 / 信道）并把凭据交给 iPhone，由 iPhone 自己去入网。
    **首次入网常失败**：30 秒内 iPhone 不发起 AirPlay TCP 连接
    （报告里是 `No AirPlay TCP after CarPlay StartSession`），重试几次通常成功。
    2026-10-09 的日志里第 4 次才成功；同一份构建在 iPhone 已在该网上时 3/3 秒连。
  - **外置 Wi-Fi / 同一局域网**：⚠️ 尚未复现成功。车机与 iPhone 连同一个网络，
    但 iPhone 始终不发起 AirPlay TCP 连接（报告里 `mdnsFamilies=IPv4,IPv6`、两个
    `airplay listener ready`）。
  - **本版起，交给 iPhone 的 Wi-Fi 密码优先取车机自己那份配置**（热点走
    `getWifiApConfiguration()`，外置 Wi-Fi 走车机自己保存的网络配置），设置里填的那份
    只在车机不暴露时使用；报告里 `Manual hotspot pskMatches=` / `Existing Wi-Fi pskMatches=`
    给出两者的比对结果。

### 功能

- **方向盘方控**：
  - **S01**：通道 A（`car.meter.music.BROADCAST` 的 JSON）—— 实测正确。
  - **T03**：通道 B（`com.leapmotor.customkey.music.pauseplay` 的整数 extras）—— **可用**。
- **方控音频归属**：焦点与 MediaSession 已拿到，原厂播放器会让出方控。
- **媒体信息投送**：曲名 / 艺术家 / 封面经 MediaSession 提供给车机仪表等读取方。
- **氛围灯** 🆕：设置 →「氛围灯」。开关灯、随音乐流光（单色 / 多色）、颜色（31 色 / 专辑取色）、
  速度、灯光档位、分区。**同一份 APK 自动识别车型**：检测到零跑车机（`/data/leapmotor/CarType`）
  就走车机自己的 `leapmotor.carapp.CARCANCONTROL` 广播（`appCAN=Atmos_ctrl`，与 `VehicleSetting.apk`
  同协议）；其它车机（含 BYD）仍走原有的 ADB 通路，BYD 行为不受影响。
  **不再以「亮度」判断是否驱动**：只看开关（与「随音乐」），零跑协议本身没有亮度参数。
- **整车 CAN 信号读取** 🆕：零跑车机通过 `leapmotor.cantoapp.update` 广播一次性回传**全部**车辆信号，
  本版把它接成一条总线（`LeapmotorVehicleSignals`，1 Hz 轮询）。只认零跑车机
  （`/data/leapmotor/CarType` 存在才启动轮询），不再走 ADB（**零跑车机没有 adbd**）。
  已知信号：档位 `1010105`、车速 `1100409`/`1100412`/`100001`、电池 SOC `1060115`（回落电源 SOC `1030115`）、
  SOH `1060306`、电压 `1060122`、快/慢充 `1030505`/`1030506`、EPB `1110113`。
  `-1` / `-1.0f` 表示「本帧未上报」，**绝不当作真实的 0**。
- **iPhone 车辆状态（电量/续航）** 🆕：由上面那条总线供数，CarPlay 里 Apple 地图能看到电量、
  低电量提醒与充电状态。车机**不上报剩余续航**，续航按 SOC × 满电续航估算（默认 400 km，
  S01 有 380/460 两版）；能量字段只给电量比例（约 150 Wh/km），仅用于比例。
  设置 →「电量同步到 iPhone」仍可总开关。
- **iPhone 航位推算（车速/档位）** 🆕：车速与档位经 `$PASCD` 供 iAP2，进隧道无 GPS 时
  iPhone 能继续推算位置。采样率受 1 Hz 总线限制，iPhone 在样本间自行外插。
- **CarPlay 来电卡片（零跑）** 🆕：设置 →「CarPlay 来电显示到仪表盘」。iPhone 的来电/去电
  通过 iAP2 `CallStateUpdate` 取得，再以 `car.hmi.sender.BROADCAST`（`sender` = 2 字节大端长度 +
  UTF-8 JSON，`{"type":"call","data":{"state":"ring|answer|end","name":"…"}}`）送到零跑仪表盘。
  **全程只用普通广播，不需要 ADB**（零跑车机没有 adbd）；BYD 车机仍走原来的 ADB 通路，行为不变。
  方控的接听/挂断走 iAP2（与 BYD 同一套），零跑上直接可用。
  设置项在零跑上**不需要 ADB 支持性探测**，打开即可。
- **USB 权限弹窗**：`MANAGE_USB` + 无障碍按内容判定自动确认。
- **自动开启热点** 🆕：连接设置 →「车载热点（推荐）」→「保存热点信息并使用此模式」下方的开关。
  打开后**每次无线连接（含开机/事件自动连接）都会在热点未开时自动尝试打开车机热点**：
  反射 `startTethering`（走车机自己保存的热点配置，不动名称/密码/频段），连接结束后**不关热点**。
  需要「修改系统设置」权限，开关打开时引导到系统设置授权（无需 ADB）；BYD 高级组里的原开关
  仍保留（同一条实现通路），**本版起零跑等无 ADB 车机也能用这个功能**。
- **蓝牙自动启动** 🆕：设置 → 连接 →「自动连接」卡片（「自动确认 USB 权限」下方）。
  从已配对设备里**多选信任名单**；名单里的 iPhone 蓝牙连上车机 → 自动打开 DiPlay 并走自动连接流程。
  会话进行中不触发，15 s 去重；与原有的「iPhone 蓝牙连接时自动连接」（单选手机）并存，互不影响。
- **Wi-Fi 自动启动** 🆕：同一卡片。填入目标 Wi-Fi 名称（SSID）；**车机连上这个网络**（例如手机热点）
  → 自动打开 DiPlay 并走自动连接流程。适合「外置 Wi-Fi / 同一局域网」模式：车机入网即拉起连接。
- **修复：自动连接卡片按钮重复渲染** 🔧：「自动确认 USB 权限」下方的「自动应用权限」按钮 /
  提示行此前被添加了两次，本版去重。
- **修复：点击连接手机闪退** 🔧：`CarPlayHostActivity.onCreate` 注册 showmap 广播接收器时未声明
  `RECEIVER_EXPORTED`，Android 13+（API 33+）抛 `SecurityException` 导致 Activity 无法启动
  （Android 7 真车无此检查；诊断报告 20261013-000740-569，小米 Android 16 测试机必现）。
  按代码库既有 SDK 分支模式修复三处：showmap 接收器（EXPORTED）、Leapmotor 方控广播接收器
  （EXPORTED）、方控学习广播绑定（EXPORTED）；全树 registerReceiver 已扫净，其余为受保护
  系统广播或已有分支。
- **修复：自动开启热点在本车机不生效** 🔧：此前只反射 4 参 `startTethering(int, ResultReceiver,
  boolean, String)`——该签名 Android 8 才引入，本车机（Android 7.1.2）的绑定接口只有 3 参
  形式，反射直接 `NoSuchMethodException` → 误报「固件不支持自动开启热点」→ 无线连接失败、
  一直提示重连（诊断报告 20261014-184116-352）。现按签名级联尝试：4 参（Android 8+）→
  3 参（Android 7.x 绑定接口）→ 传统的 `setWifiApEnabled(null, true)`（用车机自己保存的
  热点配置启动，不动名称/密码/频段）。开启成功后仍由原有轮询确认热点真的起来才算成功。
- **修复：仪表音乐开关失效** 🔧：「仪表音乐（实验性）」关掉后，音乐封面图标仍持续显示在
  中控悬浮卡片上。原因是悬浮卡片与仪表盘侧的音乐图标构造时绕过了总开关。现恢复开关闸门：
  开关关闭即不出图标；卡片的「音乐」内容源（用户明确选择的整卡音乐）仍照常显示，不受该开关影响。
- **修复：无线会话约 30 秒一次反复断开重连** 🔧：无线会话建立后手机随即请求蓝牙交接
  （`disableBluetooth`）并在约 30 秒后掐断蓝牙 RFCOMM 控制链路，而「会话保活」状态此前要等
  45 秒交接看门狗才生效——中间窗口里控制链路的任何死亡都被当成整个会话失败，把正在正常出
  画面的 AirPlay 会话一起拆掉（诊断报告 20261015-062233-716：7 次尝试全部如此，P2P 组与
  视频本身始终正常）。对齐 FreeAuto/上游 0.2.18 的判定：**只要 AirPlay 会话还活着且处于交接
  窗口，蓝牙引导链路的死亡只记日志**，由看门狗完成回退（保留画面与音频，控制功能经隧道 iAP2
  或回退路径继续）。
- **界面汉化**、零跑档位识别、倒车暂停、iOS 27 视频车内播放（N 挡门控）。
- **在线更新**（首页快捷设置下方 → 在线更新）：检查 GitHub 上的新构建 → 下载 → 安装。
- CarPlay 应用列表里那个「回到原车」的图标按钮，名称与图标都是**零跑**。
- **中控屏上的仪表盘地图**（悬浮卡片）：窗口类型按 SDK 分支，API < 26 降级 `TYPE_PHONE`；
  画面取自 **stream 111（仪表盘流）**。
- **仪表盘内容源 / 区域 / 裁切** 🆕：**卡片与仪表盘共用**同一份内容源配置——主屏视频（stream 110） /
  独立导航（stream 111） / 音乐（歌曲卡片）。每个源可单独设置**显示区域**与**裁切方式**
  （适应=完整放入留黑边 / 裁满=放大铺满溢出裁掉）。**触摸框选**：设置里每个源的「触摸框选区域」
  打开同比例预览画布，拖动四条边、实时百分比读数；卡片显示时还可「在卡片上框选」——拖动四边
  画面实时重排（所见即所得），卡片上带保存/取消。仪表盘视频面按源分流：独立导航走 stream 111
  主解码（现状不变）、主屏视频以 110 镜像喂仪表面（不占主解码器）、音乐源不挂视频面；
  源切换 / 区域调整即时生效。设置项不再要求开启中控卡片——**后续接上仪表盘即可直接使用**。
  默认源 = 独立导航（不改设置无感）。
- **设置页**：分类 + 侧栏 + 搜索（上游 0.2.14 结构）；本线自有的「与嘟嘟桌面共享实时界面」
  在**高级**组、紧跟仪表盘地图；「方控学习」在**车辆**组、紧跟方向盘按键；「在线更新」在首页。

## ⚠️ 已知限制

- **Wi-Fi Direct 在 API 25 上无法指定信道**：`WifiP2pGroup.getFrequency()` 与带 config 的
  `createGroup` 都是 API 29 才有的，所以信道由系统选择。升到 Android 10 以上才能手动指定。
- **CI 不再跑 lint 与单元测试**，只出 release 包。改运行时代码时请手动跑一次
  `python E:\Launcher\kotlin_static_check.py <改动的 .kt 文件>`。
- **APK 必须带 `arm64-v8a`**：为某个 native 库钉 ABI 会把**整个进程变成 32 位**，
  而 32 位会打挂车载热点。CI 里已加断言。
- **开无线 CarPlay 时车机自身没有网络**（msm8953 单射频，不支持 STA+GO 并发）。
- 无线握手完成后会尝试把 iAP2 从蓝牙引导通道切到隧道通道；若隧道未就绪会
  `wireless handoff fallback ... preserving AirPlay`，**AirPlay 与画面不受影响**。
- 本版升级会**更换发版 tag 命名空间**（`v0.2.15-beta-` → `v0.2.17-beta-`）：
  CI 会同时为 `v0.2.15-beta-` / `v0.2.12-beta-` 创建别名 release，故**旧 beta 包仍能自动发现本版**。
- **大版本升级建议卸载后重装**（残留状态可能影响首次连接）。

## 复测判据

方向盘方控（S01 / T03 都适用）：

1. 起会话时应看到 `media keys listening on 9 car actions`（通道 A 侧）
   与 `car bus listening on 2 extra wheel channels`（通道 B 侧）。
2. 按一次「下一首」，应出现**恰好一行**
   `media key source=<通道> action=nextOne -> CarPlay 4 sent=true`。
3. 若某台车无反应：看 `media key extra-channel unmatched action=... extras=[...]`
   —— 这行会把该车机真正发的 extras 全量列出来。

氛围灯（需车辆通电）：

1. 设置 →「氛围灯」→ 打开「启用」并「保存」：零跑车机应**直接通过**（零跑通路不做 ADB 支持性探测）；
   BYD 车机沿用原有的 ADB 只读探测。
2. 报告里应出现 `DiPlay-Ambient: leapmotor probe file=true model=<n> path=/data/leapmotor/CarType`
   —— 这行判定车型（`file=false` 即走 BYD 通路）。
3. 随后应出现 `DiPlay-Ambient: leapmotor applied=<n> sw=0 auto=1 deg=<d> r=<r> g=<g> b=<b>`
   （`sw=0/auto=1` 即自动档；随音乐时 `deg/r/g/b` 随频谱变化）。
4. 关掉「启用」后应出现 `leapmotor restored to auto`，车机回到自身自动氛围灯。

整车 CAN 信号（需车辆通电）：

1. 起会话后报告里应出现 `DiPlay-Vehicle: vehicle signals listening on leapmotor.cantoapp.update`
   —— 这行说明接收器注册成功（车型判定走 `DiPlay-Ambient: leapmotor probe file=true`）。
2. 挂挡应逐次出现 `DiPlay-Vehicle: gear raw=<a> -> <b> (<R/N/D/P>)`；
   挂 **R** 时同时 `reverse: pause CarPlay sent=true`，退出 R 时 `reverse ended: resume CarPlay sent=true`。
3. 车动起来后应出现 `DiPlay-LM-Speed: first CAN speed <nn.n> km/h`。
4. 若接收器注册失败会打印 `vehicle signals receiver not registered: ...`；完全没有任何
   `vehicle signals listening` 行说明该车机不认这个协议（非零跑，或系统版本不同）。

CarPlay 来电卡片（零跑，需车辆通电）：

1. 打开设置 →「CarPlay 来电显示到仪表盘」：报告里应出现
   `DiPlay-Lm-Call: call output listening`（`DiPlay-Ambient: leapmotor probe file=true` 是前提）。
2. 手机上模拟来电：应出现 `DiPlay-Lm-Call: CarPlay call RINGING`，仪表盘出现来电卡片。
3. 来电状态变化依次打 `… CarPlay call ACTIVE` / `CarPlay call ended`；挂断后卡片应消失。
4. 车机自身的蓝牙来电会打 `DiPlay-Lm-Call: car phone state=<0|1|2>`（`1` 振铃 `2` 通话中 `0` 空闲）。
5. ⚠️ **本机判据只验证到「广播已发出」**；仪表是否真的渲染卡片属实车验证项
   （`data.state` 的拼写取自第三方 App 的实现，尚未上车验证）。若仪表不显示，
   联系开发者改为「通话中让位」策略。

无线连接成功时报告里应出现：

- Wi-Fi Direct：`wireless hotspot backend=Wi-Fi P2P iface=p2p0` →
  `wireless Bonjour services started mode=interface iface=p2p0` → `AirPlay session active`
- 车载热点：`wireless hotspot backend=Manual hotspot` → `AirPlay session active`
- 出画面：`Video: first frame rendered`
- 悬浮卡片：`Centre map: card shown streamActive=true`（流应为 **111 / dashboard stream**）

自动启动三开关（实车）：

1. **自动开启热点**：关掉车机热点 → 连接设置里打开开关（按引导授予「修改系统设置」）→ 点
   「连接手机」：热点应被自动打开并继续无线连接；日志（TAG `DiPlay-Hotspot` / 连接诊断行）应出现
   `car hotspot auto-enable: ...`。若权限没给到位，结果是 `PERMISSION_REQUIRED`，不会反复重试。
2. **蓝牙自动启动**：从最近任务划掉（或确认无会话）→ iPhone 蓝牙连上车机 → DiPlay 应自动打开
   并开始无线连接。报告 `Phone Bluetooth wake:` 行：`result=startActivity-returned` 即拉起成功；
   `result=matched-bluetooth` 说明匹配到了但拉起没跑完，`failureClass=` 给出原因。
3. **Wi-Fi 自动启动**：车机手动连上设置的 SSID → DiPlay 自动打开并开始无线连接；
   报告同一行应显示 `result=matched-wifi`（→ `startActivity-returned`）。
   注意：车机连上 Wi-Fi 后**射频被占用**（STA 模式），随后的无线连接要按所连接的模式走
   （外置 Wi-Fi 模式才是这个开关的主场景）。

无线失败时先看这两行（本版新增）：

- `Manual hotspot pskMatches=true|false|unknown` / `Existing Wi-Fi pskMatches=...`
  —— `true` 两份密码一致；`unknown` 车机不暴露自己的密码，用的是设置里填的那份；
  `false` 两份不一致，此时已改用**车机那份**，请把这一行一起回报。
- 之后若仍是 `No AirPlay TCP after CarPlay StartSession`，说明密码已经不是问题，
  而是 iPhone 没能入网 —— 属车机射频/iPhone 侧，不是 DiPlay 的连接代码。

## 装包说明

- 包名 `com.shihab.diplay.psabeta`（beta 渠道），平台签名。
- 诊断报告第一行带真实版本名（`DiPlay 0.2.17（NN-sha）-beta`），贴日志前先核对这一行。
