package app.polyphony.core.playback.audio.eq

import app.polyphony.core.domain.playback.EqualizerBands
import app.polyphony.core.domain.playback.EqualizerPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EqualizerDesignTest {

    private val rates = listOf(44_100, 48_000, 96_000)

    @Test fun `a bell has its gain at its centre and none far from it`() {
        val bell = Biquad.peaking(1_000.0, 6.0, EqualizerDesign.Q, 48_000)
        assertEquals(6.0, bell.magnitudeDb(1_000.0, 48_000), 1e-9)
        assertEquals(0.0, bell.magnitudeDb(20.0, 48_000), 0.05)
        assertEquals(0.0, bell.magnitudeDb(20_000.0, 48_000), 0.1)
        val cut = Biquad.peaking(1_000.0, -6.0, EqualizerDesign.Q, 48_000)
        assertEquals(-6.0, cut.magnitudeDb(1_000.0, 48_000), 1e-9)
    }

    @Test fun `flat is no filters and no preamp`() {
        val flat = EqualizerDesign.of(EqualizerBands.FLAT, 44_100)
        assertTrue(flat.isFlat)
        assertEquals(0.0, flat.responseDb(1_000.0), 0.0)
    }

    @Test fun `every preset sounds as its sliders say at each band's centre`() {
        for (rate in rates) {
            for (preset in EqualizerPreset.entries) {
                val gains = preset.gainsDb ?: continue
                val design = EqualizerDesign.of(gains, rate)
                EqualizerBands.FREQUENCIES_HZ.forEachIndexed { i, f ->
                    val got = design.filtersDb(f.toDouble())
                    assertEquals("${preset.name} at $f Hz, $rate Hz", gains[i].toDouble(), got, 0.1)
                }
            }
        }
    }

    @Test fun `a custom curve with steep steps still lands close`() {
        val steps = listOf(-12f, -12f, 12f, 12f, 0f, 0f, 6f, -6f, 6f, -6f)
        val design = EqualizerDesign.of(steps, 48_000)
        EqualizerBands.FREQUENCIES_HZ.forEachIndexed { i, f ->
            assertEquals("at $f Hz", steps[i].toDouble(), design.filtersDb(f.toDouble()), 0.5)
        }
    }

    @Test fun `the preamp keeps every frequency at or below 0 dB, the loudest at 0`() {
        for (rate in rates) {
            for (preset in EqualizerPreset.entries) {
                val gains = preset.gainsDb ?: continue
                if (gains.none { it > 0f }) continue
                val design = EqualizerDesign.of(gains, rate)
                val response = EqualizerDesign.grid(rate).map(design::responseDb)
                assertTrue("${preset.name} $rate: ${response.max()}", response.max() <= 1e-9)
                assertEquals("${preset.name} $rate", 0.0, response.max(), 1e-9)
                assertTrue("${preset.name} preamp ${design.preampDb}", design.preampDb < 0)
            }
        }
    }

    @Test fun `bass boost is about its largest boost quieter elsewhere`() {
        val design = EqualizerDesign.of(EqualizerPreset.BASS_BOOST.gainsDb!!, 44_100)
        assertTrue("${design.preampDb}", design.preampDb in -7.5..-5.9)
        assertEquals(design.preampDb, design.responseDb(4_000.0), 0.3)
    }

    @Test fun `cuts alone need next to no preamp`() {
        // Holding the band next to a cut at 0 dB takes a hair of boost there, hence not exactly 0.
        for (preset in listOf(EqualizerPreset.BASS_CUT, EqualizerPreset.TREBLE_CUT)) {
            val design = EqualizerDesign.of(preset.gainsDb!!, 48_000)
            assertTrue("${preset.name} ${design.preampDb}", design.preampDb in -0.1..0.0)
        }
        assertTrue(EqualizerDesign.of(EqualizerPreset.BASS_CUT.gainsDb!!, 48_000).responseDb(31.0) < -5.0)
    }

    @Test fun `bands the stream can't carry are left out`() {
        assertEquals(EqualizerBands.FREQUENCIES_HZ.map { it.toDouble() }, EqualizerDesign.of(EqualizerPreset.ROCK.gainsDb!!, 44_100).frequenciesHz)
        val at32k = EqualizerDesign.of(EqualizerPreset.ROCK.gainsDb!!, 32_000)
        assertEquals(9, at32k.frequenciesHz.size)
        assertEquals(8_000.0, at32k.frequenciesHz.last(), 0.0)
        assertEquals(8, EqualizerDesign.of(EqualizerPreset.ROCK.gainsDb!!, 16_000).frequenciesHz.size)
        val onlyTop = List(9) { 0f } + 6f
        assertTrue("a curve only above the stream is flat", EqualizerDesign.of(onlyTop, 32_000).isFlat)
    }

    @Test fun `a curve from elsewhere is cleaned before it's designed`() {
        val design = EqualizerDesign.of(listOf(99f, Float.NaN), 48_000)
        assertEquals(12.0, design.filtersDb(31.0), 0.5)
        assertTrue(abs(design.filtersDb(2_000.0)) < 0.5)
    }
}
