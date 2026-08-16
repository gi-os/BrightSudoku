package com.gios.brightsudoku.game

import com.gios.brightsudoku.game.Sudoku.CELLS
import com.gios.brightsudoku.game.Sudoku.N
import com.gios.brightsudoku.game.Sudoku.bit
import com.gios.brightsudoku.gen.Puzzle
import java.util.Base64

/**
 * A game in progress, as one line of text.
 *
 * The clues and the answer are written out rather than regenerated from the
 * seed. Generation takes long enough that reproducing it on every launch would
 * put a visible pause between tapping the tool and seeing your board, and the
 * whole payload is still under 400 characters — small enough to sit in a
 * preference and be rewritten after every digit.
 *
 * Everything [decode] returns has been checked: the right number of squares, the
 * right characters, clues that do not repeat inside a unit, and an answer that
 * really is an answer to those clues. Anything else decodes to null, and the app
 * deals a fresh puzzle rather than opening a broken one.
 */
data class SaveState(
    val seed: Int,
    val difficulty: Difficulty,
    val givens: IntArray,
    val solution: IntArray,
    val entries: IntArray,
    val pencil: IntArray,
) {
    fun encode(): String = listOf(
        VERSION,
        seed.toString(),
        difficulty.label,
        digits(givens),
        digits(solution),
        digits(entries),
        packPencil(pencil),
    ).joinToString(SEP)

    /** Rebuild the puzzle this save was made from. */
    fun puzzle(): Puzzle = Puzzle(seed, difficulty, givens, solution)

    /** How far along it was, for a Continue line that says something. */
    val filledCount: Int
        get() = (0 until CELLS).count { givens[it] != 0 || entries[it] != 0 }

    // IntArray fields make the generated versions compare by identity, which
    // would quietly make every save look distinct. Compared by content instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SaveState) return false
        return seed == other.seed &&
            difficulty == other.difficulty &&
            givens.contentEquals(other.givens) &&
            solution.contentEquals(other.solution) &&
            entries.contentEquals(other.entries) &&
            pencil.contentEquals(other.pencil)
    }

    override fun hashCode(): Int {
        var h = seed
        h = 31 * h + difficulty.hashCode()
        h = 31 * h + givens.contentHashCode()
        h = 31 * h + solution.contentHashCode()
        h = 31 * h + entries.contentHashCode()
        h = 31 * h + pencil.contentHashCode()
        return h
    }

    companion object {
        private const val VERSION = "1"

        /**
         * Field separator. Every field is digits, base64, or a grade label made of
         * letters, so a pipe cannot occur inside one.
         */
        private const val SEP = "|"

        /** Capture a board mid-solve. */
        fun of(board: Board): SaveState = SaveState(
            seed = board.puzzle.seed,
            difficulty = board.puzzle.difficulty,
            givens = board.puzzle.givens.copyOf(),
            solution = board.puzzle.solution.copyOf(),
            entries = board.entries.copyOf(),
            pencil = board.pencil.copyOf(),
        )

        fun decode(raw: String?): SaveState? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(SEP)
            if (parts.size != 7 || parts[0] != VERSION) return null

            val seed = parts[1].toIntOrNull() ?: return null
            val difficulty = Difficulty.byLabel(parts[2]) ?: return null
            val givens = parseDigits(parts[3]) ?: return null
            val solution = parseDigits(parts[4]) ?: return null
            val entries = parseDigits(parts[5]) ?: return null
            val pencil = unpackPencil(parts[6]) ?: return null

            // The clues have to be a legal position, and the answer has to be a
            // finished grid that agrees with them. A save failing either is not a
            // puzzle at a different version — it is nonsense, and playing it would
            // hand someone a board with no ending.
            if (!Sudoku.isLegal(givens)) return null
            if (!Sudoku.isComplete(solution)) return null
            for (i in 0 until CELLS) {
                if (givens[i] != 0 && givens[i] != solution[i]) return null
            }
            // An entry on top of a clue, or a digit outside 1..9, means the save
            // was mangled rather than merely stale.
            for (i in 0 until CELLS) {
                if (entries[i] != 0 && givens[i] != 0) return null
            }

            return SaveState(seed, difficulty, givens, solution, entries, pencil)
        }

        private fun digits(cells: IntArray): String {
            val sb = StringBuilder(CELLS)
            for (d in cells) sb.append(('0' + d))
            return sb.toString()
        }

        private fun parseDigits(s: String): IntArray? {
            if (s.length != CELLS) return null
            val out = IntArray(CELLS)
            for (i in 0 until CELLS) {
                val ch = s[i]
                if (ch < '0' || ch > '9') return null
                out[i] = ch - '0'
            }
            return out
        }

        /**
         * Nine bits per square, base64'd — 92 bytes, about 124 characters.
         *
         * A digit-per-mark encoding would be nearly a thousand characters on a
         * fully pencilled grid, which is the state this gets written in most
         * often.
         */
        private fun packPencil(pencil: IntArray): String {
            val bits = ByteArray((CELLS * N + 7) / 8)
            for (cell in 0 until CELLS) {
                for (d in 1..N) {
                    if (pencil[cell] and bit(d) == 0) continue
                    val index = cell * N + (d - 1)
                    bits[index / 8] = (bits[index / 8].toInt() or (0x80 ushr (index % 8))).toByte()
                }
            }
            return Base64.getEncoder().encodeToString(bits)
        }

        private fun unpackPencil(s: String): IntArray? {
            val bytes = runCatching { Base64.getDecoder().decode(s) }.getOrNull() ?: return null
            if (bytes.size < (CELLS * N + 7) / 8) return null
            val out = IntArray(CELLS)
            for (cell in 0 until CELLS) {
                for (d in 1..N) {
                    val index = cell * N + (d - 1)
                    if (bytes[index / 8].toInt() and (0x80 ushr (index % 8)) != 0) {
                        out[cell] = out[cell] or bit(d)
                    }
                }
            }
            return out
        }
    }
}
