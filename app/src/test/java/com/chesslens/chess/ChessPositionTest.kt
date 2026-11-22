package com.chesslens.chess

import com.chesslens.overlay.*
import org.junit.Assert.*
import org.junit.Test

class ChessPositionTest {
    @Test fun startingFenRoundTrips() {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        assertEquals(fen, ChessPosition.fromFen(fen).toFen())
    }
    @Test fun incompleteStateIsNotSerializedAsFen() {
        val board = List<Char?>(64) { null }
        assertNull(ChessPosition(board, true, null, null).toFen())
    }
    @Test fun boardMappingSupportsBothOrientations() {
        val bounds = BoardRect(0f, 0f, 800f, 800f)
        assertEquals(750f to 750f, BoardCoordinates.center("h1", bounds, true))
        assertEquals(50f to 50f, BoardCoordinates.center("h1", bounds, false))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedFen() { ChessPosition.fromFen("8/8 w - - 0 1") }
}
