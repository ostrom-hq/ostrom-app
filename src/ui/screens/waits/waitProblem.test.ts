// SPDX-License-Identifier: AGPL-3.0-only
import {ApiError, type ApiProblem} from "@/api/http/ApiError"
import {answerProblem, unlockErrorProblem, unlockOutcomeProblem} from "./waitProblem"

const api = (problem: ApiProblem): ApiError => new ApiError(problem)
const refused = (status: number, type = "x"): ApiError => api({kind: "refused", status, type, message: "m"})

describe("unlockOutcomeProblem", () => {
    it.each([
        ["unavailable", "biometrics-unavailable"],
        ["refused", "unlock-refused"],
        ["failed", "biometrics-failed"],
    ] as const)("tells %s as %s", (outcome, problem) => {
        expect(unlockOutcomeProblem(outcome)).toBe(problem)
    })
})

describe("unlockErrorProblem", () => {
    it.each([
        [new Error("keychain"), "failed"],
        [api({kind: "network"}), "unlock-offline"],
        [api({kind: "timeout"}), "unlock-offline"],
        [api({kind: "unauthorized"}), "session-expired"],
        [refused(500), "failed"],
        [api({kind: "unexpected-status", status: 418}), "failed"],
        [api({kind: "server", status: 502}), "failed"],
        [api({kind: "invalid-response", detail: "d"}), "failed"],
    ])("tells %p as %s", (error, problem) => {
        expect(unlockErrorProblem(error)).toBe(problem)
    })
})

describe("answerProblem", () => {
    it.each([
        [new Error("boom"), "failed"],
        [refused(409, "org_required"), "org-required"],
        [refused(409, "wait_closed"), "closed"],
        [refused(403, "forbidden"), "forbidden"],
        [refused(400, "invalid_answer"), "invalid"],
        [refused(422), "failed"],
        [api({kind: "network"}), "offline"],
        [api({kind: "timeout"}), "offline"],
        [api({kind: "unauthorized"}), "session-expired"],
        [api({kind: "unexpected-status", status: 418}), "failed"],
        [api({kind: "server", status: 502}), "failed"],
        [api({kind: "invalid-response", detail: "d"}), "failed"],
    ])("tells %p as %s", (error, problem) => {
        expect(answerProblem(error)).toBe(problem)
    })
})
