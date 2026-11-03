// SPDX-License-Identifier: AGPL-3.0-only
import * as Notifications from "expo-notifications"
import type {PushPermission, PushService} from "./PushService"

export interface PushServiceConfig {
    /** The EAS project id; absent until the project is created, and then no push token can be asked for. */
    readonly projectId: string | undefined
    readonly platform: string
}

/** A request waits for a human: shown even while the app is open. No badge: nothing in the app would clear it. */
Notifications.setNotificationHandler({
    handleNotification: () =>
        Promise.resolve({shouldShowBanner: true, shouldShowList: true, shouldPlaySound: true, shouldSetBadge: false}),
})

/**
 * Android 13+ shows the permission prompt only once a channel exists, and shows nothing without one. The server sends
 * its pushes to this channel (`channelId`).
 */
const ANDROID_CHANNEL = "requests"

function dataOf(response: Notifications.NotificationResponse | null): unknown {
    return response?.notification.request.content.data
}

export class PushServiceImpl implements PushService {
    constructor(private readonly config: PushServiceConfig) {}

    async register(): Promise<PushPermission> {
        const {projectId, platform} = this.config
        if (projectId === undefined || (platform !== "ios" && platform !== "android")) return {kind: "unavailable"}
        if (platform === "android") {
            // Private on a locked phone: it says Ostrom has a notification, never who asks, what or where
            // (AUDIT-SECU-APP-2 N2). The phone shows the rest once unlocked.
            await Notifications.setNotificationChannelAsync(ANDROID_CHANNEL, {
                name: "Requests",
                importance: Notifications.AndroidImportance.HIGH,
                lockscreenVisibility: Notifications.AndroidNotificationVisibility.PRIVATE,
            })
        }
        const current = await Notifications.getPermissionsAsync()
        const status = current.granted || !current.canAskAgain ? current : await Notifications.requestPermissionsAsync()
        if (!status.granted) return {kind: "denied"}
        try {
            const token = await Notifications.getExpoPushTokenAsync({projectId})
            return {kind: "granted", address: {pushToken: token.data, platform}}
        } catch {
            // A simulator, or no network to Expo's push service: the app works without push.
            return {kind: "unavailable"}
        }
    }

    onOpen(listener: (data: unknown) => void): () => void {
        const subscription = Notifications.addNotificationResponseReceivedListener((response) => {
            listener(dataOf(response))
        })
        return () => {
            subscription.remove()
        }
    }

    /** The notification that launched the app, given once: it is cleared, so a remount does not open it again. */
    launchData(): Promise<unknown> {
        const response = Notifications.getLastNotificationResponse()
        if (response !== null) Notifications.clearLastNotificationResponse()
        return Promise.resolve(dataOf(response))
    }

    clearShown(): Promise<void> {
        return Notifications.dismissAllNotificationsAsync()
    }
}
