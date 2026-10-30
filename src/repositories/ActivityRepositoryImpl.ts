import {type ActivityPage, ActivityPageSchema} from "@/api/contract/activity"
import type {HttpClient} from "@/api/http/HttpClient"
import type {ActivityRepository} from "./ActivityRepository"

export class ActivityRepositoryImpl implements ActivityRepository {
    constructor(private readonly http: HttpClient) {}

    page(cursor?: string): Promise<ActivityPage> {
        return this.http.send({
            method: "GET",
            path: "/api/activity",
            ...(cursor === undefined ? {} : {query: {before: cursor}}),
            response: ActivityPageSchema,
        })
    }
}
