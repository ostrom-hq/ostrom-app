import type {AnswerResult, Approval, ApprovalAnswer, ApprovalPage} from "@/api/contract/approvals"

/** The requests for a yes: what waits for me, one request with its evidence, and my answer. */
export interface ApprovalsRepository {
    /** The requests waiting for my answer. */
    waiting(): Promise<ApprovalPage>
    approval(id: string): Promise<Approval>
    /**
     * Answers, bound to the digest of what the screen showed (the server refuses with 409 if it changed).
     * `note` carries the reason of a no, which the agent reads to know what to do instead.
     */
    answer(id: string, answer: ApprovalAnswer, shownDigest: string, note?: string): Promise<AnswerResult>
}
