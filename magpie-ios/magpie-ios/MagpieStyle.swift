import SwiftUI

struct MagpieTheme {
    var isDark: Bool

    var background: Color { tone(0xF4F4F6, 0x1A1A1E) }
    var card: Color { tone(0xFFFFFF, 0x232327) }
    var control: Color { tone(0xF1F1F4, 0x2D2D33) }
    var line: Color { tone(0xE3E3E8, 0x3A3A41) }
    var ink: Color { tone(0x1C1C21, 0xEDEDF1) }
    var secondary: Color { tone(0x4B4B53, 0xB9B9C1) }
    var muted: Color { tone(0x63636B, 0xA1A1AA) }
    var accent: Color { tone(0x4F46E5, 0xA5B4FC) }
    var green: Color { tone(0x117744, 0x6FD99B) }
    var switchTint: Color { tone(0x1C1C21, 0x6159E5) }

    private func tone(_ light: UInt32, _ dark: UInt32) -> Color {
        let rgb = isDark ? dark : light
        return Color(
            red: Double((rgb >> 16) & 255) / 255,
            green: Double((rgb >> 8) & 255) / 255,
            blue: Double(rgb & 255) / 255
        )
    }
}

extension EnvironmentValues {
    @Entry var magpieTheme = MagpieTheme(isDark: false)
}

extension View {
    @ViewBuilder
    func onValueChange<Value: Equatable>(of value: Value, perform action: @escaping (Value) -> Void) -> some View {
        if #available(iOS 17.0, macOS 14.0, visionOS 1.0, *) {
            onChange(of: value) { _, current in action(current) }
        } else {
            onChange(of: value, perform: action)
        }
    }
}

struct MagpieCard<Content: View>: View {
    @Environment(\.magpieTheme) private var theme

    @ViewBuilder var content: Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 12)
        VStack(alignment: .leading, spacing: 0) {
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.card, in: shape)
        .overlay {
            shape.strokeBorder(theme.line, lineWidth: 1)
                .allowsHitTesting(false)
        }
    }
}

struct MagpieDivider: View {
    @Environment(\.magpieTheme) private var theme

    var body: some View {
        Rectangle()
            .fill(theme.line)
            .frame(height: 1)
            .accessibilityHidden(true)
    }
}

struct MagpieSearch: View {
    @Environment(\.magpieTheme) private var theme

    let title: String
    @Binding var query: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(theme.muted)
                .accessibilityHidden(true)
            TextField(title, text: $query)
                .textFieldStyle(.plain)
                .frame(minHeight: 44)
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Label("Clear Search", systemImage: "xmark.circle.fill")
                }
                .labelStyle(.iconOnly)
                .frame(minWidth: 44, minHeight: 44)
                .foregroundStyle(theme.muted)
                .buttonStyle(.plain)
            }
        }
        .padding(.leading, 12)
        .background(theme.control, in: RoundedRectangle(cornerRadius: 8))
    }
}

struct MagpieRow: View {
    @Environment(\.magpieTheme) private var theme

    let title: String
    let detail: String
    let icon: String
    var isAsset = false
    var showsChevron = false

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if isAsset {
                    Image(icon).resizable().scaledToFit().frame(width: 22, height: 22)
                } else {
                    Image(systemName: icon).font(.body)
                }
            }
            .frame(width: 24)
            .foregroundStyle(theme.secondary)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(theme.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(theme.muted)
                    .accessibilityHidden(true)
            }
        }
        .padding(12)
        .frame(minHeight: 48)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

struct MagpieMessage: View {
    @Environment(\.magpieTheme) private var theme

    let title: String
    let message: String
    let icon: String

    var body: some View {
        MagpieCard {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.title3)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(theme.ink)
                Text(message)
                    .font(.footnote)
            }
            .foregroundStyle(theme.muted)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(24)
        }
        .accessibilityElement(children: .combine)
    }
}

struct MagpieSegments<Option: Hashable>: View {
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.magpieTheme) private var theme

    let title: String
    let options: [Option]
    @Binding var selection: Option
    let label: (Option) -> String

    var body: some View {
        if typeSize.isAccessibilitySize {
            Picker(title, selection: $selection) {
                ForEach(options, id: \.self) { option in
                    Text(label(option)).tag(option)
                }
            }
            .pickerStyle(.menu)
            .frame(minHeight: 44)
        } else {
            HStack(spacing: 2) {
                ForEach(options, id: \.self) { option in
                    Button {
                        selection = option
                    } label: {
                        Text(label(option))
                            .font(.subheadline)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                            .frame(maxWidth: .infinity, minHeight: 32)
                            .background {
                                if option == selection {
                                    let shape = RoundedRectangle(cornerRadius: 7)
                                    shape.fill(theme.card)
                                        .overlay(shape.strokeBorder(theme.line, lineWidth: 1))
                                }
                            }
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                            .foregroundStyle(option == selection ? theme.ink : theme.muted)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(option == selection ? .isSelected : [])
                }
            }
            .padding(.horizontal, 3)
            .background {
                RoundedRectangle(cornerRadius: 8)
                    .fill(theme.control)
                    .frame(height: 36)
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(title)
        }
    }
}

struct MagpieDetail<Content: View>: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.magpieTheme) private var theme

    let title: String
    @ViewBuilder var content: Content

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    content
                }
                .padding(16)
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
            }
            .background(theme.background.ignoresSafeArea())
            .navigationTitle(title)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
