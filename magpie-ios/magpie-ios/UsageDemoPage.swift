import Charts
import SwiftUI

struct UsageDemoPage: View {
    @Environment(\.magpieTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.locale) private var locale
    @State private var tab = Tab.overview
    @State private var period = UsageRange.today
    @State private var selected: UsageSample?

    private enum Tab: CaseIterable {
        case overview, requests, sessions, context

        var title: String {
            switch self {
            case .overview: String(localized: "Overview")
            case .requests: String(localized: "Requests")
            case .sessions: String(localized: "Sessions")
            case .context: String(localized: "Context")
            }
        }
    }

    var body: some View {
        let calendar = Calendar.current
        let end = calendar.startOfDay(for: .now)
        let start = calendar.date(byAdding: .day, value: 1 - period.days, to: end) ?? end
        let requests = UsageSample.requests.filter { $0.date >= start }
        let totals = UsageTotals(requests: requests)

        VStack(alignment: .leading, spacing: 10) {
            MagpieSegments(title: String(localized: "Usage Sections"), options: Tab.allCases, selection: $tab, label: \.title)
            HStack {
                UsageRangePicker(range: $period)

                Spacer()
                Text(formattedCost(totals))
                    .font(.headline.monospacedDigit())
                    .accessibilityLabel("Cost \(formattedCost(totals))")
            }

            switch tab {
            case .overview:
                Text("Shows sample gateway requests.")
                    .font(.footnote)
                    .foregroundStyle(theme.muted)
                metrics(totals)
                trend(requests, start: start, calendar: calendar)
                breakdown("AGENT", requests: requests, key: \.agent.name)
                breakdown(String(localized: "Models"), requests: requests, key: \.agent.model.rawValue)
            case .requests:
                requestList(requests)
            case .sessions:
                breakdown(String(localized: "Sessions"), requests: requests, key: \.session)
            case .context:
                MagpieMessage(title: String(localized: "No context records"), message: String(localized: "Context usage appears after the host collects it."), icon: "text.alignleft")
            }
        }
        .sheet(item: $selected) { request in
            MagpieDetail(title: String(localized: "Request Details")) {
                MagpieCard {
                    MagpieRow(title: request.agent.model.rawValue, detail: request.agent.name,
                              icon: request.agent.mark, isAsset: true)
                    MagpieDivider()
                    MagpieRow(title: String(localized: "Status"), detail: request.status, icon: "point.3.connected.trianglepath.dotted")
                    MagpieDivider()
                    MagpieRow(title: "Token", detail: tokenBreakdown(input: request.input, output: request.output),
                              icon: "number")
                    MagpieDivider()
                    MagpieRow(title: String(localized: "Cache Reads"), detail: compact(request.cache), icon: "internaldrive")
                }
            }
        }
    }

    private func metrics(_ totals: UsageTotals) -> some View {
        MagpieCard {
            if typeSize.isAccessibilitySize {
                tokenMetric(totals)
                MagpieDivider()
                cacheMetric(totals)
                MagpieDivider()
                reasoningMetric(totals)
                MagpieDivider()
                requestMetric(totals)
            } else {
                Grid(horizontalSpacing: 0, verticalSpacing: 0) {
                    GridRow {
                        tokenMetric(totals)
                            .overlay(alignment: .trailing) { verticalRule }
                        cacheMetric(totals)
                    }
                    MagpieDivider()
                    GridRow {
                        reasoningMetric(totals)
                            .overlay(alignment: .trailing) { verticalRule }
                        requestMetric(totals)
                    }
                }
            }
        }
    }

    private var verticalRule: some View {
        Rectangle().fill(theme.line).frame(width: 1).accessibilityHidden(true)
    }

    private func tokenMetric(_ totals: UsageTotals) -> some View {
        metric(compact(totals.tokens), title: "TOKEN", detail: tokenBreakdown(input: totals.input, output: totals.output))
    }

