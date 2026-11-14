package com.printhouse.app

import android.app.Application
import android.content.Context
import android.content.Intent
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
        val f = frame
        f?.applyDisplay()
        if (!screenOff && !getSystemService(PowerManager::class.java).isInteractive && !settings.isNightNow()) {
            WakeReceiver.wake(this)
            if (f == null) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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
        lateinit var instance: App
            private set
    }
}
