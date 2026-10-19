# DiPlay 0.2.21 · PSA 车机版（beta 测试渠道）

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

- **上游 0.2.21 同步** 🆕：并入上游 0.2.21 相对 0.2.20 的更新——BYD 分屏声明
  （`BYD_SUPPORT_SPLIT_ACTIVITY`，DiLink 7.0 分屏元数据，DiLink 7.0 分屏可用）、车机音乐
  节奏频段分析（BydCarMusicRhythm 0.2.21 版）、文档/官网更新、意大利语补全。
  软件内版本号跟进 **0.2.21**；OTA 通道不变（beta 仍 `v0.2.17-beta-<run>`，正式仍
  `diplay-<run>`）。上游 0.2.21 其余改动（音乐时钟调整计卡顿修复、有线自恢复、
  BYD 仪表交还等）位于本线保留的连接核心/氛围灯区，继续按本线实现，不跟。
- **方向盘方控**：
  - **S01**：通道 A（`car.meter.music.BROADCAST` 的 JSON）—— 实测正确。
  - **T03**：通道 B（`com.leapmotor.customkey.music.pauseplay` 的整数 extras）—— **可用**。
- **方控音频归属**：焦点与 MediaSession 已拿到，原厂播放器会让出方控。
- **媒体信息投送**：曲名 / 艺术家 / 封面经 MediaSession 提供给车机仪表等读取方。
- **氛围灯** 🆕：设置 →「氛围灯」。开关灯、随音乐流光（单色 / 多色）、颜色（31 色 / 专辑取色）、
  速度、灯光档位、分区。**同一份 APK 自动识别车型**：检测到零跑车机（`/data/leapmotor/CarType`）
  就走车机自己的 `leapmotor.carapp.CARCANCONTROL` 广播（`appCAN=Atmos_ctrl`，与 `VehicleSetting.apk`
  同协议）；其它车机（含 BYD）仍走原有的 ADB 通路，BYD 行为不受影响。
  **零跑协议里的 `lampDeg` 是 0–10 档「亮度」，不是角度**（角度是另一套 `angledeng` 大灯高度，
  本协议不带）；设置里的「灯光档位」就是它，随音乐时按 OEM 规则用：多色＝颜色跳、亮度恒定，
  单色＝颜色不变、亮度跳。
- **修复：氛围灯日志不进诊断报告** 🔧：氛围灯的行此前只打 `DiPlay-Ambient` 这个 logcat tag，
  而导出的报告是从 `appendLog` 组装的、**不是 logcat dump**，所以测试员在报告里永远看不到它们
  （复测判据却要求 grep 这些行）。现统一走 `AmbientDiagnostics`：logcat 用 `xcertplay-usb`，
  同时接进报告。BYD 通路的 worker 失败行也一并接入。
- **颜色多选：每行显示色块与十六进制** 🆕：设置 →「氛围灯」→「原车颜色编号（多选）」。
  此前列表只有 1–31 的裸编号，看不出是什么颜色；现在每行前面是该号的**原厂预览色块**，后面跟
  `#RRGGBB`（取自原车 `light_ambient_color_{1..31}_all.png`，不是实测灯带值）。
  批量按钮会在「全选 / 取消全选」之间自动切换 —— 全部勾上后一键清空，不用逐个点掉。
- **颜色怎么变，取决于「随音乐」**：该开关同时决定颜色是否轮换（内部 `colorCycle` 跟随它）。
  **关**：颜色固定 = 已选编号里最小的那个（所以只选一种和全选效果一样）；
  **开**：按「颜色模式」换色 —— 节拍/低频/智能/节奏预测按拍子顺序轮换（1→2→…→31→1），
  能量模式按响度映射到列表位置。**不是随机**：只有「颜色来源＝专辑取色」且封面没取到可用颜色时
  才会随机跳。
- **修复：氛围灯三处语义错误** 🔧：① 多色随音乐时亮度被频谱驱动（OEM 多色分支亮度恒为
  `GetDeg()`，只有颜色跳），现改为恒定用户档位；② 关闭功能时强制下发 `lampDeg=5`，会把车上
  自己的 `fendeng` 静默重置，现改为沿用用户档位；③ 静音帧发 `sw=0`、有声帧发 `sw=1`，
  20 Hz 来回切换车机开关，现统一为 `sw=1`（关闭功能时交回 `sw=0` 自动档）。
