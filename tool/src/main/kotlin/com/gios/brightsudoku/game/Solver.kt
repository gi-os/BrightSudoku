package com.gios.brightsudoku.game

import com.gios.brightsudoku.game.Sudoku.ALL
import com.gios.brightsudoku.game.Sudoku.CELLS
import com.gios.brightsudoku.game.Sudoku.N
import com.gios.brightsudoku.game.Sudoku.bit
import com.gios.brightsudoku.game.Sudoku.peers
import com.gios.brightsudoku.game.Sudoku.units

/**
 * The techniques the app knows, hardest last.
 *
 * This list is not decoration. It is the definition of "solvable" that the
 * generator enforces: a puzzle it cannot finish with these is thrown away, so
 * every puzzle the app deals can be reasoned out to the end. Nothing here
 * guesses, and neither does the player.
 *
 * [rank] is what grades a puzzle — the hardest technique its solution actually
 * needs. Adding a technique to this ladder therefore makes some existing seeds
 * easier, which is why [Generate.ALGORITHM_VERSION] moves when this list does.
 */
enum class Technique(val rank: Int, val label: String) {
    /** One candidate left in a cell. */
    NAKED_SINGLE(0, "Last one free"),

    /** One place left in a unit for a digit. */
    HIDDEN_SINGLE(1, "Only square"),

    /** A digit confined to one row or column inside a box, or one box inside a line. */
    LOCKED(2, "Locked digit"),

    /** k cells in a unit holding exactly k digits between them. */
    NAKED_SUBSET(3, "Matched pair"),

    /** k digits in a unit confined to exactly k cells. */
    HIDDEN_SUBSET(4, "Hidden pair"),

    /** A digit forming a rectangle across two rows and two columns. */
    X_WING(5, "X-wing"),

    /** Two-candidate pincers either side of a two-candidate pivot. */
    XY_WING(6, "XY-wing"),

    /** An x-wing with three lines instead of two. */
    SWORDFISH(7, "Swordfish"),
    ;

    companion object {
        /** The ladder, easiest first. The solver always takes the cheapest step available. */
        val ladder: List<Technique> = entries.sortedBy { it.rank }
    }
}

/**
 * One deduction, with enough context to explain it.
 *
 * A hint that only says "put a 4 here" teaches nothing and is indistinguishable
 * from the app playing for you. Carrying the unit it fired in and the cells that
 * forced it lets the board show the reasoning instead of just the answer.
 *
 * @param cell      the cell a digit goes into, or -1 for a step that only eliminates
 * @param digit     the digit placed, or the digit eliminated
 * @param unit      the unit the deduction fired in, or -1
 * @param because   cells that support it, for highlighting
 * @param eliminated cells this step removes [digit] from, for an elimination step
 */
data class Step(
    val technique: Technique,
    val cell: Int,
    val digit: Int,
    val unit: Int = -1,
    val because: List<Int> = emptyList(),
    val eliminated: List<Int> = emptyList(),
) {
    val isPlacement: Boolean get() = cell >= 0

    /** One line, in the app's voice: what to do and why it follows. */
    fun explain(): String = when (technique) {
        Technique.NAKED_SINGLE ->
            "Only $digit can go here — every other digit is already in this square's row, column or box."
        Technique.HIDDEN_SINGLE ->
            "$digit fits nowhere else in this ${Sudoku.unitName(unit)}, so it goes here."
        Technique.LOCKED ->
            "In this ${Sudoku.unitName(unit)}, $digit is stuck on the marked line, so it can be ruled out further along it."
        Technique.NAKED_SUBSET ->
            "The marked squares share ${because.size} digits between them, so those digits are used up here."
        Technique.HIDDEN_SUBSET ->
            "Those digits fit only in the marked squares, so nothing else fits there."
        Technique.X_WING ->
            "$digit sits at the corners of a rectangle, so it can be ruled out elsewhere in those lines."
        Technique.XY_WING ->
            "Whichever way the marked pivot goes, one of its two partners takes $digit — so nothing that sees both can."
        Technique.SWORDFISH ->
            "$digit is pinned to the same three lines whichever way it falls, so it can be ruled out elsewhere in them."
    }
}

