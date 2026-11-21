package com.printhouse.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Compose l'image affichée : un passe-partout qui occupe tout l'écran, et la ou les photos rendues
 * comme des tirages mats derrière des fenêtres biseautées. Tout est dessiné une fois dans une image
 * de la taille de l'écran ; l'affichage n'a plus qu'à la montrer.
 *
 * [dark] : carton noir uni, qui se confond avec le tour de la tablette ; sinon carton crème texturé.
 * [marginVmin] : largeur de la marge, en centièmes du petit côté de l'écran.
 * [strength] : intensité du rendu papier, de 0 (photo d'origine) à 1 (tirage très mat).
 */
class PrintRenderer(val w: Int, val h: Int, val dark: Boolean, val marginVmin: Float, val strength: Float = 1f) {
    private val unit = min(w, h) / 100f // équivalent d'un « vmin »
    private val margin = marginVmin * unit

    val matColor = if (dark) MAT_BLACK else MAT_CREAM
    val pencil = if (dark) PENCIL_LIGHT else PENCIL_DARK

    private val grain = grainTile()

    /** Le carton seul : fond de l'écran pendant les fondus. */
    val mat: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawMat(Canvas(it)) }

    // --- Carton -----------------------------------------------------------

    private fun drawMat(c: Canvas) {
        c.drawColor(matColor)
        if (dark) return // noir uni, sans texture ni reflet
        val p = Paint()
        p.shader = BitmapShader(fibreTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        c.drawPaint(p)
        p.shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        p.alpha = 150
        c.drawPaint(p)
        // Lumière de pièce très douce venant d'en haut à gauche ; le coin opposé tombe un peu.
        p.alpha = 255
        p.shader = RadialGradient(
            0.2f * w, 0.1f * h, 1.25f * max(w, h),
            intArrayOf(Color.argb(60, 255, 252, 244), Color.argb(0, 255, 252, 244), Color.argb(0, 70, 55, 35), Color.argb(38, 70, 55, 35)),
            floatArrayOf(0f, 0.4f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawPaint(p)
    }

    /** Grain fin du papier : points bruns de très faible opacité. */
    private fun grainTile(): Bitmap {
        val n = 256
        val rnd = Random(11)
        val px = IntArray(n * n) { Color.argb(rnd.nextInt(26), 78, 66, 50) }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /** Fibres du carton : courts traits clairs et sombres, presque invisibles. */
    private fun fibreTile(): Bitmap {
        val n = 512
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = Random(4)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.2f; strokeCap = Paint.Cap.ROUND }
        repeat(1400) {
            val x = rnd.nextFloat() * n
            val y = rnd.nextFloat() * n
            val len = 5f + rnd.nextFloat() * 26f
            val a = rnd.nextFloat() * Math.PI.toFloat()
            p.color = if (rnd.nextInt(3) == 0) Color.argb(26 + rnd.nextInt(20), 255, 253, 246) else Color.argb(7 + rnd.nextInt(11), 84, 70, 52)
            val dx = cos(a) * len
            val dy = sin(a) * len
            // Dessiné aussi décalé d'une tuile pour que le motif se raccorde sans couture.
            for (ox in intArrayOf(0, -n)) for (oy in intArrayOf(0, -n)) c.drawLine(x + ox, y + oy, x + ox + dx, y + oy + dy, p)
        }
        return bmp
    }

    // --- Fonds -----------------------------------------------------------

    /** Ce qui remplit l'écran derrière les tirages, selon le cadre. */
    class Ground(val bitmap: Bitmap?, val color: Int, val pencil: Int)

    private val page: Bitmap by lazy { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawPage(Canvas(it)) } }

    fun ground(frame: String): Ground = when (frame) {
        "album" -> Ground(page, if (dark) PAGE_BLACK else PAGE_KRAFT, if (dark) PENCIL_LIGHT else PENCIL_KRAFT)
        "contact" -> Ground(null, Color.BLACK, PENCIL_LIGHT)
        else -> Ground(mat, matColor, pencil)
    }

    /** Page d'album : papier noir épais, ou kraft si le carton choisi est clair. */
    private fun drawPage(c: Canvas) {
        c.drawColor(if (dark) PAGE_BLACK else PAGE_KRAFT)
        val p = Paint()
        p.shader = BitmapShader(fibreTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        p.alpha = if (dark) 26 else 190
        c.drawPaint(p)
        p.shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        p.alpha = if (dark) 60 else 255
        c.drawPaint(p)
    }

    // --- Papiers et tons --------------------------------------------------

    /** Un papier : son noir le plus dense, son blanc, et ce qu'il fait aux couleurs. */
    private class Paper(val ink: Int, val white: Int, val saturation: Float, val contrast: Float, val grain: Float)

    private fun paper(name: String) = when (name) {
        "baryta" -> Paper(0xFF141210.toInt(), 0xFFF6F2EA.toInt(), 0.96f, 1.0f, 0.4f)  // noirs profonds, blanc neutre
        "cotton" -> Paper(0xFF2A2521.toInt(), 0xFFEFE6D3.toInt(), 0.82f, 0.90f, 1.7f) // doux, chaud, texturé
        else -> Paper(INK, PAPER, 0.87f, 0.93f, 1f)
    }

    /** Le blanc du papier nu (marges des tirages), un peu moins vif à pleine intensité. */
    private fun paperWhite(look: Look) = paper(look.paper).white

    /**
     * Encre sur papier : couleurs un peu retenues, contraste adouci, puis toute la gamme ramenée
     * entre le noir de l'encre et le blanc du papier (jamais un blanc d'écran).
     */
    private fun inkFilter(look: Look): ColorMatrixColorFilter {
        val pa = paper(look.paper)
        fun mix(full: Float) = 1f + (full - 1f) * strength // de « rien » (1) à l'effet complet
        val cm = ColorMatrix()
        when (look.tone) {
            "bw" -> cm.setSaturation(0f)
            "sepia" -> {
                cm.setSaturation(0f)
                cm.postConcat(ColorMatrix(floatArrayOf(1.04f, 0f, 0f, 0f, 0f, 0f, 0.95f, 0f, 0f, 0f, 0f, 0f, 0.80f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            }
            else -> cm.setSaturation(mix(pa.saturation))
        }
        val k = mix(pa.contrast)
        val o = 127.5f * (1 - k)
        cm.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, o, 0f, k, 0f, 0f, o, 0f, 0f, k, 0f, o, 0f, 0f, 0f, 1f, 0f)))
        fun floor(ink: Int) = ink * strength
        fun scale(ink: Int, white: Int) = (255f + (white - 255f) * strength - floor(ink)) / 255f
        cm.postConcat(ColorMatrix(floatArrayOf(
            scale(Color.red(pa.ink), Color.red(pa.white)), 0f, 0f, 0f, floor(Color.red(pa.ink)),
            0f, scale(Color.green(pa.ink), Color.green(pa.white)), 0f, 0f, floor(Color.green(pa.ink)),
            0f, 0f, scale(Color.blue(pa.ink), Color.blue(pa.white)), 0f, floor(Color.blue(pa.ink)),
            0f, 0f, 0f, 1f, 0f,
        )))
        return ColorMatrixColorFilter(cm)
    }

    /** La photo imprimée dans [r], avec le grain de son papier par-dessus l'encre. */
    private fun drawInk(c: Canvas, photo: Bitmap, r: RectF, look: Look) {
        c.drawBitmap(photo, null, r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { colorFilter = inkFilter(look) })
        c.save()
        c.clipRect(r)
        drawGrain(c, paper(look.paper).grain * strength)
        c.restore()
    }

    /** Grain du papier sur la zone découpée ; [amount] peut dépasser 1 (papier très texturé). */
    private fun drawGrain(c: Canvas, amount: Float) {
        var left = amount
        val p = Paint().apply { shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        while (left > 0.01f) {
            p.alpha = (255 * min(1f, left)).toInt()
            c.drawPaint(p)
            left -= 1f
        }
    }

    // --- Mise en page -----------------------------------------------------

    /**
     * Des fenêtres côte à côte, toutes de la même hauteur, les plus grandes possible dans [area]
     * sans jamais recadrer. Chaque photo est entourée de marges ([padL]…) : celles de son papier.
     */
    private fun row(ratios: List<Float>, area: RectF, gap: Float, padL: Float = 0f, padT: Float = 0f, padR: Float = 0f, padB: Float = 0f): List<RectF> {
        val n = ratios.size
        val availW = area.width() - n * (padL + padR) - gap * (n - 1)
        val availH = area.height() - padT - padB
        val ph = min(availH, availW / ratios.sum())
        val widths = ratios.map { ph * it }
        var x = area.left + (area.width() - (widths.sum() + n * (padL + padR) + gap * (n - 1))) / 2f
        val top = (area.top + (area.height() - ph - padT - padB) / 2f + padT).roundToInt().toFloat()
        return widths.map { wd ->
            val left = (x + padL).roundToInt().toFloat()
            x += wd + padL + padR + gap
            RectF(left, top, left + wd.roundToInt(), top + ph.roundToInt())
        }
    }

    private fun ratios(photos: List<Bitmap>) = photos.map { it.width.toFloat() / it.height }

    /** La zone utile de l'écran : la marge du bas est un peu plus haute, comme dans un encadrement. */
    private fun area(scale: Float = 1f) = RectF(margin * scale, margin * scale, w - margin * scale, h - 1.5f * margin * scale)

    // --- Composition ------------------------------------------------------

    /**
     * Le fond + [photos] en tirages + [captions] sous chacune (chaîne vide = sans légende),
     * dans le cadre, le papier et le ton de [look].
     */
    fun compose(photos: List<Bitmap>, captions: List<String>, look: Look = Look("mat", "matte", "color", false)): Bitmap {
        val g = ground(look.frame)
        val out = g.bitmap?.copy(Bitmap.Config.ARGB_8888, true)
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(g.color) }
        val c = Canvas(out)
        when (look.frame) {
            "border" -> composeBorder(c, photos, captions, look)
            "instant" -> composeInstant(c, photos, captions, look)
            "gallery" -> composeGallery(c, photos, captions, look)
            "album" -> composeAlbum(c, photos, captions, look, g.pencil)
            "contact" -> composeContact(c, photos, captions, look, g.pencil)
            else -> composeMat(c, photos, captions, look)
        }
        return out
    }

    /** Passe-partout : fenêtres biseautées dans le carton qui remplit l'écran. */
    private fun composeMat(c: Canvas, photos: List<Bitmap>, captions: List<String>, look: Look) {
        val gap = if (photos.size > 1) margin * 0.9f else 0f
        row(ratios(photos), area(), gap).forEachIndexed { i, r ->
            drawWindow(c, photos[i], r, look, dark)
            caption(c, captions.getOrNull(i), r.right, r.bottom + bevel() + 1.5f * unit, Paint.Align.RIGHT, pencil, look.hand)
        }
    }

    private fun drawWindow(c: Canvas, photo: Bitmap, r: RectF, look: Look, darkMat: Boolean) {
        drawBevel(c, r, darkMat)
        drawInk(c, photo, r, look)
        c.save()
        c.clipRect(r)
        drawInnerShadow(c, r)
        c.restore()
    }

    /** Une feuille de papier nue (marges comprises), avec son ombre portée et son grain. */
    private fun drawSheet(c: Canvas, sheet: RectF, look: Look) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paperWhite(look)
            setShadowLayer(1.5f * unit, 0.25f * unit, 0.6f * unit, Color.argb(120, 20, 14, 8))
        }
        c.drawRect(sheet, p)
        c.save()
        c.clipRect(sheet)
        drawGrain(c, paper(look.paper).grain * 0.8f)
        c.restore()
    }

    /** La photo sur sa feuille, avec le très léger creux que l'encre laisse au bord de l'image. */
    private fun drawOnSheet(c: Canvas, photo: Bitmap, r: RectF, look: Look) {
        drawInk(c, photo, r, look)
        c.drawRect(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(40, 30, 22, 12)
        })
    }

    /** Tirage à marge blanche, posé sur le fond. */
    private fun composeBorder(c: Canvas, photos: List<Bitmap>, captions: List<String>, look: Look) {
        val b = (2.3f * unit).roundToInt().toFloat()
        val gap = if (photos.size > 1) margin * 0.8f else 0f
        row(ratios(photos), area(), gap, b, b, b, b).forEachIndexed { i, r ->
            drawSheet(c, RectF(r.left - b, r.top - b, r.right + b, r.bottom + b), look)
            drawOnSheet(c, photos[i], r, look)
            caption(c, captions.getOrNull(i), r.right + b, r.bottom + b + 1.3f * unit, Paint.Align.RIGHT, pencil, look.hand)
        }
    }

    /** Instantané : large marge en bas, où la légende est écrite ; chaque tirage un peu de travers. */
    private fun composeInstant(c: Canvas, photos: List<Bitmap>, captions: List<String>, look: Look) {
        val side = (2.6f * unit).roundToInt().toFloat()
        val foot = (10.5f * unit).roundToInt().toFloat()
        val gap = if (photos.size > 1) margin * 0.9f else 0f
        val a = area(1.1f).apply { bottom = h - margin * 1.1f }
        row(ratios(photos), a, gap, side, side, side, foot).forEachIndexed { i, r ->
            val sheet = RectF(r.left - side, r.top - side, r.right + side, r.bottom + foot)
            c.save()
            c.rotate(TILTS[(i + photos.size) % TILTS.size] * 0.7f, sheet.centerX(), sheet.centerY())
            drawSheet(c, sheet, look)
            drawOnSheet(c, photos[i], r, look)
            caption(c, captions.getOrNull(i), sheet.centerX(), r.bottom + foot * 0.26f, Paint.Align.CENTER, PENCIL_DARK, look.hand, sheet.width() - 2 * side, 1.25f)
            c.restore()
        }
    }

    /** Cadre de galerie : une moulure en bois autour d'un grand passe-partout clair. */
    private fun composeGallery(c: Canvas, photos: List<Bitmap>, captions: List<String>, look: Look) {
        val wood = (2.4f * unit).roundToInt().toFloat()
        val inner = max(margin, 7f * unit)
        val gap = if (photos.size > 1) inner * 0.7f else 0f
        // Le cadre épouse les photos : la moulure se pose autour du passe-partout, pas de l'écran.
        val outerMax = RectF(margin * 0.6f, margin * 0.6f, w - margin * 0.6f, h - margin * 0.6f)
        val windowArea = RectF(outerMax.left + wood + inner, outerMax.top + wood + inner, outerMax.right - wood - inner, outerMax.bottom - wood - inner * 1.5f)
        val rects = row(ratios(photos), windowArea, gap)
        val matRect = RectF(rects.first().left - inner, rects.first().top - inner, rects.last().right + inner, rects.first().bottom + inner * 1.5f)
        val frame = RectF(matRect.left - wood, matRect.top - wood, matRect.right + wood, matRect.bottom + wood)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = WOOD
        p.setShadowLayer(1.8f * unit, 0.3f * unit, 0.8f * unit, Color.argb(140, 15, 10, 5))
        c.drawRect(frame, p)
        p.clearShadowLayer()
        // Veines du bois, et les quatre onglets de la moulure.
        c.save()
        c.clipRect(frame)
        val rnd = Random(7)
        p.strokeWidth = 1f
        repeat((frame.height() / 3).toInt()) {
            p.color = if (rnd.nextBoolean()) Color.argb(30, 255, 225, 180) else Color.argb(40, 30, 15, 5)
            val y = frame.top + rnd.nextFloat() * frame.height()
            c.drawLine(frame.left, y, frame.right, y + (rnd.nextFloat() - 0.5f) * unit, p)
        }
        p.color = Color.argb(70, 20, 10, 4)
        for ((x0, y0, x1, y1) in listOf(
            listOf(frame.left, frame.top, matRect.left, matRect.top), listOf(frame.right, frame.top, matRect.right, matRect.top),
            listOf(frame.left, frame.bottom, matRect.left, matRect.bottom), listOf(frame.right, frame.bottom, matRect.right, matRect.bottom),
        )) c.drawLine(x0, y0, x1, y1, p)
        c.restore()
        // Le passe-partout clair, avec l'ombre que la moulure porte dessus.
        c.save()
        c.clipRect(matRect)
        c.drawColor(MAT_CREAM)
        p.shader = BitmapShader(fibreTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        c.drawPaint(p)
        p.shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        p.alpha = 150
        c.drawPaint(p)
        p.alpha = 255
        p.shader = null
        drawInnerShadow(c, matRect)
        c.restore()
        rects.forEachIndexed { i, r ->
            drawWindow(c, photos[i], r, look, false)
            caption(c, captions.getOrNull(i), r.right, r.bottom + bevel() + 1.3f * unit, Paint.Align.RIGHT, PENCIL_DARK, look.hand)
        }
    }

    /** Page d'album : des tirages tenus par des coins, un peu de travers, une seule légende écrite sur la page. */
    private fun composeAlbum(c: Canvas, photos: List<Bitmap>, captions: List<String>, look: Look, ink: Int) {
        val b = (1.5f * unit).roundToInt().toFloat()
        val gap = if (photos.size > 1) margin * 0.9f else 0f
        val a = area(1.25f).apply { bottom = h - margin * 1.9f }
        val rects = row(ratios(photos), a, gap, b, b, b, b)
        val corner = 3.2f * unit
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        rects.forEachIndexed { i, r ->
            val sheet = RectF(r.left - b, r.top - b, r.right + b, r.bottom + b)
            c.save()
            c.translate(0f, (if (photos.size > 1) (if (i % 2 == 0) -1.2f else 1.2f) else 0f) * unit)
            c.rotate(TILTS[(i + photos.size + 1) % TILTS.size], sheet.centerX(), sheet.centerY())
            drawSheet(c, sheet, look)
            drawOnSheet(c, photos[i], r, look)
            // Coins photo : quatre triangles de papier qui tiennent le tirage.
            p.color = if (dark) CORNER_CREAM else CORNER_DARK
            p.setShadowLayer(0.4f * unit, 0f, 0.15f * unit, Color.argb(90, 0, 0, 0))
            val o = 0.45f * unit
            for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                val x = if (sx < 0) sheet.left - o else sheet.right + o
                val y = if (sy < 0) sheet.top - o else sheet.bottom + o
                c.drawPath(Path().apply { moveTo(x, y); lineTo(x - sx * corner, y); lineTo(x, y - sy * corner); close() }, p)
            }
            p.clearShadowLayer()
            c.restore()
        }
        val text = captions.firstOrNull { it.isNotEmpty() }
        caption(c, text, rects.first().left - b, rects.maxOf { it.bottom } + b + 2.6f * unit, Paint.Align.LEFT, ink, look.hand, w * 0.8f, 1.2f)
    }

    /** Planche-contact : les vues d'une même journée sur une ou deux bandes de film. */
    private fun composeContact(c: Canvas, photos: List<Bitmap>, captions: List<String>, look: Look, ink: Int) {
        val rows = if (photos.size <= 3) 1 else 2
        val perRow = (photos.size + rows - 1) / rows
        val band = 2.2f * unit            // bord perforé du film
        val gapX = 1.3f * unit
        val gapY = 3f * unit
        // Les vues prennent la forme de la plupart des photos (film tenu debout pour des portraits),
        // et la bande se resserre autour d'elles.
        val shape = ratios(photos).sorted()[photos.size / 2].coerceIn(0.72f, 1.5f)
        val maxW = (w - 2 * margin - gapX * (perRow + 1)) / perRow
        val maxH = (h - 2.6f * margin - gapY * (rows - 1)) / rows - 2 * band
        val cellH = min(maxH, maxW / shape)
        val cellW = cellH * shape
        val stripW = perRow * cellW + gapX * (perRow + 1)
        val stripLeft = (w - stripW) / 2f
        val stripH = cellH + 2 * band
        val top0 = (h - 0.6f * margin - (stripH * rows + gapY * (rows - 1))) / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILM_MARK; textSize = 1.15f * unit; typeface = Typeface.MONOSPACE; alpha = 190 }
        val first = 3 + Math.floorMod(photos.size * 7 + look.hashCode(), 20) // numéro de vue de départ, comme sur un vrai film
        for (row in 0 until rows) {
            val top = top0 + row * (stripH + gapY)
            p.color = FILM
            c.drawRect(stripLeft, top, stripLeft + stripW, top + stripH, p)
            // Perforations en haut et en bas.
            p.color = Color.BLACK
            val holeW = 1.25f * unit
            val holeH = 0.85f * unit
            var x = stripLeft + holeW
            while (x + holeW < stripLeft + stripW) {
                for (y in listOf(top + (band - holeH) * 0.3f, top + stripH - (band - holeH) * 0.3f - holeH))
                    c.drawRoundRect(x, y, x + holeW, y + holeH, 0.2f * unit, 0.2f * unit, p)
                x += holeW * 2.1f
            }
            for (k in 0 until perRow) {
                val i = row * perRow + k
                if (i >= photos.size) break
                val left = stripLeft + gapX + k * (cellW + gapX)
                val cell = RectF(left, top + band, left + cellW, top + band + cellH)
                p.color = Color.BLACK
                c.drawRect(cell, p)
                val ratio = photos[i].width.toFloat() / photos[i].height
                val pw = min(cell.width(), cell.height() * ratio)
                val phh = pw / ratio
                val r = RectF(cell.centerX() - pw / 2, cell.centerY() - phh / 2, cell.centerX() + pw / 2, cell.centerY() + phh / 2)
                c.drawBitmap(photos[i], null, r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { colorFilter = inkFilter(look) })
                c.drawText("${first + i}", cell.left + 0.3f * unit, top + stripH - (band - holeH) * 0.3f - holeH - 0.25f * unit, num)
            }
        }
        val text = captions.firstOrNull { it.isNotEmpty() }?.let { "$it · ${photos.size} ${tr("vues")}" }
        caption(c, text, stripLeft + stripW, top0 + rows * stripH + (rows - 1) * gapY + 1.4f * unit, Paint.Align.RIGHT, ink, look.hand)
    }

    /**
     * Légende au crayon dont le haut est en [top] : en italique d'imprimerie, ou d'une écriture à
     * la main. Trop longue pour [maxWidth], elle est raccourcie par la fin.
     */
    private fun caption(c: Canvas, text: String?, x: Float, top: Float, align: Paint.Align, color: Int, hand: Boolean, maxWidth: Float = w * 0.6f, scale: Float = 1f) {
        if (text.isNullOrEmpty()) return
        val t = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            if (hand) {
                typeface = HAND
                textSize = 2.9f * unit * scale
            } else {
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                textSize = 1.85f * unit * scale
                letterSpacing = 0.02f
            }
            this.color = color
            alpha = if (hand) 205 else 165
            textAlign = align
        }
        val shown = TextUtils.ellipsize(text, t, maxWidth, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, x, top + t.textSize * (if (hand) 0.85f else 1f), t)
    }

    private fun bevel() = max(3f, (0.36f * unit).roundToInt().toFloat())

    /** Biseau à 45° : les faces tournées vers la lumière (bas, droite) sont claires, les autres à l'ombre. */
    private fun drawBevel(c: Canvas, r: RectF, dark: Boolean) {
        val b = bevel()
        val o = RectF(r.left - b, r.top - b, r.right + b, r.bottom + b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun face(color: Int, vararg pts: Float) {
            val path = Path().apply {
                moveTo(pts[0], pts[1])
                for (i in 2 until pts.size step 2) lineTo(pts[i], pts[i + 1])
                close()
            }
            p.color = color
            c.drawPath(path, p)
        }
        // Sur carton noir, l'âme crème du carton ressort ; les faces à l'ombre sont plus sombres.
        val faces = if (dark) intArrayOf(0xFF8E877A.toInt(), 0xFF9F988A.toInt(), 0xFFEDE8DC.toInt(), 0xFFF3EFE4.toInt())
        else intArrayOf(0xFFCFC7B7.toInt(), 0xFFD6CEBF.toInt(), 0xFFF9F6EE.toInt(), 0xFFFBF8F1.toInt())
        face(faces[0], o.left, o.top, o.right, o.top, r.right, r.top, r.left, r.top)             // haut
        face(faces[1], o.left, o.top, r.left, r.top, r.left, r.bottom, o.left, o.bottom)         // gauche
        face(faces[2], o.right, o.top, o.right, o.bottom, r.right, r.bottom, r.right, r.top)     // droite
        face(faces[3], o.left, o.bottom, r.left, r.bottom, r.right, r.bottom, o.right, o.bottom) // bas
        if (!dark) { // arête du carton, à peine marquée
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f
            p.color = Color.argb(28, 90, 75, 55)
            c.drawRect(o.left - 0.5f, o.top - 0.5f, o.right + 0.5f, o.bottom + 0.5f, p)
        }
    }

    /** Ombre que l'épaisseur du carton porte sur le tirage, en haut et à gauche. */
    private fun drawInnerShadow(c: Canvas, r: RectF) {
        val d = 1.0f * unit
        val shade = Color.argb(96, 45, 35, 22)
        val p = Paint()
        p.shader = LinearGradient(0f, r.top, 0f, r.top + d, shade, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.top, r.right, r.top + d, p)
        p.shader = LinearGradient(r.left, 0f, r.left + d * 0.85f, 0f, shade, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.top, r.left + d * 0.85f, r.bottom, p)
        // Fin liseré sombre tout autour, là où le tirage passe sous le carton.
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = Color.argb(70, 30, 22, 12)
        c.drawRect(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f, p)
    }

    companion object {
        const val MAT_BLACK = 0xFF000000.toInt()
        const val MAT_CREAM = 0xFFEBE5D8.toInt()
        const val PAPER = 0xFFF3EDE1.toInt()  // blanc du papier photo
        const val INK = 0xFF25211E.toInt()    // noir le plus dense d'une encre mate
        const val PENCIL_LIGHT = 0xFFB9B2A4.toInt() // crayon clair sur carton noir
        const val PENCIL_DARK = 0xFF6F675D.toInt()
        const val PENCIL_KRAFT = 0xFF3F3122.toInt() // crayon gras sur papier kraft
        const val PAGE_BLACK = 0xFF0C0B0A.toInt()   // page d'album en papier noir
        const val PAGE_KRAFT = 0xFFB1976F.toInt()
        const val CORNER_CREAM = 0xFFD9D0BC.toInt()
        const val CORNER_DARK = 0xFF2B241D.toInt()
        const val WOOD = 0xFF6B4A2E.toInt()         // moulure en noyer
        const val FILM = 0xFF171513.toInt()         // support du film
        const val FILM_MARK = 0xFFD08A2E.toInt()    // numéros de vue
        /** Légers angles des tirages posés à la main, en degrés. */
        private val TILTS = floatArrayOf(-2.2f, 1.8f, -1.2f, 2.4f)
        /** Écriture à la main livrée avec Android (Dancing Script) ; à défaut, la police ordinaire. */
        private val HAND: Typeface by lazy { Typeface.create("cursive", Typeface.NORMAL) }
    }
}
