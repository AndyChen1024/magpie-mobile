import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

struct UsagePage: View {
    @Environment(\.magpieTheme) private var theme
    @Environment(\.locale) private var locale

    @ObservedObject var workspace: UsageWorkspace
    @State private var showsConnection = false
    @State private var showsDemo = false
    @State private var selected: UsageRequestRow?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if showsDemo {
                Button {
                    showsDemo = false
                } label: {
                    Label("Back to LAN Usage", systemImage: "chevron.left")
                }
                .frame(minHeight: 44)
                UsageDemoPage()
            } else if let origin = workspace.origin {
                connectionHeader(origin)
                filters
                switch workspace.section {
                case .overview: overview
                case .requests: requests
                }
            } else {
                disconnected
            }
        }
        .onAppear { workspace.restoreIfNeeded() }
        .onValueChange(of: workspace.observationID) { _ in selected = nil }
        .onValueChange(of: workspace.range) { _ in selected = nil }
        .sheet(isPresented: $showsConnection) {
            UsageConnectionForm(workspace: workspace)
        }
        .sheet(item: $selected) { item in requestDetail(item) }
    }

    private var disconnected: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let issue = workspace.storageIssue {
                MagpieMessage(title: String(localized: "Could not load the connection"), message: issue.localizedDescription, icon: "lock")
                Button("Retry", action: workspace.retryRestore)
                    .frame(minHeight: 44)
            } else {
                MagpieMessage(
                    title: String(localized: "Connect to a Magpie Host"),
                    message: String(localized: "Use the address and key of a Magpie gateway on your local network to see usage and request details."),
                    icon: "server.rack"
                )
            }

            Button {
                showsConnection = true
            } label: {
                Label("Connection Settings", systemImage: "gearshape")
                    .foregroundStyle(theme.background)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .accessibilityIdentifier("usage-connect")

            Button("View Demo Usage", action: { showsDemo = true })
                .frame(minHeight: 44)
                .accessibilityIdentifier("usage-demo")
        }
    }

    private func connectionHeader(_ origin: UsageOrigin) -> some View {
        MagpieCard {
            Button {
                showsConnection = true
            } label: {
                MagpieRow(title: workspace.isFixture ? String(localized: "Test Data") : String(localized: "Magpie Host"),
                          detail: origin.address, icon: "server.rack", showsChevron: true)
                    .padding(.trailing, 4)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("usage-connect")
        }
    }

    private var filters: some View {
        VStack(spacing: 0) {
            MagpieSegments(title: String(localized: "Usage Sections"), options: UsageSection.allCases, selection: Binding(
                get: { workspace.section }, set: { workspace.select($0) }
            ), label: \.title)

            HStack {
                UsageRangePicker(range: Binding(get: { workspace.range }, set: { workspace.select($0) }))
                Spacer()
                Button(action: workspace.refresh) {
                    Label("Refresh", systemImage: "arrow.clockwise")
                        .labelStyle(.iconOnly)
                }
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
                .buttonStyle(.plain)
                .foregroundStyle(theme.muted)
                .accessibilityIdentifier("usage-refresh")
            }
        }
    }

    @ViewBuilder
    private var overview: some View {
        switch workspace.snapshot.overview {
        case .loading:
            loading(String(localized: "Loading overview"))
        case let .loaded(summary):
            overviewCard(summary)
        case .empty:
            MagpieMessage(title: String(localized: "No usage"), message: String(localized: "The host has no usage in this time range."), icon: "chart.bar")
        case let .failed(issue):
            issueCard(issue, retry: workspace.refresh)
        }
    }

    @ViewBuilder
    private var requests: some View {
        switch workspace.snapshot.requests {
        case .loading:
            loading(String(localized: "Loading requests"))
        case let .loaded(list):
            Text("Requests: \(list.rows.count) of \(list.total)")
                .font(.footnote)
                .foregroundStyle(theme.muted)
                .accessibilityIdentifier("usage-request-count")
            LazyVStack(spacing: 8) {
                ForEach(list.rows) { row in
                    MagpieCard {
                        Button {
                            selected = row
                        } label: {
                            let agent = row.agent ?? String(localized: "Agent unavailable")
                            MagpieRow(title: row.model ?? String(localized: "Model unavailable"),
                                      detail: "\(agent) · \(formattedDate(row)) · \(status(row))",
                                      icon: "doc.text", showsChevron: true)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("usage-request-\(row.id)")
                    }
                }
            }
            switch list.next {
            case .available:
                Button("Load More", action: workspace.loadMore)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("usage-load-more")
            case .loading:
                loading(String(localized: "Loading more requests"))
            case let .failed(issue):
                issueCard(issue, retry: workspace.loadMore)
            case .finished:
                EmptyView()
            }
        case .empty:
            MagpieMessage(title: String(localized: "No requests"), message: String(localized: "There are no visible requests in this time range."), icon: "doc.text")
        case let .failed(issue):
            issueCard(issue, retry: workspace.refresh)
        }
    }

    private func overviewCard(_ summary: UsageSummary) -> some View {
        let tokens = Decimal(summary.input) + Decimal(summary.output)
        let input = summary.input.formatted(.number.locale(locale))
        let output = summary.output.formatted(.number.locale(locale))
        return MagpieCard {
            MagpieRow(title: String(localized: "\(summary.calls) calls"),
                      detail: String(localized: "\(summary.errors) errors"), icon: "chart.bar")
            MagpieDivider()
            MagpieRow(title: String(localized: "Tokens: \(tokens.formatted(.number.locale(locale)))"),
                      detail: String(localized: "Input \(input) · Output \(output)"), icon: "number")
            MagpieDivider()
            MagpieRow(title: String(localized: "Cost \(money(summary.knownCost))"),
                      detail: summary.unpriced > 0
                          ? String(localized: "\(summary.unpriced) unpriced requests")
                          : String(localized: "In US dollars"),
                      icon: "dollarsign.circle")
        }
        .accessibilityIdentifier("usage-summary")
    }

    private func requestDetail(_ row: UsageRequestRow) -> some View {
        MagpieDetail(title: String(localized: "Request Details")) {
            MagpieCard {
                MagpieRow(title: row.model ?? String(localized: "Model unavailable"),
                          detail: row.agent ?? String(localized: "Agent unavailable"), icon: "doc.text")
                MagpieDivider()
                MagpieRow(title: String(localized: "Time"), detail: formattedDate(row), icon: "clock")
                MagpieDivider()
                MagpieRow(title: String(localized: "Status"), detail: status(row), icon: "info.circle")
                MagpieDivider()
                MagpieRow(title: String(localized: "Cost"), detail: money(row.knownCost), icon: "dollarsign.circle")
            }
        }
    }

    private func loading(_ title: String) -> some View {
        ProgressView(title)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 24)
    }

    private func issueCard(_ issue: UsageIssue, retry: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            MagpieMessage(title: issue.title, message: issue.message, icon: "exclamationmark.circle")
                .accessibilityIdentifier("usage-issue")

            if issue == .keyRestricted && workspace.section == .overview {
                Button("View Requests") { workspace.select(.requests) }
                    .buttonStyle(.bordered)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("usage-open-requests")
            } else {
                Button("Retry", action: retry)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("usage-retry")
            }

            #if canImport(UIKit)
            if issue == .localNetworkDenied, let settings = URL(string: UIApplication.openSettingsURLString) {
                Link("Open Settings", destination: settings)
                    .frame(minHeight: 44)
            }
            #endif
        }
    }

    private func formattedDate(_ row: UsageRequestRow) -> String {
        row.timestamp?.formatted(.dateTime.year().month(.abbreviated).day().hour().minute().locale(locale))
            ?? String(localized: "Time unavailable")
    }

    private func status(_ row: UsageRequestRow) -> String {
        guard let value = row.httpStatus, (100...599).contains(value) else { return String(localized: "Status unavailable") }
        return "HTTP \(value)"
    }

    private func money(_ amount: Decimal?) -> String {
        guard let amount else { return String(localized: "Unknown") }
        let format = Decimal.FormatStyle.Currency(code: "USD", locale: locale)
            .precision(.fractionLength(2...8))
        let minimum = Decimal(sign: .plus, exponent: -8, significand: 1)
        if amount > .zero, amount < minimum { return "<\(minimum.formatted(format))" }
        return amount.formatted(format)
    }
}

struct UsageRangePicker: View {
    @Environment(\.magpieTheme) private var theme

    @Binding var range: UsageRange

    var body: some View {
        Menu {
            Picker("Time Range", selection: $range) {
                ForEach(UsageRange.allCases, id: \.self) { item in
                    Text(item.title).tag(item)
                }
            }
        } label: {
            HStack(spacing: 5) {
                Text(range.title).font(.headline)
                Image(systemName: "chevron.down")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(theme.muted)
            }
            .frame(minHeight: 44)
        }
        .accessibilityLabel("Time Range")
        .accessibilityValue(range.title)
        .accessibilityIdentifier("usage-period")
    }
}
