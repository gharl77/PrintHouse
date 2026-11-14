package com.printhouse.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lien avec Home Assistant par jeton d'accès longue durée : le cadre lit l'état d'un capteur de
 * présence et éteint son écran quand la pièce est vide depuis un moment, puis le rallume dès que
 * quelqu'un revient. Rien n'est écrit dans Home Assistant.
 */
class HaLink(private val app: App) {
    private val s get() = app.settings
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0
    @Volatile private var reset = false

    /** L'écran vient d'être rallumé à la main : le délai sans présence repart de zéro. */
    fun resetAbsence() { reset = true }

    /** Dernier état lu du capteur ("on", "off"…), vide tant que rien n'a été lu. */
    @Volatile var lastState = ""
        private set
    @Volatile var error: String? = null
        private set
    /** Depuis combien de minutes la pièce est vide (0 = quelqu'un est là). */
    @Volatile var absentMinutes = 0
        private set

    val active get() = s.haEnabled && s.haUrl.isNotEmpty() && s.haToken.isNotEmpty() && s.presenceEntity.isNotEmpty()

    /** (Re)démarre la surveillance selon les réglages ; à appeler après tout changement. */
    fun restart() {
        val my = ++gen
        val on = active
        // Écran éteint, la tablette doit continuer à interroger Home Assistant pour se rallumer.
        app.refreshLocks()
        lastState = ""
        error = null
        absentMinutes = 0
        if (!on) {
            main.post { if (app.presenceOff) app.setPresenceScreen(true) }
            return
        }
        Thread({ loop(my) }, "ha-presence").apply { isDaemon = true; start() }
    }

    private fun loop(my: Int) {
        var absentSince = 0L
        while (my == gen) {
            val now = SystemClock.elapsedRealtime()
            if (reset) { reset = false; absentSince = 0L }
            try {
                val state = JSONObject(get("/api/states/" + s.presenceEntity)).optString("state")
                if (my != gen) return
                lastState = state
                error = null
                if (state in ABSENT) {
                    if (absentSince == 0L) absentSince = now
                    absentMinutes = ((now - absentSince) / 60_000L).toInt()
                    if (!app.presenceOff && now - absentSince >= s.presenceDelayMinutes.coerceAtLeast(0) * 60_000L) {
                        app.events.add(tr("Pièce vide depuis %d min : écran éteint").format(absentMinutes))
                        main.post { if (my == gen) app.setPresenceScreen(false) }
                    }
                } else {
                    absentSince = 0L
                    absentMinutes = 0
                    if (app.presenceOff) {
                        app.events.add(tr("Présence détectée : écran rallumé"))
                        main.post { if (my == gen) app.setPresenceScreen(true) }
                    }
                }
            } catch (e: Exception) {
                // Home Assistant injoignable : dans le doute, l'écran reste (ou revient) allumé.
                if (my != gen) return
                error = e.message ?: e.javaClass.simpleName
                app.events.add("Home Assistant : $error")
                absentSince = 0L
                absentMinutes = 0
                if (app.presenceOff) main.post { if (my == gen) app.setPresenceScreen(true) }
            }
            try { Thread.sleep(Logic.presencePollMs(s.isNightNow(), app.screenOff)) } catch (_: InterruptedException) { return }
        }
    }

    private fun get(path: String): String {
        var c: HttpURLConnection? = null
        try {
            c = URL(s.haUrl + path).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 20_000
            c.setRequestProperty("Authorization", "Bearer " + s.haToken)
            c.setRequestProperty("Accept", "application/json")
            when (val code = c.responseCode) {
                in 200..299 -> return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                401, 403 -> throw IOException("Jeton refusé par Home Assistant (HTTP $code).")
                404 -> throw IOException("Entité introuvable dans Home Assistant.")
                else -> throw IOException("Home Assistant a répondu HTTP $code.")
            }
        } catch (e: IOException) {
            if (e.message?.contains("Home Assistant") == true) throw e
            throw IOException("Home Assistant injoignable (${e.message ?: e.javaClass.simpleName}).")
        } catch (e: ClassCastException) {
            throw IOException("Adresse Home Assistant invalide.")
        } finally {
            c?.disconnect()
        }
    }

    /** Entités pouvant servir de capteur de présence : [{id, name, state}], triées par nom. */
    fun entities(): JSONArray {
        val all = JSONArray(get("/api/states"))
        val rows = ArrayList<JSONObject>()
        for (i in 0 until all.length()) {
            val o = all.getJSONObject(i)
            val id = o.optString("entity_id")
            if (id.substringBefore('.') !in DOMAINS) continue
            val name = o.optJSONObject("attributes")?.optString("friendly_name").orEmpty().ifEmpty { id }
            rows.add(JSONObject().put("id", id).put("name", name).put("state", o.optString("state")))
        }
        rows.sortBy { it.optString("name").lowercase() }
        return JSONArray(rows)
    }

    companion object {
        /** États qui veulent dire « personne » ; tout autre état (y compris inconnu) garde l'écran allumé. */
        private val ABSENT = setOf("off", "not_home")
        private val DOMAINS = setOf("binary_sensor", "person", "device_tracker", "input_boolean", "group")
    }
}
