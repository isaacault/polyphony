package app.polyphony.core.playback.audio.eq

import app.polyphony.core.domain.playback.EqualizerPreset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerDspTest {

    private val rate = 44_100

    /** [seconds] of a stereo sine at [frequencyHz] and [amplitude] (1 = full scale), as 16-bit PCM. */
    private fun sine16(frequencyHz: Double, amplitude: Double, seconds: Double = 1.5, fromFrame: Int = 0): ByteBuffer {
        val frames = (rate * seconds).toInt()
        val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder())
        for (i in fromFrame until fromFrame + frames) {
            val s = (amplitude * 32767 * sin(2 * PI * frequencyHz * i / rate)).roundToInt().toShort()
            buffer.putShort(s).putShort(s)
        }
        buffer.flip()
        return buffer
    }

    private fun run16(dsp: EqualizerDsp, input: ByteBuffer): ShortArray {
        val output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.nativeOrder())
        dsp.processPcm16(input, output)
        output.flip()
        return ShortArray(output.remaining() / 2) { output.getShort() }
    }

    /** RMS in dB of the left channel's samples from [fromFrame] on. */
    private fun rmsDb(samples: ShortArray, fromFrame: Int): Double {
        var sum = 0.0
        var n = 0
        for (i in fromFrame * 2 until samples.size step 2) {
            sum += samples[i].toDouble() * samples[i]
            n++
        }
        return 20 * log10(sqrt(sum / n))
    }

    @Test fun `a tone comes out at the curve's level for its frequency`() {
        val design = EqualizerDesign.of(EqualizerPreset.BASS_BOOST.gainsDb!!, rate)
        for (f in listOf(62.0, 250.0, 1_000.0, 4_000.0)) {
            val input = sine16(f, 0.25)
            val inDb = rmsDb(ShortArray(input.remaining() / 2) { input.getShort(it * 2) }, rate / 2)
            val outDb = rmsDb(run16(EqualizerDsp(design, 2), input), rate / 2)
            assertEquals("at $f Hz", design.responseDb(f), outDb - inDb, 0.1)
        }
    }

    @Test fun `a full-scale tone at a boosted band doesn't clip`() {
        val curve = listOf(0f, 0f, 0f, 0f, 0f, 12f, 0f, 0f, 0f, 0f)
        val out = run16(EqualizerDsp(EqualizerDesign.of(curve, rate), 2), sine16(1_000.0, 1.0))
        val peak = out.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak <= (EqualizerDsp.LIMIT * 32768).roundToInt())
        assertTrue("still loud: $peak", peak > 0.9 * 32768)
    }

    @Test fun `without the preamp a boost would have clipped, so the limiter catches what's left`() {
        // White-ish noise at full scale through bass and treble boost: the preamp alone can't promise
        // every sample stays in range (phase adds peaks), the limiter does.
        val design = EqualizerDesign.of(EqualizerPreset.LOUDNESS.gainsDb!!, rate)
        val frames = rate
        val input = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder())
        val random = java.util.Random(7)
        repeat(frames) { val s = (if (random.nextBoolean()) 32767 else -32768).toShort(); input.putShort(s).putShort(s) }
        input.flip()
        val out = run16(EqualizerDsp(design, 2), input)
        assertTrue(out.all { abs(it.toInt()) <= (EqualizerDsp.LIMIT * 32768).roundToInt() })
    }

    @Test fun `split buffers sound the same as one, so a gapless join doesn't click`() {
        val design = EqualizerDesign.of(EqualizerPreset.ROCK.gainsDb!!, rate)
        val whole = run16(EqualizerDsp(design, 2), sine16(110.0, 0.3, seconds = 0.2))
        val split = EqualizerDsp(design, 2)
        val input = sine16(110.0, 0.3, seconds = 0.2)
        val half = (input.remaining() / 2) and 3.inv()
        val first = input.duplicate().order(ByteOrder.nativeOrder()).also { it.limit(half) }
        val second = input.duplicate().order(ByteOrder.nativeOrder()).also { it.position(half) }
        assertArrayEquals(whole, run16(split, first) + run16(split, second))
    }

    @Test fun `a new curve mid-song keeps going without a jump`() {
        val dsp = EqualizerDsp(EqualizerDesign.of(EqualizerPreset.ROCK.gainsDb!!, rate), 2)
        val before = run16(dsp, sine16(200.0, 0.3, seconds = 0.1))
        dsp.update(EqualizerDesign.of(EqualizerPreset.VOCAL.gainsDb!!, rate))
        val after = run16(dsp, sine16(200.0, 0.3, seconds = 0.1, fromFrame = rate / 10))
        val step = abs(after[0] - before[before.size - 2])
        assertTrue("step $step", step < 0.05 * 32768)
    }

    @Test fun `float streams are equalised too`() {
        val design = EqualizerDesign.of(EqualizerPreset.TREBLE_BOOST.gainsDb!!, rate)
        val frames = rate
        val input = ByteBuffer.allocate(frames * 8).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val s = (0.25 * sin(2 * PI * 8_000.0 * i / rate)).toFloat()
            input.putFloat(s).putFloat(s)
        }
        input.flip()
        val output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.nativeOrder())
        EqualizerDsp(design, 2).processFloat(input, output)
        output.flip()
        var sum = 0.0
        var n = 0
        for (i in 0 until frames) {
            val l = output.getFloat()
            output.getFloat()
            if (i >= frames / 2) { sum += l.toDouble() * l; n++ }
        }
        val outDb = 20 * log10(sqrt(sum / n) / (0.25 / sqrt(2.0)))
        assertEquals(design.responseDb(8_000.0), outDb, 0.1)
    }

    @Test fun `mono and multichannel streams are equalised per channel`() {
        val design = EqualizerDesign.of(EqualizerPreset.VOCAL.gainsDb!!, rate)
        val mono = EqualizerDsp(design, 1)
        val input = ByteBuffer.allocate(2_000).order(ByteOrder.nativeOrder())
        repeat(1_000) { input.putShort((it * 13 % 2000 - 1000).toShort()) }
        input.flip()
        val output = ByteBuffer.allocate(2_000).order(ByteOrder.nativeOrder())
        mono.processPcm16(input, output)
        assertEquals(2_000, output.position())
        assertEquals(6, EqualizerDsp(design, 6).channelCount)
    }
}
