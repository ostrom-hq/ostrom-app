// SPDX-License-Identifier: AGPL-3.0-only
import {z} from "zod"
import type {AppServices} from "@/services/AppServices"
import type {BiometricGate} from "@/services/biometrics/BiometricGate"
import {MemoryUnlockKeyStore} from "@/services/unlock/MemoryUnlockKeyStore"
import {PhoneUnlockImpl} from "@/services/unlock/PhoneUnlockImpl"
import {createAppServices} from "./services"

/** The real server the CI started, and the phone session it made there (`POST /auth/mobile/token`) with its unlock key. */
const RealServerSchema = z.object({
    serverUrl: z.url(),
    token: z.string().min(1),
    login: z.string().min(1),
    unlockKey: z.string().min(1),
})
export type RealServer = z.output<typeof RealServerSchema>
const variables: Readonly<Record<string, string>> = {
    serverUrl: "EXPO_PUBLIC_OSTROM_E2E_SERVER",
    token: "EXPO_PUBLIC_OSTROM_E2E_TOKEN",
    login: "EXPO_PUBLIC_OSTROM_E2E_LOGIN",
    unlockKey: "EXPO_PUBLIC_OSTROM_E2E_UNLOCK_KEY",
}

/** What the build was given; a missing value stops the app at once, rather than a flow failing on a blank screen. */
export function realServer(given: unknown): RealServer {
    const parsed = RealServerSchema.safeParse(given)
    if (!parsed.success) {
        const missing = parsed.error.issues.map(
            (issue) => variables[issue.path.map(String).join(".")] ?? "EXPO_PUBLIC_OSTROM_E2E_SERVER, _TOKEN, _LOGIN and _UNLOCK_KEY",
        )
        throw new Error(`The real-server E2E build needs ${missing.join(", ")}`)
    }
    return parsed.data
}

/**
 * The services of the E2E build against a real Ostrom server (`EXPO_PUBLIC_OSTROM_REAL_E2E=1`, CI only, see
 * `metro.config.js`, and ostrom-server's `.github/workflows/app-journey.yml`): the real services, HTTP client and repositories, except what
 * the emulator cannot do. The GitHub sign-in gives the phone session the CI made on that server, with its unlock key, the fingerprint is confirmed
 * (no finger is enrolled), and push is unavailable.
 */
export function createRealServerAppServices(server: RealServer): AppServices {
    const biometrics: BiometricGate = {confirm: () => Promise.resolve("confirmed")}
    return {
        // Its own server only, which it reaches over HTTP in a release build.
        ...createAppServices({keepsServer: (serverUrl) => serverUrl === server.serverUrl}),
        signIn: {
            signIn: () =>
                Promise.resolve({
                    kind: "signed-in",
                    session: {serverUrl: server.serverUrl, token: server.token, user: {login: server.login}},
                    unlockKey: server.unlockKey,
                }),
        },
        biometrics,
        unlock: new PhoneUnlockImpl({keys: new MemoryUnlockKeyStore(), biometrics}),
        push: {
            register: () => Promise.resolve({kind: "unavailable"}),
            onOpen: () => () => undefined,
            launchData: () => Promise.resolve(undefined),
        },
    }
}
