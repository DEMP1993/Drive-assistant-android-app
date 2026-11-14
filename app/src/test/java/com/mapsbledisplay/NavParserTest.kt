package com.mapsbledisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Echte bzw. typische Texte der Google-Maps-Navigations-Benachrichtigung.
 * NEU = Maps 26.46 (Live-Update, Nov. 2026): alles im Titel, text = null.
 * ALT = klassisches Format: Entfernung im Titel, Anweisung im Text.
 */
class NavParserTest {

    private fun p(title: String?, text: String?, sub: String? = null) =
        NavParser.parse(title, text, sub)

    // --- NEU (Maps 26.46) ---------------------------------------------------

    @Test fun neu_entfernungUndAnweisungImTitel() {
        val n = p("230 m · Links abbiegen auf Paderborner Str.", null, "Ankunft um 15:21")
        assertEquals("turn-left", n.maneuver)
        assertEquals("230 m", n.distance)
        assertEquals("Paderborner Str", n.street)
    }

    @Test fun neu_richtungMitVerbAmEnde() {
        val n = p("60 m · Links Richtung Paderborner Str. abbiegen", null, "Ankunft um 15:21")
        assertEquals("turn-left", n.maneuver)
        assertEquals("60 m", n.distance)
        assertEquals("Paderborner Str", n.street)
    }

    @Test fun neu_nurRichtung() {
        val n = p("Richtung Dreibirkenweg", null, "Ankunft um 15:21")
        assertEquals("straight", n.maneuver)
        assertEquals("", n.distance)
        assertEquals("Dreibirkenweg", n.street)
    }

    @Test fun neu_himmelsrichtung() {
        val n = p("Auf Günter-Porsiel-Str. nach Nordosten", null, "Ankunft um 15:21")
        assertEquals("straight", n.maneuver)
        assertEquals("Günter-Porsiel-Str", n.street)
    }

    @Test fun neu_uebersichtWirdIgnoriert() {
        assertTrue(NavParser.isStatusOnly("5 min (1,9 km) mit dem Rad", "Ankunft um 15:21 · Vor d. Seelhorst"))
        assertTrue(NavParser.isStatusOnly("1 Std. 5 Min. (20 km)", "Ankunft um 16:30"))
        assertTrue(NavParser.isStatusOnly("12 min (4.1 mi) by bike", "Arrive at 3:21 PM"))
        assertTrue(NavParser.isStatusOnly("Route wird neu berechnet...", null))
    }

    // --- ALT (bisheriges Format) muss weiter gehen -------------------------

    @Test fun alt_entfernungImTitelAnweisungImText() {
        val n = p("200 m", "Links abbiegen auf Hauptstraße", "15:21 Ankunft")
        assertEquals("turn-left", n.maneuver)
        assertEquals("200 m", n.distance)
        assertEquals("Hauptstraße", n.street)
        assertFalse(NavParser.isStatusOnly("200 m", "Links abbiegen auf Hauptstraße"))
    }

    @Test fun alt_rechtsHaltenRichtung() {
        val n = p("1,2 km", "Rechts halten Richtung A2")
        assertEquals("slight-right", n.maneuver)
        assertEquals("1,2 km", n.distance)
        assertEquals("A2", n.street)
    }

    @Test fun alt_nurStrasseImText() {
        val n = p("350 m", "Bahnhofstraße")
        assertEquals("350 m", n.distance)
        assertEquals("Bahnhofstraße", n.street)
    }

    @Test fun alt_englisch() {
        val n = p("500 ft", "Turn right onto Main St")
        assertEquals("turn-right", n.maneuver)
        assertEquals("500 ft", n.distance)
        assertEquals("Main St", n.street)
        assertFalse(NavParser.isStatusOnly("500 ft", "Turn right onto Main St"))
    }

    @Test fun alt_kreisverkehrUndZiel() {
        assertEquals("roundabout", p("80 m", "Im Kreisverkehr die 2. Ausfahrt nehmen").maneuver)
        assertEquals("arrive", p("Ziel erreicht", null).maneuver)
    }
}
