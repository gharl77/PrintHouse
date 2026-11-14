package com.printhouse.app

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executor

/** Ce dont le choix des photos a besoin d'Immich. Chaque appel coûte au moins une requête. */
interface PhotoSource {
    fun random(f: Immich.Filter, count: Int): List<Immich.Photo>
    /** Coûte une requête par année. */
    fun onThisDay(f: Immich.Filter, maxYears: Int): List<Immich.Photo>
    /** Souvenirs préparés par Immich, sans filtre possible. */
    fun memories(): List<Immich.Photo>
    fun sameDay(p: Immich.Photo): List<Immich.Photo>
    fun peopleWithBirthDate(): List<Immich.Person>
    fun peopleOf(id: String): List<Immich.Person>
}

class ImmichSource(private val server: Immich.Server) : PhotoSource {
    override fun random(f: Immich.Filter, count: Int) = Immich.random(server, f, count)
    override fun onThisDay(f: Immich.Filter, maxYears: Int) = Immich.onThisDay(server, f, maxYears)
    override fun memories() = Immich.memories(server)
    override fun sameDay(p: Immich.Photo) = Immich.sameDay(server, p)
    override fun peopleWithBirthDate() = Immich.peopleWithBirthDate(server)
    override fun peopleOf(id: String) = Immich.peopleOf(server, id)
}

/** Les réglages qui gouvernent le choix des photos, figés le temps d'un choix. */
data class Rules(
    val sourceMode: String = "all",
    val albums: List<Choice> = emptyList(),
    val persons: List<Choice> = emptyList(),
    val sinceYears: Int = 0,
    val memoryShare: Int = 0,
    val seasonShare: Int = 0,
    val birthdayShare: Int = 0,
    val seriesShare: Int = 0,
    val repeatDays: Int = 0,
    /** "never", "birthday" ou "always". */
    val ageCaption: String = "never",
    /** N'afficher l'âge que des moins de N ans (0 = tout le monde). */
    val ageLimit: Int = 0,
    val skipScreenshots: Boolean = false,
    val skipSmall: Boolean = false,
    val skipNoCamera: Boolean = false,
) {
    /** Tout changement de cette signature vide les files d'attente : ce ne sont plus les mêmes photos. */
    val signature get() = listOf(sourceMode, albums.map { it.id }, persons.map { it.id }, sinceYears).joinToString("|")
    val filtered get() = sourceMode != "all" || persons.isNotEmpty()

    /** Mode invités : seulement [album], sans souvenirs, saison, anniversaires ni âges. */
    fun forGuests(album: Choice) = copy(
        sourceMode = "albums", albums = listOf(album), persons = emptyList(),
        memoryShare = 0, seasonShare = 0, birthdayShare = 0, ageCaption = "never",
    )
}

/**
 * Choisit la photo suivante : au hasard selon les filtres, avec des souvenirs « Ce jour-là », des
 * photos de saison, d'anniversaire, des séries d'une même journée, sans celles qui sont écartées,
 * filtrées ou vues récemment. Ne touche ni aux images ni à Android, pour être testé sur l'ordinateur.
 *
 * Règle d'or : choisir une photo ne doit jamais demander plus de quelques requêtes à Immich, quels
 * que soient les réglages. Tout ce qui en demande davantage part en arrière-plan ([background]).
 * À n'appeler que depuis un seul fil.
 */
