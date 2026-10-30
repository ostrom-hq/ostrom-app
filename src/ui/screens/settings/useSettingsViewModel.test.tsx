// SPDX-License-Identifier: AGPL-3.0-only
import {QueryClientProvider} from "@tanstack/react-query"
import {act, renderHook} from "@testing-library/react-native"
import type {ReactNode} from "react"
import type {NotificationSettings} from "@/api/contract/notifications"
import {ServicesProvider} from "@/services/AppServices"
import {fakeRepositories, signedInServices} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {useSettingsViewModel} from "./useSettingsViewModel"

describe("useSettingsViewModel", () => {
    it("changes and saves nothing before the settings are read", async () => {
        const saveSettings = jest.fn<Promise<NotificationSettings>, [NotificationSettings]>()
        const services = await signedInServices({
            repositories: fakeRepositories({
                notifications: {
                    registerDevice: jest.fn(),
                    unregisterDevice: jest.fn(),
                    settings: () => new Promise<NotificationSettings>(() => undefined),
                    saveSettings,
                },
            }),
        })
        const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
            <ServicesProvider services={services}>
                <QueryClientProvider client={testQueryClient()}>{children}</QueryClientProvider>
            </ServicesProvider>
        )
        const {result} = await renderHook(() => useSettingsViewModel("en", "Europe/Paris"), {wrapper})
        expect(result.current.state.kind).toBe("loading")
        await act(async () => {
            result.current.toggle("spending", true)
            result.current.setQuietHours(true)
            await new Promise((resolve) => setTimeout(resolve, 20))
        })
        expect(saveSettings).not.toHaveBeenCalled()
    })
})
