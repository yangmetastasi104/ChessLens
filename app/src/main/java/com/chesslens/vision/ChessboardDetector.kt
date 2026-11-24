package com.chesslens.vision

import kotlin.math.sqrt

data class BoardGeometry(val left: Float, val top: Float, val right: Float, val bottom: Float, val confidence: Float) {
    val cellWidth get() = (right - left) / 8f
    val cellHeight get() = (bottom - top) / 8f
}

/**
 * Theme-independent checker-pattern detector. Searches image scale and translation,
 * scores the two alternating square-color clusters, and rejects weak plaid-like matches.
 * It detects axis-aligned boards; tilted/perspective boards are explicitly rejected.
 */
class ChessboardDetector {
    private var cached: BoardGeometry? = null

    fun detect(input: RgbFrame): BoardGeometry? {
        val frame = RgbFrame.fromScaled(input)
        val minSide = minOf(frame.width, frame.height)
        // A playable board fills most of the shorter screen side. Smaller checker
        // patterns are game thumbnails or UI previews and must not be calibrated.
        val minCell = maxOf(8, minSide / 16)
        val cachedBoard = cached?.let { scaleGeometry(it, input, frame) }
            ?.takeIf { it.cellWidth >= minCell && it.cellHeight >= minCell }
        var best: Candidate? = null
        if (cachedBoard != null) {
            val cx = cachedBoard.left.toInt()
            val cy = cachedBoard.top.toInt()
            val cell = cachedBoard.cellWidth.toInt()
            best = search(frame, cell - maxOf(2, cell / 8), cell + maxOf(2, cell / 8), cx - cell, cx + cell, cy - cell, cy + cell)
        }
        val global = search(frame, minCell, minSide / 7,
            0, frame.width, 0, frame.height)
        if (global != null && (best == null || global.score > best.score)) best = global
        val candidate = best?.let { refine(frame, it) } ?: return null
        if (candidate.cell * 8 < minSide / 2) return null
        // The checker colors need clear separation; reject UI textures and arbitrary grids.
        if (candidate.score < 16.0) return null
        val sx = input.width.toFloat() / frame.width
        val sy = input.height.toFloat() / frame.height
        val result = BoardGeometry(candidate.x * sx, candidate.y * sy,
            (candidate.x + candidate.cell * 8) * sx, (candidate.y + candidate.cell * 8) * sy,
            (candidate.score / 90.0).coerceIn(0.0, 1.0).toFloat())
        cached = result
        return result
    }

    fun clearCache() { cached = null }

    private data class Candidate(val x: Int, val y: Int, val cell: Int, val score: Double)

    private fun refine(frame: RgbFrame, coarse: Candidate): Candidate {
        var best = coarse
        for (cell in (coarse.cell - 2).coerceAtLeast(8)..(coarse.cell + 2)) {
            val side = cell * 8
            if (side > frame.width || side > frame.height) continue
            val radius = maxOf(1, cell / 2)
            val minX = (coarse.x - radius).coerceAtLeast(0)
            val maxX = minOf(frame.width - side, coarse.x + radius)
            val minY = (coarse.y - radius).coerceAtLeast(0)
            val maxY = minOf(frame.height - side, coarse.y + radius)
            for (y in minY..maxY) for (x in minX..maxX) {
                val score = checkerScore(frame, x, y, cell)
                if (score > best.score) best = Candidate(x, y, cell, score)
            }
        }
        return best
    }

    private fun scaleGeometry(g: BoardGeometry, input: RgbFrame, frame: RgbFrame): BoardGeometry {
        val sx = frame.width.toFloat() / input.width
        val sy = frame.height.toFloat() / input.height
        return BoardGeometry(g.left * sx, g.top * sy, g.right * sx, g.bottom * sy, g.confidence)
    }

    private fun search(
        frame: RgbFrame, minCell: Int, maxCell: Int,
        minX: Int, maxX: Int, minY: Int, maxY: Int
    ): Candidate? {
        if (minCell > maxCell || maxX < minX || maxY < minY) return null
        var best: Candidate? = null
        for (cell in minCell..maxCell step maxOf(1, (maxCell - minCell) / 24)) {
            val side = cell * 8
            if (side > frame.width || side > frame.height) continue
            val x0 = minX.coerceAtLeast(0)
            val x1 = minOf(maxX, frame.width - side)
            val y0 = minY.coerceAtLeast(0)
            val y1 = minOf(maxY, frame.height - side)
            val stride = maxOf(1, cell / 2)
            var y = y0
            while (y <= y1) {
                var x = x0
                while (x <= x1) {
                    val score = checkerScore(frame, x, y, cell)
                    if (best == null || score > best.score) best = Candidate(x, y, cell, score)
                    x += stride
                }
                y += stride
            }
        }
        return best
    }

    private fun checkerScore(frame: RgbFrame, x: Int, y: Int, cell: Int): Double {
        var ar = 0.0; var ag = 0.0; var ab = 0.0; var ac = 0
        var br = 0.0; var bg = 0.0; var bb = 0.0; var bc = 0
        var asq = 0.0; var bsq = 0.0
        // Four near-corner samples avoid most piece silhouettes and board highlights.
        val offsets = intArrayOf(cell / 5, cell - 1 - cell / 5)
        for (row in 0..7) for (col in 0..7) {
            for (dy in offsets) for (dx in offsets) {
                val pixel = frame[(x + col * cell + dx).coerceIn(0, frame.width - 1),
                    (y + row * cell + dy).coerceIn(0, frame.height - 1)]
                val r = (pixel shr 16 and 255).toDouble()
                val g = (pixel shr 8 and 255).toDouble()
                val b = (pixel and 255).toDouble()
                val intensity = .299 * r + .587 * g + .114 * b
                if ((row + col) and 1 == 0) {
                    ar += r; ag += g; ab += b; asq += intensity * intensity; ac++
                } else {
                    br += r; bg += g; bb += b; bsq += intensity * intensity; bc++
                }
            }
        }
        val dr = ar / ac - br / bc
        val dg = ag / ac - bg / bc
        val db = ab / ac - bb / bc
        // Penalize partial-board aliases: a correctly aligned checkerboard has
        // tight within-parity color clusters across all 32 squares.
        val aMean = .299 * ar / ac + .587 * ag / ac + .114 * ab / ac
        val bMean = .299 * br / bc + .587 * bg / bc + .114 * bb / bc
        val withinA = (asq / ac - aMean * aMean).coerceAtLeast(0.0)
        val withinB = (bsq / bc - bMean * bMean).coerceAtLeast(0.0)
        val withinPenalty = sqrt((withinA + withinB) / 2.0)
        // RGB Euclidean checker separation works with both chromatic and grayscale themes.
        return sqrt(dr * dr + dg * dg + db * db) - withinPenalty * 0.65
    }
}
