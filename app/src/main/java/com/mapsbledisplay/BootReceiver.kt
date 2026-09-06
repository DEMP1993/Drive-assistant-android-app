package com.mapsbledisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Nach einem Neustart des Handys den Hintergrund-Scan wieder registrieren
 * (der Bluetooth-Stack vergisst ihn beim Booten). Es laeuft danach KEIN
 * Dienst - der startet erst, wenn der Drive Assistant eingeschaltet wird.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            BackgroundScan.start(context)
        }
    }
}
