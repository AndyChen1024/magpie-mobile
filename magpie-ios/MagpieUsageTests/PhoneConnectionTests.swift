import CryptoKit
import Foundation
import XCTest
@testable import magpie_ios

final class PhoneConnectionTests: XCTestCase {
    func testPairingRequiresProtectedOfferAndCanonicalTicket() throws {
        let offer = offerForTest()
        let encoded = try JSONEncoder().encode(offer).base64URL
        let ticket = Data(repeating: 2, count: 32).base64URL
        let link = "magpie-mobile://pair#offer=\(encoded)&ticket=\(ticket)"
        let decoded = try PhonePairingLink("\n\(link)\n")
        XCTAssertEqual(decoded.offer, offer)
        XCTAssertTrue(decoded.ticket == ticket)
        let url = try decoded.offer.relayURL()
        XCTAssertEqual(url.scheme, "wss")
        XCTAssertEqual(url.path, "/ws")
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
        XCTAssertEqual(query, [.init(name: "serverId", value: offer.serverId), .init(name: "role", value: "client"),
                               .init(name: "v", value: "2")])
        for invalid in [link + "=", link + "&ticket=\(ticket)", link.replacingOccurrences(of: "://pair#", with: "://pair/?#")] {
            XCTAssertThrowsError(try PhonePairingLink(invalid)) { XCTAssertEqual($0 as? PhoneConnectionError, .invalidLink) }
        }
        let legacy = PhoneOffer(v: offer.v, serverId: offer.serverId, daemonPublicKeyB64: offer.daemonPublicKeyB64,
                                relay: offer.relay, minimumSecurityMode: "legacy")
        XCTAssertThrowsError(try legacy.relayURL())
        XCTAssertThrowsError(try offerForTest(endpoint: "relay.example:8080", useTLS: false).relayURL()) {
            XCTAssertEqual($0 as? PhoneConnectionError, .tlsRequired)
        }
    }

    func testAuthorizationProofIncludesFinalLineAndRejectsWrongHostChallenge() throws {
        let offer = offerForTest()
        let nonce = Data(repeating: 7, count: 32).base64URL
        let challenge = PhoneChallenge(v: 1, type: "challenge", serverId: offer.serverId, nonce: nonce)
        try challenge.validate(for: offer)
        XCTAssertThrowsError(try challenge.validate(for: PhoneOffer(v: 2, serverId: "other", daemonPublicKeyB64: offer.daemonPublicKeyB64,
                                                                     relay: offer.relay, minimumSecurityMode: "protected")))
        let key = Curve25519.Signing.PrivateKey()
        let publicKey = key.publicKey.rawRepresentation.base64URL
        let auth = try PhoneRequest.authorization(for: challenge, identity: key, ticket: nil, label: "ignored")
        XCTAssertEqual(auth.type, "device.auth")
        XCTAssertEqual(auth.publicKey, publicKey)
        XCTAssertNil(auth.ticket)
        XCTAssertNil(auth.label)
        let authSignature = try XCTUnwrap(auth.signature.flatMap(Data.init(canonicalBase64URL:)))
        let authProof = "magpie-connect-phone/v1\n\(offer.serverId)\n\(nonce)\ndevice.auth\n\(publicKey)\n"
        XCTAssertTrue(key.publicKey.isValidSignature(authSignature, for: Data(authProof.utf8)))
        let ticket = Data(repeating: 6, count: 32).base64URL
        let pair = try PhoneRequest.authorization(for: challenge, identity: key, ticket: ticket, label: "iPhone")
        XCTAssertEqual(pair.type, "device.pair")
        XCTAssertEqual(pair.label, "iPhone")
        let pairSignature = try XCTUnwrap(pair.signature.flatMap(Data.init(canonicalBase64URL:)))
        let pairProof = "magpie-connect-phone/v1\n\(offer.serverId)\n\(nonce)\ndevice.pair\n\(publicKey)\n\(ticket)"
        XCTAssertTrue(key.publicKey.isValidSignature(pairSignature, for: Data(pairProof.utf8)))
    }

