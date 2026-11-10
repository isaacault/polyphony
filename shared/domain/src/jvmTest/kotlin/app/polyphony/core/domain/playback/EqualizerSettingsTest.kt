package app.polyphony.core.domain.playback

import app.polyphony.core.domain.repository.AppSettings
import app.polyphony.core.domain.repository.PlaybackSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerSettingsTest {

    // As the app's own Json (NetworkModule): unknown keys ignored, unknown enum values defaulted.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test fun `every preset has a gain for each band, within range and on the half-decibel grid`() {
        for (preset in EqualizerPreset.entries) {
            val gains = preset.gainsDb ?: continue
            assertEquals(preset.name, EqualizerBands.COUNT, gains.size)
            for (g in gains) {
                assertTrue("${preset.name} $g", g in EqualizerBands.MIN_GAIN_DB..EqualizerBands.MAX_GAIN_DB)
                assertEquals("${preset.name} $g", g, EqualizerBands.clamp(g))
            }
        }
        assertNull("custom is the user's", EqualizerPreset.CUSTOM.gainsDb)
        assertEquals(EqualizerBands.FLAT, EqualizerPreset.FLAT.gainsDb)
    }

    @Test fun `bands are octaves from 31 Hz to 16 kHz`() {
        val f = EqualizerBands.FREQUENCIES_HZ
        assertEquals(10, f.size)
        assertEquals(31f, f.first())
        assertEquals(16_000f, f.last())
        f.zipWithNext().forEach { (a, b) -> assertEquals(2.0, (b / a).toDouble(), 0.05) }
        assertEquals(listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"), f.map(EqualizerBands::label))
    }

    @Test fun `gains are clamped to the range and the grid`() {
        assertEquals(12f, EqualizerBands.clamp(40f))
        assertEquals(-12f, EqualizerBands.clamp(-13.2f))
        assertEquals(2.5f, EqualizerBands.clamp(2.6f))
        assertEquals(3f, EqualizerBands.clamp(2.8f))
        assertEquals(0f, EqualizerBands.clamp(Float.NaN))
        assertEquals(12f, EqualizerBands.clamp(Float.POSITIVE_INFINITY))
    }

    @Test fun `a curve from elsewhere is made the right length and range`() {
        assertEquals(List(10) { 0f }, EqualizerBands.sanitize(emptyList()))
        assertEquals(listOf(3f, 12f) + List(8) { 0f }, EqualizerBands.sanitize(listOf(3f, 99f)))
        assertEquals(List(10) { 1f }, EqualizerBands.sanitize(List(14) { 1f }))
    }

    @Test fun `the curve played is the preset's, or the custom one`() {
        val custom = List(10) { it.toFloat() - 5f }
        assertEquals(EqualizerPreset.BASS_BOOST.gainsDb, EqualizerSettings(preset = EqualizerPreset.BASS_BOOST, customGainsDb = custom).gainsDb)
        assertEquals(custom, EqualizerSettings(preset = EqualizerPreset.CUSTOM, customGainsDb = custom).gainsDb)
    }

    @Test fun `only the built-in mode with a shape plays a curve`() {
        val bass = EqualizerSettings(preset = EqualizerPreset.BASS_BOOST)
        assertNull(bass.copy(mode = EqualizerMode.OFF).builtInCurve)
        assertNull("the phone's effects do it", bass.copy(mode = EqualizerMode.SYSTEM).builtInCurve)
        assertEquals(EqualizerPreset.BASS_BOOST.gainsDb, bass.copy(mode = EqualizerMode.BUILT_IN).builtInCurve)
        assertNull("flat leaves the sound untouched", EqualizerSettings(mode = EqualizerMode.BUILT_IN).builtInCurve)
    }

    @Test fun `moving a band starts the custom curve from the preset playing`() {
        val rock = EqualizerSettings(mode = EqualizerMode.BUILT_IN, preset = EqualizerPreset.ROCK, customGainsDb = List(10) { -3f })
        val edited = rock.withBand(2, 7.3f)
        assertEquals(EqualizerPreset.CUSTOM, edited.preset)
        val expected = EqualizerPreset.ROCK.gainsDb!!.toMutableList().also { it[2] = 7.5f }
        assertEquals(expected, edited.customGainsDb)
        assertEquals("another band of the custom curve", 1f, edited.withBand(9, 1f).customGainsDb[9])
        assertEquals("a band that isn't there", edited, edited.withBand(10, 3f))
    }

    @Test fun `settings round-trip through JSON, as on disk and in a backup`() {
        val settings = AppSettings(
            playback = PlaybackSettings(
                equalizer = EqualizerSettings(EqualizerMode.BUILT_IN, EqualizerPreset.CUSTOM, listOf(1f, -2.5f, 3f, 0f, 0f, 0f, 0f, 0f, 4f, -12f)),
            ),
        )
        val decoded = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(settings.playback.equalizer, decoded.playback.equalizer)
    }

    @Test fun `settings from before the built-in equaliser keep announcing the session to the phone`() {
        val old = json.decodeFromString(AppSettings.serializer(), """{"playback":{"crossfadeMs":4000,"externalEqualizer":true}}""")
        assertEquals(4000, old.playback.crossfadeMs)
        assertEquals(EqualizerSettings(), old.playback.equalizer)
        assertEquals(EqualizerMode.SYSTEM, old.playback.equalizer.mode)
    }

    @Test fun `a preset or mode from a newer build falls back rather than failing`() {
        val newer = json.decodeFromString(
            AppSettings.serializer(),
            """{"playback":{"equalizer":{"mode":"SPATIAL","preset":"DUBSTEP","customGainsDb":[1,2,3]}}}""",
        )
        assertEquals(EqualizerMode.SYSTEM, newer.playback.equalizer.mode)
        assertEquals(EqualizerPreset.FLAT, newer.playback.equalizer.preset)
        assertEquals(listOf(1f, 2f, 3f) + List(7) { 0f }, newer.playback.equalizer.copy(preset = EqualizerPreset.CUSTOM).gainsDb)
    }
}
