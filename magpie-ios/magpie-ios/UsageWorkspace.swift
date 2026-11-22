import Combine
import Foundation

nonisolated enum UsageRange: CaseIterable, Sendable {
    case today, week, month

    var title: String {
        switch self {
        case .today: String(localized: "Today")
        case .week: String(localized: "Last 7 Days")
        case .month: String(localized: "Last 30 Days")
        }
    }

    var days: Int {
        switch self {
        case .today: 1
        case .week: 7
        case .month: 30
        }
    }
}

nonisolated enum UsageSection: CaseIterable, Sendable {
    case overview, requests

    var title: String {
        switch self {
        case .overview: String(localized: "Overview")
        case .requests: String(localized: "Requests")
        }
    }
}

nonisolated enum UsageIssue: Equatable, Sendable {
    case keyRestricted, unauthorized, forbidden, localNetworkDenied, network, trust, redirect, invalidData, server

    var title: String {
        switch self {
        case .keyRestricted: String(localized: "This key can only view its own requests")
        case .unauthorized: String(localized: "Invalid gateway key")
        case .forbidden: String(localized: "Access denied by the gateway")
        case .localNetworkDenied: String(localized: "Local network access denied")
        case .network: String(localized: "Connection failed")
        case .trust: String(localized: "The host certificate could not be verified")
        case .redirect: String(localized: "The gateway redirected the request")
        case .invalidData: String(localized: "Invalid usage data")
        case .server: String(localized: "Gateway unavailable")
        }
    }

    var message: String {
        switch self {
        case .keyRestricted: String(localized: "Open Requests to see records available to this key.")
        case .unauthorized: String(localized: "Check or update the gateway key in Connection Settings.")
        case .forbidden: String(localized: "Check local network sharing and the key permissions on your Mac, then try again.")
        case .localNetworkDenied: String(localized: "Allow Magpie under Local Network in Settings, then return and try again.")
        case .network: String(localized: "Make sure your device and Mac are on the same network and Magpie is running.")
        case .trust: String(localized: "Make sure your device trusts the certificate and it matches the gateway address.")
        case .redirect: String(localized: "Enter the Magpie gateway's HTTPS address in Connection Settings.")
        case .invalidData: String(localized: "Check the Magpie version on the host, then try again.")
        case .server: String(localized: "Check the Magpie gateway on your Mac, then try again.")
        }
    }
}

nonisolated struct UsageSummary: Equatable, Sendable {
    let calls: Int
    let errors: Int
    let input: Int
    let output: Int
    let knownCost: Decimal?
    let unpriced: Int
}

nonisolated struct UsageRequestRow: Identifiable, Equatable, Sendable {
    let id: String
    let timestamp: Date?
    let agent: String?
    let model: String?
    let httpStatus: Int?
    let knownCost: Decimal?
}

nonisolated struct UsageRequestList: Equatable, Sendable {
    let rows: [UsageRequestRow]
    let total: Int
    let next: NextPage

    nonisolated enum NextPage: Equatable, Sendable {
        case available, loading, failed(UsageIssue), finished
    }
}

nonisolated enum UsageLoad<Value: Equatable & Sendable>: Equatable, Sendable {
    case loading, loaded(Value), empty, failed(UsageIssue)
}

nonisolated struct UsageSnapshot: Equatable, Sendable {
    let range: UsageRange
    var overview: UsageLoad<UsageSummary> = .loading
    var requests: UsageLoad<UsageRequestList> = .loading
}

nonisolated enum UsageAction: Equatable, Sendable {
    case section(UsageSection)
    case range(UsageRange)
    case refresh(UsageSection)
    case nextPage
    case foregrounded
}

/// Presentation boundary. The shared session owns request identity, decoding and pagination.
protocol UsageSession: AnyObject {
    /// Start observing by replaying the latest state. Cancel iteration to unsubscribe.
    func snapshots() -> AsyncStream<UsageSnapshot>
    func send(_ action: UsageAction)
    func close()
}

final class UsageWorkspace: ObservableObject {
    typealias SessionFactory = @MainActor (UsageCredential) -> any UsageSession
    typealias OriginParser = @MainActor (String) throws -> UsageOrigin

    @Published private(set) var origin: UsageOrigin?
    @Published private(set) var range = UsageRange.today
    @Published private(set) var section = UsageSection.overview
    @Published private(set) var snapshot = UsageSnapshot(range: .today)
    @Published private(set) var observationID = UUID()
    @Published private(set) var storageIssue: UsageConnectionError?

    let isFixture: Bool
    var canConnect: Bool { makeSession != nil }

    private let store: any UsageCredentialStore
    private let makeSession: SessionFactory?
    private let parseOrigin: OriginParser
    private var session: (any UsageSession)?
    private var hasRestored = false

    init(
        store: any UsageCredentialStore = UsageKeychain(),
        makeSession: SessionFactory? = nil,
        parseOrigin: @escaping OriginParser = UsageOrigin.init,
        isFixture: Bool = false
    ) {
        self.store = store
        self.makeSession = makeSession
        self.parseOrigin = parseOrigin
        self.isFixture = isFixture
    }

    // Avoid the isolated deinit crash on older Swift runtimes (Swift #88036).
    nonisolated deinit {}

    func restoreIfNeeded() {
        guard !hasRestored, let makeSession else { return }
        hasRestored = true
        storageIssue = nil
        do {
            if let credential = try store.read() {
                begin(credential, makeSession: makeSession)
            }
        } catch {
            storageIssue = .secureStorage
        }
    }

    func retryRestore() {
        hasRestored = false
        restoreIfNeeded()
    }

    func connect(address: String, key: String) throws {
        let candidate = try parseOrigin(address)
        guard let makeSession else { throw UsageConnectionError.unavailable }
        let credential: UsageCredential
        if key.isEmpty, let saved = try store.read(), saved.origin == candidate {
            credential = saved
        } else {
            credential = try UsageCredential(origin: candidate, key: key)
        }
        try store.save(credential)
        hasRestored = true
        storageIssue = nil
        begin(credential, makeSession: makeSession)
    }

    func disconnect() throws {
        try store.clear()
        hasRestored = true
        storageIssue = nil
        session?.close()
        session = nil
        origin = nil
        resetSelection()
        observationID = UUID()
    }

    func observe() async {
        guard let session else { return }
        let id = observationID
        for await state in session.snapshots() {
            guard !Task.isCancelled, id == observationID else { return }
            if state.range == range { snapshot = state }
        }
    }

    func select(_ section: UsageSection) {
        guard self.section != section else { return }
        self.section = section
        session?.send(.section(section))
    }

    func select(_ range: UsageRange) {
        guard self.range != range else { return }
        self.range = range
        snapshot = UsageSnapshot(range: range)
        session?.send(.range(range))
    }

    func refresh() {
        session?.send(.refresh(section))
    }

    func loadMore() {
        session?.send(.nextPage)
    }

    func foregrounded() {
        session?.send(.foregrounded)
    }

    private func begin(_ credential: UsageCredential, makeSession: SessionFactory) {
        session?.close()
        origin = credential.origin
        resetSelection()
        session = makeSession(credential)
        observationID = UUID()
    }

    private func resetSelection() {
        range = .today
        section = .overview
        snapshot = UsageSnapshot(range: .today)
    }
}
