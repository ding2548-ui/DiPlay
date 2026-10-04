// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var exportButton: Button? = null
    // Display switches are updated in place (never a full render()) so the settings page
    // keeps its scroll position when one of them drives the other two.
    private var fullscreenSwitch: Switch? = null
    private var hideTopBarSwitch: Switch? = null
    private var hideBottomBarSwitch: Switch? = null
    private var syncingDisplaySwitches = false
    private var updateBusy = false
    private var updateMessage: TextView? = null
    private var updateActionButton: Button? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp("附近设备", "允许“附近设备”，DiPlay 才能连接已配对的 iPhone。")
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            "本地初始化未完成。请重新安装完整的 DiPlay 测试版 APK 后重试。"
        }
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { page = "home"; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); super.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun onResume() {
        super.onResume(); handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && page == "home") render()
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun render() {
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        updateMessage = null; updateActionButton = null
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        val content = column().apply { setPadding(dp(32), dp(24), dp(32), dp(32)) }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_carplay); contentDescription = "CarPlay" }, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(label("DiPlay", 26, TEXT, true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(button(if (page == "home") "车机首页" else "返回", false) {
            if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            else { page = "home"; render() }
        }, LinearLayout.LayoutParams(dp(130), dp(56)))
        content.addView(header)
        content.addView(space(24))
        when (page) {
            "settings" -> settings(content)
            "about" -> about(content)
            else -> home(content)
        }
        setContentView(scroll)
        refreshStatus()
    }

    private fun home(content: LinearLayout) {
        val wide = resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        left.addView(label("你的手机，你的驾驶", 12, ACCENT, true).apply { letterSpacing = .16f })
        left.addView(label("熟悉的驾驶体验", if (wide) 42 else 36, TEXT, true).apply { setPadding(0, dp(12), 0, dp(10)) })
        left.addView(label("地图、音乐与通话。\nCarPlay，就在你的车机上。", 19, MUTED))
        val card = card()
        card.addView(label("无线 CARPLAY", 12, ACCENT, true).apply { letterSpacing = .12f })
        status = label("随时就绪", 24, TEXT, true).apply { setPadding(0, dp(10), 0, dp(16)) }
        card.addView(status)
        connectButton = button("连接手机", true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)
        }
        card.addView(connectButton, matchButton())
        card.addView(label("先将 iPhone 与车机蓝牙配对，然后连接。\n请保持蓝牙和 Wi-Fi 开启。", 15, MUTED).apply { setPadding(0, dp(14), 0, 0) })
        if (carHotspotOff()) {
            card.addView(label("车机热点“${AirPlayPersistence.loadManualHotspotSsid(this)}”已关闭。请先在车机设置中打开热点再连接。", 15, WARNING).apply { setPadding(0, dp(14), 0, 0) })
            card.addView(button("打开车机热点设置", false) { openCarWifiSettings() }, matchButton(10, 56))
        }
        card.addView(button("选择 iPhone", false) { choosePhone() }, matchButton(16, 56))
        disconnectButton = button("断开连接", false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        card.addView(disconnectButton, matchButton(10, 56))
        val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = "CarPlay 图标"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val branding = column().apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
        right.addView(button("使用 USB 连接", false) { connect(false) }, matchButton())
        right.addView(label("将 iPhone 插入 USB 数据口。\niPhone 提示时请允许 CarPlay。", 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(24)) })
        right.addView(button("设置", false) { page = "settings"; render() }, matchButton())
        right.addView(label("让 DiPlay 适配你的车机", 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24)) })
        right.addView(label("公开预览  ·  ${version()}", 12, MUTED).apply { letterSpacing = .08f })
        if (wide) {
            // Both rows share column widths. The USB button starts at the wireless
            // card's top edge, independently of hero wrapping or font scaling.
            fun columns(first: View, second: View, stretchSecond: Boolean = false) = row().apply {
                gravity = Gravity.TOP
                addView(first, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(40), LinearLayout.LayoutParams(dp(40), 1))
                addView(second, LinearLayout.LayoutParams(0, if (stretchSecond) -1 else -2, 1f))
            }
            body.addView(columns(left, branding, true))
            body.addView(space(26))
            body.addView(columns(card, right))
        } else {
            body.addView(left)
            body.addView(space(26))
            body.addView(card)
            body.addView(space(26))
            body.addView(branding)
            body.addView(space(24))
            body.addView(right)
        }
        setupError?.let { body.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
        content.addView(body)
    }

    private fun settings(content: LinearLayout) {
        content.addView(label("你的驾驶，你做主", 34, TEXT, true))
        content.addView(label("尺寸、分辨率、音乐缓冲和帧率的更改会重连 CarPlay；其他更改在下次连接时生效。", 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "自动连接") { card ->
            toggle(card, "DiPlay 启动时连接", "沿用上次的连接方式和所选 iPhone。", DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            toggle(card, "车机启动后自动打开", "是否可用取决于车机的开机启动设置。", AirPlayPersistence.loadAutoStartOnBoot(this)) { AirPlayPersistence.saveAutoStartOnBoot(this, it) }
            card.addView(button("选择 iPhone · ${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
        }
        section(content, "无线连接") { card -> wirelessLinkControls(card) }
        section(content, "方控学习") { card -> wheelLearningControls(card) }
        section(content, "蓝牙接管") { card ->
            toggle(card, "CarPlay 接管时断开车机蓝牙音频",
                "CarPlay 出声时主动断开手机的 A2DP / 通话音频 profile（只断连接，不断配对），" +
                    "避免画面在 CarPlay、声音还走车机蓝牙。关闭后沿用音频焦点压制。",
                DiPlayPreferences.a2dpHandoff(this)) { DiPlayPreferences.saveA2dpHandoff(this, it) }
        }
        section(content, "有线 lwIP 传输（实验）") { card ->
            toggle(card, "有线走 lwIP 用户态网络栈",
                "有线 CarPlay 不再建立 VPN/内核路由，NCM 帧直接进用户态 lwIP 栈，" +
                    "经回环代理进入 AirPlay 服务（实验，需真机验证）。" +
                    "需要 32 位构建：本构建 " +
                    (if (com.shilapi.xcertplay.network.LwipNative.available) "已满足" else "不满足（当前进程无法加载 lwIP 库）") +
                    "。更改在下次有线连接时生效。",
                DiPlayPreferences.wiredLwip(this)) { DiPlayPreferences.saveWiredLwip(this, it) }
        }
        section(content, "显示与性能") { card ->
            carPlaySizeControl(card)
            // 16-step ladder, matching AutoKit's resolution option count; each label shows the
            // resulting pixel size on this head unit so 720P/360P is readable at a glance.
            val resolutionPercents = listOf(175, 170, 165, 160, 155, 150, 145, 140, 135, 130, 125, 120, 115, 110, 105, 100, 95, 90, 85, 80, 75, 70, 65, 60, 55, 50, 45, 40, 35, 30, 25)
            val nativeW = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            val nativeH = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            fun scaledPixels(pixels: Int, percent: Int): Int {
                val value = ((pixels.toLong() * percent + 50L) / 100L).toInt().coerceAtLeast(1)
                return if (value % 2 == 0) value else value + 1
            }
            val resolutionLabels = resolutionPercents.map { percent ->
                val outW = scaledPixels(nativeW, percent)
                val outH = scaledPixels(nativeH, percent)
                val tag = when (percent) {
                    100 -> "原生"
                    50 -> "半分辨率"
                    25 -> "负载最轻"
                    else -> "${outH}P"
                }
                "$percent% · 约${outW}x$outH（$tag）"
            }
            choice(card, "分辨率", resolutionLabels,
                resolutionPercents.indexOf(AirPlayPersistence.loadDisplayScalePercent(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveDisplayScalePercent(this, resolutionPercents[it])
            }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, "音乐缓冲", listOf("300 毫秒 · 默认", "500 毫秒", "1000 毫秒 · 最稳定"),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            choice(card, "帧率", listOf("30 fps · 负载更轻", "60 fps · 画面更流畅"), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            toggle(card, "高效视频", "使用 HEVC。关闭可获得最广的车机兼容性。", AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
            toggle(card, "右舵", "让 CarPlay 控件更靠近驾驶员。", AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            // Three interlocked display switches. 全屏 = both bars hidden; either bar switch
            // works standalone and closes 全屏. The master state is derived from the two bar
            // switches, so the UI can never disagree with what CarPlay will actually do.
            // Updates happen in place — no render(), so the page keeps its scroll position.
            fun refreshDisplaySwitches() {
                syncingDisplaySwitches = true
                val hideTop = AirPlayPersistence.loadHideTopBar(this)
                val hideBottom = AirPlayPersistence.loadHideBottomBar(this)
                hideTopBarSwitch?.isChecked = hideTop
                hideBottomBarSwitch?.isChecked = hideBottom
                fullscreenSwitch?.isChecked = hideTop && hideBottom
                syncingDisplaySwitches = false
            }
            fun reconnectToApply() {
                if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
            }
            fullscreenSwitch = switchRow(card, "全屏",
                "CarPlay 打开时隐藏车机状态栏与导航栏（两者都隐藏）。打开会自动关闭下面两个单独开关。",
                AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) { value ->
                AirPlayPersistence.saveHideTopBar(this, value)
                AirPlayPersistence.saveHideBottomBar(this, value)
                refreshDisplaySwitches()
                reconnectToApply()
            }
            hideTopBarSwitch = switchRow(card, "隐藏状态栏",
                "CarPlay 打开时隐藏车机状态栏。打开会自动关闭“全屏”。",
                AirPlayPersistence.loadHideTopBar(this)) { value ->
                AirPlayPersistence.saveHideTopBar(this, value)
                refreshDisplaySwitches()
                reconnectToApply()
            }
            hideBottomBarSwitch = switchRow(card, "隐藏导航栏",
                "CarPlay 打开时隐藏车机导航栏。打开会自动关闭“全屏”。",
                AirPlayPersistence.loadHideBottomBar(this)) { value ->
                AirPlayPersistence.saveHideBottomBar(this, value)
                refreshDisplaySwitches()
                reconnectToApply()
            }
        }
        if (com.shilapi.xcertplay.hud.BydOutputSettings.available(this)) section(content, "比亚迪导航") { card ->
            toggle(card, "在 HUD 与仪表盘上显示导航",
                "在支持的比亚迪屏幕上显示手机导航箭头、距离和路名。车型兼容性各异。",
                com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
        }
        section(content, "权限与连接帮助") { card ->
            card.addView(label("“附近设备”用于连接 iPhone；麦克风用于 Siri 和通话；较旧的 Android 版本还需位置权限才能进行无线配置；USB 模式可能请求建立本地 VPN 连接。", 16, MUTED))
            card.addView(button("应用权限", false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button("蓝牙设置", false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 60))
            card.addView(button("无线连接帮助", false) { wirelessHelp() }, matchButton(10, 60))
        }
        section(content, "在线更新") { card ->
            card.addView(label("检测 GitHub 上的新构建：自动下载、安装并重新打开 DiPlay，装好后会删除下载的 APK。", 14, MUTED))
            updateMessage = label("当前版本 ${version()}", 16, TEXT).apply { setPadding(0, dp(12), 0, 0) }
            card.addView(updateMessage)
            updateActionButton = button("检查更新", false) { startUpdateCheck() }
            card.addView(updateActionButton, matchButton(12, 60))
            card.addView(updateSourceButton(), matchButton(10, 60))
        }
        section(content, "关于与诊断") { card ->
            card.addView(button("关于 DiPlay", false) { page = "about"; render() }, matchButton(0, 60))
            exportButton = button(if (exportInProgress) "正在保存报告…" else "保存诊断报告", false) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                else chooseReportDestination()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "报告保存到 Downloads/DiPlay。" else "选择报告的保存位置。"
            card.addView(label(destination + "不会自动发送任何内容。协议载荷与凭据均不包含在内。", 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
    }

    private fun about(content: LinearLayout) {
        content.addView(label("DiPlay", 40, TEXT, true))
        content.addView(label("CarPlay，融入你的车机", 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "公开预览 · ${version()}") { card ->
            card.addView(label("面向 Android 车机的独立 CarPlay 接收端。有线与无线连接都在车机本地完成，并采用本地认证。普通 iPhone 无需越狱、Mac、转接器或登录即可连接。\n\n本预览版使用实验性的配件身份，与每款 iPhone 和车机的兼容性仍在测试中。它并非 Apple 认证产品。", 17, TEXT))
        }
        section(content, "得益于开源") { card ->
            card.addView(label("接收端基于 xcertplay，遵循 GPL-3.0 许可。DiPlay 的界面沿用 DiAuto 的设计，遵循 AGPL-3.0 许可。\n\n包含 AndroidX、Bouncy Castle、JmDNS 与 SLF4J。随版本附有源码与许可声明。\n\nCarPlay 及 CarPlay 图标归 Apple Inc. 所有。DiPlay 是独立项目。", 16, MUTED))
        }
    }

    /** Beta: per-action steering wheel learning, ported from EasyPlay. */
    private fun wheelLearningControls(card: LinearLayout) {
        card.addView(label(
            "按一次车上的按键，把它绑定到下面的动作。只有学习过的键会被转发给 CarPlay，" +
                "未学习的键一律忽略。学习按键支持媒体按键广播与零跑车机广播两种来源。",
            14, MUTED,
        ))
        val bindings = WheelLearningStore.load(this)
        var top = 16
        for (action in WheelAction.entries) {
            val binding = bindings.firstOrNull { it.action == action }
            card.addView(button("${action.label} · ${binding?.label() ?: "未学习"}", false) {
                startWheelLearning(action)
            }, matchButton(top, 60))
            top = 10
        }
        card.addView(button("清除全部方控学习", false) {
            WheelLearningStore.clear(this)
            LearnedWheelKeys.refreshBroadcastReceivers(this, emptyList())
            toast("已清除全部方控学习")
            render()
        }, matchButton(10, 60))
        card.addView(space(12))
        val logEnabled = LearnedWheelKeys.isBroadcastLogEnabled(this)
        toggle(card, "监听方控广播日志",
            "按 action 监听车机方控广播并记录最近 12 条；点任意一条可把它绑定为方控键。",
            logEnabled) {
            LearnedWheelKeys.setBroadcastLogEnabled(this, it)
            render()
        }
        if (logEnabled) {
            val entries = LearnedWheelKeys.broadcastLogEntries()
            if (entries.isEmpty()) {
                card.addView(label("暂未捕获到广播——按几下方向盘按键后回到本页查看。", 14, MUTED))
            } else {
                entries.reversed().forEachIndexed { index, entry ->
                    card.addView(button("广播 ${entry.action} · ${entry.detail}", false) {
                        chooseWheelActionForBroadcast(entry)
                    }, matchButton(if (index == 0) 10 else 6, 56))
                }
            }
        }
    }

    private fun chooseWheelActionForBroadcast(entry: LearnedWheelKeys.BroadcastLogEntry) {
        val options = WheelAction.entries.map { it.label }.toTypedArray()
        var pending = 0
        AlertDialog.Builder(this).setTitle("把广播绑定为方控动作")
            .setSingleChoiceItems(options, 0) { _, index -> pending = index }
            .setPositiveButton("保存") { _, _ ->
                val target = WheelAction.entries[pending]
                val id = entry.bindingId
                val bindings = WheelLearningStore.load(this)
                    .filter { it.action != target && it.id != id } + WheelBinding(id, target)
                if (WheelLearningStore.save(this, bindings)) {
                    LearnedWheelKeys.refreshBroadcastReceivers(this, bindings)
                    toast("已学习：${target.label} ← 广播 ${entry.action} · ${entry.detail}")
                } else {
                    toast("无法保存方控设置，请重试")
                }
                render()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun startWheelLearning(action: WheelAction) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("${action.label} · 方控学习")
            .setMessage("请按一次车上的按键。\n\n只记录按键，不改动任何配对；按“取消”放弃。")
            .setNegativeButton("取消") { _, _ -> LearnedWheelKeys.cancelCapture() }
            .show()
        LearnedWheelKeys.beginCapture(this) { id ->
            runCatching { dialog.dismiss() }
            val bindings = WheelLearningStore.load(this)
                .filter { it.action != action && it.id != id } + WheelBinding(id, action)
            if (WheelLearningStore.save(this, bindings)) toast("已学习：${action.label} ← ${WheelBinding(id, action).label()}")
            else toast("无法保存方控设置，请重试")
            render()
        }
    }

    private fun updateSourceButton(): Button {
        val sources = AppUpdater.sources()
        val labels = sources.map { AppUpdater.sourceLabel(it) }
        val picker = button("下载源 · ${AppUpdater.sourceLabel(AppUpdater.source(this))}", false) {}
        picker.setOnClickListener {
            var pending = sources.indexOf(AppUpdater.source(this))
            AlertDialog.Builder(this).setTitle("下载源")
                .setSingleChoiceItems(labels.toTypedArray(), pending) { _, index -> pending = index }
                .setPositiveButton("保存") { _, _ ->
                    if (pending != sources.indexOf(AppUpdater.source(this))) {
                        AppUpdater.saveSource(this, sources[pending])
                        picker.text = "下载源 · ${AppUpdater.sourceLabel(sources[pending])}"
                    }
                }
                .setNegativeButton("取消", null).show()
        }
        return picker
    }

    private fun startUpdateCheck() {
        if (updateBusy) return
        updateBusy = true
        updateActionButton?.isEnabled = false
        updateMessage?.text = "正在检查更新…"
        Thread {
            val result = runCatching { AppUpdater.latestBuild(AppUpdater.source(this)) }
            val current = AppUpdater.currentBuild(this)
            handler.post {
                updateBusy = false
                updateActionButton?.isEnabled = true
                val latest = result.getOrNull()
                when {
                    result.isFailure -> updateMessage?.text =
                        "检查失败：${result.exceptionOrNull()?.message ?: "网络不可达"}。可尝试更换下载源。"
                    latest == null || (current != null && latest <= current) ->
                        updateMessage?.text = "未发现更高构建（当前 ${version()}）。"
                    current != null && latest > current -> {
                        updateMessage?.text = "发现新构建 2.11（$latest），当前 2.11（$current）。"
                        updateActionButton?.text = "下载并安装 2.11（$latest）"
                        updateActionButton?.setOnClickListener { startUpdateDownload(latest) }
                    }
                }
            }
        }.start()
    }

    private fun startUpdateDownload(build: Int) {
        if (updateBusy) return
        updateBusy = true
        updateActionButton?.isEnabled = false
        updateMessage?.text = "正在下载 2.11（$build）…"
        Thread {
            val result = runCatching {
                AppUpdater.downloadApk(this, build, AppUpdater.source(this)) { done, total ->
                    if (total > 0) handler.post {
                        updateMessage?.text = "正在下载 2.11（$build）… ${done * 100 / total}%"
                    }
                }
            }
            handler.post {
                val apk = result.getOrNull()
                if (apk == null) {
                    updateMessage?.text = "下载失败：${result.exceptionOrNull()?.message ?: "网络不可达"}。可尝试更换下载源。"
                    updateBusy = false
                    updateActionButton?.isEnabled = true
                    return@post
                }
                updateMessage?.text = "下载完成（${apk.length() / 1048576} MB）。"
                AlertDialog.Builder(this).setTitle("安装更新")
                    .setMessage("已下载 2.11（$build）。安装期间 DiPlay 会短暂关闭，装好后自动重新打开。")
                    .setPositiveButton("立即安装") { _, _ ->
                        updateMessage?.text = "正在安装…"
                        Thread {
                            val installed = runCatching { AppUpdater.installApk(this, apk) }
                            handler.post {
                                if (installed.isFailure) {
                                    updateMessage?.text = "安装失败：${installed.exceptionOrNull()?.message}"
                                    updateBusy = false
                                    updateActionButton?.isEnabled = true
                                }
                            }
                        }.start()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        apk.delete()
                        updateMessage?.text = "已取消安装。"
                        updateBusy = false
                        updateActionButton?.isEnabled = true
                    }
                    .show()
            }
        }.start()
    }

    // The car hotspot link needs the hotspot on; DiPlay only checks it (turning it on needs ADB-only permission).
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle("车机热点已关闭")
            .setMessage("DiPlay 通过车机热点“${AirPlayPersistence.loadManualHotspotSsid(this)}”连接。请先在车机设置中打开热点，然后连接。")
            .setPositiveButton("打开车机设置") { _, _ -> openCarWifiSettings() }
            .setNeutralButton("连接") { _, _ -> connect(true) }
            .setNegativeButton("取消", null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun wifiDirectChannelLabel(channel: Int): String = when (channel) {
        WifiP2pChannels.AUTO -> "自动"
        else -> "信道 $channel（${if (channel < 36) "2.4 GHz" else "5 GHz"}）"
    }

    /** Preferred Wi-Fi Direct listen channel; saved and used from the next connection on. */
    private fun wifiDirectChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = { "首选 Wi-Fi Direct 信道 · ${wifiDirectChannelLabel(it)}" }
        val control = button(summary(AirPlayPersistence.loadWifiP2pPreferredChannel(this)), false) {}
        control.setOnClickListener {
            val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.channels
            val current = AirPlayPersistence.loadWifiP2pPreferredChannel(this)
            var selection = current
            AlertDialog.Builder(this).setTitle("Wi-Fi Direct 信道")
                .setSingleChoiceItems(choices.map(::wifiDirectChannelLabel).toTypedArray(),
                    choices.indexOf(current)) { _, which -> selection = choices[which] }
                .setPositiveButton("保存") { _, _ ->
                    if (selection != current) {
                        AirPlayPersistence.saveWifiP2pPreferredChannel(this, selection)
                        control.text = summary(selection)
                        toast("已保存，下次连接无线时生效")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        parent.addView(control, matchButton(12, 60))
        parent.addView(label("仅对 Wi-Fi Direct 方式生效；个别环境下固定信道能避开自动选到的拥挤信道。", 15, MUTED).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
    }

    // Wi-Fi Direct is the default link. The car's own hotspot is an alternative when Wi-Fi Direct is unstable.
    // The runtime config rejects manual mode without valid credentials, so it is only saved together with them.
    private fun wirelessLinkControls(parent: LinearLayout) {        val mode = AirPlayPersistence.loadWirelessHotspotMode(this)
        val carHotspot = mode == WirelessHotspotMode.MANUAL || mode == WirelessHotspotMode.EXTERNAL_WIFI
        val options = arrayOf("Wi-Fi Direct · 默认", "车机热点", "外部 Wi-Fi · 车机与手机同一网络")
        val currentIndex = when (mode) {
            WirelessHotspotMode.MANUAL -> 1
            WirelessHotspotMode.EXTERNAL_WIFI -> 2
            else -> 0
        }
        val control = button("无线方式 · ${options[currentIndex]}", false) {}
        control.setOnClickListener {
            var selection = currentIndex
            AlertDialog.Builder(this).setTitle("无线方式")
                .setSingleChoiceItems(options, selection) { _, index -> selection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) "应用并重连" else "保存") { _, _ ->
                    val target = when (selection) {
                        0 -> WirelessHotspotMode.WIFI_P2P
                        1 -> WirelessHotspotMode.MANUAL
                        else -> WirelessHotspotMode.EXTERNAL_WIFI
                    }
                    val liveSsid = currentStationSsid()
                    when {
                        target == mode -> Unit
                        selection == 0 -> applyWirelessLink(WirelessHotspotMode.WIFI_P2P)
                        hotspotError(storedSsid(), storedPassword()) == null ->
                            applyWirelessLink(target)
                        // External Wi-Fi never needs a typed password (the car is already a
                        // station member); fill the name from the live station connection so the
                        // user is not forced through the credential dialog.
                        target == WirelessHotspotMode.EXTERNAL_WIFI && liveSsid != null -> {
                            saveHotspotCredentials(liveSsid, storedPassword())
                            applyWirelessLink(target)
                        }
                        else -> askHotspotCredentials { ssid, password ->
                            saveHotspotCredentials(ssid, password)
                            applyWirelessLink(target)
                        }
                    }
                }.setNegativeButton("取消", null).show()
        }
        parent.addView(control, matchButton(0, 60)); parent.addView(space(12))
        // Wi-Fi Direct preferred channel (upstream 0.2.11): remembered and used from the next
        // connection on; "自动" keeps the previous behaviour. Only meaningful for Wi-Fi Direct.
        wifiDirectChannelControl(parent)
        if (!carHotspot) {
            parent.addView(label("DiPlay 会为 iPhone 自建 Wi-Fi Direct 网络。", 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            return
        }
        // Auto-fill: in external Wi-Fi mode the joined network's live name is the only value
        // that matters (the password field is unused for a network the car already joined).
        // Keep the stored name in sync with reality so the user never has to type it.
        var externalHint: String? = null
        if (mode == WirelessHotspotMode.EXTERNAL_WIFI) {
            val live = currentStationSsid()
            val stored = storedSsid()
            when {
                live == null -> externalHint =
                    "车机尚未连接 Wi-Fi：请先在车机设置中把车机连上外部 Wi-Fi，回到本页会自动填入 Wi-Fi 名称（开放网络可不填密码，加密网络必须填写密码）。"
                live != stored -> {
                    saveHotspotCredentials(live, storedPassword())
                    externalHint = "已自动填入当前 Wi-Fi：$live（开放网络可不填密码，加密网络必须填写密码；iPhone 需与车机在同一 Wi-Fi）。"
                }
                else -> externalHint = "已自动填入当前 Wi-Fi：$stored（开放网络可不填密码，加密网络必须填写密码；iPhone 需与车机在同一 Wi-Fi）。"
            }
        }
        val ssid = storedSsid()
        val password = storedPassword()
        parent.addView(button("热点名称 · $ssid", false) {
            textInput("车机热点名称", ssid, secret = false) { value ->
                hotspotError(value, password)?.let { toast(it); return@textInput }
                saveHotspotCredentials(value, password)
                render()
            }
        }, matchButton(0, 60))
        parent.addView(space(12))
        parent.addView(button("热点密码 · ${if (password.isEmpty()) "none" else "•".repeat(8)}", false) {
            textInput("车机热点密码", password, secret = true) { value ->
                hotspotError(ssid, value)?.let { toast(it); return@textInput }
                saveHotspotCredentials(ssid, value)
                render()
            }
        }, matchButton(0, 60))
        if (mode == WirelessHotspotMode.EXTERNAL_WIFI) {
            // In external Wi-Fi mode the car is a plain STA client, so the real SSID,
            // BSSID and channel of the joined network are all readable (connectionInfo).
            // The manual channel field below only applies to 车机热点 mode where Android 7
            // cannot observe the AP channel — showing it here just misleads.
            parent.addView(label(
                externalHint ?: "外部 Wi-Fi 模式：SSID 自动读取；开放网络可不填密码直接连，加密网络必须填写密码后才能连接。",
                14, MUTED,
            ).apply { setPadding(0, dp(4), 0, dp(18)) })
            return
        }
        parent.addView(space(12))
        // The iAP2 0x5703/0x4301 payloads carry this channel to the iPhone. Android 7 cannot
        // observe the hotspot channel through public APIs, so an unset channel means the payloads
        // say 0 — an invalid value the iPhone rejects. It MUST match the real hotspot channel.
        val channel = AirPlayPersistence.loadManualHotspotChannel(this)
        parent.addView(button(
            "热点信道 · ${if (channel == 0) "0（未设置，无法连接）" else channel.toString()}",
            false,
        ) {
            textInput("车机热点信道（须与车机热点实际信道一致，2.4G 常用 1/6/11）", channel.toString(), secret = false) { value ->
                val parsed = value.trim().toIntOrNull()
                if (parsed == null || parsed !in 0..196) {
                    toast("信道必须为 0 或 1-196")
                    return@textInput
                }
                AirPlayPersistence.saveManualHotspotChannel(this, parsed)
                AirPlayPersistence.saveManualHotspotBand(
                    this,
                    when {
                        parsed == 0 -> com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO
                        parsed <= 13 -> com.shilapi.xcertplay.orchestration.ManualHotspotBand.GHZ_2_4
                        else -> com.shilapi.xcertplay.orchestration.ManualHotspotBand.GHZ_5
                    },
                )
                render()
            }
        }, matchButton(0, 60))
        parent.addView(label("请先在车机设置中打开热点，并在此填入相同的名称、密码与信道（信道须与车机热点设置一致，填 0 无法连接）。iPhone 会加入该网络以使用 CarPlay。更改在下次连接时生效。", 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)

    /** The SSID the car's station is joined to right now, or null when not on a network. */
    private fun currentStationSsid(): String? {
        val wifi = runCatching { getSystemService(WifiManager::class.java) }.getOrNull() ?: return null
        val raw = runCatching { wifi.connectionInfo?.ssid }.getOrNull() ?: return null
        val ssid = raw.removePrefix("\"").removeSuffix("\"").trim()
        return ssid.takeUnless { it.isBlank() || it == "<unknown ssid>" }
    }
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.validate(ssid, password)

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        // Band and channel are owned by the dedicated channel editor so a credentials-only edit
        // never wipes the configured channel back to 0.
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        textInput("车机热点名称", storedSsid(), secret = false) { ssid ->
            textInput("车机热点密码", storedPassword(), secret = true) { password ->
                val error = hotspotError(ssid, password)
                if (error != null) toast(error) else done(ssid, password)
            }
        }
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        if (CarPlayBackgroundSession.hasSession()) connect(true)
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("保存") { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton("取消", null).show()
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, "CarPlay 尺寸", sizes.map { it.label }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label("调整 CarPlay 图标与文字大小。应用尺寸会重连 CarPlay。", 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun connect(wireless: Boolean) {
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) != com.shilapi.xcertplay.orchestration.WirelessHotspotMode.WIFI_P2P) {
            // Encrypted external Wi-Fi requires a password before connecting: without it the
            // iPhone is told the wrong security and every attempt stalls in discovery retries.
            val probe = com.shilapi.xcertplay.network.ExternalWifiSecurityProbe.probe(this)
            if (probe != null) {
                if (storedSsid() != probe.ssid) saveHotspotCredentials(probe.ssid, storedPassword())
                if (probe.open == false && storedPassword().isEmpty()) {
                    promptForWifiPassword(probe.ssid)
                    return
                }
            }
        }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun promptForWifiPassword(ssid: String) {
        val input = EditText(this).apply {
            setSingleLine()
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            hint = "此 Wi-Fi 的连接密码"
        }
        AlertDialog.Builder(this)
            .setTitle("Wi-Fi「$ssid」已加密")
            .setMessage("车机当前连接的 Wi-Fi 是加密网络，必须填写该网络的密码才能开始无线 CarPlay。\n\n密码只用于向 iPhone 描述这个网络，不会发送给其他设备。")
            .setView(input)
            .setPositiveButton("保存并连接") { _, _ ->
                val password = input.text.toString().trim()
                if (password.isEmpty()) {
                    toast("密码不能为空，无法连接加密网络")
                    return@setPositiveButton
                }
                saveHotspotCredentials(ssid, password)
                connect(true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(this).setTitle("打开蓝牙")
                .setMessage("请先开启车机蓝牙并配对你的 iPhone。")
                .setPositiveButton("打开蓝牙") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("稍后", null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle("配对 iPhone")
                .setMessage("在 iPhone 上打开“设置 → 蓝牙”，与车机配对。然后返回 DiPlay，选择“连接手机”。")
                .setPositiveButton("打开蓝牙") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("知道了", null).show(); return
        }
        AlertDialog.Builder(this).setTitle("选择你的 iPhone")
            .setItems(devices.map { device ->
                val name = device.name ?: "已配对设备"
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton("配对另一台") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton("取消") { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle("无线连接帮助")
            .setMessage("将 iPhone 与车机蓝牙配对，保持 Wi-Fi 开启，并在 iPhone 上允许 CarPlay。请关闭其他手机投屏应用。\n\n如果此前的投屏应用仍留有连接，请用下方“重置 CarPlay Wi-Fi”后重新连接。车机正常的联网 Wi-Fi 不受影响。")
            .setPositiveButton("知道了", null)
            .setNeutralButton("重置 CarPlay Wi-Fi") { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle("重置 CarPlay Wi-Fi？")
            .setMessage("此操作会断开现有的 Wi-Fi Direct 连接（包括重装后残留的连接）。请先关闭其他投屏应用。车机正常的联网 Wi-Fi 不受影响。")
            .setPositiveButton("重置并连接") { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton("取消", null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast("此车机不支持 Wi-Fi Direct。"); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast("Wi-Fi Direct 仍被占用。请关闭其他投屏应用后重试。")
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast("无法重置 Wi-Fi Direct。请关闭其他投屏应用后重试。") }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp("无线权限", "重置 CarPlay Wi-Fi 前，请允许“附近设备”（较旧的 Android 版本还需位置权限）。")
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> "初始化需要处理"
            CarPlayBackgroundSession.active -> "CarPlay 已连接"
            running -> "正在连接你的 iPhone…"
            DiPlayPreferences.phoneAddress(this) != null -> "已就绪：${DiPlayPreferences.phoneName(this)}"
            else -> "随时就绪"
        }
        if (lastRunning != running) {
            connectButton?.text = if (running) "打开 CarPlay" else "连接手机"
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
    }
    private fun reportFileName() = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "此车机无法打开保存位置。请重试保存到 Downloads。"
                else "此车机没有可用的文件选择器来保存报告。")
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = "正在保存报告…" }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("DiPlay ${version()} · 内测诊断报告")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("车机：${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("连接方式：${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("认证：本地实验性测试身份；无远程回退")
                    appendLine("已保存的视频偏好（可能与当前会话不同）：${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay 尺寸：${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("已保存的分辨率偏好（可能与当前会话不同）：${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
                    appendLine("会话：${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("车机主板：${Build.BOARD}；硬件：${Build.HARDWARE}；版本：${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- 最近一次显示协商（时间戳可区分它与当前设置）---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                if (uri != null) DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else error("需要选择保存位置")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = "保存诊断报告" }
                if (result.isSuccess) {
                    AlertDialog.Builder(this).setTitle("诊断报告已保存")
                        .setMessage(if (uri == null) "Downloads/DiPlay/$fileName" else "报告已保存到所选位置。")
                        .setPositiveButton("完成", null).show()
                } else {
                    AlertDialog.Builder(this).setTitle("无法保存报告")
                        .setMessage("请检查存储是否可用，或选择其他保存位置。")
                        .setPositiveButton("选择位置") { _, _ -> chooseReportDestination() }
                        .setNegativeButton("关闭", null).show()
                }
            }
        }, "diplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("应用设置") { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton("稍后", null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast("请在车机的“设置”应用中打开此项。") } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun section(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        val card = card(); card.addView(label(title, 22, TEXT, true).apply { setPadding(0, 0, 0, dp(16)) }); build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit) {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(Switch(this).apply { contentDescription = title; isChecked = value; minHeight = dp(56); buttonTintList = ColorStateList.valueOf(ACCENT); setOnCheckedChangeListener { _, checked -> save(checked) } })
        parent.addView(line)
    }

    /** Like [toggle] but hands the Switch back so callers can update it without a full render(). */
    private fun switchRow(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit): Switch {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val switch = Switch(this).apply {
            contentDescription = title; isChecked = value; minHeight = dp(56); buttonTintList = ColorStateList.valueOf(ACCENT)
            setOnCheckedChangeListener { _, checked -> if (!syncingDisplaySwitches) save(checked) }
        }
        line.addView(switch)
        parent.addView(line)
        return switch
    }
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) "应用并重连" else "保存") { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton("取消", null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    private fun card() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(24), dp(24), dp(24), dp(24)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 18f; setTextColor(if (primary) BG else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER), null)
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56); stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private val BG = Color.rgb(12, 17, 27)
        private val SURFACE = Color.rgb(21, 30, 44)
        private val BORDER = Color.rgb(42, 56, 75)
        private val ACCENT = Color.rgb(166, 200, 255)
        private val TEXT = Color.rgb(241, 245, 252)
        private val MUTED = Color.rgb(168, 182, 202)
        private val WARNING = Color.rgb(255, 196, 128)
    }
}
