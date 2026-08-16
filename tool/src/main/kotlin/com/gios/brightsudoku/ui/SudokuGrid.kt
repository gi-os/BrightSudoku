package com.gios.brightsudoku.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.brightsudoku.game.Board
import com.gios.brightsudoku.game.Step
import com.gios.brightsudoku.game.Sudoku
import com.thelightphone.sdk.ui.LightThemeTokens

/** No square is selected. */
const val NO_CELL = -1

/**
 * The board.
 *
 * Drawn into a single [Canvas] rather than as 81 composables, and the pencil
 * marks matter more than the digits there: a fully pencilled grid is over seven
 * hundred glyphs, which as composables is a recomposition the phone cannot afford
 * on every tap.
 *
 * Text in a canvas has to be measured before it can be drawn, and measuring is
 * the expensive half. There are only ever nine digits at three sizes, so all
 * twenty-seven layouts are measured once and cached; drawing then reuses them and
 * overrides only the colour. That is what keeps a redraw to arithmetic.
 *
 * [version] exists because [Board] is deliberately plain mutable state — all the
 * rules live in testable non-Compose code — so something has to tell Compose the
 * board changed. Bumping an Int on every move is that something.
 */
@Composable
fun SudokuGrid(
    board: Board,
    selected: Int,
    version: Int,
    showWrong: Boolean,
    hint: Step?,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val ink = LightThemeTokens.colors.content
    val paper = LightThemeTokens.colors.background
    val font = LightThemeTokens.typography.copy.fontFamily
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side: Dp = minOf(maxWidth, maxHeight)
        val cell: Dp = side / Sudoku.N

        // Sized from the real cell rather than from a constant: the layout above
        // fits the grid to whatever the panel gives it, so a hardcoded font would
        // be wrong on any screen but the one it was tuned on.
        val glyphs = remember(measurer, cell, font) {
            Glyphs(
                measurer = measurer,
                givenStyle = TextStyle(fontFamily = font, fontSize = (cell.value * 0.58f).sp, fontWeight = FontWeight.Bold),
                entryStyle = TextStyle(fontFamily = font, fontSize = (cell.value * 0.56f).sp, fontWeight = FontWeight.Normal),
                pencilStyle = TextStyle(fontFamily = font, fontSize = (cell.value * 0.24f).sp, fontWeight = FontWeight.Normal),
            )
        }

        // Derived once per change rather than once per frame. Each of these walks
        // the whole board, and the draw lambda runs on every frame the grid is
        // on screen — including the frames where nothing about it has moved.
        val flagged = remember(board, version, showWrong) {
            buildSet {
                addAll(board.conflicts())
                if (showWrong) addAll(board.wrong())
            }
        }
        val peers = remember(selected) {
            if (selected != NO_CELL) Sudoku.peers[selected].toSet() else emptySet()
        }

        Canvas(
            modifier = Modifier
                .size(side)
                .pointerInput(board) {
                    val cellPx = size.width.toFloat() / Sudoku.N
                    detectTapGestures { offset ->
                        val c = (offset.x / cellPx).toInt()
                        val r = (offset.y / cellPx).toInt()
                        if (r in 0 until Sudoku.N && c in 0 until Sudoku.N) onSelect(r * Sudoku.N + c)
                    }
                },
        ) {
            version.let { }  // read it, so the draw re-runs when the board changes

            val cellPx = size.width / Sudoku.N
            val selectedDigit = if (selected != NO_CELL) board.digitAt(selected) else 0
            val hinted = hint?.takeIf { it.isPlacement }?.cell ?: NO_CELL
            val supporting = hint?.because?.toSet().orEmpty()
            val struck = hint?.eliminated?.toSet().orEmpty()

            // ---- washes, under everything ------------------------------------
            for (i in 0 until Sudoku.CELLS) {
                val x = (i % Sudoku.N) * cellPx
                val y = (i / Sudoku.N) * cellPx
                val topLeft = Offset(x, y)
                val cellSize = Size(cellPx, cellPx)

                val shade = when {
                    i == selected -> null                       // drawn solid below
                    i in supporting || i in struck -> 0.16f     // what the hint is pointing at
                    selectedDigit != 0 && board.digitAt(i) == selectedDigit -> 0.14f
                    i in peers -> 0.06f
                    else -> null
                }
                if (shade != null) {
                    drawRect(ink.copy(alpha = shade), topLeft, cellSize)
                }
            }

            if (selected != NO_CELL) {
                // The selected square is inverted rather than outlined. On a
                // one-bit panel an outline competes with the grid rules it sits
                // on; a solid block never does.
                drawRect(
                    ink,
                    Offset((selected % Sudoku.N) * cellPx, (selected / Sudoku.N) * cellPx),
                    Size(cellPx, cellPx),
                )
            }

            // ---- rules -------------------------------------------------------
            val hair = 1.dp.toPx()
            val heavy = 2.dp.toPx()
            for (i in 0..Sudoku.N) {
                // Every third line heavy: the box boundaries are the whole reason
                // a 9x9 of digits is readable at a glance.
                val isBox = i % 3 == 0
                val w = if (isBox) heavy else hair
                val a = if (isBox) 0.65f else 0.22f
                drawLine(ink.copy(alpha = a), Offset(i * cellPx, 0f), Offset(i * cellPx, size.height), w)
                drawLine(ink.copy(alpha = a), Offset(0f, i * cellPx), Offset(size.width, i * cellPx), w)
            }

            // ---- digits and marks --------------------------------------------
            for (i in 0 until Sudoku.CELLS) {
                val x = (i % Sudoku.N) * cellPx
                val y = (i / Sudoku.N) * cellPx
                val digit = board.digitAt(i)

                if (digit != 0) {
                    val layout = if (board.isGiven(i)) glyphs.given[digit] else glyphs.entry[digit]
                    // Inverted inside the selection, so the digit stays legible
                    // on the solid block.
                    val colour = if (i == selected) paper else ink
                    drawCentred(layout, colour, x, y, cellPx)

                    if (i in flagged) {
                        // One mark for "this is wrong", whether it clashes with
                        // another digit or, with checking on, simply is not the
                        // answer. Two different marks would be a legend to learn.
                        val inset = cellPx * 0.24f
                        drawLine(
                            color = if (i == selected) paper else ink,
                            start = Offset(x + inset, y + cellPx * 0.84f),
                            end = Offset(x + cellPx - inset, y + cellPx * 0.84f),
                            strokeWidth = heavy,
                        )
                    }
                } else {
                    val marks = board.pencilAt(i)
                    if (marks != 0) {
                        for (d in 1..Sudoku.N) {
                            if (marks and Sudoku.bit(d) == 0) continue
                            // Marks sit in a 3x3 of their own, each digit always
                            // in the same corner, so the pattern can be read
                            // without reading the numbers.
                            val mr = (d - 1) / 3
                            val mc = (d - 1) % 3
                            val third = cellPx / 3f
                            drawCentred(
                                layout = glyphs.pencil[d],
                                colour = if (i == selected) paper else ink.copy(alpha = 0.62f),
                                x = x + mc * third,
                                y = y + mr * third,
                                cellPx = third,
                            )
                        }
                    }
                }

                // A square the hint wants a digit in gets a frame; a square it
                // rules something out of gets a corner tick.
                if (i == hinted) {
                    drawRect(
                        color = ink,
                        topLeft = Offset(x + heavy, y + heavy),
                        size = Size(cellPx - heavy * 2, cellPx - heavy * 2),
                        style = Stroke(width = heavy),
                    )
                }
                if (i in struck) {
                    drawLine(
                        color = ink,
                        start = Offset(x + cellPx * 0.62f, y + cellPx * 0.18f),
                        end = Offset(x + cellPx * 0.84f, y + cellPx * 0.18f),
                        strokeWidth = heavy,
                    )
                }
            }
        }
    }
}

