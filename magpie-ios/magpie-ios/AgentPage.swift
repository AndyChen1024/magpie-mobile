import SwiftUI

enum DemoModel: String, CaseIterable, Identifiable {
    case sonnet = "Claude Sonnet 4.5"
    case gpt = "GPT-6.1-Sol"
    case deepSeek = "DeepSeek V3.2"

    var id: Self { self }

    var mark: String {
        switch self {
        case .sonnet: "ClaudeMark"
        case .gpt: "OpenAIMark"
        case .deepSeek: "DeepSeekMark"
        }
    }
}

struct DemoAgent: Identifiable {
    let id: String
    let name: String
    let mark: String
    var model: DemoModel
    var isEnabled = true

    static let samples = [
        Self(id: "claude", name: "Claude Code", mark: "ClaudeCodeMark", model: .sonnet),
        Self(id: "codex", name: "Codex", mark: "CodexMark", model: .gpt),
        Self(id: "omp", name: "omp", mark: "OmpMark", model: .gpt),
    ]
}

struct DemoProfile: Identifiable {
    let name: String
    let agents: [DemoAgent]

    var id: String { name }
}

struct AgentPage: View {
    @Environment(\.magpieTheme) private var theme

    @Binding var agents: [DemoAgent]
    @Binding var profiles: [DemoProfile]
    @State private var presented: Detail?
    @State private var showsSaveProfile = false
    @State private var profileName = ""

    private enum Detail: Identifiable {
        case agent(String), manage

        var id: String {
            switch self {
            case let .agent(id): id
            case .manage: "manage"
            }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Once enabled, **magpie** appears as a provider in the agent's model list. You can also switch models below.")
                .font(.subheadline)
                .foregroundStyle(theme.secondary)
                .fixedSize(horizontal: false, vertical: true)

            MagpieCard {
                ForEach($agents) { $agent in
                    AgentRow(agent: $agent) {
                        presented = .agent(agent.id)
                    }
                    MagpieDivider()
                }

                Button {
                    presented = .manage
                } label: {
                    Label("Manage", systemImage: "list.bullet")
                }
                .font(.subheadline)
                .foregroundStyle(theme.muted)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .trailing)
                .padding(.horizontal, 12)
                .contentShape(Rectangle())
                .buttonStyle(.plain)
            }

            profileBar
        }
        .sheet(item: $presented) { item in
            switch item {
            case let .agent(id):
                if let agent = agents.first(where: { $0.id == id }) {
                    MagpieDetail(title: agent.name) {
                        MagpieCard {
                            MagpieRow(title: String(localized: "Integration Status"),
                                      detail: agent.isEnabled ? String(localized: "Enabled") : String(localized: "Disabled"),
                                      icon: "point.3.connected.trianglepath.dotted")
                            MagpieDivider()
                            MagpieRow(title: String(localized: "Current Model"), detail: agent.model.rawValue, icon: agent.model.mark, isAsset: true)
                        }
                    }
                }
            case .manage:
                MagpieDetail(title: String(localized: "Manage Agents")) {
                    MagpieCard {
                        ForEach($agents) { $agent in
                            Toggle(isOn: $agent.isEnabled) {
                                Label {
                                    Text(agent.name)
                                } icon: {
                                    Image(agent.mark).resizable().scaledToFit().frame(width: 22, height: 22)
                                }
                            }
                            .tint(theme.switchTint)
                            .padding(12)
                            if agent.id != agents.last?.id {
                                MagpieDivider()
                            }
                        }
                    }
                }
            }
        }
        .alert("Save Profile", isPresented: $showsSaveProfile) {
            TextField("Profile name", text: $profileName)
            Button("Cancel", role: .cancel) {}
            Button("Save", action: saveProfile)
                .disabled(profileName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        } message: {
            Text("Save the current demo agent and model settings for this run.")
        }
    }

    private var profileBar: some View {
        HStack(alignment: .center, spacing: 10) {
            Text("Profiles")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(theme.muted)
            if profiles.isEmpty {
                Text("No profiles yet")
                    .font(.footnote)
                    .foregroundStyle(theme.muted)
            } else {
                Menu {
                    ForEach(profiles) { profile in
                        Button(profile.name) {
                            agents = profile.agents
                        }
                    }
                } label: {
                    Label("Apply Profile", systemImage: "chevron.down")
                        .font(.footnote)
                        .frame(minHeight: 44)
                }
                .accessibilityIdentifier("apply-profile")
            }

            Spacer(minLength: 0)
            Button {
                profileName = ""
                showsSaveProfile = true
            } label: {
                Label("Save Current", systemImage: "plus")
                    .font(.subheadline)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            .foregroundStyle(theme.secondary)
        }
        .padding(.horizontal, 4)
    }

    private func saveProfile() {
        let name = profileName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        let profile = DemoProfile(name: name, agents: agents)
        if let index = profiles.firstIndex(where: { $0.name == name }) {
            profiles[index] = profile
        } else {
            profiles.append(profile)
        }
    }
}

private struct AgentRow: View {
    @Environment(\.magpieTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize

    @Binding var agent: DemoAgent
    let showDetails: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if typeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 2) {
                    identity
                    controls
                }
            } else {
                HStack(spacing: 8) {
                    identity
                    controls
                }
            }

            if agent.isEnabled {
                modelPicker
                    .padding(.leading, typeSize.isAccessibilitySize ? 0 : 36)
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 8)
    }

    private var identity: some View {
        HStack(spacing: 12) {
            Image(agent.mark)
                .resizable()
                .scaledToFit()
                .frame(width: 24, height: 24)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                Text(agent.name)
                    .font(.body.weight(.medium))
                    .accessibilityAddTraits(.isHeader)
                HStack(spacing: 5) {
                    Circle()
                        .fill(agent.isEnabled ? theme.green : theme.muted)
                        .frame(width: 6, height: 6)
                        .accessibilityHidden(true)
                    Text(agent.isEnabled ? String(localized: "Enabled · Switch with /model") : String(localized: "Disabled"))
                        .font(.footnote)
                        .foregroundStyle(theme.muted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var controls: some View {
        HStack(spacing: 12) {
            Button(action: showDetails) {
                HStack(spacing: 4) {
                    Text("Details")
                    Image(systemName: "chevron.right")
                        .font(.caption2.weight(.semibold))
                        .accessibilityHidden(true)
                }
                .font(.footnote)
                .foregroundStyle(theme.secondary)
                .fixedSize(horizontal: true, vertical: false)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(agent.name) details")
            .accessibilityIdentifier("agent-detail-\(agent.id)")

            Toggle(agent.name, isOn: $agent.isEnabled)
                .labelsHidden()
                .tint(theme.switchTint)
                .frame(minHeight: 44)
                .accessibilityLabel("Enable \(agent.name) integration")
                .accessibilityIdentifier("agent-toggle-\(agent.id)")
        }
    }

    private var modelPicker: some View {
        Menu {
            Picker("Model", selection: $agent.model) {
                ForEach(DemoModel.allCases) { model in
                    Text(model.rawValue).tag(model)
                }
            }
        } label: {
            HStack(spacing: 6) {
                Image(agent.model.mark)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 16, height: 16)
                    .accessibilityHidden(true)
                Text(agent.model.rawValue)
                    .font(.subheadline.weight(.medium))
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 4)
                Image(systemName: "chevron.down")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(theme.muted)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .frame(minHeight: 44)
            .background(theme.control, in: RoundedRectangle(cornerRadius: 7))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(agent.name) model")
        .accessibilityValue(agent.model.rawValue)
        .accessibilityIdentifier("agent-model-\(agent.id)")
    }
}
