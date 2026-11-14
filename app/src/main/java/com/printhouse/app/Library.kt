package com.printhouse.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * Choisit ce que le cadre montre : photos au hasard selon les filtres, souvenirs « Ce jour-là »,
 * paires de photos verticales, retour en arrière, photos écartées, et une réserve gardée sur la
 * tablette pour continuer à défiler quand Immich est injoignable.
 * À n'appeler que depuis un seul fil (celui du chargement), sauf [hide] et [clearHidden] qui sont sûrs.
 */
class Library(ctx: Context, private val settings: Settings) {
    /** Ce qui est affiché en une fois : une photo, ou deux verticales côte à côte. */
    class Page(val photos: List<Immich.Photo>, val bitmaps: List<Bitmap>, val offline: Boolean)

    private val cacheDir = File(ctx.cacheDir, "photos").apply { mkdirs() }
    private val rnd = Random()

    private val queue = ArrayDeque<Immich.Photo>()
    private val series = ArrayDeque<Immich.Photo>() // suite d'une série « même journée »
    private val season = ArrayDeque<Immich.Photo>() // même période de l'année, les années passées
    private val birthdayQueue = ArrayDeque<Immich.Photo>()
    private var birthdayPeople: List<Immich.Person> = emptyList() // dont c'est l'anniversaire aujourd'hui
    private var birthdayDay = ""
    private var birthdayTriedAt = 0L
    private var birthdayEmptyUntil = 0L // Immich n'a rien renvoyé : on ne redemande pas tout de suite
    private var seasonEmptyUntil = 0L
    private val seenFile = File(ctx.filesDir, "seen.json")
    private val seen: MutableMap<String, Long> = loadSeen() // photo → jour (depuis 1970) du dernier affichage

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

