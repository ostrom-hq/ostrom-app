// SPDX-License-Identifier: AGPL-3.0-only
import {createMockServer} from "./mockServer"

describe("createMockServer", () => {
    it("answers 404 on an id that is not valid percent-encoding, instead of throwing", () => {
        const answer = createMockServer().handle({method: "GET", path: "/api/v1/approvals/%E0%A4%A/view", query: {}, body: undefined})
        expect(answer).toEqual({status: 404, body: {error: {type: "not_found", message: "No such request"}}})
    })

    it("decodes an id before looking it up", () => {
        const answer = createMockServer().handle({method: "GET", path: "/api/v1/approvals/appr%5F0001/view", query: {}, body: undefined})
        expect(answer.status).toBe(200)
    })
})
