package com.chesslens.vision

import com.chesslens.chess.ChessPosition
import com.chesslens.tracking.TrackingResult
import org.junit.Assert.*
import org.junit.Test

class FrameAnalysisPipelineTest {
    @Test fun rejectsBoardThatDoesNotMatchTheConfirmedFen() {
        val pipeline = FrameAnalysisPipeline(START, whiteAtBottom = true)
        val result = pipeline.process(frame(MIDDLEGAME, THEME_A))
        assertTrue(result.tracking is TrackingResult.RecognitionMismatch)
        assertTrue(result.message.contains("does not match"))
        assertEquals(START, pipeline.currentPosition)
    }

    @Test fun rejectsFlippedBoardWhenThePlayerColorIsWrong() {
        val pipeline = FrameAnalysisPipeline(MIDDLEGAME, whiteAtBottom = false)
        val result = pipeline.process(frame(MIDDLEGAME, THEME_A))
        assertTrue(result.tracking is TrackingResult.RecognitionMismatch)
        assertTrue(result.message.contains("does not match"))
        assertEquals(MIDDLEGAME, pipeline.currentPosition)
    }

    @Test fun recalibratesWhenTheFirstBoardWasADifferentTheme() {
        val pipeline = FrameAnalysisPipeline(START, whiteAtBottom = true)
        assertEquals("Calibrated from confirmed FEN", pipeline.process(frame(START, THEME_A)).message)

        val firstMismatch = pipeline.process(frame(START, THEME_B))
        assertTrue(firstMismatch.tracking is TrackingResult.RecognitionMismatch)
        assertTrue(firstMismatch.message.contains("Uncertain squares"))

        val recalibrating = pipeline.process(frame(START, THEME_B))
        assertTrue(recalibrating.message.contains("recalibrating"))

        assertEquals("Calibrated from confirmed FEN", pipeline.process(frame(START, THEME_B)).message)
        assertEquals(TrackingResult.Unchanged, pipeline.process(frame(START, THEME_B)).tracking)
        assertEquals(START, pipeline.currentPosition)
    }

    private data class Theme(val whitePiece: Int, val blackPiece: Int)

    private fun frame(position: ChessPosition, theme: Theme): RgbFrame {
        val size = 256
        val cell = size / 8
        val pixels = IntArray(size * size)
        for (row in 0..7) for (col in 0..7) {
            val light = (row + col) % 2 == 0
            val bg = if (light) 0xFFE0D0B0.toInt() else 0xFF574331.toInt()
            for (y in row * cell until (row + 1) * cell)
                for (x in col * cell until (col + 1) * cell) pixels[y * size + x] = bg
            val piece = position.squares[row * 8 + col] ?: continue
            val fg = if (piece.isUpperCase()) theme.whitePiece else theme.blackPiece
            val from = cell * 2 / 10
            val to = cell * 8 / 10
            // A stable piece-specific stripe pattern keeps the synthetic pieces
            // distinguishable, like real chess glyphs, instead of one blob per color.
            for (y in row * cell + from until row * cell + to)
                for (x in col * cell + from until col * cell + to) {
                    val bit = ((x - col * cell - from) % 4) + ((y - row * cell - from) % 2) * 4
                    if ((piece.code shr bit and 1) != 0) pixels[y * size + x] = fg
                }
        }
        return RgbFrame(size, size, pixels)
    }

    companion object {
        private val START = ChessPosition.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        private val MIDDLEGAME = ChessPosition.fromFen("r3k2r/1pqnbppp/p2ppn2/6B1/3NPP2/2N2Q2/PPP3PP/2KR1B1R w kq - 0 10")
        private val THEME_A = Theme(0xFF00FF00.toInt(), 0xFFFF00FF.toInt())
        private val THEME_B = Theme(0xFFFFFF00.toInt(), 0xFF00FFFF.toInt())
    }
}
