package com.mapsbledisplay

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * "Eingeschraenkte Einstellungen" (ab Android 13): Wurde die App ueber den
 * Browser oder einen Dateimanager installiert, sperrt Android den
 * Benachrichtigungszugriff, bis der Nutzer in der App-Info oben rechts im
 * Menue "Eingeschraenkte Einstellungen zulassen" waehlt. Dieser Menuepunkt
 * erscheint erst, nachdem man einmal versucht hat, den Zugriff zu erteilen.
 * Direkt dorthin springen kann keine App - wir fuehren so nah wie moeglich hin.
 */
object RestrictedSettings {

    private const val TAG = "RestrictedSettings"
    private const val OP = "android:access_restricted_settings"
    private const val PREFS = "setup"
    private const val KEY_TRIED = "notif_access_tried"

    /** true, solange Android den Benachrichtigungszugriff fuer diese Installation sperrt. */
    fun isRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val source = try {
            context.packageManager.getInstallSourceInfo(context.packageName).packageSource
        } catch (e: Exception) {
            return false
        }
        if (source != PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE &&
            source != PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
        ) return false
        return try {
            val ops = context.getSystemService(AppOpsManager::class.java)
            ops.unsafeCheckOpNoThrow(OP, Process.myUid(), context.packageName) !=
                AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            // Op unbekannt: sicherheitshalber Anleitung zeigen (App kam aus dem Browser)
            Log.w(TAG, "Status nicht lesbar: ${e.message}")
            true
        }
    }

    /** Hat der Nutzer schon einmal versucht, den Zugriff zu erteilen? */
    fun tried(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TRIED, false)

    /**
     * Oeffnet direkt den Schalter fuer unseren Listener (Rueckfall: Liste aller
     * Apps mit Benachrichtigungszugriff) und merkt sich den Versuch.
     */
    fun openNotificationAccess(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TRIED, true).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, MapsNotificationListenerService::class.java).flattenToString()
            )
            try {
                context.startActivity(detail)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Detailseite nicht verfuegbar: ${e.message}")
            }
        }
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** App-Info oeffnen - dort oben rechts im Menue liegt die Freigabe. */
    fun openAppInfo(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    }
}
