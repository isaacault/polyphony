package app.polyphony.core.playback.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/** Registered via manifest meta-data `com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME`. */
class PolyphonyCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        // Media3 owns the media session and notification; disable the Cast SDK's own.
        val mediaOptions = CastMediaOptions.Builder()
            .setMediaSessionEnabled(false)
            .setNotificationOptions(null)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(mediaOptions)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setResumeSavedSession(true)
            .setEnableReconnectionService(true)
            .setRemoteToLocalEnabled(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
