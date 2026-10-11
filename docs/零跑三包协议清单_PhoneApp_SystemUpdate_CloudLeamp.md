# 零跑三包协议清单 —— PhoneApp / SystemUpdate / CloudLeamp

> 分析对象（apktool 反编译，仅 smali）：
> - `D:\Launcher\PhoneApp_unpacked` —— `com.leapmotor.phone`（**电话**应用，versionName 1.78 / versionCode 78，minSdk 16 / targetSdk 26）
> - `D:\Launcher\SystemUpdate_unpacked` —— `com.leapmotor.systemupdate`（**系统更新**应用，1.2.202107171143，minSdk 24，`sharedUserId=android.uid.system`）
> - `D:\Launcher\CloudLeamp_unpacked` —— `com.leapmotor.cloudleamp`（**车云连接器**，1.0.190，minSdk 19）
> 配套文档：`docs/零跑车机数据来源分析.md`（车辆信号主线）、`docs/BYD独有功能_零跑适配清单.md`
> 分析时间：2026-10-11
> 结论口径：**只看各包自身暴露/消费的进程间通道**（广播 / AIDL / ContentProvider / 文件），不依赖 ADB。

---

## 0. 一句话结论

| 包 | 最有价值的可复用点 | 门槛 |
|---|---|---|
| **PhoneApp** | 来电状态广播 `com.leapmotor.phonecall`（`MMI_PhoneSts` 0/1/2）+ 详细来电 JSON `car.hmi.sender.BROADCAST`；可控发起呼叫 `com.leapmotor.ICU2MMICtrl` | 无（隐式导出） |
| **SystemUpdate** | `DeviceCtrlAidlService`（`getCarType/getVinInfo/getHighVoltage/getDCDCState/ECU 版本`等 38 个方法） | **signature** —— DiPlay 是平台签名，**可调** ✅ |
| **CloudLeamp** | **VIN 配方法**：读 `/data/leapmotor/VinInfo`（回落 `<sdcard>/VinInfo.txt`）；`car.meter.carinfo.BROADCAST` 的报文格式 | 无（读文件） |

**⚠️ 最重要的负结论**：`car.meter.carinfo.BROADCAST`（纯文本 JSON 的车速/里程）在这三个包里**只有接收方，没有发送方**。生产者在另一个未解包的仪表/CAN 应用里。**DiPlay 不能假设它会来**，所以车辆信号仍应以已实现的 CAN 总线（`leapmotor.cantoapp.update`）为主。

---

## 1. PhoneApp（`com.leapmotor.phone`）

车机的**蓝牙电话**应用：HFP (Hands-Free Profile) 客户端 + PBAP 电话簿同步。

### 1.1 ★ 来电状态广播（支撑"来电弹窗"）

**发送方**：`BluetoothPbapServer.SendCalligStateOutside(boolean isHold, boolean isComeCall)`
—— `smali/com/car/hmi/bluetooth/utils/BluetoothPbapServer.smali:427-502`

| Action | Extra | 类型 | 取值 |
|---|---|---|---|
| `com.leapmotor.phonecall` | `MMI_PhoneSts` | **int** | `0`=空闲/挂断　`1`=**来电响铃**　`2`=**通话中** |

判定逻辑（`smali:464-501`）：`isHold && isComeCall` → 1；`isHold && !isComeCall` → 2；`!isHold` → 0。

**DiPlay 可用性**：✅ 普通 `sendBroadcast`（无权限、无 explicit package），API 25 上任何 App 都能注册接收。
**用途**：DiPlay 自己画"CarPlay 来电卡片"时用它同步真实通话状态；或做 `BydCarPlayCall` 的零跑数据源。

### 1.2 ★ 仪表盘来电卡片 —— `car.hmi.sender.BROADCAST`（★ 已实现）

`car.hmi.sender.BROADCAST` 是 **仪表盘（ICU）的通用出口/入口**，不是电话专用。`sender` extra 的格式
（第三方 `小零助手` 的 `MeterWire.pack` + `LauncherThree` 的 `Dlg` 双份证实）：