    private func cacheMetric(_ totals: UsageTotals) -> some View {
        let rate = Double(totals.cache) / Double(max(1, totals.input + totals.cache))
        let hitRate = rate.formatted(.percent.precision(.fractionLength(2)).locale(locale))
        return metric(compact(totals.cache), title: String(localized: "Cache Reads"), detail: String(localized: "Hit rate \(hitRate)"))
    }

    private func reasoningMetric(_ totals: UsageTotals) -> some View {
        metric(compact(totals.reasoning), title: String(localized: "Reasoning"), detail: String(localized: "Included in output"))
    }

    private func requestMetric(_ totals: UsageTotals) -> some View {
        metric(totals.count.formatted(.number.locale(locale)), title: String(localized: "Calls"),
               detail: String(localized: "\(totals.failures) failed"))
    }

    private func metric(_ value: String, title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value)
                .font(.title2.weight(.semibold).monospacedDigit())
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(theme.muted)
            Text(detail)
                .font(.caption)
                .foregroundStyle(theme.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityElement(children: .combine)
    }

    private func trend(_ requests: [UsageSample], start: Date, calendar: Calendar) -> some View {
        let unit: Calendar.Component = period == .today ? .hour : .day
        let elapsedHours = calendar.dateComponents([.hour], from: start, to: .now).hour ?? 0
        let count = period == .today ? max(1, elapsedHours + 1) : period.days
        let byDate = Dictionary(grouping: requests) {
            calendar.dateInterval(of: unit, for: $0.date)?.start ?? $0.date
        }
        let buckets = (0..<count).compactMap { offset in
            calendar.date(byAdding: unit, value: offset, to: start).map {
                ($0, UsageTotals(requests: byDate[$0, default: []]))
            }
        }

        return MagpieCard {
            Chart(buckets, id: \.0) { date, totals in
                BarMark(x: .value(String(localized: "Time"), date, unit: unit),
                        y: .value(String(localized: "Output Tokens"), totals.output))
                    .foregroundStyle(theme.accent)
                    .accessibilityLabel("Output Tokens")
                    .accessibilityValue(totals.output.formatted())
                BarMark(x: .value(String(localized: "Time"), date, unit: unit),
                        y: .value(String(localized: "Input Tokens"), totals.input))
                    .foregroundStyle(theme.accent.opacity(0.35))
                    .accessibilityLabel("Input Tokens")
                    .accessibilityValue(totals.input.formatted())
            }
            .chartYAxis(.hidden)
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) {
                    if period == .today {
                        AxisValueLabel(format: .dateTime.hour().minute())
                    } else {
                        AxisValueLabel(format: .dateTime.month(.twoDigits).day(.twoDigits))
                    }
                }
            }
            .accessibilityLabel("Token Usage Over Time")
            .frame(height: 106)
            .padding(12)
        }
    }

    private func breakdown(
        _ title: String, requests: [UsageSample], key: KeyPath<UsageSample, String>
    ) -> some View {
        let byName = Dictionary(grouping: requests) { $0[keyPath: key] }
        let groups: [UsageGroup] = byName.map { name, rows in
            UsageGroup(name: name, totals: UsageTotals(requests: rows), mark: rows.first?.agent.mark ?? "MagpieMark")
        }
        let ranked = groups.sorted {
            if $0.totals.tokens == $1.totals.tokens { return $0.name < $1.name }
            return $0.totals.tokens > $1.totals.tokens
        }
        let maximum = Double(max(1, ranked.first?.totals.tokens ?? 0))

        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Text("Token")
                    .font(.caption)
            }
            .foregroundStyle(theme.muted)
            .padding(.horizontal, 4)

            MagpieCard {
                ForEach(ranked, id: \.name) { group in
                    VStack(alignment: .leading, spacing: 0) {
                        let calls = String(localized: "\(group.totals.count) calls")
                        let failures = String(localized: "\(group.totals.failures) failed")
                        MagpieRow(title: group.name, detail: "\(calls) · \(failures)", icon: group.mark, isAsset: true)
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(compact(group.totals.tokens))
                                    .font(.subheadline.weight(.semibold).monospacedDigit())
                                Spacer()
                                Text(formattedCost(group.totals))
                                    .font(.footnote.monospacedDigit())
                                    .foregroundStyle(theme.green)
                            }
                            ProgressView(value: Double(group.totals.tokens), total: maximum)
                                .tint(theme.accent)
                                .accessibilityHidden(true)
                        }
                        .padding(.leading, 48)
                        .padding([.bottom, .trailing], 12)
                    }

                    if group.name != ranked.last?.name {
                        MagpieDivider()
                    }
                }
            }
        }
        .padding(.top, 8)
    }

    private func requestList(_ requests: [UsageSample]) -> some View {
        LazyVStack(alignment: .leading, spacing: 8) {
            ForEach(requests) { request in
                MagpieCard {
                    Button {
                        selected = request
                    } label: {
                        let time = request.date.formatted(.dateTime.year().month(.abbreviated).day().hour().minute().locale(locale))
                        MagpieRow(title: request.agent.model.rawValue,
                                  detail: "\(request.agent.name) · \(time) · \(request.status)",
                                  icon: request.agent.mark, isAsset: true, showsChevron: true)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("usage-request-\(request.id)")
                }
            }
        }
    }

    private func compact(_ value: Int) -> String {
        value.formatted(.number.notation(.compactName).locale(locale))
    }

    private func tokenBreakdown(input: Int, output: Int) -> String {
        String(localized: "Input \(compact(input)) · Output \(compact(output))")
    }

    private func formattedCost(_ totals: UsageTotals) -> String {
        (Double(totals.cents) / 100).formatted(.currency(code: "USD").locale(locale))
    }
}

