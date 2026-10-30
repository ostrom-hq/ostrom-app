// SPDX-License-Identifier: AGPL-3.0-only
import {act, fireEvent, screen, waitFor} from "@testing-library/react-native"
import {type Device, type DeviceRegistration, type NotificationSettings, NotificationSettingsSchema} from "@/api/contract/notifications"
import {ApiError} from "@/api/http/ApiError"
import {notificationSettings} from "@/api/mocks/fixtures"
import type {AppServices} from "@/services/AppServices"
import type {PushPermission} from "@/services/push/PushService"
import {fakeRepositories, fakeServices, signedInServices} from "@/test/fakeServices"
import {renderWithServices} from "@/test/renderWithServices"
import {phoneTimeZone, SettingsScreen} from "./SettingsScreen"
import {defaultQuietHours} from "./useSettingsViewModel"

const initial = NotificationSettingsSchema.parse(notificationSettings)

interface Harness {
    readonly services: AppServices
    readonly saveSettings: jest.Mock<Promise<NotificationSettings>, [NotificationSettings]>
    readonly registerDevice: jest.Mock<Promise<Device>, [DeviceRegistration]>
    readonly unregisterDevice: jest.Mock<Promise<void>, [string]>
    readonly logout: jest.Mock<Promise<void>, []>
}

async function open({
    permission = {kind: "unavailable"},
    refuseSave = false,
    save,
    unregister = () => Promise.resolve(),
    revoke = () => Promise.resolve(),
}: {
    permission?: PushPermission
    refuseSave?: boolean
    save?: (settings: NotificationSettings) => Promise<NotificationSettings>
    unregister?: (id: string) => Promise<void>
    revoke?: () => Promise<void>
} = {}): Promise<Harness> {
    const saveSettings = jest.fn(
        save ??
            ((settings: NotificationSettings) =>
                refuseSave ? Promise.reject(new ApiError({kind: "server", status: 500})) : Promise.resolve(settings)),
    )
    const registerDevice = jest.fn((_registration: DeviceRegistration) =>
        Promise.resolve({id: "dev_1", provider: "expo", platform: "ios", createdAt: "2026-10-30T08:00:00.000Z"}),
    )
    const unregisterDevice = jest.fn(unregister)
    const logout = jest.fn(revoke)
    const services = await signedInServices({
        push: {register: () => Promise.resolve(permission), onOpen: () => () => undefined, launchData: () => Promise.resolve(undefined)},
        repositories: fakeRepositories({
            account: {me: jest.fn(), exchangeCode: jest.fn(), logout},
            notifications: {registerDevice, unregisterDevice, settings: () => Promise.resolve(initial), saveSettings},
        }),
    })
    await renderWithServices(<SettingsScreen />, services)
    await screen.findByRole("switch", {name: "A request waits for my yes"})
    return {services, saveSettings, registerDevice, unregisterDevice, logout}
}

const granted: PushPermission = {kind: "granted", address: {pushToken: "ExponentPushToken[abc]", platform: "ios"}}

