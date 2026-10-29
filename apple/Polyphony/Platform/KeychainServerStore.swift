import Foundation
import PolyphonyKit
import Security

/// The signed-in server, kept in the login Keychain (it holds continuo's tokens and the library
/// password), as one JSON item.
final class KeychainServerStore: ServerStore, @unchecked Sendable {
    private let service = "app.polyphony.mac"
    private let account = "server"
    private let lock = NSLock()

    func load() async -> SavedServer? {
        lock.withLock {
            var query = baseQuery
            query[kSecReturnData as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: AnyObject?
            guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else {
                return nil
            }
            return try? JSONDecoder().decode(SavedServer.self, from: data)
        }
    }

    func save(_ server: SavedServer?) async {
        lock.withLock {
            SecItemDelete(baseQuery as CFDictionary)
            guard let server, let data = try? JSONEncoder().encode(server) else { return }
            var item = baseQuery
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            item[kSecAttrLabel as String] = "Polyphony: \(server.displayName)"
            SecItemAdd(item as CFDictionary, nil)
        }
    }

    private var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }
}
