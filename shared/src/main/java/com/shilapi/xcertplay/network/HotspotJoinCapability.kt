package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Parcelable
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Reads tethered AP capabilities under shell UID; station band support is not AP support. */
internal object HotspotJoinCapability {
    class Snapshot(private val fiveGhz: Boolean, private val channels: Set<Int>) {
        fun allows(channel: Int): Boolean = fiveGhz && channels.isNotEmpty() &&
            channels.all { it > 0 } && (channel == 0 || channel in channels)
    }
    internal interface Registration {
        fun register(callback: IInterface)
        fun unregister(callback: IInterface)
    }

    fun read(wifi: Any): Snapshot? {
        // The transaction behind this probe needs Parcel.enforceNoDataAvail (API 33); below that it
        // raises NoSuchMethodError, which the catch below cannot contain.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            @SuppressLint("BlockedPrivateApi")
            val callbackType = Class.forName("android.net.wifi.ISoftApCallback")
            @SuppressLint("BlockedPrivateApi")
            val register = wifi.javaClass.getMethod("registerSoftApCallback", callbackType)
            @SuppressLint("BlockedPrivateApi")
            val unregister = wifi.javaClass.getMethod("unregisterSoftApCallback", callbackType)
            read(object : Registration {
                override fun register(callback: IInterface) { register.invoke(wifi, callback) }
                override fun unregister(callback: IInterface) { unregister.invoke(wifi, callback) }
            })
        } catch (_: Exception) { null }
    }

    /** Android sends the current SoftApCapability immediately on registration, even with AP off. */
    internal fun read(registration: Registration, timeoutMillis: Long = 2_000): Snapshot? {
        require(timeoutMillis in 1..2_000)
        // The transaction below unpacks its reply with Parcel.enforceNoDataAvail, which is API 33.
        // On this API 25 head unit the call raises NoSuchMethodError, and an Error slips past the
        // catch(Exception) below, so the whole process would go down over an optional capability
        // probe. Nothing here can report a supported band on Android 7, so decline instead.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        var callback: IInterface? = null
        var attempted = false
        val active = AtomicBoolean(true)
        try {
            // Reading a private framework transaction code is what this probe is for; it is reached
            // through reflection precisely because the API is not public.
            @SuppressLint("BlockedPrivateApi")
            val callbackType = Class.forName("android.net.wifi.ISoftApCallback")
            @SuppressLint("BlockedPrivateApi")
            val stubType = Class.forName("android.net.wifi.ISoftApCallback\$Stub")
            @SuppressLint("BlockedPrivateApi")
            val capabilityType = Class.forName("android.net.wifi.SoftApCapability")
            @SuppressLint("BlockedPrivateApi")
            val code = stubType.getDeclaredField("TRANSACTION_onCapabilityChanged")
                .apply { isAccessible = true }.getInt(null)
            val creator = capabilityType.getField("CREATOR").get(null) as Parcelable.Creator<*>
            val feature = capabilityType.getField("SOFTAP_FEATURE_BAND_5G_SUPPORTED").getLong(null)
            val ready = CountDownLatch(1)
            val result = AtomicReference<Snapshot?>()
            val binder = object : Binder() {
                override fun onTransact(transaction: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (transaction == IBinder.INTERFACE_TRANSACTION) {
                        reply?.writeString(DESCRIPTOR)
                        return true
                    }
                    if (transaction != code || !active.get()) return true
                    try {
                        data.enforceInterface(DESCRIPTOR)
                        val capability = data.readTypedObject(creator) ?: return true
                        data.enforceNoDataAvail()
                        val supported = capabilityType.getMethod("areFeaturesSupported", Long::class.javaPrimitiveType)
                            .invoke(capability, feature) == true
                        val channels = capabilityType.getMethod("getSupportedChannelList", Int::class.javaPrimitiveType)
                            .invoke(capability, 2) as IntArray
                        result.set(Snapshot(supported, channels.toSet()))
                    } catch (_: Exception) {
                        result.set(null) // Unknown OEM parcel or capability API never approves a mutation.
                    } finally { ready.countDown() }
                    return true
                }
            }
            // A Binder bridge avoids shipping private framework classes or a copied AIDL Stub.
            callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
                when (method.name) {
                    "asBinder" -> binder
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "DiPlaySoftApCallback"
                    else -> null
                }
            } as IInterface
            binder.attachInterface(callback, DESCRIPTOR)
            attempted = true // A throwing registration may already have registered remotely.
            registration.register(callback)
            return if (ready.await(timeoutMillis, TimeUnit.MILLISECONDS)) result.get() else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) {
            return null
        } finally {
            active.set(false)
            if (attempted && callback != null) runCatching { registration.unregister(callback) }
        }
    }
    private const val DESCRIPTOR = "android.net.wifi.ISoftApCallback"
}
