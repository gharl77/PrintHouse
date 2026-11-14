package com.printhouse.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Erreur dont le message peut être montré tel quel à l'écran. [offline] : le serveur est
 * injoignable ou en panne (les photos gardées sur la tablette prennent alors le relais).
 */
class ImmichException(message: String, val offline: Boolean = false) : Exception(tr(message))

/**
 * Lecture du serveur Immich avec une clé API (droits asset.read, asset.view, album.read et,
 * pour choisir des personnes, person.read). Appels bloquants : à faire hors du fil principal.
 */
object Immich {
    data class Server(val url: String, val key: String)

    /** Critères d'une recherche : un album et une personne au plus, tirés au sort par [Library]. */
    data class Filter(
        val favorites: Boolean = false, val albumId: String? = null, val personId: String? = null,
        val takenAfter: String? = null, val takenBefore: String? = null,
    )

    /** Une personne d'Immich et sa date de naissance (« AAAA-MM-JJ », ou null). */
    data class Person(val id: String, val name: String, val birthDate: String?)

    data class Photo(
        val id: String, val date: String?, val city: String?, val country: String?,
        /** Photo verticale d'après ses métadonnées (null = inconnu). */
        val portrait: Boolean? = null,
        /** Souvenir « Ce jour-là » : nombre d'années écoulées (0 = photo ordinaire). */
        val yearsAgo: Int = 0,
        val fileName: String = "",
        val mime: String = "",
        /** L'image porte la marque d'un appareil photo (null = inconnu). */
        val hasCamera: Boolean? = null,
        /** Plus grand côté en pixels (0 = inconnu). */
        val longSide: Int = 0,
        /** « Ophélie, 3 ans » : âge des personnes au moment de la photo (vide = non affiché). */
        val ages: String = "",
    ) {
        val looksLikeScreenshot get() = Logic.looksLikeScreenshot(fileName, mime, hasCamera)

        /** « Québec · juillet 2019 », ou « Québec · il y a 5 ans » pour un souvenir. */
        fun caption(): String {
            val time = when {
                yearsAgo == 1 -> tr("il y a 1 an")
                yearsAgo > 1 -> tr("il y a %d ans").format(yearsAgo)
                else -> monthYear(date)
            }
            return listOfNotNull(ages.ifEmpty { null }, city ?: country, time).joinToString(" · ")
        }

        fun json(): JSONObject = JSONObject().put("id", id).put("date", date ?: "").put("city", city ?: "")
            .put("country", country ?: "").put("yearsAgo", yearsAgo).put("ages", ages)

        companion object {
            fun from(o: JSONObject) = Photo(
                o.getString("id"), o.optString("date").ifEmpty { null }, o.optString("city").ifEmpty { null },
                o.optString("country").ifEmpty { null }, null, o.optInt("yearsAgo"), ages = o.optString("ages"),
            )
        }
    }

