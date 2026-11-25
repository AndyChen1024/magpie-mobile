#if os(iOS)
import CryptoKit
import Foundation
import Security

nonisolated struct PhoneIdentityStore {
    var service = "mingyuan.magpie-ios.phone-connection"

    struct Identity: Codable {
        let privateKey: Data
        var offer: PhoneOffer?
        var requiresPairing = false

        init(key: Curve25519.Signing.PrivateKey = .init()) {
            privateKey = key.rawRepresentation
        }

        func signingKey() throws -> Curve25519.Signing.PrivateKey {
            do { return try Curve25519.Signing.PrivateKey(rawRepresentation: privateKey) }
            catch { throw PhoneConnectionError.secureStorage }
        }
    }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: "device-identity"]
    }

    func read() throws -> Identity? {
        var lookup = query
        lookup[kSecReturnData as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let result = SecItemCopyMatching(lookup as CFDictionary, &item)
        if result == errSecItemNotFound { return nil }
        guard result == errSecSuccess, let bytes = item as? Data,
              let identity = try? JSONDecoder().decode(Identity.self, from: bytes)
        else { throw PhoneConnectionError.secureStorage }
        return identity
    }

    func save(_ identity: Identity) throws {
        let values = [kSecValueData as String: try JSONEncoder().encode(identity)]
        let result = SecItemUpdate(query as CFDictionary, values as CFDictionary)
        if result == errSecSuccess { return }
        guard result == errSecItemNotFound else { throw PhoneConnectionError.secureStorage }
        var entry = query.merging(values) { current, _ in current }
        entry[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        guard SecItemAdd(entry as CFDictionary, nil) == errSecSuccess else {
            throw PhoneConnectionError.secureStorage
        }
    }

    func clear() throws {
        let result = SecItemDelete(query as CFDictionary)
        guard result == errSecSuccess || result == errSecItemNotFound else {
            throw PhoneConnectionError.secureStorage
        }
    }
}
#endif
