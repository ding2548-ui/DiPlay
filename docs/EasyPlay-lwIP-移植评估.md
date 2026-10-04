# 有线 lwIP 去 VPN 化 —— 移植评估（beta 实验）

来源：EasyPlay（`com.shihab.diplay.legacy`，`D:\Launcher\EasyPlay-Android4.4-10-preview36_unpacked`）
日期：2026-10-03　　状态：**组件已移植，未接入现网 CarPlay 路径**

## 一、结论前置

| 项 | 结论 |
|---|---|
| 技术可行性 | **成立**。我们的 `NcmUsbBridge` 与 EasyPlay 同源（0.2.7 系），`send(frame, timeout)` / `recv(timeout)` / `hostMac` 帧接口一致，lwIP 会话类可以直接吃我们的桥 |
| 本次落地 | `LwipNative.kt`（28 个 JNI 方法全量移植）+ `LwipSessionNetwork.kt`（会话生命周期、收发泵、TCP/UDP socket 面）+ `libdiplay_lwip.so`（armeabi-v7a，476 KB，来自 EasyPlay preview36） |
| 未落地 | **AirPlay/iAP2 socket 层切换**：现网路径（`CarPlayVpnService` + `Ipv6NcmBridge` → 内核 TUN）仍在服务；lwIP 的 socket 对象尚未接给 AirPlay 监听/会话代码 |
| 硬约束 | **native 库只有 armeabi-v7a**：arm64 进程无法加载（`LwipNative.available=false`），要在真车启用必须出 **v7a-only 变体**（`shared/build.gradle` 的 abiFilters 去掉 arm64/x86_64），整 APK 以 32 位进程运行 |
| 风险 | 中高：帧方向逻辑（`input` 丢帧计数、`pollOutput` 背压）、lwIP 的 mDNS 组播、AirPlay 对半关闭（shutdown）的依赖，都需要真机逐项验证 |

## 二、NCM 帧路径评估（为什么可行）

现状（内核路径）：

```
iPhone ⇄ USB(iAP2/NCM) ⇄ NcmUsbBridge 帧 ⇄ Ipv6NcmBridge ⇄ TUN(VpnService) ⇄ 内核 IPv6 ⇄ App socket
```

lwIP 路径（EasyPlay 已验证形态）：

```
iPhone ⇄ USB(iAP2/NCM) ⇄ NcmUsbBridge 帧 ⇄ LwipNative.input / pollOutput ⇄ lwIP 用户态栈 ⇄ LwipSessionNetwork socket ⇄ App
```

- **不再需要 VpnService**：无 TUN、无内核路由、无防泄漏规则（`allowFamily`/`addAllowedApplication`
  这两类已付费的坑彻底消失）、无 VPN 授权弹窗。
- **只吃我们自己的 USB 帧**：与无线 7000 端口问题**完全无关**（无线是 mangle PREROUTING 丢
  "发往本机地址"的包，App 空间无解；lwIP 在这里帮不上忙，见
  `DiPlay-无线7000被丢-根因分析-车机ROM.md`）。
- `LwipSessionNetwork` 的会话链路本地地址由 lwIP 自己分配（`getLocalAddress`），
  `start(hostMac)` 后 `receiveFrames`（`ncm.recv → input`）与 `transmitFrames`
  （`pollOutput → ncm.send`）两条泵线程维持双向流动。

## 三、已移植组件

| 文件 | 内容 |
|---|---|
| `shared/src/main/jniLibs/armeabi-v7a/libdiplay_lwip.so` | lwIP 完整用户态栈（来自 EasyPlay，JNI 符号绑定 `com.shilapi.xcertplay.network.LwipNative`） |
| `shared/.../network/LwipNative.kt` | 28 个 native 方法 + `available`（加载失败不炸 attach 路径） |
| `shared/.../network/LwipSessionNetwork.kt` | 会话生命周期（start/close）、双泵线程、`tcpListener`/`tcpSocket`/`udpSocket` 原语 |

`TcpListener.accept / bind / localPort`、`TcpSocket.connect / input / output / setTcpNoDelay`、
`UdpSocket.bind / sendto / recvfrom / joinGroup` 的语义对齐 EasyPlay 原版，后续把 AirPlay 的
`ServerSocket`/`Socket`/`DatagramSocket` 调用点替换为这三个原语即可完成切换。

## 四、下一步（接入现网前必须完成）

1. **出 v7a-only beta 变体** 并确认真车以 32 位进程跑起来（`LwipNative.available=true`）。
2. 把 `CarPlayHostActivity` 的 wired 装配点做成可切换：lwIP 模式下不再 establish VPN，
   AirPlay 监听绑 `LwipSessionNetwork.localAddress():7000`，iAP2 会话的 socket 走 lwIP 原语。
3. 真机验证清单：握手帧方向、`recv` 250ms 轮询的吞吐（对照 24–26 fps 视频指标）、
   mDNS 组播、iPhone 断连时的半关闭与资源回收、长会话内存（lwIP 堆大小）。
4. 性能对照：现网内核路径 24–26 fps / LPCM 零丢包 / 触控 35 ms 是回归基线，低于即视为不可用。

## 五、相关 EasyPlay 证据

- `lib/armeabi-v7a/libdiplay_lwip.so`（476 KB）导出
  `Java_com_shilapi_xcertplay_network_LwipNative_{start,input,pollOutput,socket,...}`
- Java 侧字符串：`lwip-ncm-in` / `lwip-ncm-out` / `wired userspace NCM network started` /
  `wired userspace frame direction=in|out` / `lwIP must supply a session link-local IPv6 address`
- EasyPlay 的 `LwipSessionNetwork` 构造器直接接收 `NcmUsbBridge`——与我们同名同源同 API。
