// SPDX-License-Identifier: AGPL-3.0-only
import {QueryClientProvider} from "@tanstack/react-query"
import {act, renderHook} from "@testing-library/react-native"
import type {ReactNode} from "react"
import type {NotificationSettings} from "@/api/contract/notifications"
import {ServicesProvider} from "@/services/AppServices"
import {fakeRepositories, signedInServices} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {useNotificationSettingsEditor} from "./notifications"

describe("useNotificationSettingsEditor", () => {
    it("changes and saves nothing while the settings were never read", async () => {
        const saveSettings = jest.fn<Promise<NotificationSettings>, [NotificationSettings]>()
        const services = await signedInServices({
            repositories: fakeRepositories({
                notifications: {registerDevice: jest.fn(), unregisterDevice: jest.fn(), settings: jest.fn(), saveSettings},
            }),
        })
        const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
            <ServicesProvider services={services}>
                <QueryClientProvider client={testQueryClient()}>{children}</QueryClientProvider>
            </ServicesProvider>
        )
        const {result} = await renderHook(() => useNotificationSettingsEditor(), {wrapper})
        const edit = jest.fn((current: NotificationSettings) => current)
        await act(async () => {
            result.current.change(edit)
            await new Promise((resolve) => setTimeout(resolve, 20))
        })
        expect(edit).not.toHaveBeenCalled()
        expect(saveSettings).not.toHaveBeenCalled()
        expect(result.current.failed).toBe(false)
    })
})
