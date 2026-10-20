package com.maven.notafraud.core

import com.maven.notafraud.core.guard.GuardAction
import com.maven.notafraud.core.guard.GuardPolicy
import com.maven.notafraud.core.guard.Sites
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.AppRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SitesTest {
    private val blocked = AppRules.DEFAULT_SITES

    @Test
    fun `address bar forms match their domain`() {
        assertEquals("instagram.com", Sites.match("instagram.com/reel/abc", blocked))
        assertEquals("instagram.com", Sites.match("https://www.instagram.com/", blocked))
        assertEquals("facebook.com", Sites.match("m.facebook.com", blocked))
        assertEquals("youtube.com", Sites.match("youtube.com/watch?v=1", blocked))
        assertNull(Sites.match("notinstagram.com", blocked))
        assertNull(Sites.match("google.com/search?q=instagram", blocked))
        assertNull(Sites.match("how to block instagram", blocked))
    }

    @Test
    fun `site subjects are blocked and unlockable`() {
        val d = AppData()
        assertEquals(GuardAction.BLOCK, GuardPolicy.decide("site:instagram.com", d, 0).action)
        assertEquals(GuardAction.IGNORE, GuardPolicy.decide("site:example.com", d, 0).action)
        assertEquals(GuardAction.IGNORE, GuardPolicy.decide("com.android.chrome", d, 0).action)
    }
}
