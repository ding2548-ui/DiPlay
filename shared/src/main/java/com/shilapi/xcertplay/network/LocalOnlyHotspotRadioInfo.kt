package com.shilapi.xcertplay.network

import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Android 13 exposes LOHS radio events to the Nearby devices permission, but the method is
 * SystemApi. Use it only where available; never substitute the station's channel or use shell.
 * The observer follows the reservation and publishes no SSID/password/client information.
 */
internal class LocalOnlyHotspotRadioInfo(private val wifi: WifiManager) : Closeable {
    data class Radio(val bssid: String?, val frequencyMHz: Int)

    private data class Snapshot(val radios: List<Radio>, val changedNs: Long)
    @Volatile private var snapshot = Snapshot(emptyList(), System.nanoTime())
    private var callback: Any? = null
    private var unregister: Method? = null
    @Volatile private var watchedBssid: String? = null
    @Volatile private var diagnostic: ((String) -> Unit)? = null
    private var lastFrequency: Int? = null
    private var lastClientCount: Int? = null
    var unavailableReason: String? = null
        private set

    fun start() {
        if (Build.VERSION.SDK_INT < 33) {
            unavailableReason = "live hotspot channel requires Android 13 or later"
            return
        }
        try {
            val type = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            val remove = WifiManager::class.java.getMethod("unregisterLocalOnlyHotspotSoftApCallback", type)
            val add = WifiManager::class.java.getMethod("registerLocalOnlyHotspotSoftApCallback", Executor::class.java, type)
            val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.firstOrNull()
                    "toString" -> "DiPlay local hotspot radio observer"
                    "onInfoChanged" -> {
                        val infos = (args?.firstOrNull() as? List<*>) ?: listOfNotNull(args?.firstOrNull())
                        val radios = infos.mapNotNull { info ->
                            runCatching {
                                info ?: return@runCatching null
                                val frequency = (info.javaClass.getMethod("getFrequency").invoke(info) as Number).toInt()
                                if (wifiFrequencyMhzToChannel(frequency) == null) return@runCatching null
                                val mac = info.javaClass.getMethod("getBssid").invoke(info)?.toString()
                                Radio(mac, frequency)
                            }.getOrNull()
                        }
                        if (snapshot.radios != radios) snapshot = Snapshot(radios, System.nanoTime())
                        reportRadio()
                        null
                    }
                    "onConnectedClientsChanged" -> {
                        val clients = args?.lastOrNull() as? List<*>
                        if (clients != null) reportClients(clients.size)
                        null
                    }
                    else -> null
                }
            }
            callback = proxy
            unregister = remove
            val main = Handler(Looper.getMainLooper())
            add.invoke(wifi, Executor { main.post(it) }, proxy)
        } catch (failure: Exception) {
            unavailableReason = "live hotspot channel unavailable (${failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName})"
            close()
        }
    }

    fun forBssid(bssid: String?): Radio? = matchingRadio(snapshot.radios, bssid)

    fun settledForBssid(bssid: String?): Radio? {
        val current = snapshot
        return settledRadio(current.radios, bssid, (System.nanoTime() - current.changedNs) / 1_000_000)
    }

    fun watch(bssid: String?, onDiagnostic: (String) -> Unit) {
        watchedBssid = bssid
        diagnostic = onDiagnostic
        reportRadio()
    }

    @Synchronized private fun reportRadio() {
        val report = diagnostic ?: return
        val current = forBssid(watchedBssid) ?: return
        if (lastFrequency == current.frequencyMHz) return
        lastFrequency = current.frequencyMHz
        report("LocalOnlyHotspot live radio frequency=${current.frequencyMHz}MHz channel=${wifiFrequencyMhzToChannel(current.frequencyMHz)}")
    }

    @Synchronized private fun reportClients(count: Int) {
        if (lastClientCount == count) return
        lastClientCount = count
        diagnostic?.invoke("LocalOnlyHotspot associated clients=$count")
    }

    companion object {
        // DiLink briefly reports the requested channel, then the driver moves it ~1s later.
        // Do not send those transient channel details to the phone during its Wi-Fi handshake.
        fun settledRadio(radios: List<Radio>, bssid: String?, unchangedMillis: Long): Radio? =
            if (unchangedMillis >= 2_000) matchingRadio(radios, bssid) else null

        fun matchingRadio(radios: List<Radio>, bssid: String?): Radio? = radios.singleOrNull {
            bssid != null && it.bssid.equals(bssid, ignoreCase = true) &&
                wifiFrequencyMhzToChannel(it.frequencyMHz) != null
        }
    }

    override fun close() {
        diagnostic = null
        val owned = callback
        callback = null
        if (owned != null) runCatching { unregister?.invoke(wifi, owned) }
        unregister = null
    }
}
