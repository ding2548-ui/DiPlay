package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CarPlayBonjourTest {
    @Test fun discoveryDiagnosticsKeepOutcomeWithoutPhoneIdentifiers() {
        val endpoint = CarPlayBonjourEndpoint("Private phone", "192.168.43.25", 7000, "AA:BB:CC:DD:EE:FF")
        assertEquals("control resolved family=IPv4 port=7000", CarPlayBonjourEvent.Resolved(endpoint).diagnosticSummary())
        assertEquals("control probe attempts=2 status=200 error=none",
            CarPlayBonjourEvent.Probed(endpoint, 2, "HTTP/1.1 200 Private phone", null).diagnosticSummary())
        val failed = CarPlayBonjourEvent.Probed(endpoint, 3, null, java.io.IOException("Private phone 192.168.43.25"))
            .diagnosticSummary()
        assertEquals("control probe attempts=3 status=none error=IOException", failed)
        assertFalse(failed.contains("Private phone"))
        assertEquals("control probe stage=TCP_CONNECTED attempt=2 family=IPv6",
            CarPlayBonjourEvent.ProbeProgress(CarPlayBonjourEvent.ProbeProgress.Stage.TCP_CONNECTED, 2, true).diagnosticSummary())
        assertEquals("control probe failed after=REQUEST_SENT attempt=1 failureClass=SocketTimeoutException",
            CarPlayBonjourEvent.ProbeFailed(CarPlayBonjourEvent.ProbeProgress.Stage.REQUEST_SENT, 1,
                java.net.SocketTimeoutException("Private phone secret")).diagnosticSummary())
    }
    private val config = AirPlayConfig(
        deviceName = "xcertplay",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        model = "LIVI",
    )
    private val identity = AirPlayIdentity(
        privateKey = ByteArray(32),
        publicKey = byteArrayOf(0x01, 0x23, 0xab.toByte()),
        pairingId = "pairing-1",
    )

    @Test
    fun airPlayTxtRecordsUseCurrentReceiverCapabilities() {
        assertEquals(
            linkedMapOf(
                "deviceid" to "02:00:00:00:00:02",
                "features" to "0x5653aee2,0x61",
                "flags" to "0x4",
                "model" to "LIVI",
                "srcvers" to "366.0",
                "protovers" to "1.1",
                "pi" to "pairing-1",
                "pk" to "0123ab",
            ),
            CarPlayBonjourProtocol.airPlayTxtRecords(config, identity),
        )
    }

    @Test fun discoveryFeaturesAgreeWithInfoForAudioEnabledAndDisabled() {
        for (disabled in listOf(false, true)) {
            val receiver = config.copy(disableAudioOutput = disabled)
            val parts = CarPlayBonjourProtocol.airPlayTxtRecords(receiver, identity).getValue("features")
                .split(',').map { it.removePrefix("0x").toLong(16) }
            val decoded = parts[0] or ((parts.getOrElse(1) { 0L }) shl 32)
            assertEquals(com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(receiver)["features"], decoded)
        }
        assertEquals("0xffffffff", CarPlayBonjourProtocol.featuresTxt(0xffffffffL))
    }

    @Test
    fun connectProbeRequestMatchesExactRequestLineAndHeaders() {
        assertEquals(
            "GET /ctrl-int/1/connect HTTP/1.1\r\n" +
                "Host: [fe80::1]:7000\r\n" +
                "User-Agent: AirPlay/366.0\r\n" +
                "AirPlay-Receiver-Device-ID: 020000000002\r\n" +
                "Connection: close\r\n" +
                "\r\n",
            CarPlayBonjourProtocol.connectProbeRequest(
                host = "fe80::1%wlan0",
                port = 7000,
                sourceVersion = "366.0",
                deviceId = "02:00:00:00:00:02",
            ),
        )
    }
}
