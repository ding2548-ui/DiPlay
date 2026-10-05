package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Live dashboard selections must not claim delivery before their commands reach the event channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterContentTest {
    private fun session(initialUrl: String, onTeardown: (AirPlaySession) -> Unit = {}): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            cluster = AirPlayDisplayConfig(widthPixels = 1600, heightPixels = 600, initialUrl = initialUrl)),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {
            override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int = 1
            override fun onTeardown(session: AirPlaySession, type: Int) { onTeardown(session) }
        },
    )

    private fun setup(session: AirPlaySession) {
        session.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
            .invoke(session, listOf(mapOf("type" to 111L)))
    }

    private fun teardown(session: AirPlaySession) {
        val request = RtspMessage.Request("TEARDOWN", "*", "RTSP/1.0", emptyMap(),
            BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 111L)))))
        session.javaClass.getDeclaredMethod("handleTeardown", RtspMessage.Request::class.java).apply { isAccessible = true }
            .invoke(session, request)
    }

    @Test fun aFailedShowUiMustNotClaimThatATurnCardHasBecomeAMap() {
        val initial = CarPlayClusterDisplay.Content.TURN_CARD.url
        val session = session(initial)
        try {
            setup(session)
            assertFalse(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true)) // No event connection.
            assertEquals("No command was delivered, so map zoom must not become eligible", initial, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aRecreatedStreamMustNotReportThePreviousStreamsUrlAsAlreadyShown() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = false))
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            teardown(session)
            assertEquals(0, session.clusterStream)
            setup(session)
            assertEquals(2, session.clusterStream)
            assertEquals("A new cluster stream starts at its configured initialURL", CarPlayClusterDisplay.MAP_URL,
                session.clusterUrl())
        } finally { session.close() }
    }

    private class EventSocket(private val onWrite: (() -> Unit)? = null) : Socket() {
        val bytes = ByteArrayOutputStream()
        var writes = 0
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { bytes.write(value) }
            override fun write(data: ByteArray, offset: Int, length: Int) {
                bytes.write(data, offset, length)
                writes++
                if (writes == 1) onWrite?.invoke()
            }
        }
    }

    private fun eventReady(session: AirPlaySession, event: EventSocket = EventSocket()): EventSocket {
        for ((name, value) in listOf("eventSocket" to event, "eventCipher" to ControlCipher(ByteArray(32), ByteArray(32)))) {
            session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        return event
    }

    private fun readyAfterSetup(session: AirPlaySession) {
        session.javaClass.getDeclaredMethod("notifySetupResponseSent").apply { isAccessible = true }.invoke(session)
    }

    private fun commands(event: EventSocket): List<Map<String, Any?>> {
        val plaintext = ControlCipher(ByteArray(32), ByteArray(32)).decrypt(event.bytes.toByteArray()).data
        return RtspMessage.parseMessages(plaintext).messages.map { BplistCodec.decode(it.body) as Map<String, Any?> }
    }

    @Test fun aSuccessfulSwitchPublishesTheDeliveredUrlAndInvalidatesTheOldRoute() {
        val session = session(CarPlayClusterDisplay.Content.TURN_CARD.url)
        try {
            setup(session)
            val before = session.clusterContentRoute()!!.third
            val event = eventReady(session)
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            assertNotSame(before, session.clusterContentRoute()!!.third)
            val commands = commands(event)
            assertEquals(listOf("showUI", "forceKeyFrame"), commands.map { it["type"] })
            assertEquals(CarPlayClusterDisplay.MAP_URL, (commands.first()["params"] as Map<*, *>)["url"])
        } finally { session.close() }
    }

    @Test fun aPausedSelectionStaysPendingUntilResume() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            val event = eventReady(session)
            assertTrue(session.setClusterUiShown(false))
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = false))
            readyAfterSetup(session) // A ready-channel notification must not undo a pause.
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            assertEquals(1, event.writes)
            assertTrue(session.setClusterUiShown(true))
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, session.clusterUrl())
            assertEquals(listOf("stopUI", "showUI", "forceKeyFrame"), commands(event).map { it["type"] })
        } finally { session.close() }
    }

    @Test fun anUnavailableEventChannelKeepsTheOldUrlUntilRetryActuallyDelivers() {
        val session = session(CarPlayClusterDisplay.Content.TURN_CARD.url)
        try {
            setup(session)
            assertFalse(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, session.clusterUrl())
            eventReady(session)
            readyAfterSetup(session)
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aRecreatedStreamReappliesTheSelectionAfterSetupWithoutClaimingItEarly() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            eventReady(session)
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = true))
            teardown(session)
            assertNull(session.clusterContentRoute())
            setup(session)
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            readyAfterSetup(session)
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aStreamReplacementDuringDeliveryCannotPublishTheOldResultForTheNewStream() {
        val session = session(CarPlayClusterDisplay.Content.TURN_CARD.url)
        try {
            setup(session)
            eventReady(session, EventSocket { setup(session) })
            assertFalse(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertEquals(2, session.clusterStream)
            assertNull(session.clusterUrl()) // In-flight showUI may have reached either generation.
            readyAfterSetup(session)
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aClosedSessionHasNoContentRouteAndCannotAcceptSelections() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        setup(session)
        val event = eventReady(session)
        session.close()
        assertEquals(0, session.clusterStream)
        assertNull(session.clusterContentRoute())
        assertFalse(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = false))
        assertEquals(0, event.writes)
    }

    @Test fun aStopUiThatOutlastsStreamReplacementInvalidatesTheNewRoute() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            val event = eventReady(session, EventSocket { setup(session) })
            assertFalse(session.setClusterUiShown(false))
            assertEquals(2, session.clusterStream)
            assertNull(session.clusterUrl())
            readyAfterSetup(session)
            assertEquals(1, event.writes) // An uncertain stop cannot be silently undone by a ready event.
            assertTrue(session.setClusterUiShown(true))
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun teardownInvalidatesTheRouteBeforeAnUnsuccessfulMediaCallback() {
        var checked = false
        val session = session(CarPlayClusterDisplay.MAP_URL) {
            assertEquals(0, it.clusterStream)
            assertNull(it.clusterContentRoute())
            checked = true
            throw java.io.IOException("media teardown failed")
        }
        try {
            setup(session)
            val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { teardown(session) }
            assertTrue(failure.cause is java.io.IOException)
            assertTrue(checked)
            assertNull(session.clusterContentRoute())
        } finally { session.close() }
    }
}
