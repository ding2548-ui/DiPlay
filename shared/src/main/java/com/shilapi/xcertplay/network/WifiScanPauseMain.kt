package com.shilapi.xcertplay.network

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * Shell-UID entry point that [WifiScanPause] starts through the head unit's adb with app_process.
 * Hidden framework calls are allowed there, and the shell holds the permission they check
 * (CONNECTIVITY_INTERNAL on Android 7.1-10, NETWORK_SETTINGS on 11+). Prints only the protocol line.
 */
object WifiScanPauseMain {
    internal const val PAUSE = "pause"
    internal const val RESTORE = "restore"
    private const val DEADLINE_MS = 12_000L
    private const val ROOT_UID = 0
    private const val SHELL_UID = 2000

    @JvmStatic fun main(args: Array<String>) {
        // A stuck Binder call must not leave a privileged helper running.
        Thread({ Thread.sleep(DEADLINE_MS); exitProcess(2) }, "wifi-scan-helper-deadline")
            .apply { isDaemon = true; start() }
        val result = try { execute(args) } catch (_: Throwable) { WifiScanSwitchResult.UNKNOWN }
        println("${WifiScanPause.HEADER}|${result.name}")
        exitProcess(0)
    }

    private fun execute(args: Array<String>): WifiScanSwitchResult {
        val uid = Process.myUid()
        require(uid == SHELL_UID || uid == ROOT_UID)
        require(args.size == 1 && args[0] in setOf(PAUSE, RESTORE))
        val wifi = runCatching { connect() }.getOrNull() ?: return WifiScanSwitchResult.UNSUPPORTED
        return WifiAutojoinSwitch(wifi).set(enabled = args[0] == RESTORE)
    }

    private fun connect(): Any? {
        val service = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "wifi") as? IBinder ?: return null
        return Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, service)
    }
}

/**
 * Switches the framework's automatic joining, and with it the periodic connectivity scans, through
 * the IWifiManager binder proxy. Methods are found by name and shape because BYD firmware renumbers
 * binder transactions: `enableWifiConnectivityManager(boolean)` on Android 7.1-10,
 * `allowAutojoinGlobal(boolean)` on 11-13 and `allowAutojoinGlobal(boolean, String, Bundle)` on 14+.
 * Android 13+ also reports the current setting, so one that someone else turned off is left alone
 * and every change is read back.
 */
internal class WifiAutojoinSwitch(
    private val wifi: Any,
    listenerType: Class<*>? = runCatching { Class.forName(LISTENER) }.getOrNull(),
    private val queryTimeoutMillis: Long = 5_000,
) {
    private val methods = wifi.javaClass.methods
    private val setter: Method? = methods.firstOrNull(::isAutojoinSetter)
        ?: methods.firstOrNull { it.name == "enableWifiConnectivityManager" && it.parameterTypes.contentEquals(arrayOf(BOOLEAN)) }
    private val query: Method? = listenerType?.let { type ->
        methods.firstOrNull { it.name == "queryAutojoinGlobal" && it.parameterTypes.contentEquals(arrayOf(type)) }
    }

    /** The framework method this firmware offers, or null when there is none. */
    val method: String? get() = setter?.name

    fun set(enabled: Boolean): WifiScanSwitchResult {
        val target = setter ?: return WifiScanSwitchResult.UNSUPPORTED
        try {
            // A refused query refuses the change too; any other query failure only skips the check.
            val before = try { read() } catch (denied: SecurityException) { throw denied } catch (_: Exception) { null }
            if (!enabled && before == false) return WifiScanSwitchResult.ALREADY_OFF
            call(target, *arguments(target, enabled))
        } catch (_: SecurityException) {
            // Every release checks the caller before it changes anything.
            return WifiScanSwitchResult.DENIED
        }
        // A clean void reply; Android 13+ also confirms the new state.
        val after = runCatching { read() }.getOrNull()
        return if (after == null || after == enabled) WifiScanSwitchResult.DONE else WifiScanSwitchResult.UNKNOWN
    }

    /** Android 13+: the current setting; null when this release cannot report it in time. */
    internal fun read(): Boolean? {
        val method = query ?: return null
        val type = method.parameterTypes[0]
        val answer = AtomicReference<Boolean>()
        val done = CountDownLatch(1)
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(type.name)
                answer.set(data.readInt() != 0)
                done.countDown()
                return true
            }
        }.apply { attachInterface(null, type.name) }
        val listener = Proxy.newProxyInstance(type.classLoader ?: javaClass.classLoader, arrayOf(type)) { proxy, called, args ->
            when (called.name) {
                "asBinder" -> callback
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "DiPlayAutojoinListener"
                else -> null
            }
        }
        call(method, listener)
        return if (done.await(queryTimeoutMillis, TimeUnit.MILLISECONDS)) answer.get() else null
    }

    private fun call(method: Method, vararg args: Any) {
        try {
            method.invoke(wifi, *args)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private fun arguments(method: Method, enabled: Boolean): Array<Any> =
        Array(method.parameterTypes.size) { index ->
            when (method.parameterTypes[index]) {
                BOOLEAN -> enabled
                String::class.java -> SHELL_PACKAGE
                else -> Bundle()
            }
        }

    private fun isAutojoinSetter(method: Method): Boolean {
        val types = method.parameterTypes
        return method.name == "allowAutojoinGlobal" && types.firstOrNull() == BOOLEAN &&
            types.drop(1).all { it == String::class.java || it == Bundle::class.java }
    }

    private companion object {
        const val LISTENER = "android.net.wifi.IBooleanListener"
        const val SHELL_PACKAGE = "com.android.shell"
        val BOOLEAN: Class<*> = java.lang.Boolean.TYPE
    }
}
