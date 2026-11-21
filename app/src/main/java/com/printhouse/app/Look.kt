package com.printhouse.app

import java.util.Random

/**
 * L'allure d'une page : le cadre, le papier, le ton et l'écriture de la légende.
 * Sans dépendance à Android, pour pouvoir être testé sur l'ordinateur.
 */
data class Look(val frame: String, val paper: String, val tone: String, val hand: Boolean)

object Looks {
    /** Passe-partout, tirage à marge blanche, instantané, cadre de galerie, page d'album, planche-contact. */
    val FRAMES = listOf("mat", "border", "instant", "gallery", "album", "contact")
    val PAPERS = listOf("matte", "baryta", "cotton")
    val TONES = listOf("color", "bw", "sepia")
    val CAPTIONS = listOf("print", "hand")

    /** Combien de vues une planche-contact doit avoir au minimum pour ressembler à quelque chose. */
    const val CONTACT_MIN = 3

    /** Les choix valides d'une liste « a,b,c », dans l'ordre de [allowed] ; jamais vide. */
    fun clean(csv: String, allowed: List<String>): List<String> {
        val asked = csv.split(',').map { it.trim() }.toSet()
        return allowed.filter { it in asked }.ifEmpty { listOf(allowed[0]) }
    }

    /**
     * L'allure d'une page : un choix au hasard dans chaque liste, toujours le même pour la même
     * [seed] (la photo), afin qu'une page revue en arrière ou redessinée ne change pas d'allure.
     */
    fun pick(frames: List<String>, papers: List<String>, tones: List<String>, captions: List<String>, seed: Int): Look {
        val r = Random(seed.toLong() * 2654435761L)
        fun one(l: List<String>) = if (l.size == 1) l[0] else l[r.nextInt(l.size)]
        // La couleur reste la règle : choisie avec d'autres tons, elle garde une page sur deux.
        val others = tones.filter { it != "color" }
        val tone = if (others.isEmpty() || others.size == tones.size) one(tones) else if (r.nextBoolean()) "color" else one(others)
        return Look(one(frames), one(papers), tone, one(captions) == "hand")
    }

    /** Combien de photos d'une même journée ce cadre peut réunir sur une page. */
    fun seriesSize(frame: String) = when (frame) { "album" -> 3; "contact" -> 6; else -> 1 }

    /**
     * Une planche-contact de moins de trois vues n'en est pas une : la page prend alors un autre
     * des cadres choisis, ou le tirage à marge blanche s'il n'y en a pas d'autre.
     */
    fun settle(look: Look, frames: List<String>, photos: Int): Look {
        if (look.frame != "contact" || photos >= CONTACT_MIN) return look
        val others = frames.filter { it != "contact" }
        return look.copy(frame = if (others.isEmpty()) "border" else others[Math.floorMod(look.hashCode(), others.size)])
    }
}
