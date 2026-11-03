// SPDX-License-Identifier: AGPL-3.0-only
import * as Crypto from "expo-crypto"
import * as WebBrowser from "expo-web-browser"
import * as fixtures from "@/api/mocks/fixtures"
import {mockFetch} from "@/api/mocks/mockFetch"
import {createMockServer, MOCK_UNLOCK_KEY} from "@/api/mocks/mockServer"
import {HttpClientImpl} from "@/api/http/HttpClientImpl"
import {AccountRepositoryImpl} from "@/repositories/AccountRepositoryImpl"
import type {AppServices} from "@/services/AppServices"
import type {BiometricGate} from "@/services/biometrics/BiometricGate"
import {GithubSignInImpl} from "@/services/signIn/GithubSignInImpl"
import {SecureStorageImpl} from "@/services/storage/SecureStorageImpl"
import {MemoryUnlockKeyStore} from "@/services/unlock/MemoryUnlockKeyStore"
import {PhoneUnlockImpl} from "@/services/unlock/PhoneUnlockImpl"
import {createSessionStore} from "@/stores/session"
import {copyToClipboard, openOutside, repositoriesOver} from "./services"

/**
 * Where the E2E build's browser sign-in starts: `scripts/e2e-auth-host.py`, on the runner, which the emulator reaches at
 * 10.0.2.2. It stands for GitHub and the server's start route, and answers on the app's `ostrom://auth/callback`.
 */
const E2E_AUTH_HOST = "http://10.0.2.2:4700"

/** A server whose address ends with this signs in through the real browser and the E2E auth host. */
const BROWSER_SERVERS = ".browser.example"

/**
 * The sign-in's start URL, sent to the E2E auth host instead of the typed server, with the first label of the server
 * as the host's mode: `good` answers with the sign-in's own state, `forged` with another, `replay` with the state of
 * the sign-in before it.
 */
export function throughE2eAuthHost(url: string): string {
    const start = new URL(url)
    start.searchParams.set("mode", start.hostname.replace(/\..*$/, ""))
    // React Native's URL gives the changed query in toString() only, not in `search`.
    return `${E2E_AUTH_HOST}${start.toString().slice(start.origin.length)}`
}

/**
 * The services of the E2E build: no Ostrom server runs on the CI runner, so the app talks to the in-memory server of
 * `api/mocks`, through the real HTTP client, repositories and screens. Only what the emulator cannot do is replaced:
 * the GitHub sign-in (signed in as `rev-b` of `acme` on the address typed, with the mock server's unlock key), the
 * fingerprint (no finger is enrolled: confirmed, and the key is given back from memory), and push (no project id:
 * unavailable). The session store, the unlock against the server and the links are the real ones. On a server of
 * `browser.example`, the sign-in is the real one, down to the system browser and the `ostrom://` return, with the E2E
 * auth host for GitHub (AUDIT-SECU-APP-2 A4, proved on the emulator).
 */
export function createMockAppServices(): AppServices {
    const fetch = mockFetch(createMockServer())
    const biometrics: BiometricGate = {confirm: () => Promise.resolve("confirmed")}
    const browserSignIn = new GithubSignInImpl({
        openAuthSession: (url, redirectUri) => WebBrowser.openAuthSessionAsync(throughE2eAuthHost(url), redirectUri),
        randomBytes: (length) => Crypto.getRandomBytes(length),
        accountOf: (serverUrl, token) => new AccountRepositoryImpl(new HttpClientImpl({baseUrl: serverUrl, token: () => token, fetch})),
    })
    return {
        sessionStore: createSessionStore(new SecureStorageImpl()),
        signIn: {
            signIn: (serverUrl) => {
                if (new URL(serverUrl).hostname.endsWith(BROWSER_SERVERS)) return browserSignIn.signIn(serverUrl)
                return Promise.resolve({
                    kind: "signed-in",
                    session: {serverUrl, token: "ost_mock_token", user: {login: fixtures.me.dev.login}, orgId: fixtures.acme.id},
                    unlockKey: MOCK_UNLOCK_KEY,
                })
            },
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
