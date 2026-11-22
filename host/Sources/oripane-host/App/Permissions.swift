import AppKit
import ApplicationServices
import CoreGraphics
import ServiceManagement

/// The privacy permissions the host needs. Read without prompting, so the app can check them as
/// often as it likes; each prompt is asked for once, from the setup window.
enum Privacy {
    static var screenRecording: Bool { CGPreflightScreenCaptureAccess() }
    static var accessibility: Bool { AXIsProcessTrusted() }

    /// Also what adds the app to the list in System Settings.
    static func requestScreenRecording() { CGRequestScreenCaptureAccess() }

    static func requestAccessibility() {
        AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue(): true] as CFDictionary)
    }

    enum Pane: String {
        case screenRecording = "Privacy_ScreenCapture"
        case accessibility = "Privacy_Accessibility"
        case localNetwork = "Privacy_LocalNetwork"
    }

    static func openSettings(_ pane: Pane) {
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?\(pane.rawValue)")!)
    }
}

/// The app itself as a login item. Only meaningful for the copy in ~/Applications: macOS records
/// the bundle that registered, so a test copy elsewhere would add itself instead.
enum LoginItem {
    static var status: SMAppService.Status { SMAppService.mainApp.status }

    /// Registered, whether or not the owner has approved it yet. `.notFound` is what remains
    /// after the owner removes the item in System Settings.
    static var isOn: Bool { status == .enabled || status == .requiresApproval }

    static func describe(_ s: SMAppService.Status) -> String {
        switch s {
        case .enabled: return "enabled"
        case .requiresApproval: return "requires_approval"
        case .notRegistered: return "off"
        case .notFound: return "not_found"
        @unknown default: return "unknown"
        }
    }

    /// Logs the outcome and returns the error macOS gave, if any. `requiresApproval` after
    /// registering counts as done: the owner finishes it in System Settings.
    @discardableResult
    static func set(_ on: Bool) -> Error? {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            log("login_item status=\(describe(status))")
            return nil
        } catch {
            // Unregistering an item that is not there is the state the owner asked for.
            if !on, !isOn {
                log("login_item status=\(describe(status))")
                return nil
            }
            log("login_item status=error error=\"\(error.localizedDescription)\"")
            return error
        }
    }

    static func openSettings() { SMAppService.openSystemSettingsLoginItems() }
}
