# DiPlay

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

为兼容的比亚迪安卓车机提供有线及无线 CarPlay，采用 DiAuto 风格界面。

> 这些项目专注于比亚迪汽车。它们可能在其他品牌上运行，但其他品牌不在支持范围内，也没有增加支持或修复其品牌特定兼容性问题的计划。

[下载与中文网站](https://shihabal3amri.github.io/DiPlay/zh-Hans/) · [0.2.21 版本](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.21) · [完整说明](README.md) · [报告问题](https://github.com/shihabal3amri/DiPlay/issues/new/choose)

## 0.2.21 — 公开预览版

请安装在允许 APK 安装的 Android 7.0+（API 24+）车机上；Android 7.0–8.1 尚未经实车验证。无需越狱、转接盒、账户或认证服务器。有线及无线核心连接不要求 ADB；可选车辆数据及车辆控制需要支持的固件和已授权网络 ADB。

Wi-Fi Direct 现支持 Android 7.0–9 的旧版建组路径，使用系统返回的真实凭据。首选信道依赖固件 API；Android 7.0–9 无法读回协商频率，所以请求信道在诊断中标为未经验证，系统默认为信道 0。Android 10+ 保留频率验证。也可使用车机内置热点、USB 或[现有 Wi-Fi／同一局域网](docs/EXISTING_WIFI.md)；同一局域网模式由车机和 iPhone 自行连接外部路由器，DiPlay 不替你修改默认路由。[Android 9 Wi-Fi Direct 限制](docs/ANDROID9_WIFI_DIRECT.md)说明清理及持久配置边界。

### 新增与修正

- **音乐**：修正 0.2.20 中音乐落后于 iPhone 的问题；暂停、切歌不再延迟几秒，播放速度也不再忽快忽慢。
- **有线连接**：iPhone 收下开始请求却一直不打开 CarPlay 时，DiPlay 会自动重新连接；点“断开连接”后不再一直等待；已经自行恢复的连接不再被重试断开。
- **麦克风与音乐**：在没有安卓自带 Opus 编码器的车机上使用麦克风时，DiPlay 不再闪退；通话或短暂打断后，音乐会恢复播放。
- **无线连接与比亚迪车机**：无线 CarPlay 无法启动时会显示原因；CarPlay 结束后，DiPlay 会把仪表交还给原车；从 DiYou 桌面卡片打开时不再闪退；Android 10 以上的比亚迪车机可使用“隐藏比亚迪来电弹窗（实验性）”。
- **安装与语言**：DiPlay 可安装在 Android 7.0 上，并新增意大利语。
- **新选项（默认关闭）**：临时显示系统栏（5 秒）、黑色圆角遮罩、方向盘播放／暂停按钮、双击触发 Siri、导入车机热点详情（实验性）、桌面悬浮球（实验性）、车辆原厂音乐律动。

[0.2.21 完整说明](docs/RELEASE-NOTES-0.2.21.md)包含贡献链接及功能限制；构建和验证信息见[验证记录](docs/VALIDATION.md)。Android 7.0–8.1 尚需实车验证，不宣称所有车型的连接、音频或 Siri 问题均已解决。可选功能请停车后测试。

### 请提供 0.2.21 的新诊断报告

1. 更新到 **0.2.21**，复现问题并记录发生时间。开机／自动启动问题发生后，可手动打开 DiPlay 导出。
2. 打开“**设置 → 诊断 → 保存诊断报告**”。Android 10+ 通常保存到 **Downloads/DiPlay**；Android 7.0–9 会请求存储权限并同样保存到该位置，也可点“选择保存位置”。如选择器或公共存储不可用，应用会使用专用外部或私有目录，并在确认中说明目的地。
3. 使用确认中的**查看报告／分享**；没有分享应用时，可在报告视图中选择并复制文本。检查 `.txt` 并删除隐私信息，再附到匹配的[现有问题](https://github.com/shihabal3amri/DiPlay/issues)，或[新建问题](https://github.com/shihabal3amri/DiPlay/issues/new/choose)。报告不会自动上传，请勿公开热点密码或私有认证文件。
4. 注明车型／车机、DiLink/Android/完整固件版本、iPhone/iOS、USB／车机热点／Wi-Fi Direct／同一局域网、相关设置、复现步骤、预期与实际结果及故障时间。

[从源码构建](docs/BUILD.md)：主应用请选择 `mobile` 模块。`maphost` 是地图演示应用，构建步骤和 APK 路径见说明。

历史记录：[0.2.17](docs/RELEASE-NOTES-0.2.17.md)、[0.2.16](docs/RELEASE-NOTES-0.2.16.md)、[0.2.15](docs/RELEASE-NOTES-0.2.15.md)、[0.2.14](docs/RELEASE-NOTES-0.2.14.md)、[0.2.13](docs/RELEASE-NOTES-0.2.13.md)、[0.2.12](docs/RELEASE-NOTES-0.2.12.md)、[0.2.11](docs/RELEASE-NOTES-0.2.11.md)、[安装与连接](docs/INSTALL.md)。

这是公开预览版，**未经 Apple 认证**。APK 使用从公开 Carlinkit 固件中提取的既有实验性配件身份，并非为 DiPlay 新签发的 MFi 身份；其中的私钥可被提取，未来 iOS 是否继续接受及其公开分发适用性尚未确定。Android 签名密钥和配件身份不进入 Git 或源代码压缩包；普通源代码/CI 构建默认不配置身份。部分车机仍可能卡顿或无法应用图标大小设置。

标准导航小组件需要支持 Android 小组件的启动器；比亚迪内置主页不接受任意小组件。悬浮地图和嵌入地图需要启用“CarPlay 仪表地图”。应用及网站源代码包含英语、简体中文、繁体中文（台湾）、阿拉伯语、俄语、乌克兰语、西班牙语和日语。应用的香港／澳门及 Hant 选择使用台湾译文，不宣称提供独立地区翻译。[繁体中文说明](README.zh-TW.md)提供安装、设置、隐私和构建指南。源代码、构建说明及许可证随版本提供。
