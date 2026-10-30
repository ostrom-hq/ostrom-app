import {
    type AnswerRequest,
    type ApprovalAnswer,
    type ApprovalQueue,
    ApprovalQueueSchema,
    type ApprovalView,
    ApprovalViewSchema,
} from "@/api/contract/approvals"
import type {HttpClient} from "@/api/http/HttpClient"
import {segment} from "@/api/http/HttpClientImpl"
import type {ApprovalsRepository} from "./ApprovalsRepository"

export class ApprovalsRepositoryImpl implements ApprovalsRepository {
    constructor(private readonly http: HttpClient) {}

    queue(): Promise<ApprovalQueue> {
        return this.http.send({method: "GET", path: "/api/v1/approvals/queue", response: ApprovalQueueSchema})
    }

    view(id: string): Promise<ApprovalView> {
        return this.http.send({method: "GET", path: `/api/v1/approvals/${segment(id)}/view`, response: ApprovalViewSchema})
    }

    answer(id: string, answer: ApprovalAnswer, shownDigest: string, note?: string): Promise<ApprovalView> {
        const body: AnswerRequest = note === undefined ? {answer, shownDigest} : {answer, shownDigest, note}
        return this.http.send({method: "POST", path: `/api/v1/approvals/${segment(id)}/answer`, body, response: ApprovalViewSchema})
    }
}
