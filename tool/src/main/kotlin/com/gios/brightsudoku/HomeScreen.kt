package com.gios.brightsudoku

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.gios.brightsudoku.data.SudokuStore
import com.gios.brightsudoku.data.ToolState
import com.gios.brightsudoku.game.Board
import com.gios.brightsudoku.game.Difficulty
import com.gios.brightsudoku.game.SaveState
import com.gios.brightsudoku.game.Step
import com.gios.brightsudoku.game.Sudoku
import com.gios.brightsudoku.gen.Generate
import com.gios.brightsudoku.gen.Puzzle
import com.gios.brightsudoku.hw.LocalWheelBus
import com.gios.brightsudoku.hw.WheelBus
import com.gios.brightsudoku.hw.WheelScroll
import com.gios.brightsudoku.hw.dispatch
import com.gios.brightsudoku.ui.BoardThumbnail
import com.gios.brightsudoku.ui.NO_CELL
import com.gios.brightsudoku.ui.SudokuGrid
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Room at the bottom of the display for the LightOS back button. */
private val BACK_BUTTON_INSET = 44.dp

/** Which view the single screen is showing. */
sealed interface View {
    object Menu : View
    object Settings : View
    object Dealing : View
    data class Play(val board: Board) : View
}

/**
 * The whole tool, in one screen.
 *
 * Deliberately not several SDK screens. Menu, settings and board are cheap
 * Compose state, and one screen means one back stack.
 *
 * LightOS's hardware back cannot be intercepted here: `LightActivity` wires its
 * back dispatcher straight to its own `goBack()`, which pops the SDK's stack and
 * calls `finish()` when it empties. With a single screen on the stack, back
 * always closes the tool — which is why every view below carries its own way
 * home.
 */
@InitialScreen
class HomeScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, SudokuViewModel>(sealedActivity) {

    override val viewModelClass: Class<SudokuViewModel>
        get() = SudokuViewModel::class.java

    override fun createViewModel(): SudokuViewModel {
        // Reaching DataStore is the one thing that can fail before any UI exists.
        // Capture it rather than letting it kill the process: the tool is still
        // playable without saved progress.
        val store = runCatching { SudokuStore(lightContext.dataStore) }
        return SudokuViewModel(store.getOrNull(), store.exceptionOrNull())
    }

    private val wheel = WheelBus()

    /**
     * Whether a notch is worth anything here.
     *
     * The board is the one view with nothing to scroll, and on it a turn is worth
     * more left alone: the SDK forwards an unclaimed key to LightOS, which reads
     * it as brightness. Claiming the wheel everywhere would mean it went dead the
     * moment a puzzle opened — the longest anyone looks at this screen, and
     * exactly when they might want it dimmer. The board is sized to fit the
     * panel, so there is nothing to scroll there anyway.
     */
    private fun wheelWanted(): Boolean = viewModel.view.value !is View.Play

