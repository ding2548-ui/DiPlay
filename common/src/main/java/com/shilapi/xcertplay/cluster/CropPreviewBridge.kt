package com.shilapi.xcertplay.cluster

import android.view.Surface

/**
 * 主屏「裁剪视频画面」编辑器要预览 CarPlay 实时画面，而 media sink 只存在于
 * `CarPlayHostActivity`（不是单例，设置页拿不到）。宿主按 `ShowmapOverlay.sink` 的同一套写法
 * 发布这一行桥：设置页注册镜像 surface，宿主把 surface 转给 sink 的镜像解码器。
 *
 * 每次 [request]/[release] 都带流类型——sink 用 `(type, key)` 作镜像 id，key 固定为
 * [KEY]，因此同一时刻编辑器只可能有一条预览解码器；对话框关闭必须 [release]，否则每条
 * 流都会多养一个解码器直到会话结束。
 */
internal object CropPreviewBridge {
    const val KEY = "crop-preview"

    /** (streamType, key, surface)；surface 为 null 表示注销。宿主未启动时为 null。 */
    var attach: ((Int, String, Surface?) -> Unit)? = null

    /** 会话是否在跑——编辑器据此提示「未连接 CarPlay，预览为黑屏」。 */
    fun available(): Boolean = attach != null

    fun request(streamType: Int, surface: Surface) {
        attach?.invoke(streamType, KEY, surface)
    }

    fun release(streamType: Int) {
        attach?.invoke(streamType, KEY, null)
    }
}
