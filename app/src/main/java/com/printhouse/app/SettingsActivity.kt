package com.printhouse.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

/** Réglages : serveur Immich, photos à afficher, rythme, légende, nuit. */
class SettingsActivity : Activity() {
    private lateinit var settings: Settings
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var status: TextView
    private lateinit var source: Spinner
    private lateinit var interval: Spinner
    private lateinit var caption: Switch
    private lateinit var night: Switch
    private lateinit var nightFrom: EditText
    private lateinit var nightTo: EditText

    /** (nom affiché, valeur) des sources proposées. */
    private var sources: List<Pair<String, String>> = emptyList()
    private val intervals = listOf(1, 2, 5, 10, 15, 30, 60)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = (application as App).settings

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(32))
        }
        fun add(v: View, top: Int = 0) = col.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) })
        fun label(text: String, top: Int = 20) = add(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(getColor(R.color.pencil))
        }, top)
        fun field(value: String, hint: String, type: Int) = EditText(this).apply {
            setText(value)
            this.hint = hint
            inputType = type
            setSingleLine()
        }

        add(TextView(this).apply {
            text = getString(R.string.settings)
            textSize = 26f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            setTextColor(getColor(R.color.ink))
        })

        label(tr("Adresse du serveur Immich"))
        url = field(settings.url, "http://192.168.1.10:2283", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        add(url)

        label(tr("Clé API (Immich → Paramètres du compte → Clés API)"))
        key = field("", if (settings.key.isEmpty()) tr("Collez la clé ici") else tr("Clé enregistrée — laissez vide pour la garder"),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        add(key)

        val test = Button(this).apply { text = tr("Tester la connexion") }
        status = TextView(this).apply { textSize = 14f; setPadding(dp(16), 0, 0, 0) }
        add(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(test, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, 8)
        test.setOnClickListener { testConnection() }

        label(tr("Photos à afficher"))
        source = Spinner(this)
        add(source)
        val chosen = settings.albums
        sources = listOf(
            tr("Toute la bibliothèque") to "all",
            tr("Favoris") to "favorites",
            (if (chosen.isEmpty()) tr("Albums (à choisir sur la page web)") else tr("Albums choisis") + " : " + chosen.joinToString(", ") { it.name }) to "albums",
        )
        source.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sources.map { it.first })
        source.setSelection(sources.indexOfFirst { it.second == settings.sourceMode }.coerceAtLeast(0))

        label(tr("Changer de photo toutes les…"))
        interval = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                intervals.map { if (it == 1) tr("1 minute") else if (it == 60) tr("1 heure") else tr("%d minutes").format(it) })
            setSelection(intervals.indexOf(settings.intervalMinutes).coerceAtLeast(0))
        }
        add(interval)

        caption = Switch(this).apply { text = tr("Légende sous la photo (lieu et mois)"); textSize = 16f; isChecked = settings.caption }
        add(caption, 24)

        night = Switch(this).apply { text = tr("Éteindre l'écran la nuit"); textSize = 16f; isChecked = settings.night }
        add(night, 16)
        nightFrom = field(settings.nightFrom, "22:30", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        nightTo = field(settings.nightTo, "06:30", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        add(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@SettingsActivity).apply { text = tr("de ") ; textSize = 16f })
            addView(nightFrom, LinearLayout.LayoutParams(dp(110), WRAP_CONTENT))
            addView(TextView(this@SettingsActivity).apply { text = tr("  à  "); textSize = 16f })
            addView(nightTo, LinearLayout.LayoutParams(dp(110), WRAP_CONTENT))
        })

        add(Button(this).apply {
            text = tr("Enregistrer")
            setOnClickListener { save() }
        }, 28)

        add(TextView(this).apply {
            val page = WebServer.localIp()?.let { "\nTous les réglages (albums, personnes, apparence, luminosité, Home Assistant, mise à jour) sont sur la page web, depuis un ordinateur du même réseau : http://$it:8080" } ?: ""
            text = "Sur le cadre : toucher ou balayer l'écran change de photo, un double toucher écarte la photo, un appui long ouvre ces réglages.$page"
            setTextIsSelectable(true)
            textSize = 13f
            setTextColor(getColor(R.color.pencil))
        }, 16)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    /** Le serveur tel que saisi (la clé enregistrée sert si le champ est laissé vide). */
    private fun typedServer() = Immich.Server(Settings.cleanUrl(url.text.toString()), key.text.toString().trim().ifEmpty { settings.key })

    private fun testConnection() {
        val server = typedServer()
        status.setTextColor(getColor(R.color.pencil))
        status.text = tr("Connexion…")
        io.execute {
            val result = try { Result.success(Immich.albums(server)) } catch (e: Exception) { Result.failure(e) }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess {
                    status.setTextColor(0xFF3F6B45.toInt())
                    status.text = "Connexion réussie — ${it.size} album(s) trouvé(s)."
                }.onFailure {
                    status.setTextColor(0xFF9A3B2E.toInt())
                    status.text = (it as? ImmichException)?.message ?: "Erreur : ${it.message}"
                }
            }
        }
    }

    private fun save() {
        if (Settings.minutes(nightFrom.text.toString()) == null || Settings.minutes(nightTo.text.toString()) == null) {
            status.setTextColor(0xFF9A3B2E.toInt())
            status.text = tr("Heures de nuit : écrivez-les comme 22:30.")
            return
        }
        settings.url = url.text.toString()
        key.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.key = it }
        sources.getOrNull(source.selectedItemPosition)?.let { settings.sourceMode = it.second }
        settings.intervalMinutes = intervals[interval.selectedItemPosition.coerceIn(0, intervals.lastIndex)]
        settings.caption = caption.isChecked
        settings.night = night.isChecked
        settings.nightFrom = nightFrom.text.toString().trim()
        settings.nightTo = nightTo.text.toString().trim()
        (application as App).settingsChanged(1)
        finish()
    }
}
