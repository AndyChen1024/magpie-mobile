import CryptoKit
import Foundation

// Paseo 0.16.2 secure-channel.ts: Noise_NK_25519_ChaChaPoly_SHA256,
// with the paired Mac's X25519 public key as the responder pre-message.
struct PaseoNoiseInitiator {
    private let ephemeral: Curve25519.KeyAgreement.PrivateKey
    private var transcript: Data
    private var chainingKey: Data
    private(set) var hello = Data()

    init(macKey: Data, ephemeral: Curve25519.KeyAgreement.PrivateKey = .init()) throws {
        self.ephemeral = ephemeral
        let name = Data("Noise_NK_25519_ChaChaPoly_SHA256".utf8)
        transcript = name + Data(repeating: 0, count: 32 - name.count)
        chainingKey = transcript

        mixHash(Data("paseo-relay-e2ee/1".utf8))
        mixHash(macKey)
        let publicKey = ephemeral.publicKey.rawRepresentation
        mixHash(publicKey)
        let handshakeKey = try mixKey(with: macKey)
        // Version 1 parameters, offering binary WebSocket ciphertext.
        let encrypted = try PaseoNoiseCipher(key: handshakeKey).seal(Data([1, 1]), counter: 0, ad: transcript)
        mixHash(encrypted)
        self.hello = publicKey + encrypted
    }

    mutating func finish(_ ready: Data) throws -> PaseoProtectedRecords {
        guard ready.count == 50 else { throw PhoneConnectionError.invalidChannel }
        let peerEphemeral = Data(ready.prefix(32))
        mixHash(peerEphemeral)
        let handshakeKey = try mixKey(with: peerEphemeral)
        let parameters = try PaseoNoiseCipher(key: handshakeKey).open(Data(ready.dropFirst(32)), counter: 0, ad: transcript)
        guard parameters == Data([1, 1]) || parameters == Data([1, 0]) else {
            throw PhoneConnectionError.invalidChannel
        }
        let (send, receive) = deriveKeys(from: SymmetricKey(data: Data()))
        return PaseoProtectedRecords(sendKey: SymmetricKey(data: send), receiveKey: SymmetricKey(data: receive),
                                     binary: parameters[1] == 1)
    }

    private mutating func mixHash(_ bytes: Data) {
        transcript = Data(SHA256.hash(data: transcript + bytes))
    }

    private mutating func mixKey(with remote: Data) throws -> SymmetricKey {
        let peer = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: remote)
        let secret = try ephemeral.sharedSecretFromKeyAgreement(with: peer)
        let input = secret.withUnsafeBytes { Data($0) }
        guard input.contains(where: { $0 != 0 }) else { throw PhoneConnectionError.invalidChannel }
        let (next, key) = deriveKeys(from: SymmetricKey(data: input))
        chainingKey = next
        return SymmetricKey(data: key)
    }

    private func deriveKeys(from input: SymmetricKey) -> (Data, Data) {
        let keys = HKDF<SHA256>.deriveKey(inputKeyMaterial: input, salt: chainingKey,
                                          info: Data(), outputByteCount: 64)
        let bytes = keys.withUnsafeBytes { Data($0) }
        return (Data(bytes.prefix(32)), Data(bytes.suffix(32)))
    }
}

private struct PaseoNoiseCipher {
    let key: SymmetricKey

    func seal(_ bytes: Data, counter: UInt64, ad: Data = Data()) throws -> Data {
        let sealed = try ChaChaPoly.seal(bytes, using: key, nonce: nonce(counter), authenticating: ad)
        return sealed.ciphertext + sealed.tag
    }

    func open(_ bytes: Data, counter: UInt64, ad: Data = Data()) throws -> Data {
        let sealed = try ChaChaPoly.SealedBox(nonce: nonce(counter), ciphertext: bytes.dropLast(16), tag: bytes.suffix(16))
        return try ChaChaPoly.open(sealed, using: key, authenticating: ad)
    }

    private func nonce(_ counter: UInt64) throws -> ChaChaPoly.Nonce {
        guard counter < UInt64.max else { throw PhoneConnectionError.invalidChannel }
        var littleEndian = counter.littleEndian
        return try ChaChaPoly.Nonce(data: Data(repeating: 0, count: 4) + withUnsafeBytes(of: &littleEndian) { Data($0) })
    }
}

struct PaseoProtectedRecords {
    // The Mac companion caps WebSocket messages at 8 KiB. Its JSON protocol
    // fits in one Noise chunk, so require a complete FIRST | FINAL text record.
    static let maxWireBytes = 8192
    private let sender: PaseoNoiseCipher
    private let receiver: PaseoNoiseCipher
    private var sendCounter: UInt64 = 0
    private var receiveCounter: UInt64 = 0
    let binary: Bool

    init(sendKey: SymmetricKey, receiveKey: SymmetricKey, binary: Bool) {
        sender = PaseoNoiseCipher(key: sendKey)
        receiver = PaseoNoiseCipher(key: receiveKey)
        self.binary = binary
    }

    mutating func encode(_ json: Data) throws -> Data {
        guard json.count <= Self.maxWireBytes - 24 else { throw PhoneConnectionError.invalidChannel }
        var size = UInt32(json.count).bigEndian
        let header = Data([0, 3]) + withUnsafeBytes(of: &size) { Data($0) }
        let result = try sender.seal(header + json, counter: sendCounter)
        sendCounter += 1
        var encryptedSize = UInt16(result.count).bigEndian
        return withUnsafeBytes(of: &encryptedSize) { Data($0) } + result
    }

    mutating func decode(_ record: Data) throws -> Data {
        guard (24...Self.maxWireBytes).contains(record.count),
              record.prefix(2).reduce(0, { (Int($0) << 8) | Int($1) }) == record.count - 2
        else { throw PhoneConnectionError.invalidChannel }
        let plaintext = try receiver.open(Data(record.dropFirst(2)), counter: receiveCounter)
        guard plaintext.prefix(2) == Data([0, 3]),
              plaintext.dropFirst(2).prefix(4).reduce(0, { (Int($0) << 8) | Int($1) }) == plaintext.count - 6
        else { throw PhoneConnectionError.invalidChannel }
        receiveCounter += 1
        return Data(plaintext.dropFirst(6))
    }
}
