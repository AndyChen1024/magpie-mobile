#if os(iOS)
import Combine
import Foundation
import UIKit

@MainActor
final class PhoneWorkspace: ObservableObject {
    enum Status: Equatable {
        case unpaired, connecting, connected, disconnected, authorizationExpired
    }

    @Published private(set) var status: Status = .unpaired
    @Published private(set) var host: PhoneHostReceipt?
    @Published private(set) var failure: PhoneConnectionError?
    @Published private(set) var isReading = false

    private let store: PhoneIdentityStore
    private var identity: PhoneIdentityStore.Identity?
    private var relay: PhoneRelay?
    private var operation: Task<Void, Never>?
    private var retry: Task<Void, Never>?
    private var monitor: Task<Void, Never>?
    private var attempt = UUID()
    private var retrySeconds: UInt64 = 1
    private var active = false
    private var pendingTicket: String?

    init(store: PhoneIdentityStore = .init()) {
        self.store = store
    }

    // Avoid the back-deployed isolated deinitializer on older iOS runtimes.
    nonisolated deinit {
        operation?.cancel()
        retry?.cancel()
        monitor?.cancel()
    }

    func foregrounded() {
        guard !active else { return }
        active = true
        do {
            if identity == nil { identity = try store.read() }
            if identity?.requiresPairing == true {
                status = .authorizationExpired
            } else if let offer = identity?.offer {
                connect(to: offer)
            }
        } catch {
            failure = .secureStorage
        }
    }

    func backgrounded() {
        active = false
        stopConnection()
        if identity?.requiresPairing == true {
            status = .authorizationExpired
        } else {
            status = identity?.offer == nil ? .unpaired : .disconnected
        }
    }

    func pair(using link: String) {
        guard status == .unpaired || status == .authorizationExpired else { return }
        do {
            let pairing = try PhonePairingLink(link)
            if identity == nil {
                // Reuse the key if the pairing reply is lost after Mac grants it.
                // A replacement ticket for the same Mac then updates one device.
                if let saved = try store.read() {
                    identity = saved
                } else {
                    let newIdentity = PhoneIdentityStore.Identity()
                    try store.save(newIdentity)
                    identity = newIdentity
                }
            }
            connect(to: pairing.offer, ticket: pairing.ticket)
        } catch {
            failure = error as? PhoneConnectionError ?? .secureStorage
        }
    }

    func reconnect() {
        guard status == .disconnected, let offer = identity?.offer, identity?.requiresPairing == false else { return }
        connect(to: offer)
    }

    func readHost() {
        guard status == .connected, operation == nil, let relay else { return }
        isReading = true
        let current = attempt
        operation = Task { [weak self] in
            guard let self else { return }
            do {
                let receipt = try await relay.readHost()
                guard current == attempt else { return }
                try updateHost(receipt)
                operation = nil
                isReading = false
            } catch {
                guard current == attempt else { return }
                handleFailure(error)
            }
        }
    }

    private func connect(to offer: PhoneOffer, ticket: String? = nil, automatic: Bool = false) {
        stopConnection()
        pendingTicket = ticket
        if !automatic {
            status = .connecting
            failure = nil
            retrySeconds = 1
        }
        let current = attempt
        operation = Task { [weak self] in
            guard let self else { return }
            do {
                guard var identity else { throw PhoneConnectionError.secureStorage }
                let next = try await PhoneRelay.connect(to: offer)
                guard current == attempt else { next.close(); return }
                relay = next
                let enrolling = pendingTicket != nil
                try await next.authorize(identity: identity.signingKey(), ticket: pendingTicket,
                                         label: UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone")
                guard current == attempt else { return }
                pendingTicket = nil
                if enrolling {
                    identity.offer = offer
                    identity.requiresPairing = false
                    try store.save(identity)
                    self.identity = identity
                }
                let receipt = try await next.readHost()
                guard current == attempt else { return }
                try updateHost(receipt)
                status = .connected
                failure = nil
                retrySeconds = 1
                operation = nil
                watch(next, attempt: current)
            } catch {
                guard current == attempt else { return }
                handleFailure(error)
            }
        }
    }

    private func updateHost(_ receipt: PhoneHostReceipt) throws {
        if let host, host.hostID == receipt.hostID,
           host.requestID == receipt.requestID || host.receipt == receipt.receipt {
            throw PhoneConnectionError.invalidResponse
        }
        host = receipt
    }

    private func watch(_ relay: PhoneRelay, attempt current: UUID) {
        relay.onDisconnect = { [weak self] in
            guard let self, current == attempt, operation == nil else { return }
            handleFailure(PhoneConnectionError.unreachable)
        }
        monitor = Task { [weak self] in
            do { try await relay.monitor() }
            catch {
                guard let self, current == attempt, operation == nil else { return }
                handleFailure(error)
            }
        }
    }

    private func handleFailure(_ error: Error) {
        stopConnection()
        let problem = error as? PhoneConnectionError ?? .invalidResponse
        if problem == .notAuthorized {
            host = nil
            status = .authorizationExpired
            failure = nil
            if var identity, identity.offer != nil {
                identity.requiresPairing = true
                self.identity = identity
                do { try store.save(identity) }
                catch { failure = .secureStorage }
            }
            return
        }
        failure = problem
        if identity?.requiresPairing == true {
            status = .authorizationExpired
        } else if let offer = identity?.offer {
            status = .disconnected
            if problem == .unreachable { scheduleRetry(to: offer) }
        } else {
            status = .unpaired
        }
    }

    private func scheduleRetry(to offer: PhoneOffer) {
        guard active else { return }
        let delay = retrySeconds
        retrySeconds = min(delay * 2, 15)
        retry = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: delay * 1_000_000_000) }
            catch { return }
            self?.connect(to: offer, automatic: true)
        }
    }

    private func stopConnection() {
        // Invalidate callbacks before closing the socket or cancelling tasks.
        attempt = UUID()
        operation?.cancel()
        retry?.cancel()
        monitor?.cancel()
        relay?.close()
        operation = nil
        retry = nil
        monitor = nil
        relay = nil
        pendingTicket = nil
        isReading = false
    }
}
#endif
