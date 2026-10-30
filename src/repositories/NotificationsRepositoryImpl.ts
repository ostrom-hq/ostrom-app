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
        return this.http.send({method: "POST", path: "/api/devices", body: registration, response: DeviceSchema})
    }

    async unregisterDevice(id: string): Promise<void> {
        await this.http.send({method: "DELETE", path: `/api/devices/${segment(id)}`, response: z.undefined()})
    }

    settings(): Promise<NotificationSettings> {
        return this.http.send({method: "GET", path: "/api/me/notifications", response: NotificationSettingsSchema})
    }

    saveSettings(settings: NotificationSettings): Promise<NotificationSettings> {
        return this.http.send({
            method: "PUT",
            path: "/api/me/notifications",
            body: settings,
            response: NotificationSettingsSchema,
        })
    }
}
