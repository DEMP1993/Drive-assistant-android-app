package com.mapsbledisplay

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Wird vom System gebunden (und startet dafuer notfalls den App-Prozess),
 * sobald der gekoppelte Drive Assistant in Reichweite auftaucht - also wenn
 * er eingeschaltet wird oder nach einem Verbindungsabbruch wieder advertised.
 * Siehe [CompanionPairing]. Ersetzt auf Android 12+ den eigenen
 * Hintergrund-Scan als Weckquelle; der bleibt als Rueckfallebene bestehen.
 */
@RequiresApi(Build.VERSION_CODES.S)
class DevicePresenceService : CompanionDeviceService() {

    companion object {
        private const val TAG = "DevicePresence"
    }

    // Android 12
    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) appeared(address)
    }

    // Android 13+
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        associationInfo.deviceMacAddress?.toString()?.let { appeared(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(address: String) {
        Log.i(TAG, "Drive Assistant nicht mehr sichtbar")
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.i(TAG, "Drive Assistant nicht mehr sichtbar")
    }

    private fun appeared(address: String) {
        val mac = address.uppercase()
        if (BackgroundScan.isPaused(this)) {
            Log.i(TAG, "Drive Assistant sichtbar ($mac), Auto-Verbinden pausiert")
            return
        }
        Log.i(TAG, "Drive Assistant sichtbar ($mac) -> verbinde")
        DeviceService.start(this, mac)
        // Prozess evtl. gerade frisch gestartet: Listener pruefen und ggf. neu binden
        ListenerRebind.healSoon(this)
    }
}
