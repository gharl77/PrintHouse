package com.printhouse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.Executor

/**
 * Le choix des photos contre un faux Immich qui compte les requêtes. Ces tests existent parce
 * qu'un matin, la première photo du jour a attendu 180 requêtes : aucune combinaison de réglages
 * ne doit plus pouvoir en demander autant.
 */
class PickerTest {
    /** Un faux Immich : une bibliothèque en mémoire, et le compte de ce qu'on lui demande. */
    private class Fake(var library: List<Immich.Photo> = (1..200).map { photo("id$it") }) : PhotoSource {
        var randomCalls = 0
        var onThisDayCalls = 0
        var yearlyRequests = 0 // onThisDay coûte une requête par année
        var memoriesCalls = 0
        var sameDayCalls = 0
        var peopleCalls = 0
        var peopleOfCalls = 0
        val filters = ArrayList<Immich.Filter>()
        var memoriesApi: List<Immich.Photo>? = null // null = l'API des souvenirs est refusée
        var pastYears: List<Immich.Photo> = emptyList()
        var seasonal: List<Immich.Photo> = emptyList()
        var people: List<Immich.Person> = emptyList()
        var peopleByPhoto: Map<String, List<Immich.Person>> = emptyMap()
        private val rnd = Random(7)

        /** Requêtes HTTP que tout cela aurait coûté. */
        val requests get() = randomCalls + yearlyRequests + memoriesCalls + sameDayCalls + peopleCalls + peopleOfCalls

        override fun random(f: Immich.Filter, count: Int): List<Immich.Photo> {
            randomCalls++; filters.add(f)
            if (f.takenBefore != null) return seasonal.take(count)
            return library.shuffled(rnd).take(count)
        }
        override fun onThisDay(f: Immich.Filter, maxYears: Int): List<Immich.Photo> {
            onThisDayCalls++; yearlyRequests += maxYears; filters.add(f)
            return pastYears
        }
        override fun memories(): List<Immich.Photo> {
            memoriesCalls++
            return memoriesApi ?: throw ImmichException("Il manque une permission (HTTP 403).")
        }
        override fun sameDay(p: Immich.Photo): List<Immich.Photo> { sameDayCalls++; return listOf(p) }
        override fun peopleWithBirthDate(): List<Immich.Person> { peopleCalls++; return people }
        override fun peopleOf(id: String): List<Immich.Person> { peopleOfCalls++; return peopleByPhoto[id] ?: emptyList() }
    }

