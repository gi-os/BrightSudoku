package com.gios.brightsudoku.game

import com.gios.brightsudoku.gen.Generate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoardTest {

    private fun board(autoClean: Boolean = true): Board {
        val puzzle = assertNotNull(Generate.fromSeed(7, Difficulty.GENTLE))
        return Board(puzzle, autoClean = autoClean)
    }

    private fun Board.firstEmpty(): Int = (0 until 81).first { !isGiven(it) }

    @Test
    fun `a given is not writable`() {
        val b = board()
        val given = (0 until 81).first { b.isGiven(it) }
        val was = b.digitAt(given)
        assertFalse(b.place(given, 5))
        assertFalse(b.erase(given))
        assertFalse(b.togglePencil(given, 5))
        assertEquals(was, b.digitAt(given))
        assertFalse(b.canUndo, "a refused move must not land on the undo stack")
    }

    @Test
    fun `writing the same digit again rubs it out`() {
        val b = board()
        val cell = b.firstEmpty()
        b.place(cell, 4)
        assertEquals(4, b.digitAt(cell))
        b.place(cell, 4)
        assertEquals(0, b.digitAt(cell), "tapping the digit already there should clear the square")
        b.place(cell, 4)
        b.place(cell, 6)
        assertEquals(6, b.digitAt(cell), "a different digit replaces rather than clears")
    }

    @Test
    fun `pencil marks toggle, and never sit under a digit`() {
        val b = board()
        val cell = b.firstEmpty()
        b.togglePencil(cell, 3)
        b.togglePencil(cell, 7)
        assertEquals(listOf(3, 7), Sudoku.digitsOf(b.pencilAt(cell)))
        b.togglePencil(cell, 3)
        assertEquals(listOf(7), Sudoku.digitsOf(b.pencilAt(cell)))

        b.place(cell, 5)
        assertEquals(0, b.pencilAt(cell), "marks must not show under a written digit")
        assertFalse(b.togglePencil(cell, 2), "a filled square takes no marks")
    }

    @Test
    fun `auto-clean rubs a digit out of its peers' marks, and undo puts them back`() {
        val b = board(autoClean = true)
        val cell = b.firstEmpty()
        val peers = Sudoku.peers[cell].filter { !b.isGiven(it) && b.digitAt(it) == 0 }
        for (p in peers) b.togglePencil(p, 9)
        assertTrue(peers.all { b.pencilAt(it) and Sudoku.bit(9) != 0 })

        b.place(cell, 9)
        assertTrue(
            peers.none { b.pencilAt(it) and Sudoku.bit(9) != 0 },
            "auto-clean left a 9 pencilled in a square that can no longer take one",
        )

        b.undo()
        assertEquals(0, b.digitAt(cell))
        assertTrue(
            peers.all { b.pencilAt(it) and Sudoku.bit(9) != 0 },
            "undo restored the digit but not the marks it rubbed out",
        )
    }

    @Test
    fun `auto-clean off leaves the marks alone`() {
        val b = board(autoClean = false)
        val cell = b.firstEmpty()
        val peer = Sudoku.peers[cell].first { !b.isGiven(it) && b.digitAt(it) == 0 }
        b.togglePencil(peer, 9)
        b.place(cell, 9)
        assertTrue(b.pencilAt(peer) and Sudoku.bit(9) != 0)
    }

    @Test
    fun `filling the marks writes exactly what fits`() {
        val b = board()
        assertTrue(b.fillPencilMarks())
        val candidates = assertNotNull(Candidates.of(b.cells()))
        for (cell in 0 until 81) {
            if (b.digitAt(cell) != 0) {
                assertEquals(0, b.pencilAt(cell))
            } else {
                assertEquals(candidates.cand[cell], b.pencilAt(cell), "square $cell has the wrong marks")
                // Whatever else is true, the answer must never be missing from the
                // marks — a fill that rubbed out the true digit would be a trap.
                assertTrue(b.pencilAt(cell) and Sudoku.bit(b.puzzle.solution[cell]) != 0)
            }
        }
        assertFalse(b.fillPencilMarks(), "filling twice should change nothing the second time")
        b.undo()
        assertTrue(b.pencilAt(b.firstEmpty()) == 0)
    }

    @Test
    fun `clashes are reported both ways`() {
        val b = board()
        val cell = b.firstEmpty()
        val peer = Sudoku.peers[cell].first { !b.isGiven(it) && b.digitAt(it) == 0 }
        // A digit neither square can already see, so the only clash on the board
        // is the one this test makes. Picking one blindly can land on a digit
        // that already clashes with a clue, which tests nothing.
        val digit = (1..9).first { d ->
            Sudoku.peers[cell].none { b.digitAt(it) == d } && Sudoku.peers[peer].none { b.digitAt(it) == d }
        }
        b.place(cell, digit)
        b.place(peer, digit)
        val clashes = b.conflicts()
        assertTrue(cell in clashes && peer in clashes, "both squares in a clash should be marked")

        b.erase(peer)
        assertTrue(b.conflicts().isEmpty())
    }

    @Test
    fun `a wrong digit that clashes with nothing is still wrong`() {
        val b = board()
        val cell = (0 until 81).first { !b.isGiven(it) }
        val wrongDigit = (1..9).first {
            it != b.puzzle.solution[cell] && Sudoku.peers[cell].none { p -> b.digitAt(p) == it }
        }
        b.place(cell, wrongDigit)
        assertTrue(b.conflicts().isEmpty(), "this digit was chosen not to clash")
        assertTrue(cell in b.wrong(), "checking should still catch it")
    }

    @Test
    fun `filling in the answer wins`() {
        val b = board()
        assertFalse(b.isSolved)
        for (cell in 0 until 81) if (!b.isGiven(cell)) b.place(cell, b.puzzle.solution[cell])
        assertTrue(b.isSolved)
        assertEquals(81, b.filledCount)
        assertTrue(b.conflicts().isEmpty())
        assertTrue(b.wrong().isEmpty())
    }

    @Test
    fun `undo walks back move by move, and stops when it runs out`() {
        val b = board()
        val cells = (0 until 81).filter { !b.isGiven(it) }.take(5)
        for ((i, cell) in cells.withIndex()) b.place(cell, (i % 9) + 1)
        for (cell in cells.reversed()) {
            assertTrue(b.canUndo)
            b.undo()
            assertEquals(0, b.digitAt(cell))
        }
        assertFalse(b.canUndo)
        assertFalse(b.undo(), "undo on an empty stack should say so rather than throw")
    }

    @Test
    fun `undo is bounded`() {
        val b = board()
        val cell = b.firstEmpty()
        // Two hundred moves against a limit of a hundred and twenty. The point is
        // that a long session cannot grow the stack without limit.
        repeat(200) { b.place(cell, (it % 9) + 1) }
        var undone = 0
        while (b.canUndo) { b.undo(); undone++ }
        assertTrue(undone <= 120, "undo stack grew past its limit: $undone")
        assertTrue(undone >= 100)
    }

    @Test
    fun `restart clears everything the player wrote and nothing else`() {
        val b = board()
        val cell = b.firstEmpty()
        b.place(cell, 3)
        b.togglePencil(Sudoku.peers[cell].first { !b.isGiven(it) }, 5)
        b.restart()
        assertEquals(0, b.digitAt(cell))
        assertFalse(b.canUndo)
        assertTrue((0 until 81).all { b.pencilAt(it) == 0 })
        assertEquals(b.puzzle.clueCount, b.filledCount, "restart should leave exactly the clues")
    }

    @Test
    fun `hints keep coming until the board is finished`() {
        val b = board()
        var guard = 0
        while (!b.isSolved && guard++ < 200) {
            val step = assertNotNull(b.hint(), "ran out of hints on a solvable board")
            if (step.isPlacement) {
                b.place(step.cell, step.digit)
            } else {
                // An elimination changes nothing on the board itself, so take the
                // digit it points at from the solver's own next placement instead.
                val next = assertNotNull(Solver.solveLogically(b.cells()).cells)
                val cell = (0 until 81).first { b.digitAt(it) == 0 }
                b.place(cell, next[cell])
            }
        }
        assertTrue(b.isSolved, "following hints did not finish the puzzle")
    }

    @Test
    fun `a restored board comes back exactly as it was left`() {
        val b = board()
        val cell = b.firstEmpty()
        b.place(cell, b.puzzle.solution[cell])
        b.fillPencilMarks()

        val restored = Board(b.puzzle, restoreEntries = b.entries, restorePencil = b.pencil)
        for (i in 0 until 81) {
            assertEquals(b.digitAt(i), restored.digitAt(i), "square $i came back different")
            assertEquals(b.pencilAt(i), restored.pencilAt(i))
        }
        assertFalse(restored.canUndo, "a restored board starts a fresh undo history")
    }
}
