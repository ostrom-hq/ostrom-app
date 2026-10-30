import {AskSchema, type Ask, type DecideRequest, type Decision, type YesQueue, YesQueueSchema} from "@/api/contract/yes"
import type {HttpClient} from "@/api/http/HttpClient"
import {segment} from "@/api/http/HttpClientImpl"
import type {ApprovalsRepository} from "./ApprovalsRepository"

export class ApprovalsRepositoryImpl implements ApprovalsRepository {
    constructor(private readonly http: HttpClient) {}

    queue(): Promise<YesQueue> {
        return this.http.send({method: "GET", path: "/api/yes/queue", response: YesQueueSchema})
    }

    ask(id: string): Promise<Ask> {
        return this.http.send({method: "GET", path: `/api/yes/${segment(id)}`, response: AskSchema})
    }

    decide(id: string, decision: Decision, shownDigest: string): Promise<Ask> {
        const body: DecideRequest = {result: decision, shownDigest}
        return this.http.send({method: "POST", path: `/api/yes/${segment(id)}/decide`, body, response: AskSchema})
    }
}
