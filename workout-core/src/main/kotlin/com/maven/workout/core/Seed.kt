package com.maven.workout.core

import com.maven.workout.core.Joint.*
import com.maven.workout.core.Measure.*
import com.maven.workout.core.Muscle.*

/**
 * Starting data, from Maven's workout.xlsx (Google Sheets, Oct 2026):
 * - progression types = REFERENCE!L:X (RPE<type> / REPS<type> rows, 4 weeks × 3 blocks),
 * - exercise → type = REFERENCE!H:I,
 * - maxes = Maxes!B:C (all entered as RPE 10, as the sheet assumed),
 * - the program = his Oct 26 2026 split: Push / Pull / Legs as the 3-day minimum, then
 *   Chest & Back / Shoulders & Arms / Legs 2 on days 4–6.
 * Changes he asked for: Arnold press dropped; shoulder press is now DB and uses the incline ("secondary") type.
 */
object Seed {
    private fun s(sets: Int, reps: Int?, rpe: Double?, top: Int? = null, test: Boolean = false, secs: Int? = null) =
        Step(sets = sets, reps = reps, seconds = secs, rpe = rpe, topReps = top, test = test)

    private val RAMP = listOf(6.0, 7.0, 8.0, 9.0, 6.0, 7.0, 8.0, 9.0, 7.0, 8.0, 9.0, 10.0)
    private val FLAT9 = listOf(6.0, 9.0, 9.0, 9.0, 6.0, 9.0, 9.0, 9.0, 6.0, 9.0, 9.0, 9.0)

    /** Main lift: back-off reps per block, top set reps per block (3/2/1), last week = max test. */
    private fun main(id: String, name: String, backoff: List<Int>) = Progression(
        id, name,
        steps = (0 until 12).map { i ->
            val b = i / 4
            s(3, backoff[b], RAMP[i], top = listOf(3, 2, 1)[b], test = i == 11)
        },
        notes = "Top set + 3 back-off sets. RPE climbs 6→9 each block (7→10 in block 3). Week 12 = test a new max.",
    )

    val progressions: List<Progression> = listOf(
        main("bench", "Bench (main)", listOf(4, 3, 2)),
        main("deadlift", "Deadlift (main)", listOf(5, 4, 3)),
        main("squat", "Squat (main)", listOf(8, 5, 3)),
        Progression(
            "secondary", "Secondary press (incline)",
            steps = (0 until 12).map { i -> s(3, listOf(6, 5, 4)[i / 4], RAMP[i]) },
            notes = "Sheet type Bench1. Reps drop each block, RPE climbs each week.",
        ),
        Progression(
            "type1", "Type 1 · 8→10 reps",
            steps = (0 until 12).map { i -> s(3, listOf(8, 8, 9, 10)[i % 4], FLAT9[i]) },
            notes = "Double progression: hold RPE 9, add reps through the block. Week 1 of each block is RPE 6.",
        ),
        Progression(
            "type2", "Type 2 · 10→12 reps",
            steps = (0 until 12).map { i -> s(3, listOf(10, 10, 11, 12)[i % 4], FLAT9[i]) },
            notes = "Double progression for accessories: hold RPE 9, add reps. Week 1 of each block is RPE 6.",
        ),
        Progression(
            "type3", "Type 3 · 3×8 RPE ramp",
            steps = (0 until 12).map { i -> s(3, 8, RAMP[i]) },
            notes = "Reps stay at 8, effort climbs each week.",
        ),
        Progression(
            "chinup", "Chin up",
            steps = (0 until 12).map { i -> s(3, 1, FLAT9[i]) },
            notes = "Sheet type ChinUp: 3×1. Weight = added weight. Probably wants real numbers.",
        ),
        Progression(
            "hold", "Hold to failure",
            steps = List(12) { s(3, null, null) },
            notes = "Timed: hit Go, stop when you fail. The app shows your best to beat.",
        ),
    )

