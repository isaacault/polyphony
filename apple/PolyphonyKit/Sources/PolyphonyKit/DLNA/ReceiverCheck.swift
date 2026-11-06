import Foundation

/// Settings > Servers > Test receiver address: does a song's link, at that address, serve audio? The
/// Android app's receiver check: it asks for two bytes of a real song, as a speaker would.
public enum ReceiverCheck {
    public enum Outcome: Sendable, Equatable {
        /// Audio came back: speakers can play from here.
        case audioServed(status: Int, ms: Int)
        /// Something's there, but it isn't serving this library's audio.
        case hostAnswered(status: Int)
        case unreachable(String)

        public var message: String {
            switch self {
            case let .audioServed(status, ms): "Reachable: audio served (HTTP \(status), \(ms) ms)."
            case let .hostAnswered(status): "The host answered (HTTP \(status)), but not with audio. Is it the right address?"
            case let .unreachable(detail): "Not reachable: \(detail)"
            }
        }
    }

    public static func run(address: String, library: any MusicLibrary, baseURL: URL, transport: HTTPTransport,
                           now: @Sendable () -> Date = Date.init) async -> Outcome {
        let song: Track
        do {
            guard let first = try await library.songs(.random, size: 1).first else { return .unreachable("the library has no songs to try") }
            song = first
        } catch {
            return .unreachable(ErrorText.of(error))
        }
        let link = library.streamURL(trackID: song.id, choice: StreamChoice())
        guard let url = URL(string: ReceiverURL.rewrite(link.absoluteString, baseURL: baseURL.absoluteString, receiver: address)) else {
            return .unreachable("that doesn't look like an address")
        }
        let started = now()
        do {
            let response = try await transport.send(HTTPRequest(url: url, headers: ["Range": "bytes=0-1"]))
            let ms = Int(now().timeIntervalSince(started) * 1000)
            let type = response.headers.first { $0.key.lowercased() == "content-type" }?.value.lowercased() ?? ""
            let isDocument = type.contains("xml") || type.contains("json") || type.contains("html")
            return (200..<300).contains(response.status) && !isDocument ? .audioServed(status: response.status, ms: ms)
                                                                        : .hostAnswered(status: response.status)
        } catch {
            return .unreachable(ErrorText.of(error))
        }
    }
}
