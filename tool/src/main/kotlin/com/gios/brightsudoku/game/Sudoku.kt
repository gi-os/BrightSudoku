package com.gios.brightsudoku.game

/**
 * The board's fixed geometry, and candidate arithmetic.
 *
 * Free of Android imports on purpose. Everything that decides whether a puzzle is
 * fair — whether it has one solution, whether logic alone can finish it, how hard
 * that logic is — lives in this package, and all of it is worth testing on a JVM
 * rather than on a phone.
 *
 * Cells are indexed 0..80 in reading order. A digit is 1..9; 0 means empty.
 *
 * Candidates are a bitmask, bit `d` for digit `d`, so bit 0 is unused and a full
 * set is [ALL]. A set of nine digits fits in an Int, which is what makes the
 * solver cheap enough to run inside a generator loop on the phone: an elimination
 * is one `and`, and "how many candidates are left" is one `bitCount`.
 */
object Sudoku {

    const val N = 9
    const val CELLS = 81

    /** Every digit, as a candidate mask. Bit 0 is deliberately unused. */
    const val ALL = 0b1111111110

    fun bit(digit: Int): Int = 1 shl digit

    fun rowOf(cell: Int): Int = cell / N
    fun colOf(cell: Int): Int = cell % N
    fun boxOf(cell: Int): Int = (cell / N / 3) * 3 + (cell % N) / 3

    /**
     * The 27 units: nine rows, then nine columns, then nine boxes.
     *
     * Kept in that order because every technique below reports which unit it
     * fired in, and the hint text needs to name it ("this row", "this box").
     */
    val units: Array<IntArray> = Array(27) { u ->
        when {
            u < 9 -> IntArray(N) { i -> u * N + i }
            u < 18 -> IntArray(N) { i -> i * N + (u - 9) }
            else -> {
                val b = u - 18
                val r0 = (b / 3) * 3
                val c0 = (b % 3) * 3
                IntArray(N) { i -> (r0 + i / 3) * N + (c0 + i % 3) }
            }
        }
    }

    /** Human name for a unit index, for hint text. */
    fun unitName(unit: Int): String = when {
        unit < 9 -> "row"
        unit < 18 -> "column"
        else -> "box"
    }

    /** The three units each cell belongs to: its row, its column, its box. */
    val unitsOf: Array<IntArray> = Array(CELLS) { c ->
        intArrayOf(rowOf(c), 9 + colOf(c), 18 + boxOf(c))
    }

    /**
     * The 20 cells that share a unit with each cell.
     *
     * Precomputed rather than derived per lookup: placing a digit walks its peers,
     * and the generator places millions of digits across a run.
     */
    val peers: Array<IntArray> = Array(CELLS) { c ->
        val set = LinkedHashSet<Int>()
        for (u in unitsOf[c]) for (p in units[u]) if (p != c) set.add(p)
        set.toIntArray()
    }

    /** Digits in a candidate mask, ascending. */
    fun digitsOf(mask: Int): List<Int> {
        val out = ArrayList<Int>(Integer.bitCount(mask))
        for (d in 1..N) if (mask and bit(d) != 0) out.add(d)
        return out
    }

    /** The single digit in a one-bit mask, or 0. */
    fun soleDigit(mask: Int): Int =
        if (Integer.bitCount(mask) == 1) Integer.numberOfTrailingZeros(mask) else 0

    /**
     * Is this arrangement legal — no digit twice in any unit?
     *
     * Empty cells are ignored, so this answers "is this position still legal",
     * not "is it finished".
     */
    fun isLegal(cells: IntArray): Boolean {
        if (cells.size != CELLS) return false
        for (unit in units) {
            var seen = 0
            for (c in unit) {
                val d = cells[c]
                if (d == 0) continue
                if (d !in 1..N) return false
                val b = bit(d)
                if (seen and b != 0) return false
                seen = seen or b
            }
        }
        return true
    }

    /** Every cell filled, and legal. */
    fun isComplete(cells: IntArray): Boolean =
        cells.size == CELLS && cells.none { it == 0 } && isLegal(cells)

    /**
     * Cells that clash with [cell] — same unit, same digit.
     *
     * The board never refuses a move; it just draws the clash. So this is the
     * whole of "wrong" in the UI, and it deliberately says nothing about the
     * solution: a digit can conflict with nothing and still be wrong, and the
     * board leaves that for the player to discover.
     */
    fun conflictsWith(cells: IntArray, cell: Int): List<Int> {
        val d = cells[cell]
        if (d == 0) return emptyList()
        return peers[cell].filter { cells[it] == d }
    }
}
