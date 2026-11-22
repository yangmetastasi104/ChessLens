package com.chesslens.chess

/**
 * Screenshot recognition does not reveal historic castling or en-passant state.
 * Unknown rights are represented as null and must never be emitted as invented FEN.
 */
data class ChessPosition(
    val squares: List<Char?>,
    val whiteToMove: Boolean?,
    val castling: String?,
    val enPassant: String?,
    val halfmoveClock: Int? = null,
    val fullmoveNumber: Int? = null
) {
    init {
        require(squares.size == 64) { "A chess position has exactly 64 squares" }
    }

    fun toFen(): String? {
        val active = whiteToMove ?: return null
        val rights = castling ?: return null
        val ep = enPassant ?: return null
        val half = halfmoveClock ?: return null
        val full = fullmoveNumber ?: return null
        val placement = (0..7).joinToString("/") { rank ->
            var empty = 0
            buildString {
                for (file in 0..7) {
                    val piece = squares[rank * 8 + file]
                    if (piece == null) empty++ else {
                        if (empty > 0) append(empty).also { empty = 0 }
                        append(piece)
                    }
                }
                if (empty > 0) append(empty)
            }
        }
        return "$placement ${if (active) "w" else "b"} $rights $ep $half $full"
    }

    companion object {
        /** Parses complete FEN; partial/unknown state is intentionally not accepted. */
        fun fromFen(fen: String): ChessPosition {
            val fields = fen.trim().split(Regex("\\s+"))
            require(fields.size == 6) { "FEN must contain six fields" }
            val rows = fields[0].split("/")
            require(rows.size == 8)
            val board = mutableListOf<Char?>()
            rows.forEach { row ->
                var count = 0
                row.forEach { c ->
                    if (c in '1'..'8') {
                        count += c.digitToInt()
                        repeat(c.digitToInt()) { board.add(null) }
                    } else {
                        require(c in "pnbrqkPNBRQK")
                        count++
                        board.add(c)
                    }
                }
                require(count == 8) { "Each FEN rank must describe eight squares" }
            }
            require(fields[1] in setOf("w", "b"))
            require(fields[2] == "-" || (fields[2].all { it in "KQkq" } && fields[2].toSet().size == fields[2].length))
            require(fields[3] == "-" || fields[3].matches(Regex("[a-h][36]")))
            val half = fields[4].toInt()
            val full = fields[5].toInt()
            require(half >= 0 && full >= 1)
            return ChessPosition(board, fields[1] == "w", fields[2], fields[3], half, full)
        }
    }
}