```
sender : byte[]
  [0..1] u16 大端 = JSON 字节长度
  [2..]  UTF-8 JSON
  [..]   可选：JSON 之后的原始二进制尾巴（图片等），长度由 JSON 里的 detail 描述
```

JSON 信封：`{"type":"<kind>","data":{...},"detail":{...}}`

| 已知 `type` | 证据 | 用途 |
|---|---|---|
| `call` | `Call.java`（`same()` 比对 `type`/`name`/`code`） | **来电卡片** |
| `music` | `MeterPacket.java:470` | 歌曲卡（`data` + `detail` 二进制封面） |
| `lyric` | `MeterLyricHmi`/`MeterLyricQq` | 歌词 |
| `xlui` | `ClusterStyle.pack` | 仪表 UI 风格（2 字节原始尾巴：style+gear） |
| `navi` | `ClusterDeploy` | 导航 |
| `drivemode` | `LauncherThree/Dlg.sendMeterDriveMode` | 驾驶模式 |
| `carinfo` | `car.meter.carinfo.BROADCAST`（另一 action） | 车速/里程（**无生产者**，见头部负结论） |

**DiPlay 已按此实现**：`shared/.../hud/LeapmotorCarPlayCallOutput.kt`
- 把 CarPlay 的 iAP2 `CallStateUpdate`(0x4155) 通话状态 → `{"type":"call","data":{"state":"ring|answer|end","name":"…"}}`
- JSON 由手写转义生成（不用 `org.json`，其 Android 实现无法在 JVM 单测里跑）
- 线路格式 `[u16 大端长度][UTF-8]` 与 `wire()` 一致，单测 `LeapmotorCarPlayCallOutputTest` 锁定
- **只用普通 `sendBroadcast`**：无 ADB、无反射、无 root —— 这正是零跑车机唯一可行的通路

**待实车确认**：`data.state` 的取值拼写与仪表实际渲染（`ring`/`answer` 已按第三方代码取，未上车验证）。

### 1.2b 车机侧蓝牙通话状态（同一条卡片要协同）

`com.leapmotor.phonecall`（int extra `MMI_PhoneSts`）由 PhoneApp 发出，第三方 `小零助手`
（`Call$1`）和 `LauncherThree` 都在收 —— 印证这是**稳定公开通道**：
- `1` = 振铃、`2` = 通话中、`0` = 空闲、`-1`（缺省）= 忽略
- ⚠️ **`2` 语义**：PhoneApp 源码里 `2` 是"去电"，但第三方 UI 一律显示"通话中" → **`2` 统称"通话中/去电"**，这条 int 通道**没有号码**。
- DiPlay 用它做"车机原生通话正在占用"的判断，避免与原生 UI 打架。

### 1.3 主动控制通话（`com.leapmotor.ICU2MMICtrl`）

PhoneApp 注册了一个**动态接收器**（`BluetoothPbapServer$8`）监听：
| Action | Extra | 类型 | 取值 |
|---|---|---|---|
| `com.leapmotor.ICU2MMICtrl` | `ICU_PhoneCtrl` | **int** | `1`=接听/保持　`2`=拒接+挂断　`3`=拨号/打开 |

`car.meter.phone.BROADCAST`（extra `receiver` byte[]，长度前缀+JSON，`state: answer|refuse|hangup|call`）走 `analyseJson` 同一套动作。

**DiPlay 可用性**：⚠️ **可能可用但需实车验证**。该接收器**未在 manifest 导出**（动态注册），API 25 上动态接收器默认仍可被外部 App 投递，但**只有在 `BluetoothPbapServer` 服务运行时才注册着**。→ 真机 `sendBroadcast` 试一次即知。

### 1.4 启动电话应用（`com.leapmotor.command.phone`）

