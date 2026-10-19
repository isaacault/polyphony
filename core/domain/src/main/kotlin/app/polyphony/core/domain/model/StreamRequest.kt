package app.polyphony.core.domain.model

/** Everything a player (local or remote receiver) needs to fetch audio bytes. */
data class StreamRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** Best-effort MIME type of the delivered stream (after transcoding), if known. */
    val mimeType: String? = null,
    /** True when the server will transcode; false for passthrough of the original file. */
    val isTranscoded: Boolean = false,
)
