import {z} from "zod"
import {
    type Analytics,
    ANONYMOUS_ID_KEY,
    type AnalyticsEvent,
    type AnalyticsOptions,
    type AnalyticsStorage,
    CHOICE_KEY,
    createAnalytics,
    DEFAULT_HOST,
    MAX_BATCH,
} from "./analytics"

class MemoryStorage implements AnalyticsStorage {
    readonly values = new Map<string, string>()

    get(key: string): Promise<string | undefined> {
        return Promise.resolve(this.values.get(key))
    }

    set(key: string, value: string): Promise<void> {
        this.values.set(key, value)
        return Promise.resolve()
    }

    remove(key: string): Promise<void> {
        this.values.delete(key)
        return Promise.resolve()
    }
}

interface Sent {
    readonly url: string
    readonly apiKey: string
    readonly batch: AnalyticsEvent[]
}

const BodySchema = z.object({
    api_key: z.string(),
    batch: z.array(
        z.object({
            event: z.string(),
            distinct_id: z.string(),
            timestamp: z.string(),
            properties: z.record(z.string(), z.unknown()),
        }),
    ),
})

interface Setup {
    readonly analytics: Analytics
    readonly storage: MemoryStorage
    readonly sent: Sent[]
}

function setup(overrides: Partial<AnalyticsOptions> = {}): Setup {
    const storage = new MemoryStorage()
    const sent: Sent[] = []
    let ids = 0
    const analytics = createAnalytics({
        apiKey: "phc_test",
        host: `${DEFAULT_HOST}/`,
        storage,
        post: (url, body) => {
            const parsed = BodySchema.parse(JSON.parse(body))
            sent.push({url, apiKey: parsed.api_key, batch: parsed.batch})
            return Promise.resolve()
        },
        appVersion: "0.1.0",
        platform: "ios",
        now: () => new Date("2026-10-30T03:00:00Z"),
        newId: () => `anon-${String(++ids)}`,
        ...overrides,
    })
    return {analytics, storage, sent}
}

describe("analytics", () => {
    it("is off by default and stores and sends nothing", async () => {
        const {analytics, storage, sent} = setup()

        expect(await analytics.choice()).toBe("off")
        await analytics.screen("Inbox")
        await analytics.capture("gate_opened_in_client", {channel: "app"})
        await analytics.flush()

        expect(sent).toEqual([])
        expect(storage.values.size).toBe(0)
    })

    it("sends screens and events once turned on, with the consent first", async () => {
        const {analytics, storage, sent} = setup()

        await analytics.enable()
        await analytics.screen("Inbox")
        await analytics.flush()

        expect(await analytics.choice()).toBe("on")
        expect(storage.values.get(CHOICE_KEY)).toBe("on")
        const [batch] = sent
        expect(batch?.url).toBe("https://eu.i.posthog.com/batch/")
        expect(batch?.apiKey).toBe("phc_test")
        expect(batch?.batch.map((event) => event.event)).toEqual(["consent_updated", "$screen"])
        const screen = batch?.batch[1]
        expect(screen?.distinct_id).toBe("anon-1")
        expect(screen?.timestamp).toBe("2026-10-30T03:00:00.000Z")
        expect(screen?.properties).toEqual({
            $screen_name: "Inbox",
            $lib: "ostrom-app",
            $app_version: "0.1.0",
            $os: "ios",
            $process_person_profile: false,
        })
    })

    it("captures an event without properties", async () => {
        const {analytics, sent} = setup()
        await analytics.enable()
        await analytics.capture("app_opened")
        await analytics.flush()

        expect(sent[0]?.batch[1]?.event).toBe("app_opened")
    })

    it("uses the opaque user id and organization once signed in, and a new anonymous id after sign-out", async () => {
        const {analytics, sent} = setup()
        await analytics.enable()

        analytics.identify("user-1", "org-1")
        await analytics.capture("gate_opened_in_client", {channel: "app"})
        analytics.identify("user-2", undefined)
        await analytics.capture("gate_opened_in_client", {channel: "app"})
        await analytics.reset()
        await analytics.screen("Sign in")
        await analytics.flush()

        const [consent, signedIn, noOrganization] = sent[0]?.batch ?? []
        const [signedOut] = sent[1]?.batch ?? []
        expect(consent?.distinct_id).toBe("anon-1")
        expect(signedIn?.distinct_id).toBe("user-1")
        expect(signedIn?.properties["$groups"]).toEqual({organization: "org-1"})
        expect(signedIn?.properties["$process_person_profile"]).toBe(true)
        expect(signedIn?.properties["channel"]).toBe("app")
        expect(noOrganization?.properties["$groups"]).toBeUndefined()
        expect(signedOut?.distinct_id).toBe("anon-2")
    })

    it("turning off forgets the id and drops what was not sent", async () => {
        const {analytics, storage, sent} = setup()
        await analytics.enable()
        await analytics.screen("Inbox")

        await analytics.disable()
        await analytics.flush()
        await analytics.screen("Settings")
        await analytics.flush()

        expect(sent).toEqual([])
        expect(storage.values.get(CHOICE_KEY)).toBe("off")
        expect(storage.values.has(ANONYMOUS_ID_KEY)).toBe(false)
        expect(await analytics.choice()).toBe("off")
    })

    it("sends a full batch at once", async () => {
        const {analytics, sent} = setup()
        await analytics.enable()

        for (let index = 1; index < MAX_BATCH; index++) await analytics.screen(`Screen ${String(index)}`)

        expect(sent).toHaveLength(1)
        expect(sent[0]?.batch).toHaveLength(MAX_BATCH)
    })

    it("drops a batch the network refused", async () => {
        const {analytics} = setup({post: () => Promise.reject(new Error("offline"))})
        await analytics.enable()

        await expect(analytics.flush()).resolves.toBeUndefined()
    })

    it("without a key, analytics are unavailable and cannot be turned on", async () => {
        const {analytics, storage, sent} = setup({apiKey: " "})

        await analytics.enable()
        await analytics.screen("Inbox")
        await analytics.flush()

        expect(await analytics.choice()).toBe("unavailable")
        expect(storage.values.size).toBe(0)
        expect(sent).toEqual([])
    })

    it("defaults to the system clock and random ids", async () => {
        const storage = new MemoryStorage()
        const bodies: string[] = []
        const analytics = createAnalytics({
            apiKey: "phc",
            host: DEFAULT_HOST,
            storage,
            post: (_url, body) => {
                bodies.push(body)
                return Promise.resolve()
            },
            appVersion: "0.1.0",
            platform: "android",
        })
        await analytics.enable()
        await analytics.flush()

        expect(storage.values.get(ANONYMOUS_ID_KEY)).toMatch(/^[0-9a-f-]{36}$/)
        expect(bodies).toHaveLength(1)
    })
})