**接收方**：`com.leapmotor.model.VoiceReceiver`（manifest 声明，无 `exported` 属性 → API 25 隐式导出）

| Action | Extra | 值 | 行为 |
|---|---|---|---|
| `com.leapmotor.command.phone` | `type` | String | `"open"` → 打开电话 App（`MainActivity`，`NEW_TASK|NO_ANIMATION`） |
| | `type` + `content` | String | 其它值 → 走 EventBus 内部消息（要求蓝牙已连接，否则语音播报"蓝牙未连接"） |

**DiPlay 可用性**：✅ 可发送（用于跳转电话界面）。
**⚠️ 坑**：`smali:114-120` 对 `getStringExtra("type")` **直接 `.equals()` 无判空** → **不带 `type` 发这个广播会让接收器 NPE**。要发就必须带 `type`（如 `"open"`）。

### 1.5 休眠控制（`com.leapmotor.SLEEPCTRL`）

同一 `VoiceReceiver` + `BluetoothPbapServer$8` 都监听。extra `sleep`（**boolean**）：
- `false` → 唤醒/重启 `BluetoothPbapServer` 服务
- `true` → `stopSelf()` 停服务

**DiPlay 可用性**：✅ 可发送（生命周期控制，非通话信号）。`SystemUpdate` 的 `RebootReceiver` 也监听同一 action。

### 1.6 导出的 ContentProvider —— 通讯录 / 通话记录（★ 只读可用）

manifest **显式** `exported="true"`，无 `readPermission`/`writePermission` → **任意 App 可读写**。

| Authority | 路径 | 列 | 语义 |
|---|---|---|---|
| `com.jacp.provider.demo.leader` | `leaders` / `leaderfirsts` / `leaderseconds` / `leaderthrees` / `leaderfours` / `leaderfives`（+ `/` 后接 `_id`） | `_id,name,number,sortkey,id` | **通讯录**（PBAP 同步来的联系人） |
| `com.jacp.provider.demo.programmer` | `programmers`（+ `/` 后接 `_id`） | `_id,name,number,time,type` | **通话记录**（`type`=通话类型） |

完整 URI 例：`content://com.jacp.provider.demo.programmer/programmers`（默认 `time desc`）。

**DiPlay 可用性**：✅ 可 `query` 读，也可注册 `ContentObserver`。
**⚠️ 限制**：只有**已同步的历史**数据（不是实时来电事件），且**依赖手机已配对且 PBAP 同步过** —— 无配对时表可能为空。**不能**用它做实时来电弹窗（那要用 §1.1/1.2）。

### 1.7 无冲突结论（音频）

**电话应用不申请音频焦点、不创建 MediaSession、不注册媒体按键**（全包 grep 确认）。唯一的 `AudioManager` 用法是通话时 `setStreamVolume` 保存/恢复媒体音量（`BluetoothPbapServer.smali:1576` 等）。
→ **与 DiPlay 的 CarPlay 音频/MediaSession 无直接冲突**。唯一注意：通话前后系统媒体音量会被电话应用改动一次，DiPlay 不要假设音量恒定。

---

## 2. SystemUpdate（`com.leapmotor.systemupdate`）—— ★ AIDL 宝库

车机**系统更新/ECU 诊断**应用，跑在 `android.uid.system`，持有 `libnative-lib.so`（UDS/ECU 桥接）+ `libdecrypt-lib.so`（UDS 安全访问 seedKey 算法）。

### 2.1 ★ `DeviceCtrlAidlService` —— `IDeviceCtrlInterface`（signature 保护）

- **Action**：`Leapmotor.DeviceCtrlAidlService`
- **Descriptor**：`com.leapmotor.deviceCtrl.IDeviceCtrlInterface`
- **权限**：`android:permission="com.leapmotor.JNIDEVICECTRL"`，`protectionLevel=signature`
- **DiPlay 可用性**：✅ **可以** —— DiPlay 是**平台签名**（CI: "Platform-signed release APK"），与 `com.leapmotor.systemupdate` 同一签名链 → 自动获得该 signature 权限。
  - 交叉验证：`VehicleSetting_unpacked` 自己也持有同一个 `IDeviceCtrlInterface`（`GetVersionIntentService.smali:116` 真实调用 `getVinInfo()`）→ 这**本来就是车上跨应用复用的接口**。

