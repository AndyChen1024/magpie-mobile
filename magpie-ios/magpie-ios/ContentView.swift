import SwiftUI

enum MagpiePage: String, Identifiable {
    case agents, sessions, library, providers, routing, gateway, usage, masking, plugins
    #if os(iOS)
    case phoneConnection
    #endif

    static let tabs: [Self] = [.agents, .sessions, .library, .providers]
    #if os(iOS)
    static let overflow: [Self] = [.routing, .gateway, .usage, .phoneConnection, .masking, .plugins]
    #else
    static let overflow: [Self] = [.routing, .gateway, .usage, .masking, .plugins]
    #endif

    var id: String { rawValue }

    var title: String {
        switch self {
        case .agents: "Agent"
        case .sessions: String(localized: "Sessions")
        case .library: String(localized: "Library")
        case .providers: String(localized: "Providers")
        case .routing: String(localized: "Routing")
        case .gateway: String(localized: "Gateway")
        case .usage: String(localized: "Usage")
        case .masking: String(localized: "Redaction")
        case .plugins: String(localized: "Plugins")
        #if os(iOS)
        case .phoneConnection: String(localized: "Connect Phone")
        #endif
        }
    }

    var icon: String {
        switch self {
        case .agents: "terminal"
        case .sessions: "bubble.left"
        case .library: "book.closed"
        case .providers: "key.horizontal"
        case .routing: "arrow.triangle.branch"
        case .gateway: "server.rack"
        case .usage: "chart.bar"
        case .masking: "shield"
        case .plugins: "puzzlepiece.extension"
        #if os(iOS)
        case .phoneConnection: "iphone"
        #endif
        }
    }

    var contextLabel: String? {
        switch self {
        case .usage: String(localized: "LAN Usage")
        #if os(iOS)
        case .phoneConnection: nil
        #endif
        default: String(localized: "Demo Data")
        }
    }
}

