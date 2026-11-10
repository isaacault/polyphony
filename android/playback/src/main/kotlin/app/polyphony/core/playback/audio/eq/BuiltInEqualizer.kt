package app.polyphony.core.playback.audio.eq

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import app.polyphony.core.domain.playback.EqualizerSettings

/**
 * The built-in equaliser, for this phone's own playback. Each player gets its own
 * [EqualizerAudioProcessor] through [renderersFactory], all reading the one curve here: the main
 * player and the crossfade's second player sound the same through a fade, and nothing depends on the
 * audio session (unlike the platform's effects, which an OEM may lack or limit to five fixed bands).
 * Cast and DLNA receivers play the stream themselves and never pass through it.
 */
class BuiltInEqualizer {

    /** The curve the processors play, or null for untouched sound; a new list only when it changes. */
    @Volatile
    var curve: List<Float>? = null
        private set

    fun apply(settings: EqualizerSettings) {
        val next = settings.builtInCurve
        if (next != curve) curve = next
    }

    /** ExoPlayer's default renderers, with the equaliser at the head of the audio sink's processing. */
    fun renderersFactory(context: Context): RenderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(EqualizerAudioProcessor { curve }))
                .build()
    }
}
