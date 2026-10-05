package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class HotspotJoinElementTest {
    @Test fun addsOnlyTheFiveGhzElementAndPreservesExistingElements() {
        val existing = listOf(HotspotJoinElement.Element(221, 0, "0050f2020101"))
        val result = HotspotJoinElement.merge(33, intArrayOf(2), existing)
        assertEquals(existing + HotspotJoinElement.apple5Ghz, result)
        assertEquals(result, HotspotJoinElement.merge(33, intArrayOf(2), result!!))
    }

    @Test fun rejectsOtherBandsOldApisAndAmbiguousAppleElements() {
        for (bands in listOf(intArrayOf(), intArrayOf(1), intArrayOf(3), intArrayOf(1, 2), intArrayOf(4))) {
            assertNull(HotspotJoinElement.merge(33, bands, emptyList()))
        }
        assertNull(HotspotJoinElement.merge(32, intArrayOf(2), emptyList()))
        val conflicting = HotspotJoinElement.Element(221, 0, "00a0400000010021")
        assertNull(HotspotJoinElement.merge(33, intArrayOf(2), listOf(conflicting)))
        assertNull(HotspotJoinElement.merge(33, intArrayOf(2), List(2) { HotspotJoinElement.apple5Ghz }))
    }
}
