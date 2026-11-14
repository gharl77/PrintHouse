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
