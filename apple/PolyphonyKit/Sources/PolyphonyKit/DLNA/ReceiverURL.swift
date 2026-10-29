import Foundation

/// The URL a speaker is given for one the app built from a server's base URL (the Android app's
/// rewriteForReceiver, case for case: contract/cases/receiver.json). Speakers fetch the audio
/// themselves, and a public HTTPS address often won't do for them.
public enum ReceiverURL {
    /// [receiver]: the receiver address, which replaces the base URL outright (no scheme: http).
    /// [lanIP]: else, for plain http only, a private address the app resolved for the server's host.
    public static func rewrite(_ url: String, baseURL: String, receiver: String?, lanIP: String? = nil) -> String {
        let base = baseURL.hasSuffix("/") ? String(baseURL.dropLast()) : baseURL
        if let receiver = receiver?.trimmingCharacters(in: .whitespaces), !receiver.isEmpty {
            var target = receiver
            while target.hasSuffix("/") { target.removeLast() }
            if !target.contains("://") { target = "http://" + target }
            return url.hasPrefix(base) ? target + url.dropFirst(base.count) : url
        }
        guard let lanIP, let parts = URLComponents(string: url), let host = parts.host,
              parts.scheme == "http", !isIPLiteral(host) else { return url }
        guard let range = url.range(of: "://" + host) else { return url }
        return url.replacingCharacters(in: range, with: "://" + lanIP)
    }

    static func isIPLiteral(_ host: String) -> Bool {
        host.contains(":") || host.range(of: #"^\d{1,3}(\.\d{1,3}){3}$"#, options: .regularExpression) != nil
    }
}