    override fun onKeyDown(keyCode: Int, event: KeyEvent) =
        (wheelWanted() && wheel.dispatch(event)) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) =
        (wheelWanted() && wheel.dispatch(event)) || super.onKeyUp(keyCode, event)

    /**
     * Push the text editor and deal whatever comes back.
     *
     * Text entry on LightOS is a screen of its own (see [SeedEditorScreen]), so
     * this is the one navigation the tool does.
     */
    private fun promptForSeed() {
        navigateTo(
            screenFactory = { SeedEditorScreen(it, "") },
            resultCallback = { typed -> if (typed != null) viewModel.playTypedSeed(typed) },
        )
    }

    @Composable
    override fun Content() {
        val view by viewModel.view.collectAsState()
        val state by viewModel.state.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val failure by viewModel.startupError.collectAsState()

        CompositionLocalProvider(LocalWheelBus provides wheel) {
            val trace = failure
            if (trace != null) {
                // Drawn without LightTheme or LightText on purpose: if the failure
                // is in the theme or the SDK's text stack, a reporter built on
                // them would die too and we would be back to a blank crash.
                StartupFailure(trace)
            } else {
                LightTheme(colors = themeColors) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LightThemeTokens.colors.background)
                            .padding(bottom = BACK_BUTTON_INSET),
                    ) {
                        when (val v = view) {
                            is View.Menu -> Menu(state, viewModel, onEnterSeed = ::promptForSeed)
                            is View.Settings -> Settings(state, viewModel)
                            is View.Dealing -> Dealing(state)
                            is View.Play -> Play(v.board, state, viewModel)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * Last-resort diagnostic: put the stack trace on the display.
 *
 * Sideloaded on a phone with no adb to hand, "it crashes" is all the feedback
 * there is. Rendering the trace turns one install into an actual bug report.
 */
@Composable
private fun StartupFailure(trace: String) {
    val scroll = rememberScrollState()
    WheelScroll(scroll)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(12.dp)
            .padding(bottom = BACK_BUTTON_INSET)
            .verticalScroll(scroll),
    ) {
        BasicText("Sudoku failed to start", style = TextStyle(fontSize = 15.sp, color = Color.Black))
        Spacer(Modifier.height(8.dp))
        BasicText(trace, style = TextStyle(fontSize = 9.sp, color = Color.Black))
    }
}

@Composable
private fun Header(title: String, trailing: String? = null, onHome: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = "‹ Home",
            variant = LightTextVariant.Detail,
            modifier = Modifier.lightClickable(onClick = onHome).padding(vertical = 6.dp, horizontal = 2.dp),
        )
        LightText(text = title, variant = LightTextVariant.Detail, lighten = true)
        LightText(text = trailing ?: "", variant = LightTextVariant.Detail, lighten = true)
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 12.dp),
    ) {
        LightText(text = title, variant = LightTextVariant.Copy)
        LightText(text = subtitle, variant = LightTextVariant.Detail, lighten = true)
    }
}

