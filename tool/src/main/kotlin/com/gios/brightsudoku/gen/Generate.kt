package com.gios.brightsudoku.gen

import com.gios.brightsudoku.game.Difficulty
import com.gios.brightsudoku.game.Solver
import com.gios.brightsudoku.game.Sudoku
import com.gios.brightsudoku.game.Sudoku.CELLS
import com.gios.brightsudoku.game.Sudoku.N
import kotlin.random.Random

/**
 * A dealt puzzle: the clues, the answer, and what it took to get there.
 *
 * [solution] is carried rather than recomputed because the board needs it on
 * every keystroke — to know when the grid is finished — and re-solving on each
 * one would be silly. It is proven unique before this ever exists.
 */
class Puzzle(
    val seed: Int,
    val difficulty: Difficulty,
    val givens: IntArray,
    val solution: IntArray,
) {
    val clueCount: Int get() = givens.count { it != 0 }

    fun isGiven(cell: Int): Boolean = givens[cell] != 0
}

/**
 * On-device puzzle generation.
 *
 * No puzzle data ships in the APK and none is fetched. A puzzle is a seed and a
 * grade — two small numbers — which is why "puzzle #48210" can be typed in, shared
 * or replayed with nothing stored.
 *
 * Every puzzle this returns has passed two separate tests:
 *
 * 1. **Exactly one solution**, proven by counting solutions with a bound of two
 *    after every clue removed. Not "probably one" — counted.
 * 2. **Solvable by logic alone**, proven by [Solver.solveLogically] finishing it
 *    with the technique ladder, no guessing anywhere. A puzzle with a unique
 *    answer that can only be found by trial and error is thrown away.
 *
 * The second test is the one most generators skip, and it is the difference
 * between a puzzle that is hard and a puzzle that is unfair.
 */
object Generate {

    /**
     * Bump when the algorithm — or the technique ladder that grades it — changes.
     *
     * Seeds are the app's only puzzle storage, so a change here means every
     * previously shared seed deals a different puzzle. It is a breaking change to
     * the one thing the app asks people to write down.
     */
    const val ALGORITHM_VERSION = 1

    /**
     * The puzzle for a seed and a grade, or null if the attempt budget ran out.
     *
     * Deterministic: the same (seed, difficulty) always yields the same puzzle.
     * Each attempt derives its own stream from the seed, so attempt 7 of seed 12
     * is a fixed thing rather than a function of how the machine felt.
     *
     * Null is rare and real. Severe asks for an x-wing to be genuinely
     * unavoidable, and plenty of layouts simply never need one.
     */
    fun fromSeed(
        seed: Int,
        difficulty: Difficulty = Difficulty.default,
        maxAttempts: Int = 30,
        symmetric: Boolean = true,
    ): Puzzle? {
        repeat(maxAttempts) { attempt ->
            // A fresh stream per attempt, derived from the seed rather than from
            // the clock, so the whole search stays reproducible.
            val rng = Random(seed * 31 + attempt * 7919)
            val puzzle = attempt(seed, difficulty, rng, symmetric)
            if (puzzle != null) return puzzle
        }
        return null
    }

    /**
     * A new puzzle at [difficulty], starting the search at [startSeed].
     *
     * Not every layout can be made to land in every band — the Tricky band in
     * particular is narrow, and about a third of seeds never hit it. For a new
     * game that does not matter in the slightest, because a seed costs nothing:
     * if this one will not do it, the next one will. Walking seeds rather than
     * grinding attempts on one is what keeps a "New puzzle" tap from ever
     * returning nothing.
     *
     * Seeds are consecutive rather than random so the walk is reproducible, and
     * the seed that actually produced the puzzle is the one carried on it.
     */
    fun nextFrom(
        startSeed: Int,
        difficulty: Difficulty = Difficulty.default,
        seedsToTry: Int = 8,
        maxAttempts: Int = 30,
    ): Puzzle? {
        for (i in 0 until seedsToTry) {
            // Keep seeds positive: they get shown to the player and typed back in.
            val seed = (startSeed + i) and 0x7FFFFFFF
            fromSeed(seed, difficulty, maxAttempts)?.let { return it }
        }
        return null
    }

    /**
     * The puzzle for a seed the player asked for by name, at the nearest grade
     * this seed can manage.
     *
     * A typed seed is a request for *that* seed, so walking to a different one is
     * not an option here the way it is for [nextFrom]. Walking down the grades is
     * — and it stays honest, because the puzzle is labelled with the grade it
     * turned out to be, not the one that was asked for.
     */
    fun bestEffort(
        seed: Int,
        difficulty: Difficulty = Difficulty.default,
        maxAttempts: Int = 30,
    ): Puzzle? {
        fromSeed(seed, difficulty, maxAttempts)?.let { return it }
        for (easier in Difficulty.entries.reversed()) {
            if (easier.ceiling.rank >= difficulty.ceiling.rank) continue
            fromSeed(seed, easier, maxAttempts)?.let { return it }
        }
        // Every grade refused this seed, which takes a run of bad luck. Harder
        // grades are the ones that fail, so try up the ladder before giving up.
        for (harder in Difficulty.entries) {
            if (harder.ceiling.rank <= difficulty.ceiling.rank) continue
            fromSeed(seed, harder, maxAttempts)?.let { return it }
        }
        return null
    }

