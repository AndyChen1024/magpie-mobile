import CryptoKit
import Foundation

nonisolated enum PhoneConnectionError: Error, Equatable {
    case invalidLink, tlsRequired, invalidPairing, unreachable
    case invalidChannel, invalidResponse, notAuthorized, deviceLimit, secureStorage
}

nonisolated struct PhoneOffer: Codable, Equatable {
    struct Relay: Codable, Equatable {
        let endpoint: String
        let useTls: Bool?
    }

    let v: Int
    let serverId: String
    let daemonPublicKeyB64: String
    let relay: Relay
    let minimumSecurityMode: String

    func macKey() throws -> Data {
        guard v == 2, !serverId.isEmpty, minimumSecurityMode == "protected",
              let key = Data(base64Encoded: daemonPublicKeyB64), key.count == 32
        else { throw PhoneConnectionError.invalidLink }
        return key
    }

    func relayURL() throws -> URL {
        _ = try macKey()
        let tls = relay.useTls ?? false
        guard let authority = URLComponents(string: "\(tls ? "wss" : "ws")://\(relay.endpoint)"),
              let host = authority.host, !host.isEmpty,
              let port = authority.port, (1...65535).contains(port),
              authority.user == nil, authority.password == nil, authority.path.isEmpty,
              authority.query == nil, authority.fragment == nil
        else { throw PhoneConnectionError.invalidLink }
        if !tls {
            #if DEBUG
            guard Self.isLocalTestRelay(host) else { throw PhoneConnectionError.tlsRequired }
            #else
            throw PhoneConnectionError.tlsRequired
            #endif
        }
        var url = authority
        url.path = "/ws"
        url.queryItems = [URLQueryItem(name: "serverId", value: serverId), URLQueryItem(name: "role", value: "client"),
                          URLQueryItem(name: "v", value: "2")]
        guard let value = url.url else { throw PhoneConnectionError.invalidLink }
        return value
    }

    #if DEBUG
    private static func isLocalTestRelay(_ host: String) -> Bool {
        if host == "[::1]" { return true }
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        let octets = parts.compactMap { part -> UInt8? in
            guard let value = UInt8(part), String(value) == part else { return nil }
            return value
        }
        guard octets.count == 4, parts.count == 4 else { return false }
        return octets[0] == 127 || octets[0] == 10 || (octets[0] == 192 && octets[1] == 168)
            || (octets[0] == 172 && (16...31).contains(octets[1]))
    }
    #endif
}

nonisolated struct PhonePairingLink {
    let offer: PhoneOffer
    let ticket: String

    init(_ link: String) throws {
        let input = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard input.utf8.count <= 8192, let url = URLComponents(string: input),
              url.scheme == "magpie-mobile", url.host == "pair", url.path.isEmpty,
              url.user == nil, url.password == nil, url.port == nil, url.query == nil,
              let fragment = url.percentEncodedFragment
        else { throw PhoneConnectionError.invalidLink }
        var fields = URLComponents()
        fields.percentEncodedQuery = fragment
        let items = fields.queryItems ?? []
        let offers = items.filter { $0.name == "offer" }
        let tickets = items.filter { $0.name == "ticket" }
        guard offers.count == 1, tickets.count == 1,
              let encoded = offers.first?.value, let bytes = Data(canonicalBase64URL: encoded),
              let parsed = try? JSONDecoder().decode(PhoneOffer.self, from: bytes),
              let ticket = tickets.first?.value, Data(canonicalBase64URL: ticket)?.count == 32
        else { throw PhoneConnectionError.invalidLink }
        _ = try parsed.relayURL()
        self.offer = parsed
        self.ticket = ticket
    }
}

nonisolated extension Data {
    var base64URL: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    init?(canonicalBase64URL value: String) {
        guard !value.isEmpty else { return nil }
        let standard = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let padded = standard + String(repeating: "=", count: (4 - standard.utf8.count % 4) % 4)
        guard let data = Data(base64Encoded: padded), data.base64URL == value else { return nil }
        self = data
    }
}

nonisolated struct PhoneChallenge: Decodable {
    let v: Int
    let type: String
    let serverId: String
    let nonce: String

    func validate(for offer: PhoneOffer) throws {
        guard v == 1, type == "challenge", serverId == offer.serverId,
              Data(canonicalBase64URL: nonce)?.count == 32
        else { throw PhoneConnectionError.invalidResponse }
    }
}

nonisolated struct PhoneRequest: Encodable {
    let v = 1
    let id = UUID().uuidString.lowercased()
    let type: String
    var publicKey: String?
    var signature: String?
    var ticket: String?
    var label: String?

    static func authorization(for challenge: PhoneChallenge, identity: Curve25519.Signing.PrivateKey,
                              ticket: String?, label: String) throws -> Self {
        let operation = ticket == nil ? "device.auth" : "device.pair"
        let key = identity.publicKey.rawRepresentation.base64URL
        let proof = ["magpie-connect-phone/v1", challenge.serverId, challenge.nonce, operation, key, ticket ?? ""]
            .joined(separator: "\n")
        let signature = try identity.signature(for: Data(proof.utf8)).base64URL
        return Self(type: operation, publicKey: key, signature: signature, ticket: ticket,
                    label: ticket == nil ? nil : label)
    }
}

nonisolated struct PhoneResponse: Decodable {
    let v: Int
    let type: String
    let id: String
    let error: String?
    let deviceId: String?
    let hostId: String?
    let displayName: String?
    let receipt: String?

    func validate(for request: PhoneRequest) throws {
        guard v == 1, type == "result", id == request.id else { throw PhoneConnectionError.invalidResponse }
        switch error {
        case nil: return
        case "device_not_authorized": throw PhoneConnectionError.notAuthorized
        case "invalid_pairing": throw PhoneConnectionError.invalidPairing
        case "device_limit": throw PhoneConnectionError.deviceLimit
        default: throw PhoneConnectionError.invalidResponse
        }
    }

    func validateDevice(_ identity: Curve25519.Signing.PrivateKey) throws {
        let expected = Data(SHA256.hash(data: identity.publicKey.rawRepresentation)).base64URL
        guard deviceId == expected else { throw PhoneConnectionError.invalidResponse }
    }

    func host(for offer: PhoneOffer) throws -> PhoneHostReceipt {
        guard hostId == offer.serverId, let displayName, !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let receipt, let uuid = UUID(uuidString: receipt)
        else { throw PhoneConnectionError.invalidResponse }
        return PhoneHostReceipt(requestID: id, hostID: offer.serverId, displayName: displayName, receipt: uuid)
    }
}

nonisolated struct PhoneHostReceipt {
    let requestID: String
    let hostID: String
    let displayName: String
    let receipt: UUID
}
