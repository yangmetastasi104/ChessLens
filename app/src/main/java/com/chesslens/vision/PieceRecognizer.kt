package com.chesslens.vision

import com.chesslens.chess.ChessPosition
import kotlin.math.sqrt

/**
 * Result of one recognition pass. [squares] uses null for an empty square and
 * '?' for a square that could not be classified confidently. Keeping the two
 * cases separate lets the legal-move gate reconcile a few uncertain squares
 * without ever inventing an empty square or a piece.
 */
data class PieceRecognition(
    val squares: List<Char?>,
    val confidence: Float,
    val uncertainSquares: List<Int>
)

/**
 * Local per-theme calibrated piece classifier. A user supplies a trusted FEN for a
 * visible position; normalized square-image features are learned on-device from it.
 * Unknown/low-confidence squares remain unresolved instead of being guessed empty.
 */
class PieceRecognizer(private val confidenceThreshold: Float = 0.35f) {
    private val templates = mutableMapOf<Char, MutableList<FloatArray>>()

    fun clearCalibration() = templates.clear()
    val isCalibrated: Boolean get() = templates.isNotEmpty()

    fun calibrate(frame: RgbFrame, board: BoardGeometry, whiteAtBottom: Boolean, known: ChessPosition) {
        templates.clear()
        addCalibration(frame, board, whiteAtBottom, known)
    }

    /** Adds another trusted sample of the same visible position. */
    fun addCalibration(frame: RgbFrame, board: BoardGeometry, whiteAtBottom: Boolean, known: ChessPosition) {
        for (screenRow in 0..7) for (screenCol in 0..7) {
            val boardIndex = boardIndex(screenRow, screenCol, whiteAtBottom)
            val piece = known.squares[boardIndex] ?: '.'
            val samples = templates.getOrPut(piece) { mutableListOf() }
            val limit = if (piece == '.') MAX_EMPTY_SAMPLES else MAX_PIECE_SAMPLES
            if (samples.size < limit) samples += feature(frame, board, screenRow, screenCol)
        }
    }

    fun recognize(frame: RgbFrame, board: BoardGeometry, whiteAtBottom: Boolean): PieceRecognition {
        if (templates.isEmpty()) {
            return PieceRecognition(List(64) { '?' }, 0f, (0..63).toList())
        }
        val result = MutableList<Char?>(64) { '?' }
        val uncertain = mutableListOf<Int>()
        var totalConfidence = 0f
        var classified = 0
        for (screenRow in 0..7) for (screenCol in 0..7) {
            val index = boardIndex(screenRow, screenCol, whiteAtBottom)
            val vector = feature(frame, board, screenRow, screenCol)
            val match = nearest(vector)
            if (match == null || match.second < confidenceThreshold) {
                uncertain += index
            } else {
                result[index] = match.first.takeUnless { it == '.' }
                totalConfidence += match.second
                classified++
            }
        }
        return PieceRecognition(result, if (classified == 0) 0f else totalConfidence / 64f, uncertain)
    }

    private fun nearest(vector: FloatArray): Pair<Char, Float>? {
        val classDistances = mutableMapOf<Char, Float>()
        for ((piece, samples) in templates) for (sample in samples) {
            var squared = 0f
            for (i in vector.indices) {
                val delta = vector[i] - sample[i]
                squared += delta * delta
            }
            val distance = sqrt(squared / vector.size)
            if (distance < (classDistances[piece] ?: Float.POSITIVE_INFINITY)) classDistances[piece] = distance
        }
        val ranked = classDistances.entries.sortedBy { it.value }
        val winner = ranked.firstOrNull() ?: return null
        val piece = winner.key
        val bestDistance = winner.value
        val secondDistance = ranked.getOrNull(1)?.value ?: Float.POSITIVE_INFINITY
        if (piece == '.') {
            // Empty squares are accepted only when close to a calibrated empty sample.
            return '.' to (1f - bestDistance / EMPTY_DISTANCE).coerceIn(0f, 1f)
        }
        val closeness = (1f - bestDistance / MAX_PIECE_DISTANCE).coerceIn(0f, 1f)
        val margin = if (secondDistance.isFinite())
            ((secondDistance - bestDistance) / MARGIN_DISTANCE).coerceIn(0f, 1f) else 1f
        return piece to (closeness * (0.7f + 0.3f * margin))
    }

    private fun feature(frame: RgbFrame, board: BoardGeometry, row: Int, col: Int): FloatArray {
        val left = board.left + col * board.cellWidth
        val top = board.top + row * board.cellHeight
        val cw = board.cellWidth
        val ch = board.cellHeight
        val bg = background(frame, left, top, cw, ch)
        val n = 12
        val values = FloatArray(n * n * 3)
        for (y in 0 until n) for (x in 0 until n) {
            val px = (left + cw * (.12f + .76f * (x + .5f) / n)).toInt().coerceIn(0, frame.width - 1)
            val py = (top + ch * (.12f + .76f * (y + .5f) / n)).toInt().coerceIn(0, frame.height - 1)
            val pixel = frame[px, py]
            val offset = (y * n + x) * 3
            values[offset] = ((pixel shr 16 and 255) - bg[0]) / 255f
            values[offset + 1] = ((pixel shr 8 and 255) - bg[1]) / 255f
            values[offset + 2] = ((pixel and 255) - bg[2]) / 255f
        }
        return values
    }

    /** Average the square border, avoiding the center where a piece usually sits. */
    private fun background(frame: RgbFrame, left: Float, top: Float, cw: Float, ch: Float): FloatArray {
        val fractions = floatArrayOf(.08f, .5f, .92f)
        var r = 0f
        var g = 0f
        var b = 0f
        var count = 0
        for (fy in fractions) for (fx in fractions) {
            if (fx == .5f && fy == .5f) continue
            val px = (left + cw * fx).toInt().coerceIn(0, frame.width - 1)
            val py = (top + ch * fy).toInt().coerceIn(0, frame.height - 1)
            val pixel = frame[px, py]
            r += (pixel shr 16 and 255).toFloat()
            g += (pixel shr 8 and 255).toFloat()
            b += (pixel and 255).toFloat()
            count++
        }
        return floatArrayOf(r / count, g / count, b / count)
    }

    private fun boardIndex(row: Int, col: Int, whiteAtBottom: Boolean): Int =
        if (whiteAtBottom) row * 8 + col else (7 - row) * 8 + (7 - col)

    companion object {
        private const val MAX_EMPTY_SAMPLES = 32
        private const val MAX_PIECE_SAMPLES = 8
        private const val EMPTY_DISTANCE = 0.12f
        private const val MAX_PIECE_DISTANCE = 0.32f
        private const val MARGIN_DISTANCE = 0.06f
    }
}
