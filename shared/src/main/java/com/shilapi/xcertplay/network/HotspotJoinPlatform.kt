package com.shilapi.xcertplay.network

import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/** Reflection is contained in a shell-UID app_process helper; ordinary app permission is insufficient. */
internal class HotspotJoinPlatform(private val wifi: Any, private val api: Int,
    private val firmwareKey: String,
    private val capability: () -> HotspotJoinCapability.Snapshot? = { HotspotJoinCapability.read(wifi) }) : HotspotJoinTransaction.Port<Any> {
    private val configType = Class.forName("android.net.wifi.SoftApConfiguration")
    private val builderType = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
    private val elementType = Class.forName("android.net.wifi.ScanResult\$InformationElement")
    private val getConfig = wifi.javaClass.getMethod("getSoftApConfiguration")
    private val setConfig = wifi.javaClass.getMethod("setSoftApConfiguration", configType, String::class.java)
    private val getState = wifi.javaClass.getMethod("getWifiApEnabledState")

    override fun firmware() = firmwareKey
    override fun read(): Any = getConfig.invoke(wifi) ?: throw IllegalStateException()
    override fun hotspotOff(): Boolean? = when (getState.invoke(wifi) as? Int) {
        11 -> true
        13 -> false
        else -> null // Disabled only: do not treat starting/stopping/failed as safe to write.
    }
    override fun write(config: Any): Boolean = setConfig.invoke(wifi, config, "com.android.shell") == true

    override fun repaired(config: Any): Any? {
        if (api < 33) return null
        // WifiApConfigStore forces this flag true on every non-null setter call. A false
        // original therefore cannot be restored verbatim, even when every other field is
        // preserved. Refuse it before preparing a target; Apply repeats this same gate.
        if (configType.getMethod("isUserConfiguration").invoke(config) != true) return null
        val bands = configType.getMethod("getBands").invoke(config) as IntArray
        val existing = elements(config)
        val policy = existing.map(::description)
        val merged = HotspotJoinElement.merge(api, bands, policy) ?: return null
        val channel = configType.getMethod("getChannel").invoke(config) as Int
        if (capability()?.allows(channel) != true) return null
        if (merged == policy) return config
        val payload = byteArrayOf(0, 0xa0.toByte(), 0x40, 0, 0, 2, 0, 0x21)
        val added = elementType.getConstructor(Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, ByteArray::class.java).newInstance(221, 0, payload)
        val next = withElements(config, existing + added)
        // Verify copy semantics on this firmware before permitting a transaction.
        if (withElements(next, existing) != config || elements(next).map(::description) != merged) return null
        return next
    }

    private fun elements(config: Any): List<Any> {
        val list = configType.getMethod("getVendorElements").invoke(config) as List<*>
        return list.map { it ?: throw IllegalStateException() }
    }
    private fun description(element: Any): HotspotJoinElement.Element {
        val bytes = elementType.getField("bytes").get(element) as ByteArray
        return HotspotJoinElement.Element(elementType.getField("id").getInt(element),
            elementType.getField("idExt").getInt(element), bytes.joinToString("") { "%02x".format(it.toInt() and 255) })
    }
    private fun withElements(config: Any, elements: List<Any>): Any {
        val builder = builderType.getConstructor(configType).newInstance(config)
        builderType.getMethod("setVendorElements", List::class.java).invoke(builder, elements)
        return builderType.getMethod("build").invoke(builder) ?: throw IllegalStateException()
    }
    val codec = object : HotspotJoinJournal.Codec<Any> {
        override fun encode(config: Any): ByteArray {
            val parcel = Parcel.obtain()
            return try { (config as Parcelable).writeToParcel(parcel, 0); parcel.marshall() }
            finally { parcel.recycle() }
        }
        override fun decode(bytes: ByteArray): Any {
            val parcel = Parcel.obtain()
            return try {
                parcel.unmarshall(bytes, 0, bytes.size); parcel.setDataPosition(0)
                val creator = configType.getField("CREATOR").get(null) as Parcelable.Creator<*>
                val config = creator.createFromParcel(parcel) ?: throw IllegalStateException()
                if (parcel.dataAvail() != 0) throw IllegalStateException()
                config
            } finally { parcel.recycle() }
        }
    }
    companion object {
        fun connect(): HotspotJoinPlatform {
            require(Build.VERSION.SDK_INT >= 33)
            val service = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "wifi") as IBinder
            val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, service) ?: throw IllegalStateException()
            // Local journal never migrates between framework/vendor builds.
            val firmware = HotspotJoinFirmware.current() ?: throw UnsupportedOperationException()
            return HotspotJoinPlatform(wifi, Build.VERSION.SDK_INT, firmware)
        }
    }
}
