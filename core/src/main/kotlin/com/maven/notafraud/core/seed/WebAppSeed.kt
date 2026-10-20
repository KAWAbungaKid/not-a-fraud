package com.maven.notafraud.core.seed

/*
 * Snapshot of the Project Max web app's projects and tasks (read 2026-10-20), so the phone
 * starts with the same list. Generated; edit in the app, not here.
 */
internal object WebAppSeed {
    data class P(val key: String, val name: String, val kind: String)
    data class T(
        val key: String, val title: String, val project: String, val estMin: Int, val dueIso: String?,
        val priority: String, val energy: String, val minBlockMin: Int, val done: Boolean, val doneIso: String?,
    )

    val projects = listOf(
        P("p-bio3333", "BIO SCI 3333 Human A&P", "school"),
        P("p-biosensor", "Biosensor research", "research"),
        P("p-career", "Career and applications", "admin"),
        P("p-maxapp", "Not a Fraud app", "project"),
        P("p-me3411", "ME 3411 Dynamic Systems", "school"),
        P("p-me3521", "ME 3521 Applied Thermo", "school"),
        P("p-me3525", "ME 3525 Heat Transfer", "school"),
        P("p-me4840", "ME 4840 Instrumentation", "school"),
        P("p-ptdevice", "PT device", "project"),
        P("p-scout", "Scout STEM program", "project"),
    )

    val tasks = listOf(
        T("t-android-hello", "Get a hello-world Android build onto the Samsung", "p-maxapp", 150, null, "normal", "high", 60, false, null),
        T("t-backlog-list", "Write the actual catch-up backlog: which A&P chapters, which thermo and heat transfer problem sets", "p-career", 60, "2026-10-12T23:00:00.000Z", "high", "med", 30, false, null),
        T("t-cswe", "CSWE practice session", "p-career", 90, null, "low", "med", 45, false, null),
        T("t-ex-3411-2", "Study for ME 3411 Exam 2", "p-me3411", 300, "2026-10-13T13:00:00.000Z", "high", "high", 60, false, null),
        T("t-ex-3411-3", "Study for ME 3411 Exam 3", "p-me3411", 300, "2026-11-17T14:00:00.000Z", "high", "high", 60, false, null),
        T("t-ex-3411-final", "Study for ME 3411 Final", "p-me3411", 480, "2026-12-16T18:30:00.000Z", "high", "high", 60, false, null),
        T("t-ex-3525-2", "Study for Heat Transfer Test 2", "p-me3525", 360, "2026-11-17T17:00:00.000Z", "high", "high", 60, false, null),
        T("t-ex-3525-3", "Study for Heat Transfer Final", "p-me3525", 480, "2026-12-15T17:00:00.000Z", "high", "high", 60, true, "2026-10-14T18:32:57.623Z"),
        T("t-ex-ap-3", "Study for A&P Test 3", "p-bio3333", 420, "2026-10-21T16:00:00.000Z", "high", "high", 45, false, null),
        T("t-ex-ap-4", "Study for A&P Test 4", "p-bio3333", 420, "2026-11-13T17:00:00.000Z", "high", "high", 45, false, null),
        T("t-ex-ap-5", "Study for A&P Test 5", "p-bio3333", 420, "2026-12-11T17:00:00.000Z", "high", "high", 45, false, null),
        T("t-internship", "Close out internship properly: ask for a reference and a LinkedIn recommendation", "p-career", 30, "2026-10-17T22:00:00.000Z", "high", "med", 20, false, null),
        T("t-pi-batching", "Ask PI about batching all three exposure durations in one session day", "p-biosensor", 20, "2026-10-13T22:00:00.000Z", "high", "low", 15, false, null),
        T("t-pt-ip", "Check university IP policy before using school labs on the PT device", "p-ptdevice", 45, null, "high", "med", 30, false, null),
        T("t-pt-parts", "Order PT device proof-of-concept parts", "p-ptdevice", 45, null, "high", "low", 20, false, null),
        T("t-pt-scope", "Define the one thing the PT device prototype must prove", "p-ptdevice", 90, null, "high", "high", 45, false, null),
        T("t-scout-manual", "Scout manual: draft one section", "p-scout", 90, null, "normal", "med", 45, false, null),
        T("t-summer-apps", "Summer 2027 internship applications: medical device and robotics", "p-career", 600, "2026-12-15T23:00:00.000Z", "high", "med", 45, false, null),
        T("t-thermo-dates", "Email thermo and instrumentation professors for test dates", "p-career", 15, "2026-10-13T22:00:00.000Z", "high", "low", 15, false, null),
    )
}
