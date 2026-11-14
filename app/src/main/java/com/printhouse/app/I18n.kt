package com.printhouse.app

import java.util.Locale

/** Traduit un texte français de l'interface ; sans traduction connue, le français est gardé. */
fun tr(fr: String): String = if (I18n.english) I18nEn.MAP[fr] ?: fr else fr

/**
 * Deux langues : le français (la langue du code, qui sert de clé) et l'anglais ([I18nEn]).
 * Réglage « auto » : français si la tablette est en français, anglais sinon.
 */
object I18n {
    @Volatile var english = false
        private set
    @Volatile var locale: Locale = Locale.getDefault()
        private set

    fun init(settings: Settings) {
        val device = Locale.getDefault()
        english = when (settings.language) {
            "fr" -> false
            "en" -> true
            else -> device.language != "fr"
        }
        locale = when {
            english && device.language != "en" -> Locale.ENGLISH
            !english && device.language != "fr" -> Locale.CANADA_FRENCH
            else -> device
        }
    }
}
