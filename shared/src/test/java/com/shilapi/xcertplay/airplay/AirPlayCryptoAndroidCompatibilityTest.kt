package com.shilapi.xcertplay.airplay

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30], manifest = Config.NONE)
class AirPlayCryptoAndroidCompatibilityTest {
    @Test fun olderSupportedAndroidDoesNotStageIntoDirectBuffers() {
        roundTrip()
        assertEquals(0, retainedCapacity("input"))
        assertEquals(0, retainedCapacity("output"))
    }

    @Test
    @Config(sdk = [31])
    fun android12UsesTheDirectBufferPath() {
        roundTrip()
        assertTrue(retainedCapacity("input") >= 3)
        assertTrue(retainedCapacity("output") >= 19)
    }

    private fun roundTrip() {
        val key = ByteArray(32)
        val nonce = AirPlayCrypto.nonce64(10)
        val plain = byteArrayOf(1, 2, 3)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, ByteArray(0))
        // Use a separate nonce after the empty message so each encryption is unique.
        val dataNonce = AirPlayCrypto.nonce64(11)
        val data = AirPlayCrypto.chachaSeal(key, dataNonce, plain)
        assertArrayEquals(ByteArray(0), AirPlayCrypto.chachaOpen(key, nonce, sealed))
        assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, dataNonce, data))
    }

    private fun retainedCapacity(field: String): Int {
        val threadLocal = AirPlayCrypto::class.java.getDeclaredField("platformState")
            .apply { isAccessible = true }.get(null) as ThreadLocal<*>
        val state = threadLocal.get()!!
        return (state.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(state) as ByteBuffer).capacity()
    }
}