- **修复：单色随音乐下发近黑色** 🔧：`singleColour` 调用时把亮度值当成 RGB 传了进去，
  实际发的是 `r=g=0 b=5`。现单色通路颜色恒为用户所选，只有亮度随低频跳。
- **车辆数据模式新增「零跑 CAN · 专属协议」** 🆕：设置 → 高级 →「高级车辆数据」→「车辆数据模式」。
  此前只有两个 DiLink 选项（默认模式 / 旧版车机探测），而零跑车机没有 adbd，两个都读不到东西。
  现在零跑车机上多出第三个选项并**默认选中**（`/data/leapmotor/CarType` 存在，或设置向导里选了
  「零跑 S01/T03」，即成立）；向导选零跑时也会自动切过去，改选 BYD 车型则退回默认模式。
  该模式下不显示 ADB 状态与「探测旧版车机」按钮，改为显示实时 CAN 状态（档位 / 车速 / 电量）。
  下面的开关全部走同一条广播：电量→iPhone、隧道车速与档位（航位推算）、驻车视频门控。
- **高级车辆数据：零跑上每一项的实际来源**（本轮补齐了唯一没接的一项）：电量/续航 → CAN `1060115`
  （回落电源 SOC `1030115`）；隧道车速与档位（航位推算）→ CAN `1100409`/`1100412`/`100001` + `1010105`；
  驻车视频 → CAN 档位 **N**；充电接口类型与低电量阈值 → 与车无关（iAP2 声明 / 纯阈值）。
  **蓝牙暂停**没有零跑对等协议（零跑无 adbd，其 `com.leapmotor.bluetooth.BROADCAST` 只上报状态、
  不能关射频），本轮改为**在零跑上隐藏这个开关**，不再显示一个点了没用的选项。
- **修复：向 iPhone 报告位置与 iPhone 自身定位"打架"** 🔧：定位源此前是「谁最新用谁」，而基站 /
  Wi-Fi 定位的出点频率不低于车机 GPS，粗略的基站定位反复压过车机 GPS，iPhone 收到的坐标在真实位置
  和几公里外的基站估算之间来回跳 —— 看起来就像和手机自身定位冲突。现在改为**严格优先级 + 精度门槛**：
  车机 GPS（≤120 m）优先，其次 fused（≤120 m），基站 / Wi-Fi（≤3000 m）只在完全没有 GPS 时兜底；
  同一帧不再重复编码下发（GPS 0.2–1 Hz 时不会每秒重发同一个时间戳）。
  报告新增 `location sources ...` 与 `location fix provider=gps accuracy=8m ageMs=120` 行。
  ⚠️ 车机完全没有定位时 iPhone 仍会退回自身 GPS，这是 iOS 行为，App 侧无法禁止。
- **修复：使用情况访问权限被误判为未授权** 🔧：「中控屏上的仪表盘地图 → 离开桌面时自动隐藏卡片」
  与「跟随仪表主题」都依赖使用情况访问权限。此前只认 `MODE_ALLOWED`，而本机（系统应用）在 Android 7
  上该权限是 **`MODE_DEFAULT`** —— ROM 通过默认策略放行，所以功能本来能用，界面却一直显示"需要授权"，
  必须手动去系统设置里重新开一次才会变成已授权。现在按系统的方式解析 `MODE_DEFAULT`：
  实际读一次 `queryUsageStats` 有数据即视为已授权，系统应用同样视为已授权；只有明确的
  `MODE_ERRORED` / `MODE_IGNORED` 才算未授权。
- **缓冲音乐（实验性）：拒绝原因进诊断报告** 🔧：iPhone 在缓冲流被拒时**不会**退回普通音乐流，而是
  改用手机自己的扬声器，车机这边就是"不播放、也没声音"。此前拒绝原因只打 logcat（报告里看不到），
  无法定位。现在每条拒绝都写进报告并带上原始字段：
  `Buffered audio declined: audioFormat=0x… decoded=… rate=…`、`ct=… spf=…`、`shk` 长度、
  对端地址缺失、旧渲染器未清理完等；接受时另有 `buffered audio stream client=… rate=… port=…`
  与 `Buffered audio: connected` / `queued N s, received … kbit/s`。
  **若打开后仍无声音，请把报告发回**，这几行足以区分"没连上 / 被拒 / 队列没填满"。
