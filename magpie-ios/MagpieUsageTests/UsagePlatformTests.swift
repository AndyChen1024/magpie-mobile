import XCTest
@testable import magpie_ios

@MainActor
final class UsagePlatformTests: XCTestCase {
    func testOriginCanonicalizesHTTPSDefaultPortAndV1() throws {
        XCTAssertEqual(try UsageOrigin("  HTTPS://MAGPIE.local:443/v1/  ").address, "https://magpie.local")
        XCTAssertEqual(try UsageOrigin("https://192.0.2.1:3425/").address, "https://192.0.2.1:3425")
        XCTAssertEqual(try UsageOrigin("https://[2001:db8::1]:3425/v1").address, "https://[2001:db8::1]:3425")
    }

    func testOriginRejectsInsecureOrAmbiguousInputs() {
        for address in [
            "http://magpie.local", "https://", "https://user@magpie.local", "https://magpie.local:0",
            "https://magpie.local:65536", "https://magpie.local/v1?x=1", "https://magpie.local/#fragment",
            "https://magpie.local/v1/requests", "https://magpie.local/%76%31", "https://magpie.local\\name",
            "https://mag pie.local", "https://magpie.local//v1",
        ] {
            XCTAssertThrowsError(try UsageOrigin(address))
        }
    }

    func testKeychainAtomicallyReplacesOriginAndKeyAndClears() throws {
        let keychain = UsageKeychain(service: "magpie.usage.test.\(UUID())")
        defer { try? keychain.clear() }
        let initial = try UsageCredential(origin: UsageOrigin("https://first.example.test"), key: "sample-a")
        let replacement = try UsageCredential(origin: UsageOrigin("https://second.example.test:3425"), key: "sample-b")
        XCTAssertNil(try keychain.read())
        try keychain.save(initial)
        XCTAssertEqual(try keychain.read()?.origin, initial.origin)
        XCTAssertTrue(try keychain.read()?.key == initial.key)
        try keychain.save(replacement)
        XCTAssertEqual(try keychain.read()?.origin, replacement.origin)
        XCTAssertTrue(try keychain.read()?.key == replacement.key)
        try keychain.clear()
        try keychain.clear()
        XCTAssertNil(try keychain.read())
    }

    func testEditingOriginDoesNotReuseOtherHostsCredential() throws {
        let store = TestCredentialStore()
        let workspace = UsageWorkspace(store: store, makeSession: { _ in TestUsageSession() })
        try workspace.connect(address: "https://first.example.test", key: "sample-a")
        try workspace.connect(address: "https://first.example.test/v1", key: "")
        XCTAssertThrowsError(try workspace.connect(address: "https://second.example.test", key: ""))
        XCTAssertEqual(workspace.origin, try UsageOrigin("https://first.example.test"))
        XCTAssertTrue(try store.read()?.key == "sample-a")
    }

    func testObservationIgnoresObsoleteRangeAndConnectionResults() async throws {
        let store = TestCredentialStore()
        let old = TestUsageSession()
        let current = TestUsageSession()
        let workspace = UsageWorkspace(store: store, makeSession: { $0.key == "sample-a" ? old : current })
        try workspace.connect(address: "https://magpie.example.test", key: "sample-a")
        let previous = Task { await workspace.observe() }
        old.emit(.init(range: .today, overview: .empty))
        let todayArrived = await awaitState(workspace, .empty, range: .today)
        XCTAssertTrue(todayArrived)

        workspace.select(.week)
        old.emit(.init(range: .week, overview: .failed(.keyRestricted)))
        old.emit(.init(range: .today, overview: .failed(.network)))
        let weekArrived = await awaitState(workspace, .failed(.keyRestricted), range: .week)
        XCTAssertTrue(weekArrived)

        try workspace.connect(address: "https://magpie.example.test", key: "sample-b")
        old.emit(.init(range: .today, overview: .empty))
        await previous.value
        XCTAssertEqual(workspace.snapshot.overview, .loading)
        let observation = Task { await workspace.observe() }
        defer { observation.cancel() }
        current.emit(.init(range: .today, overview: .failed(.forbidden)))
        let currentArrived = await awaitState(workspace, .failed(.forbidden), range: .today)
        XCTAssertTrue(currentArrived)
        try workspace.disconnect()
        current.emit(.init(range: .today, overview: .empty))
        await observation.value
        XCTAssertNil(workspace.origin)
        XCTAssertNil(try store.read())
        XCTAssertEqual(workspace.snapshot.overview, .loading)
    }

    func testRestoreAndForegroundKeepSessionAndUserIntent() throws {
        let store = TestCredentialStore()
        try store.save(UsageCredential(origin: UsageOrigin("https://magpie.example.test"), key: "sample"))
        let session = TestUsageSession()
        var sessionCount = 0
        let workspace = UsageWorkspace(store: store, makeSession: { _ in
            sessionCount += 1
            return session
        })
        workspace.restoreIfNeeded()
        workspace.select(.requests)
        workspace.select(.week)
        workspace.restoreIfNeeded()
        workspace.foregrounded()
        workspace.refresh()
        XCTAssertEqual(sessionCount, 1)
        XCTAssertEqual(workspace.section, .requests)
        XCTAssertEqual(workspace.range, .week)
        XCTAssertEqual(session.actions, [.section(.requests), .range(.week), .foregrounded, .refresh(.requests)])
    }

