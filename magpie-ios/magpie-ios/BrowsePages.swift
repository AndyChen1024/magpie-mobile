import SwiftUI

struct SessionsPage: View {
    @Environment(\.magpieTheme) private var theme
    @State private var search = ""
    @State private var agent = ""
    @State private var selected: Session?

    private struct Session: Identifiable {
        let id: Int
        let title: String
        let agent: String
        let project: String
        let mark: String
        let summary: String

        static let samples: [Self] = [
            .init(id: 1, title: String(localized: "Port the Magpie mobile layout"), agent: "Codex", project: "magpie-mobile", mark: "CodexMark",
                  summary: String(localized: "Map the web app's tabs, agent cards and usage overview to SwiftUI views.")),
            .init(id: 2, title: String(localized: "Check the usage overview fields"), agent: "Claude Code", project: "magpie", mark: "ClaudeCodeMark",
                  summary: String(localized: "Compare the token, cache read, reasoning and call counts shown in the web app.")),
            .init(id: 3, title: String(localized: "Review empty session states"), agent: "Codex", project: "magpie-mobile", mark: "CodexMark",
                  summary: String(localized: "Check the messages for empty sessions and unmatched searches.")),
        ]
    }

    private var matches: [Session] {
        Session.samples.filter { item in
            (agent.isEmpty || item.agent == agent) &&
                (search.isEmpty || "\(item.title) \(item.project) \(item.agent)".localizedCaseInsensitiveContains(search))
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Menu {
                Picker("Agent", selection: $agent) {
                    Text("All Agents").tag("")
                    Text("Codex").tag("Codex")
                    Text("Claude Code").tag("Claude Code")
                }
            } label: {
                Label(agent.isEmpty ? String(localized: "All Agents") : agent, systemImage: "chevron.down")
                    .font(.subheadline.weight(.medium))
                    .frame(minHeight: 44)
            }

            MagpieSearch(title: String(localized: "Search sessions"), query: $search)

            if matches.isEmpty {
                MagpieMessage(title: String(localized: "No matching sessions"), message: String(localized: "Try a different search term or agent."), icon: "bubble.left")
            } else {
                MagpieCard {
                    ForEach(matches) { item in
                        Button {
                            selected = item
                        } label: {
                            MagpieRow(title: item.title, detail: "\(item.agent) · \(item.project)",
                                      icon: item.mark, isAsset: true, showsChevron: true)
                        }
                        .buttonStyle(.plain)
                        if item.id != matches.last?.id {
                            MagpieDivider()
                        }
                    }
                }
            }
        }
        .sheet(item: $selected) { item in
            MagpieDetail(title: item.title) {
                MagpieCard {
                    MagpieRow(title: item.agent, detail: item.project, icon: item.mark, isAsset: true)
                    MagpieDivider()
                    Text(item.summary)
                        .font(.subheadline)
                        .foregroundStyle(theme.secondary)
                        .padding(12)
                }
            }
        }
    }
}

struct LibraryPage: View {
    @State private var category = Category.instructions

    private enum Category: CaseIterable {
        case instructions, memory, servers, skills

        var title: String {
            switch self {
            case .instructions: String(localized: "Instructions")
            case .memory: String(localized: "Memory")
            case .servers: String(localized: "MCP Servers")
            case .skills: String(localized: "Skills")
            }
        }

        var emptyTitle: String {
            switch self {
            case .instructions: String(localized: "No instructions")
            case .memory: String(localized: "No memory entries")
            case .servers: String(localized: "No MCP servers")
            case .skills: String(localized: "No skills")
            }
        }

        var summary: String {
            switch self {
            case .instructions: String(localized: "Shared agent instructions appear here.")
            case .memory: String(localized: "Project memory appears here.")
            case .servers: String(localized: "MCP servers from the library appear here.")
            case .skills: String(localized: "Skills available to agents appear here.")
            }
        }

        var icon: String {
            switch self {
            case .instructions: "doc.text"
            case .memory: "text.book.closed"
            case .servers: "server.rack"
            case .skills: "square.stack"
            }
        }
    }