- **通过车辆蓝牙播放 CarPlay 音频（实验性）：零跑上隐藏并强制忽略旧偏好** 🔧：该开关让接收端
  **不再声明任何音频输出**（去掉 `audioFormats`、清掉音频特性位），改由 iPhone 保持 A2DP。实测本车
  打开后**整个会话没有画面流**（灰色窗口、有背光）。上游也把它列为"需要实车反馈"，而本线保留自己的
  无线连接核心（交接期间独占蓝牙链路）。现改为：零跑上不显示该开关，且**会话构建时忽略已存的该偏好**
  （`disableAudioOutput` 恒为 false），避免旧配置把 CarPlay 卡成灰屏。BYD 车机行为不变。
- **修复：驻车播放视频在 BYD 上永远不放行** 🔧：视频门控此前**写死**读零跑的 CAN 档位，
  而 BYD 车机上那条广播从不启动 → 档位恒为空 → iOS 27 视频一直不开放。现在按车型分流：
  **BYD 用 P 挡（ADB 读取），零跑用 N 挡（CAN 广播；零跑只上报 R/N/D，没有 P 挡读数）**。
  同时「驻车时播放视频」开关恢复生效（之前无论开关状态都把视频能力无条件报给 iPhone）；
  零跑上该开关默认开（N 挡判定不需要额外条件），BYD 上仍默认关（需要网络 ADB）。
- **诊断报告**：「BYD vehicle-data probe」段改为「Vehicle-data source」，直接给出
  `mode=default|legacy|leapmotor` 与 `leapmotor=true|false`。
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
  Android 7.x 上走传统 SoftAP 开关（反射 `setWifiApEnabled`，用车机自己保存的热点配置，
  不动名称/密码/频段，**全程不需要 ADB**）；Android 8+ 走 `startTethering` 反射。
  连接结束后**不关热点**。需要「修改系统设置」权限，开关打开时引导到系统设置授权（无需 ADB）。
  开关下方新增**使用方法提示**：首次使用前先在车机设置手动开一次热点（本 ROM 的热点 DHCP
  由车机设置那次手动开启武装，反射开启本身不带；之后自动启用即正常；若又在车机设置里手动
  关过热点，需再手动开一次恢复）。该行为与 FreeAuto 实测一致（其自身日志证明反射热点首把
  同样无网络，手动开过一次后恢复）。
- **上游 0.2.20 全量同步** 🆕：并入上游 0.2.19 + 0.2.20（基线 0.2.18 之上）的主要更新：
  音频时钟漂移速率补偿 / 音频线程优先级 / 无线与 USB 独立音乐缓冲；耳机→扬声器通话路由、
  Siri 语音键一次按压一次唤醒、应用语音消息麦克风；有线启动提速（WiredStartWatchdog、
  WiredSessionMemory、USB 转移拒绝回退）、iOS 26 先请求 CarPlay 模式、经已授权 ADB 的
  USB 授权（IphoneUsbAdbGrant）、NCM 流量计数；仪表集群 13/17 固件与 Leopard 5 Ultra 识别、
  无硬件 HEVC 解码器时回退 H.264（HevcSupport）、CarPlay 内设置菜单按目标分组；
  诊断报告新增上次崩溃（CrashRecord）、方控/媒体键、车载蓝牙暂停、音乐重缓冲与 USB 设备清单；
  iPhone 圆角遮罩（IphoneCornerMask）、黑角补偿（CarPlayBlackCornerOverlay）、
  桌面气泡快捷方式（LauncherBubble）、连接失败引导（ConnectionFailureGuidance）、
  意大利语与繁体中文补全、热点不可用时「连接」按钮直连。
  **保留本线实现、未跟上游的部分**：热点自动启用（FreeAuto 反射 setWifiApEnabled，上游走
  系统壳+ADB）、Wi-Fi Direct 建组管线（FreeAuto 信道对齐）、氛围灯（上游 0.2.20 重做为
  BYD 向，本线保持零跑 lampDeg 通路）、Wi-Fi Direct 扫描暂停开关（上游新增，依赖本线未移植
  的 WifiScanPauseMain）。诊断报告的文本组装改用上游 diagnosticReportText()。
