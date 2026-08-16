package com.gios.brightsudoku.game

import com.gios.brightsudoku.gen.Generate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The solver is what every promise in this app rests on, so it is checked against
 * something other than itself wherever that is possible: known-answer puzzles,
 * hand-built positions where one technique is the only thing available, and a
 * second independent search.
 */
class SolverTest {

    private fun grid(s: String): IntArray {
        val cleaned = s.filter { it.isDigit() || it == '.' }
        require(cleaned.length == 81) { "expected 81 squares, got ${cleaned.length}" }
        return IntArray(81) { i -> if (cleaned[i] == '.') 0 else cleaned[i] - '0' }
    }

    /** A well-known puzzle with a single answer, used as a fixed reference point. */
    private val known = grid(
        """
        53..7....
        6..195...
        .98....6.
        8...6...3
        4..8.3..1
        7...2...6
        .6....28.
        ...419..5
        ....8..79
        """
    )

    private val knownAnswer = grid(
        """
        534678912
        672195348
        198342567
        859761423
        426853791
        713924856
        961537284
        287419635
        345286179
        """
    )

    @Test
    fun `geometry is what it claims to be`() {
        assertEquals(27, Sudoku.units.size)
        assertTrue(Sudoku.units.all { it.size == 9 })
        assertTrue(Sudoku.units.all { it.toSet().size == 9 })
        for (cell in 0 until 81) {
            assertEquals(3, Sudoku.unitsOf[cell].size)
            // Twenty peers: eight in the row, eight in the column, four more in
            // the box. Any other number means a unit table is wrong.
            assertEquals(20, Sudoku.peers[cell].size, "peers of $cell")
            assertTrue(cell !in Sudoku.peers[cell])
            for (u in Sudoku.unitsOf[cell]) assertTrue(cell in Sudoku.units[u])
        }
    }

    @Test
    fun `counts the one solution of a known puzzle`() {
        assertEquals(1, Solver.countSolutions(known))
        assertTrue(Solver.uniqueSolution(known).contentEquals(knownAnswer))
    }

    @Test
    fun `logic alone finishes it, and agrees with the search`() {
        val result = Solver.solveLogically(known)
        assertTrue(result.solved)
        assertTrue(result.cells.contentEquals(knownAnswer))
    }

    @Test
    fun `an ambiguous puzzle is counted as ambiguous`() {
        // Blank the whole first box's worth of information by emptying two cells
        // that were pinning each other: the answer stops being unique.
        val ambiguous = knownAnswer.copyOf()
        for (i in 0 until 81) if (i % 7 != 0) ambiguous[i] = 0
        assertTrue(Solver.countSolutions(ambiguous, limit = 2) >= 2)
        assertNull(Solver.uniqueSolution(ambiguous))
    }

    @Test
    fun `a broken position has no solutions`() {
        val broken = known.copyOf()
        // Two 5s in the top row.
        broken[1] = 5
        assertEquals(0, Solver.countSolutions(broken))
        assertNull(Solver.uniqueSolution(broken))
        assertTrue(!Solver.solveLogically(broken).solved)
    }

    @Test
    fun `an empty grid stops at the bound instead of enumerating the universe`() {
        // Without a working bound this call does not return this century.
        assertEquals(5, Solver.countSolutions(IntArray(81), limit = 5))
    }

    @Test
    fun `the ceiling really stops the ladder`() {
        // A puzzle that needs more than singles must not solve under a singles
        // ceiling, or the generator's whole grading argument is void.
        val puzzle = assertNotNull(Generate.fromSeed(4242, Difficulty.SEVERE, maxAttempts = 60))
        val capped = Solver.solveLogically(puzzle.givens, ceiling = Technique.HIDDEN_SINGLE)
        assertTrue(!capped.solved, "a Severe puzzle solved with singles only")
        val uncapped = Solver.solveLogically(puzzle.givens)
        assertTrue(uncapped.solved)
        assertTrue(uncapped.used.keys.all { it.rank <= Technique.SWORDFISH.rank })
    }

