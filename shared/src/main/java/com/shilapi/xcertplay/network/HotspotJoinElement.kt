package com.shilapi.xcertplay.network

/** Policy for the one experimentally verified Apple Device IE. No model/brand allowlist. */
internal object HotspotJoinElement {
    data class Element(val id: Int, val idExt: Int, val payload: String)
    val apple5Ghz = Element(221, 0, "00a0400000020021")

    fun merge(api: Int, bands: IntArray, existing: List<Element>): List<Element>? {
        if (api < 33 || !bands.contentEquals(intArrayOf(2))) return null
        if (existing.any { it.id != 221 || it.idExt != 0 ||
                it.payload.length !in 6..510 || it.payload.length % 2 != 0 ||
                !it.payload.matches(Regex("[0-9a-f]+")) }) return null
        // Refuse conflicting/duplicate Device IEs; preserve unrelated Apple vendor subtypes.
        val device = existing.filter { it.payload.startsWith("00a04000") }
        if (device.size > 1 || device.any { it != apple5Ghz }) return null
        return if (apple5Ghz in existing) existing else existing + apple5Ghz
    }
}