- **连接步骤实时显示（移植 FreeAuto）** 🆕：连接等待页在阶段文字下方新增一行
  「无线连接中 · 第 N / 40 步」（有线为 25 步），与 FreeAuto 相同的 40/25 步清单、
  相同的中英文文案（无线：蓝牙引导 1–31 步、Wi-Fi 32–40 步；出画面=40）。
  点「查看全部步骤」展开整表：○ 未到 / ● 当前 / ✓ 已完成 / ! 卡住（当前步加粗）；
  同一步超过 60 秒不动或连接失败时整行变「无线没连上 · 卡在第 N / 40 步」；
  会话建立后隐藏，会话断开转「正在自动重连 · 第 N 步」并继续推进。
  纯只读观察层，不触碰连接流程；步骤号由 CarPlayStatus 状态机映射（FreeAuto
  ConnectionStepMap 同款），本控制器不逐级上报的中间握手步（如证书/能力协商）会直接跳过。
- **修复：热点自动启用对齐 FreeAuto 方式** 🔧：此前 Android 7.x 上主路径是 `startTethering`
  反射 + ADB 兜底（BYD 遗产，本车无 adbd 时 ADB 路径永远秒失败）。现按 FreeAuto 的实现方式：
  **Android 7.x 直接用传统 `setWifiApEnabled` 开关**（仅需清单内 `CHANGE_WIFI_STATE` 普通权限 +
  「修改系统设置」授权），**热点自动启用链路中的 ADB 兜底整段移除**；`startTethering` 仅保留
  给 Android 8+。开启成功仍以轮询确认热点真正起来为准。
- **修复：反射创建的热点无互联网（iPhone 连上但无 DHCP）** 🔧：诊断报告 20261015-115236-952
  ——反射拉起的热点在 wlan0 上只有 IPv6 链路本地地址（`ipv4Usable=0`，Bonjour 仅 IPv6，
  iPhone 入网后无 IPv4/互联网，CarPlay 一次都没连上），而车机设置里手动开的热点正常。
  现已把反射开热点的**参数链逐参数对齐 FreeAuto**：①传给 `setWifiApEnabled` 的配置对象按
  FreeAuto 名称规则处理——**首次启动会把热点名换成 `DiPlay-XXXX`（随机 4 位后缀，持久化），
  密码/加密方式/频段完全不动**（与存储配置不同的 SSID 会迫使框架走完整的热点启动路径）；
  系统拒绝该配置时回退用保存的原配置再开一次；配置被系统隐藏或名字已归属本安装时传 null
  （与 FreeAuto 一致）。②连接时发现热点已开着但名字不是本安装的 → **重启一次换名**（关 →
  2 秒 → 开，FreeAuto applyNameRule 同款；名字已归属则不动）。换名后 iPhone 需重新选择
  `DiPlay-XXXX` 这个网络（密码仍是你自己设置的热点密码）。
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
- **Wi-Fi Direct 建组管线对齐 FreeAuto（GO 信道对齐 + 真实频率）** 🆕：此前 Android 7.x 上
  组只能用系统默认配置创建（驱动自选信道，诊断里 `channel=0 frequencyMHz=null`）。现按
  FreeAuto 方式重写 legacy 建组：**建组前经隐藏 API `setWifiP2pChannels` 把 GO 信道对齐车机
  Wi-Fi 当前信道**（单射频车机 STA+GO 同信道零代价并发），建成后经驱动查询（JNI
  `liblocal_hotspot_radio`）校验真实频率，落错信道则摘组换下一候选（对齐信道 → 149/36/6/1/11
  → 系统默认兜底）；**真实频率写进 iAP2 通告**（0x5703/0x4301 不再是 channel=0）。
  iPhone 关联更快，诊断可判。若实车验证不过可整体回退（改前源码已备份）。
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
2. 报告里应出现 `leapmotor probe file=true model=<n> path=/data/leapmotor/CarType`
   —— 这行判定车型（`file=false` 即走 BYD 通路）。
