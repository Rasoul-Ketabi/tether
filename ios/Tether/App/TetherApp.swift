import UserNotifications
import Observation
import SwiftUI
import TetherCore
import UIKit

final class AppDelegate: NSObject, UIApplicationDelegate {
    /// The app is being terminated while it still ran (swiped away from the foreground or a running background):
    /// calls stop reaching this iPhone until it is opened again, so say so.
    func applicationWillTerminate(_ application: UIApplication) {
        MainActor.assumeIsolated {
            guard AppModel.shared.link.isPaired else { return }
            let c = UNMutableNotificationContent()
            c.title = String(localized: "Calls paused")
            c.body = String(localized: "The app was closed. Open it again to keep receiving calls and messages from your Android phone.")
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "app-closed", content: c,
                trigger: UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)))
        }
    }

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
