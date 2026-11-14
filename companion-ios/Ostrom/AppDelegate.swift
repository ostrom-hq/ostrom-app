// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-ios/TeamCode/AppDelegate.swift; see NOTICE.
import Companion
import FirebaseCore
import FirebaseMessaging
import UIKit
import UserNotifications

/// Push (`notifications.push.v1`), on in a build that carries GoogleService-Info.plist (git-ignored): FCM's token goes to
/// the shared code, which registers it with each signed-in account's server; a tapped notification opens its request.
final class AppDelegate: NSObject, UIApplicationDelegate, MessagingDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else { return true }
        FirebaseApp.configure()
        Messaging.messaging().delegate = self
        MainViewControllerKt.enablePush(keys: PushKeys())
        // The APNs token needs no permission; the alert permission is asked when an account's server offers push.
        application.registerForRemoteNotifications()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Messaging.messaging().apnsToken = deviceToken
    }

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        MainViewControllerKt.pushTokenChanged(token: fcmToken)
    }

    /// In the foreground too: the person may be on another screen or account.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if let link = response.notification.request.content.userInfo["link"] as? String {
            MainViewControllerKt.openLink(link: link)
        }
        completionHandler()
    }
}

/// The shared code's view of the key pair (IosPushPlatform.kt).
final class PushKeys: NSObject, IosPushKeys {
    func publicKey() -> String {
        PushCrypto.privateKey(create: true)?.publicKey.derRepresentation.base64EncodedString() ?? ""
    }

    func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func saveSignedIn(accounts: String) {
        PushCrypto.saveSignedIn(accounts)
    }
}
