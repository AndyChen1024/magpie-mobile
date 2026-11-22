//
//  magpie_iosApp.swift
//  magpie-ios
//
//  Created by Andy on 2026/11/19.
//

import SwiftUI

@main
struct magpie_iosApp: App {
    @StateObject private var usage = Self.makeUsageWorkspace()

    var body: some Scene {
        WindowGroup {
            ContentView(usage: usage)
        }
    }

    private static func makeUsageWorkspace() -> UsageWorkspace {
        #if DEBUG
        if let name = ProcessInfo.processInfo.environment["MAGPIE_USAGE_FIXTURE"] {
            if name == "unavailable" { return UsageWorkspace() }
            if let scenario = UsageFixtureScenario(rawValue: name) {
                return UsageFixtureScenario.workspace(scenario)
            }
        }
        #endif
        #if os(iOS) && canImport(MagpieShared)
        return UsageWorkspace(makeSession: { SharedUsageSession($0) }, parseOrigin: { try SharedUsageSession.parseOrigin($0) })
        #else
        return UsageWorkspace()
        #endif
    }
}
