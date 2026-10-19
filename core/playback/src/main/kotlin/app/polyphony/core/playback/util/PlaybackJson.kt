package app.polyphony.core.playback.util

import kotlinx.serialization.json.Json

/** Compact JSON used for queue persistence and MediaItem extras. */
val PlaybackJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    isLenient = true
}
