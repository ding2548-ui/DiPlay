package com.shilapi.xcertplay.media

import kotlin.math.abs

/** Color source is independent from the musical onset/BPM mode. */
enum class AmbientColorSource { SELECTED, ALBUM }

object AmbientAlbumPalette {
    /** Ignores transparent pixels, near-black/white and grey covers without a usable hue. */
    fun dominantRgb(pixels: IntArray): Int? {
        val weight = DoubleArray(24)
        val red = DoubleArray(24); val green = DoubleArray(24); val blue = DoubleArray(24)
        for (argb in pixels) {
            if ((argb ushr 24) < 128) continue
            val r = (argb ushr 16) and 255; val g = (argb ushr 8) and 255; val b = argb and 255
            val high = maxOf(r, g, b); val low = minOf(r, g, b)
            val saturation = if (high == 0) 0.0 else (high - low).toDouble() / high
            if (high < 32 || saturation < 0.20 || low > 230) continue
            val bin = (hue(r, g, b) / 15).toInt().coerceIn(0, 23)
            val w = saturation * high / 255.0
            weight[bin] += w; red[bin] += r * w; green[bin] += g * w; blue[bin] += b * w
        }
        val index = weight.indices.maxByOrNull { weight[it] } ?: return null
        val total = weight[index]
        if (total == 0.0) return null
        return (0xff shl 24) or ((red[index] / total).toInt() shl 16) or
            ((green[index] / total).toInt() shl 8) or (blue[index] / total).toInt()
    }

    /** Keeps the existing colored-hue selection first, then accepts a majority white cover. */
    fun albumColor(pixels: IntArray): Int? {
        dominantRgb(pixels)?.let { return bydColor(it) }
        if (pixels.isEmpty()) return null
        var whiteCount = 0
        var red = 0L; var green = 0L; var blue = 0L
        for (argb in pixels) {
            if ((argb ushr 24) < 128) continue
            val r = (argb ushr 16) and 255; val g = (argb ushr 8) and 255; val b = argb and 255
            // Bright, low-chroma pixels only; transparent padding still counts toward cover area.
            if (minOf(r, g, b) < 200 || maxOf(r, g, b) - minOf(r, g, b) > 38) continue
            whiteCount++; red += r; green += g; blue += b
        }
        if (whiteCount.toLong() * 2 < pixels.size || whiteCount == 0) return null
        // A small blue/cyan cast selects OEM cold white; neutral and warm whites use white.
        return if (blue - red >= whiteCount.toLong() * 8 && blue >= green) 30 else 29
    }

    // OEM CarSetting.apk light_ambient_color_{1..31}_all.png: same lamp-strip pixel
    // across all 31 preview resources. These are UI reference colors, not measured lamp RGB.
    private val bydPreviewRgb = listOf(
        0xa84cc1, 0x7136b3, 0x2237ac, 0x2274b6, 0x2384b8, 0x298cb1,
        0x23a3b6, 0x23b6b8, 0x4ab8b1, 0x81b8b1, 0x7eb8b3, 0x84b49c,
        0x63ad81, 0x30a557, 0x85b55f, 0xa5b366, 0xb8b67d, 0xb8a774,
        0xb6b56f, 0xb5b651, 0xb8b253, 0xaf9835, 0xb3844b, 0xb57050,
        0xb86551, 0xb8224c, 0xb24369, 0xb66573, 0xb8b8b8, 0xa7b5b8, 0x88b3b8,
    )
    /** Stock UI preview for a hardware color number (1..31), including opaque alpha. */
    fun previewRgb(index: Int): Int {
        require(index in 1..31)
        return (0xff shl 24) or bydPreviewRgb[index - 1]
    }

    private fun colorHue(rgb: Int) = hue((rgb ushr 16) and 255, (rgb ushr 8) and 255, rgb and 255)
    private fun saturation(rgb: Int): Double {
        val r = (rgb ushr 16) and 255; val g = (rgb ushr 8) and 255; val b = rgb and 255
        val high = maxOf(r, g, b)
        return if (high == 0) 0.0 else (high - minOf(r, g, b)).toDouble() / high
    }
    private fun hueDistance(a: Double, b: Double): Double = abs(a - b).let { minOf(it, 360 - it) }

    fun bydColor(rgb: Int): Int {
        val target = colorHue(rgb)
        // Ignore OEM white/near-white. Hue is primary; saturation only breaks close matches.
        return bydPreviewRgb.indices.filter { saturation(bydPreviewRgb[it]) >= 0.20 }
            .minBy { hueDistance(target, colorHue(bydPreviewRgb[it])) +
                abs(saturation(rgb) - saturation(bydPreviewRgb[it])) * 3 } + 1
    }

    internal fun neighbors(center: Int): List<Int> {
        if (center == 29 || center == 30) return listOf(29, 30)
        val target = colorHue(bydPreviewRgb[center - 1])
        return bydPreviewRgb.indices.filter { saturation(bydPreviewRgb[it]) >= 0.20 &&
            hueDistance(target, colorHue(bydPreviewRgb[it])) <= 35 }
            .sortedBy { hueDistance(target, colorHue(bydPreviewRgb[it])) }.take(5).map { it + 1 }
            .ifEmpty { listOf(center) }
    }

    private fun hue(r: Int, g: Int, b: Int): Double {
        val high = maxOf(r, g, b); val low = minOf(r, g, b); val delta = high - low
        if (delta == 0) return 0.0
        val sector = when (high) {
            r -> (g - b).toDouble() / delta
            g -> (b - r).toDouble() / delta + 2
            else -> (r - g).toDouble() / delta + 4
        }
        return ((sector * 60) % 360 + 360) % 360
    }
}

internal fun ambientColorPalette(source: AmbientColorSource, selected: List<Int>, albumColor: Int?): List<Int> {
    if (source == AmbientColorSource.SELECTED) return normalizeAmbientPalette(selected)
    val center = albumColor?.takeIf { it in 1..31 } ?: return (1..31).toList()
    return AmbientAlbumPalette.neighbors(center)
}