    var body: some View {
        VStack(spacing: 12) {
            MagpieSegments(title: String(localized: "Library Categories"), options: Category.allCases, selection: $category, label: \.title)
            MagpieMessage(title: category.emptyTitle, message: category.summary, icon: category.icon)
        }
    }
}

struct ProvidersPage: View {
    @State private var search = ""
    @State private var selected: Provider?

    private struct Provider: Identifiable {
        let name: String
        let host: String
        let mark: String
        let models: [DemoModel]

        var id: String { name }

        static let samples: [Self] = [
            .init(name: "Anthropic", host: "api.anthropic.com", mark: "ClaudeMark", models: [.sonnet]),
            .init(name: "OpenAI", host: "api.openai.com", mark: "OpenAIMark", models: [.gpt]),
            .init(name: "DeepSeek", host: "api.deepseek.com", mark: "DeepSeekMark", models: [.deepSeek]),
            .init(name: "OpenRouter", host: "openrouter.ai", mark: "OpenRouterMark", models: [.sonnet, .deepSeek]),
        ]
    }

    private var matches: [Provider] {
        Provider.samples.filter {
            search.isEmpty || "\($0.name) \($0.host)".localizedCaseInsensitiveContains(search)
        }
    }

    var body: some View {
        VStack(spacing: 12) {
            MagpieSearch(title: String(localized: "Find a provider"), query: $search)
            if matches.isEmpty {
                MagpieMessage(title: String(localized: "No matching providers"), message: String(localized: "Search by name or domain."), icon: "key.horizontal")
            } else {
                MagpieCard {
                    ForEach(matches) { item in
                        Button {
                            selected = item
                        } label: {
                            MagpieRow(title: item.name, detail: item.host, icon: item.mark, isAsset: true, showsChevron: true)
                        }
                        .buttonStyle(.plain)
                        if item.id != matches.last?.id {
                            MagpieDivider()
                        }
                    }
                }
            }
        }
        .sheet(item: $selected) { item in
            MagpieDetail(title: item.name) {
                MagpieCard {
                    MagpieRow(title: String(localized: "Domain"), detail: item.host, icon: "network")
                    ForEach(item.models) { model in
                        MagpieDivider()
                        MagpieRow(title: model.rawValue, detail: String(localized: "Demo Model"), icon: model.mark, isAsset: true)
                    }
                }
            }
        }
    }
}

struct OverviewPage: View {
    let page: MagpiePage

    var body: some View {
        switch page {
        case .routing:
            MagpieMessage(title: String(localized: "No routing rules"), message: String(localized: "Connect to a Magpie host to see its routing configuration."), icon: page.icon)
        case .gateway:
            MagpieCard {
                MagpieRow(title: String(localized: "Gateway Status"), detail: String(localized: "No host connected"), icon: page.icon)
                MagpieDivider()
                MagpieRow(title: String(localized: "Recent Calls"), detail: String(localized: "No records"), icon: "clock")
            }
        case .masking:
            MagpieMessage(title: String(localized: "No redaction rules"), message: String(localized: "The host's redaction rules appear here."), icon: page.icon)
        case .plugins:
            MagpieMessage(title: String(localized: "No plugins"), message: String(localized: "Plugins installed on the host appear here."), icon: page.icon)
        default:
            EmptyView()
        }
    }
}

struct SettingsPage: View {
    @Binding var appearance: MagpieAppearance
    @ObservedObject var usage: UsageWorkspace

    var body: some View {
        MagpieDetail(title: String(localized: "Settings")) {
            MagpieCard {
                Picker("Appearance", selection: $appearance) {
                    ForEach(MagpieAppearance.allCases) { item in
                        Text(item.title).tag(item)
                    }
                }
                .pickerStyle(.menu)
                .padding(.horizontal, 12)
                .frame(minHeight: 48)
                .accessibilityIdentifier("appearance")
                MagpieDivider()
                MagpieRow(title: String(localized: "Usage Host"),
                          detail: usage.origin?.address ?? String(localized: "No Magpie host connected"), icon: "server.rack")
            }
        }
    }
}
