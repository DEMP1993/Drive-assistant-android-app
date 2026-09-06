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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Vordergrund-Dienst, der NUR laeuft, solange eine Verbindung zum Drive
 * Assistant besteht oder gerade aufgebaut wird (Typ connectedDevice, stille
 * Benachrichtigung). Er wird vom Hintergrund-Scan ([DeviceFoundReceiver])
 * gestartet, sobald das Geraet eingeschaltet wird, und beendet sich von
 * selbst, wenn die Verbindung [GRACE_MS] lang verloren bleibt.
 *
 * Waehrend er laeuft:
 *  - haelt er den Prozess am Leben (sonst beendet das System ihn und der
 *    NotificationListener bleibt ungebunden -> Display bleibt leer),
 *  - fordert er den Listener-Rebind an (sofort nach Verbindung und per
 *    Watchdog alle 5 Minuten),
 *  - registriert er bei Verbindungsverlust sofort wieder den Hintergrund-
 *    Scan, damit ein Reconnect automatisch passiert.
 */
class DeviceService : Service() {

    companion object {
        private const val TAG = "DeviceService"
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIF_ID = 1
        private const val REBIND_INTERVAL_MS = 5 * 60_000L
        private const val REBIND_RETRY_MS = 15_000L
        /** So lange bleibt der Dienst nach Verbindungsverlust noch aktiv. */
        private const val GRACE_MS = 3 * 60_000L

        const val EXTRA_ADDRESS = "address"
        const val ACTION_STOP = "com.mapsbledisplay.STOP"

        /**
         * Startet den Dienst (optional mit Geraeteadresse zum Direkt-Verbinden).
         * Faengt Background-Start-Verbote und fehlende Berechtigungen ab.
         */
        fun start(context: Context, address: String? = null) {
            // FGS-Typ connectedDevice verlangt ab Android 14 eine bereits
            // ERTEILTE Bluetooth-Berechtigung - sonst crasht startForeground.
            if (!Permissions.hasBle(context)) {
                Log.w(TAG, "Bluetooth-Berechtigung fehlt - Start uebersprungen")
                return
            }
            val intent = Intent(context, DeviceService::class.java)
            if (address != null) intent.putExtra(EXTRA_ADDRESS, address)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Start nicht moeglich: ${e.message}")
            }
        }

        /** Nutzer will trennen: Verbindung kappen, Auto-Verbinden pausieren. */
        fun stop(context: Context) {
            BackgroundScan.pause(context)
            BleManager.disconnect()
            context.stopService(Intent(context, DeviceService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null
    private var foregroundOk = false

    private val rebindWatchdog = object : Runnable {
        override fun run() {
            requestRebind()
            handler.postDelayed(this, REBIND_INTERVAL_MS)
        }
    }
    private val rebindRetry = Runnable { if (!listenerBound()) requestRebind() }
    private val graceStop = Runnable {
        if (BleManager.state.value != BleManager.State.CONNECTED) {
            Log.i(TAG, "Keine Verbindung seit ${GRACE_MS / 1000}s -> Dienst beendet sich")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        BleManager.init(applicationContext)
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(R.string.notif_connecting),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            )
            foregroundOk = true
        } catch (e: Exception) {
            // Letzte Verteidigungslinie: lieber kein Dienst als App-Crash
            Log.w(TAG, "startForeground fehlgeschlagen: ${e.message}")
            stopSelf()
            return
        }
        handler.postDelayed(rebindWatchdog, REBIND_INTERVAL_MS)
        stateJob = scope.launch { BleManager.state.collect { onBleState(it) } }
        Log.i(TAG, "Dienst gestartet")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundOk) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra(EXTRA_ADDRESS)
        val st = BleManager.state.value
        if (address != null && st == BleManager.State.DISCONNECTED) {
            BleManager.connectTo(address)
        } else if (address != null) {
            Log.i(TAG, "Geraet gesehen, aber Zustand $st - ignoriert")
        }
        // Kein Neustart durch das System: der Hintergrund-Scan startet uns
        // ohnehin wieder, sobald das Geraet zu sehen ist.
        return START_NOT_STICKY
    }

    private fun onBleState(st: BleManager.State) {
        when (st) {
            BleManager.State.CONNECTED -> {
                handler.removeCallbacks(graceStop)
                // System-Scan bleibt absichtlich registriert (siehe
                // BackgroundScan.start): killt das System den Prozess, faellt
                // die Verbindung und das Geraet advertised wieder -> Reconnect.
                BackgroundScan.ensure(this)
                updateNotification(R.string.notif_connected)
                // Jetzt wird der Listener gebraucht: falls das System ihn
                // rausgeworfen hat, sofort neu binden (und einmal nachpruefen).
                if (!listenerBound()) requestRebind()
                handler.removeCallbacks(rebindRetry)
                handler.postDelayed(rebindRetry, REBIND_RETRY_MS)
            }
            BleManager.State.DISCONNECTED -> {
                updateNotification(R.string.notif_waiting)
                // Reconnect automatisch: Hintergrund-Scan wieder scharf.
                BackgroundScan.ensure(this)
                handler.removeCallbacks(graceStop)
                handler.postDelayed(graceStop, GRACE_MS)
            }
            BleManager.State.SCANNING, BleManager.State.CONNECTING -> {
                handler.removeCallbacks(graceStop)
                updateNotification(R.string.notif_connecting)
            }
        }
    }

    private fun listenerBound() = MapsNotificationListenerService.connected.value

    private fun requestRebind() {
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, MapsNotificationListenerService::class.java)
            )
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind: ${e.message}")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stateJob?.cancel()
        scope.cancel()
        Log.i(TAG, "Dienst beendet")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------- Benachrichtigung
    private fun buildNotification(textRes: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(textRes))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(textRes: Int) {
        if (!foregroundOk) return
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(textRes))
        } catch (e: Exception) {
            Log.w(TAG, "notify: ${e.message}")
        }
    }

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
