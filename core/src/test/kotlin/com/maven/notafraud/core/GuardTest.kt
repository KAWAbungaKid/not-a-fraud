package com.maven.notafraud.core

import com.maven.notafraud.core.guard.Credits
import com.maven.notafraud.core.guard.GuardAction
import com.maven.notafraud.core.guard.GuardPolicy
import com.maven.notafraud.core.guard.NagPolicy
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Ledger
import com.maven.notafraud.core.model.Settings
import com.maven.notafraud.core.plan.Time
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuardTest {
    private val now = 1_800_000_000_000L
    private val ig = "com.instagram.android"

    @Test
    fun `blocked app is blocked without credit and unlocked after spending`() {
        val d = AppData(ledger = Ledger(balanceMin = 20.0))
        assertEquals(GuardAction.BLOCK, GuardPolicy.decide(ig, d, now).action)
        val spent = Credits.spend(d.ledger, ig, 10, "Instagram", now)
        assertNotNull(spent)
        assertEquals(10.0, spent.balanceMin, 1e-9)
        val d2 = d.copy(ledger = spent)
        assertEquals(GuardAction.UNLOCKED, GuardPolicy.decide(ig, d2, now + 5 * Time.MIN).action)
        assertEquals(GuardAction.BLOCK, GuardPolicy.decide(ig, d2, now + 11 * Time.MIN).action)
    }

    @Test
    fun `cannot spend more than the balance`() {
        assertNull(Credits.spend(Ledger(balanceMin = 4.0), ig, 5, "Instagram", now))
    }

    @Test
    fun `focus apps earn, system apps ignored`() {
        val d = AppData()
        assertEquals(GuardAction.EARN, GuardPolicy.decide("com.amazon.kindle", d, now).action)
        assertEquals(GuardAction.IGNORE, GuardPolicy.decide("com.sec.android.app.launcher", d, now).action)
        assertEquals(GuardAction.IGNORE, GuardPolicy.decide("com.maven.notafraud.dev", d, now).action)
    }

    @Test
    fun `earning is capped at max balance and merges entries`() {
        val s = Settings(maxBalanceMin = 30.0)
        var l = Ledger(balanceMin = 25.0)
        l = Credits.earn(l, s, 2.0, 1.0, "Reading", now)
        l = Credits.earn(l, s, 2.0, 1.0, "Reading", now + Time.MIN)
        l = Credits.earn(l, s, 10.0, 1.0, "Reading", now + 2 * Time.MIN)
        assertEquals(30.0, l.balanceMin, 1e-9)
        assertEquals(1, l.entries.size)
    }

    @Test
    fun `emergency unlocks are limited per day`() {
        val s = Settings(emergencyDailyLimit = 2, emergencyUnlockMin = 5)
        var l = Ledger(balanceMin = 0.0)
        val startOfDay = now - Time.HOUR
        l = Credits.emergency(l, s, ig, "IG", now, startOfDay)!!
        l = Credits.emergency(l, s, ig, "IG", now + Time.MIN, startOfDay)!!
        assertNull(Credits.emergency(l, s, ig, "IG", now + 2 * Time.MIN, startOfDay))
        assertTrue(Credits.isUnlocked(l, ig, now + 3 * Time.MIN))
    }

    @Test
    fun `rough day nag chain is shorter and gentler`() {
        assertEquals(3, NagPolicy.offsetsMin(rough = true).size)
        assertEquals(5, NagPolicy.offsetsMin(rough = false).size)
        val n = NagPolicy.nag(0, "Instagram", 10, "HW 5", "creed", rough = true)
        assertTrue(n.text.contains("HW 5"))
    }
}
