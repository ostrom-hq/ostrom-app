// SPDX-License-Identifier: AGPL-3.0-only
import type {z} from "zod"
import {zForbidden, zSelfApprovalRefused, zStepUpOrForbidden, zStepUpRequired} from "@/api/gen/zod.gen"
import {HttpClientImpl} from "@/api/http/HttpClientImpl"
import {ApprovalsRepositoryImpl} from "@/repositories/ApprovalsRepositoryImpl"
import {type DecisionProblem, decisionProblem} from "./decisionProblem"

/** The 403 bodies of `POST /api/v1/approvals/{id}/answer` (core 1651cdc), each one read with the contract's schema. */
const stepUp = zStepUpRequired.parse({error: {type: "step_up_required", message: "Sign in again, then answer"}})
const selfApproval = zSelfApprovalRefused.parse({
    error: {type: "self_approval_refused", message: "You asked for this action"},
    others: ["rev-b"],
})
const forbidden = zForbidden.parse({error: {type: "forbidden", message: "Not an approver of this request"}})

/** What the app makes of a 403 the server answers to a yes, through the real HTTP client and repository. */
async function problemOfAnswer(body: unknown, askedByMe: boolean): Promise<DecisionProblem> {
    const fetch = (): Promise<Response> =>
        Promise.resolve(new Response(JSON.stringify(body), {status: 403, headers: {"content-type": "application/json"}}))
    const approvals = new ApprovalsRepositoryImpl(new HttpClientImpl({baseUrl: "https://ostrom.example", token: () => "ost_t", fetch}))
    try {
        await approvals.answer("appr_0001", "approve", "sha256:shown")
    } catch (error) {
        return decisionProblem(error, askedByMe)
    }
    throw new Error("the 403 was taken as an answer")
}

describe("a 403 to a yes, told apart by error.type as the contract says", () => {
    it.each<[string, z.infer<typeof zStepUpRequired> | z.infer<typeof zSelfApprovalRefused> | z.infer<typeof zForbidden>, DecisionProblem]>([
        ["step_up_required", stepUp, "step-up"],
        ["self_approval_refused", selfApproval, "self-approval"],
        ["forbidden", forbidden, "forbidden"],
    ])("%s reads the same whoever asked", async (_type, body, expected) => {
        await expect(problemOfAnswer(body, false)).resolves.toBe(expected)
        await expect(problemOfAnswer(body, true)).resolves.toBe(expected)
    })

    it("the other writes' 403 is one of two bodies, and only error.type says which", () => {
        expect(zStepUpOrForbidden.parse(stepUp)).toEqual(stepUp)
        expect(zStepUpOrForbidden.parse(forbidden)).toEqual(forbidden)
        expect(zStepUpOrForbidden.safeParse({error: {type: "self_approval_refused", message: ""}}).success).toBe(false)
    })
})
