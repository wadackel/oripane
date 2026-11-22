import AppKit
import OripaneCore
import SwiftUI

/// Asks for a pairing in a panel in front of everything else. Requests that come while one is shown
/// wait their turn behind it, and the panel says how many do.
final class PairingDialog: Approver {
    private struct Request {
        let request: PairRequest
        let completion: (PairOutcome) -> Void
    }

    /// Main thread only.
    private var queue = ApprovalQueue<Request>()
    private var panel: NSPanel?
    private let model = PairingModel()

    func approve(_ request: PairRequest, completion: @escaping (PairOutcome) -> Void) -> () -> Void {
        let token = UUID()
        DispatchQueue.main.async { self.add(token, Request(request: request, completion: completion)) }
        return { DispatchQueue.main.async { self.withdraw(token) } }
    }

    private func add(_ token: UUID, _ r: Request) {
        switch queue.add(token, from: r.request.from, r) {
        case .full:
            log("pairing denied reason=busy from=\(r.request.from)")
            r.completion(.refused)
        case .waiting:
            log("pairing queued waiting=\(queue.waiting)")
            model.waiting = queue.waiting
        case .shown:
            show()
        }
    }

    /// Shows the request at the head of the queue, in the panel already open when there is one.
    private func show() {
        guard let (token, r) = queue.shown else {
            panel?.close()
            panel = nil
            return
        }
        model.token = token
        model.name = PairPrompt.printable(r.request.name)
        model.code = r.request.code
        model.origin = PairPrompt.origin(r.request.from)
        model.waiting = queue.waiting
        // A click meant for the request before, or for whatever was under the panel, must not allow
        // one nobody compared codes with.
        model.allowEnabled = false
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            if self?.model.token == token { self?.model.allowEnabled = true }
        }
        if panel == nil {
            let view = PairingView(model: model) { [weak self] token, ok in self?.answer(token, ok) }
            // Non-activating and never made key here: a click makes it key without activating the
            // app, so keys typed elsewhere stay there, and Esc denies only once it was clicked.
            let p = NSPanel(contentRect: .zero, styleMask: [.titled, .nonactivatingPanel], backing: .buffered, defer: false)
            p.contentViewController = NSHostingController(rootView: view)
            p.title = "Oripane"
            p.hidesOnDeactivate = false
            p.isFloatingPanel = true
            p.level = .modalPanel
            p.isReleasedWhenClosed = false
            p.center()
            p.orderFrontRegardless()
            panel = p
        }
        log("ui pairing_dialog shown code=\(r.request.code) from=\(r.request.from) waiting=\(queue.waiting) key=\(panel?.isKeyWindow == true)")
    }

    private func answer(_ token: UUID, _ ok: Bool) {
        // A click on a button drawn for the request before, before the view caught up.
        guard let (shown, r) = queue.shown, shown == token, !ok || model.allowEnabled else { return }
        _ = queue.answered(token)
        log("ui pairing_dialog answer=\(ok ? "allow" : "deny")")
        r.completion(ok ? .allowed : .denied)
        show()
    }

    /// The phone gave up: the connection is gone, so there is no one left to answer.
    private func withdraw(_ token: UUID) {
        let wasShown = queue.shown?.token == token
        _ = queue.withdrawn(token)
        if wasShown {
            log("ui pairing_dialog withdrawn")
            show()
        } else {
            model.waiting = queue.waiting
        }
    }
}

private final class PairingModel: ObservableObject {
    @Published var token = UUID()
    @Published var name = ""
    @Published var code = ""
    @Published var origin = ""
    @Published var waiting = 0
    @Published var allowEnabled = false
}

private struct PairingView: View {
    @ObservedObject var model: PairingModel
    let answer: (UUID, Bool) -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text("Pair with a new phone?").font(.headline)
            VStack(spacing: 4) {
                Text("The phone must show").foregroundStyle(.secondary)
                Text(model.code).font(.system(size: 34, weight: .semibold, design: .monospaced)).textSelection(.disabled)
            }
            VStack(spacing: 2) {
                // One line: a second would let a name pose as the origin line below it.
                Text("Request from “\(model.name)”").font(.callout).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle)
                Text(model.origin).font(.callout).foregroundStyle(.secondary)
            }
            Text("Allow only if the phone in your hand shows the same code.")
                .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if let line = PairPrompt.waitingLine(model.waiting) {
                Text(line).font(.footnote).foregroundStyle(.secondary)
            }
            HStack {
                Button("Deny") { answer(model.token, false) }.keyboardShortcut(.cancelAction)
                // No Return: a Return meant for another app, once the panel is clicked, would approve
                // a phone nobody compared codes with.
                Button("Allow") { answer(model.token, true) }.disabled(!model.allowEnabled)
            }
            .padding(.top, 4)
        }
        .padding(24)
        .frame(width: 320)
    }
}
