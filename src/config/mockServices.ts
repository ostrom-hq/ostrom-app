// SPDX-License-Identifier: AGPL-3.0-only
import * as fixtures from "@/api/mocks/fixtures"
import {mockFetch} from "@/api/mocks/mockFetch"
import {createMockServer, MOCK_UNLOCK_KEY} from "@/api/mocks/mockServer"
import {HttpClientImpl} from "@/api/http/HttpClientImpl"
import type {AppServices} from "@/services/AppServices"
import type {BiometricGate} from "@/services/biometrics/BiometricGate"
import {SecureStorageImpl} from "@/services/storage/SecureStorageImpl"
import {MemoryUnlockKeyStore} from "@/services/unlock/MemoryUnlockKeyStore"
import {PhoneUnlockImpl} from "@/services/unlock/PhoneUnlockImpl"
import {createSessionStore} from "@/stores/session"
import {copyToClipboard, openOutside, repositoriesOver} from "./services"

/**
 * The services of the E2E build: no Ostrom server runs on the CI runner, so the app talks to the in-memory server of
 * `api/mocks`, through the real HTTP client, repositories and screens. Only what the emulator cannot do is replaced:
 * the GitHub sign-in (signed in as `rev-b` of `acme` on the address typed, with the mock server's unlock key), the
 * fingerprint (no finger is enrolled: confirmed, and the key is given back from memory), and push (no project id:
 * unavailable). The session store, the unlock against the server and the links are the real ones.
 */
export function createMockAppServices(): AppServices {
    const fetch = mockFetch(createMockServer())
    const biometrics: BiometricGate = {confirm: () => Promise.resolve("confirmed")}
    return {
        sessionStore: createSessionStore(new SecureStorageImpl()),
        signIn: {
            signIn: (serverUrl) =>
                Promise.resolve({
                    kind: "signed-in",
                    session: {serverUrl, token: "ost_mock_token", user: {login: fixtures.me.dev.login}, orgId: fixtures.acme.id},
                    unlockKey: MOCK_UNLOCK_KEY,
                }),
        },
        biometrics,
        unlock: new PhoneUnlockImpl({keys: new MemoryUnlockKeyStore(), biometrics}),
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
