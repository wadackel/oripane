import AppKit
import OripaneCore

/// The menu bar icon and its menu. The menu is rebuilt each time it opens, from the latest state.
final class StatusMenu: NSObject, NSMenuDelegate {
    private unowned let app: HostApp
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    private let menu = NSMenu()

    init(app: HostApp) {
        self.app = app
        super.init()
        menu.delegate = self
        menu.autoenablesItems = false
        item.menu = menu
        refresh()
    }

    /// Only the icon: the menu itself is built when it opens.
    func refresh() {
        let m = app.menuState
        switch m.icon {
        case .idle: item.button?.image = Mark.menuBarImage(idle: true)
        case .connected: item.button?.image = Mark.menuBarImage(idle: false)
        case .attention:
            let image = NSImage(systemSymbolName: "exclamationmark.triangle", accessibilityDescription: "Oripane")
            image?.isTemplate = true
            item.button?.image = image
        }
        item.button?.toolTip = "Oripane — \(m.line)"
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let m = app.menuState
        let status = NSMenuItem(title: m.line, action: nil, keyEquivalent: "")
        status.isEnabled = false
        menu.addItem(status)
        if case .relaunchNeeded = app.status { menu.addItem(action("Relaunch Oripane", #selector(relaunch))) }
        for w in m.warnings {
            let i = action(w, #selector(setup))
            i.image = NSImage(systemSymbolName: "exclamationmark.triangle", accessibilityDescription: nil)
            menu.addItem(i)
        }
        menu.addItem(.separator())
        menu.addItem(pairedPhones())
        menu.addItem(.separator())
        let login = action("Open at Login", #selector(toggleLogin))
        login.state = LoginItem.isOn ? .on : .off
        menu.addItem(login)
        if LoginItem.status == .requiresApproval {
            menu.addItem(action("Allow Oripane in Login Items…", #selector(loginSettings)))
        }
        menu.addItem(action("Setup…", #selector(setup)))
        menu.addItem(action("Show Log", #selector(showLog)))
        menu.addItem(action("About Oripane", #selector(about)))
        menu.addItem(.separator())
        menu.addItem(action("Quit Oripane", #selector(quit), key: "q"))
    }

    private func pairedPhones() -> NSMenuItem {
        let item = NSMenuItem(title: "Paired Phones", action: nil, keyEquivalent: "")
        let sub = NSMenu()
        sub.autoenablesItems = false
        let phones = app.store.all()
        if phones.isEmpty {
            let none = NSMenuItem(title: "No paired phones", action: nil, keyEquivalent: "")
            none.isEnabled = false
            sub.addItem(none)
        }
        var connected: String?
        if case let .connected(_, _, _, clientId) = app.status { connected = clientId }
        let rows = PairedPhoneRows.rows(
            phones.map { PairedPhone(id: $0.0, name: $0.1.name, pairedAt: $0.1.pairedAt, lastUsedAt: $0.1.lastUsedAt) },
            connectedId: connected,
            timeZone: .current
        )
        for r in rows {
            let phone = NSMenuItem(title: r.title, action: nil, keyEquivalent: "")
            phone.state = r.connected ? .on : .off
            let detail = NSMenu()
            detail.autoenablesItems = false
            for line in r.details {
                let item = NSMenuItem(title: line, action: nil, keyEquivalent: "")
                item.isEnabled = false
                detail.addItem(item)
            }
            let unpair = action("Unpair…", #selector(unpair(_:)))
            unpair.representedObject = r.id
            detail.addItem(unpair)
            phone.submenu = detail
            sub.addItem(phone)
        }
        item.submenu = sub
        return item
    }

    private func action(_ title: String, _ selector: Selector, key: String = "") -> NSMenuItem {
        let i = NSMenuItem(title: title, action: selector, keyEquivalent: key)
        i.target = self
        return i
    }

    @objc private func unpair(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String,
              let phone = app.store.all().first(where: { $0.0 == id })?.1 else { return }
        NSApp.activate()
        let alert = NSAlert()
        alert.messageText = "Unpair “\(phone.name)”?"
        alert.informativeText = "The phone disconnects now. To use it with this Mac again, pair it again and allow it here."
        alert.addButton(withTitle: "Unpair")
        alert.addButton(withTitle: "Cancel")
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        app.unpair(id)
    }

    @objc private func toggleLogin() {
        if let error = LoginItem.set(!LoginItem.isOn) { app.showSetup(loginError: error.localizedDescription) }
    }

    @objc private func loginSettings() { LoginItem.openSettings() }
    @objc private func setup() { app.showSetup() }
    @objc private func relaunch() { app.relaunch() }
    @objc private func showLog() { app.showLog() }
    @objc private func about() { app.showAbout() }
    @objc private func quit() { HostApp.quit() }
}