    private fun ex(
        id: String, name: String, type: String?, muscles: Set<Muscle>, joints: Set<Joint>,
        round: Double = 5.0, measure: Measure = WEIGHT_REPS,
    ) = Exercise(id, name, measure, type, muscles, joints, round)

    val exercises: List<Exercise> = listOf(
        // Push
        ex("bench", "Bench", "bench", setOf(CHEST, TRICEPS, SHOULDERS), setOf(SHOULDER, ELBOW, WRIST)),
        ex("incline-bench", "Incline Bench", "secondary", setOf(CHEST, SHOULDERS, TRICEPS), setOf(SHOULDER, ELBOW)),
        ex("shoulder-press", "Shoulder Press (DB)", "secondary", setOf(SHOULDERS, TRICEPS), setOf(SHOULDER, ELBOW)),
        ex("machine-fly", "Machine Fly", "type2", setOf(CHEST), setOf(SHOULDER)),
        ex("skullcrusher", "Elevated Skullcrusher", "type2", setOf(TRICEPS), setOf(ELBOW)),
        ex("db-lateral", "Dumbbell Lateral Raise", "type2", setOf(SHOULDERS), setOf(SHOULDER)),
        ex("tricep-dip", "Tricep Dip", "type2", setOf(TRICEPS, CHEST), setOf(SHOULDER, ELBOW)),
        ex("cable-tricep", "Cable Tricep Extension", "type2", setOf(TRICEPS), setOf(ELBOW), round = 2.5),
        ex("cable-lateral", "Cable Lateral Raise", "type2", setOf(SHOULDERS), setOf(SHOULDER), round = 2.5),
        // Pull
        ex("deadlift", "Deadlift", "deadlift", setOf(BACK, HAMSTRINGS, GLUTES, FOREARMS), setOf(LOWER_BACK, HIP, WRIST)),
        ex("barbell-row", "Barbell Row", "type3", setOf(BACK, BICEPS), setOf(LOWER_BACK, ELBOW)),
        ex("chin-up", "Chin Up", "chinup", setOf(BACK, BICEPS), setOf(SHOULDER, ELBOW)),
        ex("cable-high-row", "Cable High Row", "type2", setOf(BACK, SHOULDERS), setOf(ELBOW), round = 2.5),
        ex("db-curl", "Dumbbell Curl", "type1", setOf(BICEPS), setOf(ELBOW, WRIST)),
        ex("face-pull", "Face Pull", "type2", setOf(SHOULDERS, BACK), emptySet(), round = 2.5),
        ex("pull-apart", "Pull Apart", "type2", setOf(SHOULDERS, BACK), emptySet()),
        ex("lat-pulldown", "Lat Pulldown", "type2", setOf(BACK, BICEPS), setOf(SHOULDER, ELBOW), round = 2.5),
        ex("cable-curl", "Cable Curl", "type2", setOf(BICEPS), setOf(ELBOW, WRIST), round = 2.5),
        // Legs
        ex("squat", "Squat", "squat", setOf(QUADS, GLUTES, CORE), setOf(KNEE, HIP, LOWER_BACK)),
        ex("hack-squat", "Hack Squat", "type1", setOf(QUADS, GLUTES), setOf(KNEE, HIP)),
        ex("calf-raise", "Calf Raises", "type2", setOf(CALVES), setOf(ANKLE)),
        ex("leg-extension", "Leg Extension", "type2", setOf(QUADS), setOf(KNEE)),
        ex("split-squat", "Split Squat", "type2", setOf(QUADS, GLUTES), setOf(KNEE, HIP)),
        ex("hip-machine", "Hip Machine", "type2", setOf(GLUTES), setOf(HIP)),
        ex("rdl", "RDL", "type1", setOf(HAMSTRINGS, GLUTES), setOf(LOWER_BACK, HIP)),
        ex("leg-curl", "Leg Curl", "type2", setOf(HAMSTRINGS), setOf(KNEE)),
        ex("tibia", "Tibia Raise", "type2", setOf(SHINS), setOf(ANKLE)),
        ex("hip-raise", "Hip Raise", "type2", setOf(GLUTES, HAMSTRINGS), setOf(HIP)),
        // New: timed
        ex("plank", "Plank", "hold", setOf(CORE), emptySet(), measure = TIME),
    )

