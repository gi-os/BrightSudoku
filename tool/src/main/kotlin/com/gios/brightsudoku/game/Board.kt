package com.gios.brightsudoku.game

import com.gios.brightsudoku.game.Sudoku.CELLS
import com.gios.brightsudoku.game.Sudoku.bit
import com.gios.brightsudoku.gen.Puzzle

/**
 * Mutable play state for one puzzle: what the player has written, and their
 * pencil marks.
 *
 * Free of Android imports, like the rest of this package. Every rule that decides
 * how the board behaves under a thumb lives here so it can be tested without an
 * emulator.
 *
 * Nothing here is a referee. A digit that clashes with another is drawn as a
 * clash, and that is the whole of the app's opinion — it never refuses a move,
 * never counts mistakes and never ends the game. Same stance as the rest of the
 * collection: you are solving a puzzle, not being assessed.
 */
class Board(
    val puzzle: Puzzle,
    /**
     * When a digit goes in, rub it out of the pencil marks of every square that
     * can no longer hold it. On by default because doing it by hand is the dull
     * half of pencilled Sudoku, and off for players who want the marks to be
     * theirs alone.
     */
    private val autoClean: Boolean = true,
    restoreEntries: IntArray? = null,
    restorePencil: IntArray? = null,
) {
    /** The player's digits. A given's slot here stays 0 — givens live in the puzzle. */
    val entries = IntArray(CELLS)

    /** Pencil marks, one candidate bitmask per cell. */
    val pencil = IntArray(CELLS)

    init {
        restoreEntries?.let { if (it.size == CELLS) it.copyInto(entries) }
        restorePencil?.let { if (it.size == CELLS) it.copyInto(pencil) }
        // A restored save could carry an entry on a given if it were damaged in
        // just the wrong way. Givens win: they are the puzzle.
        for (i in 0 until CELLS) if (puzzle.isGiven(i)) { entries[i] = 0; pencil[i] = 0 }
    }

    // ---- reading ----------------------------------------------------------

    /** What is in a square, given or written. 0 for empty. */
    fun digitAt(cell: Int): Int = if (puzzle.isGiven(cell)) puzzle.givens[cell] else entries[cell]

    fun isGiven(cell: Int): Boolean = puzzle.isGiven(cell)

    fun pencilAt(cell: Int): Int = if (digitAt(cell) == 0) pencil[cell] else 0

    /** The grid as it stands, for the solver. */
    fun cells(): IntArray = IntArray(CELLS) { digitAt(it) }

    /** Squares holding a digit that appears twice in some unit. */
    fun conflicts(): Set<Int> {
        val out = HashSet<Int>()
        val cells = cells()
        for (unit in Sudoku.units) {
            val seen = HashMap<Int, Int>()
            for (c in unit) {
                val d = cells[c]
                if (d == 0) continue
                val prev = seen.put(d, c)
                if (prev != null) { out.add(c); out.add(prev) }
            }
        }
        return out
    }

    /**
     * Squares whose digit is not the answer.
     *
     * Only ever asked for when the player has turned checking on. A wrong digit
     * that clashes with nothing is invisible otherwise, and staying quiet about
     * it is the difference between a puzzle and a puzzle with training wheels —
     * so which of the two this is stays the player's choice, not the app's.
     */
    fun wrong(): Set<Int> =
        (0 until CELLS).filter { entries[it] != 0 && entries[it] != puzzle.solution[it] }.toSet()

    /** How many of each digit are on the board, for dimming a finished number. */
    fun digitCounts(): IntArray {
        val counts = IntArray(10)
        for (i in 0 until CELLS) counts[digitAt(i)]++
        return counts
    }

    val filledCount: Int get() = (0 until CELLS).count { digitAt(it) != 0 }

    /** Every square matches the answer. Pencil marks are ignored. */
    val isSolved: Boolean
        get() {
            for (i in 0 until CELLS) if (digitAt(i) != puzzle.solution[i]) return false
            return true
        }

    // ---- writing ----------------------------------------------------------

    /**
     * Write [digit] into a square, or clear it if that digit is already there.
     *
     * Tapping the digit that is already in the square erases it. That is the same
     * anchor rule the nonogram board uses for fills: a tap that would change
     * nothing means you meant to undo it, and it saves reaching for an erase
     * button on every correction.
     *
     * @return false if the square is a given, which is never writable.
     */
    fun place(cell: Int, digit: Int): Boolean {
        if (puzzle.isGiven(cell)) return false
        val move = Move(cell)
        move.entryWas = entries[cell]
        move.pencilWas = pencil[cell]

        if (entries[cell] == digit) {
            entries[cell] = 0
        } else {
            entries[cell] = digit
            pencil[cell] = 0
            if (autoClean) rubOutPeers(cell, digit, move)
        }
        commit(move)
        return true
    }

    /** Empty a square, marks and all. */
    fun erase(cell: Int): Boolean {
        if (puzzle.isGiven(cell)) return false
        if (entries[cell] == 0 && pencil[cell] == 0) return false
        val move = Move(cell)
        move.entryWas = entries[cell]
        move.pencilWas = pencil[cell]
        entries[cell] = 0
        pencil[cell] = 0
        commit(move)
        return true
    }

    /**
     * Add or remove one pencil mark.
     *
     * Refused on a square that already holds a digit: a mark under a written
     * digit is invisible and would come back the moment the digit was erased,
     * which reads as the board inventing marks.
     */
    fun togglePencil(cell: Int, digit: Int): Boolean {
        if (puzzle.isGiven(cell) || entries[cell] != 0) return false
        val move = Move(cell)
        move.entryWas = entries[cell]
        move.pencilWas = pencil[cell]
        pencil[cell] = pencil[cell] xor bit(digit)
        commit(move)
        return true
    }

    /**
     * Fill every empty square's pencil marks with what actually fits.
     *
     * One button rather than a mode, because the first thing anyone does with
     * pencil marks on a hard grid is write all of them out, and doing that by
     * thumb on a phone is twenty minutes of tapping.
     */
    fun fillPencilMarks(): Boolean {
        val candidates = Candidates.of(cells()) ?: return false
        val move = Move(-1)
        var changed = false
        for (i in 0 until CELLS) {
            if (digitAt(i) != 0) continue
            val wanted = candidates.cand[i]
            if (pencil[i] != wanted) {
                move.pencilAlso[i] = pencil[i]
                pencil[i] = wanted
                changed = true
            }
        }
        if (changed) commit(move)
        return changed
    }

    /** Strike [digit] from the marks of every square that shares a unit with [cell]. */
    private fun rubOutPeers(cell: Int, digit: Int, move: Move) {
        val b = bit(digit)
        for (p in Sudoku.peers[cell]) {
            if (pencil[p] and b == 0) continue
            move.pencilAlso.putIfAbsent(p, pencil[p])
            pencil[p] = pencil[p] and b.inv()
        }
    }

    // ---- undo -------------------------------------------------------------

    /**
     * One move's worth of "what it was before".
     *
     * Placing a digit can touch a dozen squares once auto-clean rubs it out of
     * their marks, and all of that has to come back together — an undo that left
     * the marks rubbed out would quietly destroy the player's work.
     */
    private class Move(val cell: Int) {
        var entryWas: Int = 0
        var pencilWas: Int = 0
        val pencilAlso = HashMap<Int, Int>()
    }

    private val undoStack = ArrayList<Move>()

    /**
     * Deep enough to walk back out of a wrong turn, bounded so a long session
     * cannot grow without limit. The same number BrightSolitaire keeps.
     */
    private val undoLimit = 120

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    private fun commit(move: Move) {
        undoStack.add(move)
        if (undoStack.size > undoLimit) undoStack.removeAt(0)
    }

    fun undo(): Boolean {
        val move = undoStack.removeLastOrNull() ?: return false
        if (move.cell >= 0) {
            entries[move.cell] = move.entryWas
            pencil[move.cell] = move.pencilWas
        }
        for ((c, marks) in move.pencilAlso) pencil[c] = marks
        return true
    }

    /** Back to the clues, throwing away everything written since. */
    fun restart() {
        entries.fill(0)
        pencil.fill(0)
        undoStack.clear()
    }

    // ---- help -------------------------------------------------------------

    /**
     * The next thing logic can say about the board as the player has left it.
     *
     * Deliberately computed from the real grid rather than from the answer, so a
     * hint stays true after a wrong digit: it reasons about the position in front
     * of the player. That does mean a board with a mistake on it can run out of
     * hints, which is itself worth knowing — [Solver.nextStep] returning null on a
     * puzzle this app dealt means something already written is wrong.
     */
    fun hint(): Step? = Solver.nextStep(cells())
}
