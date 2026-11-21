package com.printhouse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    private val all = Looks.FRAMES

    @Test fun `a list of choices is never empty and ignores unknown names`() {
        assertEquals(listOf("mat"), Looks.clean("", Looks.FRAMES))
        assertEquals(listOf("mat"), Looks.clean("oval,neon", Looks.FRAMES))
        assertEquals(listOf("border", "album"), Looks.clean("album, border,oval", Looks.FRAMES))
    }

    @Test fun `a single choice is always used`() {
        for (seed in 0..50) assertEquals(Look("gallery", "cotton", "sepia", true),
            Looks.pick(listOf("gallery"), listOf("cotton"), listOf("sepia"), listOf("hand"), seed))
    }

    @Test fun `the same photo always gets the same look`() {
        val a = Looks.pick(all, Looks.PAPERS, Looks.TONES, Looks.CAPTIONS, "photo-42".hashCode())
        val b = Looks.pick(all, Looks.PAPERS, Looks.TONES, Looks.CAPTIONS, "photo-42".hashCode())
        assertEquals(a, b)
    }

    @Test fun `with several choices every one comes up`() {
        val looks = (1..400).map { Looks.pick(all, Looks.PAPERS, Looks.TONES, Looks.CAPTIONS, "id$it".hashCode()) }
        assertEquals(all.toSet(), looks.map { it.frame }.toSet())
        assertEquals(Looks.PAPERS.toSet(), looks.map { it.paper }.toSet())
        assertEquals(Looks.TONES.toSet(), looks.map { it.tone }.toSet())
        assertEquals(setOf(true, false), looks.map { it.hand }.toSet())
        // La couleur garde environ une page sur deux, le noir et blanc et le sépia se partagent le reste.
        assertTrue(looks.count { it.tone == "color" } in 160..240)
        // Aucun cadre ne domine : chacun entre la moitié et le double de sa part.
        for (f in all) assertTrue(f, looks.count { it.frame == f } in 33..133)
    }

    @Test fun `a contact sheet needs three frames, otherwise another chosen frame is used`() {
        val contact = Look("contact", "matte", "color", false)
        assertEquals("contact", Looks.settle(contact, all, 3).frame)
        assertEquals("border", Looks.settle(contact, listOf("contact"), 1).frame)
        assertEquals("instant", Looks.settle(contact, listOf("instant", "contact"), 2).frame)
        assertTrue(Looks.settle(contact, all, 1).frame in all - "contact")
        assertEquals("album", Looks.settle(Look("album", "matte", "color", false), all, 1).frame)
    }

    @Test fun `room light follows a logarithmic scale`() {
        assertEquals(0f, Logic.luxLevel(0f), 0.001f)
        assertEquals(0f, Logic.luxLevel(5f), 0.001f)
        assertEquals(1f, Logic.luxLevel(300f), 0.001f)
        assertEquals(1f, Logic.luxLevel(20000f), 0.001f)
        assertEquals(0.5f, Logic.luxLevel(38.7f), 0.01f) // milieu géométrique de 5 et 300
        assertTrue(Logic.luxLevel(67f) in 0.6f..0.7f)
    }
}