    /** Prénoms des personnes dont c'est l'anniversaire aujourd'hui. */
    val birthdaysToday get() = birthdayPeople.map { it.name }
    @Volatile private var memories: List<Immich.Photo> = emptyList()
    @Volatile private var memoriesLoading = false
    private val background = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "memories").apply { isDaemon = true } }
    private var memoryIndex = 0
    private var memoriesDay = ""
    private var memoriesTriedAt = 0L
    private val history = ArrayList<List<Immich.Photo>>()
    private var pos = -1
    private var signature = ""
    @Volatile private var hidden: Set<String> = settings.hidden

    /** Les photos actuellement à l'écran. */
    @Volatile var current: List<Immich.Photo> = emptyList()
        private set

    val hiddenIds get() = hidden.toList()
    val hiddenCount get() = hidden.size
    val cachedCount get() = cacheDir.listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0

    /** Écarte une photo pour de bon (sur la tablette seulement ; Immich n'est pas modifié). */
    fun hide(id: String) {
        hidden = hidden + id
        settings.hidden = hidden
        File(cacheDir, "$id.jpg").delete()
        File(cacheDir, "$id.json").delete()
    }

    /** Remet une photo écartée dans le défilement. */
    fun restore(id: String) {
        hidden = hidden - id
        settings.hidden = hidden
    }

    /**
     * Ni écartée, ni exclue par les filtres « pas une photo », ni vue trop récemment
     * (sauf si [allowSeen] : il ne reste rien d'autre à montrer).
     */
    private fun acceptable(p: Immich.Photo, allowSeen: Boolean = false): Boolean {
        if (p.id in hidden) return false
        if (settings.skipScreenshots && p.looksLikeScreenshot) { stats.screenshots++; return false }
        if (settings.skipSmall && p.longSide in 1 until SMALL) { stats.small++; return false }
        if (settings.skipNoCamera && p.hasCamera == false) { stats.noCamera++; return false }
        return allowSeen || !seenRecently(p.id)
    }

    // --- Photos déjà vues ----------------------------------------------------

    private fun today() = System.currentTimeMillis() / 86_400_000L

    private fun loadSeen(): MutableMap<String, Long> = try {
        val o = JSONObject(seenFile.readText())
        val map = HashMap<String, Long>()
        for (k in o.keys()) map[k] = o.getLong(k)
        map
    } catch (_: Exception) {
        HashMap()
    }

    private fun seenRecently(id: String): Boolean {
        val days = settings.repeatDays
        val day = seen[id] ?: return false
        return days > 0 && today() - day < days
    }

    private fun markSeen(photos: List<Immich.Photo>) {
        val now = today()
        for (p in photos) seen[p.id] = now
        if (seen.size > SEEN_MAX) seen.entries.sortedBy { it.value }.take(seen.size - SEEN_MAX * 9 / 10).forEach { seen.remove(it.key) }
        try { seenFile.writeText(JSONObject(seen as Map<*, *>).toString()) } catch (_: Exception) {}
    }

    /** Les dernières pages montrées, de la plus récente à la plus ancienne (pour la page web). */
    fun recent(max: Int): List<Immich.Photo> = synchronized(history) { history.toList() }.asReversed().flatten().distinctBy { it.id }.take(max)

    /** Après une restauration de sauvegarde : relit la liste des photos écartées. */
    fun clearHiddenCache() { hidden = settings.hidden }

    fun clearHidden() {
        hidden = emptySet()
        settings.hidden = hidden
    }

    /**
     * La page à afficher après un pas [step] (+1 : suivante, -1 : précédente, 0 : recharger
     * l'actuelle). Renvoie null s'il n'y a rien avant.
     */
    fun page(step: Int): Page? {
        val server = settings.server
        val sig = listOf(server.url, settings.sourceMode, settings.albums, settings.persons, settings.sinceYears).joinToString("|")
        if (sig != signature) {
            queue.clear(); series.clear(); season.clear(); birthdayQueue.clear(); birthdayDay = ""
            synchronized(history) { history.clear() }
            pos = -1; memoriesDay = ""; signature = sig
            birthdayEmptyUntil = 0L; seasonEmptyUntil = 0L
        }
        synchronized(history) { history.removeAll { page -> page.any { it.id in hidden } } }
        pos = pos.coerceAtMost(history.size - 1)

        val target = pos + (if (step == 0 && pos < 0) 1 else step)
        if (target < 0) return null
        if (target < history.size) {
            val photos = history[target]
            val page = try {
                Page(photos, photos.map { load(server, it) }, false)
            } catch (e: ImmichException) {
                if (!e.offline) throw e
                offlinePage() ?: throw e
            }
            pos = target
            current = page.photos
            return page
        }

        val page = try { newPage(server) } catch (e: ImmichException) {
            if (!e.offline) throw e
            offlinePage() ?: throw e
        }
        if (!page.offline) {
            synchronized(history) {
                history.add(page.photos)
                if (history.size > HISTORY_MAX) history.removeAt(0)
            }
            pos = history.size - 1
            markSeen(page.photos)
            stats.shown += page.photos.size
        }
        current = page.photos
        return page
    }

    private fun newPage(server: Immich.Server): Page {
        val first = withAges(server, nextPhoto(server))
        val bmp = load(server, first)
        if (settings.pairPortraits && bmp.width < bmp.height * 0.85f) {
            // Photo verticale : on lui cherche une compagne verticale dans la file.
            // (Sans laisser la file grossir quand Immich ne dit pas quelles photos sont verticales.)
            if (queue.none { it.portrait == true } && queue.size < 2 * BATCH) try { refill(server) } catch (_: ImmichException) {}
            val partner = queue.firstOrNull { it.portrait == true && acceptable(it) && it.id != first.id }
            if (partner != null) {
                queue.remove(partner)
                val b2 = try { load(server, partner) } catch (_: ImmichException) { null }
                if (b2 != null && b2.width < b2.height * 0.85f) return Page(listOf(first, withAges(server, partner)), listOf(bmp, b2), false)
                b2?.recycle()
            }
        }
        return Page(listOf(first), listOf(bmp), false)
    }

    private fun nextPhoto(server: Immich.Server): Immich.Photo {
        while (true) { // la suite d'une série passe avant tout
            val p = series.pollFirst() ?: break
            if (acceptable(p, allowSeen = true)) return p
        }
        ensureMemories(server)
        ensureBirthdays(server)
        // Deux passes : d'abord sans les photos vues récemment, puis en les acceptant s'il ne reste
        // rien. Chaque passe a un nombre limité d'appels à Immich : avec des filtres serrés (peu de
        // photos, toutes déjà vues), il ne faut pas enchaîner des dizaines de requêtes pour une photo.
        for (allowSeen in listOf(false, true)) {
            var refills = 0
            for (attempt in 0 until ATTEMPTS) {
                val now = System.currentTimeMillis()
                val roll = rnd.nextInt(100)
                val p: Immich.Photo? = when {
                    birthdayPeople.isNotEmpty() && roll < settings.birthdayShare -> {
                        if (birthdayQueue.isEmpty() && now > birthdayEmptyUntil) {
                            refillBirthday(server)
                            if (birthdayQueue.isEmpty()) birthdayEmptyUntil = now + RETRY_EMPTY_MS
                        }
                        birthdayQueue.pollFirst()?.also { stats.birthdays++ }
                    }
                    memories.isNotEmpty() && rnd.nextInt(100) < settings.memoryShare -> {
                        val list = memories // copie locale : la liste peut être remplacée en arrière-plan
                        if (list.isEmpty()) null else list[memoryIndex++ % list.size].also { stats.memories++ }
                    }
                    rnd.nextInt(100) < settings.seasonShare -> {
                        if (season.isEmpty() && now > seasonEmptyUntil) {
                            refillSeason(server)
                            if (season.isEmpty()) seasonEmptyUntil = now + RETRY_EMPTY_MS
                        }
                        season.pollFirst()?.also { stats.season++ }
                    }
                    else -> null
                }
                if (p != null) {
                    if (acceptable(p, allowSeen)) return p
                } else {
                    if (queue.isEmpty()) {
                        if (refills++ >= MAX_REFILLS) break // assez cherché : passe suivante
                        refill(server)
                    }
                    val q = queue.pollFirst() ?: throw ImmichException("Immich n'a renvoyé aucune photo pour ces critères.")
                    if (acceptable(q, allowSeen)) { startSeries(server, q); return q }
                }
            }
        }
        throw ImmichException("Aucune photo à afficher : elles sont toutes écartées ou filtrées.")
    }

    // --- Anniversaires et âges ------------------------------------------------

    /** Une fois par jour : de qui est-ce l'anniversaire ? (Parmi les personnes choisies, s'il y en a.) */
    private fun ensureBirthdays(server: Immich.Server) {
        if (settings.birthdayShare <= 0) { birthdayPeople = emptyList(); return }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val now = System.currentTimeMillis()
        if (today == birthdayDay && (birthdayPeople.isNotEmpty() || now - birthdayTriedAt < 6 * 3_600_000L)) return
        birthdayDay = today
        birthdayTriedAt = now
        birthdayQueue.clear()
        val cal = java.util.Calendar.getInstance()
        val leap = java.util.GregorianCalendar().isLeapYear(cal.get(java.util.Calendar.YEAR))
        val chosen = settings.persons.map { it.id }.toSet()
        birthdayPeople = try {
            Immich.peopleWithBirthDate(server).filter {
                (chosen.isEmpty() || it.id in chosen) &&
                    Logic.isBirthday(it.birthDate!!, cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH), leap)
            }
        } catch (_: ImmichException) {
            emptyList() // pas de permission person.read, ou Immich injoignable : nouvel essai plus tard
        }
    }

    private fun refillBirthday(server: Immich.Server) {
        val who = birthdayPeople[rnd.nextInt(birthdayPeople.size)]
        try {
            birthdayQueue.addAll(Immich.random(server, filter().copy(personId = who.id), BATCH))
        } catch (_: ImmichException) {
        }
    }

    /** Des photos prises à la même période de l'année, une autre année tirée au sort. */
    private fun refillSeason(server: Immich.Server) {
        val maxYears = settings.sinceYears.let { if (it > 0) it else 20 }
        repeat(4) {
            val (from, to) = Immich.seasonWindow(1 + rnd.nextInt(maxYears), SEASON_DAYS)
            try {
                season.addAll(Immich.random(server, filter().copy(takenAfter = from, takenBefore = to), BATCH))
            } catch (_: ImmichException) {
                return
            }
            if (season.isNotEmpty()) return
        }
    }

    /** Ajoute à la légende l'âge des personnes de la photo, selon le réglage. */
    private fun withAges(server: Immich.Server, p: Immich.Photo): Immich.Photo {
        val mode = settings.ageCaption
        if (mode == "never" || p.ages.isNotEmpty()) return p
        if (mode == "birthday" && birthdayPeople.isEmpty()) return p
        val people = try { Immich.peopleOf(server, p.id) } catch (_: ImmichException) { return p }
        val celebrated = birthdayPeople.map { it.id }.toSet()
        val texts = people
            .filter { it.birthDate != null && (mode == "always" || it.id in celebrated) }
            .sortedByDescending { it.id in celebrated }
            .mapNotNull { Immich.ageText(it.name, it.birthDate!!, p.date) }
            .take(2)
        return if (texts.isEmpty()) p else p.copy(ages = texts.joinToString(" · "))
    }

    /** De temps en temps, [first] est suivie de quelques photos de la même journée, dans l'ordre. */
    private fun startSeries(server: Immich.Server, first: Immich.Photo) {
        if (rnd.nextInt(100) >= settings.seriesShare) return
        try {
            val day = Immich.sameDay(server, first).filter { acceptable(it, allowSeen = true) }
            val at = day.indexOfFirst { it.id == first.id }
            if (at >= 0) day.drop(at + 1).take(SERIES_MORE).let { more ->
                series.addAll(more)
                if (more.isNotEmpty()) stats.series++
            }
        } catch (_: ImmichException) {
        }
    }

    /** Un lot de photos au hasard. Avec plusieurs albums ou personnes, chaque lot en tire un au sort. */
    private fun refill(server: Immich.Server) {
        queue.addAll(Immich.random(server, filter(), BATCH))
    }

    private fun filter(): Immich.Filter {
        val albums = if (settings.sourceMode == "albums") settings.albums else emptyList()
        // Tirage pondéré : un gros album revient plus souvent qu'un petit.
        val album = if (albums.isEmpty()) null else {
            var pick = rnd.nextInt(albums.sumOf { it.count.coerceAtLeast(1) })
            albums.first { c -> pick -= c.count.coerceAtLeast(1); pick < 0 }
        }
        val persons = settings.persons
        val years = settings.sinceYears
        return Immich.Filter(
            favorites = settings.sourceMode == "favorites",
            albumId = album?.id,
            personId = if (persons.isEmpty()) null else persons[rnd.nextInt(persons.size)].id,
            takenAfter = if (years > 0) Immich.isoYearsBefore(years) else null,
        )
    }

    /**
     * Souvenirs du jour : cherchés une fois par jour, en arrière-plan. La recherche demande une
     * requête par année et par filtre (des dizaines en tout) : elle ne doit jamais retenir
     * l'affichage de la photo suivante. En attendant, le cadre montre des photos ordinaires.
     */
    private fun ensureMemories(server: Immich.Server) {
        if (settings.memoryShare <= 0) { memories = emptyList(); return }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val now = System.currentTimeMillis()
        if (memoriesLoading) return
        if (today == memoriesDay && (memories.isNotEmpty() || now - memoriesTriedAt < 3_600_000L)) return
        if (today != memoriesDay) memories = emptyList() // ceux d'hier ne sont plus « ce jour-là »
        memoriesDay = today
        memoriesTriedAt = now
        memoriesLoading = true
        val forSignature = signature
        val maxYears = settings.sinceYears.let { if (it > 0) it else MEMORY_YEARS }
        val base = Immich.Filter(favorites = settings.sourceMode == "favorites")
        val combos = Logic.memoryCombos(
            if (settings.sourceMode == "albums") settings.albums.map { it.id } else emptyList(),
            settings.persons.map { it.id }, MEMORY_COMBOS,
        )
        background.execute {
            val found = try {
                combos.flatMap { (album, person) -> Immich.onThisDay(server, base.copy(albumId = album, personId = person), maxYears) }
                    .distinctBy { it.id }.shuffled(rnd)
            } catch (_: Exception) {
                emptyList()
            }
            if (forSignature == signature) memories = found
            memoriesLoading = false
        }
    }

    // --- Réserve sur la tablette -------------------------------------------

    private fun load(server: Immich.Server, photo: Immich.Photo): Bitmap {
        val file = File(cacheDir, "${photo.id}.jpg")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let { file.setLastModified(System.currentTimeMillis()); return it }
            file.delete()
        }
        val bytes = Immich.imageBytes(server, photo.id)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw ImmichException(tr("Image illisible."))
        try {
            file.writeBytes(bytes)
            File(cacheDir, "${photo.id}.json").writeText(photo.json().toString())
            prune()
        } catch (_: Exception) {
        }
        return bmp
    }

    private fun prune() {
        val files = cacheDir.listFiles { f -> f.name.endsWith(".jpg") } ?: return
        if (files.size <= CACHE_MAX) return
        files.sortedBy { it.lastModified() }.take(files.size - CACHE_MAX).forEach {
            it.delete()
            File(cacheDir, it.nameWithoutExtension + ".json").delete()
        }
    }

    /** Immich injoignable : une photo de la réserve, différente de celle affichée si possible. */
    private fun offlinePage(): Page? {
        val files = (cacheDir.listFiles { f -> f.name.endsWith(".jpg") } ?: return null)
            .filter { it.nameWithoutExtension !in hidden }
        if (files.isEmpty()) return null
        val shown = current.map { it.id }.toSet()
        val pick = (files.filter { it.nameWithoutExtension !in shown }.ifEmpty { files }).let { it[rnd.nextInt(it.size)] }
        val bmp = BitmapFactory.decodeFile(pick.path) ?: return null
        val id = pick.nameWithoutExtension
        val photo = try {
            Immich.Photo.from(JSONObject(File(cacheDir, "$id.json").readText()))
        } catch (_: Exception) {
            Immich.Photo(id, null, null, null)
        }
        return Page(listOf(photo), listOf(bmp), true)
    }

    companion object {
        private const val BATCH = 12
        private const val ATTEMPTS = 90           // photos examinées par passe, au plus
        private const val MAX_REFILLS = 5         // appels à Immich par passe, au plus
        private const val RETRY_EMPTY_MS = 600_000L
        private const val MEMORY_YEARS = 25   // années remontées pour « Ce jour-là »
        private const val MEMORY_COMBOS = 2   // filtres (album, personne) parcourus : 2 × 25 requêtes au plus
        private const val SEASON_DAYS = 21   // de part et d'autre de la date du jour
        private const val SEEN_MAX = 6000
        private const val SMALL = 1000      // plus grand côté, en pixels
        private const val SERIES_MORE = 3   // photos ajoutées après la première
        private const val HISTORY_MAX = 60
        private const val CACHE_MAX = 80
    }
}
