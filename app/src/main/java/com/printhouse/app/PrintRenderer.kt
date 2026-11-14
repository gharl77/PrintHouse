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
 */
class PrintRenderer(val w: Int, val h: Int, val dark: Boolean, val marginVmin: Float) {
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

    // --- Tirages ----------------------------------------------------------

    /**
     * Carton + [photos] en tirages (une, ou deux côte à côte) + [captions] au crayon sous chacune
     * (chaîne vide = sans légende).
     */
    fun compose(photos: List<Bitmap>, captions: List<String>): Bitmap {
        val out = mat.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)

        // Fenêtres : les plus grandes possible, sans jamais recadrer, toutes de la même hauteur.
        // La marge du bas est un peu plus haute, comme dans un encadrement classique.
        val n = photos.size
        val gap = if (n > 1) margin * 0.9f else 0f
        val availW = w - 2f * margin
        val availH = h - 2.5f * margin
        val cellW = (availW - gap * (n - 1)) / n
        val ratios = photos.map { it.width.toFloat() / it.height }
        val ph = ratios.minOf { min(availH, cellW / it) }
        val widths = ratios.map { ph * it }
        var x = (w - (widths.sum() + gap * (n - 1))) / 2f
        val top = (margin + (availH - ph) / 2f).roundToInt().toFloat()

        for (i in photos.indices) {
            val left = x.roundToInt().toFloat()
            val r = RectF(left, top, left + widths[i].roundToInt(), top + ph.roundToInt())
            drawPrint(c, photos[i], r)
            captions.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { drawCaption(c, it, r) }
            x += widths[i] + gap
        }
        return out
    }

    private fun drawPrint(c: Canvas, photo: Bitmap, r: RectF) {
        drawBevel(c, r)

        // Encre sur papier mat : couleurs un peu retenues, contraste adouci, puis toute la gamme
        // ramenée entre le noir d'une encre mate et le blanc du papier (jamais un blanc d'écran).
        val cm = ColorMatrix().apply { setSaturation(0.87f) }
        val k = 0.93f
        val o = 127.5f * (1 - k)
        cm.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, o, 0f, k, 0f, 0f, o, 0f, 0f, k, 0f, o, 0f, 0f, 0f, 1f, 0f)))
        fun scale(ink: Int, paper: Int) = (paper - ink) / 255f
        cm.postConcat(ColorMatrix(floatArrayOf(
            scale(Color.red(INK), Color.red(PAPER)), 0f, 0f, 0f, Color.red(INK).toFloat(),
            0f, scale(Color.green(INK), Color.green(PAPER)), 0f, 0f, Color.green(INK).toFloat(),
            0f, 0f, scale(Color.blue(INK), Color.blue(PAPER)), 0f, Color.blue(INK).toFloat(),
            0f, 0f, 0f, 1f, 0f,
        )))
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
        c.drawBitmap(photo, null, r, p)

        // Grain du papier par-dessus l'encre, puis l'ombre du carton.
        c.save()
        c.clipRect(r)
        c.drawPaint(Paint().apply { shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) })
        drawInnerShadow(c, r)
        c.restore()
    }

    private fun drawCaption(c: Canvas, caption: String, r: RectF) {
        val t = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textSize = 1.85f * unit
            color = pencil
            alpha = 165
            textAlign = Paint.Align.RIGHT
            letterSpacing = 0.02f
        }
        c.drawText(caption, r.right, r.bottom + bevel() + 1.5f * unit + t.textSize, t)
    }

    private fun bevel() = max(3f, (0.36f * unit).roundToInt().toFloat())

    /** Biseau à 45° : les faces tournées vers la lumière (bas, droite) sont claires, les autres à l'ombre. */
    private fun drawBevel(c: Canvas, r: RectF) {
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
    }
}
