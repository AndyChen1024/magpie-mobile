#if os(iOS)
import SwiftUI

struct PhoneConnectionPage: View {
    @Environment(\.magpieTheme) private var theme
    @ObservedObject var workspace: PhoneWorkspace
    @State private var showsScanner = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Connect Phone")
                .font(.title2.bold())
                .accessibilityAddTraits(.isHeader)

            MagpieCard {
                VStack(alignment: .leading, spacing: 8) {
                    if workspace.status == .connecting {
                        ProgressView("Connecting to Mac…")
                            .accessibilityIdentifier("phone-connecting")
                    } else {
                        Label(statusTitle, systemImage: statusIcon)
                            .font(.headline)
                            .accessibilityIdentifier(statusIdentifier)
                    }
                    if let host = workspace.host {
                        Text(host.displayName)
                            .font(.body)
                            .privacySensitive()
                            .accessibilityIdentifier("phone-mac-name")
                    }
                    if workspace.status == .unpaired || workspace.status == .authorizationExpired {
                        Text("Open Connect Phone in Magpie on your Mac and generate a pairing code.")
                            .font(.subheadline)
                            .foregroundStyle(theme.muted)
                    }
                    if let failure = workspace.failure {
                        Text(message(for: failure))
                            .font(.subheadline)
                            .foregroundStyle(theme.muted)
                            .accessibilityIdentifier("phone-error")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
            }
            actions
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .sheet(isPresented: $showsScanner) {
            PhoneQRScannerSheet { link in
                showsScanner = false
                workspace.pair(using: link)
            }
        }
    }

    @ViewBuilder
    private var actions: some View {
        switch workspace.status {
        case .unpaired, .authorizationExpired:
            Button {
                showsScanner = true
            } label: {
                Label("Scan Pairing Code", systemImage: "qrcode.viewfinder")
                    .foregroundStyle(theme.background)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .accessibilityIdentifier("phone-scan")

            PasteButton(payloadType: String.self) { items in
                guard let link = items.first else { return }
                workspace.pair(using: link)
            }
            .labelStyle(.titleAndIcon)
            .controlSize(.large)
            .accessibilityLabel("Paste Pairing Link")
            .accessibilityIdentifier("phone-paste")
        case .connected:
            Button(action: workspace.readHost) {
                Label("Refresh Mac Name", systemImage: "arrow.clockwise")
            }
            .frame(minHeight: 44)
            .disabled(workspace.isReading)
            .accessibilityIdentifier("phone-read")
        case .disconnected:
            Button(action: workspace.reconnect) {
                Label("Reconnect", systemImage: "arrow.clockwise")
            }
            .frame(minHeight: 44)
            .accessibilityIdentifier("phone-reconnect")
        case .connecting:
            EmptyView()
        }
    }

    private var statusTitle: LocalizedStringKey {
        switch workspace.status {
        case .unpaired: "Phone Not Paired"
        case .connecting: "Connecting to Mac…"
        case .connected: "Mac Connected"
        case .disconnected: "Mac Disconnected"
        case .authorizationExpired: "Pair Again"
        }
    }

    private var statusIcon: String {
        switch workspace.status {
        case .unpaired: "iphone"
        case .connecting: "link"
        case .connected: "checkmark.circle"
        case .disconnected: "wifi.slash"
        case .authorizationExpired: "lock"
        }
    }

    private var statusIdentifier: String {
        switch workspace.status {
        case .unpaired: "phone-unpaired"
        case .connecting: "phone-connecting"
        case .connected: "phone-connected"
        case .disconnected: "phone-disconnected"
        case .authorizationExpired: "phone-authorization-expired"
        }
    }

    private func message(for error: PhoneConnectionError) -> LocalizedStringKey {
        switch error {
        case .invalidLink: "This Magpie pairing link is invalid."
        case .tlsRequired: "This build requires a secure relay address."
        case .invalidPairing: "This pairing code expired or was already used. Generate a new code on your Mac."
        case .unreachable: "Can’t reach the Mac through the relay. Check your connection and allow Local Network access for a local relay."
        case .invalidChannel: "Couldn’t verify the protected connection to your Mac."
        case .invalidResponse: "Your Mac returned an invalid reply."
        case .notAuthorized: "Your Mac removed this phone’s authorization."
        case .deviceLimit: "Your Mac has reached its paired device limit."
        case .secureStorage: "Couldn’t access this phone’s pairing in Keychain."
        }
    }
}
#endif
