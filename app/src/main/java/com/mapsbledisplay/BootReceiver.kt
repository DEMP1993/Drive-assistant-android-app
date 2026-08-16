package com.mapsbledisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Startet den KeepAliveService nach einem Neustart des Handys. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            KeepAliveService.start(context)
        }
    }
}
