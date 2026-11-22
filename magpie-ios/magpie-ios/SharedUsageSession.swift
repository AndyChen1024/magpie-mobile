#if os(iOS) && canImport(MagpieShared)
import Foundation
@preconcurrency import MagpieShared

final class SharedUsageSession: UsageSession {
    private let controller: UsageController
    private let transport: SharedUsageTransport

    init(_ credential: UsageCredential, configuration: URLSessionConfiguration = .ephemeral) {
        transport = SharedUsageTransport(origin: credential.origin, configuration: configuration)
        let credentials = SharedUsageCredentials(credential)
        controller = UsageController(transport: transport, credentials: credentials)
        if let origin = MagpieShared.UsageOrigin.companion.parse(raw: credential.origin.address) {
            controller.connect(origin: origin)
        }
    }

    // Avoid the isolated deinit crash on older Swift runtimes (Swift #88036).
    nonisolated deinit {}

    nonisolated static func parseOrigin(_ address: String) throws -> UsageOrigin {
        guard let origin = MagpieShared.UsageOrigin.companion.parse(raw: address) else {
            throw UsageConnectionError.invalidAddress
        }
        return try UsageOrigin(origin.value)
    }

    func snapshots() -> AsyncStream<UsageSnapshot> {
        let (stream, continuation) = AsyncStream.makeStream(of: UsageSnapshot.self, bufferingPolicy: .bufferingNewest(1))
        let observer = SharedUsageObserver(continuation: continuation)
        let observation = controller.observe(observer: observer)
        continuation.onTermination = { _ in
            Task { @MainActor in observation.cancel() }
        }
        return stream
    }

    func send(_ action: UsageAction) {
        switch action {
        case let .section(section):
            controller.openTab(tab: section == .overview ? .overview : .requests)
        case let .range(range):
            switch range {
            case .today: controller.selectPeriod(period: .today)
            case .week: controller.selectPeriod(period: .week)
            case .month: controller.selectPeriod(period: .month)
            }
        case .refresh:
            controller.refresh()
        case .nextPage:
            controller.loadMore()
        case .foregrounded:
            let state = controller.state
            if state.tab == .overview && state.summary.failure == .localPermission ||
                state.tab == .requests && (state.requests.failure == .localPermission || state.requests.moreFailure == .localPermission) {
                controller.retry()
            }
        }
    }

    func close() {
        controller.close()
        transport.close()
    }
}

nonisolated private final class SharedUsageObserver: NSObject, UsageObserver {
    private let continuation: AsyncStream<UsageSnapshot>.Continuation

    init(continuation: AsyncStream<UsageSnapshot>.Continuation) {
        self.continuation = continuation
    }

    func onState(state: UsageState) {
        let range: UsageRange
        switch state.period {
        case .week: range = .week
        case .month: range = .month
        default: range = .today
        }
        continuation.yield(UsageSnapshot(range: range, overview: summary(state.summary), requests: requests(state.requests)))
    }

    private func summary(_ state: SummaryState) -> UsageLoad<UsageSummary> {
        switch state.phase {
        case .empty: return .empty
        case .error: return .failed(issue(state.failure))
        case .ready:
            guard let data = state.value else { return .failed(.invalidData) }
            let hasNoKnownPrice = data.costUsd == 0 && data.unpriced > 0 && data.unpriced >= data.calls
            return .loaded(UsageSummary(calls: Int(data.calls), errors: Int(data.errors), input: Int(data.input),
                                        output: Int(data.output), knownCost: hasNoKnownPrice ? nil : money(data.costUsd),
                                        unpriced: Int(data.unpriced)))
        default: return .loading
        }
    }

    private func requests(_ state: RequestsState) -> UsageLoad<UsageRequestList> {
        switch state.phase {
        case .empty: return .empty
        case .error: return .failed(issue(state.failure))
        case .ready:
            let next: UsageRequestList.NextPage
            if state.loadingMore { next = .loading }
            else if let failure = state.moreFailure { next = .failed(issue(failure)) }
            else { next = state.nextOffset == nil ? .finished : .available }

            let rows = state.rows.enumerated().map { index, row in
                let date = (try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(row.timestamp))
                    ?? (try? Date.ISO8601FormatStyle().parse(row.timestamp))
                return UsageRequestRow(id: String(index), timestamp: date, agent: row.agent, model: row.model,
                                       httpStatus: row.httpStatus?.intValue, knownCost: row.costUsd.flatMap { money($0.doubleValue) })
            }
            return .loaded(.init(rows: rows, total: state.total?.intValue ?? rows.count, next: next))
        default: return .loading
        }
    }

    private func money(_ value: Double) -> Decimal? {
        Decimal(string: String(value), locale: Locale(identifier: "en_US_POSIX"))
    }

    private func issue(_ failure: UsageFailure?) -> UsageIssue {
        switch failure {
        case .restricted: .keyRestricted
        case .unauthorized, .credential: .unauthorized
        case .forbidden: .forbidden
        case .localPermission: .localNetworkDenied
        case .network: .network
        case .tls: .trust
        case .redirect: .redirect
        case .invalidData: .invalidData
        default: .server
        }
    }
}

nonisolated private final class SharedUsageCredentials: NSObject, UsageCredentials {
    private let address: String
    private let key: String

    init(_ credential: UsageCredential) {
        address = credential.origin.address
        key = credential.key
    }

    func read(origin: MagpieShared.UsageOrigin, completionHandler: @escaping (String?, (any Error)?) -> Void) {
        completionHandler(origin.value == address ? key : nil, nil)
    }
}

private final class SharedUsageTransport: NSObject, UsageTransport {
    private let transport: UsageHTTPTransport
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private var closed = false

    init(origin: UsageOrigin, configuration: URLSessionConfiguration) {
        transport = UsageHTTPTransport(origin: origin, configuration: configuration)
    }

    nonisolated deinit {}

    nonisolated func get(
        request: UsageHttpRequest, completionHandler: @escaping (UsageHttpResponse?, (any Error)?) -> Void
    ) {
        let address = request.url
        let authorization = request.authorization
        Task { @MainActor in
            send(address: address, authorization: authorization, completionHandler: completionHandler)
        }
    }

    private func send(
        address: String, authorization: String, completionHandler: @escaping (UsageHttpResponse?, (any Error)?) -> Void
    ) {
        guard !closed else {
            completionHandler(UsageHttpResponse(statusCode: 0, body: "", failure: .network), nil)
            return
        }
        let id = UUID()
        tasks[id] = Task {
            defer { tasks[id] = nil }
            let reply: UsageHttpResponse
            do {
                let response = try await transport.get(url: address, authorization: authorization)
                reply = UsageHttpResponse(statusCode: Int32(response.statusCode), body: response.body, failure: nil)
            } catch {
                let failure: UsageFailure
                switch error as? UsageTransportError {
                case .tls: failure = .tls
                case .redirect: failure = .redirect
                case .invalidResponse: failure = .invalidData
                case .localPermission: failure = .localPermission
                default: failure = .network
                }
                reply = UsageHttpResponse(statusCode: 0, body: "", failure: failure)
            }
            completionHandler(reply, nil)
        }
    }

    func close() {
        closed = true
        for task in tasks.values { task.cancel() }
    }
}
#endif
