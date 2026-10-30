import {fireEvent, screen, waitFor} from "@testing-library/react-native"
import type {Ask, Decision} from "@/api/contract/yes"
import {ApiError} from "@/api/http/ApiError"
import type {BiometricOutcome} from "@/services/biometrics/BiometricGate"
import {asks, fakeRepositories, signedInServices} from "@/test/fakeServices"
import {renderWithServices} from "@/test/renderWithServices"
import {AskScreen} from "./AskScreen"

interface Harness {
    readonly ask: jest.Mock<Promise<Ask>, [string]>
    readonly decide: jest.Mock<Promise<Ask>, [string, Decision, string]>
    readonly confirm: jest.Mock<Promise<BiometricOutcome>, [string]>
}

/** A server holding one request, which a decision changes, and a phone whose biometrics answer `outcome`. */
async function open(
    initial: Ask,
    {outcome = "confirmed", refuse}: {outcome?: BiometricOutcome; refuse?: ApiError} = {},
): Promise<Harness> {
    let current = initial
    const ask = jest.fn((_id: string) => Promise.resolve(current))
    const decide = jest.fn((_id: string, decision: Decision, _shownDigest: string): Promise<Ask> => {
        if (refuse !== undefined) return Promise.reject(refuse)
        current = {...current, status: decision, decidedBy: "rev-b", you: {login: "rev-b", self: false, canApprove: false}}
        return Promise.resolve(current)
    })
    const confirm = jest.fn((_prompt: string) => Promise.resolve(outcome))
    const services = await signedInServices({
        biometrics: {confirm},
        repositories: fakeRepositories({approvals: {queue: jest.fn(), ask, decide}}),
    })
    await renderWithServices(<AskScreen id={initial.id} />, services)
    await screen.findByRole("header", {name: initial.title})
    return {ask, decide, confirm}
}

const refused = (status: number, type: string): ApiError => new ApiError({kind: "refused", status, type, message: ""})

