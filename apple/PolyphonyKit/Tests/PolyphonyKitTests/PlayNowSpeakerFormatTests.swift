import Foundation
import Testing
@testable import PolyphonyKit

struct PlayNowSpeakerFormatTests {
    /// The Yamaha RX-V483's GetProtocolInfo sinks (2026-11-13), less its L16 rates: no Ogg, no Opus.
    let yamaha = RendererFormats(mimeTypes: [
        "audio/3gpp", "audio/aac", "audio/aacp", "audio/aiff", "audio/dsd", "audio/flac", "audio/L16",
        "audio/m4a", "audio/mp3", "audio/mp4", "audio/mpeg", "audio/vnd.dlna.adts", "audio/wav",
        "audio/x-aac", "audio/x-aiff", "audio/x-dff", "audio/x-dsd", "audio/x-dsf", "audio/x-flac",
        "audio/x-mp3", "audio/x-mp4", "audio/x-mpeg", "audio/x-mpg", "audio/x-ms-wma", "audio/x-wav",
    ])

    @Test func anMP3IsLabelledMP3() {
        #expect(PlayNowSpeakerFormat.choose(format: "mp3", formats: yamaha) == .init(mimeType: "audio/mpeg", convertTo: nil))
        #expect(PlayNowSpeakerFormat.choose(format: "FLAC", formats: yamaha) == .init(mimeType: "audio/flac", convertTo: nil))
    }

    @Test func opusIsConvertedForASpeakerWithoutIt() {
        #expect(PlayNowSpeakerFormat.choose(format: "opus", formats: yamaha) == .init(mimeType: "audio/flac", convertTo: "flac"))
        let opus = RendererFormats(mimeTypes: ["audio/mpeg", "audio/opus"])
        #expect(PlayNowSpeakerFormat.choose(format: "opus", formats: opus) == .init(mimeType: "audio/ogg", convertTo: nil))
        let mp3Only = RendererFormats(mimeTypes: ["audio/mpeg"])
        #expect(PlayNowSpeakerFormat.choose(format: "opus", formats: mp3Only) == .init(mimeType: "audio/mpeg", convertTo: "mp3"))
    }

    @Test func unknownFormatsAreConvertedAndUnknownSpeakersGetTheFile() {
        #expect(PlayNowSpeakerFormat.choose(format: nil, formats: yamaha) == .init(mimeType: "audio/flac", convertTo: "flac"))
        #expect(PlayNowSpeakerFormat.choose(format: "opus", formats: .unknownFormats) == .init(mimeType: "audio/ogg", convertTo: nil))
    }

    @Test func theConversionGoesOnTheSignedLink() {
        let signed = URL(string: "http://192.168.0.101/v1/play-now/abc/audio?d=dev&exp=1&sig=00")!
        let converted = PlayNowSpeakerFormat.link(signed, .init(mimeType: "audio/flac", convertTo: "flac"))
        #expect(converted.absoluteString == "http://192.168.0.101/v1/play-now/abc/audio?d=dev&exp=1&sig=00&as=flac")
        #expect(PlayNowSpeakerFormat.link(signed, .init(mimeType: "audio/mpeg", convertTo: nil)) == signed)
    }
}
