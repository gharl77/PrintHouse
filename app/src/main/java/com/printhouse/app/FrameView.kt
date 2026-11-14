package com.printhouse.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * L'écran du cadre : montre l'image composée par [PrintRenderer] et passe de l'une à l'autre
 * par un fondu vers le carton nu, puis vers le nouveau tirage.
 */
class FrameView(ctx: Context) : View(ctx) {
    private var mat: Bitmap? = null
    private var matColor = PrintRenderer.MAT_BLACK
    private var pencil = PrintRenderer.PENCIL_LIGHT
    private var frame: Bitmap? = null
    private var frameAlpha = 0f
    private var nightAlpha = 0f
    private var notice: String? = null
    private var flash: String? = null
    private var details: List<String>? = null
    private var detailsRight = false // fiche posée sur la photo de droite d'une paire
    private val clearDetails = Runnable { details = null; invalidate() }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(178, 0, 0, 0) }
    private val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF3EDE1.toInt() }
    private var fade: ValueAnimator? = null
    private var nightFade: ValueAnimator? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC) }
    private val clearFlash = Runnable { flash = null; invalidate() }

    val hasPhoto get() = frame != null

    /** Affiche [next] (image plein écran, sur le carton de [from]) ; [done] est appelé après le fondu. */
    fun show(next: Bitmap, from: PrintRenderer, fadeMs: Long = FADE_MS, done: () -> Unit) {
        notice = null
        val old = frame
        if (old == null) {
            setMat(from)
            frame = next
            fadeTo(0f, 1f, fadeMs, done)
        } else {
            fadeTo(frameAlpha, 0f, fadeMs) {
                setMat(from)
                frame = next
                old.recycle()
                fadeTo(0f, 1f, fadeMs, done)
            }
        }
    }

    private fun setMat(r: PrintRenderer) {
        mat = r.mat
        matColor = r.matColor
        pencil = r.pencil
    }

    /** Message au centre du carton ; ignoré tant qu'une photo est affichée (elle reste en place). */
    fun showNotice(text: String) {
        if (frame != null) return
        notice = text
        invalidate()
    }

    /** Fiche de la photo (date, lieu, personnes…), montrée quelques secondes puis effacée. */
    fun showDetails(lines: List<String>, onRight: Boolean = false) {
        details = lines.takeIf { it.isNotEmpty() }
        detailsRight = onRight
        removeCallbacks(clearDetails)
        postDelayed(clearDetails, 9000)
        invalidate()
    }

    /** Petit message passager en bas de l'écran (« Photo écartée »…). */
    fun flash(text: String) {
        flash = text
        removeCallbacks(clearFlash)
        postDelayed(clearFlash, 3500)
        invalidate()
    }

    fun setNight(on: Boolean) {
        val target = if (on) 1f else 0f
        if (nightAlpha == target && nightFade == null) return
        nightFade?.cancel()
        nightFade = ValueAnimator.ofFloat(nightAlpha, target).apply {
            duration = 4000
            addUpdateListener { nightAlpha = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { nightFade = null }
            })
            start()
        }
    }

    private fun fadeTo(from: Float, to: Float, ms: Long, end: () -> Unit) {
        fade?.cancel()
        fade = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { frameAlpha = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(matColor)
        mat?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        frame?.let {
            paint.alpha = (frameAlpha * 255).toInt()
            canvas.drawBitmap(it, 0f, 0f, paint)
        }
        textPaint.color = pencil
        val text = notice
        if (text != null && frame == null) {
            textPaint.textSize = minOf(width, height) * 0.03f
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, (width * 0.6f).toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.4f).build()
            canvas.save()
            canvas.translate(width * 0.2f, (height - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
        flash?.let {
            textPaint.textSize = minOf(width, height) * 0.022f
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(it, minOf(width, height) * 0.04f, height - minOf(width, height) * 0.03f, textPaint)
        }
        details?.let { lines ->
            val u = minOf(width, height) / 100f
            detailPaint.textSize = 2.5f * u
            val pad = 2.2f * u
            val maxText = width * 0.62f
            val shown = lines.map { android.text.TextUtils.ellipsize(it, detailPaint, maxText, android.text.TextUtils.TruncateAt.END).toString() }
            val lineH = detailPaint.textSize * 1.5f
            val boxW = shown.maxOf { detailPaint.measureText(it) } + 2 * pad
            val boxH = shown.size * lineH + 2 * pad - detailPaint.textSize * 0.4f
            val left = if (detailsRight) width - 8f * u - boxW else 8f * u
            val top = height - 11f * u - boxH
            canvas.drawRoundRect(left, top, left + boxW, top + boxH, 1.4f * u, 1.4f * u, panelPaint)
            shown.forEachIndexed { i, line ->
                detailPaint.isFakeBoldText = i == 0
                canvas.drawText(line, left + pad, top + pad + detailPaint.textSize * 0.85f + i * lineH, detailPaint)
            }
        }
        if (nightAlpha > 0f) canvas.drawColor(Color.argb((nightAlpha * 255).toInt(), 0, 0, 0))
    }

    companion object {
        const val FADE_MS = 2400L
        const val QUICK_FADE_MS = 450L // changement demandé du doigt
    }
}
