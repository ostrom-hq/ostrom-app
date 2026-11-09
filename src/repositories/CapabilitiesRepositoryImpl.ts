// SPDX-License-Identifier: AGPL-3.0-only
import {type Capabilities, CapabilitiesSchema} from "@/api/contract/capabilities"
import type {HttpClient} from "@/api/http/HttpClient"
import type {CapabilitiesRepository} from "./CapabilitiesRepository"

export class CapabilitiesRepositoryImpl implements CapabilitiesRepository {
    constructor(private readonly http: HttpClient) {}

    read(): Promise<Capabilities> {
        return this.http.send({method: "GET", path: "/api/v1/capabilities", response: CapabilitiesSchema})
    }
}