**方法全表**（交易码取自 `$Stub.TRANSACTION_*`；均在 `smali/com/leapmotor/deviceCtrl/`）：

| Tx | 方法 | 签名 | 语义 |
|---|---|---|---|
| 0x01 | `setLcd` | `int setLcd(boolean)` | ⚠️ 名不副实：实现是 `setVolume(2,2)` / `setVolume(1,1)`，**不是屏幕控制** |
| 0x02 | `setVolume` | `int setVolume(int type, int volume)` | 按类型设音量 |
| 0x03 | `startKeepWork` | `int startKeepWork()` | 置升级态=1 |
| 0x04 | `stopKeepWork` | `int stopKeepWork()` | 置升级态=0 |
| 0x05 | `getEcuVersion` | `int getEcuVersion(int port)` | |
| 0x06 | `getEcuHardVersion` | `int getEcuHardVersion(int port)` | |
| 0x07 | `getEcuModelVersion` | `int getEcuModelVersion(int port)` | |
| 0x08 | `getEcuVin` | `int getEcuVin(int port)` | |
| 0x09 | `getECUManufacturingDate` | `int getECUManufacturingDate(int port)` | |
| 0x0a | `getSystemSupplierIdentifier` | `int getSystemSupplierIdentifier(int port)` | |
| 0x0b | `getECUSerialNumber` | `int getECUSerialNumber(int port)` | |
| 0x0c | `getPartNumberAlphaCode` | `int getPartNumberAlphaCode(int port)` | |
| 0x0d | `getSoftwareNumber` | `int getSoftwareNumber(int port)` | |
| 0x0e | `getHardwareNumber` | `int getHardwareNumber(int port)` | |
| 0x0f | `getBootSoftware` | `int getBootSoftware(int port)` | |
| 0x10 | `getUDSVersion` | `int getUDSVersion(int port)` | |
| 0x11 | `getBootOrSoftware` | `int getBootOrSoftware(int port)` | |
| 0x12 | `getEcuDateUpdate` | `int getEcuDateUpdate(int port)` | |
| 0x13 | `getConfig` | `int getConfig()` | |
| 0x14 | `getVinInfo` | `int getVinInfo()` | 触发 VIN 读取（真值在别处，见 §3.3） |
| **0x15** | **`getCarType`** | `int getCarType()` | **车系** —— 与 `LmCarProbe` 注释一致 ✅ |
| 0x16 | `getHighVoltage` | `int getHighVoltage()` | 高压状态 |
| 0x17 | `getDCDCState` | `int getDCDCState()` | DCDC 状态 |
| 0x18 | `clearFaultInfo` | `int clearFaultInfo()` | 清故障码 |
| 0x19 | `ecuResetAll` | `int ecuResetAll()` | |
| 0x1a | `forbidDriveSafety` | `int forbidDriveSafety()` | |
| 0x1b | `removeHighVoltage` | `int removeHighVoltage()` | |
| 0x1c | `addHighVoltage` | `int addHighVoltage()` | |
| 0x1d | `enableCommunication` | `int enableCommunication()` | |
| 0x1e | `onPowerUp` | `int onPowerUp()` | |
| 0x1f | `onPowerCar` | `int onPowerCar()` | |
| 0x20 | `androidUpdateStart` | `int androidUpdateStart()` | |
| 0x21 | `androidUpdateStop` | `int androidUpdateStop()` | |
| 0x22 | `androidUpdate` | `int androidUpdate(int port, String fileSrc)` | |
| 0x23 | `enterUpgrade` | `int enterUpgrade(int pyID)` | |
| 0x24 | `ecuUpdate` | `int ecuUpdate(int port, int pyid, String fileSrc)` | |
| 0x25 | `preProgramming` | `int preProgramming(int pyID)` | |
| 0x26 | `getEcuASCIIDidInfo` | `String getEcuASCIIDidInfo(int pyID, int did)` | 唯一返回 String |