describe("AskScreen", () => {
    it("shows the evidence: who asks, what it does, the exact commits and checks", async () => {
        await open(asks.pending)
        for (const text of [
            "dev-a",
            "claude-code",
            "sonnet",
            "Merge to main",
            "gh pr merge 6 --squash",
            "#6 · c60f859",
            "c60f859",
            "feat: expose readiness",
            "b0ff918",
            "Passed",
            "Running",
            "Production merge",
            "rev-b",
            "Commits follow Conventional Commits",
            "#6",
        ]) {
            expect(screen.getAllByText(text).length).toBeGreaterThan(0)
        }
        expect(screen.getByText(/export const ready = true/)).toBeOnTheScreen()
    })

    it("approves after Face ID, bound to the digest the screen showed", async () => {
        const {confirm, decide} = await open(asks.pending)
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        expect(await screen.findByText("Approved")).toBeOnTheScreen()
        expect(confirm).toHaveBeenCalledWith("Approve “Merge PR #6 into main”")
        expect(decide).toHaveBeenCalledWith("appr_0001", "approved", asks.pending.shownDigest)
        expect(screen.getByText("Decided by rev-b")).toBeOnTheScreen()
        expect(screen.queryByRole("button", {name: "Approve"})).toBeNull()
    })

    it("rejects after Face ID", async () => {
        const {confirm, decide} = await open(asks.pending)
        await fireEvent.press(screen.getByRole("button", {name: "Reject"}))
        expect(await screen.findByText("Rejected")).toBeOnTheScreen()
        expect(confirm).toHaveBeenCalledWith("Reject “Merge PR #6 into main”")
        expect(decide).toHaveBeenCalledWith("appr_0001", "rejected", asks.pending.shownDigest)
    })

    it("sends nothing when the person cancels Face ID", async () => {
        const {confirm, decide} = await open(asks.pending, {outcome: "cancelled"})
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        await waitFor(() => {
            expect(screen.getByRole("button", {name: "Approve"})).toBeEnabled()
        })
        expect(confirm).toHaveBeenCalled()
        expect(decide).not.toHaveBeenCalled()
        expect(screen.queryByRole("alert")).toBeNull()
    })

    it("refuses to decide from a phone without biometrics or passcode", async () => {
        const {decide} = await open(asks.pending, {outcome: "unavailable"})
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        expect(
            await screen.findByText("Set up Face ID, a fingerprint or a passcode on this phone to decide from it."),
        ).toBeOnTheScreen()
        expect(decide).not.toHaveBeenCalled()
    })

    it("reads the request again when it changed after the screen showed it", async () => {
        const {ask} = await open(asks.pending, {refuse: refused(409, "shown_digest_mismatch")})
        const reads = ask.mock.calls.length
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        expect(
            await screen.findByText("The request changed after you opened it. Read it again before deciding."),
        ).toBeOnTheScreen()
        expect(ask.mock.calls.length).toBeGreaterThan(reads)
    })

    it("says the server refused a self-approval", async () => {
        await open(asks.pending, {refuse: refused(403, "self_approval_refused")})
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        expect(await screen.findByText("You cannot approve your own request.")).toBeOnTheScreen()
    })

    it("never offers my own request to me", async () => {
        await open(asks.self)
        expect(screen.getByText("You asked for this: someone else has to say yes.")).toBeOnTheScreen()
        expect(screen.getByRole("button", {name: "Approve"})).toBeDisabled()
        expect(screen.getByRole("button", {name: "Reject"})).toBeDisabled()
    })

    it("says when someone else's request is not mine to decide", async () => {
        await open({...asks.pending, you: {login: "rev-c", self: false, canApprove: false}})
        expect(screen.getByText("You cannot decide this request.")).toBeOnTheScreen()
    })

    it("shows a decided request with its receipt, without actions", async () => {
        await open(asks.decided)
        expect(screen.getByText("rcpt_gate")).toBeOnTheScreen()
        expect(screen.getByText("Signature checked by the server")).toBeOnTheScreen()
        expect(screen.queryByRole("button", {name: "Approve"})).toBeNull()
    })

    it("warns about a receipt the server could not check", async () => {
        const {receipt} = asks.decided
        if (receipt === undefined || receipt === null) throw new Error("fixture without receipt")
        await open({...asks.decided, receipt: {...receipt, valid: false}})
        expect(screen.getByText("The server could not check this signature")).toBeOnTheScreen()
    })

    it("colours every check state, and reads an unknown one as unknown", async () => {
        await open({
            ...asks.pending,
            checks: [
                {name: "a", state: "success"},
                {name: "b", state: "failure"},
                {name: "c", state: "pending"},
                {name: "d", state: "skipped"},
                {name: "e", state: "unknown"},
            ],
        })
        for (const label of ["Passed", "Failed", "Running", "Skipped", "Unknown"]) {
            expect(screen.getByText(label)).toBeOnTheScreen()
        }
    })

    it("leaves out what the server did not send", async () => {
        await open({
            ...asks.pending,
            who: {},
            what: {sessionRepo: "acme/tools"},
            gate: {label: "Open gate", approvers: []},
            rules: [],
            evidence: [],
            commits: [],
            checks: [],
            diff: null,
            pr: null,
        })
        expect(screen.getByText("acme/tools")).toBeOnTheScreen()
        expect(screen.queryByText("Commits")).toBeNull()
        expect(screen.queryByText("Approvers")).toBeNull()
        expect(screen.queryByText("Changes")).toBeNull()
    })

    it("leaves out the gate when there is none", async () => {
        await open({...asks.pending, gate: null})
        expect(screen.queryByText("Production merge")).toBeNull()
    })

    it("offers to try again when the request cannot be loaded", async () => {
        const ask = jest
            .fn<Promise<Ask>, [string]>()
            .mockRejectedValueOnce(new ApiError({kind: "network"}))
            .mockResolvedValue(asks.pending)
        const services = await signedInServices({
            repositories: fakeRepositories({approvals: {queue: jest.fn(), ask, decide: jest.fn()}}),
        })
        await renderWithServices(<AskScreen id="appr_0001" />, services)
        await fireEvent.press(await screen.findByRole("button", {name: "Try again"}))
        expect(await screen.findByRole("header", {name: "Merge PR #6 into main"})).toBeOnTheScreen()
    })
})