describe("SettingsScreen", () => {
    it("shows the account and the notification settings", async () => {
        await open()
        expect(screen.getByText("rev-b")).toBeOnTheScreen()
        expect(screen.getByText("https://ostrom.test")).toBeOnTheScreen()
        expect(screen.getByRole("switch", {name: "A request waits for my yes"})).toBeChecked()
        expect(screen.getByRole("switch", {name: "Spending passes a budget threshold"})).not.toBeChecked()
        expect(screen.getByText("No notification from 22:00 to 07:00 (Europe/Paris)")).toBeOnTheScreen()
        expect(await screen.findByText(/Notifications are off on this phone/)).toBeOnTheScreen()
    })

    it("registers this phone for push when the person allows it", async () => {
        const {registerDevice} = await open({permission: granted})
        expect(await screen.findByText("This phone receives Ostrom notifications.")).toBeOnTheScreen()
        expect(registerDevice).toHaveBeenCalledWith({pushToken: "ExponentPushToken[abc]", platform: "ios", provider: "expo", locale: "en"})
    })

    it("saves a toggle at once", async () => {
        const {saveSettings} = await open()
        await fireEvent.press(screen.getByRole("switch", {name: "Spending passes a budget threshold"}))
        await waitFor(() => {
            expect(saveSettings).toHaveBeenCalledWith({...initial, spending: true})
            expect(screen.getByRole("switch", {name: "Spending passes a budget threshold"})).toBeChecked()
        })
    })

    it("turns quiet hours off, then on again in the phone's time zone", async () => {
        const {saveSettings} = await open()
        await fireEvent.press(screen.getByRole("switch", {name: "Quiet hours"}))
        await waitFor(() => {
            expect(saveSettings).toHaveBeenLastCalledWith({requests: true, decisions: true, spending: false})
            expect(screen.queryByText(/No notification from/)).toBeNull()
        })
        await fireEvent.press(screen.getByRole("switch", {name: "Quiet hours"}))
        await waitFor(() => {
            expect(saveSettings).toHaveBeenLastCalledWith({...initial, quietHours: defaultQuietHours(phoneTimeZone())})
            expect(screen.getByText(/No notification from 22:00 to 07:00/)).toBeOnTheScreen()
        })
    })

    it.each([true, false])("never flips a second switch back with the answer to the first (first saved: %s)", async (firstSaved) => {
        const answers: (() => void)[] = []
        await open({
            save: (settings) =>
                new Promise<NotificationSettings>((resolve, reject) => {
                    const first = answers.length === 0
                    answers.push(() => {
                        if (!first || firstSaved) resolve(settings)
                        else reject(new ApiError({kind: "server", status: 500}))
                    })
                }),
        })
        await fireEvent.press(screen.getByRole("switch", {name: "Spending passes a budget threshold"}))
        await fireEvent.press(screen.getByRole("switch", {name: "A request waits for my yes"}))
        const bothOn = (): void => {
            expect(screen.getByRole("switch", {name: "A request waits for my yes"})).not.toBeChecked()
            expect(screen.getByRole("switch", {name: "Spending passes a budget threshold"})).toBeChecked()
        }
        await waitFor(() => {
            expect(answers).toHaveLength(2)
            bothOn()
        })
        // The answer settles, then a real tick lets a write reach the screen, if there were one.
        await act(async () => {
            answers[0]?.()
            await new Promise((resolve) => setTimeout(resolve, 20))
        })
        bothOn()
        await act(async () => {
            answers[1]?.()
            await new Promise((resolve) => setTimeout(resolve, 20))
        })
        bothOn()
        expect(answers).toHaveLength(2)
    })

    it("puts a setting back when the server refuses it", async () => {
        await open({refuseSave: true})
        await fireEvent.press(screen.getByRole("switch", {name: "A request waits for my yes"}))
        expect(await screen.findByText("The change could not be saved. It has been undone.")).toBeOnTheScreen()
        expect(screen.getByRole("switch", {name: "A request waits for my yes"})).toBeChecked()
    })

    it("signs out, stops push on this phone, then revokes the token on the server", async () => {
        const {services, unregisterDevice, logout} = await open({permission: granted})
        await screen.findByText("This phone receives Ostrom notifications.")
        await fireEvent.press(screen.getByRole("button", {name: "Sign out"}))
        await waitFor(() => {
            expect(services.sessionStore.getState().state).toEqual({status: "signed-out"})
        })
        await waitFor(() => {
            expect(logout).toHaveBeenCalledTimes(1)
        })
        expect(unregisterDevice).toHaveBeenCalledWith("dev_1")
        expect(unregisterDevice.mock.invocationCallOrder[0]).toBeLessThan(logout.mock.invocationCallOrder[0] ?? 0)
    })

    it("signs out at once, without waiting for the server", async () => {
        const never = (): Promise<void> => new Promise(() => undefined)
        const {services, logout} = await open({unregister: never, revoke: never})
        await fireEvent.press(screen.getByRole("button", {name: "Sign out"}))
        await waitFor(() => {
            expect(services.sessionStore.getState().state).toEqual({status: "signed-out"})
        })
        // No push device on this phone: the revocation goes alone.
        expect(logout).toHaveBeenCalledTimes(1)
    })

    it("signs out even when the server refuses both calls", async () => {
        const offline = (): Promise<void> => Promise.reject(new ApiError({kind: "network"}))
        const {services, logout} = await open({permission: granted, unregister: offline, revoke: offline})
        await screen.findByText("This phone receives Ostrom notifications.")
        await fireEvent.press(screen.getByRole("button", {name: "Sign out"}))
        await waitFor(() => {
            expect(services.sessionStore.getState().state).toEqual({status: "signed-out"})
        })
        await waitFor(() => {
            expect(logout).toHaveBeenCalledTimes(1)
        })
    })

    it("offers to try again when the settings cannot be loaded, and shows no account once signed out", async () => {
        const services = fakeServices()
        await renderWithServices(<SettingsScreen />, services)
        expect(await screen.findByText("The notification settings could not be loaded.")).toBeOnTheScreen()
        expect(screen.queryByText("rev-b")).toBeNull()
        await fireEvent.press(screen.getByRole("button", {name: "Try again"}))
        expect(await screen.findByText("The notification settings could not be loaded.")).toBeOnTheScreen()
    })
})