**返回值约定**：`canServerConnected==false` 时多数立即返回 `-0xe`（-14）；`androidUpdate` 失败 `-0xa/-0xd`，成功 `0`。

**DiPlay 可用的直接价值**：
- `getCarType()` → 车系判定（比读 `/data/leapmotor/CarType` 更权威，虽然后者已验证可用且免费）
- `getHighVoltage()` / `getDCDCState()` → 高压/DCDC 状态（可补"车辆状态"）
- `getVinInfo()` / `getEcuVin(port)` → VIN 的另一条读取路径

### 2.2 `SpiStateAidlService` —— `ISpiStateInterface`（**无权限门槛！**）

- **Action**：`Leapmotor.SpiStateAidlService`
- **Descriptor**：`com.leapmotor.deviceCtrl.ISpiStateInterface`
- **权限**：`<service>` 上**没有 `android:permission`** → **任意 App 可 bind**（`android:protectionLevel="normal"` 写在 `<service>` 上是**无效属性**，只有 `<permission>` 才有意义）。
- 方法仅 4 个：

| Tx | 方法 | 签名 | 语义 |
|---|---|---|---|
| 0x01 | `readingUDS` | `int readingUDS()` | 握手 + 上报升级状态 |
| 0x02 | `sendUDSPackage` | `int sendUDSPackage(byte[] data)` | 注入原始 UDS 报文（len==1→睡眠，len==2→唤醒） |
| 0x03 | `getRestartReason` | `String getRestartReason()` | 上次重启原因 |
| 0x04 | `distanceDiagnose` | `String distanceDiagnose(String request)` | 诊断请求/响应 JSON |

**⚠️ 安全提示**：`distanceDiagnose` 的 `functionID == -102` 分支在 **system uid 进程内 `Runtime.exec(command)`** —— 等于给外部开了 system shell。**DiPlay 不要碰这个分支**。
**DiPlay 可用性**：✅ 可 bind，但它**不是**便民信号 API（不暴露车速/电量/VIN），价值有限。`getRestartReason()` 也许有点用。

### 2.3 `SoundEffectPlayAidlService`（normal，但**空实现**）

- **Action**：`Leapmotor.SoundEffectPlayAidlService`，权限 `com.leapmotor.SOUNDEFFECTPLAY`（本 App 自己声明为 `normal` → 任意 App 声明即可用）
- 方法仅 1 个：`int play(int index, float leftVolume, float rightVolume, int priority, int loop, float rate)`
- **⚠️ 实现体是 `return 0`（空壳）** → **调用不发声**。别指望用它在零跑上放提示音。

### 2.4 可发送的系统广播（`RebootReceiver` 接收）

| Action | Extras | 行为 |
|---|---|---|
| `com.leapmotor.RESTORESYSTEM` | `restart` (int) | `==0` 时重启系统 |
| `com.leapmotor.SETTIME` | `year,month,day,hour,minute,second` (int) | 系统校时 |
| `com.leapmotor.SLEEPCTRL` | `sleep` (boolean) | 见 §1.5 |
| `android.intent.action.BOOT_COMPLETED` | — | 开机 |

**DiPlay 可用性**：✅ 可发送（重启/校时）。**DiPlay 不发送 `SETTIME`** 是原设计，但可作为发送方复用。
本 App **发出**的广播只有：`com.leapmotor.KILLICUUPDATE`（`kill`）、`test.leapmotor.adjust.BROADCAST`（`ctrl`/`command`）、`leapmotor.distance.uploadfile.BROADCAST`（`path`）。

### 2.5 Native 库（JNI 方法名，未反汇编 .so）

