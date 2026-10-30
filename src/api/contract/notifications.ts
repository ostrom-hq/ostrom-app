import {z} from "zod"
import {zNotificationPreferences, zPushDevice, zRegisterPushDeviceBody} from "@/api/gen/zod.gen"

export type DevicePlatform = "ios" | "android"

/** `POST /api/v1/push-devices`: this phone's Expo push token, so the server can say "a request waits for you". */
export type DeviceRegistration = z.output<typeof zRegisterPushDeviceBody> & {
    readonly provider: "expo"
    readonly platform: DevicePlatform
}

export const DeviceSchema = zPushDevice
export type Device = z.output<typeof zPushDevice>

/** `GET` and `PUT /api/v1/me/notifications`: what this person is told on their phone. */
export const NotificationSettingsSchema = zNotificationPreferences
export type NotificationSettings = z.output<typeof zNotificationPreferences>
export type QuietHours = NonNullable<NotificationSettings["quietHours"]>

/**
 * The data of an Ostrom push (`registerPushDevice` in the contract: `{kind: "approval", approvalId}`): which request
 * to open. The id has the alphabet of a route segment, so a push cannot smuggle a path. Anything else is ignored.
 */
export const PushDataSchema = z.object({
    kind: z.literal("approval"),
    approvalId: z.string().regex(/^[A-Za-z0-9_-]+$/),
})
export type PushData = z.output<typeof PushDataSchema>
