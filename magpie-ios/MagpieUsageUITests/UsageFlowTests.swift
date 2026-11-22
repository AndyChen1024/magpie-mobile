import XCTest

final class UsageFlowTests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    @MainActor
    func testRestrictedOverviewStillShowsRequestsAndCanDisconnect() {
        let app = openUsage("restricted")
        connect(app)

        XCTAssertTrue(app.buttons["usage-open-requests"].waitForExistence(timeout: 5))
        attach("受限用量概览")
        app.buttons["usage-open-requests"].tap()
        let request = app.buttons["usage-request-sample-1"]
        XCTAssertTrue(request.waitForExistence(timeout: 5))
        request.tap()
        XCTAssertTrue(app.navigationBars["请求详情"].waitForExistence(timeout: 5))
        XCTAssertTrue(text(app, containing: "未提供状态").exists)
        XCTAssertTrue(text(app, containing: "未知").exists)
        app.buttons["完成"].tap()

        app.buttons["usage-connect"].tap()
        app.buttons["usage-disconnect"].tap()
        app.buttons["断开并清除"].tap()
        XCTAssertTrue(app.buttons["usage-demo"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testOverviewAndPaginationRetry() {
        let app = openUsage("paged")
        connect(app)

        XCTAssertTrue(text(app, containing: "150 Token").waitForExistence(timeout: 5))
        XCTAssertTrue(text(app, containing: "$0.125").exists)
        XCTAssertTrue(text(app, containing: "1 次请求未定价").exists)
        attach("已知费用和部分未定价")

        app.buttons["usage-period"].tap()
        app.buttons["最近 7 天"].tap()
        XCTAssertEqual(app.buttons["usage-period"].value as? String, "最近 7 天")
        app.buttons["请求"].tap()
        app.buttons["usage-load-more"].tap()
        XCTAssertTrue(app.buttons["usage-retry"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["usage-request-sample-1"].exists)
        app.buttons["usage-retry"].tap()
        XCTAssertTrue(app.buttons["usage-request-sample-3"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["usage-load-more"].exists)
    }

    @MainActor
    func testConnectionErrorCanRetryAndEmptyRequestsStayEmpty() {
        let app = openUsage("offline")
        connect(app)

        XCTAssertTrue(text(app, containing: "连接失败").waitForExistence(timeout: 5))
        app.buttons["usage-retry"].tap()
        XCTAssertTrue(text(app, containing: "3 次调用").waitForExistence(timeout: 5))
        app.buttons["请求"].tap()
        app.buttons["usage-retry"].tap()
        XCTAssertTrue(text(app, containing: "暂无请求").waitForExistence(timeout: 5))
    }

    @MainActor
    func testOfflineBuildHasExplicitDemoEntry() {
        let app = openUsage("unavailable")
        app.buttons["usage-connect"].tap()
        XCTAssertFalse(app.buttons["usage-connect-confirm"].isEnabled)
        attach("首次连接设置")
        app.buttons["取消"].tap()
        app.buttons["usage-demo"].tap()
        XCTAssertTrue(text(app, containing: "仅统计示例网关调用").waitForExistence(timeout: 5))
        app.buttons["返回局域网用量"].tap()
        XCTAssertTrue(app.buttons["usage-connect"].exists)
    }

    @MainActor
    func testEnglishNavigationAndDemoContent() {
        let app = launch("unavailable", language: "en", locale: "en_US")
        XCTAssertEqual(app.buttons["settings"].label, "Settings")
        XCTAssertEqual(app.buttons["agent-detail-codex"].label, "Codex details")
        XCTAssertTrue(text(app, containing: "Switch with /model").exists)
        attach("English Agent")

        app.buttons["tab-sessions"].tap()
        XCTAssertTrue(app.textFields["Search sessions"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons.containing(NSPredicate(format: "label CONTAINS %@", "Port the Magpie mobile layout")).firstMatch.exists)
        app.buttons["tab-library"].tap()
        XCTAssertTrue(text(app, containing: "No instructions").waitForExistence(timeout: 5))
        app.buttons["Skills"].tap()
        XCTAssertTrue(text(app, containing: "No skills").exists)
        app.buttons["tab-providers"].tap()
        XCTAssertTrue(app.textFields["Find a provider"].waitForExistence(timeout: 5))

        app.buttons["tab-more"].tap()
        XCTAssertEqual(app.buttons["more-masking"].label, "Redaction")
        app.buttons["more-usage"].tap()
        app.buttons["usage-connect"].tap()
        XCTAssertTrue(app.navigationBars["Connect to Magpie"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.secureTextFields["usage-key"].label, "Gateway key")
        app.buttons["Cancel"].tap()
        app.buttons["usage-demo"].tap()
        XCTAssertTrue(text(app, containing: "Shows sample gateway requests.").waitForExistence(timeout: 5))
    }

    @MainActor
    func testEnglishUsageNumbersAndRequestDetails() {
        let app = openUsage("paged", language: "en", locale: "en_US")
        connect(app)

        XCTAssertTrue(text(app, containing: "Tokens: 150").waitForExistence(timeout: 5))
        XCTAssertTrue(text(app, containing: "3 calls").exists)
        XCTAssertTrue(text(app, containing: "1 error").exists)
        XCTAssertFalse(text(app, containing: "1 errors").exists)
        XCTAssertTrue(text(app, containing: "$0.125").exists)
        XCTAssertTrue(text(app, containing: "1 unpriced request").exists)
        XCTAssertFalse(text(app, containing: "1 unpriced requests").exists)
        attach("English Usage Overview")

        app.buttons["usage-period"].tap()
        app.buttons["Last 7 Days"].tap()
        XCTAssertEqual(app.buttons["usage-period"].value as? String, "Last 7 Days")
        app.buttons["Requests"].tap()
        XCTAssertTrue(text(app, containing: "Requests: 2 of 3").waitForExistence(timeout: 5))
        app.buttons["usage-request-sample-1"].tap()
        XCTAssertTrue(app.navigationBars["Request Details"].waitForExistence(timeout: 5))
        XCTAssertTrue(text(app, containing: "Status unavailable").exists)
        XCTAssertTrue(text(app, containing: "Unknown").exists)
    }

    @MainActor
    private func openUsage(_ fixture: String, language: String = "zh-Hans", locale: String = "zh_CN") -> XCUIApplication {
        let app = launch(fixture, language: language, locale: locale)
        app.buttons["tab-more"].tap()
        app.buttons["more-usage"].tap()
        return app
    }

    @MainActor
    private func launch(_ fixture: String, language: String, locale: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["MAGPIE_USAGE_FIXTURE"] = fixture
        app.launchArguments += ["-AppleLanguages", "(\(language))", "-AppleLocale", locale]
        app.launch()
        return app
    }

    @MainActor
    private func connect(_ app: XCUIApplication) {
        app.buttons["usage-connect"].tap()
        let address = app.textFields["usage-address"]
        XCTAssertTrue(address.waitForExistence(timeout: 5))
        address.tap()
        address.typeText("https://magpie.example.test/v1")
        let key = app.secureTextFields["usage-key"]
        key.tap()
        key.typeText("placeholder-usage-key")
        app.buttons["usage-connect-confirm"].tap()
    }

    @MainActor
    private func text(_ app: XCUIApplication, containing value: String) -> XCUIElement {
        app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", value)).firstMatch
    }

    @MainActor
    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