- `libnative-lib.so` → `com.leapmotor.systemupdate.LeapmotorCtrl`：`getCarType/getVin/getVinInfo/getHighVoltage/getDCDCState/sendUDSPackage/distanceDiagnose/enterUpgrade/update/...`（JNI 符号 `Java_com_leapmotor_systemupdate_LeapmotorCtrl_*`）
- `libdecrypt-lib.so` → `com.leapmotor.decrypt.LeapmotorDecrypt`：`seedKeyEXTSupplier/seedKeyExtLp/seedKeyPROGSupplier/seedKeyProgLp`（UDS 27 安全访问算法）+ `encryptFileSrc`（解密固件）

---

## 3. CloudLeamp（`com.leapmotor.cloudleamp`）—— 车云连接器

跑 MQTT(SSL) 连零跑云，是车上「云控（远程控制/诊断/取图/流量支付）」的落地执行端。

### 3.1 MQTT 配置（**DiPlay 不可复用**，仅作认知）

- Broker：`ssl://10.192.127.22:8883`（hardcode 内网地址；运行时可由 `<sdcard>/ipSourcefile.txt` 的 JSON 字段 `MQtt` 覆盖）
- 认证：`userName=lpCarClient` / `passWord=<弱口令>`；clientId = **VIN**
- **双向 TLS（mTLS）**：客户端证书 `res/raw/car.p12` + 信任库 `client.bks`（`MQttKeyStore=emqttp`），私钥由 TEE 托管（`libTeeClient.so`/`libca_sst.so`）
- Topic 模式：`/<VIN>/<业务>`，例如下行 `/remoteControl`、`/remote_diagnostic_request`；上行 `/vechicleStatus/candataGzip`、`/lpcloud/candata`、`/carVersion`、`/restartInfo`

**结论**：**DiPlay 无法也不应接这条链路**（缺 TEE 私钥、内网 broker、私有协议）。但它**证明**了本车有一条整车 CAN 上云通道。

### 3.2 `car.meter.carinfo.BROADCAST` 报文格式（★ 可接收）

CloudLeamp **只是接收方**（`smali/com/cloudBroadcast/carCloudBroadcast.smali:249-361`）。

| Extra | 类型 | 说明 |
|---|---|---|
| `receiver` | **byte[]** | 2 字节大端长度前缀 + UTF-8 JSON |

JSON：顶层 `type` 必须 == **`"carinfo"`**，`data` 内含 `infotype`（String）+ `state`（数值）：

| `infotype` | state 类型 | 语义 |
|---|---|---|
| `"speed"` / `"vehiclespeed"` | int | 车速 |
| `"currentmile"` | int | 当前里程 |
| `"tripmile"` | double | 单次行程里程 |
| `"adasswitch"` | int | ADAS 开关 |

同一 receiver 还读普通 String extra：`car_lock`（`"lock_on"`）、`air_state`（`"air_on"`）、`type`（`"car_gps"`）+ `latitude`/`longitude`/`altitude`。

**⚠️ 关键负结论**：这三个包（及 `VehicleSetting`、`LauncherThree`、`dudu`）**都找不到发送方**。生产者在另一个**未解包的系统/仪表应用**里。
→ **DiPlay 不能假设它会来**。若要用，需先找到生产者并确认它常驻；**当前车辆信号仍应以已实现的 `leapmotor.cantoapp.update` CAN 总线为准**。

### 3.3 ★ VIN 配方法（**DiPlay 可直接用**）

`smali/com/commonTools/commonStaticTools.smali:240-322` `readFileId()`：

1. 先读 **`/data/leapmotor/VinInfo`**（逐行拼接）
2. 不存在 → 退到 **`<外部存储>/VinInfo.txt`**（`Environment.getExternalStorageDirectory() + "/VinInfo.txt"`）
3. 兜底：`TelephonyManager.getDeviceId()`（退化用 IMEI）

