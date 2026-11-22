package com.chesslens.vision

import org.junit.Assert.*
import org.junit.Test

class ChessboardDetectorTest {
    @Test fun findsBoardAtUnknownOffsetAndScale() {
        val width = 320
        val height = 480
        val pixels = IntArray(width * height) { 0xFF30343A.toInt() }
        val left = 44; val top = 91; val cell = 24
        for (row in 0 until 8) for (col in 0 until 8) {
            val color = if ((row + col) % 2 == 0) 0xFFE5D8B8.toInt() else 0xFF73553D.toInt()
            for (y in top + row * cell until top + (row + 1) * cell)
                for (x in left + col * cell until left + (col + 1) * cell)
                    pixels[y * width + x] = color
        }
        val detected = ChessboardDetector().detect(RgbFrame(width, height, pixels))
        assertNotNull(detected)
        assertEquals(left.toFloat(), detected!!.left, 5f)
        assertEquals(top.toFloat(), detected.top, 5f)
        assertEquals((left + cell * 8).toFloat(), detected.right, 8f)
        assertTrue(detected.confidence > .5f)
    }

    @Test fun rejectsImageWithoutCheckerPattern() {
        val frame = RgbFrame(200, 300, IntArray(60_000) { 0xFF444444.toInt() })
        assertNull(ChessboardDetector().detect(frame))
    }
}