@Composable
private fun Menu(state: ToolState, vm: SudokuViewModel, onEnterSeed: () -> Unit) {
    val note by vm.seedMessage.collectAsState()
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(text = "Sudoku", variant = LightTextVariant.Heading)
        Spacer(Modifier.height(4.dp))
        LightText(
            text = solvedLine(state),
            variant = LightTextVariant.Detail,
            lighten = true,
        )

        Spacer(Modifier.height(24.dp))

        val save = state.save
        if (save != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoardThumbnail(save.givens, save.entries, side = 46.dp)
                Column(
                    Modifier.padding(start = 12.dp).weight(1f).lightClickable { vm.resume(save, state) },
                ) {
                    LightText(text = "Continue", variant = LightTextVariant.Copy)
                    LightText(
                        text = "${save.difficulty.label} · ${save.filledCount} of 81 filled",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        MenuRow("New puzzle", state.difficulty.blurb) { vm.newGame(state) }
        MenuRow("Difficulty  ·  ${state.difficulty.label}", "Tap to change") { vm.cycleDifficulty(state) }
        MenuRow("From a seed", note ?: "A number or a word") { onEnterSeed() }
        MenuRow("Settings", settingsLine(state)) { vm.show(View.Settings) }
    }
}

private fun solvedLine(state: ToolState): String {
    val total = state.solved.values.sum()
    return when (total) {
        0 -> "Nothing solved yet"
        1 -> "1 puzzle solved"
        else -> "$total puzzles solved" +
            Difficulty.entries.filter { (state.solved[it] ?: 0) > 0 }
                .joinToString(prefix = "  ·  ", separator = ", ") { "${it.label} ${state.solved[it]}" }
    }
}

private fun settingsLine(state: ToolState): String = buildList {
    add(if (state.autoClean) "Tidy marks on" else "Tidy marks off")
    if (state.checkAsYouGo) add("checking on")
}.joinToString(", ")

/**
 * Shown while a puzzle is being made.
 *
 * A Gentle grid appears too fast to read this; a Severe one can take a second or
 * two, because the generator is proving something about it rather than looking it
 * up. Saying so beats a frozen screen.
 */
@Composable
private fun Dealing(state: ToolState) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(text = "Making a ${state.difficulty.label.lowercase()} puzzle", variant = LightTextVariant.Copy)
            Spacer(Modifier.height(6.dp))
            LightText(
                text = "Checking it has one answer, and needs no guessing",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Settings(state: ToolState, vm: SudokuViewModel) {
    val scroll = rememberScrollState()
    WheelScroll(scroll)
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp).verticalScroll(scroll)) {
        Header(title = "Settings") { vm.show(View.Menu) }

        MenuRow(
            title = "Tidy marks  ·  ${if (state.autoClean) "On" else "Off"}",
            subtitle = if (state.autoClean) {
                "A digit rubs itself out of the marks around it"
            } else {
                "Marks stay exactly as you left them"
            },
        ) { vm.setAutoClean(!state.autoClean) }

        MenuRow(
            title = "Check as you go  ·  ${if (state.checkAsYouGo) "On" else "Off"}",
            subtitle = if (state.checkAsYouGo) {
                "A wrong digit is marked the moment it goes in"
            } else {
                "Only digits that clash are marked"
            },
        ) { vm.setCheckAsYouGo(!state.checkAsYouGo) }

        MenuRow(
            title = "Difficulty  ·  ${state.difficulty.label}",
            subtitle = state.difficulty.blurb,
        ) { vm.cycleDifficulty(state) }
    }
}

@Composable
private fun Play(board: Board, state: ToolState, vm: SudokuViewModel) {
    val selected by vm.selected.collectAsState()
    val version by vm.version.collectAsState()
    val pencilMode by vm.pencilMode.collectAsState()
    val hint by vm.hint.collectAsState()
    val solved by vm.solved.collectAsState()
    val showing = hint

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SudokuGrid(
            board = board,
            selected = selected,
            version = version,
            showWrong = state.checkAsYouGo,
            hint = hint,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            onSelect = { vm.select(it) },
        )

        // One line, always present, so nothing below it ever moves. A board that
        // reflows as you play is the fastest way to make someone tap the wrong
        // square.
        LightText(
            text = when {
                solved -> "Solved · ${vm.moveCount} moves"
                showing != null -> showing.explain()
                else -> " "
            },
            variant = LightTextVariant.Superfine,
            align = TextAlign.Center,
            lighten = !solved,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(22.dp),
        )

        Spacer(Modifier.weight(1f))

        if (solved) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LightText(
                    text = "New puzzle",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.lightClickable { vm.newGame(state) },
                )
                LightText(
                    text = "Home",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.lightClickable { vm.leaveBoard() },
                )
            }
            return@Column
        }

        DigitPad(board, pencilMode, onDigit = { vm.enter(it) })

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = "Home",
                variant = LightTextVariant.Detail,
                modifier = Modifier.lightClickable { vm.leaveBoard() },
            )
            // The label names the mode a tap will *use*, not the one it switches
            // to, so there is never a question what pressing a digit does next.
            LightText(
                text = if (pencilMode) "Marks" else "Digits",
                variant = LightTextVariant.Copy,
                modifier = Modifier.lightClickable { vm.togglePencilMode() },
            )
            LightText(
                text = "Erase",
                variant = LightTextVariant.Detail,
                modifier = Modifier.lightClickable { vm.eraseSelected() },
            )
            LightText(
                text = "Undo",
                variant = LightTextVariant.Detail,
                lighten = !board.canUndo,
                modifier = Modifier.lightClickable { vm.undo() },
            )
            LightText(
                text = if (showing == null) "Hint" else "Do it",
                variant = LightTextVariant.Detail,
                modifier = Modifier.lightClickable { vm.hintPressed() },
            )
        }
    }
}

/**
 * The nine digits.
 *
 * A digit already used nine times is dimmed rather than removed: a pad that
 * changes shape as you play moves every other key under your thumb.
 */
