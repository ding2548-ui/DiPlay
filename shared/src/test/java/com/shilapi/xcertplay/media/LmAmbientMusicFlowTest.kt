package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.LmAmbientLightProtocol
import com.shilapi.xcertplay.LmAmbientMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LmAmbientMusicFlowTest {
    @Test fun silenceKeepsTheSelectedColourAtTheDefaultAngle() {
        val flow = LmAmbientMusicFlow { LmAmbientMode.AUTO }
        val idle = flow.params(AmbientMusicEnvelope.Energy(0.0, 0.0), 0L, 0xa84cc1)
        assertEquals(LmAmbientLightProtocol.SW_AUTO, idle.sw)
        assertEquals(LmAmbientLightProtocol.AUTO_ON, idle.auto)
        assertEquals(LmAmbientLightProtocol.DEG_DEFAULT, idle.deg)
        assertEquals(0xa8, idle.r); assertEquals(0x4c, idle.g); assertEquals(0xc1, idle.b)
    }

    @Test fun bandsDriveTheThreeChannelsIndependently() {
        val flow = LmAmbientMusicFlow { LmAmbientMode.ON }
        val params = flow.params(AmbientMusicEnvelope.Energy(0.4, 0.4, 0.02, 0.02), 100L, 0xffffff)
        // The low band is the loudest, so red dominates and blue trails well behind.
        assertTrue(params.r > params.g)
        assertTrue(params.g > params.b)
        assertEquals(LmAmbientLightProtocol.SW_ON, params.sw)
        assertEquals(LmAmbientLightProtocol.AUTO_OFF, params.auto)
    }

    @Test fun everyProducedParamIsAcceptedByTheProtocol() {
        val flow = LmAmbientMusicFlow { LmAmbientMode.AUTO }
        var now = 0L
        for (step in 0..40) {
            now += 200
            val bass = (step % 5) / 5.0
            val mid = (step % 3) / 3.0
            val high = (step % 7) / 7.0
            val energy = AmbientMusicEnvelope.Energy(maxOf(bass, mid, high), bass, mid, high)
            val params = flow.params(energy, now, 0x14ff14)
            assertTrue("step $step invalid: $params", LmAmbientLightProtocol.valid(params))
        }
    }

    @Test fun angleFollowsTheLowBandAndStaysInRange() {
        val flow = LmAmbientMusicFlow { LmAmbientMode.AUTO }
        val quiet = flow.params(AmbientMusicEnvelope.Energy(0.02, 0.02, 0.0, 0.0), 0L, 0x14ff14)
        val loud = flow.params(AmbientMusicEnvelope.Energy(1.0, 1.0, 1.0, 1.0), 200L, 0x14ff14)
        assertTrue(loud.deg >= quiet.deg)
        assertTrue(loud.deg in LmAmbientLightProtocol.DEG_MIN..LmAmbientLightProtocol.DEG_MAX)
    }

    @Test fun offModeNeverLeavesTheLampOff() {
        // A disabled feature must not reach the lamp as "off": restore() owns that transition, and
        // an OFF frame here would fight it on the next tick.
        val flow = LmAmbientMusicFlow { LmAmbientMode.OFF }
        val params = flow.params(AmbientMusicEnvelope.Energy(0.0, 0.0), 0L, 0x14ff14)
        assertTrue(params.sw != LmAmbientLightProtocol.SW_OFF)
    }

    @Test fun resetDropsTheSmoothedState() {
        val flow = LmAmbientMusicFlow { LmAmbientMode.AUTO }
        flow.params(AmbientMusicEnvelope.Energy(1.0, 1.0, 1.0, 1.0), 0L, 0xffffff)
        flow.reset()
        val params = flow.params(AmbientMusicEnvelope.Energy(0.0, 0.0), 0L, 0x14ff14)
        assertEquals(LmAmbientLightProtocol.DEG_DEFAULT, params.deg)
    }
}
