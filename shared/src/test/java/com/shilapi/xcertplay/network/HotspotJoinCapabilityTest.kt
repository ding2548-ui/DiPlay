package com.shilapi.xcertplay.network

import android.os.IInterface
import android.os.Parcel
import android.os.Parcelable
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HotspotJoinCapabilityTest {
    private class Registration(private val capability: Any? = null, private val denied: Boolean = false) :
        HotspotJoinCapability.Registration {
        var registered: IInterface? = null
        var unregistered: IInterface? = null
        override fun register(callback: IInterface) {
            registered = callback
            if (denied) throw SecurityException("synthetic denial")
            if (capability != null) emit(callback, capability)
        }
        override fun unregister(callback: IInterface) { unregistered = callback }
    }
    private fun capability(features: Long, channels: IntArray): Any {
        val type = Class.forName("android.net.wifi.SoftApCapability")
        return type.getConstructor(Long::class.javaPrimitiveType).newInstance(features).also {
            type.getMethod("setSupportedChannelList", Int::class.javaPrimitiveType, IntArray::class.java)
                .invoke(it, 2, channels)
        }
    }
    @Test fun readsAuthoritativeCapabilityThroughTheBinderCallbackAndAlwaysUnregisters() {
        val type = Class.forName("android.net.wifi.SoftApCapability")
        val feature = type.getField("SOFTAP_FEATURE_BAND_5G_SUPPORTED").getLong(null)
        val registration = Registration(capability(feature, intArrayOf(36, 44)))
        val snapshot = HotspotJoinCapability.read(registration, 10)!!
        assertTrue(snapshot.allows(0)); assertTrue(snapshot.allows(44)); assertFalse(snapshot.allows(48))
        assertSame(registration.registered, registration.unregistered)
    }
    @Test fun reflectiveWifiServiceRegistrationUsesTheFirmwareCallbackInterface() {
        val feature = Class.forName("android.net.wifi.SoftApCapability")
            .getField("SOFTAP_FEATURE_BAND_5G_SUPPORTED").getLong(null)
        val registration = Registration(capability(feature, intArrayOf(44)))
        val wifiType = Class.forName("android.net.wifi.IWifiManager")
        val wifi = Proxy.newProxyInstance(wifiType.classLoader, arrayOf(wifiType)) { _, method, args ->
            when (method.name) {
                "registerSoftApCallback" -> registration.register(args!![0] as IInterface)
                "unregisterSoftApCallback" -> registration.unregister(args!![0] as IInterface)
                else -> throw AssertionError("Unexpected service method")
            }
            null
        }
        assertTrue(HotspotJoinCapability.read(wifi)!!.allows(44))
        assertSame(registration.registered, registration.unregistered)
    }
    @Test fun unsupportedAndEmptyChannelReportsCannotApproveARepair() {
        val feature = Class.forName("android.net.wifi.SoftApCapability")
            .getField("SOFTAP_FEATURE_BAND_5G_SUPPORTED").getLong(null)
        for (capability in listOf(capability(0, intArrayOf(36)), capability(feature, intArrayOf()))) {
            val registration = Registration(capability)
            val snapshot = HotspotJoinCapability.read(registration, 10)!!
            assertFalse(snapshot.allows(0)); assertFalse(snapshot.allows(36))
            assertSame(registration.registered, registration.unregistered)
        }
    }
    @Test fun missingDeniedOrInterruptedCallbacksFailClosedAndReleaseRegistration() {
        for (registration in listOf(Registration(), Registration(denied = true))) {
            assertNull(HotspotJoinCapability.read(registration, 1))
            assertSame(registration.registered, registration.unregistered)
        }
        val interrupted = Registration()
        Thread.currentThread().interrupt()
        try {
            assertNull(HotspotJoinCapability.read(interrupted, 10))
            assertTrue(Thread.currentThread().isInterrupted)
            assertSame(interrupted.registered, interrupted.unregistered)
        } finally { Thread.interrupted() }
    }
    @Test fun nullOrMalformedCapabilityParcelsFailClosedAndReleaseRegistration() {
        val feature = Class.forName("android.net.wifi.SoftApCapability")
            .getField("SOFTAP_FEATURE_BAND_5G_SUPPORTED").getLong(null)
        for (malformed in listOf(false, true)) {
            var registered: IInterface? = null
            var unregistered: IInterface? = null
            val registration = object : HotspotJoinCapability.Registration {
                override fun register(callback: IInterface) {
                    registered = callback
                    emit(callback, if (malformed) capability(feature, intArrayOf(44)) else null, malformed)
                }
                override fun unregister(callback: IInterface) { unregistered = callback }
            }
            assertNull(HotspotJoinCapability.read(registration, 10))
            assertSame(registered, unregistered)
        }
    }
    companion object {
        private fun emit(callback: IInterface, capability: Any?, malformed: Boolean = false) {
            val stub = Class.forName("android.net.wifi.ISoftApCallback\$Stub")
            val code = stub.getDeclaredField("TRANSACTION_onCapabilityChanged").apply { isAccessible = true }.getInt(null)
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken("android.net.wifi.ISoftApCallback")
                data.writeTypedObject(capability as Parcelable?, 0)
                if (malformed) data.writeInt(123)
                assertTrue(callback.asBinder().transact(code, data, null, android.os.IBinder.FLAG_ONEWAY))
            } finally { data.recycle() }
        }
    }
}