**DiPlay 可用性**：✅ **直接读文件即可**，无需 AIDL、无需权限（`/data/leapmotor/` 已验证世界可读）。
**用途**：VIN 功能（原 BYD 独有）的零跑解法。与 `DeviceCtrlAidlService.getVinInfo()`（§2.1）互为备用。

### 3.4 其它

- `com.leapmotor.coreControl_infor`、`com.leapmotor.cloud_APPControl`、`com.leapmotor.broadcast.navitocload.location` —— manifest 声明了 action，但 **smali 中无任何处理分支**（未使用）→ **不要依赖**。`navitocload.location` 疑似等于 §3.2 的 GPS 路径，含糊未定。
- `com.leapmotor.ClickCall`（extra `on1state="ClickCall"`）→ 打包成 `{"result":0,...,"reason":32}` 救援上报。与 CarPlay 无关。
- CloudLeamp **发出**的广播（DiPlay 可消费/可生产，均属云控链路）：`com.leapmotor.APPCARINFO`、`com.leapmotor.chargesettings_fromapp`、`leapmotor.carapp.BcmWakeCtrl`、`com.leapmotor.windowCtrl`、`com.leapmotor.carIdChange` 等（完整表见 `defineStaticInfo` 常量区）。

---

## 4. 汇总：DiPlay 可落地的选项（按价值排序）

| 优先级 | 功能 | 通道 | 包 | 门槛 | 说明 |
|---|---|---|---|---|---|
| **P0** | **来电弹窗 / 通话卡片** | 广播 `com.leapmotor.phonecall`（`MMI_PhoneSts` 0/1/2） | PhoneApp | 无 | 立刻可用；可升级为 `car.hmi.sender.BROADCAST` JSON 拿号码 |
| **P0** | **VIN** | 文件 `/data/leapmotor/VinInfo` | CloudLeamp | 无 | 直接读文件 |
| P1 | 高压/DCDC 状态补车辆状态 | AIDL `IDeviceCtrlInterface.getHighVoltage/getDCDCState` | SystemUpdate | signature（DiPlay 满足） | 需写 AIDL 客户端 |
| P1 | 车系判定（比文件更权威） | AIDL `getCarType()` (0x15) | SystemUpdate | signature | 与读文件二选一 |
| P1 | 通话记录 / 通讯录 | ContentProvider `com.jacp.provider.demo.programmer` / `.leader` | PhoneApp | 无 | 静态数据，依赖 PBAP 同步 |
| P2 | 接听/挂断通话 | 广播 `com.leapmotor.ICU2MMICtrl`（`ICU_PhoneCtrl` 1/2/3） | PhoneApp | 无（隐式） | **需实车验证**动态接收器可达性 |
| P2 | 启动电话应用 | 广播 `com.leapmotor.command.phone`（`type="open"`） | PhoneApp | 无 | **必须带 `type`**，否则对方 NPE |
| P2 | 车速/里程（若生产者存在） | 广播 `car.meter.carinfo.BROADCAST` | CloudLeamp（接收） | 无 | **⚠️ 生产者未知，先用 CAN 总线** |
| P3 | 重启/校时 | 广播 `com.leapmotor.RESTORESYSTEM` / `SETTIME` | SystemUpdate | 无 | 谨慎使用 |
| ✗ | 放提示音 | AIDL `ISoundEffectPlayInterface.play` | SystemUpdate | normal | **空实现，不发声** |
| ✗ | 系统 shell / UDS 注入 | AIDL `ISpiStateInterface.distanceDiagnose(-102)` | SystemUpdate | 无 | **危险，禁用** |
| ✗ | 云端数据 | MQTT | CloudLeamp | mTLS+TEE | 不可复用 |

---

## 5. 实车验证清单（写代码前先抓）

