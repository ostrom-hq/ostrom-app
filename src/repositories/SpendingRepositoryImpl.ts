import {type SpendPeriod, type SpendReport, SpendReportSchema} from "@/api/contract/spending"
import type {HttpClient} from "@/api/http/HttpClient"
import type {SpendingRepository} from "./SpendingRepository"

export class SpendingRepositoryImpl implements SpendingRepository {
    constructor(private readonly http: HttpClient) {}

    report(period: SpendPeriod): Promise<SpendReport> {
        return this.http.send({method: "GET", path: "/api/spending", query: {period}, response: SpendReportSchema})
    }
}
