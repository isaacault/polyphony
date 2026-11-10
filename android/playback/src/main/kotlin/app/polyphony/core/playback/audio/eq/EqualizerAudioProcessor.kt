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

    /** The engine for [gains] in [format], built or updated only when either changed. */
    private fun engineFor(gains: List<Float>?, format: AudioFormat): EqualizerDsp? {
        if (gains == null || format.sampleRate <= 0 || format.channelCount <= 0) {
            dsp = null
            playing = null
            return null
        }
        val current = dsp
        if (current != null && current.sampleRate == format.sampleRate && current.channelCount == format.channelCount) {
            if (gains !== playing) {
                current.update(EqualizerDesign.of(gains, format.sampleRate))
                playing = gains
            }
            return current.takeUnless { it.design.isFlat }
        }
        val design = EqualizerDesign.of(gains, format.sampleRate)
        playing = gains
        return EqualizerDsp(design, format.channelCount).also { dsp = it }.takeUnless { design.isFlat }
    }

    override fun onReset() {
        dsp = null
        playing = null
    }
}
