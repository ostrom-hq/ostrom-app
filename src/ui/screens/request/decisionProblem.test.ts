// SPDX-License-Identifier: AGPL-3.0-only
import {ApiError, type ApiProblem} from "@/api/http/ApiError"
import {biometricProblem, type DecisionProblem, decisionProblem} from "./decisionProblem"

const refused = (status: number, type: string): ApiProblem => ({kind: "refused", status, type, message: ""})

const CASES: readonly (readonly [ApiProblem, boolean, DecisionProblem])[] = [
    [refused(409, "shown_digest_mismatch"), false, "changed"],
    [refused(409, "conflict"), false, "already-decided"],
    [refused(409, "org_required"), false, "org-required"],
    [refused(403, "step_up_required"), false, "step-up"],
    [refused(403, "step_up_required"), true, "step-up"],
    [refused(403, "self_approval_refused"), false, "self-approval"],
    [refused(403, "forbidden"), true, "self-approval"],
    [refused(403, "forbidden"), false, "forbidden"],
    [refused(400, "invalid_request"), false, "failed"],
    [{kind: "network"}, false, "offline"],
    [{kind: "timeout"}, false, "offline"],
    [{kind: "unauthorized"}, false, "session-expired"],
    [{kind: "unexpected-status", status: 404}, false, "failed"],
    [{kind: "server", status: 500}, false, "failed"],
    [{kind: "invalid-response", detail: ""}, false, "failed"],
]

describe("decisionProblem", () => {
    it.each(CASES)("reads %o (asked by me: %s) as %s", (problem, askedByMe, expected) => {
        expect(decisionProblem(new ApiError(problem), askedByMe)).toBe(expected)
    })

    it("reads an unknown error as a failure", () => {
        expect(decisionProblem(new Error("bug"), false)).toBe("failed")
    })

    it("maps biometric outcomes", () => {
        expect(biometricProblem("unavailable")).toBe("biometrics-unavailable")
        expect(biometricProblem("failed")).toBe("biometrics-failed")
    })
})
