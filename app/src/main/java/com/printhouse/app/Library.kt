package com.printhouse.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.util.Random
import java.util.concurrent.Executors

/**
 * Ce que le cadre montre : les pages (une photo, deux verticales ou trois carrées), le retour en
 * arrière, les photos écartées, et une réserve gardée sur la tablette pour continuer à défiler
 * quand Immich est injoignable. Le choix des photos lui-même est fait par [Picker].
 * À n'appeler que depuis un seul fil (celui du chargement), sauf [hide], [restore], [clearHidden]
 * et [recent], qui sont sûrs.
 */
class Library(ctx: Context, private val settings: Settings) {
    /** Ce qui est affiché en une fois. */
    class Page(val photos: List<Immich.Photo>, val bitmaps: List<Bitmap>, val offline: Boolean)

    private val cacheDir = File(ctx.cacheDir, "photos").apply { mkdirs() }
    private val seenFile = File(ctx.filesDir, "seen.json")
    private val rnd = Random()
    @Volatile private var hidden: Set<String> = settings.hidden
    private val seen: MutableMap<String, Long> = loadSeen()
    private val picker = Picker(
        isHidden = { it in hidden },
        seen = seen,
        background = Executors.newSingleThreadExecutor { r -> Thread(r, "memories").apply { isDaemon = true } },
    )
    private val lovedFile = File(ctx.filesDir, "loved.json")
    @Volatile private var loved: List<Immich.Photo> = try {
        org.json.JSONArray(lovedFile.readText()).let { a -> (0 until a.length()).map { Immich.Photo.from(a.getJSONObject(it)) } }
    } catch (_: Exception) {
        emptyList()
    }
    val lovedCount get() = loved.size

    /** Une photo vient d'être mise en favori depuis le cadre : elle reviendra un peu plus souvent. */
    @Synchronized fun love(photo: Immich.Photo) {
        loved = (loved.filter { it.id != photo.id } + photo.copy(ages = "")).takeLast(LOVED_MAX)
        try { lovedFile.writeText(org.json.JSONArray(loved.map { it.json().put("dateReliable", it.dateReliable) }).toString()) } catch (_: Exception) {}
    }

    /** Retrouve une photo montrée récemment par son identifiant. */
    fun shown(id: String): Immich.Photo? = (current + recent(120)).firstOrNull { it.id == id }

    private val history = ArrayList<List<Immich.Photo>>()
    private var pos = -1

    /** Les photos actuellement à l'écran. */
    @Volatile var current: List<Immich.Photo> = emptyList()
        private set

    val stats get() = picker.stats
    val birthdaysToday get() = picker.birthdaysToday
    val themeError get() = picker.themeError
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

    /** Après une restauration de sauvegarde : relit la liste des photos écartées. */
    fun clearHiddenCache() { hidden = settings.hidden }

    fun clearHidden() {
        hidden = emptySet()
        settings.hidden = hidden
    }

    /** Les dernières pages montrées, de la plus récente à la plus ancienne (pour la page web). */
    fun recent(max: Int): List<Immich.Photo> = synchronized(history) { history.toList() }.asReversed().flatten().distinctBy { it.id }.take(max)

    private fun loadSeen(): MutableMap<String, Long> = try {
        val o = JSONObject(seenFile.readText())
        val map = HashMap<String, Long>()
        for (k in o.keys()) map[k] = o.getLong(k)
        map
    } catch (_: Exception) {
        HashMap()
    }

    /** Les réglages du moment, vus par [Picker] (le mode invités les remplace presque tous). */
    private fun rules(): Rules {
        val r = Rules(
            sourceMode = settings.sourceMode, albums = settings.albums, persons = settings.persons,
            themes = settings.themes, personsWeight = settings.personsWeight, themesWeight = settings.themesWeight,
            albumsWeight = settings.albumsWeight, themeSize = settings.themeSize,
            excludeThemes = settings.excludeThemes, excludeSize = settings.excludeSize,
            loved = loved.filter { it.id !in hidden }, lovedShare = settings.lovedShare,
            sinceYears = settings.sinceYears, memoryShare = settings.memoryShare, seasonShare = settings.seasonShare,
            birthdayShare = settings.birthdayShare, seriesShare = settings.seriesShare, repeatDays = settings.repeatDays,
            ageCaption = settings.ageCaption, ageLimit = settings.ageLimit, skipScreenshots = settings.skipScreenshots,
            skipSmall = settings.skipSmall, skipNoCamera = settings.skipNoCamera,
        )
        val guest = settings.guestAlbum
        return if (settings.guestMode && guest != null) r.forGuests(guest) else r
    }