    /**
     * One layout, dug out and then handed back clues until it grades where it was
     * asked to.
     *
     * Digging to a clue count and hoping is the obvious way to do this and it
     * does not work. Measured over 640 layouts, hidden singles alone finish most
     * grids right down to 24 clues, so a fixed depth produces Gentle puzzles
     * almost whatever you ask for, and the hardest grades essentially never
     * appear. Difficulty is a property of the layout, not of how many clues you
     * took away.
     *
     * So each layout is dug as far as it will go and then walked back up: a
     * restored clue can only make a puzzle easier, so putting them back one pair
     * at a time steps down through the grades, and the walk stops on the one that
     * was asked for. That turns a layout into a whole spectrum of puzzles rather
     * than one sample from it, which is what makes the harder grades reachable at
     * all.
     */
    private fun attempt(
        seed: Int,
        difficulty: Difficulty,
        rng: Random,
        symmetric: Boolean,
    ): Puzzle? {
        val solution = completeGrid(rng) ?: return null
        val givens = solution.copyOf()

        // Dig in rotationally symmetric pairs — the traditional look, and one
        // uniqueness check buys two clues.
        val order = (0 until CELLS).shuffled(rng)
        val removed = BooleanArray(CELLS)
        val history = ArrayList<List<Int>>()
        var clues = CELLS

        for (cell in order) {
            if (clues <= difficulty.clueFloor) break
            if (removed[cell]) continue
            val mate = CELLS - 1 - cell
            val pair = if (symmetric && mate != cell && !removed[mate]) listOf(cell, mate) else listOf(cell)

            val saved = pair.map { givens[it] }
            for (c in pair) givens[c] = 0
            if (Solver.countSolutions(givens, limit = 2) == 1) {
                for (c in pair) removed[c] = true
                history.add(pair)
                clues -= pair.size
            } else {
                for ((i, c) in pair.withIndex()) givens[c] = saved[i]
            }
        }

        // Walk back up. Every position visited here still has exactly one
        // solution — adding a clue that agrees with the answer cannot introduce
        // another — so only the grade needs re-checking.
        while (true) {
            // Capped at the grade's own ceiling, so proving a position too hard
            // costs the cheap rungs of the ladder and never the expensive ones.
            val result = Solver.solveLogically(givens, ceiling = difficulty.ceiling)

            if (difficulty.matches(result)) {
                // Belt and braces on the promise the whole app rests on. The dig
                // loop only ever accepted single-solution positions, so this
                // cannot fire — and "one solution, reachable by logic" is too
                // important to rest on that loop being correct.
                if (!result.cells.contentEquals(solution)) return null
                return Puzzle(seed, difficulty, givens, solution)
            }

            // Solved without ever needing this grade's easiest technique, so the
            // walk has stepped past the band. Restoring more clues only makes it
            // easier still, so this layout is finished — and a fresh one is
            // cheaper than anything clever.
            if (result.solved && (result.hardest?.rank ?: 0) < difficulty.floorRank) return null

            // Too hard, or beyond the ladder entirely. Put the last clues back.
            val pair = history.removeLastOrNull() ?: return null
            for (c in pair) givens[c] = solution[c]
        }
    }

    /**
     * A random finished grid.
     *
     * Ordinary backtracking with the digits tried in a shuffled order, which is
     * enough: the first row alone gives 9! orderings, and the search almost never
     * backtracks far.
     */
    fun completeGrid(rng: Random): IntArray? {
        val cells = IntArray(CELLS)

        fun fill(index: Int): Boolean {
            if (index == CELLS) return true
            val digits = (1..N).shuffled(rng)
            for (d in digits) {
                if (Sudoku.peers[index].any { cells[it] == d }) continue
                cells[index] = d
                if (fill(index + 1)) return true
                cells[index] = 0
            }
            return false
        }

        return if (fill(0)) cells else null
    }

    /**
     * A seed from typed text.
     *
     * A number is itself. Anything else is hashed, so "morning coffee" is a
     * puzzle you can tell someone about over the phone. Blank is not a seed —
     * returning 0 for it would quietly deal everyone the same board.
     */
    fun seedFromText(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toIntOrNull()?.let { return it }
        var h = 2166136261u
        for (ch in trimmed.lowercase()) {
            h = h xor ch.code.toUInt()
            h *= 16777619u
        }
        return (h.toInt() and 0x7FFFFFFF)
    }
}
