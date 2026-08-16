package com.gios.brightsudoku.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gios.brightsudoku.game.Difficulty
import com.gios.brightsudoku.game.SaveState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Everything the tool remembers between launches. */
data class ToolState(
    val difficulty: Difficulty = Difficulty.default,
    /** Rub a digit out of its peers' pencil marks when it is written. */
    val autoClean: Boolean = true,
    /**
     * Mark a written digit that is not the answer.
     *
     * Off by default. With it on you cannot go wrong, which is a different and
     * much smaller game — so turning it on is a decision the player makes rather
     * than a favour the app does.
     */
    val checkAsYouGo: Boolean = false,
    /** Where the seed counter is up to, so New never repeats a puzzle. */
    val nextSeed: Int = 1,
    /** The board the player last touched, if they left it unfinished. */
    val save: SaveState? = null,
    /** How many puzzles have been finished, per grade. */
    val solved: Map<Difficulty, Int> = emptyMap(),
)

/**
 * The tool's persisted state, on the `DataStore<Preferences>` the SDK hands every
 * screen.
 *
 * All of it is small — one board, four counters and three settings — so
 * preferences suit the shape and a database would be ceremony. It comes back as
 * one flow the UI collects, so a single read keeps every view consistent.
 */
class SudokuStore(private val dataStore: DataStore<Preferences>) {

    private val difficultyKey = stringPreferencesKey("difficulty")
    private val autoCleanKey = booleanPreferencesKey("auto_clean")
    private val checkKey = booleanPreferencesKey("check_as_you_go")
    private val nextSeedKey = intPreferencesKey("next_seed")
    private val saveKey = stringPreferencesKey("save")
    private val solvedKey = stringPreferencesKey("solved_counts")

    val state: Flow<ToolState> = dataStore.data.map { p ->
        ToolState(
            // An unknown grade means a hand-edited or downgraded preference.
            // Falling back beats refusing to start.
            difficulty = Difficulty.byLabel(p[difficultyKey]) ?: Difficulty.default,
            autoClean = p[autoCleanKey] ?: true,
            checkAsYouGo = p[checkKey] ?: false,
            nextSeed = p[nextSeedKey] ?: 1,
            save = SaveState.decode(p[saveKey]),
            solved = decodeSolved(p[solvedKey]),
        )
    }

    /**
     * Remember the board. Written after every digit, which is what the rest of
     * the collection does: preferences coalesce writes and the payload is a few
     * hundred characters.
     */
    suspend fun saveGame(save: SaveState) {
        dataStore.edit { it[saveKey] = save.encode() }
    }

    suspend fun clearGame() {
        dataStore.edit { it.remove(saveKey) }
    }

    suspend fun setDifficulty(difficulty: Difficulty) {
        dataStore.edit { it[difficultyKey] = difficulty.label }
    }

    suspend fun setAutoClean(enabled: Boolean) {
        dataStore.edit { it[autoCleanKey] = enabled }
    }

    suspend fun setCheckAsYouGo(enabled: Boolean) {
        dataStore.edit { it[checkKey] = enabled }
    }

    /**
     * Move the seed counter past [seed].
     *
     * Read-modify-write inside `edit` so two quick deals cannot both read the
     * same number and hand out the same puzzle twice.
     */
    suspend fun advanceSeed(seed: Int) {
        dataStore.edit { p ->
            val next = (seed + 1) and 0x7FFFFFFF
            if (next > (p[nextSeedKey] ?: 1)) p[nextSeedKey] = next
        }
    }

    suspend fun recordSolved(difficulty: Difficulty) {
        dataStore.edit { p ->
            val counts = decodeSolved(p[solvedKey]).toMutableMap()
            counts[difficulty] = (counts[difficulty] ?: 0) + 1
            p[solvedKey] = encodeSolved(counts)
        }
    }

    suspend fun resetAll() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        /**
         * `Gentle:3,Severe:1`. Written by hand rather than with a serialiser
         * because the tool module pulls in no JSON library, and because anything
         * unparseable here has to read as "no puzzles solved" rather than as a
         * crash on launch.
         */
        fun encodeSolved(counts: Map<Difficulty, Int>): String =
            counts.entries.filter { it.value > 0 }.joinToString(",") { "${it.key.label}:${it.value}" }

        fun decodeSolved(raw: String?): Map<Difficulty, Int> {
            if (raw.isNullOrBlank()) return emptyMap()
            val out = HashMap<Difficulty, Int>()
            for (part in raw.split(",")) {
                val label = part.substringBefore(":", "")
                val count = part.substringAfter(":", "").toIntOrNull() ?: continue
                val difficulty = Difficulty.byLabel(label) ?: continue
                if (count > 0) out[difficulty] = count
            }
            return out
        }
    }
}
