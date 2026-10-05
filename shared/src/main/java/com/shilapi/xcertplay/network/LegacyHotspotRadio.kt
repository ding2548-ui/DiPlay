package com.shilapi.xcertplay.network

/** Read-only, ordinary-UID fallback for drivers with Wireless Extensions (not an AP setter). */
internal object LegacyHotspotRadio {
    data class Reading(val frequencyMHz: Int?, val error: String?)

    fun read(interfaceName: String, band: String?): Reading {
        if (!interfaceName.matches(Regex("(?:ap|wlan|swlan|softap)[0-9]+")))
            return Reading(null, "invalid AP interface")
        val raw = try { LegacyHotspotNative.query(interfaceName) }
        catch (_: LinkageError) { return Reading(null, "driver reader unavailable") }
        if (raw.size != 3) return Reading(null, "invalid driver response")
        if (raw[0] != 0) return Reading(null, "driver frequency query errno=${raw[0]}")
        val frequency = decodeFrequency(raw[1], raw[2], band)
        return Reading(frequency, if (frequency == null) "unrecognized driver frequency" else null)
    }

    // iw_freq uses mantissa * 10^exponent Hz; some drivers return a channel number instead.
    // A channel alone is ambiguous, so require the reservation's explicit band in that case.
    fun decodeFrequency(mantissa: Int, exponent: Int, band: String?): Int? {
        if (mantissa <= 0 || exponent !in 0..9) return null
        if (exponent == 0 && mantissa < 1000) return when {
            band == "2.4 GHz" && mantissa in 1..13 -> 2407 + mantissa * 5
            band == "2.4 GHz" && mantissa == 14 -> 2484
            band == "5 GHz" && mantissa in 32..177 -> 5000 + mantissa * 5
            else -> null
        }
        var hz = mantissa.toLong()
        repeat(exponent) { hz *= 10 }
        if (hz % 1_000_000 != 0L) return null
        val mhz = hz / 1_000_000
        return mhz.toInt().takeIf {
            mhz in 2412..2472 && (mhz - 2412) % 5 == 0L || mhz == 2484L ||
                mhz in 5160..5885 && (mhz - 5000) % 5 == 0L
        }
    }

    class Settled {
        private var frequency: Int? = null
        private var since = 0L
        fun observe(value: Int?, nowMs: Long): Int? {
            if (value != frequency) { frequency = value; since = nowMs }
            return value?.takeIf { nowMs - since >= 2_000 }
        }
    }
}

private object LegacyHotspotNative {
    init { System.loadLibrary("local_hotspot_radio") }
    external fun query(interfaceName: String): IntArray
}
