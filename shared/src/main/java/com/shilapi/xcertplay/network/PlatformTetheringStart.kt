package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.ResultReceiver
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Starts the car's saved hotspot through the platform's IConnectivityManager.startTethering, which
 * WRITE_SETTINGS access authorizes on Android 8.0 to 10. Every attempt is reported, so a firmware that
 * refuses it can be told apart from a call that never reached the system.
 *
 * The binder object is a non-public IConnectivityManager.Stub.Proxy. Its own member can be unreachable
 * (non-SDK restrictions, or class access checks), so the method is looked up on the interface first,
 * then on the proxy with forced access, and finally, on Android 9 and 10, with a hidden API exemption.
 */
internal object PlatformTetheringStart {
    class Candidate(val types: Array<Class<*>>, val arguments: (receiver: Any?, packageName: String) -> Array<Any?>)

    private val candidates = listOf(
        Candidate(arrayOf(Int::class.javaPrimitiveType!!, ResultReceiver::class.java, Boolean::class.javaPrimitiveType!!, String::class.java)) {
            receiver, packageName -> arrayOf(TETHERING_WIFI, receiver, false, packageName)
        },
        Candidate(arrayOf(Int::class.javaPrimitiveType!!, ResultReceiver::class.java, Boolean::class.javaPrimitiveType!!)) {
            receiver, _ -> arrayOf(TETHERING_WIFI, receiver, false)
        },
    )

    /**
     * The switch that relies on this call is offered on Android 8.0 to 10 only.
     *
     * Android 7 refuses the call from every app: its isTetheringSupported() asks UserManager.isAdminUser()
     * under the caller's identity, which needs MANAGE_USERS ("You either need MANAGE_USERS or CREATE_USERS
     * permission to: query user"). Android 8.0 asks under the system's identity. WifiManager.setWifiApEnabled
     * skips that check, but on Android 7 only startTethering marks the access point for tethering, so a
     * hotspot started that way would give the iPhone no address.
     *
     * Android 11 moved tethering out of IConnectivityManager into the tethering module, which an app
     * cannot reach.
     */
    fun supported(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk in Build.VERSION_CODES.O..Build.VERSION_CODES.Q

    fun start(context: Context, receiver: ResultReceiver, log: (String) -> Unit) {
        val failures = mutableListOf<String>()
        fun attempt(): Boolean {
            val service = connectivityService(context, failures) ?: return false
            val owners = listOfNotNull(
                runCatching { Class.forName("android.net.IConnectivityManager") }
                    .onFailure { failures += "IConnectivityManager: ${describe(it)}" }.getOrNull(),
                service.javaClass,
            )
            return invoke(service, owners, candidates, receiver, context.packageName, failures, log)
        }
        if (attempt()) return
        // Android 9 and 10 may hide these non-SDK members from apps that target a newer SDK.
        if (Build.VERSION.SDK_INT in 28..29 && exemptHiddenApis()) {
            log("car hotspot start: retrying with the hidden API exemption")
            if (attempt()) return
        }
        log("car hotspot start unavailable: ${failures.joinToString("; ")}")
        throw NoSuchMethodException("startTethering unavailable: ${failures.joinToString("; ")}")
    }

    /**
     * Calls the first reachable startTethering. Returns false when none could be called; a call that
     * reached the system and threw ([InvocationTargetException]) is reported and rethrown.
     */
    internal fun invoke(
        service: Any,
        owners: List<Class<*>>,
        candidates: List<Candidate>,
        receiver: Any?,
        packageName: String,
        failures: MutableList<String>,
        log: (String) -> Unit,
    ): Boolean {
        for (owner in owners) for (candidate in candidates) {
            val label = "${owner.name}.startTethering/${candidate.types.size}"
            val method: Method = try {
                owner.getMethod("startTethering", *candidate.types)
            } catch (error: ReflectiveOperationException) {
                failures += "$label: ${describe(error)}"
                continue
            } catch (error: SecurityException) {
                failures += "$label: ${describe(error)}"
                continue
            }
            val arguments = candidate.arguments(receiver, packageName)
            for (forceAccess in listOf(false, true)) {
                try {
                    if (forceAccess) method.isAccessible = true
                    method.invoke(service, *arguments)
                    log("car hotspot start: called $label${if (forceAccess) " (accessible)" else ""}")
                    return true
                } catch (error: InvocationTargetException) {
                    log("car hotspot start: $label rejected by the system: ${describe(error.targetException)}")
                    throw error
                } catch (error: IllegalAccessException) {
                    failures += "$label${if (forceAccess) " (accessible)" else ""}: ${describe(error)}"
                } catch (error: SecurityException) {
                    failures += "$label${if (forceAccess) " (accessible)" else ""}: ${describe(error)}"
                }
            }
        }
        return false
    }

    private fun connectivityService(context: Context, failures: MutableList<String>): Any? {
        // ServiceManager is only the way around a field that cannot be read; an empty field means no service.
        val manager = context.getSystemService(ConnectivityManager::class.java)
        try {
            val service = ConnectivityManager::class.java.getDeclaredField("mService")
                .apply { isAccessible = true }.get(manager)
            if (service == null) failures += "ConnectivityManager.mService: empty"
            return service
        } catch (error: ReflectiveOperationException) {
            failures += "ConnectivityManager.mService: ${describe(error)}"
        } catch (error: SecurityException) {
            failures += "ConnectivityManager.mService: ${describe(error)}"
        }
        return runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, Context.CONNECTIVITY_SERVICE) as IBinder
            Class.forName("android.net.IConnectivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.onFailure { failures += "ServiceManager connectivity: ${describe(it)}" }.getOrNull()
    }

    /**
     * Signature prefixes exempted from the hidden API checks: only the members this call uses. The
     * first prefix also covers IConnectivityManager.Stub and its Proxy. An exemption lasts for the
     * process's life and DiPlay's process also runs CarPlay, so nothing else may be exempted.
     */
    internal val HIDDEN_API_EXEMPTIONS = arrayOf(
        "Landroid/net/IConnectivityManager",
        "Landroid/net/ConnectivityManager;->mService",
        "Landroid/os/ServiceManager;",
    )

    /** Meta-reflection reaches VMRuntime.setHiddenApiExemptions on Android 9 and 10 only. */
    private fun exemptHiddenApis(): Boolean = runCatching {
        val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
        val getDeclaredMethod = Class::class.java.getDeclaredMethod(
            "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
        val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
        val getRuntime = getDeclaredMethod.invoke(vmRuntime, "getRuntime", null) as Method
        val setExemptions = getDeclaredMethod.invoke(
            vmRuntime, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)) as Method
        setExemptions.invoke(getRuntime.invoke(null), HIDDEN_API_EXEMPTIONS)
        true
    }.getOrDefault(false)

    internal fun describe(error: Throwable): String {
        val cause = (error as? InvocationTargetException)?.targetException ?: error.cause
        return error.javaClass.simpleName + (error.message?.let { ": ${it.take(160)}" } ?: "") +
            (cause?.takeIf { it !== error }?.let { " <- ${it.javaClass.simpleName}" } ?: "")
    }

    private const val TETHERING_WIFI = 0
}
