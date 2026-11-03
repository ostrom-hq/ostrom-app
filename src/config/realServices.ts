// SPDX-License-Identifier: AGPL-3.0-only
import {z} from "zod"
import type {AppServices} from "@/services/AppServices"
import type {BiometricGate} from "@/services/biometrics/BiometricGate"
import {MemoryUnlockKeyStore} from "@/services/unlock/MemoryUnlockKeyStore"
import {PhoneUnlockImpl} from "@/services/unlock/PhoneUnlockImpl"
import {createAppServices} from "./services"

/** The real server the CI started, and the phone session it made there (`POST /auth/mobile/token`) with its unlock key. */
const SessionBuildSchema = z.object({
    serverUrl: z.url(),
    token: z.string().min(1),
    login: z.string().min(1),
    unlockKey: z.string().min(1),
})

/**
 * The real server alone (`EXPO_PUBLIC_OSTROM_E2E_SIGN_IN=browser`): the build signs in through the browser, as a phone
 * does, and is given no session at all (AUDIT-SECU-APP-2 N1).
 */
const BrowserBuildSchema = z.object({
    serverUrl: z.url(),
    token: z.undefined(),
    login: z.undefined(),
    unlockKey: z.undefined(),
})

/** The server, and the session the CI made there unless the build signs in through the browser. */
export interface RealServer {
    readonly serverUrl: string
    readonly session?: {readonly token: string; readonly login: string; readonly unlockKey: string}
}
const variables: Readonly<Record<string, string>> = {
    serverUrl: "EXPO_PUBLIC_OSTROM_E2E_SERVER",
    token: "EXPO_PUBLIC_OSTROM_E2E_TOKEN",
    login: "EXPO_PUBLIC_OSTROM_E2E_LOGIN",
    unlockKey: "EXPO_PUBLIC_OSTROM_E2E_UNLOCK_KEY",
}

function namesOf(error: z.ZodError, whole: string): string {
    return error.issues.map((issue) => variables[issue.path.map(String).join(".")] ?? whole).join(", ")
}

/**
 * What the build was given; a missing value stops the app at once, rather than a flow failing on a blank screen. A
 * browser build given any part of a session stops too: it must prove the sign-in, not skip it.
 *
 * @param given The variables, by field.
 * @param signIn `browser` for a build that signs in through the browser (`EXPO_PUBLIC_OSTROM_E2E_SIGN_IN`).
 */
export function realServer(given: unknown, signIn?: string): RealServer {
    if (signIn === "browser") {
        const parsed = BrowserBuildSchema.safeParse(given)
        if (!parsed.success) {
            const extra = parsed.error.issues.filter((issue) => issue.path[0] !== "serverUrl")
            if (extra.length > 0) {
                const names = namesOf(new z.ZodError(extra), "a session")
                throw new Error(`The real-server E2E build that signs in through the browser takes no session, and is given ${names}`)
            }
            throw new Error("The real-server E2E build needs EXPO_PUBLIC_OSTROM_E2E_SERVER")
        }
        return {serverUrl: parsed.data.serverUrl}
    }
    const parsed = SessionBuildSchema.safeParse(given)
    if (!parsed.success) {
        throw new Error(`The real-server E2E build needs ${namesOf(parsed.error, "EXPO_PUBLIC_OSTROM_E2E_SERVER, _TOKEN, _LOGIN and _UNLOCK_KEY")}`)
    }
    const {serverUrl, ...session} = parsed.data
    return {serverUrl, session}
}

/**
 * The services of the E2E build against a real Ostrom server (`EXPO_PUBLIC_OSTROM_REAL_E2E=1`, CI only, see
 * `metro.config.js`, and ostrom-server's `.github/workflows/app-journey.yml`): the real services, HTTP client and repositories, except what
 * the emulator cannot do. The GitHub sign-in gives the phone session the CI made on that server, with its unlock key, or, in a browser build,
 * is the real one, to that server whatever address is typed (a release build takes no HTTP address); the fingerprint is confirmed (no finger
 * is enrolled), and push is unavailable.
 */
export function createRealServerAppServices(server: RealServer): AppServices {
    const biometrics: BiometricGate = {confirm: () => Promise.resolve("confirmed")}
    // Its own server only, which it reaches over HTTP in a release build.
    const real = createAppServices({keepsServer: (serverUrl) => serverUrl === server.serverUrl})
    const session = server.session
    return {
        ...real,
        signIn: {
            signIn: () =>
                session === undefined
                    ? real.signIn.signIn(server.serverUrl)
                    : Promise.resolve({
                          kind: "signed-in",
                          session: {serverUrl: server.serverUrl, token: session.token, user: {login: session.login}},
                          unlockKey: session.unlockKey,
                      }),
        },
        biometrics,
        unlock: new PhoneUnlockImpl({keys: new MemoryUnlockKeyStore(), biometrics}),
        push: {
            register: () => Promise.resolve({kind: "unavailable"}),
            onOpen: () => () => undefined,
            launchData: () => Promise.resolve(undefined),
            clearShown: () => Promise.resolve(),
        },
    }
}
