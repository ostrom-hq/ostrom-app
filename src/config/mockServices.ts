// SPDX-License-Identifier: AGPL-3.0-only
import * as fixtures from "@/api/mocks/fixtures"
import {mockFetch} from "@/api/mocks/mockFetch"
import {createMockServer} from "@/api/mocks/mockServer"
import {HttpClientImpl} from "@/api/http/HttpClientImpl"
import type {AppServices} from "@/services/AppServices"
import {SecureStorageImpl} from "@/services/storage/SecureStorageImpl"
import {createSessionStore} from "@/stores/session"
import {copyToClipboard, openOutside, repositoriesOver} from "./services"

/**
 * The services of the E2E build: no Ostrom server runs on the CI runner, so the app talks to the in-memory server of
 * `api/mocks`, through the real HTTP client, repositories and screens. Only what the emulator cannot do is replaced:
 * the GitHub sign-in (signed in as `rev-b` of `acme` on the address typed), the fingerprint (no finger is enrolled: confirmed),
 * and push (no project id: unavailable). The session store and the links are the real ones.
 */
export function createMockAppServices(): AppServices {
    const fetch = mockFetch(createMockServer())
    return {
        sessionStore: createSessionStore(new SecureStorageImpl()),
        signIn: {
            signIn: (serverUrl) =>
                Promise.resolve({
                    kind: "signed-in",
                    session: {serverUrl, token: "ost_mock_token", user: {login: fixtures.me.dev.login}, orgId: fixtures.acme.id},
                }),
        },
        biometrics: {confirm: () => Promise.resolve("confirmed")},
        push: {
            register: () => Promise.resolve({kind: "unavailable"}),
            onOpen: () => () => undefined,
            launchData: () => Promise.resolve(undefined),
        },
        repositoriesFor: (session) =>
            repositoriesOver(
                new HttpClientImpl({baseUrl: session.serverUrl, token: () => session.token, org: () => session.orgId, fetch}),
            ),
        openUrl: openOutside,
        copyText: copyToClipboard,
    }
}
