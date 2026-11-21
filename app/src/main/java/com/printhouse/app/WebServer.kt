package com.printhouse.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Mini serveur HTTP (sans dépendance) qui sert la page de réglages (assets/config.html) et une
 * petite API JSON, pour régler le cadre depuis un ordinateur ou un téléphone du même réseau.
 * Chaque modification est signalée à [App.settingsChanged] sur le fil principal.
 */
class WebServer(private val app: App) {
    private class Reply(val code: Int, val type: String, val body: String, val bytes: ByteArray? = null)

    @Volatile private var server: ServerSocket? = null
    @Volatile var port = 0
        private set
    private val main = Handler(Looper.getMainLooper())
    private val settings get() = app.settings
    private val lock = Any()
    private var failures = 0
    private var lockedUntil = 0L

    fun start() {
        if (server != null) return
        val ss = bind(8080) ?: bind(8081) ?: return
        server = ss
        port = ss.localPort
        Thread({ acceptLoop(ss) }, "web-config").apply { isDaemon = true; start() }
    }

    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
    }

    /** Adresse de la page sur le réseau local, ou null (serveur arrêté, pas de Wi-Fi). */
    fun url(): String? = if (server != null) localIp()?.let { "http://$it:$port" } else null

    private fun bind(p: Int): ServerSocket? = try {
        ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) }
    } catch (_: IOException) { null }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (_: IOException) { break }
            Thread({ handle(s) }, "web-req").apply { isDaemon = true; start() }
        }
    }

    // ------------------------------------------------------------ HTTP

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 15_000
                val input = BufferedInputStream(s.getInputStream())
                val parts = (readLine(input) ?: return).split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(input) ?: return
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (method == "POST" && path == "/api/update/upload") { respond(s, upload(headers, input, len)); return }
                if (len > 200_000) { respond(s, Reply(413, JSON, err("Requête trop grosse"))); return }
                val body = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = input.read(body, off, len - off)
                    if (n < 0) break
                    off += n
                }
                val reply = try {
                    route(method, path, headers, String(body, 0, off, Charsets.UTF_8))
                } catch (e: Exception) {
                    Reply(400, JSON, err(e.message ?: "Requête invalide"))
                }
                respond(s, reply)
            } catch (_: Exception) {
            }
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun respond(s: Socket, r: Reply) {
        val bytes = r.bytes ?: r.body.toByteArray(Charsets.UTF_8)
        val reason = when (r.code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Error" }
        val head = "HTTP/1.1 ${r.code} $reason\r\n" +
            "Content-Type: ${r.type}${if (r.bytes == null) "; charset=utf-8" else ""}\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Content-Security-Policy: frame-ancestors 'self'\r\n" +
            "Connection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    // ------------------------------------------------------------ API

    private fun route(method: String, path: String, headers: Map<String, String>, body: String): Reply {
        if (method == "GET" && (path == "/" || path == "/index.html")) {
            val html = app.assets.open("config.html").use { it.readBytes().toString(Charsets.UTF_8) }
            return Reply(200, "text/html", html)
        }
        if (!path.startsWith("/api/")) return Reply(404, "text/plain", "Introuvable")
        if (method == "GET" && path == "/api/i18n") {
            val dict = JSONObject()
            if (I18n.english) for ((k, v) in I18nEn.MAP) dict.put(k, v)
            return ok(JSONObject().put("english", I18n.english).put("dict", dict))
        }

        checkPin(headers["x-pin"])?.let { return it }
        // Protection CSRF simple : l'API n'accepte que les requêtes JSON de la page elle-même.
        if (method == "POST" && headers["content-type"]?.startsWith("application/json") != true) {
            return Reply(400, JSON, err("Type de contenu invalide"))
        }
        // Visage d'une personne, relayé depuis Immich pour les jetons de la page.
        FACE.matchEntire(path)?.let { m ->
            if (method != "GET") return Reply(404, JSON, err("Introuvable"))
            return try {
                Reply(200, "image/jpeg", "", Immich.personThumbnail(settings.server, m.groupValues[1]))
            } catch (e: ImmichException) {
                Reply(502, JSON, err(e.message ?: "Immich injoignable"))
            }
        }
        // L'image actuellement sur le cadre, passe-partout compris.
        if (method == "GET" && path == "/api/frame.jpg") {
            val jpeg = app.shownJpeg ?: return Reply(404, JSON, err("Introuvable"))
            return Reply(200, "image/jpeg", "", jpeg)
        }
        // Vignette d'une photo (pour la liste des photos écartées).
        THUMB.matchEntire(path)?.let { m ->
            if (method != "GET") return Reply(404, JSON, err("Introuvable"))
            return try {
                Reply(200, "image/webp", "", Immich.thumbnail(settings.server, m.groupValues[1]))
            } catch (e: ImmichException) {
                Reply(502, JSON, err(e.message ?: "Immich injoignable"))
            }
        }
        val req = if (body.isBlank()) JSONObject() else JSONObject(body)

        return when ("$method $path") {
            "GET /api/state" -> ok(state())
            // Vérifie l'adresse et la clé enregistrées, et donne les albums à proposer.
            "GET /api/albums" -> immich { JSONObject().put("albums", Choice.array(Immich.albums(settings.server))) }
            "GET /api/people" -> immich { JSONObject().put("people", Choice.array(Immich.people(settings.server))) }
            "POST /api/connection" -> {
                val u = req.optString("url").trim()
                val k = req.optString("key").trim()
                if (u.isNotEmpty()) settings.url = u
                if (k.isNotEmpty()) settings.key = k
                changed(1)
                ok(state())
            }
            "POST /api/settings" -> {
                for (k in listOf("nightFrom", "nightTo", "eveningFrom", "morningFrom", "weekendFrom", "weekendTo")) {
                    if (req.has(k) && Settings.minutes(req.optString(k)) == null) return Reply(400, JSON, err("Heure invalide : écrivez-la comme 22:30."))
                }
                var step: Int? = null // 1 : la source change ; 0 : seule l'apparence change
                fun redraw() { if (step == null) step = 0 }
                if (req.has("sourceMode")) req.optString("sourceMode").let { if (it in setOf("all", "favorites")) { settings.sourceMode = it; step = 1 } }
                if (req.has("albums")) { settings.albums = Choice.list(req.optJSONArray("albums")); step = 1 }
                req.optJSONArray("excludeThemes")?.let { a -> settings.excludeThemes = (0 until a.length()).map { a.optString(it) }; step = 1 }
                if (req.has("excludeSize")) { settings.excludeSize = req.optInt("excludeSize", 100).coerceIn(10, 1000); step = 1 }
                if (req.has("lovedShare")) settings.lovedShare = req.optInt("lovedShare").coerceIn(0, 100)
                req.optJSONArray("themes")?.let { a -> settings.themes = (0 until a.length()).map { a.optString(it) }; step = 1 }
                for ((k, set) in listOf<Pair<String, (Int) -> Unit>>(
                    "personsWeight" to { v -> settings.personsWeight = v }, "themesWeight" to { v -> settings.themesWeight = v }, "albumsWeight" to { v -> settings.albumsWeight = v },
                )) if (req.has(k)) set(req.optInt(k, 2).let { if (it in setOf(1, 2, 4)) it else 2 })
                if (req.has("themeSize")) { settings.themeSize = req.optInt("themeSize", 100).coerceIn(10, 1000); step = 1 }
                if (req.has("persons")) { settings.persons = Choice.list(req.optJSONArray("persons")); step = 1 }
                if (req.has("sinceYears")) { settings.sinceYears = req.optInt("sinceYears").coerceIn(0, 100); step = 1 }
                if (req.has("skipScreenshots")) settings.skipScreenshots = req.optBoolean("skipScreenshots")
                if (req.has("skipSmall")) settings.skipSmall = req.optBoolean("skipSmall")
                if (req.has("skipNoCamera")) settings.skipNoCamera = req.optBoolean("skipNoCamera")
                if (req.has("seriesShare")) settings.seriesShare = req.optInt("seriesShare").coerceIn(0, 100)
                if (req.has("tabletLock")) settings.tabletLock = req.optBoolean("tabletLock")
                if (req.has("updateRepo")) settings.updateRepo = req.optString("updateRepo").trim().removePrefix("https://github.com/").trim('/').take(120)
                if (req.has("language")) {
                    settings.language = req.optString("language").let { if (it in setOf("auto", "fr", "en")) it else "auto" }
                    I18n.init(settings)
                    redraw()
                }
                if (req.has("ageLimit")) settings.ageLimit = req.optInt("ageLimit").coerceIn(0, 120)
                if (req.has("guestMode")) { settings.guestMode = req.optBoolean("guestMode"); step = 1 }
                if (req.has("guestAlbum")) {
                    settings.guestAlbum = Choice.list(JSONArray().put(req.optJSONObject("guestAlbum") ?: JSONObject())).firstOrNull()
                    if (settings.guestMode) step = 1
                }
                if (req.has("weekendNight")) settings.weekendNight = req.optBoolean("weekendNight")
                if (req.has("weekendFrom")) settings.weekendFrom = req.optString("weekendFrom").trim()
                if (req.has("weekendTo")) settings.weekendTo = req.optString("weekendTo").trim()
                if (req.has("motionWake")) settings.motionWake = req.optBoolean("motionWake")
                if (req.has("seasonShare")) settings.seasonShare = req.optInt("seasonShare").coerceIn(0, 100)
                if (req.has("repeatDays")) settings.repeatDays = req.optInt("repeatDays").coerceIn(0, 365)
                if (req.has("birthdayShare")) settings.birthdayShare = req.optInt("birthdayShare").coerceIn(0, 100)
                if (req.has("ageCaption")) req.optString("ageCaption").let { if (it in setOf("never", "birthday", "always")) settings.ageCaption = it }
                if (req.has("printStrength")) { settings.printStrength = req.optInt("printStrength").coerceIn(0, 100); redraw() }
                if (req.has("memoryShare")) settings.memoryShare = req.optInt("memoryShare").coerceIn(0, 100)
                if (req.has("pairPortraits")) settings.pairPortraits = req.optBoolean("pairPortraits")
                if (req.has("interval")) settings.intervalMinutes = req.optInt("interval", 5).coerceIn(1, 1440)
                if (req.has("caption")) { settings.caption = req.optBoolean("caption"); redraw() }
                if (req.has("matColor")) { settings.matColor = if (req.optString("matColor") == "cream") "cream" else "black"; redraw() }
                if (req.has("marginSize")) req.optString("marginSize").let { if (it in setOf("small", "medium", "large")) { settings.marginSize = it; redraw() } }
                if (req.has("night")) settings.night = req.optBoolean("night")
                if (req.has("nightFrom")) settings.nightFrom = req.optString("nightFrom").trim()
                if (req.has("nightTo")) settings.nightTo = req.optString("nightTo").trim()
                if (req.has("autoBrightness")) settings.autoBrightness = req.optBoolean("autoBrightness")
                if (req.has("dayBrightness")) settings.dayBrightness = req.optInt("dayBrightness").coerceIn(1, 100)
                if (req.has("eveningBrightness")) settings.eveningBrightness = req.optInt("eveningBrightness").coerceIn(1, 100)
                if (req.has("eveningFrom")) settings.eveningFrom = req.optString("eveningFrom").trim()
                if (req.has("morningFrom")) settings.morningFrom = req.optString("morningFrom").trim()
                changed(step)
                ok(state())
            }
            "GET /api/ha/entities" -> try {
                ok(JSONObject().put("entities", app.ha.entities()))
            } catch (e: Exception) {
                Reply(502, JSON, err(e.message ?: "Home Assistant injoignable"))
            }
            "POST /api/ha" -> {
                if (req.has("enabled")) settings.haEnabled = req.optBoolean("enabled")
                if (req.has("url")) settings.haUrl = req.optString("url").take(300)
                req.optString("token").trim().takeIf { it.isNotEmpty() }?.let { settings.haToken = it }
                if (req.optBoolean("clearToken")) settings.haToken = ""
                if (req.has("entity")) req.optString("entity").trim().let { if (it.isEmpty() || ENTITY.matches(it)) settings.presenceEntity = it }
                if (req.has("delay")) settings.presenceDelayMinutes = req.optInt("delay", 5).coerceIn(0, 240)
                app.ha.restart()
                if (app.ha.active) Thread.sleep(1500) // le temps d'une première lecture, pour renvoyer l'état réel
                ok(state())
            }
            "POST /api/mqtt" -> {
                if (req.has("enabled")) settings.mqttEnabled = req.optBoolean("enabled")
                if (req.has("host")) settings.mqttHost = req.optString("host").trim().take(200)
                if (req.has("port")) settings.mqttPort = req.optInt("port", 1883).coerceIn(1, 65535)
                if (req.has("user")) settings.mqttUser = req.optString("user").trim().take(200)
                req.optString("pass").takeIf { it.isNotEmpty() }?.let { settings.mqttPass = it }
                if (req.has("deviceName")) settings.deviceName = req.optString("deviceName").trim().take(60).ifEmpty { "PrintHouse" }
                main.post { app.mqtt.restart() }
                Thread.sleep(2500) // laisse au broker le temps de répondre, pour renvoyer l'état réel
                ok(state())
            }
            "POST /api/next" -> { val n = if (req.optInt("step", 1) < 0) -1 else 1; main.post { app.step(n) }; ok(JSONObject().put("ok", true)) }
            "POST /api/screen" -> { val on = req.optBoolean("on", true); main.post { app.setScreen(on) }; ok(JSONObject().put("ok", true)) }
            "POST /api/hidden/clear" -> { app.library.clearHidden(); ok(state()) }
            "GET /api/recent" -> ok(JSONObject().put("photos", JSONArray(app.library.recent(12).map {
                JSONObject().put("id", it.id).put("caption", it.caption())
            })))
            "POST /api/hide" -> {
                val id = req.optString("id")
                if (!UUID.matches(id)) return Reply(400, JSON, err("Requête invalide"))
                app.library.hide(id)
                if (app.library.current.any { it.id == id }) main.post { app.step(1) }
                ok(state())
            }
            "POST /api/favorite" -> immich {
                val id = req.optString("id")
                if (!UUID.matches(id)) throw ImmichException("Requête invalide")
                Immich.favorite(settings.server, id)
                app.library.shown(id)?.let { app.library.love(it) }
                JSONObject().put("ok", true)
            }
            // Pour vérifier la relance automatique après une erreur fatale.
            "POST /api/crash/test" -> { main.postDelayed({ throw IllegalStateException("Essai de plantage demandé depuis la page web") }, 300); ok(JSONObject().put("ok", true)) }
            "GET /api/log" -> ok(JSONObject().put("events", app.events.json()))
            "POST /api/log/clear" -> { app.events.clear(); ok(JSONObject().put("events", JSONArray())) }
            "GET /api/hidden" -> ok(JSONObject().put("ids", JSONArray(app.library.hiddenIds)))
            "POST /api/hidden/restore" -> { app.library.restore(req.optString("id")); ok(state()) }
            "POST /api/export" -> ok(settings.export(req.optBoolean("secrets")).put("version", app.version))
            "POST /api/restore" -> {
                settings.restore(req)
                I18n.init(settings)
                app.library.clearHiddenCache()
                main.post { app.ha.restart(); app.mqtt.restart() }
                changed(1)
                ok(state())
            }
            "POST /api/update/check" -> { app.updater.check(); ok(state()) }
            "POST /api/update/install" -> { app.updater.installLatest(); ok(state()) }
            "POST /api/pin" -> {
                val p = req.optString("pin").trim()
                if (p.isNotEmpty() && !p.all { it.isDigit() }) {
                    return Reply(400, JSON, err("Le code PIN ne contient que des chiffres. La clé API Immich se colle dans « Connexion », pas ici."))
                }
                settings.pin = p
                ok(state())
            }
            else -> Reply(404, JSON, err("Introuvable"))
        }
    }

    private fun immich(block: () -> JSONObject): Reply = try {
        ok(block())
    } catch (e: ImmichException) {
        Reply(502, JSON, err(e.message ?: "Immich injoignable"))
    }

    /** Reçoit un APK et lance son installation (Android demande une confirmation sur la tablette). */
    private fun upload(headers: Map<String, String>, input: InputStream, len: Int): Reply {
        checkPin(headers["x-pin"])?.let { return it }
        if (headers["content-type"]?.startsWith("application/vnd.android.package-archive") != true) {
            return Reply(400, JSON, err("Type de contenu invalide"))
        }
        if (len <= 0 || len > 50_000_000) return Reply(413, JSON, err("Fichier vide ou trop gros"))
        val file = java.io.File(app.cacheDir, "upload.apk")
        file.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size, left))
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
            if (left > 0) { file.delete(); return Reply(400, JSON, err("Envoi interrompu")) }
        }
        val error = app.updater.installFile(file)
        return if (error != null) Reply(400, JSON, err(error)) else ok(state())
    }

    private fun changed(step: Int?) { main.post { app.settingsChanged(step) } }

    private fun checkPin(given: String?): Reply? {
        val pin = settings.pin
        if (pin.isEmpty()) return null
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (now < lockedUntil) return Reply(429, JSON, err("Trop d'essais. Patientez une minute."))
            if (given != pin) {
                failures++
                if (failures >= 5) { failures = 0; lockedUntil = now + 60_000 }
                return Reply(401, JSON, err("Code PIN requis"))
            }
            failures = 0
        }
        return null
    }

    /** Les réglages, sans jamais les secrets (seulement s'ils sont définis). */
    private fun state() = JSONObject()
        .put("url", settings.url)
        .put("keySet", settings.key.isNotEmpty())
        .put("sourceMode", settings.sourceMode)
        .put("albums", Choice.array(settings.albums))
        .put("themes", JSONArray(settings.themes))
        .put("excludeThemes", JSONArray(settings.excludeThemes))
        .put("excludeSize", settings.excludeSize)
        .put("lovedShare", settings.lovedShare)
        .put("lovedCount", app.library.lovedCount)
        .put("personsWeight", settings.personsWeight)
        .put("themesWeight", settings.themesWeight)
        .put("albumsWeight", settings.albumsWeight)
        .put("themeSize", settings.themeSize)
        .put("themeError", app.library.themeError ?: "")
        .put("persons", Choice.array(settings.persons))
        .put("sinceYears", settings.sinceYears)
        .put("memoryShare", settings.memoryShare)
        .put("pairPortraits", settings.pairPortraits)
        .put("interval", settings.intervalMinutes)
        .put("caption", settings.caption)
        .put("skipScreenshots", settings.skipScreenshots)
        .put("skipSmall", settings.skipSmall)
        .put("skipNoCamera", settings.skipNoCamera)
        .put("seriesShare", settings.seriesShare)
        .put("language", settings.language)
        .put("tabletLock", settings.tabletLock)
        .put("update", JSONObject()
            .put("repo", settings.updateRepo)
            .put("latest", app.updater.latest)
            .put("available", app.updater.available))
        .put("ageLimit", settings.ageLimit)
        .put("guestMode", settings.guestMode)
        .put("guestAlbum", settings.guestAlbum?.json() ?: JSONObject.NULL)
        .put("weekendNight", settings.weekendNight)
        .put("weekendFrom", settings.weekendFrom)
        .put("weekendTo", settings.weekendTo)
        .put("motionWake", settings.motionWake)
        .put("camera", if (!MotionWake.hasFrontCamera(app)) "none" else if (MotionWake.hasPermission(app)) "granted" else "denied")
        .put("seasonShare", settings.seasonShare)
        .put("repeatDays", settings.repeatDays)
        .put("birthdayShare", settings.birthdayShare)
        .put("ageCaption", settings.ageCaption)
        .put("printStrength", settings.printStrength)
        .put("birthdaysToday", JSONArray(app.library.birthdaysToday))
        .put("netLocks", app.reachable)
        .put("battery", app.battery)
        .put("charging", app.charging)
        .put("stats", app.library.stats.let {
            JSONObject().put("shown", it.shown).put("screenshots", it.screenshots).put("small", it.small).put("noCamera", it.noCamera)
                .put("series", it.series).put("memories", it.memories).put("season", it.season).put("birthdays", it.birthdays).put("excluded", it.excluded).put("loved", it.loved)
        })
        .put("hiddenCount", app.library.hiddenCount)
        .put("cachedCount", app.library.cachedCount)
        .put("shown", app.shownCaption)
        .put("matColor", settings.matColor)
        .put("marginSize", settings.marginSize)
        .put("night", settings.night)
        .put("nightFrom", settings.nightFrom)
        .put("nightTo", settings.nightTo)
        .put("nightNow", settings.isNightNow())
        .put("screenOff", app.screenOff)
        .put("presenceOff", app.presenceOff)
        .put("autoBrightness", settings.autoBrightness)
        .put("dayBrightness", settings.dayBrightness)
        .put("eveningBrightness", settings.eveningBrightness)
        .put("eveningFrom", settings.eveningFrom)
        .put("morningFrom", settings.morningFrom)
        .put("brightnessNow", settings.brightnessNow()?.let { Math.round(it * 100) } ?: -1)
        .put("ha", JSONObject()
            .put("enabled", settings.haEnabled)
            .put("url", settings.haUrl)
            .put("tokenSet", settings.haToken.isNotEmpty())
            .put("entity", settings.presenceEntity)
            .put("delay", settings.presenceDelayMinutes)
            .put("active", app.ha.active)
            .put("state", app.ha.lastState)
            .put("absentMinutes", app.ha.absentMinutes)
            .put("error", app.ha.error ?: ""))
        .put("mqtt", JSONObject()
            .put("enabled", settings.mqttEnabled)
            .put("host", settings.mqttHost)
            .put("port", settings.mqttPort)
            .put("user", settings.mqttUser)
            .put("passSet", settings.mqttPass.isNotEmpty())
            .put("deviceName", settings.deviceName)
            .put("connected", app.mqtt.connected)
            .put("error", app.mqtt.error ?: ""))
        .put("updateStatus", app.updater.status)
        .put("pinSet", settings.pin.isNotEmpty())
        .put("version", app.version)
        .put("address", url() ?: "")
        .put("device", android.os.Build.MODEL)

    private fun ok(o: JSONObject) = Reply(200, JSON, o.toString())
    private fun err(msg: String) = JSONObject().put("error", tr(msg)).toString()

    companion object {
        private const val JSON = "application/json"
        private val UUID = Regex("""^[0-9a-fA-F-]{36}$""")
        private val THUMB = Regex("""^/api/assets/([0-9a-fA-F-]{36})/thumbnail$""")
        private val FACE = Regex("""^/api/people/([0-9a-fA-F-]{36})/thumbnail$""")
        private val ENTITY = Regex("""^[a-z_]+\.[a-z0-9_]+$""")

        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }
}
