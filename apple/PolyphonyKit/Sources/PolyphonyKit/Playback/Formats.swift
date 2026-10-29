import Foundation

/// Which files Apple's player (AVFoundation) plays as they are. Navidrome serves the rest (Ogg
/// Vorbis, Opus, WMA, APE, WavPack, DSD…) transcoded, so they play too.
public enum AppleFormats {
    private static let native: Set<String> = ["mp3", "m4a", "m4b", "mp4", "aac", "alac", "flac", "wav", "aif", "aiff", "aifc", "caf"]

    /// nil (unknown) is left to the player: most libraries' unlabelled files are MP3 or FLAC.
    public static func plays(_ container: String?) -> Bool {
        guard let c = container?.lowercased(), !c.isEmpty else { return true }
        return native.contains(c)
    }

    /// The transcode to ask for when [plays] says no: MP3 at this bitrate.
    public static let fallbackFormat = "mp3"
    public static let fallbackBitrateKbps = 320
}
