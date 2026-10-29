import Foundation

/// Where a Subsonic server is and how to sign its requests. With continuo, the credentials are the
/// per-device ones continuo hands out at sign-in (`ContinuoSession.library`), not the Navidrome password.
public struct SubsonicCredentials: Sendable, Hashable {
    public var baseURL: URL
    public var username: String
    public var password: String
    /// Kept per server and reused, so stream and cover URLs stay the same across calls and launches:
    /// the image and audio caches key on the URL, and a fresh salt per request would miss every time.
    public var salt: String
    /// Plain `p=enc:` auth, for old servers without token support.
    public var legacyAuth: Bool

    public init(baseURL: URL, username: String, password: String, salt: String = SubsonicCredentials.newSalt(),
                legacyAuth: Bool = false) {
        self.baseURL = baseURL
        self.username = username
        self.password = password
        self.salt = salt
        self.legacyAuth = legacyAuth
    }

    public static func newSalt() -> String {
        (0..<16).map { _ in String(format: "%x", Int.random(in: 0..<16)) }.joined()
    }
}

public enum ServerAddress {
    public enum Invalid: Error, Sendable { case address(String) }

    /// Accepts what people type: adds `https://` when the scheme is missing, drops trailing slashes and
    /// a trailing `/rest`.
    public static func normalise(_ input: String) throws -> URL {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.lowercased().hasPrefix("http://") && !text.lowercased().hasPrefix("https://") {
            text = "https://" + text
        }
        while text.hasSuffix("/") { text.removeLast() }
        if text.lowercased().hasSuffix("/rest") { text.removeLast(5) }
        while text.hasSuffix("/") { text.removeLast() }
        guard let url = URL(string: text), let host = url.host, !host.isEmpty, !host.contains(" ") else {
            throw Invalid.address(input)
        }
        return url
    }
}

/// Builds `/rest/<endpoint>` URLs with the standard Subsonic parameters.
struct SubsonicURLBuilder: Sendable {
    static let apiVersion = "1.16.1"
    static let clientName = "Polyphony"

    let credentials: SubsonicCredentials

    func url(_ endpoint: String, _ params: [(String, String)] = []) -> URL {
        var components = URLComponents(url: credentials.baseURL.appendingPathComponent("rest/\(endpoint)"),
                                       resolvingAgainstBaseURL: false)!
        var items = [URLQueryItem(name: "u", value: credentials.username)]
        if credentials.legacyAuth {
            let hex = Array(credentials.password.utf8).map { String(format: "%02x", $0) }.joined()
            items.append(URLQueryItem(name: "p", value: "enc:" + hex))
        } else {
            items.append(URLQueryItem(name: "t", value: MD5.hex(credentials.password + credentials.salt)))
            items.append(URLQueryItem(name: "s", value: credentials.salt))
        }
        items += [
            URLQueryItem(name: "v", value: Self.apiVersion),
            URLQueryItem(name: "c", value: Self.clientName),
            URLQueryItem(name: "f", value: "json"),
        ]
        items += params.map { URLQueryItem(name: $0.0, value: $0.1) }
        components.queryItems = items
        // URLComponents leaves "+" alone, which servers read as a space (search "C+C Music Factory").
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return components.url!
    }
}
