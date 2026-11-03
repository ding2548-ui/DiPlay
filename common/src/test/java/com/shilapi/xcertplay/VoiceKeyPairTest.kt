package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceKeyPairTest {
    @Test fun theGenericKeyAfterACarPlayKeyIsTheSamePress() {
        val pair = VoiceKeyPair()
        assertTrue(pair.allowCarPlayKey(10_000))
        assertFalse(pair.allowGenericKey(10_460))
    }

    @Test fun theCarPlayKeyAfterAGenericKeyIsTheSamePress() {
        val pair = VoiceKeyPair()
        assertTrue(pair.allowGenericKey(10_000))
        assertFalse(pair.allowCarPlayKey(10_300))
    }

    @Test fun aLaterPressOpensSiriAgain() {
        val pair = VoiceKeyPair()
        assertTrue(pair.allowCarPlayKey(10_000))
        assertFalse(pair.allowGenericKey(10_460))
        assertTrue(pair.allowCarPlayKey(10_000 + VoiceKeyPair.WINDOW_MILLIS))
        assertFalse(pair.allowGenericKey(10_000 + VoiceKeyPair.WINDOW_MILLIS + 460))
    }

    @Test fun pressesOfOneFamilyAreNotSuppressedByEachOther() {
        val pair = VoiceKeyPair()
        assertTrue(pair.allowGenericKey(10_000))
        assertTrue(pair.allowGenericKey(10_100))
        assertTrue(pair.allowCarPlayKey(20_000))
        assertTrue(pair.allowCarPlayKey(20_100))
    }
}
