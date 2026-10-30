// SPDX-License-Identifier: AGPL-3.0-only
import * as fixtures from "@/api/mocks/fixtures"
import {mockFetch} from "@/api/mocks/mockFetch"
import {createMockServer} from "@/api/mocks/mockServer"
import {HttpClientImpl} from "@/api/http/HttpClientImpl"
import type {AppServices} from "@/services/AppServices"
import {createAppServices, repositoriesOver} from "./services"

/**
 * The services of the E2E build (`EXPO_PUBLIC_OSTROM_MOCK=1`): no Ostrom server runs on the CI runner, so the app
 * talks to the in-memory server of `api/mocks`, through the real HTTP client, repositories and screens. Only what the
 * emulator cannot do is replaced: the GitHub sign-in (the server's answer, signed in as `rev-b` on the address typed),
 * the fingerprint (no finger is enrolled: confirmed), and push (no project id: unavailable). The session store and
 * the links stay the real ones.
 */
export function createMockAppServices(): AppServices {
    const fetch = mockFetch(createMockServer())
    return {
        ...createAppServices(),
        signIn: {
            signIn: (serverUrl) =>
                Promise.resolve({kind: "signed-in", session: {serverUrl, token: "ost_mock_token", user: {login: fixtures.me.dev.login}}}),
        },
        biometrics: {confirm: () => Promise.resolve("confirmed")},
        push: {
            register: () => Promise.resolve({kind: "unavailable"}),
            onOpen: () => () => undefined,
            launchData: () => Promise.resolve(undefined),
        },
        repositoriesFor: (session) =>
            repositoriesOver(new HttpClientImpl({baseUrl: session.serverUrl, token: () => session.token, fetch})),
    }
}
