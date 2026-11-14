package com.printhouse.app

/** Calculs sans dépendance à Android, pour pouvoir les tester sur l'ordinateur. */
object Logic {
    private const val RAMP = 30 // minutes de passage progressif entre le jour et le soir

    /**
     * Luminosité (0,01 à 1) à la minute [now] de la journée : [day] à partir de [morning], [eve]
     * à partir de [evening], avec un passage progressif d'une demi-heure après chaque seuil.
     */
    fun brightness(now: Int, morning: Int, evening: Int, day: Float, eve: Float): Float {
        fun since(start: Int) = Math.floorMod(now - start, 1440)
        val sinceMorning = since(morning)
        val sinceEvening = since(evening)
        val value = if (sinceMorning < sinceEvening) { // dernier seuil franchi : le matin
            if (sinceMorning < RAMP) eve + (day - eve) * sinceMorning / RAMP else day
        } else {
            if (sinceEvening < RAMP) day + (eve - day) * sinceEvening / RAMP else eve
        }
        return value.coerceIn(0.01f, 1f)
    }

    /**
     * Est-ce la nuit à la minute [minute] du jour [day] (1 = lundi … 7 = dimanche) ? Les heures
     * [week] (début, fin) valent pour toutes les nuits ; [weekend], s'il est donné, pour celles qui
     * commencent le vendredi et le samedi. Une nuit peut enjamber minuit.
     */
    fun isNight(minute: Int, day: Int, week: Pair<Int, Int>, weekend: Pair<Int, Int>?): Boolean {
        fun hours(d: Int) = if (weekend != null && (d == 5 || d == 6)) weekend else week
        // La nuit commencée aujourd'hui…
        val (from, to) = hours(day)
        if (if (from <= to) minute in from until to else minute >= from) return true
        // … ou celle commencée hier soir et qui dure encore ce matin.
        val (yFrom, yTo) = hours(if (day == 1) 7 else day - 1)
        return yFrom > yTo && minute < yTo
    }

    /** Délai entre deux lectures du capteur de présence, en millisecondes. */
    fun presencePollMs(night: Boolean, screenOff: Boolean): Long = when {
        night -> 30_000L      // la nuit, l'écran reste éteint quoi qu'il arrive : rien ne presse
        screenOff -> 2_000L   // écran éteint le jour : se rallumer vite quand quelqu'un arrive
        else -> 5_000L
    }

    /**
     * Combinaisons (album, personne) à parcourir pour chercher les souvenirs du jour, limitées à
     * [cap] : chaque combinaison coûte une requête par année. Une valeur null = pas de filtre.
     */
    fun memoryCombos(albums: List<String>, persons: List<String>, cap: Int): List<Pair<String?, String?>> {
        val a: List<String?> = if (albums.isEmpty()) listOf(null) else albums
        val p: List<String?> = if (persons.isEmpty()) listOf(null) else persons
        return a.flatMap { album -> p.map { person -> album to person } }.shuffled().take(cap)
    }

    private val DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})""")

    /** Âge à la date [at] de quelqu'un né le [birth] (dates « AAAA-MM-JJ… ») : (années, mois), ou null. */
    fun age(birth: String, at: String): Pair<Int, Int>? {
        val (by, bm, bd) = DATE.find(birth)?.destructured ?: return null
        val (ay, am, ad) = DATE.find(at)?.destructured ?: return null
        var months = (ay.toInt() - by.toInt()) * 12 + (am.toInt() - bm.toInt())
        if (ad.toInt() < bd.toInt()) months--
        if (months < 0 || months > 120 * 12) return null
        return months / 12 to months % 12
    }

    /** [birth] tombe-t-il le jour [month]/[day] ? Un 29 février est fêté le 28 les autres années. */
    fun isBirthday(birth: String, month: Int, day: Int, leapYear: Boolean): Boolean {
        val (_, bm, bd) = DATE.find(birth)?.destructured ?: return false
        if (bm.toInt() == month && bd.toInt() == day) return true
        return bm.toInt() == 2 && bd.toInt() == 29 && !leapYear && month == 2 && day == 28
    }

    /** Compare deux versions « 2026.11.2 » nombre par nombre. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private val SCREENSHOT = Regex("screen ?shot|capture d.[ée]cran|^capture[ _-]", RegexOption.IGNORE_CASE)

    /** Capture d'écran : son nom le dit, ou c'est un PNG sans appareil photo. */
    fun looksLikeScreenshot(fileName: String, mime: String, hasCamera: Boolean?) =
        SCREENSHOT.containsMatchIn(fileName) || (mime == "image/png" && hasCamera == false)

    /** "22:30" → 1350 ; null si l'heure est illisible. */
    fun minutes(hhmm: String): Int? {
        val m = Regex("""^\s*(\d{1,2})\s*[:hH]\s*(\d{0,2})\s*$""").find(hhmm) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }
}
