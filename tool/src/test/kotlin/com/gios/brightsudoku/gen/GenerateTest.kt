package com.gios.brightsudoku.gen

import com.gios.brightsudoku.game.Difficulty
import com.gios.brightsudoku.game.Solver
import com.gios.brightsudoku.game.Sudoku
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The generator makes two promises, and this file is where they are kept.
 *
 * Everything it hands out has exactly one solution, and can be finished by logic
 * with no guessing anywhere. Both are checked here against every puzzle the tests
 * produce, not sampled.
 */
class GenerateTest {

    @Test
    fun `a complete grid is a complete grid`() {
        for (seed in 1..30) {
            val grid = assertNotNull(Generate.completeGrid(Random(seed)))
            assertTrue(Sudoku.isComplete(grid), "seed $seed produced an illegal finished grid")
        }
    }

    @Test
    fun `complete grids differ from one another`() {
        // A shuffle wired up wrongly still produces a legal grid — the same legal
        // grid, every time. That failure is invisible without this check.
        val seen = (1..30).map { Generate.completeGrid(Random(it))!!.joinToString("") }.toSet()
        assertEquals(30, seen.size)
    }

    @Test
    fun `every puzzle has exactly one solution and needs no guessing`() {
        var made = 0
        for (difficulty in Difficulty.entries) {
            for (seed in 1..25) {
                val puzzle = Generate.fromSeed(seed, difficulty) ?: continue
                made++

                assertEquals(
                    1,
                    Solver.countSolutions(puzzle.givens, limit = 2),
                    "seed $seed ${difficulty.label} has more than one answer",
                )

                val logic = Solver.solveLogically(puzzle.givens)
                assertTrue(logic.solved, "seed $seed ${difficulty.label} cannot be reasoned out")
                assertTrue(
                    logic.cells.contentEquals(puzzle.solution),
                    "seed $seed ${difficulty.label}: logic reached a different answer",
                )

                // The clues have to be part of the answer, and the answer has to
                // be a finished grid. A generator that drifted on either would
                // still produce something playable and unwinnable.
                assertTrue(Sudoku.isComplete(puzzle.solution))
                for (i in 0 until 81) {
                    if (puzzle.givens[i] != 0) assertEquals(puzzle.givens[i], puzzle.solution[i])
                }
                assertTrue(puzzle.clueCount in 17..50, "odd clue count ${puzzle.clueCount}")
            }
        }
        assertTrue(made > 60, "expected the generator to produce plenty of puzzles, got $made")
    }

    @Test
    fun `a puzzle is graded by what it actually needs`() {
        for (difficulty in Difficulty.entries) {
            var checked = 0
            for (seed in 1..25) {
                val puzzle = Generate.fromSeed(seed, difficulty) ?: continue
                checked++
                assertEquals(difficulty, puzzle.difficulty)

                val logic = Solver.solveLogically(puzzle.givens)
                val hardest = logic.hardest?.rank ?: 0

                // Inside the band on both sides. The ceiling is the easy half to
                // get right; the floor is what stops every grade quietly
                // collapsing into Gentle, which is exactly what an earlier
                // version of this generator did.
                assertTrue(
                    hardest <= difficulty.ceiling.rank,
                    "seed $seed graded ${difficulty.label} needs ${logic.hardest}",
                )
                assertTrue(
                    hardest >= difficulty.floorRank,
                    "seed $seed graded ${difficulty.label} only needs ${logic.hardest} — too easy for the grade",
                )
            }
            assertTrue(checked >= 10, "${difficulty.label}: only $checked puzzles to check")
        }
    }

    @Test
    fun `the same seed always deals the same puzzle`() {
        for (seed in listOf(1, 7, 99, 12345, Int.MAX_VALUE, -3)) {
            for (difficulty in Difficulty.entries) {
                val a = Generate.fromSeed(seed, difficulty)
                val b = Generate.fromSeed(seed, difficulty)
                if (a == null) {
                    assertNull(b, "seed $seed ${difficulty.label} was not reproducible")
                    continue
                }
                assertNotNull(b)
                assertTrue(a.givens.contentEquals(b.givens), "seed $seed ${difficulty.label} drifted")
                assertTrue(a.solution.contentEquals(b.solution))
                assertEquals(a.difficulty, b.difficulty)
            }
        }
    }

