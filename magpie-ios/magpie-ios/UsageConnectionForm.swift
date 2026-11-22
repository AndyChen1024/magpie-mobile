import SwiftUI

struct UsageConnectionForm: View {
    @Environment(\.dismiss) private var dismiss

    @ObservedObject var workspace: UsageWorkspace
    @State private var address = ""
    @State private var key = ""
    @State private var issue: UsageConnectionError?
    @State private var confirmsDisconnect = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://magpie.local:3425", text: $address)
                        .accessibilityLabel("Gateway address")
                        .accessibilityIdentifier("usage-address")
                        #if os(iOS)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        #endif
                    SecureField(workspace.origin == nil ? String(localized: "Gateway key") : String(localized: "New key (blank keeps current)"), text: $key)
                        .accessibilityLabel("Gateway key")
                        .accessibilityIdentifier("usage-key")
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        #endif
                } header: {
                    Text("Gateway Settings")
                } footer: {
                    Text("Use a Magpie gateway with local network sharing enabled. Your device must trust its HTTPS certificate. When you change hosts, enter the key for the new host.")
                }

                if !workspace.canConnect {
                    Section {
                        Label(UsageConnectionError.unavailable.localizedDescription, systemImage: "info.circle")
                    }
                }
                if let issue {
                    Section {
                        Label(issue.localizedDescription, systemImage: "exclamationmark.circle")
                            .accessibilityIdentifier("usage-connection-issue")
                    }
                }
                if workspace.origin != nil || workspace.storageIssue != nil {
                    Section {
                        Button("Disconnect and Remove Connection", role: .destructive) { confirmsDisconnect = true }
                            .accessibilityIdentifier("usage-disconnect")
                    }
                }
            }
            .navigationTitle("Connect to Magpie")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Connect", action: connect)
                        .disabled(!workspace.canConnect || address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("usage-connect-confirm")
                }
            }
        }
        .onAppear { address = workspace.origin?.address ?? "" }
        .confirmationDialog("Disconnect and remove this host's gateway key?", isPresented: $confirmsDisconnect, titleVisibility: .visible) {
            Button("Disconnect and Remove", role: .destructive, action: disconnect)
        }
    }

    private func connect() {
        do {
            try workspace.connect(address: address, key: key)
            key = ""
            dismiss()
        } catch {
            issue = error as? UsageConnectionError ?? .secureStorage
        }
    }

    private func disconnect() {
        do {
            try workspace.disconnect()
            key = ""
            dismiss()
        } catch {
            issue = .secureStorage
        }
    }
}
