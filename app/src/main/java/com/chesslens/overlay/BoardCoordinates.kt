package com.chesslens.overlay

/** Maps algebraic squares to pixel centers within a detected board rectangle. */
data class BoardRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(right > left && bottom > top) }
}

object BoardCoordinates {
    fun center(square: String, board: BoardRect, whiteAtBottom: Boolean): Pair<Float, Float> {
        require(square.matches(Regex("[a-h][1-8]"))) { "Invalid square: $square" }
        val file = square[0] - 'a'
        val rank = square[1] - '1'
        val cellW = (board.right - board.left) / 8f
        val cellH = (board.bottom - board.top) / 8f
        val col = if (whiteAtBottom) file else 7 - file
        val row = if (whiteAtBottom) 7 - rank else rank
        return board.left + (col + .5f) * cellW to board.top + (row + .5f) * cellH
    }
}
