package com.mapsbledisplay

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * Autostart-Erlaubnis auf Xiaomi/Redmi/POCO (MIUI, HyperOS).
 *
 * Ohne sie blockiert das System das Neubinden des NotificationListeners und
 * das Starten der App im Hintergrund. Die Einstellung wird bei jeder
 * Installation bzw. jedem Update zurueckgesetzt. Android selbst kennt sie
 * nicht; sie steckt im inoffiziellen AppOp 10008 und laesst sich nur per
 * Reflection lesen. Setzen kann die App sie nicht - nur hinfuehren.
 */
object XiaomiAutostart {

    private const val TAG = "XiaomiAutostart"
    private const val OP_AUTOSTART = 10008

    fun isXiaomi(): Boolean =
        listOf(Build.MANUFACTURER, Build.BRAND).any {
            it.equals("xiaomi", true) || it.equals("redmi", true) || it.equals("poco", true)
        }

    /** true = erlaubt, false = verweigert, null = unbekannt (kein Xiaomi / nicht lesbar). */
    fun isAllowed(context: Context): Boolean? {
        if (!isXiaomi()) return null
        return try {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
            )
            val mode = method.invoke(ops, OP_AUTOSTART, Process.myUid(), context.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "Autostart-Status nicht lesbar: ${e.message}")
            null
        }
    }

    /**
     * Oeffnet die Xiaomi-Autostart-Liste (dort steht der Schalter fuer jede App);
     * die App-Info ist nur Rueckfall - auf HyperOS 2 hat sie keinen Autostart-Schalter.
     */
    fun openSettings(context: Context) {
        val appInfo = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        val autostartList = Intent().setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
        for (intent in listOf(autostartList, appInfo)) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                Log.w(TAG, "Einstellungen nicht zu oeffnen: ${e.message}")
            }
        }
    }
}
