import Foundation
@testable import PolyphonyKit

/// Answers requests from a script, and remembers what was asked.
final class FakeTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var handler: (HTTPRequest) -> HTTPResponse
    private(set) var requests: [HTTPRequest] = []

    init(_ handler: @escaping (HTTPRequest) -> HTTPResponse) {
        self.handler = handler
    }

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        let h = lock.withLock {
            requests.append(request)
            return handler
        }
        return h(request)
    }

    var calls: [HTTPRequest] {
        lock.withLock { requests }
    }
}

func fixture(_ name: String) -> Data {
    let url = Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures")!
    return try! Data(contentsOf: url)
}

func query(_ url: URL) -> [String: String] {
    let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
    return Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { $1 })
}

let credentials = SubsonicCredentials(baseURL: URL(string: "https://continuo.ault.me")!, username: "isaac",
                                      password: "device-secret", salt: "0123456789abcdef")
