package ch.etasystems.pirol.ml

import ch.etasystems.pirol.audio.dsp.AudioResampler
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** T248: Der MFCC-Rueckfall resampelt nach [AudioResampler.defaultMode] (nicht immer linear). */
class MfccResamplerModeTest {

    private val saved = AudioResampler.defaultMode

    @After
    fun tearDown() {
        AudioResampler.defaultMode = saved
    }

    /** 96 kHz, Ton bei 30 kHz (ueber Nyquist von 48 kHz): linear faltet ihn ins Band, Polyphase daempft ihn */
    private val input = FloatArray(96_000) { (0.5 * sin(2 * PI * 30_000.0 * it / 96_000.0)).toFloat() }

    @Test
    fun `Modus der Einstellung wirkt auf die MFCC`() {
        AudioResampler.defaultMode = AudioResampler.Mode.POLYPHASE
        val poly = MfccExtractor().extractSummary(input, 96_000)
        AudioResampler.defaultMode = AudioResampler.Mode.LINEAR
        val linear = MfccExtractor().extractSummary(input, 96_000)
        assertFalse("Polyphase und linear muessen verschiedene MFCC liefern", poly.contentEquals(linear))
    }

    @Test
    fun `gleiche Abtastrate bleibt unveraendert`() {
        AudioResampler.defaultMode = AudioResampler.Mode.POLYPHASE
        val a = MfccExtractor().extractSummary(input.copyOf(48_000), 48_000)
        AudioResampler.defaultMode = AudioResampler.Mode.LINEAR
        val b = MfccExtractor().extractSummary(input.copyOf(48_000), 48_000)
        assertTrue(a.contentEquals(b))
    }
}
