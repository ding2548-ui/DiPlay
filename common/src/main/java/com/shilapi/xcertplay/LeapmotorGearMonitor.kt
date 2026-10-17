package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * The Leapmotor gear signal (P/R/N/D) and the video-in-car gate.
 *
 * Kept as a thin facade over [LeapmotorVehicleSignals], which owns the CAN broadcast and decodes
 * the whole frame. This type only exposes the gear-specific answers the rest of the app asks for,
 * so existing call sites did not change when the monitor grew into the full signal bus.
 *
 * Consumers:
 *  - 倒车（R）→ 暂停 CarPlay 音乐；退出 R → 恢复（仅当是本次倒车自己暂停的）。
 *  - 视频门控（iOS 27 video in car）：**零跑没有 P 挡读数，改为 N 挡允许视频**。
 *    收到任何档位数据之前一律返回 null（禁止视频），避免把"无数据"误当可用。
 */
internal object LeapmotorGearMonitor {
    /**
     * Both the logcat line and the app's diagnostic report receive these. Forwarded to
     * [LeapmotorVehicleSignals.onDiagnostic], which owns the `@Volatile` field; this facade has no
     * backing field, so it must not carry `@Volatile` itself (field-only annotation).
     */
    var onDiagnostic: ((String) -> Unit)?
        get() = LeapmotorVehicleSignals.onDiagnostic
        set(value) {
            LeapmotorVehicleSignals.onDiagnostic = value
        }

    fun attach(context: Context, next: CarPlayController) =
        LeapmotorVehicleSignals.attach(context, next)

    fun detach(expected: CarPlayController?) = LeapmotorVehicleSignals.detach(expected)

    /** Whether video may play now (N 挡); null when no gear data ever arrived. */
    fun videoAllowed(): Boolean? {
        val raw = LeapmotorVehicleSignals.snapshot()?.gearRaw ?: return null
        return raw == LeapmotorVehicleSignals.GEAR_N
    }

    /** Current gear as R/N/D/P (P covers "not R, N or D"), or null without data. */
    fun gearName(): String? = LeapmotorVehicleSignals.snapshot()?.gear?.letter?.toString()
}
