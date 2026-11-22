#if DEBUG
import Foundation

enum UsageFixtureScenario: String {
    case restricted, offline, empty, paged

    static func workspace(_ scenario: Self) -> UsageWorkspace {
        UsageWorkspace(store: MemoryUsageCredentials(), makeSession: { _ in
            FixtureUsageSession(scenario)
        }, isFixture: true)
    }
}

private final class MemoryUsageCredentials: UsageCredentialStore {
    private var credential: UsageCredential?

    nonisolated deinit {}

    func read() throws -> UsageCredential? { credential }
    func save(_ credential: UsageCredential) throws { self.credential = credential }
    func clear() throws { credential = nil }
}

private final class FixtureUsageSession: UsageSession {
    private let scenario: UsageFixtureScenario
    private var state: UsageSnapshot
    private var continuations: [UUID: AsyncStream<UsageSnapshot>.Continuation] = [:]

    init(_ scenario: UsageFixtureScenario) {
        self.scenario = scenario
        state = Self.initial(scenario, range: .today)
    }

    nonisolated deinit {}

    func snapshots() -> AsyncStream<UsageSnapshot> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: UsageSnapshot.self, bufferingPolicy: .bufferingNewest(1))
        continuations[id] = continuation
        continuation.yield(state)
        continuation.onTermination = { [weak self] _ in
            Task { @MainActor [weak self] in self?.continuations.removeValue(forKey: id) }
        }
        return stream
    }

    func send(_ action: UsageAction) {
        switch action {
        case let .range(range):
            state = Self.initial(scenario, range: range)
        case .refresh(.overview) where scenario == .offline:
            state.overview = .loaded(Self.summary)
        case .refresh(.requests) where scenario == .offline:
            state.requests = .empty
        case .refresh(.requests) where scenario == .paged:
            state.requests = Self.firstPage
        case .nextPage where scenario == .paged:
            if case let .loaded(list) = state.requests {
                switch list.next {
                case .available:
                    state.requests = .loaded(.init(rows: list.rows, total: 3, next: .failed(.network)))
                case .failed:
                    state.requests = .loaded(.init(rows: list.rows + [Self.row(3)], total: 3, next: .finished))
                case .loading, .finished:
                    break
                }
            }
        default:
            break
        }
        for continuation in continuations.values { continuation.yield(state) }
    }

    func close() {
        for continuation in continuations.values { continuation.finish() }
        continuations.removeAll()
    }

    private static func initial(_ scenario: UsageFixtureScenario, range: UsageRange) -> UsageSnapshot {
        switch scenario {
        case .restricted:
            .init(range: range, overview: .failed(.keyRestricted),
                  requests: .loaded(.init(rows: [row(1)], total: 1, next: .finished)))
        case .offline:
            .init(range: range, overview: .failed(.network), requests: .failed(.network))
        case .empty:
            .init(range: range, overview: .empty, requests: .empty)
        case .paged:
            .init(range: range, overview: .loaded(summary), requests: firstPage)
        }
    }

    private static let summary = UsageSummary(
        calls: 3, errors: 1, input: 120, output: 30,
        knownCost: Decimal(sign: .plus, exponent: -3, significand: 125), unpriced: 1
    )

    private static var firstPage: UsageLoad<UsageRequestList> {
        .loaded(.init(rows: [row(1), row(2)], total: 3, next: .available))
    }

    private static func row(_ index: Int) -> UsageRequestRow {
        .init(id: "sample-\(index)", timestamp: Date(timeIntervalSince1970: 1_785_096_000 - Double(index) * 60),
              agent: "Codex", model: String(localized: "Sample Model \(index)"), httpStatus: index == 1 ? nil : 200,
              knownCost: index == 1 ? nil : 0)
    }
}
#endif