enum MagpieAppearance: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: Self { self }

    var title: String {
        switch self {
        case .system: String(localized: "System")
        case .light: String(localized: "Light")
        case .dark: String(localized: "Dark")
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

struct ContentView: View {
    @AppStorage("magpie.appearance") private var appearance = MagpieAppearance.system
    @Environment(\.scenePhase) private var scenePhase
    @State private var backgrounded = false
    @ObservedObject private var usage: UsageWorkspace
    #if os(iOS)
    @StateObject private var phone = PhoneWorkspace()
    #endif

    init(usage: UsageWorkspace) {
        self.usage = usage
    }

    var body: some View {
        MagpieWorkspace(appearance: $appearance, usage: usage)
            #if os(iOS)
            .environmentObject(phone)
            .task { if scenePhase == .active { phone.foregrounded() } }
            #endif
            .preferredColorScheme(appearance.colorScheme)
            .task(id: usage.observationID) { await usage.observe() }
            .onValueChange(of: scenePhase) { phase in
                #if os(iOS)
                if phase == .active { phone.foregrounded() }
                if phase == .background { phone.backgrounded() }
                #endif
                if phase == .background { backgrounded = true }
                if phase == .active && backgrounded {
                    backgrounded = false
                    usage.foregrounded()
                }
            }
    }
}

private struct MagpieWorkspace: View {
    @Environment(\.colorScheme) private var colorScheme

    @Binding var appearance: MagpieAppearance
    let usage: UsageWorkspace
    #if os(iOS)
    @EnvironmentObject private var phone: PhoneWorkspace
    #endif
    @State private var page = MagpiePage.agents
    @State private var showsMore = false
    @State private var showsSettings = false
    @State private var agents = DemoAgent.samples
    @State private var profiles: [DemoProfile] = []

    private var theme: MagpieTheme {
        MagpieTheme(isDark: colorScheme == .dark)
    }

    var body: some View {
        VStack(spacing: 0) {
            header
                .accessibilityHidden(showsMore)

            ScrollView {
                pageContent
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 24)
                    .frame(maxWidth: 680)
                    .frame(maxWidth: .infinity)
            }
            .id(page)
            .scrollDismissesKeyboard(.interactively)
            .accessibilityHidden(showsMore)
            .overlay {
                if showsMore {
                    ZStack(alignment: .bottomTrailing) {
                        Color.black.opacity(0.001)
                            .accessibilityHidden(true)
                            .onTapGesture { showsMore = false }

                        ViewThatFits(in: .vertical) {
                            moreMenu.fixedSize(horizontal: false, vertical: true)
                            ScrollView { moreMenu }
                                .frame(maxWidth: 260)
                        }
                        .padding(.trailing, 8)
                        .padding(.bottom, 6)
                    }
                }
            }

            tabBar
        }
        .foregroundStyle(theme.ink)
        .tint(theme.ink)
        .background(theme.background.ignoresSafeArea())
        .sheet(isPresented: $showsSettings) {
            SettingsPage(appearance: $appearance, usage: usage)
        }
        .environment(\.magpieTheme, theme)
    }

    private var header: some View {
        HStack(spacing: 6) {
            Image("MagpieMark")
                .resizable()
                .scaledToFit()
                .frame(width: 19, height: 19)
                .accessibilityHidden(true)
            Text("magpie")
                .font(.headline)

            Spacer(minLength: 8)
            if let label = page.contextLabel {
                Text(label)
                    .font(.caption)
                    .foregroundStyle(theme.muted)
            }
            Button {
                showsMore = false
                showsSettings = true
            } label: {
                Label("Settings", systemImage: "gearshape")
            }
            .labelStyle(.iconOnly)
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
            .buttonStyle(.plain)
            .foregroundStyle(theme.muted)
            .accessibilityIdentifier("settings")
        }
        .padding(.leading, 12)
        .padding(.trailing, 8)
        .frame(minHeight: 48)
    }

    @ViewBuilder
    private var pageContent: some View {
        switch page {
        case .agents: AgentPage(agents: $agents, profiles: $profiles)
        case .sessions: SessionsPage()
        case .library: LibraryPage()
        case .providers: ProvidersPage()
        case .usage: UsagePage(workspace: usage)
        #if os(iOS)
        case .phoneConnection: PhoneConnectionPage(workspace: phone)
        #endif
        case .routing, .gateway, .masking, .plugins: OverviewPage(page: page)
        }
    }

    private var tabBar: some View {
        VStack(spacing: 0) {
            MagpieDivider()

            HStack(spacing: 0) {
                ForEach(MagpiePage.tabs) { tab in
                    tabButton(tab.title, icon: tab.icon, selected: page == tab, id: tab.id) {
                        open(tab)
                    }
                }
                tabButton(String(localized: "More"), icon: "ellipsis", selected: showsMore || MagpiePage.overflow.contains(page), id: "more") {
                    showsMore.toggle()
                }
                .accessibilityValue(showsMore ? String(localized: "Expanded") : String(localized: "Collapsed"))
                .accessibilityAction(.escape) { showsMore = false }
            }
            .padding(.horizontal, 4)
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        }
        .background(theme.background.ignoresSafeArea(edges: .bottom))
    }

    private func tabButton(
        _ title: String, icon: String, selected: Bool, id: String, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 19, weight: .medium))
                    .frame(height: 21)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.caption2.weight(.medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .frame(maxWidth: .infinity, minHeight: 50)
            .contentShape(Rectangle())
            .foregroundStyle(selected ? theme.ink : theme.muted)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("tab-\(id)")
    }

    private var moreMenu: some View {
        MagpieCard {
            VStack(spacing: 0) {
                ForEach(MagpiePage.overflow) { entry in
                    Button {
                        open(entry)
                    } label: {
                        Label(entry.title, systemImage: entry.icon)
                            .font(.subheadline.weight(.medium))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 12)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                            .background {
                                if page == entry {
                                    RoundedRectangle(cornerRadius: 8).fill(theme.control)
                                }
                            }
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(page == entry ? .isSelected : [])
                    .accessibilityIdentifier("more-\(entry.id)")
                }
            }
            .padding(5)
        }
        .frame(maxWidth: 260)
        .shadow(color: .black.opacity(theme.isDark ? 0.42 : 0.16), radius: 18, y: 10)
        .accessibilityAction(.escape) { showsMore = false }
    }

    private func open(_ destination: MagpiePage) {
        showsMore = false
        page = destination
    }
}

private struct MagpieContentPreview: PreviewProvider {
    static var previews: some View {
        ContentView(usage: UsageWorkspace())
    }
}
