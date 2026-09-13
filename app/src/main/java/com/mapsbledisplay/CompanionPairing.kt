package com.mapsbledisplay

import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Einmalige Kopplung ueber den Companion Device Manager (ab Android 12).
 *
 * Warum: Den eigenen Hintergrund-Scan ([BackgroundScan]) haelt z. B. HyperOS
 * schon ~40 s nach dem Verlassen der App an ("Suspended") - das Einschalten
 * des Geraets weckt die App dann nicht mehr. Nach der Kopplung sucht ANDROID
 * SELBST nach dem Drive Assistant und startet die App ueber den
 * [DevicePresenceService], sobald er auftaucht. Gekoppelte Apps bekommen
 * ausserdem Hintergrund-Ausnahmen (Akku, Vordergrund-Dienst-Start).
 *
 * Wichtig: Die Firmware advertised nur, solange sie NICHT verbunden ist. Vor
 * dem Koppeln muss eine bestehende Verbindung daher getrennt werden.
 */
object CompanionPairing {

    private const val TAG = "CompanionPairing"

    /**
     * true, solange der System-Kopplungsdialog laeuft. Auto-Verbinden ist dann
     * gesperrt - eine Verbindung wuerde das Advertising stoppen und der Dialog
     * faende das Geraet nicht.
     */
    @Volatile
    var inProgress = false
        private set

    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    private fun cdm(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    /** MAC-Adresse des gekoppelten Drive Assistant (Grossbuchstaben) oder null. */
    fun pairedAddress(context: Context): String? {
        if (!isSupported(context)) return null
        val mgr = cdm(context) ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mgr.myAssociations.firstNotNullOfOrNull { it.deviceMacAddress?.toString() }
            } else {
                @Suppress("DEPRECATION")
                mgr.associations.firstOrNull()
            }?.uppercase()
        } catch (e: Exception) {
            Log.w(TAG, "Kopplungen nicht lesbar: ${e.message}")
            null
        }
    }

    fun isPaired(context: Context): Boolean = pairedAddress(context) != null

    /**
     * Beim System anmelden, dass es auf das gekoppelte Geraet achten soll.
     * Mehrfachaufruf ist unschaedlich (App-Start, Boot, Listener-Verbindung).
     */
    fun ensureObserving(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val address = pairedAddress(context) ?: return
        try {
            @Suppress("DEPRECATION")
            cdm(context)?.startObservingDevicePresence(address)
            Log.i(TAG, "System beobachtet Drive Assistant $address")
        } catch (e: Exception) {
            Log.w(TAG, "startObservingDevicePresence: ${e.message}")
        }
    }

    /**
     * Startet die Kopplung. [onDialog] bekommt den System-Dialog (IntentSender)
     * und muss ihn per ActivityResult-Launcher anzeigen; danach [finish] aufrufen.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun start(context: Context, onDialog: (IntentSender) -> Unit, onFailed: (String) -> Unit) {
        val mgr = cdm(context) ?: return onFailed("CompanionDeviceManager fehlt")
        inProgress = true
        // Advertising freigeben: verbunden ist das Geraet fuer den Dialog unsichtbar
        if (BleManager.state.value != BleManager.State.DISCONNECTED) BleManager.disconnect()

        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(BleManager.SERVICE_UUID)).build()
            )
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()

        val callback = object : CompanionDeviceManager.Callback() {
            // Android 12: Dialog kommt hier an
            @Deprecated("Deprecated in Java")
            override fun onDeviceFound(intentSender: IntentSender) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onDialog(intentSender)
            }

            // Android 13+
            override fun onAssociationPending(intentSender: IntentSender) = onDialog(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                Log.i(TAG, "Gekoppelt: ${associationInfo.deviceMacAddress}")
            }

            override fun onFailure(error: CharSequence?) {
                Log.w(TAG, "Kopplung fehlgeschlagen: $error")
                inProgress = false
                onFailed(error?.toString() ?: "")
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mgr.associate(request, context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                mgr.associate(request, callback, null)
            }
            Log.i(TAG, "Kopplung gestartet")
        } catch (e: Exception) {
            inProgress = false
            onFailed(e.message ?: "")
        }
    }

    /** Dialog beendet (bestaetigt oder abgebrochen): Sperre aufheben, beobachten. */
    fun finish(context: Context) {
        inProgress = false
        ensureObserving(context)
    }
}
