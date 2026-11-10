package app.polyphony.core.playback.audio.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import app.polyphony.core.domain.playback.EqualizerMode
import app.polyphony.core.domain.playback.EqualizerPreset
import app.polyphony.core.domain.playback.EqualizerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class EqualizerAudioProcessorTest {

    private val stereo16 = AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    private fun tone(frames: Int = 4_410): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val s = (8_000 * sin(2 * PI * 100.0 * i / 44_100)).roundToInt().toShort()
            buffer.putShort(s).putShort(s)
        }
        buffer.flip()
        return buffer
    }

    private fun AudioProcessor.start(format: AudioFormat = stereo16): AudioProcessor = apply {
        configure(format)
        flush()
    }

    private fun AudioProcessor.feed(input: ByteBuffer): ByteArray {
        queueInput(input)
        val out = getOutput()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }

    @Test fun `with no curve the sound passes untouched`() {
        val processor = EqualizerAudioProcessor { null }.start()
        assertTrue("active, ready to be turned on mid-song", processor.isActive)
        val input = tone()
        val expected = bytes(input)
        assertTrue(expected.contentEquals(processor.feed(input)))
    }

    @Test fun `the curve is heard from the next buffer, and turning it off restores the original`() {
        val eq = BuiltInEqualizer()
        val processor = EqualizerAudioProcessor { eq.curve }.start()
        val plain = bytes(tone())

        eq.apply(EqualizerSettings(mode = EqualizerMode.BUILT_IN, preset = EqualizerPreset.BASS_BOOST))
        val boosted = processor.feed(tone())
        assertEquals(plain.size, boosted.size)
        assertFalse(plain.contentEquals(boosted))

        eq.apply(EqualizerSettings(mode = EqualizerMode.SYSTEM, preset = EqualizerPreset.BASS_BOOST))
        assertTrue(plain.contentEquals(processor.feed(tone())))
    }

    @Test fun `the same settings again keep the same curve, so nothing is redesigned`() {
        val eq = BuiltInEqualizer()
        val settings = EqualizerSettings(mode = EqualizerMode.BUILT_IN, preset = EqualizerPreset.ROCK)
        eq.apply(settings)
        val first = eq.curve
        eq.apply(settings.copy())
        assertTrue(first === eq.curve)
        eq.apply(settings.copy(preset = EqualizerPreset.POP))
        assertNotEquals(first, eq.curve)
    }

    @Test fun `float streams are taken, other encodings left to pass by`() {
        assertTrue(EqualizerAudioProcessor { null }.start(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)).isActive)
        assertFalse(EqualizerAudioProcessor { null }.start(AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT)).isActive)
    }

    @Test fun `a flush between songs keeps the filters going`() {
        val eq = BuiltInEqualizer().apply { apply(EqualizerSettings(mode = EqualizerMode.BUILT_IN, preset = EqualizerPreset.LOUDNESS)) }
        val continuous = EqualizerAudioProcessor { eq.curve }.start()
        val flushed = EqualizerAudioProcessor { eq.curve }.start()
        continuous.feed(tone())
        flushed.feed(tone())
        flushed.flush()
        assertTrue(continuous.feed(tone()).contentEquals(flushed.feed(tone())))
    }
}
