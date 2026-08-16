package com.gios.brightsudoku.game

import com.gios.brightsudoku.gen.Generate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A save is written after every digit and read on every launch, so the two
 * things that matter are that a good one survives the trip exactly, and that a
 * damaged one decodes to nothing rather than to a board with no ending.
 */
class SaveStateTest {

    private fun playedBoard(): Board {
        val puzzle = assertNotNull(Generate.fromSeed(11, Difficulty.TRICKY, maxAttempts = 60))
        val board = Board(puzzle)
        var placed = 0
        for (cell in 0 until 81) {
            if (board.isGiven(cell) || placed >= 12) continue
            board.place(cell, puzzle.solution[cell])
            placed++
        }
        board.fillPencilMarks()
        return board
    }

    @Test
    fun `a board in progress round-trips exactly`() {
        val board = playedBoard()
        val save = SaveState.of(board)
        val decoded = assertNotNull(SaveState.decode(save.encode()))
        assertEquals(save, decoded)

        val restored = Board(decoded.puzzle(), restoreEntries = decoded.entries, restorePencil = decoded.pencil)
        for (i in 0 until 81) {
            assertEquals(board.digitAt(i), restored.digitAt(i), "square $i")
            assertEquals(board.pencilAt(i), restored.pencilAt(i), "marks on square $i")
        }
    }

    @Test
    fun `a fresh board and a finished one both round-trip`() {
        val puzzle = assertNotNull(Generate.fromSeed(3, Difficulty.GENTLE))

        val fresh = Board(puzzle)
        assertEquals(SaveState.of(fresh), SaveState.decode(SaveState.of(fresh).encode()))

        val done = Board(puzzle)
        for (cell in 0 until 81) if (!done.isGiven(cell)) done.place(cell, puzzle.solution[cell])
        val decoded = assertNotNull(SaveState.decode(SaveState.of(done).encode()))
        val restored = Board(decoded.puzzle(), restoreEntries = decoded.entries)
        assertTrue(restored.isSolved, "a finished board should come back finished")
    }

    @Test
    fun `every grade survives the trip`() {
        // The grade is stored as its label, so a renamed grade breaks every save
        // written before the rename. This is the check that would catch it.
        for (difficulty in Difficulty.entries) {
            val puzzle = Generate.fromSeed(5, difficulty, maxAttempts = 60) ?: continue
            val decoded = assertNotNull(SaveState.decode(SaveState.of(Board(puzzle)).encode()))
            assertEquals(difficulty, decoded.difficulty)
        }
    }

    @Test
    fun `pencil marks survive at full density`() {
        // Every square marked with every digit is the widest the encoding ever
        // gets, and the case where an off-by-one in the bit packing shows up.
        val puzzle = assertNotNull(Generate.fromSeed(2, Difficulty.GENTLE))
        val board = Board(puzzle)
        for (cell in 0 until 81) {
            if (board.isGiven(cell)) continue
            for (d in 1..9) board.togglePencil(cell, d)
        }
        val decoded = assertNotNull(SaveState.decode(SaveState.of(board).encode()))
        for (cell in 0 until 81) {
            val expected = if (board.isGiven(cell)) 0 else Sudoku.ALL
            assertEquals(expected, decoded.pencil[cell], "marks on square $cell")
        }
    }

    @Test
    fun `damaged saves decode to nothing`() {
        val good = SaveState.of(playedBoard()).encode()
        assertNotNull(SaveState.decode(good))

        val bad = mapOf(
            "null" to null,
            "blank" to "",
            "whitespace" to "   ",
            "junk" to "not a save at all",
            "truncated" to good.substring(0, good.length / 2),
            "one field short" to good.substringBeforeLast("|"),
            "extra field" to "$good|extra",
            "wrong version" to "9" + good.substring(1),
            "short grid" to good.replaceFirst("|", "|") .let { s ->
                val parts = s.split("|").toMutableList()
                parts[3] = parts[3].drop(1)
                parts.joinToString("|")
            },
            "letters in the grid" to good.split("|").toMutableList().also { it[3] = "x".repeat(81) }
                .joinToString("|"),
            "unknown grade" to good.split("|").toMutableList().also { it[2] = "Impossible" }
                .joinToString("|"),
            "seed is not a number" to good.split("|").toMutableList().also { it[1] = "abc" }
                .joinToString("|"),
            "bad base64 marks" to good.split("|").toMutableList().also { it[6] = "!!!!" }
                .joinToString("|"),
        )
        for ((what, raw) in bad) {
            assertNull(SaveState.decode(raw), "a $what save should decode to nothing")
        }
    }

    @Test
    fun `a save whose answer does not answer its clues is rejected`() {
        // The one corruption that would otherwise be playable: a legal grid of
        // clues and a legal finished grid that have nothing to do with each
        // other. It would deal a board that can never be finished.
        val board = playedBoard()
        val other = assertNotNull(Generate.fromSeed(99, Difficulty.GENTLE))
        val mismatched = SaveState.of(board).copy(solution = other.solution)
        assertNull(SaveState.decode(mismatched.encode()))
    }

    @Test
    fun `an illegal set of clues is rejected`() {
        val board = playedBoard()
        val save = SaveState.of(board)
        val clues = save.givens.copyOf()
        // Put a second copy of a digit in the first row.
        val first = clues.indexOfFirst { it != 0 }
        val spare = (0 until 9).first { clues[it] == 0 }
        clues[spare] = clues[first]
        assertNull(SaveState.decode(save.copy(givens = clues).encode()))
    }

    @Test
    fun `an entry written over a clue is rejected`() {
        val board = playedBoard()
        val save = SaveState.of(board)
        val entries = save.entries.copyOf()
        entries[save.givens.indexOfFirst { it != 0 }] = 5
        assertNull(SaveState.decode(save.copy(entries = entries).encode()))
    }

    @Test
    fun `the encoding stays small enough to write after every digit`() {
        // It is rewritten on every keystroke, so its size is a real constraint
        // rather than a curiosity.
        val encoded = SaveState.of(playedBoard()).encode()
        assertTrue(encoded.length < 500, "a save is ${encoded.length} characters")
        // Exactly seven fields: no field may contain the separator, which is the
        // assumption the whole format rests on.
        assertEquals(7, encoded.split("|").size, "a field has picked up a separator")
    }
}
