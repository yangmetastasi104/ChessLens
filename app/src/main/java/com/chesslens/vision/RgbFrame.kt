package com.chesslens.vision

/** Platform-independent immutable ARGB frame, so vision logic is unit-testable. */
data class RgbFrame(val width: Int, val height: Int, val argb: IntArray) {
    init { require(width > 0 && height > 0 && argb.size == width * height) }
    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    companion object {
        fun fromScaled(source: RgbFrame, maxWidth: Int = 320, maxHeight: Int = 640): RgbFrame {
            val scale = minOf(1.0, maxWidth.toDouble() / source.width, maxHeight.toDouble() / source.height)
            if (scale == 1.0) return source
            val width = (source.width * scale).toInt().coerceAtLeast(1)
            val height = (source.height * scale).toInt().coerceAtLeast(1)
            val pixels = IntArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                pixels[y * width + x] = source[x * source.width / width, y * source.height / height]
            }
            return RgbFrame(width, height, pixels)
        }
    }
}
