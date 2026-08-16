package com.mapsbledisplay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Haelt den App-Prozess dauerhaft am Leben (Foreground-Service mit stiller
 * Dauer-Benachrichtigung). Hintergrund: MIUI killt den Prozess sonst nach
 * einiger Zeit und bindet den NotificationListener danach NICHT neu - das
 * Display bleibt dann leer, obwohl alles korrekt eingerichtet ist.
 *
 * Ein Watchdog fordert zusaetzlich alle 5 Minuten einen Listener-Rebind an
 * (No-Op, wenn der Listener bereits gebunden ist).
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIF_ID = 1
        private const val REBIND_INTERVAL_MS = 5 * 60_000L

        /** Startet den Service; faengt Background-Start-Verbote (API 31+) ab. */
        fun start(context: Context) {
            // FGS-Typ connectedDevice verlangt ab Android 14 eine bereits
            // ERTEILTE Bluetooth-Berechtigung - ohne sie wuerde
            // startForeground crashen (SecurityException beim ersten Start).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.BLUETOOTH_CONNECT
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Bluetooth-Berechtigung fehlt - Start uebersprungen")
                return
            }
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, KeepAliveService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Start nicht moeglich: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val rebindWatchdog = object : Runnable {
        override fun run() {
            NotificationListenerService.requestRebind(
                ComponentName(this@KeepAliveService, MapsNotificationListenerService::class.java)
            )
            handler.postDelayed(this, REBIND_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        BleManager.init(applicationContext)
        createChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notif,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            )
        } catch (e: Exception) {
            // Letzte Verteidigungslinie: lieber kein KeepAlive als App-Crash
            Log.w(TAG, "startForeground fehlgeschlagen: ${e.message}")
            stopSelf()
            return
        }
        handler.postDelayed(rebindWatchdog, REBIND_INTERVAL_MS)
        Log.i(TAG, "Foreground-Service gestartet")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY // nach einem Kill vom System neu starten lassen

    override fun onDestroy() {
        handler.removeCallbacks(rebindWatchdog)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keepalive_channel),
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
        )
    }
}
