// SPDX-License-Identifier: AGPL-3.0-only
import {act, fireEvent, screen, waitFor} from "@testing-library/react-native"
import {ApiError} from "@/api/http/ApiError"
import type {AppServices} from "@/services/AppServices"
import {fakeRepositories, signedInServices} from "@/test/fakeServices"
import {renderWithServices} from "@/test/renderWithServices"
import {IncompatibleServerScreen} from "./IncompatibleServerScreen"

async function open(
    logout: () => Promise<void>,
    apiVersion: number | undefined,
): Promise<{services: AppServices; logout: jest.Mock<Promise<void>, []>}> {
    const logoutCall = jest.fn(logout)
    const services = await signedInServices({
        repositories: fakeRepositories({
            account: {me: jest.fn(), organizations: jest.fn(), exchangeCode: jest.fn(), unlock: jest.fn(), logout: logoutCall},
        }),
    })
    services.sessionStore.getState().noteIncompatible("ost_1", apiVersion)
    await renderWithServices(<IncompatibleServerScreen />, services)
    return {services, logout: logoutCall}
}

describe("IncompatibleServerScreen", () => {
    it("names the API version the server speaks, and signs out", async () => {
        const {services, logout} = await open(() => Promise.resolve(), 2)
        expect(screen.getByText("This server is not supported")).toBeOnTheScreen()
        expect(screen.getByText(/This server speaks API v2; this app speaks v1/)).toBeOnTheScreen()
        await fireEvent.press(screen.getByRole("button", {name: "Sign out"}))
        await waitFor(() => {
            expect(services.sessionStore.getState().state).toEqual({status: "signed-out"})
        })
        expect(logout).toHaveBeenCalledTimes(1)
    })

    it("says a server is too old when it cannot name its version, and signs out when it cannot revoke", async () => {
        const {services} = await open(() => Promise.reject(new ApiError({kind: "network"})), undefined)
        expect(screen.getByText(/This server is too old for this app/)).toBeOnTheScreen()
        await fireEvent.press(screen.getByRole("button", {name: "Sign out"}))
        await act(async () => {
            await Promise.resolve()
        })
        expect(services.sessionStore.getState().state).toEqual({status: "signed-out"})
    })
})
