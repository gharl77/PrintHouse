package com.printhouse.app

import android.app.Application
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.net.wifi.WifiManager
import android.os.PowerManager

/** Ce qui vit aussi longtemps que l'app : réglages, photos, page web, lien Home Assistant. */
class App : Application() {
    lateinit var settings: Settings
        private set
    lateinit var library: Library
        private set
    lateinit var web: WebServer
        private set
    lateinit var ha: HaLink
        private set
    lateinit var mqtt: MqttDevice
        private set
    lateinit var updater: Updater
        private set

    /** Le cadre à l'écran, s'il existe. */
    var frame: MainActivity? = null

    /** Écran éteint parce que la pièce est vide (capteur de présence). */
    @Volatile var presenceOff = false
        private set
    /** Écran éteint à la main (interrupteur « Écran » de Home Assistant, page web). */
    @Volatile var manualOff = false
        private set
    /** Écran éteint pour l'une de ces raisons (en plus de la nuit). */
    val screenOff get() = presenceOff || manualOff

    /** Légende de ce qui est affiché, pour la page web. */
    @Volatile var shownCaption = ""
        private set

    /** L'image affichée sur le cadre (JPEG réduit), pour la page web. */
    @Volatile var shownJpeg: ByteArray? = null
        private set

    /** Batterie de la tablette, en % (-1 = inconnue), et si elle est en charge. */
    @Volatile var battery = -1
        private set
    @Volatile var charging = false
        private set

    val version: String get() = packageManager.getPackageInfo(packageName, 0).versionName ?: ""

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        I18n.init(settings)
        library = Library(this, settings)
        updater = Updater(this)
        web = WebServer(this).also { it.start() }
        ha = HaLink(this)
        mqtt = MqttDevice(this)
        ha.restart()
        mqtt.restart()
        updater.startPeriodicCheck()
        watchBattery()
        restartOnCrash()
    }

    /** Suit le niveau de batterie, pour Home Assistant (couper la charge avec une prise connectée). */
    private fun watchBattery() {
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                if (pct != battery || plugged != charging) {
                    battery = pct
                    charging = plugged
                    mqtt.publishState()
                }
            }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    /**
     * Un cadre doit tenir 24 heures sur 24 : après une erreur fatale, l'app se ferme proprement
     * et Android la relance deux secondes plus tard (au lieu de laisser « s'est arrêté » à l'écran).
     */
    private fun restartOnCrash() {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                android.util.Log.e("PrintHouse", "Erreur fatale, relance", e)
                scheduleRestart()
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    fun scheduleRestart() {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 2000, pi)
    }

    /**
     * Un réglage a changé (fil principal). [step] : null = rien à réafficher, 0 = redessiner la
     * photo actuelle (apparence), 1 = passer à une nouvelle photo (source).
     */
    fun settingsChanged(step: Int?) {
        frame?.onSettingsChanged(step)
        mqtt.publishState()
    }

    /** Photo suivante (+1) ou précédente (-1), demandée par la page web ou Home Assistant. */
    fun step(n: Int) {
        frame?.userStep(n)
    }

    fun photoShown(caption: String, jpeg: ByteArray?) {
        shownCaption = caption
        shownJpeg = jpeg
        mqtt.publishState()
        jpeg?.let { mqtt.publishImage(it) }
    }

    /** Le capteur de présence dit que la pièce est vide ([on] = false) ou occupée. */
    fun setPresenceScreen(on: Boolean) {
        presenceOff = !on
        applyScreen()
    }

    /**
     * Interrupteur « Écran » (Home Assistant, page web). Allumer l'emporte aussi sur une
     * extinction par absence : le délai sans présence repart de zéro.
     */
    fun setScreen(on: Boolean) {
        manualOff = !on
        if (on) { presenceOff = false; ha.resetAbsence() }
        applyScreen()
    }

    private fun applyScreen() {
        mqtt.publishState()
        frame?.applyDisplay()
        if (!screenOff && !getSystemService(PowerManager::class.java).isInteractive && !settings.isNightNow()) {
            WakeReceiver.wake(this)
            // Toujours ramener le cadre devant : si une autre fenêtre (branchement USB, écran
            // d'accueil) est passée au premier plan, l'écran se rendormirait sans le montrer.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** La nuit, un toucher rallume le cadre jusqu'à cette heure (millisecondes). */
    @Volatile var nightPeekUntil = 0L
        private set

    /** Le cadre doit-il être noir maintenant ? (Nuit, pièce vide ou interrupteur « Écran ».) */
    val dark get() = (settings.isNightNow() && System.currentTimeMillis() > nightPeekUntil) || screenOff

    /**
     * Quelqu'un touche le cadre alors qu'il est noir : il est là, on rallume. La nuit, pour deux
     * minutes ; sinon jusqu'à ce que le capteur constate de nouveau l'absence pendant le délai réglé.
     */
    fun peek() {
        if (settings.isNightNow()) nightPeekUntil = System.currentTimeMillis() + NIGHT_PEEK_MS
        manualOff = false
        presenceOff = false
        ha.resetAbsence()
        applyScreen()
    }

    /** À appeler quand le lien Home Assistant ou MQTT change. */
    fun refreshLocks() = updateLocks(ha.active || mqtt.active)

    /**
     * Avec Home Assistant, la tablette doit rester joignable même écran éteint, pour pouvoir
     * être rallumée : on garde le processeur et le Wi-Fi éveillés.
     */
    private fun updateLocks(keep: Boolean) {
        if (keep && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "printhouse:ha").apply { acquire() }
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "printhouse:ha").apply { acquire() }
        } else if (!keep) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock = null
        }
    }

    companion object {
        private const val NIGHT_PEEK_MS = 120_000L
        lateinit var instance: App
            private set
    }
}
