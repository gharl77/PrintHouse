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

    @Test fun `the search for memories stays small whatever the filters`() {
        val people = (1..10).map { "p$it" }
        assertEquals(listOf<Pair<String?, String?>>(null to null), Logic.memoryCombos(emptyList(), emptyList(), 2))
        assertEquals(2, Logic.memoryCombos(emptyList(), people, 2).size)
        assertEquals(2, Logic.memoryCombos(listOf("a", "b", "c"), people, 2).size)
        assertTrue(Logic.memoryCombos(listOf("a"), people, 2).all { it.first == "a" && it.second in people })
        assertEquals(setOf("a" to "p1"), Logic.memoryCombos(listOf("a"), listOf("p1"), 2).toSet())
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
