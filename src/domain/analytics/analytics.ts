/**
 * Product analytics of the app, **opt-in**: off until the person turns it on (the toggle shown once after sign-in, and
 * in the settings), and nothing is stored or sent before that. No key configured (a self-hosted build) means no
 * analytics at all, toggle included. Events follow ostrom-core `docs/telemetry.md`: `$screen` and
 * `gate_opened_in_client`, with the user's opaque id once signed in, never an e-mail, a name or a request's content.
 */

/** Where the choice and the anonymous id are kept; the app passes its secure storage. */
export interface AnalyticsStorage {
    get(key: string): Promise<string | undefined>
    set(key: string, value: string): Promise<void>
    remove(key: string): Promise<void>
}

export type AnalyticsChoice = "on" | "off" | "unavailable"

export interface AnalyticsEvent {
    readonly event: string
    readonly distinct_id: string
    readonly timestamp: string
    readonly properties: Readonly<Record<string, unknown>>
}

export interface AnalyticsOptions {
    /** The PostHog project key (public, write-only); empty for a build without analytics. */
    readonly apiKey: string
    /** PostHog host, EU cloud by default. */
    readonly host: string
    readonly storage: AnalyticsStorage
    /** Posts a JSON body; a failure is dropped by the caller of `flush`. */
    readonly post: (url: string, body: string) => Promise<void>
    readonly appVersion: string
    readonly platform: string
    readonly now?: () => Date
    readonly newId?: () => string
}

export interface Analytics {
    /** `on`, `off` (the default) or `unavailable` when the build has no key. */
    choice(): Promise<AnalyticsChoice>
    /** Turns analytics on; a no-op without a key. */
    enable(): Promise<void>
    /** Turns analytics off, forgets the anonymous id and drops what was not sent. */
    disable(): Promise<void>
    /** The person signed in: later events carry their opaque user id and organization. */
    identify(userId: string, organizationId: string | undefined): void
    /** The person signed out: back to the anonymous id. */
    reset(): void
    screen(name: string): Promise<void>
    capture(event: string, properties?: Readonly<Record<string, string | number | boolean>>): Promise<void>
    /** Sends what is queued; failures are dropped (analytics never change what the app does). */
    flush(): Promise<void>
}

export const CHOICE_KEY = "ostrom.analytics"
export const ANONYMOUS_ID_KEY = "ostrom.analytics.id"
export const DEFAULT_HOST = "https://eu.i.posthog.com"
/** Above this many queued events, a capture sends the batch at once. */
export const MAX_BATCH = 20

export function createAnalytics(options: AnalyticsOptions): Analytics {
    const now = options.now ?? (() => new Date())
    const newId = options.newId ?? randomId
    const available = options.apiKey.trim() !== ""
    let queue: AnalyticsEvent[] = []
    let user: {readonly id: string; readonly organization: string | undefined} | undefined

    async function choice(): Promise<AnalyticsChoice> {
        if (!available) return "unavailable"
        return (await options.storage.get(CHOICE_KEY)) === "on" ? "on" : "off"
    }

    async function anonymousId(): Promise<string> {
        const existing = await options.storage.get(ANONYMOUS_ID_KEY)
        if (existing !== undefined) return existing
        const created = newId()
        await options.storage.set(ANONYMOUS_ID_KEY, created)
        return created
    }

    async function flush(): Promise<void> {
        if (queue.length === 0) return
        const batch = queue
        queue = []
        try {
            await options.post(`${options.host.replace(/\/+$/, "")}/batch/`, JSON.stringify({api_key: options.apiKey, batch}))
        } catch {
            // Dropped on purpose: the network or PostHog failed, and analytics are best effort.
        }
    }

    async function capture(event: string, properties: Readonly<Record<string, unknown>> = {}): Promise<void> {
        if ((await choice()) !== "on") return
        const groups = user?.organization === undefined ? {} : {$groups: {organization: user.organization}}
        queue.push({
            event,
            distinct_id: user?.id ?? (await anonymousId()),
            timestamp: now().toISOString(),
            properties: {
                ...properties,
                ...groups,
                $lib: "ostrom-app",
                $app_version: options.appVersion,
                $os: options.platform,
                $process_person_profile: user !== undefined,
            },
        })
        if (queue.length >= MAX_BATCH) await flush()
    }

    return {
        choice,
        async enable() {
            if (!available) return
            await options.storage.set(CHOICE_KEY, "on")
            await capture("consent_updated", {analytics: true})
        },
        async disable() {
            queue = []
            await options.storage.set(CHOICE_KEY, "off")
            await options.storage.remove(ANONYMOUS_ID_KEY)
        },
        identify(userId, organizationId) {
            user = {id: userId, organization: organizationId}
        },
        reset() {
            user = undefined
        },
        screen: (name) => capture("$screen", {$screen_name: name}),
        capture,
        flush,
    }
}

/**
 * A random UUID v4 for the anonymous id. Hermes has no `crypto.randomUUID`; the id only has to be unlikely to collide,
 * it protects nothing.
 */
function randomId(): string {
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (char) => {
        const random = Math.floor(Math.random() * 16)
        return (char === "x" ? random : (random % 4) + 8).toString(16)
    })
}