private struct UsageGroup {
    let name: String
    let totals: UsageTotals
    let mark: String
}

private struct UsageSample: Identifiable {
    let id: Int
    let date: Date
    let agent: DemoAgent
    let session: String
    let input: Int
    let output: Int
    let cache: Int
    let reasoning: Int
    let cents: Int
    let failed: Bool

    var status: String { failed ? "HTTP 429" : "HTTP 200" }

    static let requests: [Self] = {
        let calendar = Calendar.current
        let now = Date.now
        let today = calendar.startOfDay(for: now)
        let agents = Array(DemoAgent.samples.prefix(2))
        return (0..<30).flatMap { day -> [Self] in
            guard let start = calendar.date(byAdding: .day, value: -day, to: today),
                  let interval = calendar.dateInterval(of: .day, for: start)
            else { return [] }
            let duration = min(now, interval.end).timeIntervalSince(start)
            let count = day == 0 ? 123 : 16 + day % 11
            return (0..<count).map { index in
                let id = day * 1000 + index
                let agent = agents[(index / 4) % agents.count]
                let failed = id % 113 < 2
                let elapsed = duration * Double(count - index) / Double(count + 1)
                return Self(
                    id: id, date: start.addingTimeInterval(elapsed), agent: agent,
                    session: agent.id == "codex"
                        ? String(localized: "Port the Magpie mobile layout")
                        : String(localized: "Check the usage overview fields"),
                    input: failed ? 0 : 820 + id % 380,
                    output: failed ? 0 : 470 + id % 190,
                    cache: failed ? 0 : 4100 + id % 770,
                    reasoning: failed ? 0 : 65 + id % 85,
                    cents: failed ? 0 : 8 + id % 6, failed: failed
                )
            }
        }
    }()
}

private struct UsageTotals {
    var input = 0
    var output = 0
    var cache = 0
    var reasoning = 0
    var count = 0
    var failures = 0
    var cents = 0

    var tokens: Int { input + output }

    init(requests: [UsageSample]) {
        count = requests.count
        for request in requests {
            input += request.input
            output += request.output
            cache += request.cache
            reasoning += request.reasoning
            cents += request.cents
            if request.failed { failures += 1 }
        }
    }
}