    @Test
    fun `different seeds deal different puzzles`() {
        val seen = HashSet<String>()
        for (seed in 1..25) {
            val puzzle = Generate.fromSeed(seed, Difficulty.GENTLE) ?: continue
            assertTrue(seen.add(puzzle.givens.joinToString("")), "seed $seed repeats an earlier puzzle")
        }
        assertTrue(seen.size >= 20)
    }

    @Test
    fun `the clues are laid out symmetrically`() {
        // Rotational symmetry is the traditional look, and it is the one property
        // of the dig loop that is visible on the board rather than in the logic.
        for (difficulty in Difficulty.entries) {
            for (seed in 1..10) {
                val puzzle = Generate.fromSeed(seed, difficulty) ?: continue
                for (cell in 0 until 81) {
                    val mate = 80 - cell
                    assertEquals(
                        puzzle.givens[cell] != 0,
                        puzzle.givens[mate] != 0,
                        "seed $seed ${difficulty.label}: square $cell breaks symmetry",
                    )
                }
            }
        }
    }

    @Test
    fun `a new puzzle is always available at every grade`() {
        // The one call the New button makes. It walks seeds, so unlike fromSeed it
        // is not allowed to come back empty-handed.
        for (difficulty in Difficulty.entries) {
            for (start in listOf(1, 500, 9_000, 1_234_567)) {
                val puzzle = assertNotNull(
                    Generate.nextFrom(start, difficulty),
                    "no ${difficulty.label} puzzle within 8 seeds of $start",
                )
                assertEquals(difficulty, puzzle.difficulty)
                assertTrue(puzzle.seed >= start && puzzle.seed < start + 8)
                assertEquals(1, Solver.countSolutions(puzzle.givens, limit = 2))
            }
        }
    }

    @Test
    fun `a typed seed always gets a puzzle, labelled with what it really is`() {
        for (seed in 1..15) {
            val puzzle = assertNotNull(
                Generate.bestEffort(seed, Difficulty.SEVERE),
                "seed $seed produced nothing at any grade",
            )
            // It may have fallen back to an easier grade, but the label has to
            // match the puzzle rather than the request.
            val logic = Solver.solveLogically(puzzle.givens)
            assertTrue(puzzle.difficulty.matches(logic), "seed $seed is labelled wrongly")
        }
    }

    @Test
    fun `text turns into a seed, and blank does not`() {
        assertEquals(42, Generate.seedFromText("42"))
        assertEquals(42, Generate.seedFromText("  42 "))
        assertEquals(-7, Generate.seedFromText("-7"))
        assertNull(Generate.seedFromText(""))
        assertNull(Generate.seedFromText("   "))

        val a = assertNotNull(Generate.seedFromText("morning coffee"))
        val b = assertNotNull(Generate.seedFromText("Morning Coffee"))
        assertEquals(a, b, "case should not change the puzzle a phrase names")
        assertTrue(a >= 0, "a hashed seed must stay positive so it can be shown and retyped")
        assertTrue(a != Generate.seedFromText("evening coffee"))
    }

    @Test
    fun `generation stays inside a phone's patience`() {
        // Not a hard performance assertion — CI machines vary too much for that
        // to be anything but a flaky test. It is a floor low enough that only a
        // real regression trips it, and it prints the numbers the README quotes.
        for (difficulty in Difficulty.entries) {
            val start = System.nanoTime()
            var made = 0
            for (seed in 1..10) if (Generate.fromSeed(seed, difficulty) != null) made++
            val perPuzzle = (System.nanoTime() - start) / 1e6 / 10
            println("${difficulty.label}: $made/10 in ${"%.0f".format(perPuzzle)} ms per seed")
            assertTrue(
                perPuzzle < 2_000,
                "${difficulty.label} took ${perPuzzle}ms per seed, which is far past anything usable",
            )
        }
    }
}
