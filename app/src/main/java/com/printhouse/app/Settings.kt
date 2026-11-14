package com.printhouse.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.reflect.KProperty

/** Un album ou une personne choisis dans Immich. */
data class Choice(val id: String, val name: String, val count: Int = 0) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("count", count)

    companion object {
        private val ID = Regex("""^[0-9a-fA-F-]{36}$""")
        fun list(a: JSONArray?): List<Choice> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val o = a!!.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (ID.matches(id)) Choice(id, o.optString("name").take(120), o.optInt("count")) else null
        }
        fun array(l: List<Choice>) = JSONArray().apply { l.forEach { put(it.json()) } }
    }
}

/** Réglages du cadre, gardés sur la tablette (les secrets sont chiffrés, voir [SecretBox]). */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("printhouse", Context.MODE_PRIVATE)

    private inner class Str(val k: String, val d: String) {
        operator fun getValue(t: Any?, pr: KProperty<*>): String = p.getString(k, d)!!
        operator fun setValue(t: Any?, pr: KProperty<*>, v: String) = p.edit().putString(k, v).apply()
    }
    private inner class Num(val k: String, val d: Int) {
        operator fun getValue(t: Any?, pr: KProperty<*>): Int = p.getInt(k, d)
        operator fun setValue(t: Any?, pr: KProperty<*>, v: Int) = p.edit().putInt(k, v).apply()
    }
    private inner class Flag(val k: String, val d: Boolean) {
        operator fun getValue(t: Any?, pr: KProperty<*>): Boolean = p.getBoolean(k, d)
        operator fun setValue(t: Any?, pr: KProperty<*>, v: Boolean) = p.edit().putBoolean(k, v).apply()
    }
    private inner class Secret(val k: String) {
        operator fun getValue(t: Any?, pr: KProperty<*>): String = SecretBox.decrypt(p.getString(k, "")!!)
        operator fun setValue(t: Any?, pr: KProperty<*>, v: String) = p.edit().putString(k, SecretBox.encrypt(v.trim())).apply()
    }
    private inner class Choices(val k: String) {
        operator fun getValue(t: Any?, pr: KProperty<*>): List<Choice> =
            try { Choice.list(JSONArray(p.getString(k, "[]"))) } catch (_: Exception) { emptyList() }
        operator fun setValue(t: Any?, pr: KProperty<*>, v: List<Choice>) = p.edit().putString(k, Choice.array(v).toString()).apply()
    }

    // --- Immich
    var url: String
        get() = p.getString("url", "")!!
        set(v) = p.edit().putString("url", cleanUrl(v)).apply()
    var key by Secret("key")
    /** Code PIN de la page web de réglages (vide = page ouverte à tout le réseau local). */
    var pin by Secret("pin")

    // --- Photos
    /** "all" (toute la bibliothèque), "favorites" ou "albums" (ceux de [albums]). */
    var sourceMode by Str("sourceMode", "all")
    var albums by Choices("albums")
    /** Personnes à montrer (vide = tout le monde). */
    var persons by Choices("persons")
    /** Ne garder que les photos des N dernières années (0 = toutes). */
    var sinceYears by Num("sinceYears", 0)
    /** Part des photos « Ce jour-là » (prises à la même date une autre année), en %. */
    var memoryShare by Num("memoryShare", 25)
    var pairPortraits by Flag("pairPortraits", true)
    var intervalMinutes by Num("interval", 5)
    var caption by Flag("caption", true)
    /** Écarter ce qui n'est pas une photo : captures d'écran, images trop petites, images sans appareil photo. */
    var skipScreenshots by Flag("skipScreenshots", true)
    var skipSmall by Flag("skipSmall", true)
    var skipNoCamera by Flag("skipNoCamera", false)
    /** Chance (en %) qu'une photo soit suivie d'autres photos de la même journée. */
    var seriesShare by Num("seriesShare", 10)
    /** Part (en %) des photos de la même saison : même période de l'année, les années passées. */
    var seasonShare by Num("seasonShare", 0)
    /** Ne pas remontrer une photo vue dans les N derniers jours (0 = sans importance). */
    var repeatDays by Num("repeatDays", 7)
    /** Le jour de l'anniversaire de quelqu'un, part (en %) de ses photos. */
    var birthdayShare by Num("birthdayShare", 50)
    /** Âge des personnes dans la légende : "never", "birthday" (le jour de leur anniversaire) ou "always". */
    var ageCaption by Str("ageCaption", "always")
    /** Photos écartées d'un double toucher : elles ne reviennent plus. */
    var hidden: Set<String>
        get() = p.getStringSet("hidden", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("hidden", v).apply()

    // --- Apparence
    /** "black" ou "cream". */
    var matColor by Str("matColor", "black")
    /** "small", "medium" ou "large". */
    var marginSize by Str("marginSize", "medium")
    /** Intensité du rendu « tirage papier », de 0 (photo d'origine) à 100 (tirage très mat). */
    var printStrength by Num("printStrength", 100)
    val matDark get() = matColor != "cream"
    val marginVmin get() = when (marginSize) { "small" -> 3.5f; "large" -> 9f; else -> 5.5f }

    // --- Écran
    var night by Flag("night", true)
    var nightFrom by Str("nightFrom", "22:30")
    var nightTo by Str("nightTo", "06:30")
    var autoBrightness by Flag("autoBrightness", true)
    var dayBrightness by Num("dayBrightness", 80)
    var eveningBrightness by Num("eveningBrightness", 25)
    var eveningFrom by Str("eveningFrom", "19:30")
    var morningFrom by Str("morningFrom", "07:30")

    // --- Home Assistant (jeton d'accès longue durée)
    var haEnabled by Flag("haEnabled", false)
    var haUrl: String
        get() = p.getString("haUrl", "")!!
        set(v) = p.edit().putString("haUrl", cleanUrl(v)).apply()
    var haToken by Secret("haToken")
    /** Capteur de présence lu dans Home Assistant (ex. binary_sensor.presence_salon). */
    var presenceEntity by Str("presenceEntity", "")
    /** Minutes sans présence avant d'éteindre l'écran. */
    var presenceDelayMinutes by Num("presenceDelay", 5)

    // --- Home Assistant (MQTT) : pour que le cadre y apparaisse comme un appareil
    var mqttEnabled by Flag("mqttEnabled", false)
    var mqttHost by Str("mqttHost", "")
    var mqttPort by Num("mqttPort", 1883)
    var mqttUser by Str("mqttUser", "")
    var mqttPass by Secret("mqttPass")
    var deviceName by Str("deviceName", "PrintHouse")
    /** Identifiant stable de l'appareil dans Home Assistant. */
    val deviceId: String
        get() = p.getString("deviceId", null) ?: ("printhouse_" + java.util.UUID.randomUUID().toString().take(6)).also {
            p.edit().putString("deviceId", it).apply()
        }

    // --- Divers
    /** "auto", "fr" ou "en". */
    var language by Str("language", "auto")
    /** Exiger le code PIN sur la tablette (réglages, écarter une photo). */
    var tabletLock by Flag("tabletLock", false)
    /** Dépôt GitHub où chercher les nouvelles versions ("compte/depot" ; vide = pas de vérification). */
    var updateRepo by Str("updateRepo", "")

    /** Tous les réglages dans un objet JSON ; les secrets en clair seulement si [withSecrets]. */
    fun export(withSecrets: Boolean): JSONObject {
        val o = JSONObject()
        for ((k, v) in p.all) {
            if (k in SECRETS) continue
            when (v) {
                is Set<*> -> o.put(k, JSONArray(v.toList()))
                else -> o.put(k, v)
            }
        }
        if (withSecrets) o.put("secrets", JSONObject().apply { for (k in SECRETS) put(k, SecretBox.decrypt(p.getString(k, "")!!)) })
        return JSONObject().put("app", "PrintHouse").put("settings", o)
    }

    /** Remplace les réglages par ceux d'une sauvegarde (les secrets absents sont conservés). */
    fun restore(backup: JSONObject) {
        val o = backup.optJSONObject("settings") ?: throw IllegalArgumentException("Ce fichier n'est pas une sauvegarde PrintHouse.")
        val e = p.edit()
        for (k in p.all.keys) if (k !in SECRETS) e.remove(k)
        for (k in o.keys()) {
            if (k == "secrets" || k in SECRETS) continue
            when (val v = o.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is String -> e.putString(k, v)
                is JSONArray -> e.putStringSet(k, (0 until v.length()).map { v.optString(it) }.toSet())
            }
        }
        o.optJSONObject("secrets")?.let { sec ->
            for (k in SECRETS) if (sec.has(k)) e.putString(k, SecretBox.encrypt(sec.optString(k)))
        }
        e.apply()
    }

    val configured get() = url.isNotEmpty() && key.isNotEmpty()
    val server get() = Immich.Server(url, key)

    init {
        // Ancien réglage « source » (un seul album) → nouveaux réglages.
        p.getString("source", null)?.let { old ->
            val e = p.edit().remove("source").remove("sourceLabel")
            when {
                old == "favorites" -> e.putString("sourceMode", "favorites")
                old.startsWith("album:") -> {
                    val name = (p.getString("sourceLabel", "") ?: "").removePrefix("Album · ").replace(Regex(""" \(\d+\)$"""), "")
                    e.putString("sourceMode", "albums")
                        .putString("albums", Choice.array(listOf(Choice(old.removePrefix("album:"), name))).toString())
                }
            }
            e.apply()
        }
    }

    private fun nowMinutes() = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    fun isNightNow(): Boolean {
        if (!night) return false
        val from = minutes(nightFrom) ?: return false
        val to = minutes(nightTo) ?: return false
        val now = nowMinutes()
        return if (from <= to) now in from until to else now >= from || now < to
    }

    /**
     * Luminosité voulue maintenant (0,01 à 1), ou null pour laisser celle de la tablette.
     * Pleine le jour, basse le soir, avec un passage progressif d'une demi-heure.
     */
    fun brightnessNow(): Float? {
        if (!autoBrightness) return null
        val m = minutes(morningFrom) ?: return null
        val e = minutes(eveningFrom) ?: return null
        return Logic.brightness(nowMinutes(), m, e, dayBrightness.coerceIn(1, 100) / 100f, eveningBrightness.coerceIn(1, 100) / 100f)
    }

    companion object {
        private val SECRETS = setOf("key", "pin", "haToken", "mqttPass")

        fun minutes(hhmm: String): Int? = Logic.minutes(hhmm)

        fun cleanUrl(raw: String): String {
            var u = raw.trim().trimEnd('/').removeSuffix("/api")
            if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return u
        }
    }
}
