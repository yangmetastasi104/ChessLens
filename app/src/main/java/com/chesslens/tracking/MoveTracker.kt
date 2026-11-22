package com.chesslens.tracking

import com.chesslens.chess.ChessPosition
import com.chesslens.chess.ChessRules

sealed interface TrackingResult {
    data class MoveAccepted(val previous: ChessPosition, val current: ChessPosition, val uci: String) : TrackingResult
    data object Unchanged : TrackingResult
    data object RecognitionMismatch : TrackingResult
    data object Ambiguous : TrackingResult
}

/**
 * Advances state only when an observed board exactly matches one legal successor.
 * Invalid/noisy frames never mutate history or invent side-to-move/castling state.
 */
class MoveTracker(initial: ChessPosition) {
    var position: ChessPosition = initial
        private set

    fun reset(newPosition: ChessPosition) { position = newPosition }

    fun observe(observedSquares: List<Char?>): TrackingResult {
        require(observedSquares.size == 64)
        if (observedSquares == position.squares) return TrackingResult.Unchanged
        val matches = ChessRules.legalMoves(position).mapNotNull { move ->
            val next = ChessRules.apply(position, move)
            if (next.squares == observedSquares) move.uci() to next else null
        }
        return when (matches.size) {
            0 -> TrackingResult.RecognitionMismatch
            1 -> {
                val previous = position
                val (uci, next) = matches.single()
                position = next
                TrackingResult.MoveAccepted(previous, next, uci)
            }
            else -> TrackingResult.Ambiguous
        }
    }
}
