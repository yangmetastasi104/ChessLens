package com.chesslens.chess

data class ChessMove(val from: Int, val to: Int, val promotion: Char? = null) {
    init {
        require(from in 0..63 && to in 0..63 && from != to)
        require(promotion == null || promotion.lowercaseChar() in "qrbn")
    }

    fun uci(): String = ChessRules.squareName(from) + ChessRules.squareName(to) +
        (promotion?.lowercaseChar()?.toString() ?: "")
}

/**
 * Small, dependency-free legal chess rules implementation. Board indices follow FEN:
 * 0=a8 and 63=h1. Position state is immutable at the API boundary.
 */
object ChessRules {
    private val knightSteps = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
    private val kingSteps = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)
    private val bishopSteps = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
    private val rookSteps = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    fun parseSquare(square: String): Int {
        require(square.matches(Regex("[a-h][1-8]")))
        return (8 - square[1].digitToInt()) * 8 + (square[0] - 'a')
    }

    fun squareName(index: Int): String {
        require(index in 0..63)
        return "${'a' + index % 8}${'8' - index / 8}"
    }

    fun legalMoves(position: ChessPosition): List<ChessMove> {
        val white = position.whiteToMove ?: return emptyList()
        val board = position.squares.toTypedArray()
        return pseudoMoves(position, board, white).filter { move ->
            val next = applyUnchecked(position, move)
            !isInCheck(next.squares, white)
        }
    }

    fun apply(position: ChessPosition, move: ChessMove): ChessPosition {
        require(move in legalMoves(position)) { "Illegal move ${move.uci()}" }
        return applyUnchecked(position, move)
    }

    fun isInCheck(position: ChessPosition, white: Boolean = position.whiteToMove ?: true): Boolean =
        isInCheck(position.squares, white)

    fun isCheckmate(position: ChessPosition): Boolean =
        isInCheck(position) && legalMoves(position).isEmpty()

    fun isStalemate(position: ChessPosition): Boolean =
        !isInCheck(position) && legalMoves(position).isEmpty()

    private fun isInCheck(board: List<Char?>, white: Boolean): Boolean {
        val king = if (white) 'K' else 'k'
        val square = board.indexOf(king)
        return square < 0 || isAttacked(board, square, byWhite = !white)
    }

    private fun pseudoMoves(position: ChessPosition, board: Array<Char?>, white: Boolean): List<ChessMove> {
        val out = ArrayList<ChessMove>(48)
        for (from in board.indices) {
            val piece = board[from] ?: continue
            if (piece.isUpperCase() != white) continue
            val row = from / 8
            val col = from % 8
            when (piece.lowercaseChar()) {
                'p' -> pawnMoves(position, board, from, row, col, white, out)
                'n' -> knightSteps.forEach { (dr, dc) -> addStep(board, from, row, col, dr, dc, white, out) }
                'b' -> addRays(board, from, row, col, bishopSteps, white, out)
                'r' -> addRays(board, from, row, col, rookSteps, white, out)
                'q' -> addRays(board, from, row, col, bishopSteps + rookSteps, white, out)
                'k' -> {
                    kingSteps.forEach { (dr, dc) -> addStep(board, from, row, col, dr, dc, white, out) }
                    castlingMoves(position, board, from, white, out)
                }
            }
        }
        return out
    }

    private fun pawnMoves(
        position: ChessPosition,
        board: Array<Char?>,
        from: Int,
        row: Int,
        col: Int,
        white: Boolean,
        out: MutableList<ChessMove>
    ) {
        val direction = if (white) -1 else 1
        val lastRow = if (white) 0 else 7
        val oneRow = row + direction
        if (inside(oneRow, col) && board[oneRow * 8 + col] == null) {
            addPawnMove(from, oneRow * 8 + col, lastRow, white, out)
            val startRow = if (white) 6 else 1
            val twoRow = row + direction * 2
            if (row == startRow && board[twoRow * 8 + col] == null)
                out += ChessMove(from, twoRow * 8 + col)
        }
        for (dc in listOf(-1, 1)) {
            val nr = row + direction
            val nc = col + dc
            if (!inside(nr, nc)) continue
            val to = nr * 8 + nc
            val target = board[to]
            if ((target != null && target.isUpperCase() != white && target.lowercaseChar() != 'k') ||
                position.enPassant == squareName(to)
            ) addPawnMove(from, to, lastRow, white, out)
        }
    }

    private fun addPawnMove(from: Int, to: Int, lastRow: Int, white: Boolean, out: MutableList<ChessMove>) {
        if (to / 8 == lastRow) {
            for (piece in "qrbn") out += ChessMove(from, to, if (white) piece.uppercaseChar() else piece)
        } else out += ChessMove(from, to)
    }

    private fun castlingMoves(position: ChessPosition, board: Array<Char?>, from: Int, white: Boolean, out: MutableList<ChessMove>) {
        val homeRow = if (white) 7 else 0
        if (from != homeRow * 8 + 4 || isAttacked(board.asList(), from, !white)) return
        val rights = position.castling ?: return
        val kingRight = if (white) 'K' else 'k'
        val queenRight = if (white) 'Q' else 'q'
        val rook = if (white) 'R' else 'r'
        val enemy = !white
        if (kingRight in rights && board[homeRow * 8 + 7] == rook &&
            board[homeRow * 8 + 5] == null && board[homeRow * 8 + 6] == null &&
            !isAttacked(board.asList(), homeRow * 8 + 5, enemy) &&
            !isAttacked(board.asList(), homeRow * 8 + 6, enemy)
        ) out += ChessMove(from, homeRow * 8 + 6)
        if (queenRight in rights && board[homeRow * 8] == rook &&
            board[homeRow * 8 + 1] == null && board[homeRow * 8 + 2] == null && board[homeRow * 8 + 3] == null &&
            !isAttacked(board.asList(), homeRow * 8 + 3, enemy) &&
            !isAttacked(board.asList(), homeRow * 8 + 2, enemy)
        ) out += ChessMove(from, homeRow * 8 + 2)
    }

    private fun addStep(board: Array<Char?>, from: Int, row: Int, col: Int, dr: Int, dc: Int, white: Boolean, out: MutableList<ChessMove>) {
        val nr = row + dr
        val nc = col + dc
        if (!inside(nr, nc)) return
        val to = nr * 8 + nc
        val target = board[to]
        if (target == null || (target.isUpperCase() != white && target.lowercaseChar() != 'k'))
            out += ChessMove(from, to)
    }

    private fun addRays(
        board: Array<Char?>, from: Int, row: Int, col: Int,
        directions: List<Pair<Int, Int>>, white: Boolean, out: MutableList<ChessMove>
    ) {
        for ((dr, dc) in directions) {
            var nr = row + dr
            var nc = col + dc
            while (inside(nr, nc)) {
                val to = nr * 8 + nc
                val target = board[to]
                if (target == null) out += ChessMove(from, to)
                else {
                    if (target.isUpperCase() != white && target.lowercaseChar() != 'k') out += ChessMove(from, to)
                    break
                }
                nr += dr
                nc += dc
            }
        }
    }

    private fun isAttacked(board: List<Char?>, target: Int, byWhite: Boolean): Boolean {
        val row = target / 8
        val col = target % 8
        val pawn = if (byWhite) 'P' else 'p'
        val sourceRow = row + if (byWhite) 1 else -1
        for (dc in listOf(-1, 1)) {
            val sourceCol = col + dc
            if (inside(sourceRow, sourceCol) && board[sourceRow * 8 + sourceCol] == pawn) return true
        }
        val knight = if (byWhite) 'N' else 'n'
        for ((dr, dc) in knightSteps) {
            val r = row + dr; val c = col + dc
            if (inside(r, c) && board[r * 8 + c] == knight) return true
        }
        val king = if (byWhite) 'K' else 'k'
        for ((dr, dc) in kingSteps) {
            val r = row + dr; val c = col + dc
            if (inside(r, c) && board[r * 8 + c] == king) return true
        }
        if (rayAttacked(board, row, col, bishopSteps, if (byWhite) setOf('B', 'Q') else setOf('b', 'q'))) return true
        return rayAttacked(board, row, col, rookSteps, if (byWhite) setOf('R', 'Q') else setOf('r', 'q'))
    }

    private fun rayAttacked(board: List<Char?>, row: Int, col: Int, directions: List<Pair<Int, Int>>, attackers: Set<Char>): Boolean {
        for ((dr, dc) in directions) {
            var r = row + dr; var c = col + dc
            while (inside(r, c)) {
                val piece = board[r * 8 + c]
                if (piece != null) {
                    if (piece in attackers) return true
                    break
                }
                r += dr; c += dc
            }
        }
        return false
    }

    private fun applyUnchecked(position: ChessPosition, move: ChessMove): ChessPosition {
        val board = position.squares.toMutableList()
        val piece = board[move.from] ?: error("No piece on ${squareName(move.from)}")
        val white = piece.isUpperCase()
        val wasEnPassant = piece.lowercaseChar() == 'p' &&
            position.enPassant == squareName(move.to) && board[move.to] == null &&
            move.from % 8 != move.to % 8
        val captured = if (wasEnPassant) board[move.to + if (white) 8 else -8] else board[move.to]
        board[move.from] = null
        if (wasEnPassant) board[move.to + if (white) 8 else -8] = null
        board[move.to] = move.promotion ?: piece

        if (piece.lowercaseChar() == 'k' && kotlin.math.abs(move.to - move.from) == 2) {
            val row = move.from / 8
            if (move.to % 8 == 6) {
                board[row * 8 + 5] = board[row * 8 + 7]
                board[row * 8 + 7] = null
            } else {
                board[row * 8 + 3] = board[row * 8]
                board[row * 8] = null
            }
        }

        var rights = position.castling ?: ""
        fun revoke(vararg chars: Char) { chars.forEach { rights = rights.replace(it.toString(), "") } }
        when (piece) {
            'K' -> revoke('K', 'Q')
            'k' -> revoke('k', 'q')
            'R' -> if (move.from == parseSquare("h1")) revoke('K') else if (move.from == parseSquare("a1")) revoke('Q')
            'r' -> if (move.from == parseSquare("h8")) revoke('k') else if (move.from == parseSquare("a8")) revoke('q')
        }
        when (move.to) {
            parseSquare("h1") -> if (captured == 'R') revoke('K')
            parseSquare("a1") -> if (captured == 'R') revoke('Q')
            parseSquare("h8") -> if (captured == 'r') revoke('k')
            parseSquare("a8") -> if (captured == 'r') revoke('q')
        }
        val nextRights = rights.filter { it in "KQkq" }.let { it.ifEmpty { "-" } }
        val nextEp = if (piece.lowercaseChar() == 'p' && kotlin.math.abs(move.to - move.from) == 16)
            squareName((move.to + move.from) / 2) else "-"
        val half = if (piece.lowercaseChar() == 'p' || captured != null) 0 else (position.halfmoveClock ?: 0) + 1
        val full = (position.fullmoveNumber ?: 1) + if (!white) 1 else 0
        return ChessPosition(board, !white, nextRights, nextEp, half, full)
    }

    private fun inside(row: Int, col: Int) = row in 0..7 && col in 0..7
}
