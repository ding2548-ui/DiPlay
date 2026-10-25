package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.LmAmbientLightProtocol
import com.shilapi.xcertplay.LmAmbientMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LmAmbientMusicFlowTest {
    @Test fun silenceKeepsTheSelectedColourAtTheConfiguredBrightness() {
        val flow = LmAmbientMusicFlow { LmAmbientMode.AUTO }
        val idle = flow.params(AmbientMusicEnvelope.Energy(0.0, 0.0), 0L, 0xa84cc1)
        assertEquals(LmAmbientLightProtocol.SW_AUTO, idle.sw)
        assertEquals(LmAmbientLightProtocol.AUTO_ON, idle.auto)
        assertEquals(LmAmbientLightProtocol.DEG_DEFAULT, idle.deg)
        assertEquals(0xa8, idle.r); assertEquals(0x4c, idle.g); assertEquals(0xc1, idle.b)
    }

    @Test fun multiColourShowsOnlyTheUserColour() {
        // 颜色只能来自用户选择：MULTI 显示调用方传入的色板当前色，绝不发频谱 RGB
        // （原厂 musicColorMode==2 的 |f0|*2 行为看起来就是随机变色，已刻意不复刻）。
        val flow = LmAmbientMusicFlow { LmAmbientMode.ON }
        flow.params(AmbientMusicEnvelope.Energy(1.0, 1.0, 1.0, 1.0), 0L, 0xb8224c,
            AmbientFlowMode.MULTI, 7)
        val params = flow.params(AmbientMusicEnvelope.Energy(0.5, 0.5, 0.25, 0.01), 200L, 0xb8224c,
            AmbientFlowMode.MULTI, 7)
        assertEquals(0xb8, params.r); assertEquals(0x22, params.g); assertEquals(0x4c, params.b)
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

    @Test fun multiColourHoldsTheUserBrightness() {
        // The OEM multi-colour branch (`VehicleControlService$6`, `musicColorMode == 2`) keeps
        // `GetDeg()` and only the colour moves. Deriving `lampDeg` from the spectrum there made the
        // strip dim on quiet passages, which the OEM never does.
        val flow = LmAmbientMusicFlow { LmAmbientMode.ON }
        val quiet = flow.params(AmbientMusicEnvelope.Energy(0.02, 0.02, 0.0, 0.0), 0L, 0x14ff14,
            AmbientFlowMode.MULTI, 7)
        val loud = flow.params(AmbientMusicEnvelope.Energy(1.0, 1.0, 1.0, 1.0), 200L, 0x14ff14,
            AmbientFlowMode.MULTI, 7)
        assertEquals(7, quiet.deg)
        assertEquals(7, loud.deg)
    }

    @Test fun singleColourSweepsTheBrightnessAndKeepsTheUserColour() {
        // The OEM single-colour branch (`musicColorMode == 1`) drives `lampDeg` from the low band and
        // leaves the RGB alone. The colour must be the user's pick, not the brightness level.
        val flow = LmAmbientMusicFlow { LmAmbientMode.ON }
        val quiet = flow.params(AmbientMusicEnvelope.Energy(0.02, 0.02, 0.0, 0.0), 0L, 0xa84cc1,
            AmbientFlowMode.SINGLE, 2)
        val loud = flow.params(AmbientMusicEnvelope.Energy(1.0, 1.0, 1.0, 1.0), 200L, 0xa84cc1,
            AmbientFlowMode.SINGLE, 2)
        assertTrue("quiet=${quiet.deg} loud=${loud.deg}", loud.deg >= quiet.deg)
        assertTrue(quiet.deg in 2..LmAmbientLightProtocol.DEG_MAX)
        assertTrue(loud.deg in LmAmbientLightProtocol.DEG_MIN..LmAmbientLightProtocol.DEG_MAX)
        assertEquals(0xa8, loud.r); assertEquals(0x4c, loud.g); assertEquals(0xc1, loud.b)
        assertEquals(0xa8, quiet.r); assertEquals(0x4c, quiet.g); assertEquals(0xc1, quiet.b)
    }

    @Test fun theSwitchStateIsTheSameOnSilentAndAudibleFrames() {
        // The sink de-duplicates on the whole parameter set: a silent frame that resolved to a
        // different switch state would re-send the broadcast 20 times a second and toggle the car's
        // lamp switch with it.
        val flow = LmAmbientMusicFlow { LmAmbientMode.ON }
        val silent = flow.params(AmbientMusicEnvelope.Energy(0.0, 0.0), 0L, 0x14ff14)
        val audible = flow.params(AmbientMusicEnvelope.Energy(0.6, 0.6, 0.3, 0.1), 200L, 0x14ff14)
        assertEquals(silent.sw, audible.sw)
        assertEquals(silent.auto, audible.auto)
        assertEquals(LmAmbientLightProtocol.SW_ON, audible.sw)
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
