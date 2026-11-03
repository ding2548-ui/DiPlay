package com.shilapi.xcertplay.network.fakebinder

/** Shaped like IConnectivityManager: a public interface implemented by a non-public proxy class. */
interface FakeConnectivity {
    fun startTethering(type: Int, receiver: Any?, showProvisioningUi: Boolean, callerPackage: String)
}

/** Like IConnectivityManager.Stub.Proxy, this class is not public; the JVM refuses its methods through it. */
private class FakeProxy(private val reject: Throwable? = null) : FakeConnectivity {
    val calls = mutableListOf<List<Any?>>()

    override fun startTethering(type: Int, receiver: Any?, showProvisioningUi: Boolean, callerPackage: String) {
        reject?.let { throw it }
        calls += listOf(type, receiver, showProvisioningUi, callerPackage)
    }
}

fun fakeProxy(reject: Throwable? = null): Any = FakeProxy(reject)

@Suppress("UNCHECKED_CAST")
fun fakeCalls(proxy: Any): List<List<Any?>> = (proxy as FakeProxy).calls
