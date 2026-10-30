import type {ApprovalAnswer, ApprovalQueue, ApprovalView} from "@/api/contract/approvals"

/** The requests for a yes: what waits for me and what I answered lately, one request as shown, and my answer. */
export interface ApprovalsRepository {
    /** The yes queue: the requests waiting for my answer, and the recent answers. */
    queue(): Promise<ApprovalQueue>
    /** One request with its evidence, and what I may do with it. */
    view(id: string): Promise<ApprovalView>
    /**
     * Answers, bound to the digest of what the screen showed (the server refuses with 409 if it changed), and gives
     * the request as it now stands. `note` carries the reason of a no, which the agent reads to know what to do instead.
     */
    answer(id: string, answer: ApprovalAnswer, shownDigest: string, note?: string): Promise<ApprovalView>
}