    /** Maxes!C, dated the day the sheet was exported. Shoulder press is his Oct 26 set: 75×8, "could have done 9 or 10". */
    fun maxes(date: String): List<MaxEntry> {
        fun m(id: String, w: Double, r: Int, rpe: Double = 10.0) = MaxEntry(id, date, w, r, rpe)
        return listOf(
            m("bench", 245.0, 4), m("incline-bench", 175.0, 6), m("shoulder-press", 75.0, 8, 8.5),
            m("machine-fly", 190.0, 11), m("skullcrusher", 80.0, 10), m("db-lateral", 25.0, 10),
            m("tricep-dip", 25.0, 12), m("cable-tricep", 17.5, 12), m("cable-lateral", 25.0, 12),
            m("deadlift", 365.0, 1), m("barbell-row", 185.0, 10), m("chin-up", 5.0, 1),
            m("cable-high-row", 42.5, 12), m("db-curl", 30.0, 10), m("face-pull", 72.5, 12),
            m("pull-apart", 20.0, 12), m("lat-pulldown", 160.0, 12), m("cable-curl", 57.5, 8),
            m("squat", 185.0, 12), m("hack-squat", 30.0, 12), m("calf-raise", 135.0, 12),
            m("leg-extension", 55.0, 12), m("hip-machine", 75.0, 12), m("rdl", 185.0, 12),
            m("leg-curl", 20.0, 1), m("tibia", 20.0, 1), m("hip-raise", 20.0, 2), m("split-squat", 70.0, 12),
        )
    }

    val program = Program(
        id = "maven-ppl-6",
        name = "Maven PPL + upper/arms (from sheet)",
        weeksPerBlock = 4, blocks = 3, coreDays = 3,
        days = listOf(
            DayTemplate("push", "Push", listOf("bench", "incline-bench", "shoulder-press", "machine-fly", "skullcrusher", "db-lateral")),
            DayTemplate("pull", "Pull", listOf("deadlift", "barbell-row", "chin-up", "cable-high-row", "db-curl", "face-pull", "pull-apart")),
            DayTemplate("legs", "Legs", listOf("squat", "hack-squat", "calf-raise", "leg-extension", "split-squat", "hip-machine", "plank")),
            DayTemplate("chest-back", "Chest & Back", listOf("bench", "barbell-row", "machine-fly", "lat-pulldown", "cable-high-row", "chin-up")),
            DayTemplate("shoulders-arms", "Shoulders & Arms", listOf("shoulder-press", "cable-lateral", "face-pull", "pull-apart", "tricep-dip", "cable-tricep", "db-curl", "cable-curl")),
            DayTemplate("legs-2", "Legs 2", listOf("squat", "rdl", "calf-raise", "leg-curl", "hip-raise", "tibia", "plank")),
        ),
        notes = "Days 1–3 are the minimum (Push/Pull/Legs). Days 4–6 split smaller: chest & back, shoulders & arms, legs.",
    )

    fun data(today: String): WorkoutData = WorkoutData(
        exercises = exercises,
        progressions = progressions,
        programs = listOf(program),
        activeProgramId = program.id,
        // The sheet was set to Week 2, Block 1.
        cycle = CycleState(week = 2, block = 1),
        maxes = maxes(today),
    )
}

object Migration {
    const val CURRENT = 1
    fun apply(d: WorkoutData): WorkoutData = if (d.version >= CURRENT) d else d.copy(version = CURRENT)
}
