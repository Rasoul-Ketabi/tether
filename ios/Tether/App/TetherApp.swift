import Observation
import SwiftUI
import TetherCore
import UIKit

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Create the model (and with it the CBCentralManager) right away: when iOS relaunches us in the
        // background for a Bluetooth event, state restoration needs the manager during launch.
        Notifications.shared.setUp()
        MainActor.assumeIsolated {
            _ = AppModel.shared.calls // CXProvider must exist before a call can be reported
        }
        return true
    }
}

/// Simple navigation requests (e.g. tapping an SMS notification).
@MainActor @Observable
final class Router {
    static let shared = Router()
    var tab: Tab = .messages
    var openThread: Int64?

    enum Tab: Hashable { case recents, contacts, keypad, messages, link }

    func open(thread: Int64) {
        tab = .messages
        openThread = thread
    }

    /// A number to write to (from a bubble's phone-number menu).
    var composeTo: String?
    func compose(to number: String) {
        tab = .messages
        composeTo = number
    }
}

@main
struct TetherApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(AppModel.shared)
                .environment(Router.shared)
        }
        .onChange(of: phase) { _, p in
            AppModel.shared.appActive = p == .active
            if p == .active {
                let m = AppModel.shared
                m.requestSync()
                // Coming back to the app during a call opens the call screen, like the Phone app.
                if let c = m.callScreen, c.state != "ended", c.state != "ringing" {
                    m.callScreenDismissed = false
                    m.showCallScreen = true
                }
            }
        }
    }
}
