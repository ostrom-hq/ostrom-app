// SPDX-License-Identifier: AGPL-3.0-only
import {z} from "zod"
import type {AppServices} from "@/services/AppServices"
import type {BiometricGate} from "@/services/biometrics/BiometricGate"
import {MemoryUnlockKeyStore} from "@/services/unlock/MemoryUnlockKeyStore"
import {PhoneUnlockImpl} from "@/services/unlock/PhoneUnlockImpl"
import {createAppServices} from "./services"

/** The real server the CI started, and the token it made there for a person it signed in by e-mail. */
const RealServerSchema = z.object({serverUrl: z.url(), token: z.string().min(1), login: z.string().min(1)})
export type RealServer = z.output<typeof RealServerSchema>

/** What the build was given; a missing value stops the app at once, rather than a flow failing on a blank screen. */
export function realServer(given: unknown): RealServer {
    const parsed = RealServerSchema.safeParse(given)
    if (!parsed.success) throw new Error("The real-server E2E build needs EXPO_PUBLIC_OSTROM_E2E_SERVER, _TOKEN and _LOGIN")
    return parsed.data
}

/**
 * The services of the E2E build against a real Ostrom server (`EXPO_PUBLIC_OSTROM_REAL_E2E=1`, CI only, see
 * `metro.config.js` and `.github/workflows/e2e-real.yml`): the real services, HTTP client and repositories, except what
 * the emulator cannot do. The GitHub sign-in gives the token the CI made on that server, the fingerprint is confirmed
 * (no finger is enrolled), and push is unavailable.
 */
export function createRealServerAppServices(server: RealServer): AppServices {
    const biometrics: BiometricGate = {confirm: () => Promise.resolve("confirmed")}
    return {
        ...createAppServices(),
        signIn: {
            signIn: () =>
                Promise.resolve({
                    kind: "signed-in",
                    session: {serverUrl: server.serverUrl, token: server.token, user: {login: server.login}},
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
