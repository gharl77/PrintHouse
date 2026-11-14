package com.printhouse.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * Rallume l'écran et remet le cadre au premier plan : à la fin de la nuit (alarme), au démarrage
 * de la tablette et après une mise à jour de l'app.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // En pleine nuit, ou quand la pièce est vide : on lance le cadre sans allumer l'écran
        // (le capteur de présence le rallumera à l'arrivée de quelqu'un).
        val app = ctx.applicationContext as App
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> app.events.add(tr("Démarrage de la tablette"))
            Intent.ACTION_MY_PACKAGE_REPLACED -> app.events.add(tr("Mise à jour installée"))
            else -> app.events.add(tr("Fin de la nuit"))
        }
        if (!app.settings.isNightNow() && !app.screenOff) wake(ctx)
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val ACTION_MORNING = "com.printhouse.app.MORNING"

        /** Allume l'écran s'il est en veille. */
        fun wake(ctx: Context) {
            @Suppress("DEPRECATION")
            ctx.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "printhouse:wake")
                .acquire(10_000)
        }

        /** Programme (ou annule) le réveil à la prochaine heure de fin de nuit. */
        fun schedule(ctx: Context, settings: Settings) {
            val alarms = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, WakeReceiver::class.java).setAction(ACTION_MORNING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = settings.nextNightEnd()
            if (at == null) { alarms.cancel(pi); return }
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: SecurityException) {
                // Android récent sans droit aux alarmes exactes : réveil à quelques minutes près.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }
}
