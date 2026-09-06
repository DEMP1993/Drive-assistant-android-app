package com.mapsbledisplay

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Wird vom Bluetooth-Stack aufgerufen, sobald der Hintergrund-Scan
 * ([BackgroundScan]) den Drive Assistant sieht - auch wenn die App gerade
 * nicht laeuft. Startet den [DeviceService], der die Verbindung aufbaut.
 */
class DeviceFoundReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DeviceFound"
        const val ACTION_FOUND = "com.mapsbledisplay.DEVICE_FOUND"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FOUND) return

        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) {
            Log.w(TAG, "Hintergrund-Scan Fehler $error")
            return
        }
        val type = intent.getIntExtra(
            BluetoothLeScanner.EXTRA_CALLBACK_TYPE, ScanSettings.CALLBACK_TYPE_ALL_MATCHES
        )
        if (type == ScanSettings.CALLBACK_TYPE_MATCH_LOST) return

        @Suppress("DEPRECATION")
        val results: List<ScanResult> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableArrayListExtra(
                    BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java
                ) ?: emptyList()
            else
                intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
                    ?: emptyList()

        val address = results.firstOrNull()?.device?.address ?: return
        Log.i(TAG, "Drive Assistant gesehen ($address) -> verbinde")
        DeviceService.start(context, address)
    }
}
