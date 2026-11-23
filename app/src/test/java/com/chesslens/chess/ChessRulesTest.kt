package com.chesslens.chess

import com.chesslens.tracking.MoveTracker
import com.chesslens.tracking.TrackingResult
import com.chesslens.vision.FrameAnalysisPipeline
import org.junit.Assert.*
import org.junit.Test

class ChessRulesTest {
    @Test fun initialPositionHasTwentyLegalMoves() {
        val start = ChessPosition.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        assertEquals(20, ChessRules.legalMoves(start).size)
    }

    @Test fun pinnedPieceCannotExposeItsKing() {
        val pinned = ChessPosition.fromFen("k3r3/8/8/8/8/8/4R3/4K3 w - - 0 1")
        assertFalse(ChessRules.legalMoves(pinned).any { it.uci() == "e2d2" })
    }

    @Test fun castlingMovesRookAndRevokesRights() {
        val position = ChessPosition.fromFen("4k3/8/8/8/8/8/8/4K2R w K - 0 1")
        val result = ChessRules.apply(position, ChessMove(ChessRules.parseSquare("e1"), ChessRules.parseSquare("g1")))
        assertEquals('K', result.squares[ChessRules.parseSquare("g1")])
        assertEquals('R', result.squares[ChessRules.parseSquare("f1")])
        assertEquals("-", result.castling)
    }

    @Test fun promotionChoicesAreLegalAndApplied() {
        val position = ChessPosition.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val promotions = ChessRules.legalMoves(position).filter { it.from == ChessRules.parseSquare("a7") }
        assertEquals(setOf('Q', 'R', 'B', 'N'), promotions.mapNotNull { it.promotion }.toSet())
        val promoted = ChessRules.apply(position, ChessMove(ChessRules.parseSquare("a7"), ChessRules.parseSquare("a8"), 'N'))
        assertEquals('N', promoted.squares[ChessRules.parseSquare("a8")])
    }

    @Test fun enPassantRemovesTheCapturedPawn() {
        val position = ChessPosition.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
        val next = ChessRules.apply(position, ChessMove(ChessRules.parseSquare("e5"), ChessRules.parseSquare("d6")))
        assertEquals('P', next.squares[ChessRules.parseSquare("d6")])
        assertNull(next.squares[ChessRules.parseSquare("d5")])
    }

    @Test fun trackerAcceptsOnlyAValidatedSingleMove() {
        val position = ChessPosition.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val tracker = MoveTracker(position)
        val after = ChessRules.apply(position, ChessMove(ChessRules.parseSquare("e2"), ChessRules.parseSquare("e4")))
        val result = tracker.observe(after.squares)
        assertTrue(result is TrackingResult.MoveAccepted)
        assertEquals("e2e4", (result as TrackingResult.MoveAccepted).uci)
        assertEquals(TrackingResult.Unchanged, tracker.observe(after.squares))
        val bad = List<Char?>(64) { null }
        assertEquals(TrackingResult.RecognitionMismatch, tracker.observe(bad))
        assertEquals(after, tracker.position)
    }

    @Test fun trackerAcceptsAPartialObservationWhenExactlyOneMoveFits() {
        val position = ChessPosition.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val tracker = MoveTracker(position)
        val after = ChessRules.apply(position, ChessMove(ChessRules.parseSquare("e2"), ChessRules.parseSquare("e4")))
        val partial = after.squares.mapIndexed { index, piece ->
            if (index == 0 || index == 63) '?' else piece
        }
        val result = tracker.observePartial(partial)
        assertTrue(result is TrackingResult.MoveAccepted)
        assertEquals("e2e4", (result as TrackingResult.MoveAccepted).uci)
        assertEquals(after, tracker.position)
    }

    @Test fun trackerRecoversWhenTwoPlyFramesWereMissed() {
        val position = ChessPosition.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val tracker = MoveTracker(position)
        val afterWhite = ChessRules.apply(
            position,
            ChessMove(ChessRules.parseSquare("e2"), ChessRules.parseSquare("e4"))
        )
        val afterBlack = ChessRules.apply(
            afterWhite,
            ChessMove(ChessRules.parseSquare("e7"), ChessRules.parseSquare("e5"))
        )

        val result = tracker.observePartial(afterBlack.squares)

        assertTrue(result is TrackingResult.PositionReconciled)
        assertEquals(2, (result as TrackingResult.PositionReconciled).plies)
        assertEquals(afterBlack, tracker.position)
    }

    @Test fun playerSideIsAlwaysTheBottomSideAndControlsSuggestionTurn() {
        val whiteToMove = ChessPosition.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val blackPlayer = FrameAnalysisPipeline(whiteToMove, whiteAtBottom = false)
        assertFalse(blackPlayer.playerIsWhite)
        assertFalse(blackPlayer.isPlayerTurn)

        val blackToMove = ChessPosition.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1")
        val blackPlayerTurn = FrameAnalysisPipeline(blackToMove, whiteAtBottom = false)
        assertTrue(blackPlayerTurn.isPlayerTurn)

        val whitePlayer = FrameAnalysisPipeline(whiteToMove, whiteAtBottom = true)
        assertTrue(whitePlayer.playerIsWhite)
        assertTrue(whitePlayer.isPlayerTurn)
    }
}