/** What a logical solve came to. */
data class LogicResult(
    val solved: Boolean,
    /** The hardest technique the solve needed, or null if it needed none. */
    val hardest: Technique?,
    /** How many times each technique fired. */
    val used: Map<Technique, Int>,
    /** The position it reached — solved, or as far as logic got. */
    val cells: IntArray,
) {
    // Generated equals/hashCode would compare `cells` by identity. Nothing relies
    // on comparing results, so the honest move is to not pretend they compare.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Candidate state for a position: what is placed, and what could still go where.
 *
 * Mutable and reused, because the generator runs this thousands of times per
 * puzzle and allocating two arrays per attempt is most of the cost.
 */
class Candidates private constructor(
    val value: IntArray,
    val cand: IntArray,
) {
    companion object {
        /**
         * Build candidates for a position, or null if the position is already
         * broken — a repeated digit in a unit, or an empty cell nothing fits in.
         */
        fun of(cells: IntArray): Candidates? {
            val c = Candidates(IntArray(CELLS), IntArray(CELLS) { ALL })
            for (i in 0 until CELLS) {
                val d = cells[i]
                if (d != 0 && !c.place(i, d)) return null
            }
            return c
        }
    }

    fun copy(): Candidates = Candidates(value.copyOf(), cand.copyOf())

    /**
     * Put [digit] in [cell] and strike it from every peer.
     *
     * @return false if that leaves a peer with nothing, which means the position
     *   is dead and the caller should back out.
     */
    fun place(cell: Int, digit: Int): Boolean {
        val b = bit(digit)
        if (value[cell] != 0) return value[cell] == digit
        if (cand[cell] and b == 0) return false
        value[cell] = digit
        cand[cell] = 0
        for (p in peers[cell]) {
            if (value[p] == digit) return false
            if (cand[p] and b != 0) {
                cand[p] = cand[p] and b.inv()
                if (value[p] == 0 && cand[p] == 0) return false
            }
        }
        return true
    }

    /** Strike [digit] from [cell]. False if that empties it. */
    fun eliminate(cell: Int, digit: Int): Boolean {
        val b = bit(digit)
        if (value[cell] != 0 || cand[cell] and b == 0) return true
        cand[cell] = cand[cell] and b.inv()
        return cand[cell] != 0
    }

    val isSolved: Boolean get() = value.none { it == 0 }

    fun snapshot(): IntArray = value.copyOf()
}

/**
 * Everything the app knows about deciding a Sudoku.
 *
 * Two jobs, deliberately kept apart:
 *
 * - [countSolutions] is search. It guesses, backtracks and answers "how many
 *   ways does this finish". The generator uses it to prove a puzzle has exactly
 *   one answer.
 * - [solveLogically] and [nextStep] never guess. They apply the [Technique]
 *   ladder and stop when it runs out. The generator uses that to prove a puzzle
 *   can actually be reasoned out, and the board uses it for hints.
 *
 * A puzzle has to pass both. One solution is not enough on its own — plenty of
 * unique puzzles can only be finished by trial and error, and dealing one of
 * those to someone on a phone would be a small act of hostility.
 */
object Solver {

    /**
     * How many ways this position finishes, counted up to [limit].
     *
     * Stops as soon as it reaches [limit], so uniqueness costs two solutions'
     * worth of search rather than an exhaustive walk. The generator asks with
     * `limit = 2` after every clue it removes, which is the hot path of the
     * whole app.
     */
    fun countSolutions(cells: IntArray, limit: Int = 2): Int {
        val c = Candidates.of(cells) ?: return 0
        return count(c, limit)
    }

    /** The one solution, or null if there are none or more than one. */
    fun uniqueSolution(cells: IntArray): IntArray? {
        val c = Candidates.of(cells) ?: return null
        var found: IntArray? = null
        var seen = 0
        walk(c, 2) { solution ->
            if (seen == 0) found = solution.copyOf()
            seen++
        }
        return if (seen == 1) found else null
    }

    private fun count(c: Candidates, limit: Int): Int {
        var n = 0
        walk(c, limit) { n++ }
        return n
    }

    /**
     * Depth-first search with singles propagated at every node.
     *
     * Branching on the cell with the fewest candidates is what keeps this
     * cheap: on a real puzzle the propagation usually finishes on its own, and
     * where it does not, the first branch point rarely has more than two ways to
     * go.
     */
    private fun walk(c: Candidates, limit: Int, onSolution: (IntArray) -> Unit) {
        var found = 0

        fun recurse(state: Candidates): Boolean {
            if (!propagateSingles(state)) return false
            if (state.isSolved) {
                onSolution(state.value)
                found++
                return found >= limit
            }
            var best = -1
            var bestCount = 10
            for (i in 0 until CELLS) {
                if (state.value[i] != 0) continue
                val n = Integer.bitCount(state.cand[i])
                if (n < bestCount) {
                    bestCount = n
                    best = i
                    if (n == 2) break
                }
            }
            if (best < 0) return false
            for (d in Sudoku.digitsOf(state.cand[best])) {
                val branch = state.copy()
                if (branch.place(best, d) && recurse(branch)) return true
            }
            return false
        }

        recurse(c.copy())
    }

    /**
     * Fill in everything forced by naked and hidden singles.
     *
     * Used inside the search, where the point is speed rather than explanation,
     * so it reports nothing but success or contradiction.
     */
    private fun propagateSingles(c: Candidates): Boolean {
        var changed = true
        while (changed) {
            changed = false
            for (i in 0 until CELLS) {
                if (c.value[i] != 0) continue
                val m = c.cand[i]
                if (m == 0) return false
                val d = Sudoku.soleDigit(m)
                if (d != 0) {
                    if (!c.place(i, d)) return false
                    changed = true
                }
            }
            for (unit in units) {
                for (d in 1..N) {
                    val b = bit(d)
                    var where = -1
                    var count = 0
                    var already = false
                    for (cell in unit) {
                        if (c.value[cell] == d) { already = true; break }
                        if (c.value[cell] == 0 && c.cand[cell] and b != 0) { where = cell; count++ }
                    }
                    if (already) continue
                    if (count == 0) return false
                    if (count == 1) {
                        if (!c.place(where, d)) return false
                        changed = true
                    }
                }
            }
        }
        return true
    }

    /**
     * Solve by logic alone, recording what it took.
     *
     * Always takes the easiest available step, which is what makes the grade
     * meaningful: a puzzle is only graded hard if the hard technique was
     * genuinely unavoidable at that point, not merely one option among several.
     */
    fun solveLogically(
        cells: IntArray,
        /**
         * The hardest technique this solve is allowed to use. Anything above it is
         * not searched for at all, and the solve simply stops.
         *
         * This is what makes the generator affordable. Grading a position mostly
         * means proving it is *too hard*, and without a ceiling that proof costs a
         * full search for every technique on the ladder — including the fish and
         * wing searches, which are the expensive ones. With a ceiling of, say,
         * locked digits, a position that needs a subset is rejected the moment
         * the cheap rungs run dry, and the expensive ones are never run.
         */
        ceiling: Technique? = null,
        maxSteps: Int = 2000,
    ): LogicResult {
        val c = Candidates.of(cells)
            ?: return LogicResult(false, null, emptyMap(), cells.copyOf())
        val used = HashMap<Technique, Int>()
        var hardest: Technique? = null
        var steps = 0

        while (!c.isSolved && steps++ < maxSteps) {
            val step = nextStep(c, ceiling) ?: break
            if (!apply(c, step)) return LogicResult(false, hardest, used, c.snapshot())
            used[step.technique] = (used[step.technique] ?: 0) + 1
            if (hardest == null || step.technique.rank > hardest.rank) hardest = step.technique
        }
        return LogicResult(c.isSolved, hardest, used, c.snapshot())
    }

    /** Carry out a step. False means the step contradicted itself, which is a bug. */
    private fun apply(c: Candidates, step: Step): Boolean {
        if (step.isPlacement) return c.place(step.cell, step.digit)
        var ok = true
        for (cell in step.eliminated) ok = c.eliminate(cell, step.digit) && ok
        return ok
    }

    /** The next deduction available in a position, or null if logic is stuck. */
    fun nextStep(cells: IntArray): Step? = Candidates.of(cells)?.let { nextStep(it) }

    /**
     * The next deduction, cheapest technique first.
     *
     * The ladder is walked in order rather than "whatever fires" so that a hint
     * is always the simplest thing available. Being shown an x-wing when a cell
     * three squares away has one candidate left would be worse than no hint.
     */
    fun nextStep(c: Candidates, ceiling: Technique? = null): Step? {
        for (technique in Technique.ladder) {
            if (ceiling != null && technique.rank > ceiling.rank) return null
            val step = when (technique) {
                Technique.NAKED_SINGLE -> nakedSingle(c)
                Technique.HIDDEN_SINGLE -> hiddenSingle(c)
                Technique.LOCKED -> locked(c)
                Technique.NAKED_SUBSET -> nakedSubset(c)
                Technique.HIDDEN_SUBSET -> hiddenSubset(c)
                Technique.X_WING -> fish(c, size = 2, Technique.X_WING)
                Technique.XY_WING -> xyWing(c)
                Technique.SWORDFISH -> fish(c, size = 3, Technique.SWORDFISH)
            }
            if (step != null) return step
        }
        return null
    }

    // ---- the ladder -------------------------------------------------------

    private fun nakedSingle(c: Candidates): Step? {
        for (i in 0 until CELLS) {
            if (c.value[i] != 0) continue
            val d = Sudoku.soleDigit(c.cand[i])
            if (d != 0) return Step(Technique.NAKED_SINGLE, i, d, Sudoku.unitsOf[i][2])
        }
        return null
    }

    private fun hiddenSingle(c: Candidates): Step? {
        for ((u, unit) in units.withIndex()) {
            for (d in 1..N) {
                val b = bit(d)
                if (unit.any { c.value[it] == d }) continue
                var where = -1
                var count = 0
                for (cell in unit) {
                    if (c.value[cell] == 0 && c.cand[cell] and b != 0) { where = cell; count++ }
                }
                if (count == 1) {
                    return Step(
                        technique = Technique.HIDDEN_SINGLE,
                        cell = where,
                        digit = d,
                        unit = u,
                        because = unit.filter { it != where },
                    )
                }
            }
        }
        return null
    }

    /**
     * Locked candidates, both directions.
     *
     * Pointing: a digit's places inside a box all sit in one row or column, so it
     * leaves that line alone elsewhere. Claiming: a digit's places inside a line
     * all sit in one box, so it leaves the rest of the box alone.
     */
    private fun locked(c: Candidates): Step? {
        for ((u, unit) in units.withIndex()) {
            for (d in 1..N) {
                val b = bit(d)
                if (unit.any { c.value[it] == d }) continue
                val places = unit.filter { c.value[it] == 0 && c.cand[it] and b != 0 }
                if (places.size < 2) continue

                // Which other unit, if any, contains every one of those places.
                val shared = Sudoku.unitsOf[places[0]].filter { other ->
                    other != u && places.all { p -> other in Sudoku.unitsOf[p] }
                }
                for (other in shared) {
                    val kill = units[other].filter {
                        it !in places && c.value[it] == 0 && c.cand[it] and b != 0
                    }
                    if (kill.isNotEmpty()) {
                        return Step(
                            technique = Technique.LOCKED,
                            cell = -1,
                            digit = d,
                            unit = u,
                            because = places,
                            eliminated = kill,
                        )
                    }
                }
            }
        }
        return null
    }

    /**
     * Naked pairs and triples.
     *
     * k cells in a unit whose candidates come to exactly k digits between them
     * must use up those digits, so nothing else in the unit can have them.
     * Quads are left out: they are vanishingly rare at these grades and every one
     * this generator produced had an easier route available anyway.
     */
    private fun nakedSubset(c: Candidates): Step? {
        for ((u, unit) in units.withIndex()) {
            val open = unit.filter { c.value[it] == 0 }
            for (k in 2..3) {
                if (open.size <= k) continue
                val step = forEachSubset(open, k) { subset ->
                    var mask = 0
                    for (cell in subset) mask = mask or c.cand[cell]
                    if (Integer.bitCount(mask) != k) return@forEachSubset null
                    val kill = ArrayList<Int>()
                    var digit = 0
                    for (cell in open) {
                        if (cell in subset) continue
                        val hit = c.cand[cell] and mask
                        if (hit != 0) {
                            kill.add(cell)
                            if (digit == 0) digit = Sudoku.digitsOf(hit).first()
                        }
                    }
                    if (kill.isEmpty()) null
                    else Step(Technique.NAKED_SUBSET, -1, digit, u, subset, kill)
                }
                if (step != null) return step
            }
        }
        return null
    }

    /**
     * Hidden pairs and triples: k digits in a unit that fit only in the same k
     * cells. Those cells can hold nothing else.
     */
    private fun hiddenSubset(c: Candidates): Step? {
        for ((u, unit) in units.withIndex()) {
            val open = unit.filter { c.value[it] == 0 }
            if (open.size < 4) continue
            val places = HashMap<Int, List<Int>>()
            for (d in 1..N) {
                if (unit.any { c.value[it] == d }) continue
                val where = open.filter { c.cand[it] and bit(d) != 0 }
                if (where.size in 2..3) places[d] = where
            }
            val digits = places.keys.toList()
            for (k in 2..3) {
                if (digits.size < k) continue
                val step = forEachSubset(digits, k) { subset ->
                    val cells = LinkedHashSet<Int>()
                    for (d in subset) cells.addAll(places.getValue(d))
                    if (cells.size != k) return@forEachSubset null
                    var keep = 0
                    for (d in subset) keep = keep or bit(d)
                    val kill = cells.filter { c.cand[it] and keep.inv() and ALL != 0 }
                    if (kill.isEmpty()) return@forEachSubset null
                    // Report one of the digits actually being removed, so the
                    // board highlights something the player can see.
                    val extra = Sudoku.digitsOf(c.cand[kill[0]] and keep.inv() and ALL)
                    Step(Technique.HIDDEN_SUBSET, -1, extra.first(), u, cells.toList(), kill)
                }
                if (step != null) return step
            }
        }
        return null
    }

    /**
     * X-wing and swordfish, which are the same argument at two sizes.
     *
     * Take [size] rows in which a digit has at most [size] possible squares, and
     * suppose those squares between them occupy exactly [size] columns. Each of
     * those rows needs the digit somewhere, and all the room they have is in
     * those columns — so the digit fills one square per column and has none left
     * over for any other row. It can be struck from those columns everywhere
     * else. Then the same with rows and columns swapped.
     *
     * Written once for both sizes rather than twice: a swordfish is not a
     * different idea from an x-wing, and two copies of this would be two places
     * for the off-by-one to hide.
     */
    private fun fish(c: Candidates, size: Int, technique: Technique): Step? {
        for (transposed in listOf(false, true)) {
            for (d in 1..N) {
                val b = bit(d)
                fun cellAt(line: Int, pos: Int) = if (transposed) pos * N + line else line * N + pos

                // Where the digit can still go in each line, as positions along it.
                val places = Array(N) { line ->
                    (0 until N).filter { pos ->
                        val cell = cellAt(line, pos)
                        c.value[cell] == 0 && c.cand[cell] and b != 0
                    }
                }
                // A line with one place is a hidden single and belongs to an
                // earlier rung; one with more places than the fish is too loose
                // to be pinned by it.
                val usable = (0 until N).filter { places[it].size in 2..size }
                if (usable.size < size) continue

                val step = forEachSubset(usable, size) { lines ->
                    val columns = LinkedHashSet<Int>()
                    for (line in lines) columns.addAll(places[line])
                    if (columns.size != size) return@forEachSubset null

                    val kill = ArrayList<Int>()
                    for (line in 0 until N) {
                        if (line in lines) continue
                        for (pos in columns) {
                            val cell = cellAt(line, pos)
                            if (c.value[cell] == 0 && c.cand[cell] and b != 0) kill.add(cell)
                        }
                    }
                    if (kill.isEmpty()) return@forEachSubset null

                    val corners = lines.flatMap { line -> places[line].map { cellAt(line, it) } }
                    Step(technique, -1, d, -1, corners, kill)
                }
                if (step != null) return step
            }
        }
        return null
    }

    /**
     * XY-wing.
     *
     * A pivot square holding exactly {x, y}, and two squares it can see holding
     * exactly {x, z} and {y, z}. The pivot is x or y; either way one of the two
     * partners is forced to z. So any square that can see both partners cannot be
     * z, whichever way the pivot turns out.
     *
     * This is the first rung that reasons about a square without knowing what
     * goes in it, which is why it sits above the subsets: it is the point where
     * the puzzle stops being bookkeeping.
     */
    private fun xyWing(c: Candidates): Step? {
        val pairs = (0 until CELLS).filter { c.value[it] == 0 && Integer.bitCount(c.cand[it]) == 2 }
        for (pivot in pairs) {
            val (x, y) = Sudoku.digitsOf(c.cand[pivot])
            val wings = pairs.filter { it != pivot && it in peers[pivot] }
            for (a in wings) {
                val maskA = c.cand[a]
                // One digit shared with the pivot, one not: that is what makes it
                // a pincer rather than just another pair.
                val sharedA = if (maskA and bit(x) != 0) x else if (maskA and bit(y) != 0) y else continue
                val z = Sudoku.digitsOf(maskA and bit(sharedA).inv()).firstOrNull() ?: continue
                if (z == x || z == y) continue
                val other = if (sharedA == x) y else x
                for (e in wings) {
                    if (e == a) continue
                    if (c.cand[e] != bit(other) or bit(z)) continue
                    val kill = (0 until CELLS).filter {
                        it != a && it != e && it != pivot &&
                            c.value[it] == 0 && c.cand[it] and bit(z) != 0 &&
                            it in peers[a] && it in peers[e]
                    }
                    if (kill.isNotEmpty()) {
                        return Step(Technique.XY_WING, -1, z, -1, listOf(pivot, a, e), kill)
                    }
                }
            }
        }
        return null
    }

    /**
     * Walk every k-sized subset of [items], stopping at the first non-null result.
     *
     * A bitmask walk rather than recursion: a unit never has more than nine open
     * cells, so this is 512 cheap iterations, and it keeps the two subset
     * techniques above readable.
     */
    private inline fun <T, R> forEachSubset(items: List<T>, k: Int, body: (List<T>) -> R?): R? {
        val n = items.size
        if (n > 30) return null
        for (mask in 1 until (1 shl n)) {
            if (Integer.bitCount(mask) != k) continue
            val subset = ArrayList<T>(k)
            for (i in 0 until n) if (mask and (1 shl i) != 0) subset.add(items[i])
            val r = body(subset)
            if (r != null) return r
        }
        return null
    }
}
