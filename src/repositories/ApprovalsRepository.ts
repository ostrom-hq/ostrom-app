import type {Ask, Decision, YesQueue} from "@/api/contract/yes"

/** The requests for a yes: what waits for me, one request with its evidence, and my decision. */
export interface ApprovalsRepository {
    queue(): Promise<YesQueue>
    ask(id: string): Promise<Ask>
    /** Decides, bound to the digest of what the screen showed. Refusals come back as `ApiError` `refused`. */
    decide(id: string, decision: Decision, shownDigest: string): Promise<Ask>
}
