import CryptoKit
import Foundation

@MainActor
final class PhoneRelay {
    private let socket: PhoneRelaySocket
    private let offer: PhoneOffer
    private let challenge: PhoneChallenge
    private var records: PaseoProtectedRecords

    var onDisconnect: (() -> Void)? {
        get { socket.onDisconnect }
        set { socket.onDisconnect = newValue }
    }

    private init(socket: PhoneRelaySocket, offer: PhoneOffer, challenge: PhoneChallenge, records: PaseoProtectedRecords) {
        self.socket = socket
        self.offer = offer
        self.challenge = challenge
        self.records = records
    }

    nonisolated deinit {}

    static func connect(to offer: PhoneOffer) async throws -> PhoneRelay {
        let socket = PhoneRelaySocket(url: try offer.relayURL())
        do {
            return try await socket.withDeadline(seconds: 12) {
                var noise = try protectedOperation { try PaseoNoiseInitiator(macKey: offer.macKey()) }
                // The compatibility envelope still carries an independent X25519 public key.
                let compatibilityKey = Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
                let hello = Handshake(type: "e2ee_hello", key: compatibilityKey.base64EncodedString(),
                                      protected: .init(v: 1, message: noise.hello.base64EncodedString()),
                                      capabilities: .init(binaryCiphertext: true))
                let helloText = String(decoding: try JSONEncoder().encode(hello), as: UTF8.self)
                let ready = try await receiveReady(using: socket, hello: helloText)
                guard let envelope = ready.protected, envelope.v == 1,
                      let message = Data(base64Encoded: envelope.message) else { throw PhoneConnectionError.invalidChannel }
                var records = try protectedOperation { try noise.finish(message) }
                let payload = try await receiveRecord(using: socket, records: &records)
                let challenge = try decode(PhoneChallenge.self, from: payload)
                try challenge.validate(for: offer)
                return PhoneRelay(socket: socket, offer: offer, challenge: challenge, records: records)
            }
        } catch {
            socket.close()
            throw error
        }
    }

    func authorize(identity: Curve25519.Signing.PrivateKey, ticket: String? = nil, label: String = "iPhone") async throws {
        let request = try PhoneRequest.authorization(for: challenge, identity: identity, ticket: ticket, label: label)
        let result = try await send(request)
        try result.validateDevice(identity)
    }

    func readHost() async throws -> PhoneHostReceipt {
        try await send(PhoneRequest(type: "host.get")).host(for: offer)
    }

    func monitor() async throws {
        while true {
            try await Task.sleep(nanoseconds: 10_000_000_000)
            try await socket.withDeadline(seconds: 5) { try await socket.ping() }
        }
    }

    func close() { socket.close() }

    private func send(_ request: PhoneRequest) async throws -> PhoneResponse {
        try await socket.withDeadline(seconds: 8) {
            let record = try Self.protectedOperation { try records.encode(JSONEncoder().encode(request)) }
            let frame: URLSessionWebSocketTask.Message = records.binary ? .data(record) : .string(record.base64EncodedString())
            try await socket.send(frame)
            var readRecords = records
            let payload = try await Self.receiveRecord(using: socket, records: &readRecords)
            records = readRecords
            let result = try Self.decode(PhoneResponse.self, from: payload)
            try result.validate(for: request)
            return result
        }
    }

    private static func receiveReady(using socket: PhoneRelaySocket, hello: String) async throws -> Handshake {
        try await socket.send(.string(hello))
        // Relay v2 may open the Mac peer socket after the first client hello.
        // Re-send the exact transcript until the responder acknowledges it.
        let resend = Task {
            do {
                while !Task.isCancelled {
                    try await Task.sleep(nanoseconds: 1_000_000_000)
                    try await socket.send(.string(hello))
                }
            } catch {
                if !Task.isCancelled { socket.close() }
            }
        }
        defer { resend.cancel() }
        while true {
            guard case .string(let text) = try await socket.receive(),
                  let ready = try? JSONDecoder().decode(Handshake.self, from: Data(text.utf8)),
                  ready.type == "e2ee_ready" else { continue }
            resend.cancel()
            await resend.value
            return ready
        }
    }