    func testOnlyMatchingResultsAndValidMacReceiptsAreAccepted() throws {
        let offer = offerForTest()
        let request = PhoneRequest(type: "host.get")
        XCTAssertNotEqual(request.id, PhoneRequest(type: "host.get").id)
        let receipt = UUID()
        func response(id: String = request.id, host: String = offer.serverId, result: String? = nil,
                      receipt: String? = receipt.uuidString) -> PhoneResponse {
            PhoneResponse(v: 1, type: "result", id: id, error: result, deviceId: nil, hostId: host,
                          displayName: "Example Mac", receipt: receipt)
        }
        let result = response()
        try result.validate(for: request)
        XCTAssertEqual(try result.host(for: offer).receipt, receipt)
        XCTAssertThrowsError(try response(id: "other").validate(for: request)) {
            XCTAssertEqual($0 as? PhoneConnectionError, .invalidResponse)
        }
        XCTAssertThrowsError(try response(host: "other").host(for: offer))
        XCTAssertThrowsError(try response(receipt: nil).host(for: offer))
        XCTAssertThrowsError(try response(receipt: "broken").host(for: offer))
        XCTAssertThrowsError(try response(result: "device_not_authorized").validate(for: request)) {
            XCTAssertEqual($0 as? PhoneConnectionError, .notAuthorized)
        }
        XCTAssertThrowsError(try response(result: "invalid_pairing").validate(for: request)) {
            XCTAssertEqual($0 as? PhoneConnectionError, .invalidPairing)
        }
    }

    @MainActor
    func testHandshakeAndRecordBytesMatchPaseo0162() throws {
        // Generated with the 0.16.2 Noise responder and protected-record encoder using synthetic test inputs.
        let mac = try fixture("zo060cy2M+x7cMF4FKXHbs0CloUFDTRHRboFhw5YfVk=")
        let ephemeral = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 1, count: 32))
        var initiator = try PaseoNoiseInitiator(macKey: mac, ephemeral: ephemeral)
        XCTAssertEqual(initiator.hello.base64EncodedString(), "pOCSkrZRwni5dyxWn1+puxPZBrRqtoyd+dwrRAn4ognZBh7Xo01aAMR26NCdd3Mk/5E=")
        let ready = try fixture("Xf7dO2vUf2+ijuFdlp1bsOpTd01Ii9r53xxuASSz7yKVbcvaqavhYV4wTiHG+M+HuKM=")
        var records = try initiator.finish(ready)
        XCTAssertTrue(records.binary)
        XCTAssertEqual(try records.encode(Data("request".utf8)).base64EncodedString(), "AB0t+hn00qMUOWiPkobkPDHKU4HZZPzNpobV0g33ug==")
        XCTAssertEqual(try records.encode(Data("another request".utf8)).base64EncodedString(), "ACUkSqeHFZhlVR7mzjxS5HtENpjd1nn8eXFxVS2qyTW+RJZCMPCD")
        let first = try fixture("AB5wUusRPUuwONb+Wb13Iq07oqOhmmuHWrLbi/Z0QKY=")
        let second = try fixture("ACZnKXKDgpOyT3N1n03pXGrfamFnEkGsDGiXRDkCzTCSVO5W7oHJ0g==")
        XCTAssertEqual(try records.decode(first), Data("response".utf8))
        XCTAssertThrowsError(try records.decode(first))
        var tampered = second
        tampered[tampered.count - 1] ^= 1
        XCTAssertThrowsError(try records.decode(tampered))
        XCTAssertEqual(try records.decode(second), Data("another response".utf8))

        var wrongMac = try PaseoNoiseInitiator(macKey: Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation,
                                               ephemeral: ephemeral)
        XCTAssertThrowsError(try wrongMac.finish(ready))
    }

    @MainActor
    func testStoredIdentityRestoresItsKeyOfferAndRevocation() throws {
        let store = PhoneIdentityStore(service: "tests.phone.\(UUID())")
        defer { try? store.clear() }
        let key = Curve25519.Signing.PrivateKey()
        var identity = PhoneIdentityStore.Identity(key: key)
        identity.offer = offerForTest()
        try store.save(identity)
        let restored = try XCTUnwrap(store.read())
        XCTAssertEqual(restored.offer, identity.offer)
        XCTAssertFalse(restored.requiresPairing)
        XCTAssertTrue(try restored.signingKey().publicKey.rawRepresentation == key.publicKey.rawRepresentation)
        identity.requiresPairing = true
        try store.save(identity)
        XCTAssertTrue(try store.read()?.requiresPairing == true)
        let workspace = PhoneWorkspace(store: store)
        workspace.foregrounded()
        XCTAssertEqual(workspace.status, .authorizationExpired)
        workspace.reconnect()
        XCTAssertEqual(workspace.status, .authorizationExpired)
    }

    private func offerForTest(endpoint: String = "relay.example:443", useTLS: Bool = true) -> PhoneOffer {
        PhoneOffer(v: 2, serverId: "test-mac", daemonPublicKeyB64: Data(repeating: 5, count: 32).base64EncodedString(),
                   relay: .init(endpoint: endpoint, useTls: useTLS), minimumSecurityMode: "protected")
    }

    private func fixture(_ base64: String) throws -> Data {
        try XCTUnwrap(Data(base64Encoded: base64))
    }
}
