// SPDX-License-Identifier: AGPL-3.0-only
import {ApiError, type ApiProblem} from "@/api/http/ApiError"
import {biometricProblem, type DecisionProblem, decisionProblem, unlockProblem} from "./decisionProblem"

const refused = (status: number, type: string): ApiProblem => ({kind: "refused", status, type, message: ""})

const CASES: readonly (readonly [ApiProblem, DecisionProblem])[] = [
    [refused(409, "shown_digest_mismatch"), "changed"],
    [refused(409, "conflict"), "already-decided"],
    [refused(409, "org_required"), "org-required"],
    [refused(403, "step_up_required"), "step-up"],
    [{...refused(403, "step_up_required"), requestBound: true}, "unlock-again"],
    [refused(403, "self_approval_refused"), "self-approval"],
    [refused(403, "forbidden"), "forbidden"],
    [refused(400, "invalid_request"), "failed"],
    [{kind: "network"}, "offline"],
    [{kind: "timeout"}, "offline"],
    [{kind: "unauthorized"}, "session-expired"],
    [{kind: "unexpected-status", status: 404}, "failed"],
    [{kind: "server", status: 500}, "failed"],
    [{kind: "invalid-response", detail: ""}, "failed"],
]

describe("decisionProblem", () => {
    it.each(CASES)("reads %o as %s", (problem, expected) => {
        expect(decisionProblem(new ApiError(problem))).toBe(expected)
    })

    it("reads an unknown error as a failure", () => {
        expect(decisionProblem(new Error("bug"))).toBe("failed")
    })

    it("maps biometric outcomes", () => {
        expect(biometricProblem("unavailable")).toBe("biometrics-unavailable")
        expect(biometricProblem("failed")).toBe("biometrics-failed")
        expect(biometricProblem("refused")).toBe("unlock-refused")
    })

    it.each<[unknown, DecisionProblem]>([
        [new ApiError({kind: "network"}), "unlock-offline"],
        [new ApiError({kind: "timeout"}), "unlock-offline"],
        [new ApiError({kind: "unauthorized"}), "session-expired"],
        [new ApiError({kind: "server", status: 502}), "failed"],
        [new ApiError({kind: "refused", status: 429, type: "rate_limited", message: ""}), "failed"],
        [new ApiError({kind: "unexpected-status", status: 404}), "failed"],
        [new ApiError({kind: "invalid-response", detail: "not json"}), "failed"],
        [new Error("keychain"), "failed"],
    ])("reads a failed unlock %p as %p: nothing was sent", (error, expected) => {
        expect(unlockProblem(error)).toBe(expected)
    })
})
