package ch.etasystems.pirol.ui.components

import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** T199: gemeinsame Anzeige-Einstellungen (Frequenz-/dB-Bereich) fuer Sonogramm und Spektrum. */
class SharedDisplayTest {

    @Test
    fun `Spektrum zeigt denselben Frequenzbereich wie das Sonogramm`() {
        val s = SpectrogramDisplaySettings(freqMinHz = 0f, freqMaxHz = 24000f)
        // T200: Voegel-Config bis Nyquist (24 kHz bei 48 kHz), genau wie die Sonogramm-Achse
        assertEquals(0f to 24000f, sharedFreqRange(s, SpectrogramConfig.BIRDS.resolved(48000), 48000))
        // tiefere Abtastrate: Obergrenze = Nyquist der tatsaechlichen Rate
        assertEquals(0f to 16000f, sharedFreqRange(s, SpectrogramConfig.BIRDS.resolved(32000), 32000))
        val narrow = s.copy(freqMinHz = 2000f, freqMaxHz = 6000f)
        assertEquals(2000f to 6000f, sharedFreqRange(narrow, SpectrogramConfig.BIRDS, 48000))
        // Breitband bis Nyquist
        assertEquals(0f to 16000f, sharedFreqRange(s, SpectrogramConfig.WIDEBAND, 32000))
    }

    @Test
    fun `dB-Regler setzt den gemeinsamen Bereich und schaltet Auto-Kontrast aus`() {
        val s = SpectrogramDisplaySettings(autoContrast = true).withDbRange(-100.4f, -30.6f)
        assertEquals(-100f, s.minDb, 0f)
        assertEquals(-31f, s.maxDb, 0f)
        assertFalse(s.autoContrast)
        // Mindestspanne
        val tight = SpectrogramDisplaySettings().withDbRange(-50f, -48f)
        assertEquals(-50f + MIN_DISPLAY_SPAN_DB, tight.maxDb, 0f)
    }
}
