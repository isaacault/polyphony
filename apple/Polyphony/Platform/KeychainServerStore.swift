import Foundation
import PolyphonyKit
import Security

/// The signed-in servers, kept in the login Keychain (they hold continuo's tokens and the library
/// passwords), as one JSON item; an item from before there were several reads as a list of one.
final class KeychainServerStore: ServerStore, @unchecked Sendable {
    private let service = "app.polyphony.mac"
    private let account = "server"
    private let lock = NSLock()

    func load() async -> ServerList {
        lock.withLock {
            var query = baseQuery
            query[kSecReturnData as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: AnyObject?
            guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else {
                return ServerList()
            }
            return ServerList.decoding(data) ?? ServerList()
        }
    }

    func save(_ list: ServerList) async {
        lock.withLock {
            SecItemDelete(baseQuery as CFDictionary)
            guard !list.servers.isEmpty, let data = try? JSONEncoder().encode(list) else { return }
            var item = baseQuery
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            item[kSecAttrLabel as String] = "Polyphony servers"
            SecItemAdd(item as CFDictionary, nil)
        }
    }

    private var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }
}