class Picker(
    private val isHidden: (String) -> Boolean,
    /** Photo → jour (depuis 1970) de son dernier affichage. */
    private val seen: MutableMap<String, Long>,
    private val background: Executor,
    private val rnd: Random = Random(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Compteurs depuis le lancement, pour la page web. */
    class Stats {
        @Volatile var shown = 0
        @Volatile var screenshots = 0
        @Volatile var small = 0
        @Volatile var noCamera = 0
        @Volatile var series = 0
        @Volatile var memories = 0
        @Volatile var season = 0
        @Volatile var birthdays = 0
    }
    val stats = Stats()

    private val queue = ArrayDeque<Immich.Photo>()
    private val series = ArrayDeque<Immich.Photo>() // suite d'une série « même journée »
    private val season = ArrayDeque<Immich.Photo>() // même période de l'année, les années passées
    private val birthdayQueue = ArrayDeque<Immich.Photo>()
    @Volatile private var birthdayPeople: List<Immich.Person> = emptyList() // dont c'est l'anniversaire
    private var birthdayDay = ""
    private var birthdayTriedAt = 0L
    private var birthdayEmptyUntil = 0L // Immich n'a rien renvoyé : on ne redemande pas tout de suite
    private var seasonEmptyUntil = 0L
    @Volatile private var memories: List<Immich.Photo> = emptyList()
    @Volatile private var memoriesLoading = false
    private var memoryIndex = 0
    private var memoriesDay = ""
    private var memoriesTriedAt = 0L
    @Volatile private var generation = 0
    private var signature: String? = null

    /** Prénoms des personnes dont c'est l'anniversaire aujourd'hui. */
    val birthdaysToday get() = birthdayPeople.map { it.name }

    private fun dayString() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now()))
    private fun dayNumber() = now() / 86_400_000L

    /** Vide les files quand la source ou les filtres changent. Renvoie true si c'était le cas. */
    fun resetIfChanged(rules: Rules, extra: String = ""): Boolean {
        val sig = rules.signature + "|" + extra
        if (sig == signature) return false
        signature = sig
        generation++
        queue.clear(); series.clear(); season.clear(); birthdayQueue.clear()
        birthdayDay = ""; memoriesDay = ""; memories = emptyList()
        birthdayEmptyUntil = 0L; seasonEmptyUntil = 0L
        return true
    }

    /** Ni écartée, ni exclue par les filtres « pas une photo », ni vue trop récemment (sauf [allowSeen]). */
    private fun acceptable(p: Immich.Photo, rules: Rules, allowSeen: Boolean): Boolean {
        if (isHidden(p.id)) return false
        if (rules.skipScreenshots && p.looksLikeScreenshot) { stats.screenshots++; return false }
        if (rules.skipSmall && p.longSide in 1 until SMALL) { stats.small++; return false }
        if (rules.skipNoCamera && p.hasCamera == false) { stats.noCamera++; return false }
        return allowSeen || !seenRecently(p.id, rules)
    }

    private fun seenRecently(id: String, rules: Rules): Boolean {
        val day = seen[id] ?: return false
        return rules.repeatDays > 0 && dayNumber() - day < rules.repeatDays
    }

    fun markSeen(photos: List<Immich.Photo>) {
        val today = dayNumber()
        for (p in photos) seen[p.id] = today
        if (seen.size > SEEN_MAX) seen.entries.sortedBy { it.value }.take(seen.size - SEEN_MAX * 9 / 10).forEach { seen.remove(it.key) }
        stats.shown += photos.size
    }

    /** La photo suivante, légende d'âge comprise. */
    fun next(src: PhotoSource, rules: Rules): Immich.Photo = withAges(src, rules, choose(src, rules))

    private fun choose(src: PhotoSource, rules: Rules): Immich.Photo {
        while (true) { // la suite d'une série passe avant tout
            val p = series.pollFirst() ?: break
            if (acceptable(p, rules, allowSeen = true)) return p
        }
        ensureMemories(src, rules)
        ensureBirthdays(src, rules)
        // Deux passes : d'abord sans les photos vues récemment, puis en les acceptant s'il ne reste
        // rien. Chaque passe a un nombre limité d'appels à Immich.
        for (allowSeen in listOf(false, true)) {
            var refills = 0
            for (attempt in 0 until ATTEMPTS) {
                val t = now()
                val p: Immich.Photo? = when {
                    birthdayPeople.isNotEmpty() && rnd.nextInt(100) < rules.birthdayShare -> {
                        if (birthdayQueue.isEmpty() && t > birthdayEmptyUntil) {
                            refillBirthday(src, rules)
                            if (birthdayQueue.isEmpty()) birthdayEmptyUntil = t + RETRY_EMPTY_MS
                        }
                        birthdayQueue.pollFirst()?.also { stats.birthdays++ }
                    }
                    memories.isNotEmpty() && rnd.nextInt(100) < rules.memoryShare -> {
                        val list = memories // copie locale : la liste peut être remplacée en arrière-plan
                        if (list.isEmpty()) null else list[memoryIndex++ % list.size].also { stats.memories++ }
                    }
                    rnd.nextInt(100) < rules.seasonShare -> {
                        if (season.isEmpty() && t > seasonEmptyUntil) {
                            refillSeason(src, rules)
                            if (season.isEmpty()) seasonEmptyUntil = t + RETRY_EMPTY_MS
                        }
                        season.pollFirst()?.also { stats.season++ }
                    }
                    else -> null
                }
                if (p != null) {
                    if (acceptable(p, rules, allowSeen)) return p
                } else {
                    if (queue.isEmpty()) {
                        if (refills++ >= MAX_REFILLS) break // assez cherché : passe suivante
                        queue.addAll(src.random(filter(rules), BATCH))
                    }
                    val q = queue.pollFirst() ?: throw ImmichException("Immich n'a renvoyé aucune photo pour ces critères.")
                    if (acceptable(q, rules, allowSeen)) { startSeries(src, rules, q); return q }
                }
            }
        }
        throw ImmichException("Aucune photo à afficher : elles sont toutes écartées ou filtrées.")
    }

    /**
     * Une compagne pour une photo verticale ([portrait]) ou carrée, prise dans la file d'attente.
     * Au plus une requête, et sans laisser la file grossir quand Immich ne donne pas les formes.
     */
    fun partner(src: PhotoSource, rules: Rules, portrait: Boolean, exclude: Set<String>): Immich.Photo? {
        fun fits(p: Immich.Photo) = (if (portrait) p.portrait else p.square) == true && p.id !in exclude && acceptable(p, rules, allowSeen = false)
        if (queue.none { fits(it) } && queue.size < 2 * BATCH) {
            try { queue.addAll(src.random(filter(rules), BATCH)) } catch (_: ImmichException) {}
        }
        val found = queue.firstOrNull { fits(it) } ?: return null
        queue.remove(found)
        return withAges(src, rules, found)
    }

    /** Remet en tête de file une compagne qui n'a finalement pas servi. */
    fun giveBack(p: Immich.Photo) = queue.addFirst(p)

    /** Un album et une personne tirés au sort parmi ceux choisis (les gros albums reviennent plus souvent). */
    private fun filter(rules: Rules): Immich.Filter {
        val albums = if (rules.sourceMode == "albums") rules.albums else emptyList()
        val album = if (albums.isEmpty()) null else {
            var pick = rnd.nextInt(albums.sumOf { it.count.coerceAtLeast(1) })
            albums.first { c -> pick -= c.count.coerceAtLeast(1); pick < 0 }
        }
        return Immich.Filter(
            favorites = rules.sourceMode == "favorites",
            albumId = album?.id,
            personId = if (rules.persons.isEmpty()) null else rules.persons[rnd.nextInt(rules.persons.size)].id,
            takenAfter = if (rules.sinceYears > 0) Immich.isoYearsBefore(rules.sinceYears) else null,
        )
    }

    /** De temps en temps, [first] est suivie de quelques photos de la même journée, dans l'ordre. */
    private fun startSeries(src: PhotoSource, rules: Rules, first: Immich.Photo) {
        if (rnd.nextInt(100) >= rules.seriesShare) return
        try {
            val day = src.sameDay(first).filter { acceptable(it, rules, allowSeen = true) }
            val at = day.indexOfFirst { it.id == first.id }
            if (at >= 0) day.drop(at + 1).take(SERIES_MORE).let { more ->
                series.addAll(more)
                if (more.isNotEmpty()) stats.series++
            }
        } catch (_: ImmichException) {
        }
    }

    // --- Souvenirs « Ce jour-là » ---------------------------------------------

    /**
     * Cherchés une fois par jour, en arrière-plan : la recherche ne doit jamais retenir la photo
     * suivante. Sans filtre, une seule requête (souvenirs d'Immich) ; sinon une par année et par
     * filtre, plafonnées. En attendant, le cadre montre des photos ordinaires.
     */
    private fun ensureMemories(src: PhotoSource, rules: Rules) {
        if (rules.memoryShare <= 0) { memories = emptyList(); return }
        val today = dayString()
        val t = now()
        if (memoriesLoading) return
        if (today == memoriesDay && (memories.isNotEmpty() || t - memoriesTriedAt < 3_600_000L)) return
        if (today != memoriesDay) memories = emptyList() // ceux d'hier ne sont plus « ce jour-là »
        memoriesDay = today
        memoriesTriedAt = t
        memoriesLoading = true
        val forGeneration = generation
        val maxYears = if (rules.sinceYears > 0) minOf(rules.sinceYears, MEMORY_YEARS) else MEMORY_YEARS
        val base = Immich.Filter(favorites = rules.sourceMode == "favorites")
        val combos = Logic.memoryCombos(
            if (rules.sourceMode == "albums") rules.albums.map { it.id } else emptyList(),
            rules.persons.map { it.id }, MEMORY_COMBOS,
        )
        background.execute {
            val found = try {
                val ready = if (rules.filtered) null else try {
                    src.memories().filter { it.yearsAgo <= maxYears }
                } catch (_: ImmichException) {
                    null // pas la permission memory.read, ou Immich trop ancien : on cherche soi-même
                }
                (ready ?: combos.flatMap { (album, person) -> src.onThisDay(base.copy(albumId = album, personId = person), maxYears) })
                    .distinctBy { it.id }.shuffled(rnd)
            } catch (_: Exception) {
                emptyList()
            }
            if (forGeneration == generation) memories = found
            memoriesLoading = false
        }
    }

    // --- Anniversaires et âges ------------------------------------------------

    /** Une fois par jour : de qui est-ce l'anniversaire ? (Parmi les personnes choisies, s'il y en a.) */
    private fun ensureBirthdays(src: PhotoSource, rules: Rules) {
        if (rules.birthdayShare <= 0 && rules.ageCaption != "birthday") { birthdayPeople = emptyList(); return }
        val today = dayString()
        val t = now()
        if (today == birthdayDay && (birthdayPeople.isNotEmpty() || t - birthdayTriedAt < 6 * 3_600_000L)) return
        birthdayDay = today
        birthdayTriedAt = t
        birthdayQueue.clear()
        val cal = GregorianCalendar().apply { timeInMillis = t }
        val leap = cal.isLeapYear(cal.get(Calendar.YEAR))
        val chosen = rules.persons.map { it.id }.toSet()
        birthdayPeople = try {
            src.peopleWithBirthDate().filter {
                (chosen.isEmpty() || it.id in chosen) &&
                    Logic.isBirthday(it.birthDate ?: "", cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), leap)
            }
        } catch (_: ImmichException) {
            emptyList() // pas de permission person.read, ou Immich injoignable : nouvel essai plus tard
        }
    }

    private fun refillBirthday(src: PhotoSource, rules: Rules) {
        val who = birthdayPeople[rnd.nextInt(birthdayPeople.size)]
        try {
            birthdayQueue.addAll(src.random(filter(rules).copy(personId = who.id), BATCH))
        } catch (_: ImmichException) {
        }
    }

    /** Des photos prises à la même période de l'année, une autre année tirée au sort. */
    private fun refillSeason(src: PhotoSource, rules: Rules) {
        val maxYears = if (rules.sinceYears > 0) rules.sinceYears else 20
        for (attempt in 0 until SEASON_TRIES) {
            val (from, to) = Immich.seasonWindow(1 + rnd.nextInt(maxYears), SEASON_DAYS)
            try {
                season.addAll(src.random(filter(rules).copy(takenAfter = from, takenBefore = to), BATCH))
            } catch (_: ImmichException) {
                return
            }
            if (season.isNotEmpty()) return
        }
    }

    /**
     * Ajoute à la légende l'âge des personnes de la photo, selon le réglage. Seulement si la date
     * vient de l'appareil photo : une photo numérisée porte la date de sa numérisation.
     */
    private fun withAges(src: PhotoSource, rules: Rules, p: Immich.Photo): Immich.Photo {
        val mode = rules.ageCaption
        if (mode == "never" || p.ages.isNotEmpty() || !p.dateReliable) return p
        if (mode == "birthday" && birthdayPeople.isEmpty()) return p
        val people = try { src.peopleOf(p.id) } catch (_: ImmichException) { return p }
        val celebrated = birthdayPeople.map { it.id }.toSet()
        val texts = people
            .filter { it.birthDate != null && (mode == "always" || it.id in celebrated) }
            .filter { person ->
                val years = Logic.age(person.birthDate!!, p.date ?: "")?.first ?: return@filter false
                rules.ageLimit <= 0 || years < rules.ageLimit
            }
            .sortedByDescending { it.id in celebrated }
            .mapNotNull { Immich.ageText(it.name, it.birthDate!!, p.date) }
            .take(2)
        return if (texts.isEmpty()) p else p.copy(ages = texts.joinToString(" · "))
    }

    companion object {
        const val BATCH = 12
        const val ATTEMPTS = 90            // photos examinées par passe, au plus
        const val MAX_REFILLS = 5          // appels à Immich par passe, au plus
        const val RETRY_EMPTY_MS = 600_000L
        const val MEMORY_YEARS = 25        // années remontées pour « Ce jour-là »
        const val MEMORY_COMBOS = 2        // filtres (album, personne) parcourus : 2 × 25 requêtes au plus
        const val SEASON_DAYS = 21         // de part et d'autre de la date du jour
        const val SEASON_TRIES = 4
        const val SEEN_MAX = 6000
        const val SMALL = 1000             // plus grand côté, en pixels
        const val SERIES_MORE = 3          // photos ajoutées après la première
    }
}