/** Draw a measured glyph in the middle of a square. */
private fun DrawScope.drawCentred(
    layout: TextLayoutResult,
    colour: Color,
    x: Float,
    y: Float,
    cellPx: Float,
) {
    drawText(
        textLayoutResult = layout,
        color = colour,
        topLeft = Offset(
            x + (cellPx - layout.size.width) / 2f,
            y + (cellPx - layout.size.height) / 2f,
        ),
    )
}

/**
 * The nine digits, measured once at each of the three sizes the board uses.
 *
 * Index by the digit itself; slot 0 is a spacer so the arithmetic elsewhere reads
 * as `glyphs.entry[digit]` rather than `glyphs.entry[digit - 1]`.
 */
private class Glyphs(
    measurer: TextMeasurer,
    givenStyle: TextStyle,
    entryStyle: TextStyle,
    pencilStyle: TextStyle,
) {
    val given: Array<TextLayoutResult> = measure(measurer, givenStyle)
    val entry: Array<TextLayoutResult> = measure(measurer, entryStyle)
    val pencil: Array<TextLayoutResult> = measure(measurer, pencilStyle)

    private companion object {
        fun measure(measurer: TextMeasurer, style: TextStyle): Array<TextLayoutResult> =
            Array(10) { d -> measurer.measure(if (d == 0) " " else d.toString(), style) }
    }
}

/**
 * A small static picture of a grid, for the menu's Continue row.
 *
 * Clues only, no digits: at this size a digit is unreadable, and the pattern of
 * filled squares is what tells you which board you left behind.
 */
@Composable
fun BoardThumbnail(givens: IntArray, entries: IntArray, side: Dp) {
    val ink = LightThemeTokens.colors.content
    Box(Modifier.size(side)) {
        Canvas(Modifier.size(side)) {
            val cellPx = size.width / Sudoku.N
            for (i in 0 until Sudoku.CELLS) {
                val filled = givens.getOrNull(i)?.takeIf { it != 0 } != null
                val written = entries.getOrNull(i)?.takeIf { it != 0 } != null
                if (!filled && !written) continue
                drawRect(
                    color = if (filled) ink else ink.copy(alpha = 0.42f),
                    topLeft = Offset((i % Sudoku.N) * cellPx + cellPx * 0.12f, (i / Sudoku.N) * cellPx + cellPx * 0.12f),
                    size = Size(cellPx * 0.76f, cellPx * 0.76f),
                )
            }
            for (i in 0..3) {
                val at = i * 3 * cellPx
                drawLine(ink.copy(alpha = 0.5f), Offset(at, 0f), Offset(at, size.height), 1.dp.toPx())
                drawLine(ink.copy(alpha = 0.5f), Offset(0f, at), Offset(size.width, at), 1.dp.toPx())
            }
        }
    }
}
