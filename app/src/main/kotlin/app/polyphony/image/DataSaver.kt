package app.polyphony.image

/**
 * Process-wide flag for the artwork data saver. The Coil interceptor reads it on every request, so
 * it is a plain volatile; the Application keeps it in step with the setting and the current network.
 */
object DataSaver {
    @Volatile
    var reduceArtwork: Boolean = false

    /** Largest cover edge requested while the data saver is active (the stored default is 600). */
    const val MAX_EDGE_PX = 320
}
