// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-ios/NotificationService/NotificationService.swift; see NOTICE.
import UserNotifications

/// Shows a push's real text: the relay sends a generic alert ("Ostrom" / "New activity in Ostrom") with
/// `mutable-content` and the payload encrypted for this device in `p`; this extension decrypts it with the key the app
/// shares through the Keychain. A payload it cannot read (another key, a key not made yet), or one for an account not
/// signed in on this phone, keeps the generic alert.
final class NotificationService: UNNotificationServiceExtension {
    private var handler: ((UNNotificationContent) -> Void)?
    private var generic: UNNotificationContent?

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        handler = contentHandler
        generic = request.content
        guard let content = request.content.mutableCopy() as? UNMutableNotificationContent,
              request.content.userInfo["v"] == nil || request.content.userInfo["v"] as? String == "1",
              let payload = request.content.userInfo["p"] as? String, payload.count <= 4_096,
              let key = PushCrypto.privateKey(create: false),
              let json = try? PushCrypto.decrypt(payload, with: key),
              let push = try? JSONDecoder().decode(PushCrypto.Payload.self, from: json),
              !push.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              PushCrypto.isId(push.orgId), PushCrypto.isId(push.memberId),
              // A push for an account signed out on this phone keeps the generic alert, without its text or link.
              PushCrypto.isSignedIn(orgId: push.orgId, memberId: push.memberId)
        else { return contentHandler(request.content) }
        content.title = String(push.title.prefix(200))
        content.body = String(push.body.prefix(1_000))
        // One thread per request in Notification Center, else per account.
        content.threadIdentifier = push.approvalId ?? "\(push.origin) \(push.orgId) \(push.memberId)"
        var info = content.userInfo
        info.removeValue(forKey: "p")
        info["link"] = PushCrypto.link(for: push)
        content.userInfo = info
        contentHandler(content)
    }

    /// Out of time: the generic alert is shown.
    override func serviceExtensionTimeWillExpire() {
        if let handler, let generic { handler(generic) }
    }
}
