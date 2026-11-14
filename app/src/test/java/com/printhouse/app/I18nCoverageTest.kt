package com.printhouse.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Le français est la langue du code ; chaque texte montré à l'utilisateur doit avoir son anglais
 * dans [I18nEn]. Ce test relit la page web et le code pour trouver les textes oubliés.
 */
class I18nCoverageTest {
    private val main = File("src/main")

    /** Textes identiques dans les deux langues, ou qui ne se traduisent pas. */
    private val same = setOf("English", "Français", "PrintHouse", "Immich", "Home Assistant", "MQTT", "Port", "Broker", "×", "—")

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\$", "$").replace("\\\\", "\\")
    private fun needsTranslation(s: String) = s !in same && Regex("[A-Za-zÀ-ÿ]{2}").containsMatchIn(s) &&
        !Regex("""[\d\s.:/a-z_@#-]+""").matches(s)

    private fun missing(keys: Collection<String>) = keys.map { it.trim() }.filter { needsTranslation(it) }
        .filter { it !in I18nEn.MAP && I18nEn.MAP.keys.none { k -> k.trim() == it } }.distinct()

    @Test fun `every text of the web page has an English translation`() {
        val html = File(main, "assets/config.html").readText()
        val body = html.substring(html.indexOf("<body>"), html.indexOf("<script>"))
        val keys = ArrayList<String>()
        Regex(">([^<>]+)<").findAll(body).forEach { keys.add(it.groupValues[1].replace("&amp;", "&")) }
        Regex("""(?:placeholder|aria-label|title|alt)="([^"]+)"""").findAll(body).forEach { keys.add(it.groupValues[1]) }
        Regex("""\bt\("((?:[^"\\]|\\.)*)"\)""").findAll(html).forEach { keys.add(unescape(it.groupValues[1])) }
        val tabs = html.substring(html.indexOf("const TABS"), html.indexOf("let PIN"))
        Regex(""""((?:[^"\\]|\\.)+)"""").findAll(tabs).forEach { keys.add(unescape(it.groupValues[1])) }
        val gaps = missing(keys)
        assertTrue("Sans traduction anglaise dans la page web :\n" + gaps.joinToString("\n"), gaps.isEmpty())
    }

    @Test fun `every text of the app has an English translation`() {
        val keys = ArrayList<String>()
        File(main, "java/com/printhouse/app").listFiles { f -> f.extension == "kt" }!!.forEach { f ->
            val code = f.readText()
            Regex("""\btr\("((?:[^"\\]|\\.)*)"\)""").findAll(code).forEach { keys.add(unescape(it.groupValues[1])) }
            // Messages traduits à la création (ImmichException) ou à l'envoi (err) ; ceux qui
            // contiennent une valeur variable restent en français.
            Regex("""(?:ImmichException|\berr)\("((?:[^"\\$]|\\.)*)"[,)]""").findAll(code).forEach { keys.add(unescape(it.groupValues[1])) }
        }
        val gaps = missing(keys)
        assertTrue("Sans traduction anglaise dans l'app :\n" + gaps.joinToString("\n"), gaps.isEmpty())
    }

    @Test fun `translations keep the same placeholders`() {
        val bad = I18nEn.MAP.filter { (fr, en) -> Regex("%d").findAll(fr).count() != Regex("%d").findAll(en).count() }
        assertTrue("Nombre de %d différent : " + bad.keys, bad.isEmpty())
    }
}
