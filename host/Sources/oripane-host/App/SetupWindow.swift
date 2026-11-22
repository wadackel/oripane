import AppKit
import ServiceManagement
import SwiftUI

/// What the setup window shows; the app updates it on the main thread. An ObservableObject rather
/// than `@State` or `@Observable`: Command Line Tools ship no SwiftUI macro plugin.
final class SetupModel: ObservableObject {
    enum Step: Equatable { case on, off, relaunch, notNeeded }

    @Published var screenRecording = Step.off
    @Published var accessibility = Step.off
    @Published var loginItem: SMAppService.Status = LoginItem.status
    @Published var loginError: String?
    var relaunch: () -> Void = {}
}

/// The window that walks the owner through what the host needs from macOS.
final class SetupWindowController: NSWindowController {
    init(model: SetupModel) {
        let window = NSWindow(contentViewController: NSHostingController(rootView: SetupView(model: model)))
        window.title = "Oripane Setup"
        window.styleMask = [.titled, .closable]
        window.isReleasedWhenClosed = false
        super.init(window: window)
        window.center()
    }

    required init?(coder: NSCoder) { nil }
}

private struct SetupView: View {
    @ObservedObject var model: SetupModel

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Set up Oripane").font(.title2.bold())
                Text("Oripane shows this Mac on your phone. macOS asks you to allow a few things first; each one is a switch in System Settings.")
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if model.screenRecording == .relaunch {
                StepRow(number: 1, title: "Screen Recording", state: .relaunch,
                        detail: "Allowed. Relaunch Oripane so it can start capturing.",
                        button: "Relaunch", prominent: true) { model.relaunch() }
            } else {
                StepRow(number: 1, title: "Screen Recording", state: model.screenRecording,
                        detail: "Lets Oripane capture the screen your phone shows.") {
                    Privacy.requestScreenRecording()
                    Privacy.openSettings(.screenRecording)
                }
            }
            StepRow(number: 2, title: "Accessibility", state: model.accessibility,
                    detail: "Turns taps and typing on the phone into clicks and keys on this Mac. Works as soon as it is on.") {
                Privacy.requestAccessibility()
                Privacy.openSettings(.accessibility)
            }
            StepRow(number: 3, title: "Local Network", state: nil,
                    detail: "macOS asks once Oripane starts listening, after Screen Recording is on. If you chose Don’t Allow, turn Oripane on here.") {
                Privacy.openSettings(.localNetwork)
            }
            HStack(alignment: .top, spacing: 12) {
                StepNumber(number: 4)
                VStack(alignment: .leading, spacing: 4) {
                    Toggle("Open at Login", isOn: Binding(
                        get: { model.loginItem == .enabled || model.loginItem == .requiresApproval },
                        set: { on in
                            model.loginError = LoginItem.set(on)?.localizedDescription
                            model.loginItem = LoginItem.status
                        }
                    ))
                    .toggleStyle(.switch)
                    .font(.headline)
                    if model.loginItem == .requiresApproval {
                        Button("Allow in Login Items…") { LoginItem.openSettings() }.buttonStyle(.link)
                    } else if let error = model.loginError {
                        Text(error).font(.callout).foregroundStyle(.red)
                    } else {
                        Text("Starts Oripane when you log in, so your phone always finds this Mac.")
                            .font(.callout).foregroundStyle(.secondary)
                    }
                }
            }
            Divider()
            HStack {
                Text("Permissions changed but nothing happens? Relaunching applies them.")
                    .font(.footnote).foregroundStyle(.secondary)
                Spacer()
                Button("Relaunch") { model.relaunch() }
            }
        }
        .padding(24)
        .frame(width: 520, alignment: .leading)
    }
}

private struct StepNumber: View {
    let number: Int

    var body: some View {
        Text("\(number)")
            .font(.callout.bold().monospacedDigit())
            .frame(width: 22, height: 22)
            .background(Circle().fill(Color.secondary.opacity(0.2)))
    }
}

private struct StepRow: View {
    let number: Int
    let title: String
    /// nil when macOS gives no way to read it.
    let state: SetupModel.Step?
    let detail: String
    var button = "Open Settings…"
    var prominent = false
    let open: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            StepNumber(number: number)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(title).font(.headline)
                    if let state { badge(state) }
                }
                Text(detail).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            if prominent {
                Button(button, action: open).buttonStyle(.borderedProminent)
            } else if state != .on && state != .notNeeded {
                Button(button, action: open)
            }
        }
    }

    private func badge(_ s: SetupModel.Step) -> some View {
        let (text, symbol, color): (String, String, Color)
        switch s {
        case .on: (text, symbol, color) = ("On", "checkmark.circle.fill", .green)
        case .off: (text, symbol, color) = ("Off", "exclamationmark.circle.fill", .orange)
        case .relaunch: (text, symbol, color) = ("Relaunch needed", "arrow.clockwise.circle.fill", .orange)
        case .notNeeded: (text, symbol, color) = ("Not needed for this run", "minus.circle", .secondary)
        }
        return Label(text, systemImage: symbol).font(.caption.bold()).foregroundStyle(color)
    }
}
