import Foundation

/// What a speaker is told a Play now song is, and whether continuo converts it first (the Android
/// app's `PlayNowSpeakerFormat`). continuo serves the Soulseek copy as it is, in whatever format Play
/// now found quickest (MP3, FLAC, often Opus), so the label comes from the copy's real format, and a
/// format the speaker doesn't list (the Yamaha RX-V483 has no Opus decoder) is asked for as FLAC, or
/// MP3 (continuo's `as=`). An unknown format is asked for converted too, so the label is true.
public enum PlayNowSpeakerFormat {
    public struct Choice: Equatable, Sendable {
        /// The `protocolInfo` MIME type the speaker is given.
        public let mimeType: String
        /// continuo's `as=` value (flac, mp3), or nil for the file as it is.
        public let convertTo: String?

        public init(mimeType: String, convertTo: String?) {
            self.mimeType = mimeType
            self.convertTo = convertTo
        }
    }

    public static func choose(format: String?, formats: RendererFormats) -> Choice {
        let ext = format.map { $0.lowercased().trimmingCharacters(in: .whitespaces) }
            .map { $0.hasPrefix(".") ? String($0.dropFirst()) : $0 }
        let mime = ext.flatMap(mime(for:))
        let plays: Bool
        switch ext {
        case _ where mime == nil: plays = false
        case _ where formats.unknown: plays = true
        case "opus": plays = formats.opus
        case "ogg", "oga": plays = formats.vorbis
        default: plays = formats.supports(mime)
        }
        if let mime, plays { return Choice(mimeType: mime, convertTo: nil) }
        return formats.flac || !formats.mp3
            ? Choice(mimeType: "audio/flac", convertTo: "flac")
            : Choice(mimeType: "audio/mpeg", convertTo: "mp3")
    }

    /// The link with continuo's `as=` added, when the copy is converted.
    public static func link(_ url: URL, _ choice: Choice) -> URL {
        guard let fmt = choice.convertTo,
              var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        parts.queryItems = (parts.queryItems ?? []) + [URLQueryItem(name: "as", value: fmt)]
        return parts.url ?? url
    }

    /// The MIME type of a copy's file extension, as continuo serves it; nil when not known.
    public static func mime(for ext: String) -> String? {
        switch ext {
        case "mp3": "audio/mpeg"
        case "flac": "audio/flac"
        case "m4a", "aac", "alac", "mp4": "audio/mp4"
        case "ogg", "oga", "opus": "audio/ogg"
        case "wav": "audio/wav"
        default: nil
        }
    }
}
