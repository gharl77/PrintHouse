package com.printhouse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private fun bright(hhmm: String) = Logic.brightness(Logic.minutes(hhmm)!!, 7 * 60 + 30, 19 * 60 + 30, 0.8f, 0.25f)

    @Test fun `brightness is full by day and low in the evening`() {
        assertEquals(0.8f, bright("12:00"), 0.001f)
        assertEquals(0.25f, bright("22:00"), 0.001f)
        assertEquals(0.25f, bright("03:00"), 0.001f)
    }

    @Test fun `brightness changes gradually for half an hour after each boundary`() {
        assertEquals(0.25f, bright("07:30"), 0.001f)
        assertEquals(0.525f, bright("07:45"), 0.001f)
        assertEquals(0.8f, bright("08:00"), 0.001f)
        assertEquals(0.8f, bright("19:30"), 0.001f)
        assertEquals(0.525f, bright("19:45"), 0.001f)
        assertEquals(0.25f, bright("20:00"), 0.001f)
    }

    @Test fun `brightness never reaches zero`() {
        assertEquals(0.01f, Logic.brightness(0, 420, 1200, 0.5f, 0f), 0.0001f)
    }

    @Test fun `minutes reads hours written several ways`() {
        assertEquals(1350, Logic.minutes("22:30"))
        assertEquals(390, Logic.minutes(" 6:30 "))
        assertEquals(1320, Logic.minutes("22h"))
        assertNull(Logic.minutes("24:00"))
        assertNull(Logic.minutes("soir"))
    }

    @Test fun `age counts whole years and months`() {
        assertEquals(3 to 0, Logic.age("2016-07-14", "2019-07-14T10:00:00.000Z"))
        assertEquals(2 to 11, Logic.age("2016-07-14", "2019-07-13T10:00:00.000Z"))
        assertEquals(0 to 4, Logic.age("2019-03-02", "2019-07-10"))
        assertEquals(0 to 0, Logic.age("2019-07-01", "2019-07-10"))
    }

    @Test fun `age is unknown before birth or with unreadable dates`() {
        assertNull(Logic.age("2020-01-01", "2019-07-10"))
        assertNull(Logic.age("", "2019-07-10"))
        assertNull(Logic.age("2016-07-14", "hier"))
    }

    @Test fun `birthday matches the day and month`() {
        assertTrue(Logic.isBirthday("2012-11-13", 11, 13, leapYear = false))
        assertFalse(Logic.isBirthday("2012-11-13", 11, 14, leapYear = false))
        assertFalse(Logic.isBirthday("sans date", 11, 13, leapYear = false))
    }

    @Test fun `a 29 February birthday falls on the 28th outside leap years`() {
        assertTrue(Logic.isBirthday("2012-02-29", 2, 28, leapYear = false))
        assertFalse(Logic.isBirthday("2012-02-29", 2, 28, leapYear = true))
        assertTrue(Logic.isBirthday("2012-02-29", 2, 29, leapYear = true))
    }

    @Test fun `the presence sensor is read slowly at night and quickly when the screen is off by day`() {
        assertEquals(30_000L, Logic.presencePollMs(night = true, screenOff = true))
        assertEquals(30_000L, Logic.presencePollMs(night = true, screenOff = false))
        assertEquals(2_000L, Logic.presencePollMs(night = false, screenOff = true))
        assertEquals(5_000L, Logic.presencePollMs(night = false, screenOff = false))
    }

    @Test fun `the search for memories stays small, and albums and people add up`() {
        val people = (1..10).map { "p$it" }
        assertEquals(listOf<Pair<String?, String?>>(null to null), Logic.memoryCombos(emptyList(), emptyList(), 2))
        assertEquals(2, Logic.memoryCombos(emptyList(), people, 2).size)
        assertEquals(2, Logic.memoryCombos(listOf("a", "b", "c"), people, 2).size)
        assertEquals(setOf<Pair<String?, String?>>("a" to null, null to "p1"), Logic.memoryCombos(listOf("a"), listOf("p1"), 5).toSet())
        assertTrue("un album OU une personne, jamais les deux", Logic.memoryCombos(listOf("a", "b"), people, 12).none { it.first != null && it.second != null })
    }

    @Test fun `a batch is shared between the lists according to their weights`() {
        assertEquals(listOf(4, 4, 4), Logic.shares(listOf(2, 2, 2), 12))
        assertEquals(listOf(0, 12, 0), Logic.shares(listOf(0, 2, 0), 12))
        assertEquals(listOf(6, 0, 6), Logic.shares(listOf(2, 0, 2), 12))
        assertEquals("beaucoup de personnes, un peu de thèmes", listOf(10, 2, 0), Logic.shares(listOf(4, 1, 0), 12))
        assertEquals(listOf(7, 2, 3), Logic.shares(listOf(4, 1, 2), 12))
        assertEquals(listOf(0, 0, 0), Logic.shares(listOf(0, 0, 0), 12))
        for (w in listOf(listOf(1, 2, 4), listOf(4, 4, 1), listOf(1, 1, 1), listOf(4, 0, 1))) {
            val s = Logic.shares(w, 12)
            assertEquals("$w → $s", 12, s.sum())
            assertTrue("chaque liste non vide a sa place : $w → $s", w.indices.all { (w[it] > 0) == (s[it] > 0) })
        }
    }

    @Test fun `a list is drawn according to its weight, empty lists never`() {
        val weights = listOf(4, 0, 1) // personnes : beaucoup ; thèmes : liste vide ; albums : un peu
        assertEquals(listOf(0, 0, 0, 0, 2), (0 until 5).map { Logic.pickWeighted(weights, it) })
        assertEquals(-1, Logic.pickWeighted(listOf(0, 0, 0), 0))
        assertEquals(1, Logic.pickWeighted(listOf(0, 2, 0), 1))
    }

    private val week = (22 * 60) to (6 * 60)        // 22:00 → 06:00
    private val weekend = (23 * 60 + 30) to (8 * 60) // 23:30 → 08:00
    private fun night(day: Int, hhmm: String, we: Pair<Int, Int>? = weekend) = Logic.isNight(Logic.minutes(hhmm)!!, day, week, we)

    @Test fun `a week night spans midnight`() {
        assertTrue(night(2, "22:30"))
        assertTrue(night(3, "05:59"))
        assertFalse(night(3, "06:00"))
        assertFalse(night(3, "21:59"))
    }

    @Test fun `Friday and Saturday nights use the weekend hours, until the next morning`() {
        assertFalse("vendredi 22:30 : pas encore la nuit", night(5, "22:30"))
        assertTrue(night(5, "23:45"))
        assertTrue("samedi 07:30 : la nuit de vendredi dure encore", night(6, "07:30"))
        assertFalse(night(6, "08:00"))
        assertTrue("dimanche 07:30 : la nuit de samedi", night(7, "07:30"))
        assertTrue("dimanche soir : heures de semaine", night(7, "22:30"))
        assertFalse("lundi 07:00 : la nuit de dimanche a fini à 06:00", night(1, "07:00"))
    }

    @Test fun `without weekend hours every night is the same`() {
        assertTrue(night(5, "22:30", null))
        assertFalse(night(6, "07:30", null))
    }

    @Test fun `a night that does not span midnight`() {
        val nap = (13 * 60) to (15 * 60)
        assertTrue(Logic.isNight(14 * 60, 3, nap, null))
        assertFalse(Logic.isNight(15 * 60, 3, nap, null))
        assertFalse(Logic.isNight(60, 3, nap, null))
    }

    @Test fun `versions compare number by number`() {
        assertTrue(Logic.compareVersions("2026.11.2", "2026.11.1") > 0)
        assertTrue(Logic.compareVersions("2026.11.10", "2026.11.9") > 0)
        assertTrue(Logic.compareVersions("2026.12.1", "2026.11.9") > 0)
        assertTrue(Logic.compareVersions("2027.1.1", "2026.12.9") > 0)
        assertEquals(0, Logic.compareVersions("2026.11.1", "2026.11.1"))
        assertTrue(Logic.compareVersions("2026.11", "2026.11.1") < 0)
    }

    @Test fun `screenshots are recognised by name or by a PNG with no camera`() {
        assertTrue(Logic.looksLikeScreenshot("Screenshot_20240102-101112.jpg", "image/jpeg", true))
        assertTrue(Logic.looksLikeScreenshot("Capture d’écran 2024-01-02.png", "image/png", null))
        assertTrue(Logic.looksLikeScreenshot("IMG_0001.png", "image/png", false))
        assertFalse(Logic.looksLikeScreenshot("IMG_0001.png", "image/png", true))
        assertFalse(Logic.looksLikeScreenshot("IMG_0001.png", "image/png", null))
        assertFalse(Logic.looksLikeScreenshot("IMG_0001.jpg", "image/jpeg", false))
    }
}
