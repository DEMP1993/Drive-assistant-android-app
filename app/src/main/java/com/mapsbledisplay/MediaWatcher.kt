package com.mapsbledisplay

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log

/**
 * Beobachtet die aktiven Media-Sessions des Systems (Spotify, YouTube Music,
 * Radio-Apps ...) und spiegelt Titel/Interpret/Wiedergabestatus auf den
 * Drive Assistant. Tasten-Kommandos vom Display (Play/Pause, weiter, zurueck)
 * werden hier auf die passende Session ausgefuehrt.
 *
 * Voraussetzung: Benachrichtigungszugriff (dieselbe Berechtigung wie fuer den
 * Maps-Listener) - MediaSessionManager.getActiveSessions verlangt sie und
 * bekommt dafuer die Komponente unseres NotificationListenerService.
 *
 * BLE-Format (Media-Charakteristik, siehe PROTOCOL.md):
 *   <state>|<title>|<artist>     state: play / pause / none
 */
object MediaWatcher {

    private const val TAG = "MediaWatcher"

    private var sessionManager: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null
    private var controllers: List<MediaController> = emptyList()
    private var lastSent: String? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onSessionDestroyed() = rebind()
    }

    /** Ab jetzt Media-Sessions beobachten. Mehrfachaufruf ist ok. */
    fun start(context: Context) {
        if (sessionManager == null) {
            sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                    as MediaSessionManager
            listenerComponent = ComponentName(
                context.applicationContext,
                MapsNotificationListenerService::class.java
            )
            try {
                sessionManager?.addOnActiveSessionsChangedListener(
                    { _ -> rebind() }, listenerComponent
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Kein Zugriff auf Media-Sessions: ${e.message}")
                sessionManager = null
                return
            }
        }
        rebind()
    }

    /** Session-Liste neu einlesen und Callbacks umhaengen. */
    private fun rebind() {
        val mgr = sessionManager ?: return
        try {
            controllers.forEach { it.unregisterCallback(controllerCallback) }
            controllers = mgr.getActiveSessions(listenerComponent).orEmpty()
            controllers.forEach { it.registerCallback(controllerCallback) }
            Log.i(TAG, "Aktive Media-Sessions: ${controllers.map { it.packageName }}")
        } catch (e: SecurityException) {
            Log.w(TAG, "getActiveSessions verweigert: ${e.message}")
            controllers = emptyList()
        }
        push()
    }

    /** Die relevanteste Session: bevorzugt die, die gerade spielt. */
    private fun pick(): MediaController? =
        controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull { it.metadata != null }

    /** Aktuellen Stand (dedupliziert) an den ESP32 schicken. */
    private fun push() {
        val c = pick()
        val payload = if (c == null) {
            "none||"
        } else {
            val md = c.metadata
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            // Spotify haengt z.T. "• Fuer dich empfohlen" o.ae. an den
            // Interpreten - alles ab dem Bullet gehoert nicht aufs Display.
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                .substringBefore('•').substringBefore('·').trim()
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            val st = if (playing) "play" else "pause"
            // '|' ist Feldtrenner im Protokoll -> aus den Texten heraushalten
            "$st|${title.toDisplayAscii().replace('|', '/')}" +
                "|${artist.toDisplayAscii().replace('|', '/')}"
        }
        if (payload != lastSent) {
            lastSent = payload
            Log.i(TAG, "Sende Media -> $payload")
            BleManager.sendMedia(payload)
        }
    }

    /** Tasten-Kommando vom Display ausfuehren ('P' Play/Pause, 'N', 'V'). */
    fun handleCommand(cmd: Byte) {
        val c = pick()
        if (c == null) {
            Log.w(TAG, "Kommando '${cmd.toInt().toChar()}' - keine Media-Session")
            return
        }
        val tc = c.transportControls
        when (cmd.toInt().toChar()) {
            'P' -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING)
                       tc.pause() else tc.play()
            'N' -> tc.skipToNext()
            'V' -> tc.skipToPrevious()
            else -> Log.w(TAG, "Unbekanntes Kommando: $cmd")
        }
        Log.i(TAG, "Kommando '${cmd.toInt().toChar()}' -> ${c.packageName}")
    }
}