    func testHTTPTransportBindsAuthorizationToHTTPSOrigin() async throws {
        let transport = try makeTestTransport()
        let origin = try UsageOrigin("https://magpie.example.test")
        for address in ["https://other.example.test/usage", "http://magpie.example.test/usage", "https://magpie.example.test:8443/usage"] {
            do {
                _ = try await transport.get(url: address, authorization: "Bearer sample")
                XCTFail("A different origin must be rejected")
            } catch UsageTransportError.redirect {
                // Expected. The transport should reject before opening a session.
            }
        }
        let response = try await transport.get(url: origin.address + "/usage", authorization: "Bearer sample")
        XCTAssertEqual(response.statusCode, 200)
        XCTAssertEqual(response.body, "authorized")
    }

    func testHTTPTransportReportsRedirectsAndTrustFailures() async throws {
        let transport = try makeTestTransport()
        do {
            _ = try await transport.get(url: "https://magpie.example.test/redirect", authorization: "Bearer sample")
            XCTFail("A redirect response must be rejected")
        } catch UsageTransportError.redirect {}

        do {
            _ = try await transport.get(url: "https://magpie.example.test/untrusted", authorization: "Bearer sample")
            XCTFail("A trust failure must be reported")
        } catch UsageTransportError.tls {}
    }

    #if canImport(MagpieShared)
    func testSharedControllerReadsRestrictedOverviewAndOwnRequestsViaSwiftTransport() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UsageTestURLProtocol.self]
        let workspace = UsageWorkspace(store: TestCredentialStore(), makeSession: {
            SharedUsageSession($0, configuration: configuration)
        }, parseOrigin: { try SharedUsageSession.parseOrigin($0) })
        try workspace.connect(address: "https://magpie.example.test/v1", key: "sample")
        let observation = Task { await workspace.observe() }
        defer {
            observation.cancel()
            try? workspace.disconnect()
        }

        let restricted = await awaitState(workspace, .failed(.keyRestricted), range: .today)
        XCTAssertTrue(restricted)
        workspace.select(.requests)
        workspace.select(.week)

        let deadline = ContinuousClock.now.advanced(by: .seconds(3))
        while ContinuousClock.now < deadline {
            if workspace.snapshot.range == .week, case let .loaded(list) = workspace.snapshot.requests {
                XCTAssertEqual(list.total, 1)
                XCTAssertEqual(list.next, .finished)
                XCTAssertEqual(list.rows.first?.agent, "Codex")
                XCTAssertEqual(list.rows.first?.httpStatus, 200)
                XCTAssertEqual(list.rows.first?.knownCost, 0)
                return
            }
            try? await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Shared controller did not produce the restricted key's requests")
    }
    #endif

    private func makeTestTransport() throws -> UsageHTTPTransport {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UsageTestURLProtocol.self]
        return UsageHTTPTransport(origin: try UsageOrigin("https://magpie.example.test"), configuration: configuration)
    }

    private func awaitState(
        _ workspace: UsageWorkspace, _ expected: UsageLoad<UsageSummary>, range: UsageRange
    ) async -> Bool {
        let deadline = ContinuousClock.now.advanced(by: .seconds(2))
        while ContinuousClock.now < deadline {
            if workspace.snapshot.overview == expected && workspace.snapshot.range == range { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return false
    }
}

private final class UsageTestURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        guard let url = request.url else { return }
        if url.path == "/untrusted" {
            client?.urlProtocol(self, didFailWithError: URLError(.serverCertificateUntrusted))
            return
        }
        let authorized = request.value(forHTTPHeaderField: "Authorization") == "Bearer sample"
        let headers: [String: String]?
        let status: Int
        let body: String
        switch url.path {
        case "/redirect":
            headers = ["Location": "https://other.example.test/usage"]
            status = 302
            body = ""
        case "/v1/magpie/usage":
            headers = nil
            status = authorized ? 403 : 401
            body = #"{"error":{"code":"gateway_key_held"}}"#
        case "/v1/magpie/usage/requests":
            headers = nil
            let parameters = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            let valid = Set(parameters) == Set([URLQueryItem(name: "period", value: "7d"),
                                               URLQueryItem(name: "offset", value: "0"),
                                               URLQueryItem(name: "limit", value: "50")])
            status = authorized && valid ? 200 : 400
            body = #"{"offset":0,"total":1,"rows":[{"t":"2026-11-20T15:40:00Z","agentName":"Codex","model":"sample","status":200,"priced":true,"cost":0}]}"#
        default:
            headers = nil
            status = 200
            body = authorized ? "authorized" : "missing"
        }
        guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}

@MainActor
private final class TestCredentialStore: UsageCredentialStore {
    var saved: UsageCredential?
    func read() throws -> UsageCredential? { saved }
    func save(_ credential: UsageCredential) throws { saved = credential }
    func clear() throws { saved = nil }
}

@MainActor
private final class TestUsageSession: UsageSession {
    let stream = AsyncStream.makeStream(of: UsageSnapshot.self)
    var actions: [UsageAction] = []
    func snapshots() -> AsyncStream<UsageSnapshot> { stream.stream }
    func send(_ action: UsageAction) { actions.append(action) }
    func emit(_ state: UsageSnapshot) { stream.continuation.yield(state) }

    // Leave the stream open so the test can deliver a late event after disconnection.
    func close() {}
}