    private fun monthYear(iso: String?): String? {
        val m = Regex("""^(\d{4})-(\d{2})""").find(iso ?: return null) ?: return null
        val cal = Calendar.getInstance().apply { clear(); set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, 1) }
        return SimpleDateFormat("LLLL yyyy", I18n.locale).format(cal.time)
    }

    private fun <T> call(server: Server, path: String, body: JSONObject?, accept: String, method: String = if (body != null) "POST" else "GET", read: (InputStream) -> T): T {
        if (server.url.isEmpty() || server.key.isEmpty()) throw ImmichException("Renseignez l'adresse d'Immich et la clé API.")
        var c: HttpURLConnection? = null
        try {
            c = URL(server.url + path).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 40_000
            c.setRequestProperty("x-api-key", server.key)
            c.setRequestProperty("Accept", accept)
            if (body != null) {
                c.requestMethod = method
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code == 401) throw ImmichException("Clé API refusée par Immich (HTTP 401).")
            if (code == 403) throw ImmichException("Il manque une permission à la clé API pour ${path.substringBefore('?')} (HTTP 403).")
            if (code !in 200..299) throw ImmichException("Immich a répondu HTTP $code.", offline = code >= 500)
            return c.inputStream.use(read)
        } catch (e: IOException) {
            throw ImmichException("Serveur Immich injoignable (${e.message ?: e.javaClass.simpleName}).", offline = true)
        } catch (e: ClassCastException) {
            throw ImmichException("Adresse Immich invalide.")
        } finally {
            c?.disconnect()
        }
    }

    private fun json(server: Server, path: String, body: JSONObject? = null): String =
        call(server, path, body, "application/json") { it.readBytes().toString(Charsets.UTF_8) }

    private fun <T> parse(block: () -> T): T =
        try { block() } catch (_: org.json.JSONException) { throw ImmichException("Réponse d'Immich illisible.") }

    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

    private fun photo(a: JSONObject, yearsAgo: Int = 0): Photo {
        val exif = a.optJSONObject("exifInfo")
        var w = exif?.optInt("exifImageWidth") ?: 0
        var h = exif?.optInt("exifImageHeight") ?: 0
        if (exif?.text("orientation") in setOf("5", "6", "7", "8")) w = h.also { h = w }
        return Photo(
            id = a.getString("id"),
            date = a.text("localDateTime") ?: exif?.text("dateTimeOriginal") ?: a.text("fileCreatedAt"),
            city = exif?.text("city"),
            country = exif?.text("country"),
            portrait = if (w > 0 && h > 0) w.toFloat() / h < 0.85f else null,
            yearsAgo = yearsAgo,
            fileName = a.text("originalFileName") ?: "",
            mime = a.text("originalMimeType") ?: "",
            hasCamera = exif?.let { it.text("make") != null || it.text("model") != null },
            longSide = maxOf(w, h),
        )
    }

    private fun query(f: Filter) = JSONObject().put("type", "IMAGE").put("withExif", true).apply {
        if (f.favorites) put("isFavorite", true)
        f.albumId?.let { put("albumIds", JSONArray().put(it)) }
        f.personId?.let { put("personIds", JSONArray().put(it)) }
        f.takenAfter?.let { put("takenAfter", it) }
        f.takenBefore?.let { put("takenBefore", it) }
    }

    private fun iso(c: Calendar) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(c.time)

    /** Début de journée, [years] années avant aujourd'hui. */
    fun yearsBefore(years: Int): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.YEAR, -years)
    }
    fun isoYearsBefore(years: Int) = iso(yearsBefore(years))

    /** [count] photos au hasard répondant à [f]. */
    fun random(server: Server, f: Filter, count: Int): List<Photo> {
        val text = json(server, "/api/search/random", query(f).put("size", count))
        return parse { JSONArray(text).let { arr -> (0 until arr.length()).map { photo(arr.getJSONObject(it)) } } }
    }

    /** Photos prises à la date d'aujourd'hui les années passées (jusqu'à [maxYears] ans). */
    fun onThisDay(server: Server, f: Filter, maxYears: Int): List<Photo> {
        val out = ArrayList<Photo>()
        for (y in 1..maxYears) {
            val from = yearsBefore(y)
            val to = (from.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            val q = query(f.copy(takenAfter = null)).put("takenAfter", iso(from)).put("takenBefore", iso(to)).put("size", 40)
            val text = json(server, "/api/search/metadata", q)
            parse {
                val items = JSONObject(text).optJSONObject("assets")?.optJSONArray("items")
                for (i in 0 until (items?.length() ?: 0)) out.add(photo(items!!.getJSONObject(i), yearsAgo = y))
            }
        }
        return out
    }

    /** Les photos prises le même jour que [p], dans l'ordre où elles ont été prises. */
    fun sameDay(server: Server, p: Photo): List<Photo> {
        val day = Regex("""^\d{4}-\d{2}-\d{2}""").find(p.date ?: return emptyList())?.value ?: return emptyList()
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val start = fmt.parse(day + "T00:00:00.000Z")?.time ?: return emptyList()
        // Fenêtre élargie de 14 h de chaque côté (fuseaux horaires), puis tri sur la date locale.
        val q = JSONObject().put("type", "IMAGE").put("withExif", true).put("size", 200)
            .put("takenAfter", fmt.format(java.util.Date(start - 14 * 3_600_000L)))
            .put("takenBefore", fmt.format(java.util.Date(start + 38 * 3_600_000L)))
        val text = json(server, "/api/search/metadata", q)
        return parse {
            val items = JSONObject(text).optJSONObject("assets")?.optJSONArray("items")
            (0 until (items?.length() ?: 0)).map { photo(items!!.getJSONObject(it)) }
        }.filter { it.date?.startsWith(day) == true }.sortedBy { it.date }
    }

    /** Fenêtre de [days] jours autour d'aujourd'hui, [years] années plus tôt : (début, fin) au format ISO. */
    fun seasonWindow(years: Int, days: Int): Pair<String, String> {
        val mid = yearsBefore(years)
        val from = (mid.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -days) }
        val to = (mid.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, days + 1) }
        return iso(from) to iso(to)
    }

    private fun person(o: JSONObject) = Person(o.optString("id"), o.optString("name"), o.text("birthDate")?.take(10))

    /** Les personnes nommées qui ont une date de naissance dans Immich. */
    fun peopleWithBirthDate(server: Server): List<Person> = parse {
        val arr = JSONObject(json(server, "/api/people?size=500")).optJSONArray("people") ?: JSONArray()
        (0 until arr.length()).map { person(arr.getJSONObject(it)) }.filter { it.name.isNotBlank() && it.birthDate != null }
    }

    /** Les personnes reconnues sur une photo. */
    fun peopleOf(server: Server, id: String): List<Person> = parse {
        val arr = JSONObject(json(server, "/api/assets/$id")).optJSONArray("people") ?: JSONArray()
        (0 until arr.length()).map { person(arr.getJSONObject(it)) }.filter { it.name.isNotBlank() }
    }

    /** « Ophélie, 3 ans » (ou « 4 mois » avant un an) pour quelqu'un né le [birth], à la date [at]. */
    fun ageText(name: String, birth: String, at: String?): String? {
        val (years, months) = Logic.age(birth, at ?: return null) ?: return null
        val age = when {
            years >= 2 -> tr("%d ans").format(years)
            years == 1 -> tr("1 an")
            months >= 2 -> tr("%d mois").format(months)
            months == 1 -> tr("1 mois")
            else -> tr("nouveau-né")
        }
        return "$name, $age"
    }

    /** Marque la photo comme favorite dans Immich (permission asset.update). */
    fun favorite(server: Server, id: String) {
        call(server, "/api/assets/$id", JSONObject().put("isFavorite", true), "application/json", "PUT") { it.readBytes() }
    }

    /** Quelques lignes sur la photo : date complète, lieu, personnes, albums, appareil. */
    fun details(server: Server, id: String): List<String> {
        val a = parse { JSONObject(json(server, "/api/assets/$id")) }
        val exif = a.optJSONObject("exifInfo")
        val lines = ArrayList<String>()
        Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})""").find(a.text("localDateTime") ?: "")?.let { m ->
            val (y, mo, d, h, mi) = m.destructured
            val cal = Calendar.getInstance().apply { clear(); set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt()) }
            lines.add(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.FULL, java.text.DateFormat.SHORT, I18n.locale).format(cal.time))
        }
        listOfNotNull(exif?.text("city"), exif?.text("state"), exif?.text("country")).takeIf { it.isNotEmpty() }?.let { lines.add(it.joinToString(", ")) }
        val people = a.optJSONArray("people")
        (0 until (people?.length() ?: 0)).mapNotNull { people!!.optJSONObject(it)?.text("name") }.takeIf { it.isNotEmpty() }
            ?.let { lines.add(it.joinToString(", ")) }
        try {
            val albums = JSONArray(json(server, "/api/albums?assetId=$id"))
            (0 until albums.length()).map { albums.getJSONObject(it).optString("albumName") }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
                ?.let { lines.add(tr("Albums") + (if (I18n.english) ": " else " : ") + it.joinToString(", ")) }
        } catch (_: Exception) {
        }
        listOfNotNull(exif?.text("make"), exif?.text("model")).takeIf { it.isNotEmpty() }?.let { lines.add(it.joinToString(" ")) }
        a.text("originalFileName")?.let { lines.add(it) }
        return lines
    }

    /** Petite vignette d'une photo, pour la page web. */
    fun thumbnail(server: Server, id: String): ByteArray =
        call(server, "/api/assets/$id/thumbnail?size=thumbnail", null, "image/*") { it.readBytes() }

    fun albums(server: Server): List<Choice> = parse {
        val arr = JSONArray(json(server, "/api/albums"))
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { Choice(it.getString("id"), it.optString("albumName"), it.optInt("assetCount")) }
        }.sortedBy { it.name.lowercase() }
    }

    /** Personnes nommées dans Immich. */
    fun people(server: Server): List<Choice> = parse {
        val root = JSONObject(json(server, "/api/people?size=500"))
        val arr = root.optJSONArray("people") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("isHidden") && it.optString("name").isNotBlank() }
            .map { Choice(it.getString("id"), it.optString("name")) }
            .sortedBy { it.name.lowercase() }
    }

    /** Vignette du visage d'une personne (JPEG carré). */
    fun personThumbnail(server: Server, id: String): ByteArray =
        call(server, "/api/people/$id/thumbnail", null, "image/*") { it.readBytes() }

    /** L'image en taille « preview » (JPEG d'environ 1440 px, déjà dans le bon sens). */
    fun imageBytes(server: Server, id: String): ByteArray =
        call(server, "/api/assets/$id/thumbnail?size=preview", null, "image/*") { it.readBytes() }
}
