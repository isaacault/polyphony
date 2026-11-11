package app.polyphony.core.playback.audio.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * The built-in equaliser inside one player's audio sink. [curve] is read at every buffer, so a new
 * preset is heard as soon as the sink's buffer plays out, with no re-preparing; a null curve passes
 * the audio through untouched.
 *
 * It stays active for every 16-bit or float stream whatever the setting, so turning the equaliser on
 * mid-song needs no reconfiguring, and it holds nothing back: a gapless change of song goes straight
 * through, and the filters' memory is kept over a flush, so the join between two songs of a live
 * album isn't clicked by a reset.
 */
class EqualizerAudioProcessor(private val curve: () -> List<Float>?) : BaseAudioProcessor() {

    private var dsp: EqualizerDsp? = null
    private var playing: List<Float>? = null

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val format = inputAudioFormat
        val engine = engineFor(curve(), format)
        when {
            engine == null -> output.put(inputBuffer)
            format.encoding == C.ENCODING_PCM_FLOAT -> engine.processFloat(inputBuffer, output)
            else -> engine.processPcm16(inputBuffer, output)
        }
        // A partial frame can't be equalised; it can't occur in PCM, but never hand back less than taken.
        if (inputBuffer.hasRemaining()) output.put(inputBuffer)
        output.flip()
    }

    /**
     * The engine for [gains] in [format], built or updated only when either changed; null while
     * the sound passes as it is. Turning the equaliser off (or flat) crossfades to the plain sound
     * before it stops being used, and turning it on crossfades in, so neither is a step in level.
     */
    private fun engineFor(gains: List<Float>?, format: AudioFormat): EqualizerDsp? {
        if (format.sampleRate <= 0 || format.channelCount <= 0) {
            dsp = null
            playing = null
            return null
        }
        val current = dsp
        if (current == null || current.sampleRate != format.sampleRate || current.channelCount != format.channelCount) {
            // The first buffer, or a stream in another format: as the setting is, with no fade.
            val design = designOf(gains, format.sampleRate)
            playing = gains
            val engine = EqualizerDsp(design ?: EqualizerDesign.flat(format.sampleRate), format.channelCount)
            if (design == null) engine.startFadedOut()
            dsp = engine
            return engine.takeUnless { it.isFadedOut }
        }
        if (gains !== playing) {
            playing = gains
            val design = designOf(gains, format.sampleRate)
            if (design == null) {
                current.fadeTo(on = false)   // the curve plays out under the fade
            } else {
                current.update(design)
                current.fadeTo(on = true)
            }
        }
        return current.takeUnless { it.isFadedOut }
    }

    private fun designOf(gains: List<Float>?, sampleRate: Int): EqualizerDesign? =
        gains?.let { EqualizerDesign.of(it, sampleRate) }?.takeUnless { it.isFlat }

    override fun onReset() {
        dsp = null
        playing = null
    }
}
