package com.chesslens.vision

import com.chesslens.chess.ChessPosition
import org.junit.Assert.*
import org.junit.Test

class PieceRecognizerTest {
    @Test fun calibrationRecognizesTheTrustedPositionAndOrientation() {
        val width = 128; val cell = 16
        val pixels = IntArray(width * width)
        for (row in 0..7) for (col in 0..7) {
            val light = ((row + col) % 2 == 0)
            val bg = if (light) 0xFFE0D0B0.toInt() else 0xFF574331.toInt()
            for (y in row * cell until (row + 1) * cell)
                for (x in col * cell until (col + 1) * cell) pixels[y * width + x] = bg
            val index = row * 8 + col
            val piece = START.squares[index] ?: continue
            val glyph = piece.code
            val fg = if (piece.isUpperCase()) 0xFFFFFFFF.toInt() else 0xFF080808.toInt()
            // Stable synthetic silhouette with a piece-specific normalized stripe pattern.
            for (gy in 3..12) for (gx in 3..12) {
                val bit = ((gx - 3) % 4) + ((gy - 3) % 2) * 4
                if ((glyph shr bit and 1) != 0)
                    pixels[(row * cell + gy) * width + col * cell + gx] = fg
            }
        }
        val frame = RgbFrame(width, width, pixels)
        val geometry = BoardGeometry(0f, 0f, width.toFloat(), width.toFloat(), 1f)
        val recognizer = PieceRecognizer(confidenceThreshold = .25f)
        recognizer.calibrate(frame, geometry, true, START)
        val result = recognizer.recognize(frame, geometry, true)
        assertTrue("uncertain squares=${result.uncertainSquares.size}", result.squares != null)
        assertEquals(START.squares, result.squares)
    }

    companion object {
        private val START = ChessPosition.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    }
}
