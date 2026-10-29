package app.polyphony.core.domain.transcode

import kotlinx.serialization.Serializable

/** Where audio is rendered. Remote targets carry a stable key used for per-device preferences. */
@Serializable
sealed interface OutputTarget {
    val key: String
    val displayName: String
    val isRemote: Boolean get() = this !is Local

    @Serializable
    data object Local : OutputTarget {
        override val key: String get() = "local"
        override val displayName: String get() = "This device"
    }

    @Serializable
    data class Cast(
        val deviceId: String,
        val friendlyName: String,
        val modelName: String? = null,
    ) : OutputTarget {
        override val key: String get() = "cast:$deviceId"
        override val displayName: String get() = friendlyName
    }

    @Serializable
    data class Dlna(
        val udn: String,
        val friendlyName: String,
        val modelName: String? = null,
    ) : OutputTarget {
        override val key: String get() = "dlna:$udn"
        override val displayName: String get() = friendlyName
    }
}
