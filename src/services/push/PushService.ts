// SPDX-License-Identifier: AGPL-3.0-only
/** This phone's push address, as the server needs it to say "a request waits for you". */
export interface PushAddress {
    readonly pushToken: string
    readonly platform: "ios" | "android"
}

export type PushPermission =
    | {readonly kind: "granted"; readonly address: PushAddress}
    /** The person said no, or the system does not allow asking again. */
    | {readonly kind: "denied"}
    /** No push on this build (a simulator, or no project id until the EAS project exists). */
    | {readonly kind: "unavailable"}

/** Push notifications, reduced to what Ostrom needs: an address, and the taps on its notifications. */
export interface PushService {
    /** Asks for permission when it was never asked, then gives this phone's address. */
    register(): Promise<PushPermission>
    /** The data of each notification the person taps, while the app runs. Returns the unsubscribe. */
    onOpen(listener: (data: unknown) => void): () => void
    /** The data of the notification that launched the app, if one did. */
    launchData(): Promise<unknown>
    /**
     * Takes this app's notifications off the screen, the lock screen included: at sign-out, nothing of the account
     * stays shown (AUDIT-SECU-APP-2 N3).
     */
    clearShown(): Promise<void>
}
