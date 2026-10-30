// SPDX-License-Identifier: AGPL-3.0-only
/**
 * Product analytics of the app, **opt-in**: off until the person turns it on (the toggle shown once after sign-in, and
 * in the settings), and nothing is stored or sent before that. No key configured (a self-hosted build) means no
 * analytics at all, toggle included. Events follow ostrom-core `docs/telemetry.md`: `$screen` and
 * `gate_opened_in_client`, with the user's opaque id once signed in, never an e-mail, a name or a request's content.
 *
 * Analytics never change what the app does: no method rejects, a storage or network failure only loses events.
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
    /** Posts a JSON body; a failure is dropped. */
    readonly post: (url: string, body: string) => Promise<void>
    readonly appVersion: string
    readonly platform: string
    readonly now?: () => Date
    readonly newId?: () => string
    /** Runs a task later (the flush of a small batch); `setTimeout` by default. */
    readonly schedule?: (task: () => void, delayMs: number) => void
}

export interface Analytics {
    /** `on`, `off` (the default) or `unavailable` when the build has no key. */
    choice(): Promise<AnalyticsChoice>
    /** Turns analytics on; a no-op without a key or when already on. */
    enable(): Promise<void>
    /** Turns analytics off, forgets the anonymous id and drops what was not sent. */
    disable(): Promise<void>
    /** The person signed in: later events carry their opaque user id and organization. */
    identify(userId: string, organizationId: string | undefined): void
    /**
     * The person signed out: what was queued leaves, then a new anonymous id starts, so that the next person on this
     * device is never merged with this one.
     */
    reset(): Promise<void>
    screen(name: string): Promise<void>
    capture(event: string, properties?: Readonly<Record<string, string | number | boolean>>): Promise<void>
    /** Sends what is queued; the app also calls it when it goes to the background. */
    flush(): Promise<void>
}

export const CHOICE_KEY = "ostrom.analytics"
export const ANONYMOUS_ID_KEY = "ostrom.analytics.id"
export const DEFAULT_HOST = "https://eu.i.posthog.com"
/** At this many queued events, the batch leaves at once. */
export const MAX_BATCH = 20
/** A smaller batch leaves this long after its first event. */
export const FLUSH_DELAY_MS = 10_000

interface User {
    readonly id: string
    readonly organization: string | undefined
}

export function createAnalytics(options: AnalyticsOptions): Analytics {
    const now = options.now ?? ((): Date => new Date())
    const newId = options.newId ?? randomId
    const schedule =
        options.schedule ??
        ((task: () => void, delayMs: number): void => {
            setTimeout(task, delayMs)
        })
    const apiKey = options.apiKey.trim()
    const host = options.host.trim().replace(/\/+$/, "")
    const url = `${host === "" ? DEFAULT_HOST : host}/batch/`
    const available = apiKey !== ""
    let queue: AnalyticsEvent[] = []
    let scheduled = false
    let user: User | undefined
    // Read once from storage, then kept here: a capture does no storage round trip.
    let cachedChoice: "on" | "off" | undefined
    let anonymousId: string | undefined
    // Every operation runs after the previous one: an opt-out is never overtaken by a capture that started before it.
    let tail: Promise<unknown> = Promise.resolve()

    function serially<T>(task: () => Promise<T>, fallback: T): Promise<T> {
        const next = tail.then(task).catch((): T => fallback)
        tail = next
        return next
    }

    async function quietly(task: () => Promise<void>): Promise<void> {
        try {
            await task()
        } catch {
            // Storage failed (locked keychain, corrupted keystore): the in-memory state stands.
        }
    }

    async function readChoice(): Promise<"on" | "off"> {
        cachedChoice ??= (await options.storage.get(CHOICE_KEY)) === "on" ? "on" : "off"
        return cachedChoice
    }

    async function anonymous(): Promise<string> {
        if (anonymousId !== undefined) return anonymousId
        const stored = await options.storage.get(ANONYMOUS_ID_KEY).catch((): undefined => undefined)
        const id = stored ?? newId()
        anonymousId = id
        if (stored === undefined) await quietly(() => options.storage.set(ANONYMOUS_ID_KEY, id))
        return id
    }

    function send(batch: AnalyticsEvent[]): Promise<void> {
        if (batch.length === 0) return Promise.resolve()
        // Dropped on failure on purpose: the network or PostHog failed, and analytics are best effort.
        return options.post(url, JSON.stringify({api_key: apiKey, batch})).catch(() => undefined)
    }

    function takeQueue(): AnalyticsEvent[] {
        const batch = queue
        queue = []
        scheduled = false
        return batch
    }

    /** Queues an event of [by], the person signed in when it was captured. */
    async function record(
        by: User | undefined,
        event: string,
        properties: Readonly<Record<string, unknown>>,
    ): Promise<void> {
        if (!available || (await readChoice()) !== "on") return
        const groups = by?.organization === undefined ? {} : {$groups: {organization: by.organization}}
        queue.push({
            event,
            distinct_id: by?.id ?? (await anonymous()),
            timestamp: now().toISOString(),
            properties: {
                ...properties,
                ...groups,
                $lib: "ostrom-app",
                $app_version: options.appVersion,
                $os: options.platform,
                $process_person_profile: by !== undefined,
                // No city or country derived from the request's IP.
                $geoip_disable: true,
            },
        })
        if (queue.length >= MAX_BATCH) {
            // Not awaited: the caller never waits on the network.
            void send(takeQueue())
        } else if (!scheduled) {
            scheduled = true
            schedule(() => {
                void flush()
            }, FLUSH_DELAY_MS)
        }
    }

    function capture(event: string, properties: Readonly<Record<string, unknown>> = {}): Promise<void> {
        const by = user
        return serially(() => record(by, event, properties), undefined)
    }

    function flush(): Promise<void> {
        return serially(() => send(takeQueue()), undefined)
    }

    return {
        choice: () => (available ? serially(readChoice, "off") : Promise.resolve("unavailable")),
        enable() {
            const by = user
            return serially(async () => {
                if (!available || (await readChoice()) === "on") return
                cachedChoice = "on"
                await quietly(() => options.storage.set(CHOICE_KEY, "on"))
                await record(by, "consent_updated", {analytics: true})
            }, undefined)
        },
        disable: () =>
            serially(async () => {
                takeQueue()
                cachedChoice = "off"
                anonymousId = undefined
                await quietly(() => options.storage.set(CHOICE_KEY, "off"))
                await quietly(() => options.storage.remove(ANONYMOUS_ID_KEY))
            }, undefined),
        identify(userId, organizationId) {
            user = {id: userId, organization: organizationId}
        },
        reset() {
            // At once: an event captured from now on is never the signed-out person's. Events captured before keep
            // who captured them, leave with this batch, and the anonymous id is replaced after them.
            user = undefined
            return serially(async () => {
                await send(takeQueue())
                anonymousId = undefined
                await quietly(() => options.storage.remove(ANONYMOUS_ID_KEY))
            }, undefined)
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
