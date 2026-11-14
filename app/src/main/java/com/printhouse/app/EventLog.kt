package com.printhouse.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Le journal du cadre : ce qui lui est arrivé (écran éteint ou rallumé et pourquoi, erreurs,
 * photos lentes), gardé sur la tablette et montré sur la page web. Il sert à comprendre après
 * coup pourquoi le cadre était noir ou figé, sans brancher la tablette à un ordinateur.
 */
class EventLog(ctx: Context) {
    private val file = File(ctx.filesDir, "events.json")
    private val items = ArrayList<Pair<Long, String>>()

    init {
        try {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let { items.add(it.getLong("t") to it.getString("text")) }
        } catch (_: Exception) {
        }
    }

    /** Ajoute un événement. Le même texte répété dans les dix minutes n'est noté qu'une fois. */
    @Synchronized fun add(text: String) {
        val now = System.currentTimeMillis()
        if (items.asReversed().take(6).any { it.second == text && now - it.first < REPEAT_MS }) return
        items.add(now to text.take(300))
        while (items.size > MAX) items.removeAt(0)
        try { file.writeText(json().toString()) } catch (_: Exception) {}
    }

    /** Du plus ancien au plus récent. */
    @Synchronized fun json(): JSONArray = JSONArray().apply {
        for ((t, text) in items) put(JSONObject().put("t", t).put("text", text))
    }

    @Synchronized fun clear() {
        items.clear()
        file.delete()
    }

    private companion object {
        const val MAX = 300
        const val REPEAT_MS = 600_000L
    }
}
