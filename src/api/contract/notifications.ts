import {z} from "zod"

export const PlatformSchema = z.enum(["ios", "android"])
export type DevicePlatform = z.output<typeof PlatformSchema>

/** `POST /api/devices`: the push token of this phone, so the server can say "a request waits for you". */
export const DeviceRegistrationSchema = z.object({
    pushToken: z.string(),
    provider: z.literal("expo"),
    platform: PlatformSchema,
    locale: z.string(),
})
export type DeviceRegistration = z.output<typeof DeviceRegistrationSchema>

export const DeviceSchema = z.object({id: z.string()})
export type Device = z.output<typeof DeviceSchema>

/** A daily window without push, in the person's time zone (`22:00` to `07:00`). */
export const QuietHoursSchema = z.object({
    start: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/),
    end: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/),
    timeZone: z.string(),
})
export type QuietHours = z.output<typeof QuietHoursSchema>

/** `GET` and `PUT /api/me/notifications`. */
export const NotificationSettingsSchema = z.object({
    /** A request waits for my yes. */
    requests: z.boolean(),
    /** Someone decided a request I asked for. */
    decisions: z.boolean(),
    /** The spending of the month passed a budget threshold. */
    spending: z.boolean(),
    quietHours: QuietHoursSchema.nullish(),
})
export type NotificationSettings = z.output<typeof NotificationSettingsSchema>

/** The data of an Ostrom push: which request to open. Anything else in a push is ignored. */
export const PushDataSchema = z.object({
    kind: z.literal("ask"),
    askId: z.string(),
})
export type PushData = z.output<typeof PushDataSchema>
