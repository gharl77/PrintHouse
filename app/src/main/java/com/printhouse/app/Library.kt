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
    private var memories: List<Immich.Photo> = emptyList()
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

    /** Ni écartée, ni exclue par les filtres « pas une photo ». */
    private fun acceptable(p: Immich.Photo): Boolean = p.id !in hidden &&
        !(settings.skipScreenshots && p.looksLikeScreenshot) &&
        !(settings.skipSmall && p.longSide in 1 until SMALL) &&
        !(settings.skipNoCamera && p.hasCamera == false)

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
            queue.clear(); series.clear(); history.clear(); pos = -1; memoriesDay = ""; signature = sig
        }
        history.removeAll { page -> page.any { it.id in hidden } }
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
            history.add(page.photos)
            if (history.size > HISTORY_MAX) history.removeAt(0)
            pos = history.size - 1
        }
        current = page.photos
        return page
    }

    private fun newPage(server: Immich.Server): Page {
        val first = nextPhoto(server)
        val bmp = load(server, first)
        if (settings.pairPortraits && bmp.width < bmp.height * 0.85f) {
            // Photo verticale : on lui cherche une compagne verticale dans la file.
            if (queue.none { it.portrait == true }) try { refill(server) } catch (_: ImmichException) {}
            val partner = queue.firstOrNull { it.portrait == true && acceptable(it) && it.id != first.id }
            if (partner != null) {
                queue.remove(partner)
                val b2 = try { load(server, partner) } catch (_: ImmichException) { null }
                if (b2 != null && b2.width < b2.height * 0.85f) return Page(listOf(first, partner), listOf(bmp, b2), false)
                b2?.recycle()
            }
        }
        return Page(listOf(first), listOf(bmp), false)
    }

    private fun nextPhoto(server: Immich.Server): Immich.Photo {
        while (true) { // la suite d'une série passe avant tout
            val p = series.pollFirst() ?: break
            if (acceptable(p)) return p
        }
        ensureMemories(server)
        repeat(120) {
            val share = settings.memoryShare
            if (memories.isNotEmpty() && rnd.nextInt(100) < share) {
                if (memoryIndex >= memories.size) { memories = memories.shuffled(rnd); memoryIndex = 0 }
                val m = memories[memoryIndex++]
                if (acceptable(m)) return m
            } else {
                if (queue.isEmpty()) refill(server)
                val p = queue.pollFirst() ?: throw ImmichException(tr("Immich n'a renvoyé aucune photo pour ces critères."))
                if (acceptable(p)) { startSeries(server, p); return p }
            }
        }
        throw ImmichException(tr("Aucune photo à afficher : elles sont toutes écartées ou filtrées."))
    }

    /** De temps en temps, [first] est suivie de quelques photos de la même journée, dans l'ordre. */
    private fun startSeries(server: Immich.Server, first: Immich.Photo) {
        if (rnd.nextInt(100) >= settings.seriesShare) return
        try {
            val day = Immich.sameDay(server, first).filter { acceptable(it) }
            val at = day.indexOfFirst { it.id == first.id }
            if (at >= 0) series.addAll(day.drop(at + 1).take(SERIES_MORE))
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

    /** Souvenirs du jour : cherchés une fois par jour (nouvel essai après une heure en cas d'échec). */
    private fun ensureMemories(server: Immich.Server) {
        if (settings.memoryShare <= 0) { memories = emptyList(); return }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val now = System.currentTimeMillis()
        if (today == memoriesDay && (memories.isNotEmpty() || now - memoriesTriedAt < 3_600_000L)) return
        memoriesDay = today
        memoriesTriedAt = now
        memoryIndex = 0
        val maxYears = settings.sinceYears.let { if (it > 0) it else 30 }
        val albums = if (settings.sourceMode == "albums") settings.albums else emptyList()
        val persons = settings.persons
        memories = try {
            // Mêmes filtres que le reste : chaque album et chaque personne choisis sont parcourus.
            val base = Immich.Filter(favorites = settings.sourceMode == "favorites")
            val combos = (if (albums.isEmpty()) listOf<String?>(null) else albums.map { it.id }).flatMap { a ->
                (if (persons.isEmpty()) listOf<String?>(null) else persons.map { it.id }).map { p -> base.copy(albumId = a, personId = p) }
            }.take(6)
            combos.flatMap { Immich.onThisDay(server, it, maxYears) }.distinctBy { it.id }.shuffled(rnd)
        } catch (_: ImmichException) {
            emptyList()
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
        private const val SMALL = 1000      // plus grand côté, en pixels
        private const val SERIES_MORE = 3   // photos ajoutées après la première
        private const val HISTORY_MAX = 60
        private const val CACHE_MAX = 80
    }
}
