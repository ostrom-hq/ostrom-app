import {z} from "zod"
import {
    type Device,
    type DeviceRegistration,
    DeviceSchema,
    type NotificationSettings,
    NotificationSettingsSchema,
} from "@/api/contract/notifications"
import type {HttpClient} from "@/api/http/HttpClient"
import {segment} from "@/api/http/HttpClientImpl"
import type {NotificationsRepository} from "./NotificationsRepository"

export class NotificationsRepositoryImpl implements NotificationsRepository {
    constructor(private readonly http: HttpClient) {}

    registerDevice(registration: DeviceRegistration): Promise<Device> {
        return this.http.send({method: "POST", path: "/api/v1/push-devices", body: registration, response: DeviceSchema})
    }

    /** Whatever the server answers with a success (empty, `{}`, `null`) means the device is gone. */
    async unregisterDevice(id: string): Promise<void> {
        await this.http.send({method: "DELETE", path: `/api/v1/push-devices/${segment(id)}`, response: z.unknown()})
    }

    settings(): Promise<NotificationSettings> {
        return this.http.send({method: "GET", path: "/api/v1/me/notifications", response: NotificationSettingsSchema})
    }

    saveSettings(settings: NotificationSettings): Promise<NotificationSettings> {
        return this.http.send({
            method: "PUT",
            path: "/api/v1/me/notifications",
            body: settings,
            response: NotificationSettingsSchema,
        })
    }
}