1. **来电**：注册 `com.leapmotor.phonecall`，真机拨入 → 应依次收到 `MMI_PhoneSts=1`（响铃）、`2`（接通）、`0`（挂断）。
2. **详细 JSON**：同时注册 `car.hmi.sender.BROADCAST`，打印 `sender` 解码后的 JSON，确认 `type/data.state` 字段与 agent 解出的一致。
3. **主动接听**：`sendBroadcast(Intent("com.leapmotor.ICU2MMICtrl").putExtra("ICU_PhoneCtrl",1))`，看是否接通（需 PhoneApp 在跑）。
4. **AIDL**：`bindService(Intent("Leapmotor.DeviceCtrlAidlService").setPackage("com.leapmotor.systemupdate"))` 是否成功；成功则调 `getCarType()` 看返回值。
5. **VIN**：`cat /data/leapmotor/VinInfo` 是否存在、内容是否 17 位 VIN。
6. **carinfo 生产者**：起会话后若从未收到 `car.meter.carinfo.BROADCAST`，说明生产者不在跑 → 车辆信号继续走 CAN 总线。

---

## 6. 证据索引（smali 行号）

| 结论 | 文件 : 行 |
|---|---|
| `phonecall` 广播 + `MMI_PhoneSts` 取值 | `PhoneApp_unpacked/smali/com/car/hmi/bluetooth/utils/BluetoothPbapServer.smali:427-502` |
| `command.phone` 的 `type` NPE 风险 | `PhoneApp_unpacked/smali/com/leapmotor/model/VoiceReceiver.smali:112-127` |
| `SLEEPCTRL` 处理 | `PhoneApp_unpacked/smali/com/car/hmi/bluetooth/utils/BluetoothPbapServer$8.smali:322-352` |
| Leader/Programmer Provider 列与 URI | `PhoneApp_unpacked/smali/com/car/hmi/bluetooth/utils/LeaderProvider.smali:64-247`、`ProgrammerProvider.smali:47-119` |
| `ICU2MMICtrl` 动态接收 | `PhoneApp_unpacked/smali/com/car/hmi/bluetooth/utils/BluetoothPbapServer$8.smali:199-319` |
| `IDeviceCtrlInterface` 38 方法 + 交易码 | `SystemUpdate_unpacked/smali/com/leapmotor/deviceCtrl/IDeviceCtrlInterface$Stub.smali` |
| `getCarType` = 0x15 / `getVinInfo` = 0x14 | 同上 `TRANSACTION_getCarType/getVinInfo` |
| `setLcd` 实为 setVolume | `SystemUpdate_unpacked/smali/com/leapmotor/systemupdate/aidlService/DeviceCtrlAidlService$1.smali:1233-1310` |
| `ISpiStateInterface` 4 方法 | `SystemUpdate_unpacked/smali/com/leapmotor/deviceCtrl/ISpiStateInterface$Stub.smali` |
| `distanceDiagnose -102` = shell | `SystemUpdate_unpacked/smali/com/leapmotor/systemupdate/aidlService/SpiStateAidlService.smali` |
| `play` 空实现 | `SystemUpdate_unpacked/smali/com/leapmotor/systemupdate/aidlService/SoundEffectPlayAidlService$1.smali` |
| 三 AIDL 服务权限门 | `SystemUpdate_unpacked/AndroidManifest.xml:42-56` |
| MQTT broker / 证书 | `CloudLeamp_unpacked/smali/com/mqttMain/mqttClientServer.smali:634,639-646,2870+` |
| Topic 列表 | `CloudLeamp_unpacked/smali/com/mqttMain/mqttClientServer.smali:3351-4216`、`defineStaticInfo.smali:499-1285` |
| `car.meter.carinfo` 报文格式 | `CloudLeamp_unpacked/smali/com/cloudBroadcast/carCloudBroadcast.smali:249-361` |
| VIN 路径 | `CloudLeamp_unpacked/smali/com/commonTools/commonStaticTools.smali:250,297` |
| 交叉验证：VehicleSetting 也调 `getVinInfo` | `VehicleSetting_unpacked/smali/com/leapmotor/vehiclesetting/setting/service/GetVersionIntentService.smali:116` |
