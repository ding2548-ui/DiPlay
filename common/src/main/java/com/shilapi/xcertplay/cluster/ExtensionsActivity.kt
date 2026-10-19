package com.shilapi.xcertplay.cluster

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.SafeAreaEditorView

/**
 * OgleaPlay's `extension.ExtensionsActivity` — the whole 车辆与多屏 page, ported.
 *
 * Four tabs, same keys as the original: `screens` (multi-display output and calibration),
 * `content` (which stream and where), `audio` (the music card's notification access) and
 * `oem` (car model + firmware, which are exactly the keys the per-display profile is filed under,
 * plus the OEM instrument entries).
 *
 * Editing works on a **draft** profile and only "保存校准" writes it, matching the original's
 * draft/`onSaveInstanceState`/save split: leaving the page without saving discards the change, and
 * a saved change is pushed into the running secondary screen without relaunching it.
 *
 * Deliberately not ported from the original: the network receiver ("独立 Android 设备 · 网络飞屏")
 * and OgleaPlay's own theme/settings framework. Everything on these four tabs is backed by the
 * ported model, store, controller and renderer.
 */
class ExtensionsActivity : Activity() {
    private lateinit var column: LinearLayout
    private var tab = TAB_SCREENS
    private var draft = ScreenProfile()
    private var targetDisplayId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tab = savedInstanceState?.getString(KEY_TAB) ?: intent.getStringExtra(KEY_TAB) ?: TAB_SCREENS
        draft = savedInstanceState?.getString(KEY_DRAFT)?.let { ScreenProfile.fromJson(it) }
            ?: loadDraft()
        targetDisplayId = savedInstanceState?.getInt(KEY_DISPLAY)
            ?: intent.getIntExtra(KEY_DISPLAY, -1)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(LinearLayout(this@ExtensionsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(24))
            })
        }
        column = root.getChildAt(0) as LinearLayout
        setContentView(root)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab)
        outState.putString(KEY_DRAFT, draft.toJson().toString())
        outState.putInt(KEY_DISPLAY, targetDisplayId)
    }

    // --- data ------------------------------------------------------------------------------

    private fun loadDraft(): ScreenProfile {
        val display = targetDisplay() ?: return ScreenProfile()
        return ScreenProfileStore.load(this, display)
    }

    private fun targetDisplay() =
        ScreenProfileStore.secondaryDisplays(this).firstOrNull { it.displayId == targetDisplayId }
            ?: ScreenProfileStore.preferredDisplay(this)

    private fun targetIdentity(): String? = targetDisplay()?.let { ScreenProfileStore.identity(this, it) }

    private fun saveDraft() {
        val display = targetDisplay() ?: run {
            toast("没有可用副屏"); return
        }
        targetDisplayId = display.displayId
        ScreenProfileStore.save(this, display, draft)
        ScreenProfileStore.rememberOutput(this, display)
        SecondaryScreenController.push(draft, force = true)
        toast("校准已保存并推送")
        render()
    }

    // --- scaffolding -----------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun title(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 20f
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun note(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(0xFF9AA3AD.toInt())
        textSize = 14f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(text: String, selected: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setBackgroundColor(if (selected) 0xFF2E4A6B.toInt() else 0xFF1C2530.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun render() {
        column.removeAllViews()
        column.addView(title("车辆与多屏"))
        column.addView(row().apply {
            val tabs = listOf(TAB_SCREENS to "多屏显示", TAB_CONTENT to "内容",
                TAB_AUDIO to "车机音乐", TAB_OEM to "车辆与原厂")
            tabs.forEachIndexed { index, (key, label) ->
                addView(button(label, tab == key) {
                    tab = key
                    render()
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                })
            }
        })
        when (tab) {
            TAB_CONTENT -> renderContent()
            TAB_AUDIO -> renderAudio()
            TAB_OEM -> renderOem()
            else -> renderScreens()
        }
    }

    // --- tab: screens ---------------------------------------------------------------------

    private fun renderScreens() {
        column.addView(title("多屏显示与区域校正"))
        column.addView(note("本机副屏使用 Android 显示设备；独立 Android 设备使用网络接收端。" +
            "原厂仪表请查看「车辆与原厂」。"))
        val mode = ScreenProfileStore.outputMode(this)
        column.addView(button("输出方式：独立副屏页面（优先）", mode == OutputMode.ACTIVITY) {
            ScreenProfileStore.setOutputMode(this, OutputMode.ACTIVITY); render()
        })
        column.addView(button("输出方式：兼容窗口", mode == OutputMode.WINDOW) {
            ScreenProfileStore.setOutputMode(this, OutputMode.WINDOW); render()
        })
        column.addView(button("关闭副屏输出") {
            ScreenProfileStore.setEnabled(this, false)
            SecondaryScreenController.stop(this)
            render()
        })
        column.addView(note("在主屏预览校准编辑器"))
        val displays = ScreenProfileStore.secondaryDisplays(this)
        if (displays.isEmpty()) {
            column.addView(note("系统尚未开放可用副屏。可连接外接显示器后刷新。"))
        } else {
            displays.forEach { display ->
                val selected = display.displayId == (targetDisplay() ?: return@forEach).displayId
                column.addView(button(
                    (if (selected) "● " else "") + ScreenProfileStore.describe(this, display),
                    selected,
                ) {
                    targetDisplayId = display.displayId
                    ScreenProfileStore.rememberOutput(this, display)
                    draft = ScreenProfileStore.load(this, display)
                    render()
                })
            }
        }
        column.addView(button("刷新屏幕列表") { render() })
        column.addView(button("在主屏预览校准编辑器") { openMainPreviewEditor() })
        column.addView(button("复制副屏诊断") { copyDiagnostics() })
    }

    private fun copyDiagnostics() {
        val display = targetDisplay()
        val text = buildString {
            appendLine("outputMode=${ScreenProfileStore.outputMode(this@ExtensionsActivity)}")
            appendLine("displays=" + ScreenProfileStore.secondaryDisplays(this@ExtensionsActivity)
                .joinToString(" | ") { ScreenProfileStore.describe(this@ExtensionsActivity, it) })
            appendLine("target=" + (display?.let { ScreenProfileStore.describe(this@ExtensionsActivity, it) } ?: "none"))
            appendLine("identity=" + (targetIdentity() ?: "none"))
            appendLine("profile=$draft")
            appendLine("request=${SecondaryScreenController.request}")
        }
        (getSystemService(ClipboardManager::class.java))?.setPrimaryClip(ClipData.newPlainText("screen", text))
        toast("副屏诊断已复制")
    }

    /**
     * The original's main-screen calibration editor: a preview of the target panel hosting the same
     * rect editor, so the driver can adjust without the panel in front of them.
     */
    private fun openMainPreviewEditor() {
        val display = targetDisplay() ?: run { toast("没有可用副屏"); return }
        val size = runCatching { display.mode }.getOrNull()
        val aspect = if (size != null && size.physicalWidth > 0 && size.physicalHeight > 0)
            size.physicalWidth.toDouble() / size.physicalHeight.toDouble() else 1920.0 / 720.0
        val metrics = resources.displayMetrics
        val previewWidth = (metrics.widthPixels * 0.82f).toInt()
        val previewHeight = (previewWidth / aspect).toInt().coerceAtLeast(1)
        val refW = 1000
        val refH = (1000 / aspect).toInt().coerceAtLeast(1)
        var pending = draft.activeVideo
        val readout = TextView(this).apply {
            setTextColor(0xFF9AA3AD.toInt()); textSize = 14f
            text = "位置 ${(pending.x * 100).toInt()}% ${(pending.y * 100).toInt()}% · 尺寸 " +
                "${(pending.width * 100).toInt()}% × ${(pending.height * 100).toInt()}%"
        }
        val editor = SafeAreaEditorView(this).apply {
            interactive = true
            dimOutside = false
            setRect(toRect(pending, refW, refH), refW, refH)
            onRectChanged = { rect ->
                fromRect(rect, refW, refH)?.let {
                    pending = it
                    readout.text = "位置 ${(it.x * 100).toInt()}% ${(it.y * 100).toInt()}% · 尺寸 " +
                        "${(it.width * 100).toInt()}% × ${(it.height * 100).toInt()}%"
                    draft = draft.withVideo(it)
                }
            }
        }
        val preview = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview.addView(editor)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(preview, LinearLayout.LayoutParams(previewWidth, previewHeight).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(readout, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        appDialogBuilder()
            .setTitle("目标屏网格预览 · ${ScreenProfileStore.describe(this, display)}")
            .setView(column)
            .setPositiveButton("保存校准") { _, _ -> saveDraft() }
            .setNegativeButton("取消校准", null)
            .show()
    }

    // --- tab: content ---------------------------------------------------------------------

    private fun renderContent() {
        column.addView(title("显示内容与区域"))
        column.addView(note("独立导航：保存后下次连接自动恢复。主屏五指向左横滑，依次切换跟随主屏、" +
            "独立导航、独立音乐；向右横滑反向切换。导航应用需支持 CarPlay 仪表面。"))
        val sources = ContentSource.entries
        sources.forEach { source ->
            column.addView(button(
                when (source) {
                    ContentSource.MAIN -> "跟随主屏"
                    ContentSource.NAVIGATION -> "锁定导航"
                    ContentSource.MUSIC -> "独立音乐"
                }, draft.source == source,
            ) {
                draft = draft.copy(source = source)
                render()
            })
        }
        column.addView(note("显示内容："))
        regionEditorButton("重新框选视频区域", "视频显示区域", ContentSource.MAIN)
        regionEditorButton("重新框选导航区域", "导航显示区域", ContentSource.NAVIGATION)
        regionEditorButton("重新框选音乐区域", "音乐显示区域", ContentSource.MUSIC)
        column.addView(button("裁剪视频画面") { cropEditor() })
        column.addView(note("在完整视频上框选要保留的内容；框外画面会被裁掉。返回显示区域可预览裁剪结果。"))
        column.addView(button("视频：完整适配", draft.mode == FitMode.FIT) {
            draft = draft.copy(mode = FitMode.FIT); render()
        })
        column.addView(button("视频：填满裁切", draft.mode == FitMode.CROP) {
            draft = draft.copy(mode = FitMode.CROP); render()
        })
        column.addView(button("恢复完整画面") {
            draft = draft.copy(
                video = ScreenRegion.FULL, navigationVideo = ScreenRegion.FULL,
                musicVideo = ScreenRegion.MUSIC_DEFAULT, crop = ScreenRegion.FULL,
                navigationCrop = ScreenRegion.FULL)
            render()
        })
        column.addView(button("恢复默认（待保存）") { draft = ScreenProfile(source = draft.source); render() })
        column.addView(button("保存校准") { saveDraft() })
    }

    private fun regionEditorButton(buttonLabel: String, editorTitle: String, source: ContentSource) {
        column.addView(button(buttonLabel) { regionEditor(editorTitle, source) })
    }

    private fun aspectOf(source: ContentSource): Double = when (source) {
        ContentSource.MAIN -> resources.displayMetrics.widthPixels.toDouble() /
            resources.displayMetrics.heightPixels
        ContentSource.NAVIGATION -> 1920.0 / 720.0
        ContentSource.MUSIC -> 16.0 / 9.0
    }.coerceIn(0.5, 4.0)

    private fun regionEditor(label: String, source: ContentSource) {
        val aspect = aspectOf(source)
        val metrics = resources.displayMetrics
        val previewWidth = (metrics.widthPixels * 0.78f).toInt()
        val previewHeight = (previewWidth / aspect).toInt().coerceAtLeast(1)
        val refW = 1000
        val refH = (1000 / aspect).toInt().coerceAtLeast(1)
        var pending = when (source) {
            ContentSource.MAIN -> draft.video
            ContentSource.NAVIGATION -> draft.navigationVideo
            ContentSource.MUSIC -> draft.musicVideo
        }
        val readout = TextView(this).apply {
            setTextColor(0xFF9AA3AD.toInt()); textSize = 14f
            text = readout(pending)
        }
        val editor = SafeAreaEditorView(this).apply {
            interactive = true
            dimOutside = false
            setRect(toRect(pending, refW, refH), refW, refH)
            onRectChanged = { rect ->
                fromRect(rect, refW, refH)?.let {
                    pending = it
                    readout.text = readout(it)
                }
            }
        }
        val preview = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview.addView(editor)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(preview, LinearLayout.LayoutParams(previewWidth, previewHeight).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(readout, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        appDialogBuilder()
            .setTitle("$label · 框选")
            .setView(body)
            .setPositiveButton("保存（待保存校准）") { _, _ ->
                draft = when (source) {
                    ContentSource.MAIN -> draft.copy(video = pending)
                    ContentSource.NAVIGATION -> draft.copy(navigationVideo = pending)
                    ContentSource.MUSIC -> draft.copy(musicVideo = pending)
                }
                render()
            }
            .setNeutralButton("恢复完整") { _, _ ->
                draft = when (source) {
                    ContentSource.MAIN -> draft.copy(video = ScreenRegion.FULL)
                    ContentSource.NAVIGATION -> draft.copy(navigationVideo = ScreenRegion.FULL)
                    ContentSource.MUSIC -> draft.copy(musicVideo = ScreenRegion.MUSIC_DEFAULT)
                }
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 「裁剪视频画面」：edits the active source's crop rect, exactly as the original did. */
    private fun cropEditor() {
        val source = draft.source
        val aspect = aspectOf(source)
        val metrics = resources.displayMetrics
        val previewWidth = (metrics.widthPixels * 0.78f).toInt()
        val previewHeight = (previewWidth / aspect).toInt().coerceAtLeast(1)
        val refW = 1000
        val refH = (1000 / aspect).toInt().coerceAtLeast(1)
        var pending = draft.activeCrop
        val readout = TextView(this).apply {
            setTextColor(0xFF9AA3AD.toInt()); textSize = 14f; text = readout(pending)
        }
        val editor = SafeAreaEditorView(this).apply {
            interactive = true
            dimOutside = false
            setRect(toRect(pending, refW, refH), refW, refH)
            onRectChanged = { rect ->
                fromRect(rect, refW, refH)?.let {
                    pending = it
                    readout.text = readout(it)
                }
            }
        }
        val preview = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview.addView(editor)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(preview, LinearLayout.LayoutParams(previewWidth, previewHeight).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(readout, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        appDialogBuilder()
            .setTitle("裁剪视频画面")
            .setView(body)
            .setPositiveButton("保存（待保存校准）") { _, _ ->
                draft = draft.withCrop(pending)
                render()
            }
            .setNeutralButton("恢复完整画面") { _, _ ->
                draft = when (source) {
                    ContentSource.MAIN -> draft.copy(crop = ScreenRegion.FULL)
                    ContentSource.NAVIGATION -> draft.copy(navigationCrop = ScreenRegion.FULL)
                    ContentSource.MUSIC -> draft.copy(musicVideo = ScreenRegion.FULL)
                }
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun readout(region: ScreenRegion): String =
        "位置 ${(region.x * 100).toInt()}% ${(region.y * 100).toInt()}% · 尺寸 " +
            "${(region.width * 100).toInt()}% × ${(region.height * 100).toInt()}%"

    private fun toRect(region: ScreenRegion, refW: Int, refH: Int) = SafeAreaRect(
        (region.x * refW).toInt(), (region.y * refH).toInt(),
        ((region.x + region.width) * refW).toInt(), ((region.y + region.height) * refH).toInt())

    private fun fromRect(rect: SafeAreaRect, refW: Int, refH: Int): ScreenRegion? {
        if (refW <= 0 || refH <= 0) return null
        return runCatching {
            ScreenRegion(
                rect.left / refW.toFloat(), rect.top / refH.toFloat(),
                (rect.right - rect.left) / refW.toFloat(),
                (rect.bottom - rect.top) / refH.toFloat(),
            )
        }.getOrNull()
    }

    // --- tab: audio -----------------------------------------------------------------------

    private fun renderAudio() {
        column.addView(title("车机音乐"))
        column.addView(note("独立音乐卡片读取车机收到的媒体通知（歌名/歌手/封面/进度）。" +
            "需要授予「通知读取权限」。"))
        column.addView(button("车机音乐：通知读取权限") {
            runCatching {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }.onFailure { toast("无法打开通知读取设置") }
        })
    }

    // --- tab: oem -------------------------------------------------------------------------

    private fun renderOem() {
        column.addView(title("车型与方控"))
        column.addView(note("先测试屏幕按键，再按方向盘按键确认来源；车型资料用于保存对应的屏幕校准配置。"))
        val extensions = getSharedPreferences("vehicle_extensions", Context.MODE_PRIVATE)
        val model = EditText(this).apply {
            hint = "车型，如零跑 C10"
            setText(extensions.getString("model", ""))
            setSingleLine(true)
        }
        val firmware = EditText(this).apply {
            hint = "固件版本"
            setText(extensions.getString("firmware", ""))
            setSingleLine(true)
        }
        column.addView(model, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        column.addView(firmware, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        column.addView(button("保存车型与固件") {
            extensions.edit()
                .putString("model", model.text.toString().trim())
                .putString("firmware", firmware.text.toString().trim())
                .apply()
            toast("已保存；校准配置将按车型与固件分别存放")
            render()
        })
        column.addView(title("原厂仪表 / HUD"))
        column.addView(note("Android 副屏输出与原厂接口是两条独立路径。"))
        column.addView(note("导航接口：" +
            (if (com.shilapi.xcertplay.hud.BydOutputSettings.navigationAvailable(this)) "可用"
             else "未检测到") +
            "；侧栏车辆数据：" +
            (if (com.shilapi.xcertplay.hud.BydOutputSettings.leapmotorHeadUnit()) "CAN 总线（零跑）"
             else "ADB（BYD）") +
            "；原厂音乐接口：见「车辆设置 → 原厂接口」。"))
        column.addView(note("车速与档位 · 只读诊断：" +
            (com.shilapi.xcertplay.LeapmotorVehicleSignals.snapshot()?.let {
                "档位 ${it.gear?.letter ?: '-'} · 车速 " +
                    (it.speedKmh?.let { v -> "%.0f".format(v) } ?: "-") + " km/h"
            } ?: "暂无 CAN 帧（需车辆通电并已建立过连接）")))
    }

    override fun onBackPressed() {
        // The original asked before discarding an unsaved draft; keep that behaviour.
        appDialogBuilder()
            .setTitle("放弃未保存的校准？")
            .setMessage("离开将丢弃「待保存」的修改。")
            .setPositiveButton("离开") { _, _ -> super.onBackPressed() }
            .setNegativeButton("继续编辑", null)
            .show()
    }

    private fun appDialogBuilder(): android.app.AlertDialog.Builder =
        android.app.AlertDialog.Builder(this)

    companion object {
        const val KEY_TAB = "tab"
        const val KEY_DRAFT = "draft"
        const val KEY_DISPLAY = "display"
        const val TAB_SCREENS = "screens"
        const val TAB_CONTENT = "content"
        const val TAB_AUDIO = "audio"
        const val TAB_OEM = "oem"

        fun start(context: Context, tab: String? = null) {
            context.startActivity(Intent(context, ExtensionsActivity::class.java)
                .putExtra(KEY_TAB, tab))
        }
    }
}
