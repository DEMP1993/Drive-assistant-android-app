package com.mapsbledisplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Zentrale Berechtigungspruefung fuer BLE (Activity, Dienste, Receiver). */
object Permissions {

    /** Pflicht-Berechtigungen fuer Scan + Verbindung. */
    fun requiredBle(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasBle(context: Context): Boolean =
        requiredBle().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Android 10/11: Scan-Ergebnisse im Hintergrund gibt es nur mit
     * Hintergrund-Standort. Ab Android 12 reicht BLUETOOTH_SCAN.
     */
    fun needsBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
}
