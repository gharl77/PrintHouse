package com.printhouse.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.text.InputType
import android.widget.EditText
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Le cadre : plein écran, à l'horizontale, écran toujours allumé le jour.
 * Toucher ou balayer vers la gauche = photo suivante ; balayer vers la droite = précédente ;
 * balayer vers le haut = favori dans Immich ; vers le bas = fiche de la photo ;
 * double toucher = écarter la photo ; appui long = réglages.
 */
class MainActivity : Activity() {
    private val app get() = application as App
    private lateinit var settings: Settings
    private lateinit var view: FrameView
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val side = Executors.newSingleThreadExecutor() // favoris et fiches, sans bloquer le défilement
    private var busy = false
    private var pending: Int? = null // demande arrivée pendant un fondu : faite juste après
    private var renderer: PrintRenderer? = null // touché seulement depuis [io]

    private val tick = Runnable {
        if (!dark()) show(1)
        scheduleTick()
    }
    private val displayTick = object : Runnable {
        override fun run() {
            applyDisplay()
            main.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = app.settings
        readSetupExtras(intent)
        view = FrameView(this)
        setContentView(view)
        // Le cadre passe devant l'écran de verrouillage et peut rallumer l'écran (matin, démarrage).
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        app.frame = this

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                userStep(1)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                hideAt(e.x)
                return true
            }
            // Balayage horizontal : vers la gauche = photo suivante, vers la droite = précédente.
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                val dy = e2.y - e1.y
                val far = 80 * resources.displayMetrics.density
                if (abs(dx) >= far && abs(dx) >= 1.5f * abs(dy)) {
                    userStep(if (dx < 0) 1 else -1)
                    return true
                }
                // Balayage vertical : vers le haut = favori, vers le bas = fiche de la photo.
                if (abs(dy) >= far && abs(dy) >= 1.5f * abs(dx)) {
                    if (dy < 0) favoriteAt(e1.x) else detailsAt(e1.x)
                    return true
                }
                return false
            }
            override fun onLongPress(e: MotionEvent) = openSettings()
        })
        view.setOnTouchListener { _, e ->
            if (app.dark) {
                // Cadre noir (nuit, pièce vide) : le premier toucher le rallume, sans changer de photo.
                if (e.actionMasked == MotionEvent.ACTION_DOWN) app.peek()
                true
            } else gestures.onTouchEvent(e)
        }

        if (savedInstanceState == null && !settings.configured) openSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (app.frame === this) app.frame = null
    }

    /** Le bouton Retour ne ferme pas le cadre : il n'y a rien « derrière » lui. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readSetupExtras(intent)
    }

    /**
     * Configuration depuis l'ordinateur, sans taper la clé sur la tablette :
     * adb shell am start -n com.printhouse.app/.MainActivity --es immich_url "http://…" --es immich_key "…"
     */
    private fun readSetupExtras(intent: Intent?) {
        intent?.getStringExtra("immich_url")?.let { settings.url = it }
        intent?.getStringExtra("immich_key")?.let { settings.key = it }
    }

    /** Verrouillage : le code PIN de la page web est aussi exigé sur la tablette. */
    private val locked get() = settings.tabletLock && settings.pin.isNotEmpty()

    private fun openSettings() {
        if (!locked) { startActivity(Intent(this, SettingsActivity::class.java)); return }
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = tr("Code PIN")
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(tr("Réglages verrouillés"))
            .setView(field)
            .setPositiveButton(tr("Ouvrir")) { _, _ ->
                if (field.text.toString() == settings.pin) startActivity(Intent(this, SettingsActivity::class.java))
                else view.flash(tr("Code incorrect."))
            }
            .setNegativeButton(tr("Annuler"), null)
            .setOnDismissListener { hideSystemBars() }
            .show()
    }

    /** La photo sous le doigt (pour une paire : gauche ou droite). */
    private fun photoAt(x: Float): Immich.Photo? {
        val current = app.library.current
        if (current.isEmpty() || view.width == 0) return null
        return current[(x * current.size / view.width).toInt().coerceIn(0, current.size - 1)]
    }

    /** Balayage vers le haut : la photo devient favorite dans Immich. */
    private fun favoriteAt(x: Float) {
        val photo = photoAt(x) ?: return
        val server = settings.server
        side.execute {
            val msg = try {
                Immich.favorite(server, photo.id)
                app.library.love(photo)
                tr("Ajoutée aux favoris d'Immich.")
            } catch (e: ImmichException) {
                if (e.message?.contains("403") == true) tr("Pour ajouter aux favoris, donnez la permission asset.update à la clé API.")
                else e.message ?: tr("Immich injoignable.")
            }
            main.post { view.flash(msg) }
        }
    }

    /** Balayage vers le bas : la fiche de la photo s'affiche quelques secondes. */
    private fun detailsAt(x: Float) {
        val photo = photoAt(x) ?: return
        val server = settings.server
        side.execute {
            try {
                val lines = Immich.details(server, photo.id)
                main.post { view.showDetails(lines, onRight = app.library.current.size > 1 && x > view.width / 2f) }
            } catch (e: ImmichException) {
                main.post { view.flash(e.message ?: tr("Immich injoignable.")) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        hideSystemBars()
        displayTick.run()
        // post : la vue doit connaître sa taille avant de composer la première image.
        view.post {
            show(if (view.hasPhoto) 0 else 1)
            scheduleTick()
        }
    }

    override fun onStop() {
        super.onStop()
        started = false
        main.removeCallbacks(tick)
        main.removeCallbacks(displayTick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
        // Une fenêtre passée devant le cadre peut laisser l'écran s'éteindre : on en garde la trace.
        else if (!dark() && !isFinishing) app.events.add(tr("Une autre fenêtre est passée devant le cadre"))
    }

    private var started = false

    /** Dans quel état est le cadre, pour le journal : visible ou non, et s'il retient bien l'écran. */
    fun describe(): String = listOf(
        tr(if (!started) "cadre en arrière-plan" else if (hasWindowFocus()) "cadre au premier plan" else "cadre visible mais derrière une autre fenêtre"),
        tr(if (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) "maintien de l'écran demandé" else "maintien de l'écran non demandé"),
    ).joinToString(", ")

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    /** Écran éteint : la nuit, ou quand Home Assistant dit que la pièce est vide. */
    private fun dark() = app.dark
    private var wasDark = false

    /**
     * Le jour, l'écran reste allumé, à la luminosité voulue pour l'heure. Éteint (nuit ou pièce
     * vide) : écran noir, rétroéclairage au minimum, puis la tablette se met en veille
     * d'elle-même ; [WakeReceiver] la rallume à la fin de la nuit.
     */
    fun applyDisplay() {
        val dark = dark()
        view.setNight(dark)
        // Le cadre se rallume (matin, retour de quelqu'un) : une nouvelle photo l'accueille.
        if (wasDark && !dark) { show(1); scheduleTick() }
        if (wasDark != dark) app.events.add(if (dark) tr("Écran éteint") + " : " + (app.darkReason() ?: "") else tr("Écran rallumé"))
        wasDark = dark
        val lp = window.attributes
        val wanted = if (dark) 0.01f else settings.brightnessNow(app.ha.lux) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        view.setWarmth(if (dark) 0f else settings.warmthNow(app.ha.lux))
        if (abs(lp.screenBrightness - wanted) > 0.004f) {
            lp.screenBrightness = wanted
            window.attributes = lp
        }
        if (dark) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app.holdScreen(!dark)
        WakeReceiver.schedule(this, settings)
        // La nuit vient d'être raccourcie ou désactivée pendant que l'écran dormait.
        if (!dark && !getSystemService(PowerManager::class.java).isInteractive) WakeReceiver.wake(this)
    }

    /** Réponse à la demande d'accès à la caméra (réveil par mouvement). */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        app.events.add(tr(if (MotionWake.hasPermission(this)) "Accès à la caméra accordé" else "Accès à la caméra refusé"))
        app.updateMotion()
        hideSystemBars()
    }

    /** Un réglage a changé sur la page web ([step] : voir [App.settingsChanged]). */
    fun onSettingsChanged(step: Int?) {
        // Le réveil par mouvement vient d'être activé : Android demande l'accord sur la tablette.
        if (settings.motionWake && MotionWake.hasFrontCamera(this) && !MotionWake.hasPermission(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1)
        }
        applyDisplay()
        if (step != null) show(step, quick = true)
        scheduleTick()
    }

    /** Changement demandé du doigt ou de la page web : fondu court. */
    fun userStep(step: Int) {
        show(step, quick = true)
        scheduleTick()
        // Si la photo tarde (réseau lent), un mot discret montre que le geste a été compris.
        main.removeCallbacks(waitNotice)
        main.postDelayed(waitNotice, 1800)
    }

    private val waitNotice = Runnable { if (busy && view.hasPhoto) view.flash(tr("Un instant…")) }

    /** Double toucher : la photo sous le doigt est écartée et ne reviendra plus. */
    private fun hideAt(x: Float) {
        if (busy) return
        if (locked) { view.flash(tr("Cadre verrouillé : écarter une photo se fait depuis les réglages.")); return }
        val photo = photoAt(x) ?: return
        app.library.hide(photo.id)
        view.flash(tr("Photo écartée : elle ne reviendra plus. Pour annuler : page web de réglages, onglet Photos."))
        userStep(1)
    }

    /** Fin d'un affichage : on enchaîne sur la demande arrivée entre-temps, s'il y en a une. */
    private fun done() {
        busy = false
        pending?.let { pending = null; show(it, quick = true) }
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        // Sans photo à l'écran (serveur injoignable au démarrage), on réessaie plus vite.
        val delay = if (view.hasPhoto) settings.intervalMinutes.coerceAtLeast(1) * 60_000L else 15_000L
        main.postDelayed(tick, delay)
    }

    /**
     * Affiche une autre page. [step] = +1 : la suivante (une nouvelle, sauf si on était revenu en
     * arrière) ; -1 : la précédente ; 0 : la même, redessinée. [quick] : fondu court.
     */
    private fun show(step: Int, quick: Boolean = false) {
        if (busy) { // un pas demandé l'emporte sur un simple « redessiner »
            if (step != 0 || pending == null) pending = step
            return
        }
        val w = view.width
        val h = view.height
        if (w == 0 || h == 0) return
        if (!settings.configured) {
            val url = app.web.url()
            view.showNotice(tr("Appuyez longuement sur l'écran pour ouvrir les réglages et relier le cadre à Immich.") +
                (if (url != null) "\n" + tr("Ou ouvrez cette adresse depuis un ordinateur :") + " " + url else ""))
            return
        }
        busy = true
        val withCaption = settings.caption
        val dark = settings.matDark
        val margin = settings.marginVmin
        val strength = settings.printStrength.coerceIn(0, 100) / 100f
        io.execute {
            val started = System.currentTimeMillis()
            try {
                val page = app.library.page(step)
                if (page == null) { // déjà à la première photo vue
                    main.post { done() }
                    return@execute
                }
                val r = renderer?.takeIf { it.w == w && it.h == h && it.dark == dark && it.marginVmin == margin && it.strength == strength }
                    ?: PrintRenderer(w, h, dark, margin, strength).also { renderer = it }
                val captions = page.photos.map { if (withCaption) it.caption() else "" }
                val look = Looks.settle(settings.look(page.photos[0].id), settings.frameList, page.photos.size)
                val frame = r.compose(page.bitmaps, captions, look)
                val ground = r.ground(look.frame)
                page.bitmaps.forEach { it.recycle() }
                val label = page.photos.map { it.caption() }.filter { it.isNotEmpty() }.joinToString(" | ")
                // Pour la page web et Home Assistant : la même image, réduite, en JPEG.
                val jpeg = ByteArrayOutputStream().also { out ->
                    val small = Bitmap.createScaledBitmap(frame, 960, 960 * h / w, true)
                    small.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    small.recycle()
                }.toByteArray()
                val took = (System.currentTimeMillis() - started) / 1000
                if (took >= SLOW_SECONDS) app.events.add(tr("Photo lente à arriver : %d s").format(took))
                if (page.offline) app.events.add(tr("Immich injoignable : photos de la réserve"))
                main.post {
                    view.show(frame, ground, if (quick) FrameView.QUICK_FADE_MS else FrameView.FADE_MS) { done() }
                    app.photoShown(label, jpeg)
                    if (page.offline) view.flash(tr("Immich est injoignable : photos gardées sur la tablette."))
                }
            } catch (e: Throwable) {
                val msg = (e as? ImmichException)?.message ?: (tr("Erreur") + " : ${e.message ?: e.javaClass.simpleName}")
                app.events.add(msg)
                main.post {
                    view.showNotice(msg)
                    busy = false
                    pending = null
                }
            }
        }
    }

    private companion object {
        const val SLOW_SECONDS = 3
    }
}
