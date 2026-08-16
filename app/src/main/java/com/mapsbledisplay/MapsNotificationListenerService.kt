package com.mapsbledisplay

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Faengt die laufende Google-Maps-Navigations-Benachrichtigung ab, parst sie
 * mit [NavParser] und schickt das Ergebnis ueber [BleManager] an den ESP32.
 *
 * Zusaetzlich wird das Manoever-Bild (largeIcon) der Benachrichtigung als
 * Monochrom-Bitmap uebertragen - Maps liefert die Abbiegerichtung nur als
 * Bild, nicht als Text (siehe PROTOCOL.md, Icon-Charakteristik).
 *
 * Voraussetzung: Der Nutzer muss der App in den Android-Einstellungen
 * "Benachrichtigungszugriff" erteilen (siehe MainActivity).
 */
class MapsNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MapsListener"
        private const val MAPS_PKG = "com.google.android.apps.maps"
        /** Auf true setzen, um die rohen Benachrichtigungsfelder zu loggen. */
        const val DEBUG_DUMP = true
        /** Kantenlaenge der Monochrom-Bitmap, die zum ESP32 geschickt wird. */
        private const val ICON_SIZE = 40

        /**
         * Entprellzeit fuer "Navigation beendet": Maps entfernt seine
         * Benachrichtigung auch WAEHREND aktiver Navigation staendig und
         * postet sie sofort neu. Erst wenn sie so lange am Stueck weg ist,
         * gilt die Navigation wirklich als beendet.
         */
        private const val NAV_END_DELAY_MS = 15_000L
        /** Payload fuer den "Navigation beendet"-Screen (siehe PROTOCOL.md). */
        private const val END_PAYLOAD = "end||"

        /**
         * true, solange das System den Listener tatsaechlich gebunden hat.
         * Wichtig fuer die UI: Auf MIUI kann der Zugriff "erteilt" sein,
         * ohne dass der Dienst laeuft - dann kommt nichts von Maps an.
         */
        val connected = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    // Nur echte Aenderungen senden - Maps postet oft identische Updates.
    private var lastSentPayload: String? = null
    private var lastIconHash = 0

    // Entprellter "Navigation beendet"-Melder (siehe onNotificationRemoved)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val navEndRunnable = Runnable {
        if (lastSentPayload != null && lastSentPayload != END_PAYLOAD) {
            Log.i(TAG, "Maps-Notification ${NAV_END_DELAY_MS / 1000}s weg -> Navigation beendet")
            lastSentPayload = END_PAYLOAD
            lastIconHash = 0
            BleManager.sendRaw(END_PAYLOAD)
            BleManager.sendIcon(byteArrayOf('I'.code.toByte(), 0, 0)) // Icon verwerfen
        }
    }

    override fun onCreate() {
        super.onCreate()
        BleManager.init(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != MAPS_PKG) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        if (DEBUG_DUMP) {
            Log.d(TAG, "----- Maps-Notification -----")
            Log.d(TAG, "title   = $title")
            Log.d(TAG, "text    = $text")
            Log.d(TAG, "subText = $sub")
            Log.d(TAG, "bigText = $bigText")
        }

        val nav = NavParser.parse(title, text ?: bigText, sub)
        if (nav.isEmpty()) return

        // Navigation laeuft (wieder) -> geplantes "Navigation beendet" verwerfen
        mainHandler.removeCallbacks(navEndRunnable)

        val payload = nav.toPayload()
        if (payload != lastSentPayload) {
            lastSentPayload = payload
            Log.i(TAG, "Sende -> $payload")
            BleManager.send(nav)
        }
        sendManeuverIcon(sbn)
    }

    /**
     * Rastert das Manoever-Bild der Benachrichtigung auf ICON_SIZE x ICON_SIZE
     * x 1 Bit und schickt es an den ESP32 (nur bei Aenderung).
     */
    private fun sendManeuverIcon(sbn: StatusBarNotification) {
        val icon = sbn.notification.getLargeIcon()
        if (icon == null) {
            if (DEBUG_DUMP) Log.d(TAG, "kein largeIcon in der Benachrichtigung")
            return
        }
        val drawable = icon.loadDrawable(this) ?: return

        val bmp = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
        drawable.draw(Canvas(bmp))

        val bytes = ByteArray(3 + ICON_SIZE * ICON_SIZE / 8)
        bytes[0] = 'I'.code.toByte()
        bytes[1] = ICON_SIZE.toByte()
        bytes[2] = ICON_SIZE.toByte()
        var idx = 3
        var acc = 0
        var bits = 0
        for (y in 0 until ICON_SIZE) {
            for (x in 0 until ICON_SIZE) {
                val p = bmp.getPixel(x, y)
                val lum = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
                val set = Color.alpha(p) > 96 && lum > 40
                acc = (acc shl 1) or (if (set) 1 else 0)
                if (++bits == 8) {
                    bytes[idx++] = acc.toByte()
                    acc = 0
                    bits = 0
                }
            }
        }
        bmp.recycle()

        val hash = bytes.contentHashCode()
        if (hash != lastIconHash) {
            lastIconHash = hash
            Log.i(TAG, "Sende Manoever-Icon")
            BleManager.sendIcon(bytes)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Bewusst KEIN sofortiges "clear": Maps entfernt seine Benachrichtigung
        // auch waehrend aktiver Navigation staendig (v.a. im Vordergrund oder
        // im Stand ohne Positionsaenderung) und postet sie gleich wieder.
        // Stattdessen entprellt: Bleibt sie NAV_END_DELAY_MS am Stueck weg,
        // gilt die Navigation als beendet -> "end" an den ESP32 (zeigt kurz
        // "Navigation beendet", dann den Wartebildschirm).
        if (sbn?.packageName != MAPS_PKG) return
        if (DEBUG_DUMP) Log.d(TAG, "Maps-Notification entfernt (Ende-Timer laeuft)")
        if (lastSentPayload != null && lastSentPayload != END_PAYLOAD) {
            mainHandler.removeCallbacks(navEndRunnable)
            mainHandler.postDelayed(navEndRunnable, NAV_END_DELAY_MS)
        }
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "NotificationListener getrennt")
        connected.value = false
    }

    override fun onListenerConnected() {
        Log.i(TAG, "NotificationListener verbunden")
        connected.value = true
        // Falls der Prozess vom System (nur) fuer den Listener gestartet
        // wurde: Foreground-Service nachziehen, sonst killt MIUI ihn wieder.
        KeepAliveService.start(this)
        // Media-Sessions beobachten (braucht denselben Benachrichtigungs-
        // zugriff, der hier gerade nachweislich aktiv ist).
        MediaWatcher.start(this)
        // Bereits aktive Maps-Benachrichtigung sofort verarbeiten: Im Stand
        // postet Maps u.U. lange kein neues Update - ohne das hier bliebe das
        // Display nach einem Rebind leer, bis sich die Position aendert.
        try {
            activeNotifications
                ?.filter { it.packageName == MAPS_PKG }
                ?.forEach { onNotificationPosted(it) }
        } catch (e: Exception) {
            Log.w(TAG, "activeNotifications nicht lesbar: ${e.message}")
        }
    }
}
