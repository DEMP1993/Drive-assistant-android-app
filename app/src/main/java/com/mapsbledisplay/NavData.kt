package com.mapsbledisplay

/**
 * Eine geparste Navigations-Anweisung aus der Google-Maps-Benachrichtigung.
 *
 * [maneuver] ist eines der Token aus PROTOCOL.md (z.B. "turn-right").
 * [distance] ist der bereits formatierte Text (z.B. "200 m").
 * [street]   ist der Name der naechsten Strasse (kann leer sein).
 * [raw]      ist der zusammengesetzte Originaltext (nur fuer Debug/Anzeige).
 */
data class NavData(
    val maneuver: String,
    val distance: String,
    val street: String,
    val raw: String = ""
) {
    /**
     * Serialisiert in das BLE-Format:  maneuver|distance|street
     * Distanz und Strasse werden ASCII-gesaeubert, weil der Display-Font des
     * ESP32 nur ASCII kann (Maps nutzt z.B. U+202F zwischen Zahl und "m").
     */
    fun toPayload(): String =
        "$maneuver|${distance.toDisplayAscii()}|${street.toDisplayAscii()}"

    fun isEmpty(): Boolean =
        maneuver.isBlank() && distance.isBlank() && street.isBlank()

    companion object {
        val EMPTY = NavData("clear", "", "")
    }
}

/** Ersetzt/entfernt alles, was der ASCII-Font des Displays nicht darstellen kann. */
internal fun String.toDisplayAscii(): String {
    val sb = StringBuilder(length)
    for (ch in this) {
        when (ch) {
            'ä' -> sb.append("ae")
            'ö' -> sb.append("oe")
            'ü' -> sb.append("ue")
            'Ä' -> sb.append("Ae")
            'Ö' -> sb.append("Oe")
            'Ü' -> sb.append("Ue")
            'ß' -> sb.append("ss")
            'é', 'è', 'ê' -> sb.append('e')
            'á', 'à', 'â' -> sb.append('a')
            // Unicode-Leerzeichen (NBSP, schmales NBSP von Maps, EN/EM/THIN SPACE)
            ' ', ' ', ' ', ' ', ' ', ' ', ' ' -> sb.append(' ')
            '–', '—' -> sb.append('-')   // Gedankenstriche
            '·', '•' -> sb.append('.')   // Mittelpunkt/Bullet
            else -> if (ch.code in 32..126) sb.append(ch)  // Rest weglassen
        }
    }
    return sb.toString().trim()
}
