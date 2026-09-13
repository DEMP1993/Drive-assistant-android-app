package com.mapsbledisplay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Bindet den NotificationListener neu an, ohne dass der Nutzer den Zugriff in
 * den Einstellungen aus- und wieder einschalten muss.
 *
 * Hintergrund: Wird der App-Prozess beendet ("alle Apps schliessen", Update,
 * Aufraeumen durch das System), versucht Android genau einmal, den Listener
 * neu zu binden. Blockiert das System das in dem Moment (z. B. HyperOS ohne
 * Autostart), gibt Android auf. [NotificationListenerService.requestRebind]
 * hilft dann NICHT - es wirkt nur nach einem eigenen requestUnbind.
 *
 * Neu gebunden wird erst bei einer Paketaenderung. Die App darf ihre eigene
 * Listener-Komponente aus- und wieder einschalten: das ist so eine Aenderung,
 * Android bindet daraufhin sofort neu - wie beim manuellen Aus/Ein.
 */
object ListenerRebind {

    private const val TAG = "ListenerRebind"
    /** Kurz warten: frisch gestartet bindet Android den Listener oft von selbst. */
    private const val CHECK_DELAY_MS = 3_000L
    /** Nicht oefter umschalten - jedes Umschalten trennt einen laufenden Listener. */
    private const val MIN_INTERVAL_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private var lastToggle = 0L

    private fun accessGranted(context: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Prueft nach kurzer Wartezeit und bindet neu, falls der Listener fehlt. */
    fun healSoon(context: Context) {
        val app = context.applicationContext
        handler.postDelayed({ heal(app) }, CHECK_DELAY_MS)
    }

    /** Sofort pruefen und bei Bedarf neu binden. */
    fun heal(context: Context) {
        val ctx = context.applicationContext
        if (!accessGranted(ctx) || MapsNotificationListenerService.connected.value) return
        val now = SystemClock.elapsedRealtime()
        if (lastToggle != 0L && now - lastToggle < MIN_INTERVAL_MS) return
        lastToggle = now

        val component = ComponentName(ctx, MapsNotificationListenerService::class.java)
        try {
            val pm = ctx.packageManager
            pm.setComponentEnabledSetting(
                component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP
            )
            NotificationListenerService.requestRebind(component)
            Log.i(TAG, "Listener war nicht gebunden -> Komponente umgeschaltet")
        } catch (e: Exception) {
            Log.w(TAG, "Neu binden fehlgeschlagen: ${e.message}")
        }
    }
}
