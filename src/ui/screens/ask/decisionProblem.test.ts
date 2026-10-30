import {ApiError, type ApiProblem} from "@/api/http/ApiError"
import {biometricProblem, type DecisionProblem, decisionProblem, shouldReload} from "./decisionProblem"

const refused = (status: number, type: string): ApiProblem => ({kind: "refused", status, type, message: ""})

const CASES: readonly (readonly [ApiProblem, DecisionProblem])[] = [
    [refused(403, "self_approval_refused"), "self-approval"],
    [refused(409, "shown_digest_mismatch"), "changed"],
    [refused(409, "already_decided"), "already-decided"],
    [refused(403, "not_an_approver"), "forbidden"],
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
    })

    it("reads the request again when the server may hold something else", () => {
        expect(shouldReload("changed")).toBe(true)
        expect(shouldReload("offline")).toBe(true)
        expect(shouldReload("already-decided")).toBe(true)
        expect(shouldReload("self-approval")).toBe(false)
    })
})
