package com.chesslens.vision

import com.chesslens.chess.ChessPosition
import com.chesslens.tracking.MoveTracker
import com.chesslens.tracking.TrackingResult

data class FrameAnalysis(
    val board: BoardGeometry?,
    val tracking: TrackingResult?,
    val message: String,
    val confidence: Float = 0f
)

/**
 * On-device frame pipeline: board localization -> per-theme calibration from a
 * user-confirmed FEN -> piece classification -> temporal stability -> legal transition.
 */
class FrameAnalysisPipeline(
    private val initial: ChessPosition,
    whiteAtBottom: Boolean,
    private val boardDetector: ChessboardDetector = ChessboardDetector(),
    private val recognizer: PieceRecognizer = PieceRecognizer(),
    private val tracker: MoveTracker = MoveTracker(initial)
) {
    val currentPosition: ChessPosition get() = tracker.position
    /** The player chooses their own color; their pieces remain at the bottom. */
    val playerIsWhite: Boolean = whiteAtBottom
    val currentWhiteAtBottom: Boolean get() = whiteAtBottom
    val isPlayerTurn: Boolean get() = tracker.position.whiteToMove == playerIsWhite
    private val whiteAtBottom = whiteAtBottom
    private var lastStable: List<Char?>? = null
    private var stableCount = 0
    private var calibrationFrames = 0
    private var uncertainFrames = 0

    fun process(frame: RgbFrame): FrameAnalysis {
        val board = boardDetector.detect(frame)
            ?: return FrameAnalysis(null, null, "Full chessboard not found. Show all 8 ranks.")
        if (!recognizer.isCalibrated) {
            // Validate the confirmed FEN before learning any templates. Occupancy is
            // independent of the piece theme, so a wrong position, a flipped board or
            // a covered board cannot poison the calibration.
            if (!occupancyMatches(frame, board)) {
                return FrameAnalysis(
                    board,
                    TrackingResult.RecognitionMismatch,
                    "Visible board does not match the confirmed FEN or is partly covered. Keep all 8 ranks visible; if the position is different, stop and restart with the current FEN.",
                    0f
                )
            }
            recognizer.calibrate(frame, board, whiteAtBottom, initial)
            val first = recognizer.recognize(frame, board, whiteAtBottom)
            val observed = first.squares.count { it != '?' }
            if (observed < MIN_OBSERVED_SQUARES || !isConsistent(first.squares, initial.squares)) {
                recognizer.clearCalibration()
                return FrameAnalysis(
                    board,
                    TrackingResult.RecognitionMismatch,
                    "Calibrating… keep the full board visible and move the ChessLens panel off it.",
                    first.confidence
                )
            }
            calibrationFrames = 1
            lastStable = first.squares
            stableCount = 1
            uncertainFrames = 0
            return FrameAnalysis(board, TrackingResult.Unchanged, "Calibrated from confirmed FEN", first.confidence)
        }
        val recognition = recognizer.recognize(frame, board, whiteAtBottom)
        val squares = recognition.squares
        val observed = squares.count { it != '?' }
        if (observed < MIN_OBSERVED_SQUARES) {
            uncertainFrames++
            // A total recognition failure at the confirmed starting position means
            // the templates came from a different board or theme. Clear them; the
            // next frame re-checks the visible position against the FEN before
            // learning again, so a changed position is never mislabeled.
            if (tracker.position == initial && uncertainFrames >= RECALIBRATE_AFTER) {
                recognizer.clearCalibration()
                lastStable = null
                stableCount = 0
                calibrationFrames = 0
                uncertainFrames = 0
                return FrameAnalysis(
                    board,
                    TrackingResult.RecognitionMismatch,
                    "Board not recognized; recalibrating. Keep all 8 ranks visible and move the panel off the board.",
                    recognition.confidence
                )
            }
            return FrameAnalysis(
                board,
                TrackingResult.RecognitionMismatch,
                "Uncertain squares: ${recognition.uncertainSquares.size}. Keep all 8 ranks visible.",
                recognition.confidence
            )
        }
        uncertainFrames = 0
        // Learn a few more samples only while the trusted initial position is still visible.
        if (calibrationFrames in 1 until CALIBRATION_FRAMES &&
            tracker.position == initial && isConsistent(squares, initial.squares)
        ) {
            recognizer.addCalibration(frame, board, whiteAtBottom, initial)
            calibrationFrames++
        }
        if (squares != lastStable) {
            lastStable = squares
            stableCount = 1
            return FrameAnalysis(board, null, "Confirming changed position", recognition.confidence)
        }
        stableCount++
        if (stableCount < REQUIRED_STABLE_FRAMES) {
            return FrameAnalysis(board, null, "Waiting for stable board", recognition.confidence)
        }
        val result = tracker.observePartial(squares)
        val message = when (result) {
            is TrackingResult.MoveAccepted -> "Move detected: ${result.uci}"
            is TrackingResult.PositionReconciled ->
                "Position recovered after ${result.plies} missed plies"
            TrackingResult.Unchanged -> "Position stable"
            TrackingResult.RecognitionMismatch ->
                "Position still differs from the tracked game. Restart capture with the current FEN."
            TrackingResult.Ambiguous -> "Move is ambiguous; state retained"
        }
        return FrameAnalysis(board, result, message, recognition.confidence)
    }

    fun reset(position: ChessPosition) {
        tracker.reset(position)
        recognizer.clearCalibration()
        boardDetector.clearCache()
        lastStable = null
        stableCount = 0
        calibrationFrames = 0
        uncertainFrames = 0
    }

    private fun isConsistent(observed: List<Char?>, candidate: List<Char?>): Boolean =
        observed.indices.all { index -> observed[index] == '?' || observed[index] == candidate[index] }

    private fun occupancyMatches(frame: RgbFrame, board: BoardGeometry): Boolean {
        val occupied = recognizer.occupancy(frame, board, whiteAtBottom)
        var mismatches = 0
        for (index in 0..63) {
            if (occupied[index] != (initial.squares[index] != null)) mismatches++
            if (mismatches > MAX_OCCUPANCY_MISMATCHES) return false
        }
        return true
    }

    companion object {
        private const val REQUIRED_STABLE_FRAMES = 2
        private const val CALIBRATION_FRAMES = 3
        private const val MIN_OBSERVED_SQUARES = 52
        private const val RECALIBRATE_AFTER = 2
        private const val MAX_OCCUPANCY_MISMATCHES = 4
    }
}
