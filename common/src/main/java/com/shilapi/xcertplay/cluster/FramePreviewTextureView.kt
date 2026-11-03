package com.shilapi.xcertplay.cluster

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

/**
 * 主屏编辑器的实时预览。上游 OgleaPlay 的编辑器（g61）内嵌真渲染器（yb3）铺底出画——
 * 那个编辑器运行在副屏上，SurfaceView 挖洞正常；beta 的编辑器在主屏对话框里，SurfaceView
 * 在 Dialog 窗口上的挖洞不可靠，画面会掉到窗口背景后面（用户看到的「预览出现在组件背后」）。
 * 这里改用 [TextureView] 吃同一条 [CropPreviewBridge] 镜像流：普通 view 层级，对话框里 z 序
 * 完全正常，用 [setTransform] 复现副屏渲染器 applyCrop 的同一几何——
 *
 * 视口（TextureView 自身的布局矩形）= 显示框；内容 = 裁剪框在拉伸 buffer 上的区域，按
 * FIT/CROP 缩放并居中铺满视口。TextureView 的 transform 运行在 view 空间（identity =
 * buffer 拉伸铺满 view），因此内容坐标直接用归一化值乘视口尺寸；超出视口的画面由
 * TextureView 自身边界裁掉，框外自然留黑（输出屏的黑边），不会把裁掉的部份画出来。
 */
class FramePreviewTextureView(context: Context) : TextureView(context) {

    private var streamType: Int? = null
    private var surface: Surface? = null

    private val listener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            val s = Surface(st)
            surface = s
            streamType?.let { CropPreviewBridge.request(it, s) }
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            streamType?.let { CropPreviewBridge.release(it) }
            surface?.release()
            surface = null
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }

    init {
        surfaceTextureListener = listener
    }

    /** 绑定镜像流；type=null（独立音乐没有流）时仅留黑底。换流时先注销旧流。 */
    fun bind(type: Int?) {
        if (streamType == type) return
        streamType?.let { CropPreviewBridge.release(it) }
        streamType = type
        surface?.let { s -> type?.let { CropPreviewBridge.request(it, s) } }
    }

    /**
     * 在目标视口 (vw × vh) 内呈现裁剪结果，与 [SecondaryScreenRenderer.applyCrop] 同一公式：
     * 内容宽 = crop 宽 × vw（buffer 拉伸铺满视口后的内容尺寸），FIT 取 min 缩放、CROP 取
     * max，居中项用内容尺寸（而不是整幅尺寸——整幅居中正是此前框外黑屏的根因）。
     * [vw]/[vh] 由调用方显式传入（拖动中布局尚未重排时也用目标值，避免滞后一帧）。
     */
    fun applyCropGeometry(crop: ScreenRegion, fitMode: FitMode, vw: Float, vh: Float) {
        if (vw <= 0f || vh <= 0f) return
        val cw = crop.width * vw
        val ch = crop.height * vh
        if (cw <= 0f || ch <= 0f) return
        val s = when (fitMode) {
            FitMode.FIT -> minOf(vw / cw, vh / ch)
            FitMode.CROP -> maxOf(vw / cw, vh / ch)
        }
        val tx = (vw - cw * s) / 2f - crop.x * vw * s
        val ty = (vh - ch * s) / 2f - crop.y * vh * s
        val m = Matrix()
        m.setScale(s, s)
        m.postTranslate(tx, ty)
        setTransform(m)
    }
}
