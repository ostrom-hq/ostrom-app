import type {Device, DeviceRegistration, NotificationSettings} from "@/api/contract/notifications"

/** This phone's push registration and the person's notification settings. */
export interface NotificationsRepository {
    registerDevice(registration: DeviceRegistration): Promise<Device>
    unregisterDevice(id: string): Promise<void>
    settings(): Promise<NotificationSettings>
    saveSettings(settings: NotificationSettings): Promise<NotificationSettings>
}
