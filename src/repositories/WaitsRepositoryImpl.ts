// SPDX-License-Identifier: AGPL-3.0-only
import {type Wait, WAIT_FILTER_STATUS, type WaitFilter, WaitSchema, type WaitsPage, WaitsPageSchema, type WaitValues} from "@/api/contract/waits"
import type {HttpClient} from "@/api/http/HttpClient"
import {segment} from "@/api/http/HttpClientImpl"
import type {WaitsRepository} from "./WaitsRepository"

export class WaitsRepositoryImpl implements WaitsRepository {
    constructor(private readonly http: HttpClient) {}

    list(filter: WaitFilter, {cursor, limit}: {readonly cursor?: string | undefined; readonly limit?: number | undefined} = {}): Promise<WaitsPage> {
        return this.http.send({
            method: "GET",
            path: "/api/v1/waits",
            query: {
                mine: "true",
                status: WAIT_FILTER_STATUS[filter],
                ...(cursor === undefined ? {} : {before: cursor}),
                ...(limit === undefined ? {} : {limit: String(limit)}),
            },
            response: WaitsPageSchema,
        })
    }

    answer(id: string, action: string, values: WaitValues): Promise<Wait> {
        return this.http.send({method: "POST", path: `/api/v1/waits/${segment(id)}/answer`, body: {action, values}, response: WaitSchema})
    }
}
