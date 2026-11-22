package com.chesslens.vision

import com.chesslens.chess.ChessPosition
import kotlin.math.sqrt

data class PieceRecognition(
    val squares: List<Char?>?,
    val confidence: Float,
    val uncertainSquares: List<Int>
)

/**
 * Local per-theme calibrated piece classifier. A user supplies a trusted FEN for a
 * visible position; normalized square-image features are learned on-device from it.
 * Unknown/low-confidence squares remain unresolved instead of being guessed empty.
 */
class PieceRecognizer(private val confidenceThreshold: Float = 0.78f) {
    private val templates = mutableMapOf<Char, MutableList<FloatArray>>()

    fun clearCalibration() = templates.clear()
    val isCalibrated: Boolean get() = templates.isNotEmpty()

    fun calibrate(frame: RgbFrame, board: BoardGeometry, whiteAtBottom: Boolean, known: ChessPosition) {
        templates.clear()
        for (screenRow in 0..7) for (screenCol in 0..7) {
            val boardIndex = boardIndex(screenRow, screenCol, whiteAtBottom)
            val piece = known.squares[boardIndex] ?: '.'
            templates.getOrPut(piece) { mutableListOf() } += feature(frame, board, screenRow, screenCol)
        }
    }

    fun recognize(frame: RgbFrame, board: BoardGeometry, whiteAtBottom: Boolean): PieceRecognition {
        if (templates.isEmpty()) return PieceRecognition(null, 0f, (0..63).toList())
        val result = MutableList<Char?>(64) { null }
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
        if (uncertain.isNotEmpty()) return PieceRecognition(null,
            if (classified == 0) 0f else totalConfidence / 64f, uncertain)
        return PieceRecognition(result, totalConfidence / 64f, emptyList())
    }

    private fun nearest(vector: FloatArray): Pair<Char, Float>? {
        // Background-normalized texture close to zero is an empty square. This
        // explicit reject is important: a classifier must not force every square
        // into one of the 12 occupied-piece classes.
        val energy = vector.sumOf { kotlin.math.abs(it.toDouble()) }.toFloat() / vector.size
        if (energy < EMPTY_ENERGY_THRESHOLD) return '.' to 1f
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
        // Require both a close template and separation from competing templates.
        val closeness = (1f - bestDistance / 0.62f).coerceIn(0f, 1f)
        val margin = if (secondDistance.isFinite()) ((secondDistance - bestDistance) / 0.25f).coerceIn(0f, 1f) else 0.5f
        return piece to (closeness * (0.65f + 0.35f * margin))
    }

    private fun feature(frame: RgbFrame, board: BoardGeometry, row: Int, col: Int): FloatArray {
        val left = board.left + col * board.cellWidth
        val top = board.top + row * board.cellHeight
        val cw = board.cellWidth
        val ch = board.cellHeight
        // Estimate background from corner pixels; crop out borders and labels.
        var bg = 0f
        val corner = floatArrayOf(.08f, .92f)
        for (fy in corner) for (fx in corner) bg += luma(frame[
            (left + cw * fx).toInt().coerceIn(0, frame.width - 1),
            (top + ch * fy).toInt().coerceIn(0, frame.height - 1)])
        bg /= 4f
        val n = 12
        val values = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val px = (left + cw * (.12f + .76f * (x + .5f) / n)).toInt().coerceIn(0, frame.width - 1)
            val py = (top + ch * (.12f + .76f * (y + .5f) / n)).toInt().coerceIn(0, frame.height - 1)
            values[y * n + x] = (luma(frame[px, py]) - bg) / 255f
        }
        return values
    }

    private fun luma(pixel: Int): Float {
        val r = (pixel shr 16 and 255).toFloat()
        val g = (pixel shr 8 and 255).toFloat()
        val b = (pixel and 255).toFloat()
        return .299f * r + .587f * g + .114f * b
    }

    private fun boardIndex(row: Int, col: Int, whiteAtBottom: Boolean): Int =
        if (whiteAtBottom) row * 8 + col else (7 - row) * 8 + (7 - col)

    companion object { private const val EMPTY_ENERGY_THRESHOLD = .002f }
}