    private static func receiveRecord(using socket: PhoneRelaySocket, records: inout PaseoProtectedRecords) async throws -> Data {
        while true {
            let wire: Data
            switch try await socket.receive() {
            case .data(let data): wire = data
            case .string(let text):
                if let handshake = try? JSONDecoder().decode(Handshake.self, from: Data(text.utf8)),
                   handshake.type == "e2ee_ready" { continue }
                guard let decoded = Data(base64Encoded: text) else { throw PhoneConnectionError.invalidChannel }
                wire = decoded
            @unknown default: throw PhoneConnectionError.invalidChannel
            }
            return try protectedOperation { try records.decode(wire) }
        }
    }

    private static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do { return try JSONDecoder().decode(type, from: data) }
        catch { throw PhoneConnectionError.invalidResponse }
    }

    private static func protectedOperation<T>(_ operation: () throws -> T) throws -> T {
        do { return try operation() }
        catch { throw PhoneConnectionError.invalidChannel }
    }

    private struct Handshake: Codable {
        struct Envelope: Codable { let v: Int; let message: String }
        struct Capabilities: Codable { let binaryCiphertext: Bool }
        let type: String
        let key: String?
        let protected: Envelope?
        let capabilities: Capabilities?
    }
}

@MainActor
private final class PhoneRelaySocket {
    typealias Frame = URLSessionWebSocketTask.Message
    private let task: URLSessionWebSocketTask
    private let session: URLSession
    private let continuation: AsyncThrowingStream<Frame, Error>.Continuation
    private var frames: AsyncThrowingStream<Frame, Error>.Iterator
    private var pump: Task<Void, Never>?
    private var isClosed = false
    var onDisconnect: (() -> Void)?

    init(url: URL) {
        let stream = AsyncThrowingStream<Frame, Error>.makeStream(bufferingPolicy: .bufferingOldest(8))
        continuation = stream.continuation
        frames = stream.stream.makeAsyncIterator()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.waitsForConnectivity = true
        session = URLSession(configuration: configuration)
        let task = session.webSocketTask(with: url)
        self.task = task
        task.maximumMessageSize = PaseoProtectedRecords.maxWireBytes
        task.resume()
        pump = Task { [weak self, task, continuation] in
            do {
                while !Task.isCancelled {
                    let frame = try await task.receive()
                    if case .dropped = continuation.yield(frame) { throw PhoneConnectionError.invalidChannel }
                }
            } catch {
                guard let self, !self.isClosed else { return }
                self.close(error: Self.networkError(error))
                self.onDisconnect?()
            }
        }
    }

    nonisolated deinit {
        continuation.finish()
        pump?.cancel()
        session.invalidateAndCancel()
    }

    func withDeadline<T>(seconds: UInt64, operation: () async throws -> T) async throws -> T {
        let timer = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: seconds * 1_000_000_000) }
            catch { return }
            self?.close()
        }
        defer { timer.cancel() }
        let value = try await operation()
        try Task.checkCancellation()
        guard !isClosed else { throw PhoneConnectionError.unreachable }
        return value
    }

    func send(_ frame: Frame) async throws {
        do { try await task.send(frame) }
        catch { throw Self.networkError(error) }
    }

    func receive() async throws -> Frame {
        var iterator = frames
        let frame = try await iterator.next()
        frames = iterator
        guard let frame else { throw PhoneConnectionError.unreachable }
        return frame
    }

    func ping() async throws {
        try await withCheckedThrowingContinuation { (reply: CheckedContinuation<Void, Error>) in
            task.sendPing { error in
                if error != nil { reply.resume(throwing: PhoneConnectionError.unreachable) }
                else { reply.resume() }
            }
        }
    }

    func close(error: PhoneConnectionError = .unreachable) {
        guard !isClosed else { return }
        isClosed = true
        continuation.finish(throwing: error)
        task.cancel(with: .goingAway, reason: nil)
        session.invalidateAndCancel()
        pump?.cancel()
        pump = nil
    }

    private static func networkError(_ error: Error) -> PhoneConnectionError {
        if let failure = error as? PhoneConnectionError { return failure }
        if (error as? URLError)?.code == .appTransportSecurityRequiresSecureConnection { return .tlsRequired }
        return .unreachable
    }
}
