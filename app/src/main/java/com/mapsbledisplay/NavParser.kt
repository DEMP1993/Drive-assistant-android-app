package com.mapsbledisplay

/**
 * Wandelt die Textfelder einer Google-Maps-Navigations-Benachrichtigung in
 * eine [NavData] um.
 *
 * WICHTIG: Google Maps hat KEINE offizielle API dafuer. Die genauen Strings
 * unterscheiden sich je nach App-Version, Sprache und Verkehrsmittel. Dieser
 * Parser ist daher bewusst heuristisch (Schluesselwoerter + Regex) und deckt
 * Deutsch und Englisch ab. Wenn ein Manoever nicht erkannt wird, hilft der
 * Debug-Log im NotificationListenerService, die echten Strings zu sehen und
 * die Wortlisten unten zu ergaenzen.
 */
object NavParser {

    // Entfernung: z.B. "200 m", "1,2 km", "350m", "0.5 mi", "500 ft"
    private val distanceRegex =
        Regex("""(\d+(?:[.,]\d+)?)\s?(km|m|mi|ft)\b""", RegexOption.IGNORE_CASE)

    /**
     * @param title  android.title der Benachrichtigung
     * @param text   android.text
     * @param sub    android.subText (optional)
     */
    fun parse(title: String?, text: String?, sub: String?): NavData {
        val parts = listOfNotNull(title, text, sub)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val all = parts.joinToString("  ")
        if (all.isBlank()) return NavData.EMPTY

        val maneuver = detectManeuver(all)
        val distance = distanceRegex.find(all)?.value?.replace("  ", " ")?.trim().orEmpty()
        val street = extractStreet(title, text, all)

        return NavData(maneuver, distance, street, raw = all)
    }

    // --- Manoever-Erkennung -------------------------------------------------
    // Reihenfolge ist wichtig: spezial (scharf/leicht/wenden) VOR allgemein.
    private fun detectManeuver(s: String): String {
        val t = s.lowercase()

        // Wenden / U-Turn
        if (t.contains("wenden") || t.contains("u-turn") || t.contains("u turn"))
            return "uturn"

        // Kreisverkehr
        if (t.contains("kreisverkehr") || t.contains("roundabout") || t.contains("rotary"))
            return "roundabout"

        // Ziel erreicht
        if (t.contains("ziel") || t.contains("angekommen") ||
            t.contains("arrive") || t.contains("destination"))
            return "arrive"

        // Auffahren / Einfaedeln
        if (t.contains("auffahr") || t.contains("einfaedel") || t.contains("einfädel") ||
            t.contains("merge"))
            return "merge"

        // scharf
        if (t.contains("scharf rechts") || t.contains("sharp right")) return "sharp-right"
        if (t.contains("scharf links") || t.contains("sharp left")) return "sharp-left"

        // leicht / halten Sie sich / keep
        val slightRight = t.contains("leicht rechts") || t.contains("slight right") ||
                t.contains("halten sie sich rechts") || t.contains("keep right") ||
                t.contains("rechts halten")
        val slightLeft = t.contains("leicht links") || t.contains("slight left") ||
                t.contains("halten sie sich links") || t.contains("keep left") ||
                t.contains("links halten")
        if (slightRight) return "slight-right"
        if (slightLeft) return "slight-left"

        // normal abbiegen
        if (t.contains("rechts abbiegen") || t.contains("turn right") ||
            t.contains("nach rechts") || t.contains("right onto") || t.contains("right on"))
            return "turn-right"
        if (t.contains("links abbiegen") || t.contains("turn left") ||
            t.contains("nach links") || t.contains("left onto") || t.contains("left on"))
            return "turn-left"

        // geradeaus / weiter
        if (t.contains("geradeaus") || t.contains("straight") ||
            t.contains("weiter") || t.contains("continue") || t.contains("head"))
            return "straight"

        // Fallback: nur "rechts"/"links" als Richtungswort vorhanden?
        if (Regex("""\brechts\b|\bright\b""").containsMatchIn(t)) return "turn-right"
        if (Regex("""\blinks\b|\bleft\b""").containsMatchIn(t)) return "turn-left"

        return "unknown"
    }

    // --- Strassenname-Erkennung --------------------------------------------
    // Maps formuliert oft "... auf <Strasse>" / "... onto <Street>".
    private val streetMarkers = listOf(
        " auf ", " onto ", " on ", " Richtung ", " richtung ", " toward ", " towards "
    )

    private fun extractStreet(title: String?, text: String?, all: String): String {
        for (src in listOfNotNull(title, text)) {
            for (m in streetMarkers) {
                val idx = src.indexOf(m, ignoreCase = true)
                if (idx >= 0) {
                    val street = src.substring(idx + m.length).trim()
                    if (street.isNotEmpty()) return cleanStreet(street)
                }
            }
        }
        // Fallback: 'text' ohne Entfernungsangabe, falls es nach Strasse aussieht
        val cand = text?.let { distanceRegex.replace(it, "").trim() }.orEmpty()
        return cleanStreet(cand)
    }

    private fun cleanStreet(s: String): String {
        // Bindestrich-/Punkt-Reste am Ende und doppelte Leerzeichen entfernen
        return s.replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', '·', '•', ',', '.')
            .take(60)
    }
}
