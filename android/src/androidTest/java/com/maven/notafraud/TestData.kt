package com.maven.notafraud

import androidx.test.platform.app.InstrumentationRegistry
import com.maven.notafraud.core.seed.Seed

/** Fresh installs now start with onboarding; most tests want a full, set-up app (Maven's own data). */
object TestData {
    fun loadFull() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Graph.init(ctx)
        Graph.update { Seed.data(System.currentTimeMillis()).copy(settings = Seed.data(System.currentTimeMillis()).settings.copy(onboarded = true)) }
    }
}
