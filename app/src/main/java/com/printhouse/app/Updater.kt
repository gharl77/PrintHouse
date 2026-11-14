package com.printhouse.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import java.io.File

/**
 * Mise à jour par Wi-Fi : la page web envoie un APK, l'app le vérifie et le confie à Android,
 * qui demande toujours une confirmation sur l'écran de la tablette.
 */
class Updater(private val app: App) {
    @Volatile var status = ""
        private set
    /** Dernière version publiée dans le dépôt GitHub des réglages ("" = inconnue). */
    @Volatile var latest = ""
        private set
    @Volatile private var latestUrl: String? = null
    val available get() = latest.isNotEmpty() && compare(latest, app.version) > 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Vérifie une fois par jour (et peu après le démarrage) si une version plus récente est publiée. */
    fun startPeriodicCheck() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (app.settings.updateRepo.isNotEmpty()) Thread({ check() }, "update-check").start()
                main.postDelayed(this, 24 * 3_600_000L)
            }
        }, 90_000L)
    }

    private fun http(url: String): java.net.HttpURLConnection =
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "PrintHouse")
        }

    /** Lit la dernière version publiée. Bloquant. Renvoie un message d'erreur, ou null. */
    fun check(): String? {
        val repo = app.settings.updateRepo
        if (!REPO.matches(repo)) { status = tr("Indiquez le dépôt GitHub sous la forme compte/depot."); return status }
        return try {
            val c = http("https://api.github.com/repos/$repo/releases/latest")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            when (val code = c.responseCode) {
                200 -> {
                    val o = org.json.JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                    val assets = o.optJSONArray("assets")
                    latest = o.optString("tag_name").removePrefix("v")
                    latestUrl = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it).optString("browser_download_url") }
                        .firstOrNull { it.endsWith(".apk") }
                    status = if (available) tr("Une nouvelle version est disponible.") else tr("PrintHouse est à jour.")
                    null
                }
                404 -> { latest = ""; status = tr("Aucune version publiée dans ce dépôt."); status }
                else -> { status = tr("GitHub a répondu HTTP %d.").format(code); status }
            }
        } catch (e: Exception) {
            status = tr("GitHub injoignable.") + " (${e.message ?: e.javaClass.simpleName})"
            status
        }
    }

    /** Télécharge puis installe la dernière version publiée. Bloquant. Renvoie un message d'erreur, ou null. */
    fun installLatest(): String? {
        if (!available) check()?.let { return it }
        val url = latestUrl
        if (!available || url == null) { status = tr("Aucune version plus récente avec un fichier APK."); return status }
        return try {
            status = tr("Téléchargement…")
            val file = File(app.cacheDir, "upload.apk")
            http(url).inputStream.use { inp -> file.outputStream().use { inp.copyTo(it) } }
            installFile(file)
        } catch (e: Exception) {
            status = tr("Téléchargement impossible.") + " (${e.message ?: e.javaClass.simpleName})"
            status
        }
    }

    /** Android 8+ : il faut autoriser une fois l'app à installer des applications. */
    private fun canInstall(): Boolean {
        if (app.packageManager.canRequestPackageInstalls()) return true
        status = tr("Sur la tablette, autorisez PrintHouse à installer des applications, puis renvoyez le fichier.")
        try {
            app.startActivity(Intent(SystemSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
        return false
    }

    /** Vérifie l'APK (même application) puis lance l'installation. Renvoie un message d'erreur, ou null. */
    fun installFile(file: File): String? {
        val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
        val err = when {
            info == null -> tr("Ce fichier n'est pas un APK valide.")
            info.packageName != app.packageName -> "Cet APK n'est pas PrintHouse (${info.packageName})."
            else -> null
        }
        if (err != null) { file.delete(); status = err; return err }
        if (!canInstall()) { file.delete(); return status }
        status = "Installation de la version ${info!!.versionName}…"
        try {
            val pi = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(app.packageName) }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                file.inputStream().use { inp -> s.openWrite("printhouse.apk", 0, file.length()).use { out -> inp.copyTo(out); s.fsync(out) } }
                // Intention explicite : Android 8+ ne livre pas les diffusions implicites aux récepteurs du manifeste.
                val intent = Intent(app, UpdateReceiver::class.java).setAction(ACTION_RESULT)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            status = "Échec de la mise à jour : ${e.message}"
            return status
        } finally {
            file.delete()
        }
        return null
    }

    /** Résultat renvoyé par Android. */
    fun onResult(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                status = tr("Confirmez l'installation sur l'écran de la tablette.")
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let {
                    try { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }
            }
            PackageInstaller.STATUS_SUCCESS -> status = "" // l'app redémarre d'elle-même
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                status = "Installation annulée ou refusée" + if (msg.isNotEmpty()) " ($msg)" else ""
            }
        }
    }

    companion object {
        const val ACTION_RESULT = "com.printhouse.app.INSTALL_RESULT"
        private val REPO = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")

        /** Compare deux versions « 1.2.3 » nombre par nombre. */
        fun compare(a: String, b: String): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}

/** Reçoit le résultat de l'installation (déclaré dans le manifeste, non exporté). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Updater.ACTION_RESULT) App.instance.updater.onResult(intent)
    }
}
