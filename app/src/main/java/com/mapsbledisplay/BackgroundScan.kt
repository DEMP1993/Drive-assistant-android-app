package com.mapsbledisplay

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hintergrund-Suche nach dem Drive Assistant OHNE laufenden App-Prozess.
 *
 * Der Scan wird per PendingIntent beim Bluetooth-Stack registriert: Android
 * filtert selbst (Hardware-Filter auf die Service-UUID, Low-Power-Modus) und
 * weckt die App erst, wenn das Geraet tatsaechlich advertised - also genau
 * dann, wenn es eingeschaltet wird. Danach uebernimmt [DeviceFoundReceiver]
 * und startet den [DeviceService], der verbindet.
 *
 * Solange das Geraet aus ist, laeuft dadurch KEIN Dienst der App; die
 * Registrierung ueberlebt das Beenden des Prozesses (nicht aber Bluetooth
 * aus/an oder einen Neustart - dafuer [BootReceiver] bzw. App-Oeffnen).
 */
@SuppressLint("MissingPermission") // Berechtigungen werden vorher geprueft
object BackgroundScan {

    private const val TAG = "BackgroundScan"
    private const val REQUEST_CODE = 42
    private const val PREFS = "autoconnect"
    private const val KEY_PAUSED = "paused"

    private val _active = MutableStateFlow(false)
    /** true, solange der System-Scan (nach unserem Wissen) registriert ist. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DeviceFoundReceiver::class.java)
            .setAction(DeviceFoundReceiver.ACTION_FOUND)
        // MUTABLE: das System traegt die Scan-Ergebnisse als Extras ein.
        return PendingIntent.getBroadcast(
            context.applicationContext, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** true, wenn der Nutzer das Auto-Verbinden per "Trennen" pausiert hat. */
    fun isPaused(context: Context): Boolean =
        prefs(context.applicationContext).getBoolean(KEY_PAUSED, false)

    /**
     * Registriert den Scan, sofern der Nutzer ihn nicht pausiert hat.
     * Fuer Selbstheilung nach einem Prozess-Kill (Listener-Rebind, Dienst).
     */
    fun ensure(context: Context) {
        if (isPaused(context)) {
            Log.i(TAG, "Auto-Verbinden pausiert - Scan nicht registriert")
            return
        }
        start(context)
    }

    /** Nutzer will trennen: Scan weg und Pause merken (bis start()). */
    fun pause(context: Context) {
        prefs(context.applicationContext).edit().putBoolean(KEY_PAUSED, true).apply()
        stop(context)
    }

    /**
     * Registriert den System-Scan (idempotent: alter Eintrag wird ersetzt)
     * und hebt eine Pause auf. Bleibt auch WAEHREND der Verbindung aktiv:
     * verbunden advertised das Geraet nicht (kein Ereignis), und wird der
     * Prozess vom System gekillt (z. B. "alle Apps schliessen"), taucht das
     * Geraet nach dem Verbindungsabbruch wieder auf -> Reconnect von selbst.
     */
    fun start(context: Context) {
        val ctx = context.applicationContext
        prefs(ctx).edit().putBoolean(KEY_PAUSED, false).apply()
        if (!Permissions.hasBle(ctx)) {
            Log.w(TAG, "Bluetooth-Berechtigung fehlt - kein Hintergrund-Scan")
            return
        }
        val adapter = adapter(ctx)
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            Log.w(TAG, "Bluetooth aus - kein Hintergrund-Scan")
            _active.value = false
            return
        }
        val pi = pendingIntent(ctx)
        try { scanner.stopScan(pi) } catch (_: Exception) { }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleManager.SERVICE_UUID))
            .build()
        // FIRST_MATCH = genau ein Broadcast, wenn das Geraet auftaucht
        // (braucht Hardware-Filter). Fallback: jedes Advertising-Paket.
        val offloaded = adapter.isOffloadedFilteringSupported
        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        if (offloaded) {
            settingsBuilder
                .setCallbackType(
                    ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST
                )
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
        }
        val rc = try {
            scanner.startScan(listOf(filter), settingsBuilder.build(), pi)
        } catch (e: Exception) {
            Log.w(TAG, "startScan(PendingIntent) Fehler: ${e.message}")
            -1
        }
        _active.value = rc == 0
        Log.i(TAG, "Hintergrund-Scan registriert: rc=$rc offloaded=$offloaded")
    }

    /** Entfernt den System-Scan (Pause-Flag bleibt unberuehrt). */
    private fun stop(context: Context) {
        val ctx = context.applicationContext
        try {
            adapter(ctx)?.bluetoothLeScanner?.stopScan(pendingIntent(ctx))
        } catch (e: Exception) {
            Log.w(TAG, "stopScan: ${e.message}")
        }
        _active.value = false
        Log.i(TAG, "Hintergrund-Scan gestoppt")
    }
}
