// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-ios/Push/PushCrypto.swift; see NOTICE.
import CryptoKit
import Foundation
import Security

/// The push key pair and the payload's decryption, shared by the app (which makes the key and registers its public half)
/// and the notification service extension (which decrypts each push before iOS shows it). ostrom-core's `PushPayload`:
/// ECIES on P-256, `base64( 0x01 | ephemeral point (65) | nonce (12) | AES-256-GCM ciphertext and tag )`, the key being
/// HKDF-SHA256 of the ECDH secret with salt `ephemeral point | recipient point` and info `ostrom-push-v1`.
enum PushCrypto {
    private static let service = "run.ostrom.app.push"
    private static let account = "device-key"

    /// The Keychain access group both targets list (`OstromPushKeychainGroup` in their Info.plist): the extension sees
    /// this key and nothing else of the app's Keychain, where the accounts' tokens and unlock keys stay.
    private static var accessGroup: String? { Bundle.main.object(forInfoDictionaryKey: "OstromPushKeychainGroup") as? String }

    private static func query(_ extra: [CFString: Any]) -> CFDictionary {
        var query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account]
        if let group = accessGroup, !group.isEmpty { query[kSecAttrAccessGroup] = group }
        return query.merging(extra) { _, new in new } as CFDictionary
    }

    /// The device's key, made and stored on first use when [create] (the app); the extension only reads it.
    static func privateKey(create: Bool) -> P256.KeyAgreement.PrivateKey? {
        var item: CFTypeRef?
        if SecItemCopyMatching(query([kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne]), &item) == errSecSuccess,
           let data = item as? Data, let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: data) {
            return key
        }
        guard create else { return nil }
        let key = P256.KeyAgreement.PrivateKey()
        SecItemDelete(query([:])) // An unreadable entry is replaced: the next sync registers the new public key.
        let added = SecItemAdd(query([kSecValueData: key.rawRepresentation, kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]), nil)
        return added == errSecSuccess ? key : nil
    }

    enum Failure: Error { case malformed }

    /// The payload's JSON; throws when it is malformed or was not encrypted for [key].
    static func decrypt(_ payload: String, with key: P256.KeyAgreement.PrivateKey) throws -> Data {
        guard let bytes = Data(base64Encoded: payload), bytes.count > 1 + 65 + 12 + 16, bytes.first == 1 else { throw Failure.malformed }
        let point = Data(bytes[bytes.startIndex + 1 ..< bytes.startIndex + 66])
        let nonce = Data(bytes[bytes.startIndex + 66 ..< bytes.startIndex + 78])
        let sealed = Data(bytes[(bytes.startIndex + 78)...])
        let ephemeral = try P256.KeyAgreement.PublicKey(x963Representation: point)
        let secret = try key.sharedSecretFromKeyAgreement(with: ephemeral)
        let symmetric = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: point + key.publicKey.x963Representation,
                                                       sharedInfo: Data("ostrom-push-v1".utf8), outputByteCount: 32)
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: sealed.dropLast(16), tag: sealed.suffix(16))
        return try AES.GCM.open(box, using: symmetric)
    }

    /// What a push carries once decrypted: ostrom-core's `PushPayload`.
    struct Payload: Decodable {
        let id: String
        let origin: String
        let orgId: String
        let memberId: String
        let kind: String
        let title: String
        let body: String
        let approvalId: String?
    }

    /// The link a tap opens, as the app's `NotificationLinks` builds it:
    /// `ostrom://notification?origin=…&org=…&member=…[&approval=…]`.
    static func link(for push: Payload) -> String {
        let unreserved = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        func encode(_ value: String) -> String { value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? "" }
        var link = "ostrom://notification?origin=\(encode(push.origin))&org=\(encode(push.orgId))&member=\(encode(push.memberId))"
        if let approval = push.approvalId { link += "&approval=\(encode(approval))" }
        return link
    }
}
