// SPDX-License-Identifier: AGPL-3.0-only
import {act, renderHook} from "@testing-library/react-native"
import type {ReactNode} from "react"
import {fakeServices, testSession} from "@/test/fakeServices"
import {FEATURES} from "@/domain/capabilities/compatibility"
import {ServicesProvider, SIGNED_OUT_REPOSITORIES, useCapabilities, useFeature, useIncompatibleServer, useRepositories, useServices} from "./AppServices"

describe("AppServices", () => {
    it("refuses to be used outside its provider", async () => {
        await expect(renderHook(() => useServices())).rejects.toThrow("useServices outside ServicesProvider")
    })

    it("gives the repositories of the signed-in session only", async () => {
        const services = fakeServices()
        const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
            <ServicesProvider services={services}>{children}</ServicesProvider>
        )
        const signedOut = await renderHook(() => useRepositories(), {wrapper})
        expect(signedOut.result.current).toBe(SIGNED_OUT_REPOSITORIES)
        await expect(signedOut.result.current.approvals.queue()).rejects.toMatchObject({problem: {kind: "unauthorized"}})
        await services.sessionStore.getState().signIn(testSession)
        const {result} = await renderHook(() => useRepositories(), {wrapper})
        expect(result.current).toBe(services.repositoriesFor(testSession))
    })

    it("refuses every read of the capabilities signed out", async () => {
        await expect(SIGNED_OUT_REPOSITORIES.capabilities.read()).rejects.toMatchObject({problem: {kind: "unauthorized"}})
    })

    describe("what the server serves", () => {
        const capabilities = {apiVersion: 1, serverVersion: "1.4.0", features: ["receipts.v1"]}

        it("knows nothing signed out or before the server was read, and hides no function then", async () => {
            const services = fakeServices()
            const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
                <ServicesProvider services={services}>{children}</ServicesProvider>
            )
            const signedOut = await renderHook(() => useCapabilities(), {wrapper})
            expect(signedOut.result.current).toBeUndefined()
            await services.sessionStore.getState().signIn(testSession)
            const unread = await renderHook(() => [useCapabilities(), useFeature(FEATURES.push)] as const, {wrapper})
            expect(unread.result.current).toEqual([undefined, true])
        })

        it("hides the function of a feature the server does not list", async () => {
            const services = fakeServices()
            await services.sessionStore.getState().signIn({...testSession, capabilities})
            const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
                <ServicesProvider services={services}>{children}</ServicesProvider>
            )
            const {result} = await renderHook(() => [useFeature(FEATURES.receipts), useFeature(FEATURES.push)] as const, {wrapper})
            expect(result.current).toEqual([true, false])
        })

        it("says when the server is one this app does not speak", async () => {
            const services = fakeServices()
            await services.sessionStore.getState().signIn(testSession)
            const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
                <ServicesProvider services={services}>{children}</ServicesProvider>
            )
            const {result} = await renderHook(() => useIncompatibleServer(), {wrapper})
            expect(result.current).toBeUndefined()
            await act(async () => {
                services.sessionStore.getState().noteIncompatible(testSession.token, 2)
                await Promise.resolve()
            })
            expect(result.current).toEqual({apiVersion: 2})
        })
    })
})