    @Test
    fun `every step it proposes is a step it can take`() {
        // A hint that cannot be applied is worse than no hint: the board would
        // draw a move that does nothing. Walk real puzzles a step at a time and
        // check each one against the answer.
        var placements = 0
        var eliminations = 0
        for (seed in 1..12) {
            val puzzle = Generate.fromSeed(seed, Difficulty.STEADY) ?: continue
            val cells = puzzle.givens.copyOf()
            var guard = 0
            while (guard++ < 400) {
                val step = Solver.nextStep(cells) ?: break
                if (step.isPlacement) {
                    // The single strongest check in this file: a deduction is
                    // only sound if it agrees with the answer, every time.
                    assertEquals(
                        puzzle.solution[step.cell],
                        step.digit,
                        "${step.technique} put ${step.digit} in ${step.cell}, answer is " +
                            "${puzzle.solution[step.cell]} (seed $seed)",
                    )
                    cells[step.cell] = step.digit
                    placements++
                } else {
                    // An elimination must never strike the true digit out.
                    for (cell in step.eliminated) {
                        assertTrue(
                            puzzle.solution[cell] != step.digit,
                            "${step.technique} ruled ${step.digit} out of $cell, which is the answer",
                        )
                    }
                    eliminations++
                    // Eliminations do not change `cells`, so stepping past one
                    // means re-deriving it forever. Applying it properly needs
                    // candidate state, which is what solveLogically is for; here
                    // it is enough to have checked the claim.
                    break
                }
            }
            assertTrue(cells.contentEquals(puzzle.solution) || eliminations > 0)
        }
        assertTrue(placements > 100, "expected the walk to place plenty of digits, got $placements")
    }

    @Test
    fun `every step carries an explanation`() {
        for (technique in Technique.entries) {
            val step = Step(technique, 0, 4, unit = 0, because = listOf(1, 2))
            assertTrue(step.explain().isNotBlank())
            assertTrue(step.explain().length > 20, "${technique.label} explains nothing useful")
        }
    }

    @Test
    fun `hidden single fires where nothing else can`() {
        // Row 0 is missing 4, 8 and 9, all in the first box. The two 4s below
        // block that digit from the second and third columns, so the 4 in row 0
        // has exactly one square left — while that square can still hold three
        // different digits, which is what makes this hidden rather than naked.
        val cells = grid(
            """
            ...123567
            .........
            .........
            .4.......
            .........
            .........
            ..4......
            .........
            .........
            """
        )
        val step = assertNotNull(Solver.nextStep(cells))
        assertEquals(Technique.HIDDEN_SINGLE, step.technique)
        assertEquals(0, step.cell)
        assertEquals(4, step.digit)
        // Three candidates in that square: if it were down to one, an easier rung
        // would have fired and this test would be checking the wrong technique.
        val candidates = assertNotNull(Candidates.of(cells))
        assertEquals(3, Integer.bitCount(candidates.cand[0]))
    }

    @Test
    fun `naked single fires on a cell with one candidate left`() {
        val cells = grid(
            """
            12345678.
            .........
            .........
            .........
            .........
            .........
            .........
            .........
            .........
            """
        )
        val step = assertNotNull(Solver.nextStep(cells))
        assertEquals(Technique.NAKED_SINGLE, step.technique)
        assertEquals(8, step.cell)
        assertEquals(9, step.digit)
    }

    @Test
    fun `the search and the ladder never disagree`() {
        // Two independent routes to the same answer: backtracking search, and the
        // technique ladder that never guesses. If they ever differ, one of them
        // is unsound, and the app cannot tell which.
        for (seed in 1..40) {
            for (difficulty in Difficulty.entries) {
                val puzzle = Generate.fromSeed(seed, difficulty) ?: continue
                val searched = assertNotNull(
                    Solver.uniqueSolution(puzzle.givens),
                    "seed $seed ${difficulty.label}: search found no unique answer",
                )
                val reasoned = Solver.solveLogically(puzzle.givens)
                assertTrue(reasoned.solved, "seed $seed ${difficulty.label}: logic stalled")
                assertTrue(
                    searched.contentEquals(reasoned.cells),
                    "seed $seed ${difficulty.label}: search and logic disagree",
                )
                assertTrue(searched.contentEquals(puzzle.solution))
            }
        }
    }
}
