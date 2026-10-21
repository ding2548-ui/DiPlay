package com.shilapi.xcertplay

/**
 * 裁剪诊断日志桥（临时插桩）：Log.i 不进诊断报告（报告只收 sessionLog 通道），
 * 这里同时写 logcat 与宿主 sessionLog，供诊断报告导出取证。
 */
object CropDiagLog {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun log(message: String) {
        android.util.Log.i("DiPlay-Crop", message)
        sink?.invoke(message)
    }
}