@Composable
private fun DigitPad(board: Board, pencilMode: Boolean, onDigit: (Int) -> Unit) {
    val counts = board.digitCounts()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (d in 1..Sudoku.N) {
            LightText(
                text = d.toString(),
                variant = if (pencilMode) LightTextVariant.Subheading else LightTextVariant.Heading,
                lighten = counts[d] >= Sudoku.N,
                modifier = Modifier.lightClickable { onDigit(d) }.padding(horizontal = 5.dp, vertical = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------

class SudokuViewModel(
    private val store: SudokuStore?,
    storeFailure: Throwable? = null,
) : LightViewModel<Unit>() {

    val view = MutableStateFlow<View>(View.Menu)
    val state = MutableStateFlow(ToolState())
    val selected = MutableStateFlow(NO_CELL)
    val pencilMode = MutableStateFlow(false)
    val hint = MutableStateFlow<Step?>(null)
    val solved = MutableStateFlow(false)

    /**
     * Bumped on every change to the board.
     *
     * [Board] is plain mutable state so the rules can be tested without Compose,
     * which means nothing about it is observable. This counter is what tells the
     * grid to redraw.
     */
    val version = MutableStateFlow(0)

    /** Non-null means the tool shows a trace instead of the game. */
    val startupError = MutableStateFlow<String?>(null)

    /** Feedback for the seed row when a typed seed cannot be used. */
    val seedMessage = MutableStateFlow<String?>(null)

    var moveCount = 0
        private set

    init {
        storeFailure?.let { report("Opening DataStore failed", it) }
        val s = store
        if (s != null) {
            viewModelScope.launch {
                // A throw in here would otherwise be an uncaught coroutine
                // exception, which takes the whole process down.
                runCatching { s.state.collect { state.value = it } }
                    .onFailure { report("Reading saved state failed", it) }
            }
        }
    }

    private fun report(what: String, e: Throwable) {
        startupError.value = "$what\n\n" + e.stackTraceToString()
    }

    fun show(v: View) { view.value = v }

    // ---- starting a game --------------------------------------------------

    private fun begin(puzzle: Puzzle, autoClean: Boolean, restore: SaveState? = null): Board {
        val board = Board(
            puzzle = puzzle,
            autoClean = autoClean,
            restoreEntries = restore?.entries,
            restorePencil = restore?.pencil,
        )
        moveCount = 0
        selected.value = NO_CELL
        pencilMode.value = false
        hint.value = null
        solved.value = board.isSolved
        version.value++
        view.value = View.Play(board)
        return board
    }

    /**
     * Deal a new puzzle.
     *
     * Generation runs off the main thread. It is arithmetic rather than IO, but a
     * Severe grid can take a second or two of it on this hardware — the generator
     * is proving the puzzle has one answer and needs no guessing, not looking one
     * up — and a second of that on the main thread is a frozen screen.
     */
    fun newGame(s: ToolState) {
        val store = this.store
        view.value = View.Dealing
        viewModelScope.launch {
            val puzzle = withContext(Dispatchers.Default) {
                runCatching { Generate.nextFrom(s.nextSeed, s.difficulty) }.getOrNull()
            }
            if (puzzle == null) {
                // nextFrom walks eight seeds and every grade is reachable from
                // almost all of them, so this is close to impossible. Going back
                // to the menu still beats a screen that says "Making a puzzle"
                // forever.
                seedMessage.value = "Couldn't make one — try again"
                view.value = View.Menu
                return@launch
            }
            val board = begin(puzzle, s.autoClean)
            if (store != null) {
                runCatching {
                    store.advanceSeed(puzzle.seed)
                    store.saveGame(SaveState.of(board))
                }
            }
        }
    }

    /**
     * Deal the puzzle for a typed seed.
     *
     * Unlike New, this cannot walk to a different seed — the player asked for
     * this one. It can fall back to a different grade, and the board is labelled
     * with the grade it actually got.
     */
    fun playTypedSeed(typed: String) {
        val s = state.value
        val seed = Generate.seedFromText(typed)
        if (seed == null) {
            seedMessage.value = "Type a number or a word"
            return
        }
        seedMessage.value = null
        view.value = View.Dealing
        viewModelScope.launch {
            val puzzle = withContext(Dispatchers.Default) {
                runCatching { Generate.bestEffort(seed, s.difficulty) }.getOrNull()
            }
            if (puzzle == null) {
                seedMessage.value = "That seed makes no puzzle — try another"
                view.value = View.Menu
                return@launch
            }
            val board = begin(puzzle, s.autoClean)
            store?.let { runCatching { it.saveGame(SaveState.of(board)) } }
        }
    }

    /** Reopen a saved board. */
    fun resume(save: SaveState, s: ToolState) {
        begin(save.puzzle(), s.autoClean, restore = save)
    }

    fun cycleDifficulty(s: ToolState) {
        val next = Difficulty.entries[(s.difficulty.ordinal + 1) % Difficulty.entries.size]
        val store = this.store ?: return
        viewModelScope.launch { runCatching { store.setDifficulty(next) } }
    }

    // ---- playing ----------------------------------------------------------

    private val board: Board? get() = (view.value as? View.Play)?.board

    fun select(cell: Int) {
        // Tapping the selected square again clears the selection, which is the
        // only way to put the highlight away without writing something.
        selected.value = if (selected.value == cell) NO_CELL else cell
        version.value++
    }

    fun togglePencilMode() { pencilMode.value = !pencilMode.value }

    fun enter(digit: Int) {
        val b = board ?: return
        val cell = selected.value
        if (cell == NO_CELL || b.isGiven(cell)) return
        val changed = if (pencilMode.value) b.togglePencil(cell, digit) else b.place(cell, digit)
        if (changed) afterMove(b)
    }

    fun eraseSelected() {
        val b = board ?: return
        val cell = selected.value
        if (cell == NO_CELL) return
        if (b.erase(cell)) afterMove(b)
    }


    fun undo() {
        val b = board ?: return
        if (b.undo()) {
            solved.value = b.isSolved
            afterMove(b)
        }
    }

    /**
     * First press shows the hint; second carries it out.
     *
     * Showing it first is the point — a hint that just played the move would be
     * the app solving the puzzle. The board draws which square it means and why,
     * and taking the step is a separate decision.
     */
    fun hintPressed() {
        val b = board ?: return
        val showing = hint.value
        if (showing != null) {
            if (showing.isPlacement) {
                b.place(showing.cell, showing.digit)
                selected.value = showing.cell
                afterMove(b)
            }
            hint.value = null
            return
        }
        viewModelScope.launch {
            // Finding a hint runs the technique ladder, which on a hard board is
            // the same work as grading a puzzle. Off the main thread, like
            // generation.
            hint.value = withContext(Dispatchers.Default) { runCatching { b.hint() }.getOrNull() }
            version.value++
        }
    }

    /** Bookkeeping after anything that changed the board. */
    private fun afterMove(b: Board) {
        moveCount++
        hint.value = null
        version.value++

        val store = this.store
        if (b.isSolved) {
            if (!solved.value) {
                solved.value = true
                selected.value = NO_CELL
                if (store != null) {
                    viewModelScope.launch {
                        runCatching {
                            store.recordSolved(b.puzzle.difficulty)
                            // Finished, so there is nothing left to continue.
                            store.clearGame()
                        }
                    }
                }
            }
            return
        }

        solved.value = false
        if (store != null) {
            // Saved after every digit. The payload is a few hundred characters
            // and preferences coalesce the writes.
            viewModelScope.launch { runCatching { store.saveGame(SaveState.of(b)) } }
        }
    }

    fun leaveBoard() {
        hint.value = null
        selected.value = NO_CELL
        view.value = View.Menu
    }

    fun setAutoClean(enabled: Boolean) {
        val store = this.store ?: return
        viewModelScope.launch { runCatching { store.setAutoClean(enabled) } }
    }

    fun setCheckAsYouGo(enabled: Boolean) {
        val store = this.store ?: return
        viewModelScope.launch { runCatching { store.setCheckAsYouGo(enabled) } }
    }
}