3. 随后应出现 `leapmotor applied=<n> sw=1 auto=0 deg=<d> r=<r> g=<g> b=<b>`
   —— `sw=1` 即「开」档（本版固定用开档，车机会按环境光自己关灯的话会看不到流光）。
   多色：`deg` 恒等于设置的「灯光档位」，只有 `r/g/b` 随频谱跳；
   单色：`r/g/b` 恒为用户所选颜色，只有 `deg` 随低频跳。
   若发现 `sw` 在 `0`/`1` 之间反复横跳、或 `deg` 在静音时归零，属回归，请回报。
4. 关掉「启用」后应出现 `leapmotor restored to auto deg=<d> sent=true`，
   车机回到自身自动氛围灯、亮度保持用户档位（**不再被重置成 5**）。
5. 若广播发送被系统拒绝会打 `leapmotor ambient send failed: <异常类名>`；BYD 通路为
   `ambient worker failed: <原因>`。

位置报告（需车辆通电）：

1. 打开「向 iPhone 报告位置」后，报告应出现 `location sources gps,fused,network`
   （按车机实际可用的源排列，GPS 在前）。
2. 随后最多每 30 秒一行 `location fix provider=gps accuracy=<n>m ageMs=<n>`；
   **正常情况下不应出现 `provider=network`** —— 出现说明车机 GPS 没有星号，此时定位会偏，
   属天线/信号问题，不是 App 在混用两个源。
3. 车机 GPS 正常时，CarPlay 地图的定位点应稳定跟车，不再在真实位置和几公里外之间跳。

使用情况访问（零跑）：

1. 设置 → 高级 →「中控屏上的仪表盘地图」：`离开桌面时自动隐藏卡片` 下面应直接显示**已授权**，
   不需要去系统设置里手动开一次（系统应用在 Android 7 上是 `MODE_DEFAULT`）。
2. 若仍显示需要授权，说明本机该 op 确实被拒（不是默认模式），此时按提示授权即可。

缓冲音乐（零跑，实验性）：

1. 打开后播放 Apple Music：报告里应有 `buffered audio stream client=…` 与
   `Buffered audio: connected`。
2. 若没有声音，报告里必然有一行 `Buffered audio declined: <原因>` —— 把这一行发回即可定位。
3. 若两行都有但仍断续，看 `Buffered audio: queued N s, received … kbit/s`：`received` 低于音乐码率
   说明链路带宽不够（见 `docs/BUFFERED_AUDIO.md` 的说明），不是本功能的 bug。

高级车辆数据（零跑，需车辆通电）：

1. 设置 → 高级 →「高级车辆数据」→「车辆数据模式」：零跑车机应有三项，且**第三项
   「零跑 CAN · 专属协议（无需 ADB）」被选中**；BYD 车机只有前两项。
2. 该模式下不应出现任何 ADB 状态行或「探测旧版车机」按钮。
3. 已建立过一次 CarPlay 会话后，面板应显示 `CAN 信号：档位 N · 车速 0 km/h · 电量 62%`；
   没有会话时显示「等待 CAN 信号」。
4. 挂 N 挡：iPhone 投屏的视频能在车机播放；挂出 N 挡播放器立即关闭。挂 P 挡不触发（零跑没有 P 挡读数）。
5. 诊断报告 `--- Vehicle-data source ---` 段应为 `mode=leapmotor leapmotor=true`。

整车 CAN 信号（需车辆通电）：

1. 起会话后报告里应出现 `DiPlay-Vehicle: vehicle signals listening on leapmotor.cantoapp.update`
   —— 这行说明接收器注册成功（车型判定走 `leapmotor probe file=true`）。
2. 挂挡应逐次出现 `DiPlay-Vehicle: gear raw=<a> -> <b> (<R/N/D/P>)`；
   挂 **R** 时同时 `reverse: pause CarPlay sent=true`，退出 R 时 `reverse ended: resume CarPlay sent=true`。
3. 车动起来后应出现 `DiPlay-LM-Speed: first CAN speed <nn.n> km/h`。
4. 若接收器注册失败会打印 `vehicle signals receiver not registered: ...`；完全没有任何
   `vehicle signals listening` 行说明该车机不认这个协议（非零跑，或系统版本不同）。

CarPlay 来电卡片（零跑，需车辆通电）：

1. 打开设置 →「CarPlay 来电显示到仪表盘」：报告里应出现
   `DiPlay-Lm-Call: call output listening`（`leapmotor probe file=true` 是前提）。
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
