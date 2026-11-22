import Foundation
import Security

nonisolated struct UsageOrigin: Equatable, Sendable {
    let url: URL

    var address: String { url.absoluteString }

    init(_ address: String) throws {
        let input = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            !input.contains(where: { $0.isWhitespace || $0.isNewline }),
            !input.contains("\\"),
            var parts = URLComponents(string: input),
            parts.scheme?.lowercased() == "https",
            let host = parts.percentEncodedHost, !host.isEmpty, !host.contains("%"),
            parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
            ["", "/", "/v1", "/v1/"].contains(parts.percentEncodedPath),
            parts.port.map({ (1...65535).contains($0) }) ?? true
        else {
            throw UsageConnectionError.invalidAddress
        }
        parts.scheme = "https"
        parts.percentEncodedHost = host.lowercased()
        parts.path = ""
        if parts.port == 443 { parts.port = nil }
        guard let url = parts.url, url.host(percentEncoded: true) != nil else {
            throw UsageConnectionError.invalidAddress
        }
        self.url = url
    }
}

nonisolated enum UsageConnectionError: LocalizedError {
    case invalidAddress, invalidKey, secureStorage, unavailable

    var errorDescription: String? {
        switch self {
        case .invalidAddress: String(localized: "Enter a valid HTTPS gateway address. A port and /v1 are allowed. Remove any credentials, query or fragment from the address.")
        case .invalidKey: String(localized: "Enter a valid gateway key for this host.")
        case .secureStorage: String(localized: "Could not access secure storage. Unlock your device and try again.")
        case .unavailable: String(localized: "This build cannot connect to a host.")
        }
    }
}

nonisolated struct UsageCredential {
    let origin: UsageOrigin
    let key: String

    init(origin: UsageOrigin, key: String) throws {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.unicodeScalars.allSatisfy({ (33...126).contains($0.value) }) else {
            throw UsageConnectionError.invalidKey
        }
        self.origin = origin
        self.key = trimmed
    }
}

protocol UsageCredentialStore {
    func read() throws -> UsageCredential?
    func save(_ credential: UsageCredential) throws
    func clear() throws
}

struct UsageKeychain: UsageCredentialStore {
    var service = "mingyuan.magpie-ios.usage-connection"

    private var query: [String: Any] {
        var item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        #if os(macOS)
        item[kSecUseDataProtectionKeychain as String] = true
        #endif
        return item
    }

    func read() throws -> UsageCredential? {
        var lookup = query
        lookup[kSecReturnAttributes as String] = true
        lookup[kSecReturnData as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess,
              let item = result as? [String: Any],
              let address = item[kSecAttrAccount as String] as? String,
              let data = item[kSecValueData as String] as? Data,
              let key = String(data: data, encoding: .utf8),
              let origin = try? UsageOrigin(address),
              let credential = try? UsageCredential(origin: origin, key: key)
        else { throw UsageConnectionError.secureStorage }
        return credential
    }

    func save(_ credential: UsageCredential) throws {
        let values: [String: Any] = [
            kSecAttrAccount as String: credential.origin.address,
            kSecValueData as String: Data(credential.key.utf8),
        ]
        // Keep one active connection. Updating replaces both its origin and key in one item.
        let updated = SecItemUpdate(query as CFDictionary, values as CFDictionary)
        if updated == errSecSuccess { return }
        guard updated == errSecItemNotFound else { throw UsageConnectionError.secureStorage }
        var entry = query.merging(values) { _, value in value }
        entry[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        guard SecItemAdd(entry as CFDictionary, nil) == errSecSuccess else {
            throw UsageConnectionError.secureStorage
        }
    }

    func clear() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw UsageConnectionError.secureStorage
        }
    }
}
