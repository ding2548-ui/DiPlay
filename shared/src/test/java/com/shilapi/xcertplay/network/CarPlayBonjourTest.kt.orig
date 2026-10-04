package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    }

    /**
     * A discovery summary must name the *service type* so the report can tell "the phone never
     * published `_carplay-ctrl._tcp`" apart from "we found it and failed to resolve it", but it
     * must never leak the instance name — that is routinely the owner's iPhone name.
     */
    @Test
    fun discoveryDiagnosticsKeepTheServiceTypeButDropTheInstanceName() {
        val summary = CarPlayBonjourEvent.Discovery(
            stage = CarPlayBonjourEvent.Discovery.Stage.ADDED,
            serviceType = "_carplay-ctrl._tcp.local.",
            serviceName = "Chris's iPhone",
        ).diagnosticSummary()
        assertEquals("control discovery stage=ADDED type=_carplay-ctrl._tcp.local. ipv4=0 ipv6=0", summary)
        assertFalse(summary.contains("Chris"))

        // Stages that carry no type at all still render, so an older report parser cannot choke.
        assertEquals(
            "control discovery stage=MDNS_STARTED ipv4=0 ipv6=0",
            CarPlayBonjourEvent.Discovery(CarPlayBonjourEvent.Discovery.Stage.MDNS_STARTED).diagnosticSummary(),
        )
    }

    /**
     * `detail` must survive into saved reports — it is where report-safe facts like the mDNS bind
     * interface go. `serviceName` must not, because it is routinely the owner's phone name.
     */
    @Test
    fun discoveryDetailReachesTheReportButTheInstanceNameDoesNot() {
        val summary = CarPlayBonjourEvent.Discovery(
            stage = CarPlayBonjourEvent.Discovery.Stage.MDNS_STARTED,
            serviceType = "_airplay._tcp.local.",
            serviceName = "Chris's iPhone",
            detail = "mdns-iface=fe80::1",
        ).diagnosticSummary()
        assertEquals(
            "control discovery stage=MDNS_STARTED type=_airplay._tcp.local. mdns-iface=fe80::1 ipv4=0 ipv6=0",
            summary,
        )
        assertFalse(summary.contains("Chris"))
    }

    /** The two publication-failure stages are the ones that make a silent wireless failure loud. */
    @Test
    fun publicationFailureStagesRender() {
        assertEquals(
            "control discovery stage=REGISTRATION_FAILED type=_airplay._tcp ipv4=0 ipv6=0",
            CarPlayBonjourEvent.Discovery(
                CarPlayBonjourEvent.Discovery.Stage.REGISTRATION_FAILED,
                serviceType = "_airplay._tcp",
            ).diagnosticSummary(),
        )
        assertEquals(
            "control discovery stage=NO_MATCHING_ADDRESS type=_carplay-ctrl._tcp.local. ipv4=0 ipv6=2",
            CarPlayBonjourEvent.Discovery(
                CarPlayBonjourEvent.Discovery.Stage.NO_MATCHING_ADDRESS,
                serviceType = "_carplay-ctrl._tcp.local.",
                ipv6Count = 2,
            ).diagnosticSummary(),
        )
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
    fun airPlayTxtRecordsMatchLivi() {
        assertEquals(
            linkedMapOf(
                "deviceid" to "02:00:00:00:00:02",
                "features" to "0x5653AEE2,0x61",
                "flags" to "0x4",
                "model" to "LIVI",
                "srcvers" to "366.0",
                "protovers" to "1.1",
                "pk" to "0123ab",
            ),
            CarPlayBonjourProtocol.airPlayTxtRecords(config, identity),
        )
    }

    /**
     * iOS cross-checks the Bonjour TXT `features` against the AirPlay `/info` `features`, so the
     * two renderings must decode back to the very same 64-bit value. This is the assertion that
     * would have caught the wireless regression: the old TXT string only carried the low word.
     */
    @Test
    fun txtFeaturesDecodeBackToTheInfoFeatures() {
        val txt = CarPlayBonjourProtocol.airPlayTxtRecords(config, identity).getValue("features")
        val words = txt.split(',').map { it.trim().removePrefix("0x").toLong(16) }
        val decoded = when (words.size) {
            1 -> words[0]
            2 -> (words[1] shl 32) or words[0]
            else -> error("features TXT must be 1 or 2 words, was: $txt")
        }
        assertEquals(AirPlayInfoPlist.CARPLAY_FEATURES, decoded)
    }

    /** Apple's `AirPlayReceiverServer.c` prints `<low32>,<high32>` and drops the high word at 0. */
    @Test
    fun featuresTxtFollowsApplesSplitEncoding() {
        // 0x615653aee2 -> low 0x5653aee2, high 0x61; kAirPlayFeature_Car is bit 32.
        assertEquals("0x5653AEE2,0x61", CarPlayBonjourProtocol.airPlayFeaturesTxt(0x615653aee2L))
        assertEquals("0x5653AEE2", CarPlayBonjourProtocol.airPlayFeaturesTxt(0x5653aee2L))
        assertEquals("0x0", CarPlayBonjourProtocol.airPlayFeaturesTxt(0L))
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

    /**
     * The bring-up verdict counts only stages that mean the phone was actually observed.
     *
     * `MDNS_STARTED` is our own publication and `REGISTRATION_FAILED` is a local failure; counting
     * either made the verdict report "connect" — i.e. discovered and then refused — on runs where
     * nothing had been discovered, which sends the next investigation to the wrong layer.
     */
    @Test
    fun onlyPhoneObservationStagesCountAsDiscovery() {
        val phoneStages = listOf(
            CarPlayBonjourEvent.Discovery.Stage.ADDED,
            CarPlayBonjourEvent.Discovery.Stage.RESOLVED,
            CarPlayBonjourEvent.Discovery.Stage.REMOVED,
            CarPlayBonjourEvent.Discovery.Stage.NO_MATCHING_ADDRESS,
            CarPlayBonjourEvent.Discovery.Stage.INVALID_PORT,
        )
        val localStages = listOf(
            CarPlayBonjourEvent.Discovery.Stage.MDNS_STARTED,
            CarPlayBonjourEvent.Discovery.Stage.REGISTRATION_FAILED,
        )
        phoneStages.forEach { stage ->
            assertTrue("$stage should count as phone discovery", stage.countsAsPhoneDiscovery)
        }
        localStages.forEach { stage ->
            assertFalse("$stage must not count as phone discovery", stage.countsAsPhoneDiscovery)
        }
        // Every stage must be classified, so adding one cannot silently default to either bucket.
        assertEquals(
            CarPlayBonjourEvent.Discovery.Stage.values().size,
            phoneStages.size + localStages.size,
        )
    }
}
