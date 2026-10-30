import {
    type AnswerRequest,
    type AnswerResult,
    AnswerResultSchema,
    type Approval,
    type ApprovalAnswer,
    type ApprovalPage,
    ApprovalPageSchema,
    ApprovalSchema,
} from "@/api/contract/approvals"
import type {HttpClient} from "@/api/http/HttpClient"
import {segment} from "@/api/http/HttpClientImpl"
import type {ApprovalsRepository} from "./ApprovalsRepository"

export class ApprovalsRepositoryImpl implements ApprovalsRepository {
    constructor(private readonly http: HttpClient) {}

    waiting(): Promise<ApprovalPage> {
        return this.http.send({
            method: "GET",
            path: "/api/v1/approvals",
            query: {mine: "true", status: "pending"},
            response: ApprovalPageSchema,
        })
    }

    approval(id: string): Promise<Approval> {
        return this.http.send({method: "GET", path: `/api/v1/approvals/${segment(id)}`, response: ApprovalSchema})
    }

    answer(id: string, answer: ApprovalAnswer, shownDigest: string, comment?: string): Promise<AnswerResult> {
        const body: AnswerRequest = comment === undefined ? {answer, shownDigest} : {answer, shownDigest, comment}
        return this.http.send({method: "POST", path: `/api/v1/approvals/${segment(id)}/answer`, body, response: AnswerResultSchema})
    }
}
