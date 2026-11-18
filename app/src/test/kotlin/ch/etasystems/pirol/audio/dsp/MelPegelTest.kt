package ch.etasystems.pirol.audio.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * T107: Anzeige-Pegel. Ein leiser Vogelruf (-40 dBFS, 3 kHz) muss deutlich ueber dem
 * Boden (-80 dB) liegen; mit der alten Referenz 80 dB lag er dort.
 */
class MelPegelTest {

    private fun sine(dbfs: Double, hz: Double, sr: Int = 48000, sec: Double = 0.5): ShortArray {
        val amp = Math.pow(10.0, dbfs / 20.0) * 32767
        return ShortArray((sr * sec).toInt()) { i -> (amp * sin(2 * PI * hz * i / sr)).toInt().toShort() }
    }

    @Test
    fun `leiser Ruf ist sichtbar`() {
        val frames = MelSpectrogram(sampleRate = 48000).process(sine(-40.0, 3000.0))
        val peak = frames.maxOf { f -> f.max() }
        assertTrue("Spitze $peak dB muss ueber -60 liegen", peak > -60f)
    }

    @Test
    fun `Vollaussteuerung uebersteuert nicht ins Unendliche`() {
        val frames = MelSpectrogram(sampleRate = 48000).process(sine(-1.0, 3000.0))
        val peak = frames.maxOf { f -> f.max() }
        assertTrue("Spitze $peak dB", peak <= 0f && peak > -20f)
    }
}
