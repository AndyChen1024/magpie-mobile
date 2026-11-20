import Foundation
import MagpieShared

private final class FixtureCredentials: NSObject, UsageCredentials {
    func read(origin: UsageOrigin, completionHandler: @escaping (String?, (any Error)?) -> Void) {
        completionHandler(origin.value == "https://fixture.local" ? "sample-key" : nil, nil)
    }
}

private final class FixtureTransport: NSObject, UsageTransport {
    func get(request: UsageHttpRequest, completionHandler: @escaping (UsageHttpResponse?, (any Error)?) -> Void) {
        let isLedger = request.url.contains("/usage/requests?")
        let response = UsageHttpResponse(
            statusCode: isLedger ? 200 : 403,
            body: isLedger
                ? #"{"offset":0,"total":1,"rows":[{"t":"2026-11-20T10:00:00Z","model":"demo/model"}]}"#
                : #"{"error":{"code":"gateway_key_held"}}"#,
            failure: nil
        )
        completionHandler(response, nil)
    }
}

private final class StateObserver: NSObject, UsageObserver {
    let onChange: (UsageState) -> Void

    init(onChange: @escaping (UsageState) -> Void) {
        self.onChange = onChange
    }

    func onState(state: UsageState) {
        onChange(state)
    }
}

/// A buildable demonstration of the framework boundary. Use real Keychain and URLSession adapters in the app.
@MainActor
final class UsageBridgeSmoke {
    private let controller = UsageController(transport: FixtureTransport(), credentials: FixtureCredentials())
    private var observation: UsageObservation?

    func start(onChange: @escaping (UsageState) -> Void) {
        stopObserving()
        observation = controller.observe(observer: StateObserver(onChange: onChange))
        if let origin = UsageOrigin.companion.parse(raw: "https://fixture.local/v1") {
            controller.connect(origin: origin)
        }
    }

    func showRequests() {
        controller.openTab(tab: .requests)
    }

    func showLastWeek() {
        controller.selectPeriod(period: .week)
    }

    func stopObserving() {
        observation?.cancel()
        observation = nil
    }

    func close() {
        stopObserving()
        controller.close()
    }
}