    /**
     * La page à afficher après un pas [step] (+1 : suivante, -1 : précédente, 0 : recharger
     * l'actuelle). Renvoie null s'il n'y a rien avant.
     */
    fun page(step: Int): Page? {
        val server = settings.server
        val src = ImmichSource(server)
        val rules = rules()
        if (picker.resetIfChanged(rules, server.url)) {
            synchronized(history) { history.clear() }
            pos = -1
        }
        synchronized(history) { history.removeAll { page -> page.any { it.id in hidden } } }
        pos = pos.coerceAtMost(history.size - 1)

        val target = pos + (if (step == 0 && pos < 0) 1 else step)
        if (target < 0) return null
        if (target < history.size) {
            val photos = history[target]
            val page = try {
                Page(photos, photos.map { shrink(load(server, it), photos.size) }, false)
            } catch (e: ImmichException) {
                if (!e.offline) throw e
                offlinePage() ?: throw e
            }
            pos = target
            current = page.photos
            return page
        }

        val page = try { newPage(server, src, rules) } catch (e: ImmichException) {
            if (!e.offline) throw e
            offlinePage() ?: throw e
        }
        if (!page.offline) {
            synchronized(history) {
                history.add(page.photos)
                if (history.size > HISTORY_MAX) history.removeAt(0)
            }
            pos = history.size - 1
            picker.markSeen(page.photos)
            try { seenFile.writeText(JSONObject(seen as Map<*, *>).toString()) } catch (_: Exception) {}
        }
        current = page.photos
        return page
    }

    /** Une photo ; deux si elle est verticale, trois si elle est carrée, quand des compagnes existent. */
    private fun newPage(server: Immich.Server, src: PhotoSource, rules: Rules): Page {
        val first = picker.next(src, rules)
        val photos = arrayListOf(first)
        val bitmaps = arrayListOf(load(server, first))
        // Page d'album ou planche-contact : les photos de la même journée y sont réunies.
        val room = Looks.seriesSize(settings.look(first.id).frame)
        if (room > 1) {
            for (p in picker.together(src, rules, first, room - 1)) {
                val bmp = try { load(server, p) } catch (_: ImmichException) { null } ?: continue
                photos.add(p)
                bitmaps.add(bmp)
            }
            if (photos.size > 1) return Page(photos, bitmaps.map { shrink(it, photos.size) }, false)
        }
        if (settings.pairPortraits) {
            val shape = shapeOf(bitmaps[0])
            val wanted = when (shape) { PORTRAIT -> 2; SQUARE -> 3; else -> 1 }
            while (photos.size < wanted) {
                val partner = picker.partner(src, rules, portrait = shape == PORTRAIT, exclude = photos.map { it.id }.toSet()) ?: break
                val bmp = try { load(server, partner) } catch (_: ImmichException) { null }
                // Les métadonnées peuvent mentir sur la forme : on vérifie sur l'image elle-même.
                if (bmp == null || shapeOf(bmp) != shape) {
                    bmp?.recycle()
                    if (bmp != null) picker.giveBack(partner)
                    break
                }
                photos.add(partner)
                bitmaps.add(bmp)
            }
        }
        return Page(photos, bitmaps, false)
    }

    /** À plus de trois sur une page, chaque photo est petite : inutile de garder toute sa définition. */
    private fun shrink(b: Bitmap, onPage: Int): Bitmap {
        val side = maxOf(b.width, b.height)
        if (onPage <= 3 || side <= MANY_MAX) return b
        val small = Bitmap.createScaledBitmap(b, b.width * MANY_MAX / side, b.height * MANY_MAX / side, true)
        if (small !== b) b.recycle()
        return small
    }

    private fun shapeOf(b: Bitmap): Int {
        val ratio = b.width.toFloat() / b.height
        return when {
            ratio < Immich.Photo.PORTRAIT_BELOW -> PORTRAIT
            ratio <= Immich.Photo.SQUARE_BELOW -> SQUARE
            else -> LANDSCAPE
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
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw ImmichException("Image illisible.")
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
        private const val PORTRAIT = 0
        private const val SQUARE = 1
        private const val LANDSCAPE = 2
        private const val HISTORY_MAX = 60
        private const val LOVED_MAX = 300
        private const val CACHE_MAX = 80
        private const val MANY_MAX = 900 // plus grand côté d'une photo quand la page en réunit plus de trois
    }
}
