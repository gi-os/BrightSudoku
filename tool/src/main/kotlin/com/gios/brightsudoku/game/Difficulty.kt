package com.gios.brightsudoku.game

/**
 * How hard a puzzle is, defined as the hardest technique needed to finish it.
 *
 * Not a clue count. Clue count is what most apps show and it is close to
 * meaningless: a 30-clue puzzle can fall to nothing but singles while a 32-clue
 * one needs an x-wing. Grading by the reasoning actually required is the only
 * definition that matches what a player feels, and since the app already has to
 * prove every puzzle is solvable by logic ([Solver.solveLogically]), the grade
 * costs nothing extra to produce.
 *
 * Each grade is a ceiling. A Steady puzzle needs locked digits *somewhere*, and
 * nothing harder anywhere.
 */
enum class Difficulty(
    val label: String,
    /** The hardest technique a puzzle of this grade is allowed to need. */
    val ceiling: Technique,
    /**
     * How far down to dig before the generator starts checking the grade.
     *
     * A floor, not a quota. Digging further than a grade needs only wastes the
     * work of putting the clues back, and digging a Gentle puzzle to 24 clues
     * makes something that is not Gentle at all.
     */
    val clueFloor: Int,
    /** What the player is told they are in for. */
    val blurb: String,
) {
    GENTLE("Gentle", Technique.HIDDEN_SINGLE, 34, "Singles all the way through"),
    STEADY("Steady", Technique.LOCKED, 30, "Some digits have to be pinned to a line"),
    TRICKY("Tricky", Technique.HIDDEN_SUBSET, 26, "Pairs and triples come into it"),
    SEVERE("Severe", Technique.SWORDFISH, 21, "Wings and fish, at least once"),
    ;

    /**
     * The easiest technique a puzzle of this grade must need at least once.
     *
     * A grade is a band, not a ceiling alone: Steady means locked digits are
     * needed *and* nothing harder. Without this floor every Gentle puzzle would
     * also qualify as Steady, and the generator would hand back singles-only
     * grids whatever was asked of it.
     */
    val floorRank: Int
        get() {
            val previous = entries.getOrNull(ordinal - 1) ?: return 0
            return previous.ceiling.rank + 1
        }

    /** Does a finished solve sit in this grade's band? */
    fun matches(result: LogicResult): Boolean {
        if (!result.solved) return false
        val hardest = result.hardest?.rank ?: 0
        return hardest <= ceiling.rank && hardest >= floorRank
    }

    companion object {
        val default = STEADY

        fun byLabel(label: String?): Difficulty? = entries.firstOrNull { it.label == label }
    }
}
