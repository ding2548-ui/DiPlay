package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2LocationReporterTest {
    private val provider = FakeProvider()
    private val sent = mutableListOf<Iap2Frame>()
    private val progress = mutableListOf<String>()
    private var nowMillis = 0L

    // What the iPhone requested in the car: GGA, RMC, PASCD and their intervals.
    private val start = Iap2Messages.buildRaw(Iap2LocationMessages.START_LOCATION_INFORMATION) {
        u16(0, 0)
        void(1)
        void(2)
        void(4)
        u32(0x8001, 1000)
    }
    private val stop = Iap2Messages.buildRaw(Iap2LocationMessages.STOP_LOCATION_INFORMATION) {}

    private fun link(source: FakeProvider = provider) =
        Iap2LocationReporter(source, { progress += it }, nanoTime = { nowMillis * 1_000_000 })

    @Test
    fun aLinkSendsOnlyBetweenItsOwnStartAndStop() {
        val link = link()

        assertTrue(link.handle(start) { sent += it })
        assertEquals(setOf(0, 1, 2, 4, 0x8001), provider.requested)
        assertTrue(provider.started)
        nowMillis += 1_000
        link.tick { sent += it }
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.messageId == Iap2LocationMessages.LOCATION_INFORMATION })

        assertTrue(link.handle(stop) { sent += it })
        assertFalse(provider.started)
        link.tick { sent += it }
        assertEquals(2, sent.size)
        assertEquals(60_000L, link.pollTimeout(60_000L))
    }

    @Test
    fun aSeparateLinkDoesNotInheritAnotherLinksRequest() {
        val bluetoothProvider = FakeProvider()
        link(bluetoothProvider).handle(start) { }
        bluetoothProvider.stop() // Bluetooth closed without 0xFFFC.

        val wifiProvider = FakeProvider()
        val wifi = link(wifiProvider)
        wifi.tick { sent += it }
        nowMillis += 1_000
        wifi.tick { sent += it }

        assertFalse(wifiProvider.started)
        assertTrue(sent.isEmpty())
        assertEquals(60_000L, wifi.pollTimeout(60_000L))
    }

    @Test
    fun anInactiveLinkSendsNothingAndDoesNotPoll() {
        val link = link()
        link.tick { sent += it }

        assertFalse(provider.started)
        assertTrue(sent.isEmpty())
        assertEquals(60_000L, link.pollTimeout(60_000L))
        assertEquals(60_000L, Iap2LocationReporter(null, {}).pollTimeout(60_000L))
    }

    @Test
    fun aBurstOfIncomingMessagesStillSendsOneFixASecond() {
        // The loop ticks after every incoming message; at session start the iPhone sends dozens at once.
        val link = link()
        link.handle(start) { sent += it }
        repeat(12) { link.tick { sent += it } }
        assertEquals(1, sent.size)

        nowMillis += 400
        repeat(5) { link.tick { sent += it } }
        assertEquals(1, sent.size)
        assertEquals(600L, link.pollTimeout(60_000L))

        nowMillis += 600
        link.tick { sent += it }
        assertEquals(2, sent.size)
        assertEquals(1_000L, link.pollTimeout(60_000L))
    }

    @Test
    fun noFixMeansNothingIsSent() {
        provider.nmea = null
        val link = link()
        link.handle(start) { sent += it }
        link.tick { sent += it }

        assertTrue(provider.started)
        assertTrue(sent.isEmpty())
    }

    private class FakeProvider : Iap2LocationProvider {
        var started = false
        var requested: Set<Int>? = null
        var nmea: String? = "\$GPGGA,123519.00,4807.0380,N,01131.0000,E,1,08,1.0,545.4,M,0.0,M,,*00\r\n"
        override fun onRequested(components: Set<Int>) { requested = components }
        override fun start(): Boolean { started = true; return true }
        override fun stop() { started = false }
        override fun latestNmea() = nmea
    }
}
