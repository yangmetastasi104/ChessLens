package com.chesslens.overlay

/** Maps algebraic squares to pixel centers within a detected board rectangle. */
data class BoardRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(right > left && bottom > top) }
}

object BoardCoordinates {
    /**
     * Capture frames are downsampled before vision processing, but the overlay
     * uses real screen pixels. Scale the detected board back to screen space.
     */
    fun scale(board: BoardRect, frameWidth: Int, frameHeight: Int, screenWidth: Int, screenHeight: Int): BoardRect {
        require(frameWidth > 0 && frameHeight > 0 && screenWidth > 0 && screenHeight > 0)
        val sx = screenWidth.toFloat() / frameWidth
        val sy = screenHeight.toFloat() / frameHeight
        return BoardRect(board.left * sx, board.top * sy, board.right * sx, board.bottom * sy)
    }

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