    /** Garde les tâches d'arrière-plan sans les lancer, pour vérifier que rien ne les attend. */
    private class Later : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(r: Runnable) { tasks.add(r) }
        fun runAll() { val t = ArrayList(tasks); tasks.clear(); t.forEach { it.run() } }
    }

    private companion object {
        fun photo(id: String, date: String = "2019-07-14T10:00:00.000Z", reliable: Boolean = true, name: String = "IMG.jpg", yearsAgo: Int = 0) =
            Immich.Photo(id, date, "Québec", "Canada", ratio = 1.5f, yearsAgo = yearsAgo, fileName = name, mime = "image/jpeg",
                hasCamera = true, longSide = 4000, dateReliable = reliable)
        val tenPeople = (1..10).map { Choice("00000000-0000-4000-8000-%012d".format(it), "P$it") }
        const val NOON = 1_800_000_000_000L // un instant fixe
    }

    private val later = Later()
    private val seen = HashMap<String, Long>()
    private val hidden = HashSet<String>()
    private fun picker(clock: () -> Long = { NOON }) = Picker({ it in hidden }, seen, later, Random(3), clock)

    @Test fun `the first photo of the day does not wait for the search for memories`() {
        val fake = Fake()
        val rules = Rules(persons = tenPeople, memoryShare = 25, seriesShare = 0)
        val p = picker()
        p.resetIfChanged(rules)
        assertNotNull(p.next(fake, rules))
        assertEquals("aucune recherche de souvenirs avant la photo", 0, fake.onThisDayCalls)
        assertTrue("au plus quelques requêtes pour une photo, pas ${fake.requests}", fake.requests <= 3)
        assertEquals("la recherche attend en arrière-plan", 1, later.tasks.size)
    }

    @Test fun `the search for memories stays under fifty requests even with ten people`() {
        val fake = Fake()
        val rules = Rules(persons = tenPeople, sourceMode = "albums", albums = listOf(Choice("a", "A", 10), Choice("b", "B", 10)), memoryShare = 25)
        val p = picker()
        p.resetIfChanged(rules)
        p.next(fake, rules)
        later.runAll()
        assertTrue("${fake.yearlyRequests} requêtes pour les souvenirs", fake.yearlyRequests in 1..50)
    }

    @Test fun `memories are searched once a day, not for every photo`() {
        val fake = Fake().apply { pastYears = listOf(photo("old1", yearsAgo = 5)) }
        val rules = Rules(persons = tenPeople.take(1), memoryShare = 50)
        val p = picker()
        p.resetIfChanged(rules)
        repeat(30) { p.next(fake, rules); later.runAll() }
        assertEquals(1, fake.onThisDayCalls)
    }

    @Test fun `without filters Immich's own memories are used, in one request`() {
        val fake = Fake().apply { memoriesApi = listOf(photo("m1", yearsAgo = 3), photo("m2", yearsAgo = 8)) }
        val rules = Rules(memoryShare = 100)
        val p = picker()
        p.resetIfChanged(rules)
        p.next(fake, rules)
        later.runAll()
        assertEquals(1, fake.memoriesCalls)
        assertEquals(0, fake.onThisDayCalls)
        assertTrue(p.next(fake, rules).id in setOf("m1", "m2"))
    }

    @Test fun `when Immich refuses its memories the frame searches by itself`() {
        val fake = Fake().apply { memoriesApi = null; pastYears = listOf(photo("old1", yearsAgo = 5)) }
        val rules = Rules(memoryShare = 100)
        val p = picker()
        p.resetIfChanged(rules)
        p.next(fake, rules)
        later.runAll()
        assertEquals(1, fake.onThisDayCalls)
        assertEquals("old1", p.next(fake, rules).id)
    }

    @Test fun `Immich's memories are never used with a people filter, which they would ignore`() {
        val fake = Fake().apply { memoriesApi = listOf(photo("m1", yearsAgo = 3)) }
        val rules = Rules(persons = tenPeople.take(2), memoryShare = 100)
        val p = picker()
        p.resetIfChanged(rules)
        p.next(fake, rules)
        later.runAll()
        assertEquals(0, fake.memoriesCalls)
        assertTrue(fake.filters.filter { it.takenAfter == null }.all { it.personId != null })
    }

    @Test fun `when every photo was seen recently it still shows one, after a few requests`() {
        val fake = Fake((1..12).map { photo("id$it") })
        for (i in 1..12) seen["id$i"] = NOON / 86_400_000L
        val rules = Rules(repeatDays = 7)
        val p = picker()
        p.resetIfChanged(rules)
        assertNotNull(p.next(fake, rules))
        assertTrue("${fake.randomCalls} requêtes", fake.randomCalls <= Picker.MAX_REFILLS + 2)
    }

    @Test fun `a photo seen this week is avoided while others remain`() {
        val fake = Fake((1..12).map { photo("id$it") })
        for (i in 1..11) seen["id$i"] = NOON / 86_400_000L
        val rules = Rules(repeatDays = 7)
        val p = picker()
        p.resetIfChanged(rules)
        assertEquals("id12", p.next(fake, rules).id)
    }

    @Test fun `an empty season does not ask Immich again for every photo`() {
        val fake = Fake()
        val rules = Rules(seasonShare = 100)
        val p = picker()
        p.resetIfChanged(rules)
        repeat(20) { p.next(fake, rules) }
        val seasonCalls = fake.filters.count { it.takenBefore != null }
        assertTrue("$seasonCalls requêtes de saison pour 20 photos", seasonCalls <= Picker.SEASON_TRIES)
    }

    @Test fun `a birthday with no photo of the person does not ask again for every photo`() {
        var clock = NOON
        val birthday = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(clock)).replaceRange(0, 4, "2012")
        val who = Immich.Person("00000000-0000-4000-8000-000000000099", "Emma", birthday)
        val fake = object : PhotoSource by Fake() {}.let { Fake().apply { people = listOf(who) } }
        val empty = object : PhotoSource by fake {
            override fun random(f: Immich.Filter, count: Int) = if (f.personId == who.id) { fake.randomCalls++; fake.filters.add(f); emptyList() } else fake.random(f, count)
        }
        val rules = Rules(birthdayShare = 100)
        val p = picker { clock }
        p.resetIfChanged(rules)
        repeat(20) { p.next(empty, rules) }
        assertEquals(listOf("Emma"), p.birthdaysToday)
        assertEquals(1, fake.filters.count { it.personId == who.id })
        assertEquals("la liste des anniversaires est lue une fois par jour", 1, fake.peopleCalls)
    }

    @Test fun `hidden photos and screenshots are never shown`() {
        val fake = Fake(listOf(photo("hidden"), photo("shot", name = "Screenshot_2024.jpg"), photo("good")))
        hidden.add("hidden")
        val rules = Rules(skipScreenshots = true)
        val p = picker()
        p.resetIfChanged(rules)
        repeat(10) { assertEquals("good", p.next(fake, rules).id) }
        assertTrue(p.stats.screenshots > 0)
    }

    @Test fun `guest mode shows one album only, without people, memories or ages`() {
        val family = Rules(persons = tenPeople, memoryShare = 50, seasonShare = 25, birthdayShare = 50, ageCaption = "always")
        val guests = family.forGuests(Choice("guest-album", "Voyages", 300))
        val fake = Fake().apply { people = listOf(Immich.Person("x", "Emma", "2012-01-01")) }
        val p = picker()
        p.resetIfChanged(guests)
        repeat(10) { p.next(fake, guests) }
        assertTrue(fake.filters.isNotEmpty())
        assertTrue(fake.filters.all { it.albumId == "guest-album" && it.personId == null && it.takenBefore == null })
        assertEquals(0, fake.onThisDayCalls + fake.memoriesCalls + fake.peopleCalls + fake.peopleOfCalls)
        assertTrue(later.tasks.isEmpty())
    }

    @Test fun `changing the filters empties the queue`() {
        val fake = Fake()
        val p = picker()
        val a = Rules()
        assertTrue(p.resetIfChanged(a))
        assertFalse(p.resetIfChanged(a))
        p.next(fake, a)
        assertTrue(p.resetIfChanged(a.copy(sourceMode = "favorites")))
        p.next(fake, a.copy(sourceMode = "favorites"))
        assertTrue(fake.filters.last().favorites)
    }

    @Test fun `ages are shown for children only, and only when the date comes from the camera`() {
        val child = Immich.Person("c", "Emma", "2016-07-14")
        val adult = Immich.Person("a", "Papa", "1955-03-02")
        val fake = Fake(listOf(photo("p1"))).apply { peopleByPhoto = mapOf("p1" to listOf(adult, child)) }
        val p = picker()
        val kids = Rules(ageCaption = "always", ageLimit = 18)
        p.resetIfChanged(kids)
        assertEquals("Emma, 3 ans", p.next(fake, kids).ages)

        val everyone = kids.copy(ageLimit = 0)
        assertEquals("Papa, 64 ans · Emma, 3 ans", picker().apply { resetIfChanged(everyone) }.next(fake, everyone).ages)

        val scanned = Fake(listOf(photo("p1", reliable = false))).apply { peopleByPhoto = mapOf("p1" to listOf(child)) }
        assertEquals("", picker().apply { resetIfChanged(kids) }.next(scanned, kids).ages)
        assertEquals("pas de requête pour une photo sans date fiable", 0, scanned.peopleOfCalls)
    }

    @Test fun `a partner for a portrait photo costs one request at most and never the same photo`() {
        val portraits = (1..6).map { photo("v$it").copy(ratio = 0.7f) }
        val fake = Fake(portraits)
        val rules = Rules()
        val p = picker()
        p.resetIfChanged(rules)
        val first = p.next(fake, rules)
        val before = fake.randomCalls
        val partner = p.partner(fake, rules, portrait = true, exclude = setOf(first.id))
        assertNotNull(partner)
        assertTrue(partner!!.id != first.id)
        assertTrue(fake.randomCalls - before <= 1)
        assertNull(picker().apply { resetIfChanged(rules) }.partner(Fake(listOf(photo("wide"))), rules, portrait = true, exclude = emptySet()))
    }
}
