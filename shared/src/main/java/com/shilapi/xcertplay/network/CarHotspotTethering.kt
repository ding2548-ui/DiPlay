package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Uses the car's saved hotspot configuration. This never stops or reconfigures the hotspot. */
object CarHotspotTethering {
    private val startupLock = ReentrantLock()
    enum class Result(val diagnostic: String) {
        READY("Car hotspot is on"),
        PERMISSION_REQUIRED("Hotspot control permission is missing"),
        UNSUPPORTED("This firmware does not support automatic hotspot startup"),
        FAILED("The car could not start its hotspot"),
        TIMED_OUT("Timed out waiting for the car hotspot"),
        CANCELLED("Hotspot startup was cancelled"),
    }

    fun permitted(context: Context): Boolean = Settings.System.canWrite(context)

    /** Blocking; serialize startup and connection requests, checking cancellation after acquiring the lock. */
    fun enable(
        context: Context,
        isCancelled: () -> Boolean,
        timeoutMillis: Long = WirelessStartupPolicy.HOTSPOT_READY_MILLIS,
        log: (String) -> Unit,
    ): Result {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        // FreeAuto-style primary path: on Android 7.x the classic SoftAP toggle only needs
        // CHANGE_WIFI_STATE, reuses the car's saved hotspot configuration and is confirmed by
        // polling the AP state — no ADB, no binder reflection. The startTethering binder
        // overloads arrived with Android 8 and remain the path on newer firmwares.
        val start: (ResultReceiver) -> Unit =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                { receiver -> startTetheringByBinder(context, receiver) }
            } else {
                { _ -> startLegacySoftAp(context, log) }
            }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
            CarHotspotStatus.isEnabled(context) == true
        ) {
            // FreeAuto applyNameRule parity: a hotspot that is already running but does not
            // carry this install's name is bounced once under our name; one already named ours
            // is left untouched.
            renameRunningHotspotIfForeign(context, log)
        }
        return enableUntil(
            deadline,
            isCancelled,
            { permitted(context) },
            { CarHotspotStatus.isEnabled(context) },
            startFallback = null,
            start = start,
        ).also { log("car hotspot auto-enable: ${it.diagnostic}") }
    }

    private fun startTetheringByBinder(context: Context, receiver: ResultReceiver) {
        // There is no public API for the car hotspot's startTethering overload on this platform,
        // so the binder field has to be reached reflectively. The call is already wrapped by the
        // caller's runCatching, and a missing field surfaces as NoSuchMethodException.
        @SuppressLint("SoonBlockedPrivateApi")
        val service = ConnectivityManager::class.java.getDeclaredField("mService")
            .apply { isAccessible = true }
            .get(context.getSystemService(ConnectivityManager::class.java))
            ?: throw NoSuchMethodException("Connectivity service unavailable")
        // Android 8+ carries the caller package in the binder signature; Android 8.0 does not.
        try {
            service.javaClass.getMethod(
                "startTethering", Int::class.javaPrimitiveType, ResultReceiver::class.java,
                Boolean::class.javaPrimitiveType, String::class.java,
            ).invoke(service, 0, receiver, false, context.packageName)
        } catch (error: NoSuchMethodException) {
            service.javaClass.getMethod(
                "startTethering", Int::class.javaPrimitiveType, ResultReceiver::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(service, 0, receiver, false)
        }
    }

    private fun startLegacySoftAp(context: Context, log: (String) -> Unit) {
        val wifi = context.getSystemService(WifiManager::class.java)
            ?: throw NoSuchMethodException("Wi-Fi manager unavailable")
        val saved = readSavedHotspotConfiguration(wifi)
        // FreeAuto sequence: switch on with this install's name first — the configuration whose
        // SSID differs from the stored one pushes the framework through its full AP start path;
        // if the system refuses it, retry with the saved configuration as it is. A hidden
        // configuration or an already-ours name passes null, exactly like FreeAuto.
        val named = DiPlayHotspotName.namedConfiguration(context, saved, log)
        if (!setAp(wifi, named, true, log) && named != null) {
            log("hotspot name rule: the system refused the DiPlay- name; switching on with the saved configuration as it is")
            setAp(wifi, saved, true, log)
        }
    }

    /** FreeAuto applyNameRule parity: a running hotspot that does not carry this install's name
     *  is bounced once under our name; one already named ours is left untouched. */
    private fun renameRunningHotspotIfForeign(context: Context, log: (String) -> Unit) {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return
        val saved = readSavedHotspotConfiguration(wifi) ?: return
        val target = DiPlayHotspotName.nameFor(DiPlayHotspotName.suffix(context, saved.SSID, log))
        if (!DiPlayHotspotName.needsRename(saved.SSID, target)) return
        log("hotspot name rule: restarting once with the DiPlay- name " +
            "(system password/security/band unchanged; connected devices drop briefly)")
        setAp(wifi, saved, false, log)
        try {
            Thread.sleep(2_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        setAp(wifi, DiPlayHotspotName.withNameOnly(saved, target), true, log)
    }

    private fun readSavedHotspotConfiguration(wifi: WifiManager): WifiConfiguration? =
        runCatching {
            WifiManager::class.java.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration
        }.getOrNull()

    private fun setAp(
        wifi: WifiManager,
        config: WifiConfiguration?,
        enabled: Boolean,
        log: (String) -> Unit,
    ): Boolean {
        val result = WifiManager::class.java.getMethod(
            "setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType,
        ).invoke(wifi, config, enabled)
        return (result as? Boolean) ?: false
    }

    /** An ADB observation supplements hidden platform status, but never overrides a current off state. */
    internal fun stateWithAdbObservation(
        platformState: () -> Boolean?,
        observation: AtomicReference<Boolean?>,
    ): () -> Boolean? = { platformState() ?: observation.get() }

    internal fun enable(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        start: (ResultReceiver) -> Unit,
    ): Result = enable(timeoutMillis, isCancelled, canWrite, isEnabled, null, start)

    internal fun enable(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        startFallback: (() -> Boolean)?,
        start: (ResultReceiver) -> Unit,
    ): Result = enableUntil(System.nanoTime() + timeoutMillis * 1_000_000L,
        isCancelled, canWrite, isEnabled, startFallback, start)

    private fun enableUntil(
        deadline: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        startFallback: (() -> Boolean)?,
        start: (ResultReceiver) -> Unit,
    ): Result {
        fun stopped(): Result? = when {
            isCancelled() || Thread.currentThread().isInterrupted -> Result.CANCELLED
            System.nanoTime() >= deadline -> Result.TIMED_OUT
            else -> null
        }
        while (true) {
            stopped()?.let { return it }
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return Result.TIMED_OUT
            try {
                if (startupLock.tryLock(minOf(250L, remaining), TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.CANCELLED
            }
        }
        try {
            stopped()?.let { return it }
            val initialState = isEnabled()
            stopped()?.let { return it }
            if (initialState == true) return Result.READY
            val permission = canWrite()
            stopped()?.let { return it }
            if (!permission) return Result.PERMISSION_REQUIRED
            fun fallback(): Boolean {
                stopped()?.let { return false }
                return try { startFallback?.invoke() == true } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    false
                }
            }
            val unknown = initialState == null
            if (unknown) {
                if (!fallback()) return stopped() ?: Result.UNSUPPORTED
            }
            val response = AtomicInteger(-1)
            try {
                stopped()?.let { return it }
                if (!unknown) {
                    start(object : ResultReceiver(null) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                            response.set(resultCode)
                        }
                    })
                }
            } catch (error: Exception) {
                val adbRecovered = ((error is ReflectiveOperationException && error !is InvocationTargetException) ||
                    error is SecurityException ||
                    (error is InvocationTargetException && error.targetException is SecurityException)) &&
                    fallback()
                if (!adbRecovered) {
                    stopped()?.let { return it }
                    return when {
                        error is InvocationTargetException -> if (error.targetException is SecurityException) Result.PERMISSION_REQUIRED else Result.FAILED
                        error is ReflectiveOperationException -> Result.UNSUPPORTED
                        error is SecurityException -> Result.PERMISSION_REQUIRED
                        else -> Result.FAILED
                    }
                }
            }
            while (true) {
                stopped()?.let { return it }
                val enabled = isEnabled()
                stopped()?.let { return it }
                if (enabled == true) return Result.READY
                if (response.get() > 0) return Result.FAILED
                val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMillis <= 0) return Result.TIMED_OUT
                try {
                    Thread.sleep(minOf(250L, remainingMillis))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return Result.CANCELLED
                }
            }
        } finally {
            startupLock.unlock()
        }
    }
}
