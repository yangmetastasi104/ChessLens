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

    fun process(frame: RgbFrame): FrameAnalysis {
        val board = boardDetector.detect(frame)
            ?: return FrameAnalysis(null, null, "Chessboard not found")
        if (!recognizer.isCalibrated) {
            recognizer.calibrate(frame, board, whiteAtBottom, initial)
            calibrationFrames = 1
            val first = recognizer.recognize(frame, board, whiteAtBottom)
            lastStable = first.squares
            stableCount = 1
            return FrameAnalysis(board, TrackingResult.Unchanged, "Calibrated from confirmed FEN", board.confidence)
        }
        val recognition = recognizer.recognize(frame, board, whiteAtBottom)
        val squares = recognition.squares
        val observed = squares.count { it != '?' }
        if (observed < MIN_OBSERVED_SQUARES) {
            return FrameAnalysis(
                board,
                TrackingResult.RecognitionMismatch,
                "Uncertain squares: ${recognition.uncertainSquares.size}",
                recognition.confidence
            )
        }
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
    }

    private fun isConsistent(observed: List<Char?>, candidate: List<Char?>): Boolean =
        observed.indices.all { index -> observed[index] == '?' || observed[index] == candidate[index] }

    companion object {
        private const val REQUIRED_STABLE_FRAMES = 2
        private const val CALIBRATION_FRAMES = 3
        private const val MIN_OBSERVED_SQUARES = 52
    }
}
