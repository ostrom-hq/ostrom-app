// SPDX-License-Identifier: AGPL-3.0-only
import {act, fireEvent, screen, waitFor} from "@testing-library/react-native"
import type {Approval, ApprovalAnswer, ApprovalView} from "@/api/contract/approvals"
import {ApiError} from "@/api/http/ApiError"
import type {ApprovalsRepository} from "@/repositories/ApprovalsRepository"
import type {AppServices} from "@/services/AppServices"
import type {BiometricOutcome} from "@/services/biometrics/BiometricGate"
import type {UnlockOutcome} from "@/services/unlock/PhoneUnlock"
import {approvals, fakeRepositories, signedInServices, testSession, viewOf} from "@/test/fakeServices"
import {renderWithServices} from "@/test/renderWithServices"
import {RequestScreen} from "./RequestScreen"

interface Harness {
    readonly approval: jest.Mock<Promise<Approval>, [string]>
    readonly answer: jest.Mock<Promise<ApprovalView>, Parameters<ApprovalsRepository["answer"]>>
    readonly confirm: jest.Mock<Promise<BiometricOutcome>, [string]>
}

/** What the view adds to the request: who asks, the command, the gate, the note. */
type ViewExtras = Omit<ApprovalView, "approval" | "you">

/**
 * A server holding one request, which an answer changes, shown to `rev-b` with `you` and `extras`, and a phone whose
 * biometrics answer `outcome`.
 */
async function open(
    initial: Approval,
    {
        outcome = "confirmed",
        refuse,
        you = {},
        extras = {},
        openUrl,
        copyText,
    }: {
        outcome?: BiometricOutcome
        refuse?: ApiError
        you?: Partial<ApprovalView["you"]>
        extras?: ViewExtras
        openUrl?: (url: string) => Promise<void>
        copyText?: (text: string) => Promise<void>
    } = {},
): Promise<Harness> {
    let current = initial
    const approval = jest.fn((_id: string) => Promise.resolve(current))
    const show = (request: Approval): ApprovalView => ({...extras, ...viewOf(request, you)})
    const view = async (id: string): Promise<ApprovalView> => show(await approval(id))
    const answer = jest.fn((_id: string, given: ApprovalAnswer, _shownDigest: string, _note?: string): Promise<ApprovalView> => {
        if (refuse !== undefined) return Promise.reject(refuse)
        current = {...current, status: given === "approve" ? "approved" : "rejected", decidedBy: "rev-b"}
        return Promise.resolve(show(current))
    })
    const confirm = jest.fn((_prompt: string) => Promise.resolve(outcome))
    const services = await signedInServices({
        biometrics: {confirm},
        repositories: fakeRepositories({approvals: {queue: jest.fn(), view, answer}}),
        ...(openUrl === undefined ? {} : {openUrl}),
        ...(copyText === undefined ? {} : {copyText}),
    })
    await renderWithServices(<RequestScreen id={initial.id} />, services)
    await screen.findByRole("header", {name: initial.title})
    return {approval, answer, confirm}
}

const refused = (status: number, type: string): ApiError => new ApiError({kind: "refused", status, type, message: ""})
const bare: Approval = {id: "appr_bare", title: "Bare request", requestedFrom: "rev-b", channel: "app", status: "pending", shownDigest: "d0"}

describe("RequestScreen", () => {
    it("shows the evidence before any button: who asks, where, the exact commits and checks", async () => {
        await open(approvals.pending)
        for (const text of [
            "dev-a",
            "acme/acme-api",
            "c60f859",
            "feat: expose readiness",
            "b0ff918",
            "Passed",
            "Running",
            "rev-b",
            "#6",
            "gh pr merge 6 --squash",
            /Expires/,
        ]) {
            expect(screen.getAllByText(text).length).toBeGreaterThan(0)
        }
        expect(screen.getByText(/c60f859a1b2c3d4e5f60718293a4b5c6d7e8f901/)).toBeOnTheScreen()
        const scroll = screen.getByTestId("request-scroll")
        expect(scroll).toContainElement(screen.getByTestId("request-approve"))
    })

    it("approves after Face ID, bound to the digest the screen showed", async () => {
        const {confirm, answer} = await open(approvals.pending)
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        expect(await screen.findByText("Approved")).toBeOnTheScreen()
        expect(confirm).toHaveBeenCalledWith("Approve “Merge PR #6 into main”")
        expect(answer).toHaveBeenCalledWith("appr_0001", "approve", approvals.pending.shownDigest, undefined)
        expect(screen.getByText("Approved by rev-b")).toBeOnTheScreen()
        expect(screen.queryByRole("button", {name: "Approve"})).toBeNull()
    })

    it("declines with a reason the agent reads, after Face ID", async () => {
        const {confirm, answer} = await open(approvals.pending)
        await fireEvent.press(screen.getByRole("button", {name: "Decline…"}))
        expect(screen.getByRole("button", {name: "Decline"})).toBeDisabled()
        await fireEvent.changeText(screen.getByTestId("request-reason"), "Wait for the migration PR")
        await fireEvent.press(screen.getByRole("button", {name: "Decline"}))
        expect(await screen.findByText("Declined by rev-b")).toBeOnTheScreen()
        expect(confirm).toHaveBeenCalledWith("Decline “Merge PR #6 into main”")
        expect(answer).toHaveBeenCalledWith("appr_0001", "reject", approvals.pending.shownDigest, "Wait for the migration PR")
    })

    it("names what a low-risk yes lets through, shows the size of the change, and approves without Face ID", async () => {
        const merge: Approval = {
            ...approvals.pending,
            action: {kind: "merge", repo: "acme/acme-api", ref: "release/2", headSha: "c60f859", risk: "low"},
            diffStats: {files: 3, additions: 120, deletions: 4},
        }
        const {confirm, answer} = await open(merge)
        expect(screen.getByText("release/2")).toBeOnTheScreen()
        expect(screen.getByText("3 files · +120 −4")).toBeOnTheScreen()
        await fireEvent.press(screen.getByRole("button", {name: "Approve merge"}))
        expect(await screen.findByText("Approved by rev-b")).toBeOnTheScreen()
        expect(confirm).not.toHaveBeenCalled()
        expect(answer).toHaveBeenCalledWith("appr_0001", "approve", merge.shownDigest, undefined)
    })

    it.each([
        ["push", "Approve push"],
        ["deploy", "Approve deploy"],
        ["tool", "Approve"],
        ["unknown", "Approve"],
    ] as const)("names the yes of a %s action", async (kind, label) => {
        await open({
            ...approvals.pending,
            action: {kind, repo: "acme/acme-api", ref: "main", headSha: "c60f859", risk: "high"},
            diffStats: {files: 1, additions: 1, deletions: 0},
        })
        expect(screen.getByRole("button", {name: label})).toBeOnTheScreen()
        expect(screen.getByText("1 file · +1 −0")).toBeOnTheScreen()
    })

    it("takes no yes on a diff the phone does not show in full, and keeps the no", async () => {
        await open({...approvals.pending, diffTruncated: true})
        expect(screen.getByText("The whole change is not shown on the phone: approve it from the console.")).toBeOnTheScreen()
        expect(screen.queryByTestId("request-approve")).toBeNull()
        expect(screen.getByRole("button", {name: "Decline…"})).toBeEnabled()
    })

    it("shows as head the commit the yes lets through, and no ref the server left empty", async () => {
        await open({
            ...approvals.pending,
            sha: "1111111aaaa",
            action: {kind: "merge", repo: "acme/acme-api", ref: "", headSha: "9f1e2d3bbbb", risk: "high"},
        })
        expect(screen.getByText("9f1e2d3")).toBeOnTheScreen()
        expect(screen.queryByText("1111111")).toBeNull()
        expect(screen.queryByText("Ref")).toBeNull()
    })

    it("shows a request in a status this app does not know, without an outcome or a button", async () => {
        await open({...approvals.pending, status: "unknown", decidedBy: "rev-b"})
        expect(screen.getByText("Unknown")).toBeOnTheScreen()
        expect(screen.getByText("This app does not know this request's state: answer it from the console.")).toBeOnTheScreen()
        expect(screen.queryByText(/by rev-b/)).toBeNull()
        expect(screen.queryByRole("button", {name: "Decline…"})).toBeNull()
    })

    it("goes back from the reason to the two buttons", async () => {
        await open(approvals.pending)
        await fireEvent.press(screen.getByRole("button", {name: "Decline…"}))
        await fireEvent.press(screen.getByRole("button", {name: "Back"}))
        expect(screen.getByRole("button", {name: "Approve"})).toBeOnTheScreen()
    })

    it("sends nothing when the person cancels Face ID", async () => {
        const {confirm, answer} = await open(approvals.pending, {outcome: "cancelled"})
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        await waitFor(() => {
            expect(screen.getByRole("button", {name: "Decline…"})).toBeEnabled()
        })
        expect(confirm).toHaveBeenCalled()
        expect(answer).not.toHaveBeenCalled()
        expect(screen.queryByRole("alert")).toBeNull()
    })

    it("refuses to answer from a phone without biometrics or passcode", async () => {
        const {answer} = await open(approvals.pending, {outcome: "unavailable"})
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        expect(await screen.findByText("Set up Face ID, a fingerprint or a passcode on this phone to answer from it.")).toBeOnTheScreen()
        expect(answer).not.toHaveBeenCalled()
    })

    it("sends nothing when the server does not take this phone's unlock, and says to sign in again", async () => {
        const answer = jest.fn()
        const unlock = jest.fn(() => Promise.resolve<UnlockOutcome>("refused"))
        const services = await signedInServices({
            unlock: {keep: jest.fn(), unlock, forget: jest.fn()},
            repositories: fakeRepositories({approvals: {queue: jest.fn(), view: () => Promise.resolve(viewOf(approvals.pending)), answer}}),
        })
        await renderWithServices(<RequestScreen id="appr_0001" />, services)
        await fireEvent.press(await screen.findByRole("button", {name: "Approve"}))
        expect(await screen.findByText(/Ostrom did not take this phone's proof that it is you/)).toBeOnTheScreen()
        expect(unlock).toHaveBeenCalledWith(expect.stringContaining("Merge PR #6"), expect.anything(), {always: true})
        expect(answer).not.toHaveBeenCalled()
    })

    it("says the server could not be reached when the unlock fails offline, and gives the buttons back", async () => {
        const answer = jest.fn()
        const unlock = jest.fn(() => Promise.reject(new ApiError({kind: "network"})))
        const services = await signedInServices({
            unlock: {keep: jest.fn(), unlock, forget: jest.fn()},
            repositories: fakeRepositories({approvals: {queue: jest.fn(), view: () => Promise.resolve(viewOf(approvals.pending)), answer}}),
        })
        await renderWithServices(<RequestScreen id="appr_0001" />, services)
        await fireEvent.press(await screen.findByRole("button", {name: "Approve"}))
        expect(await screen.findByText("The server could not be reached. Nothing was sent: try again.")).toBeOnTheScreen()
        expect(screen.getByRole("button", {name: "Approve"})).toBeEnabled()
        expect(answer).not.toHaveBeenCalled()
    })

    it("says nothing of a failed unlock once the person has signed out", async () => {
        const later: {services?: AppServices} = {}
        const unlock = jest.fn(async () => {
            await later.services?.sessionStore.getState().signOut()
            throw new ApiError({kind: "network"})
        })
        const services = await signedInServices({
            unlock: {keep: jest.fn(), unlock, forget: jest.fn()},
            repositories: fakeRepositories({approvals: {queue: jest.fn(), view: () => Promise.resolve(viewOf(approvals.pending)), answer: jest.fn()}}),
        })
        later.services = services
        await renderWithServices(<RequestScreen id="appr_0001" />, services)
        await fireEvent.press(await screen.findByRole("button", {name: "Approve"}))
        // The buttons come back once the failure is read.
        await waitFor(() => {
            expect(screen.getByRole("button", {name: "Approve"})).toBeEnabled()
        })
        expect(screen.queryByText("The server could not be reached. Nothing was sent: try again.")).toBeNull()
        expect(services.sessionStore.getState().state).toEqual({status: "signed-out"})
    })

    it("still signs out on a 401 at the unlock after a choice of organization: the token is the same", async () => {
        const later: {services?: AppServices} = {}
        const unlock = jest.fn(async () => {
            await later.services?.sessionStore.getState().chooseOrg("org_globex")
            throw new ApiError({kind: "unauthorized"})
        })
        const services = await signedInServices({
            unlock: {keep: jest.fn(), unlock, forget: jest.fn()},
            repositories: fakeRepositories({approvals: {queue: jest.fn(), view: () => Promise.resolve(viewOf(approvals.pending)), answer: jest.fn()}}),
        })
        later.services = services
        await renderWithServices(<RequestScreen id="appr_0001" />, services)
        await fireEvent.press(await screen.findByRole("button", {name: "Approve"}))
        expect(await screen.findByText("Your session has expired. Sign in again.")).toBeOnTheScreen()
        expect(services.sessionStore.getState().state).toEqual({status: "signed-out"})
    })

    it("signs out when the server no longer takes the token at the unlock", async () => {
        const unlock = jest.fn(() => Promise.reject(new ApiError({kind: "unauthorized"})))
        const services = await signedInServices({
            unlock: {keep: jest.fn(), unlock, forget: jest.fn()},
            repositories: fakeRepositories({approvals: {queue: jest.fn(), view: () => Promise.resolve(viewOf(approvals.pending)), answer: jest.fn()}}),
        })
        await renderWithServices(<RequestScreen id="appr_0001" />, services)
        await fireEvent.press(await screen.findByRole("button", {name: "Approve"}))
        expect(await screen.findByText("Your session has expired. Sign in again.")).toBeOnTheScreen()
        expect(services.sessionStore.getState().state).toEqual({status: "signed-out"})
    })

    it("signs nothing out and says nothing on a late 401 of a session already left", async () => {
        const next = {...testSession, token: "ost_2"}
        const later: {services?: AppServices} = {}
        const unlock = jest.fn(async () => {
            // The person signed out and in again while the unlock was on its way.
            await later.services?.sessionStore.getState().signOut()
            await later.services?.sessionStore.getState().signIn(next)
            throw new ApiError({kind: "unauthorized"})
        })
        const services = await signedInServices({
            unlock: {keep: jest.fn(), unlock, forget: jest.fn()},
            repositories: fakeRepositories({approvals: {queue: jest.fn(), view: () => Promise.resolve(viewOf(approvals.pending)), answer: jest.fn()}}),
        })
        later.services = services
        await renderWithServices(<RequestScreen id="appr_0001" />, services)
        await fireEvent.press(await screen.findByRole("button", {name: "Approve"}))
        await waitFor(() => {
            expect(screen.getByRole("button", {name: "Approve"})).toBeEnabled()
        })
        expect(screen.queryByText("Your session has expired. Sign in again.")).toBeNull()
        expect(services.sessionStore.getState().state).toEqual({status: "signed-in", session: next})
    })

    it("reads the request again when it changed after the screen showed it", async () => {
        const {approval} = await open(approvals.pending, {refuse: refused(409, "shown_digest_mismatch")})
        const reads = approval.mock.calls.length
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        expect(await screen.findByText("The request changed after you opened it. Read it again before answering.")).toBeOnTheScreen()
        expect(approval.mock.calls.length).toBeGreaterThan(reads)
    })

    it("asks for a new reading when the request changed under the screen, then binds the answer to it", async () => {
        let current: Approval = approvals.pending
        const approval = jest.fn((_id: string) => Promise.resolve(current))
        const answer = jest.fn((_id: string, _given: ApprovalAnswer, shownDigest: string, _note?: string): Promise<ApprovalView> => {
            if (shownDigest !== current.shownDigest) return Promise.reject(refused(409, "shown_digest_mismatch"))
            current = {...current, status: "approved", decidedBy: "rev-b"}
            return Promise.resolve(viewOf(current))
        })
        const view = async (id: string): Promise<ApprovalView> => viewOf(await approval(id))
        const services = await signedInServices({
            biometrics: {confirm: () => Promise.resolve<BiometricOutcome>("confirmed")},
            repositories: fakeRepositories({approvals: {queue: jest.fn(), view, answer}}),
        })
        await renderWithServices(<RequestScreen id="appr_0001" />, services)
        await screen.findByRole("header", {name: "Merge PR #6 into main"})
        // An agent pushed one more commit after the screen showed the request.
        current = {...approvals.pending, commits: ["c0ffee0"], commitDetails: [], shownDigest: "d-after-push"}
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        expect(await screen.findByRole("button", {name: "I read it again"})).toBeOnTheScreen()
        expect(screen.getByText("c0ffee0")).toBeOnTheScreen()
        expect(screen.queryByRole("button", {name: "Approve"})).toBeNull()
        await fireEvent.press(screen.getByRole("button", {name: "I read it again"}))
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        expect(await screen.findByText("Approved by rev-b")).toBeOnTheScreen()
        expect(answer).toHaveBeenLastCalledWith("appr_0001", "approve", "d-after-push", undefined)
    })

    it("says who asks through which gate, and the command the yes lets run", async () => {
        await open(approvals.pending, {
            extras: {
                who: {agent: "claude-code", model: "claude-opus-5-5"},
                what: {command: "git push origin main"},
                gate: {workflowName: "Release", label: "Merge to main"},
            },
        })
        expect(screen.getByText("claude-code · claude-opus-5-5")).toBeOnTheScreen()
        expect(screen.getByText("Release · Merge to main")).toBeOnTheScreen()
        expect(screen.getByText("git push origin main")).toBeOnTheScreen()
    })

    it("names only the agent when the server gives no model, and leaves out an empty command", async () => {
        await open({...bare, askedBy: []}, {extras: {who: {agent: "codex"}, what: {command: ""}}})
        expect(screen.getByText("codex")).toBeOnTheScreen()
        expect(screen.queryByText("Command")).toBeNull()
        expect(screen.queryByText("People")).toBeNull()
    })

    it("hides Approve when the server does not take my yes, says so in my language, and keeps the no", async () => {
        await open(approvals.pending, {you: {canApprove: false, reason: "Only a maintainer of acme-api approves a deploy"}})
        expect(screen.getByText("Your yes is not taken on this request.")).toBeOnTheScreen()
        expect(screen.queryByText("Only a maintainer of acme-api approves a deploy")).toBeNull()
        expect(screen.queryByTestId("request-approve")).toBeNull()
        expect(screen.getByRole("button", {name: "Decline…"})).toBeEnabled()
    })

    it("shows what the approver told the agent, once answered", async () => {
        await open({...approvals.decided, status: "rejected"}, {extras: {note: "Wait for the migration PR"}})
        expect(screen.getByText("Note to the agent: Wait for the migration PR")).toBeOnTheScreen()
    })

    it("keeps a note off a request that still waits", async () => {
        await open(approvals.pending, {extras: {note: "draft"}})
        expect(screen.queryByText(/Note to the agent/)).toBeNull()
    })

    it("explains a yes that needs a fresh sign-in, then offers the console, without a raw error", async () => {
        const openUrl = jest.fn((_url: string) => Promise.resolve())
        await open(approvals.pending, {refuse: refused(403, "step_up_required"), openUrl})
        expect(screen.queryByRole("button", {name: "Open the console"})).toBeNull()
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        expect(await screen.findByText("Ostrom wants a fresh sign-in for this yes: confirm it in the console.")).toBeOnTheScreen()
        expect(openUrl).not.toHaveBeenCalled()
        await fireEvent.press(screen.getByRole("button", {name: "Open the console"}))
        expect(openUrl).toHaveBeenCalledWith("https://ostrom.test/yes/appr_0001")
        expect(screen.queryByText(/step_up_required/)).toBeNull()
    })

    it("shows the console's address to copy when the console cannot be opened", async () => {
        const openUrl = jest.fn((_url: string) => Promise.reject(new Error("no browser")))
        const copyText = jest.fn((_text: string) => Promise.resolve())
        await open(approvals.pending, {refuse: refused(403, "step_up_required"), openUrl, copyText})
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        await fireEvent.press(await screen.findByRole("button", {name: "Open the console"}))
        expect(await screen.findByText("https://ostrom.test/yes/appr_0001")).toBeOnTheScreen()
        expect(screen.getByTestId("request-console-url").props).toMatchObject({selectable: true})
        await fireEvent.press(screen.getByRole("button", {name: "Copy the address"}))
        expect(copyText).toHaveBeenCalledWith("https://ostrom.test/yes/appr_0001")
        expect(await screen.findByRole("button", {name: "Address copied"})).toBeOnTheScreen()
        // Answering again starts over: the address goes until the console fails to open again.
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        expect(await screen.findByRole("button", {name: "Open the console"})).toBeOnTheScreen()
        expect(screen.queryByTestId("request-console-url")).toBeNull()
    })

    it("drops the address once the console opens", async () => {
        const openUrl = jest
            .fn<Promise<void>, [string]>()
            .mockRejectedValueOnce(new Error("no browser"))
            .mockResolvedValue(undefined)
        await open(approvals.pending, {refuse: refused(403, "step_up_required"), openUrl})
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        await fireEvent.press(await screen.findByRole("button", {name: "Open the console"}))
        expect(await screen.findByTestId("request-console-url")).toBeOnTheScreen()
        await fireEvent.press(screen.getByRole("button", {name: "Open the console"}))
        await waitFor(() => {
            expect(screen.queryByTestId("request-console-url")).toBeNull()
        })
    })

    it("keeps the copy button when the clipboard refuses", async () => {
        const openUrl = jest.fn((_url: string) => Promise.reject(new Error("no browser")))
        const copyText = jest.fn((_text: string) => Promise.reject(new Error("no clipboard")))
        await open(approvals.pending, {refuse: refused(403, "step_up_required"), openUrl, copyText})
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        await fireEvent.press(await screen.findByRole("button", {name: "Open the console"}))
        await fireEvent.press(await screen.findByRole("button", {name: "Copy the address"}))
        expect(copyText).toHaveBeenCalled()
        expect(screen.getByRole("button", {name: "Copy the address"})).toBeOnTheScreen()
        expect(screen.queryByRole("button", {name: "Address copied"})).toBeNull()
    })

    it("says in clear that the request is mine, and why the server refused my yes", async () => {
        await open(approvals.self, {refuse: refused(403, "forbidden")})
        expect(screen.getByText("You asked for this. Ostrom takes your yes only if nobody else can give it.")).toBeOnTheScreen()
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        expect(await screen.findByText("Someone else can approve this request, so your own yes is not taken.")).toBeOnTheScreen()
    })

    it("shows who answered and when, without actions", async () => {
        await open({...approvals.decided, selfApproved: true})
        expect(screen.getByText(/^Approved by rev-b · .+ · self-approved$/)).toBeOnTheScreen()
        expect(screen.queryByRole("button", {name: "Approve"})).toBeNull()
    })

    it("dates a declined request when the server says when", async () => {
        await open({...approvals.decided, status: "rejected"})
        expect(screen.getByText(/^Declined by rev-b · /)).toBeOnTheScreen()
    })

    it("says an expired request expired, with its time when known", async () => {
        await open({...approvals.pending, status: "expired"})
        expect(screen.getByText(/^Expired at .+ without an answer$/)).toBeOnTheScreen()
        expect(screen.queryByRole("button", {name: "Approve"})).toBeNull()
    })

    it("colours every check state, and reads an unknown one as unknown", async () => {
        await open({
            ...approvals.pending,
            checks: [
                {name: "a", state: "success"},
                {name: "b", state: "failure"},
                {name: "c", state: "pending"},
                {name: "d", state: "skipped"},
                {name: "e", state: "unknown"},
                {name: "e", state: "failure"},
            ],
        })
        for (const label of ["Passed", "Running", "Skipped", "Unknown"]) {
            expect(screen.getByText(label)).toBeOnTheScreen()
        }
        expect(screen.getAllByText("e")).toHaveLength(2)
        expect(screen.getAllByText("Failed")).toHaveLength(2)
    })

    it("shows the commit SHAs when the server gives no titles", async () => {
        await open({...approvals.pending, commitDetails: [], commits: ["abcdef1234567"]})
        expect(screen.getByText("abcdef1")).toBeOnTheScreen()
    })

    it("leaves out what the server did not send", async () => {
        await open(bare)
        expect(screen.queryByText("Commits")).toBeNull()
        expect(screen.queryByText("Checks")).toBeNull()
        expect(screen.queryByText("Evidence")).toBeNull()
        expect(screen.queryByText("Asked by")).toBeNull()
        expect(screen.queryByText("Repository")).toBeNull()
        expect(screen.getByText(/^appr_bare\s+d0$/)).toBeOnTheScreen()
    })

    it("shows a commit without author, and an answer or expiry the server did not date", async () => {
        await open({...bare, status: "approved", decidedBy: "rev-b", commitDetails: [{sha: "1234567890", title: "fix"}]})
        expect(screen.getByText("Approved by rev-b")).toBeOnTheScreen()
        expect(screen.getByText("fix")).toBeOnTheScreen()
    })

    it("says an undated expiry, and nothing for an answer without its author", async () => {
        await open({...bare, status: "expired"})
        expect(screen.getByText("Expired without an answer")).toBeOnTheScreen()
    })

    it("says nothing about a decision whose author the server did not send", async () => {
        await open({...bare, status: "rejected"})
        expect(screen.queryByText(/Declined by/)).toBeNull()
    })

    it("offers to try again when the request cannot be loaded", async () => {
        const view = jest
            .fn<Promise<ApprovalView>, [string]>()
            .mockRejectedValueOnce(new ApiError({kind: "network"}))
            .mockResolvedValue(viewOf(approvals.pending))
        const services = await signedInServices({
            repositories: fakeRepositories({approvals: {queue: jest.fn(), view, answer: jest.fn()}}),
        })
        await renderWithServices(<RequestScreen id="appr_0001" />, services)
        expect(await screen.findByText("This request could not be loaded.")).toBeOnTheScreen()
        expect(screen.queryByRole("button", {name: "Open the console"})).toBeNull()
        await fireEvent.press(await screen.findByRole("button", {name: "Try again"}))
        expect(await screen.findByRole("header", {name: "Merge PR #6 into main"})).toBeOnTheScreen()
    })

    it("says clearly when the server wants the organization named to read, and offers the console and its address", async () => {
        const view = (): Promise<ApprovalView> => Promise.reject(refused(409, "org_required"))
        const openUrl = jest.fn((_url: string) => Promise.reject(new Error("no browser")))
        const services = await signedInServices({
            repositories: fakeRepositories({approvals: {queue: jest.fn(), view, answer: jest.fn()}}),
            openUrl,
        })
        await renderWithServices(<RequestScreen id="appr_0001" />, services)
        await fireEvent.press(await screen.findByRole("button", {name: "Open the console"}))
        expect(openUrl).toHaveBeenCalledWith("https://ostrom.test/yes/appr_0001")
        expect(await screen.findByText("https://ostrom.test/yes/appr_0001")).toBeOnTheScreen()
        // Trying again forgets the link of this attempt.
        await fireEvent.press(screen.getByRole("button", {name: "Try again"}))
        await waitFor(() => {
            expect(screen.queryByText("https://ostrom.test/yes/appr_0001")).toBeNull()
        })
        expect(await screen.findByText("You are a member of several organizations, and Ostrom needs to know which one this is for: choose it in Settings.")).toBeOnTheScreen()
    })

    it("drops the address of the error screen once the request loads by any refetch, not only Try again", async () => {
        const view = jest
            .fn<Promise<ApprovalView>, [string]>()
            .mockRejectedValueOnce(refused(409, "org_required"))
            .mockResolvedValue(viewOf(approvals.pending))
        const openUrl = jest.fn((_url: string) => Promise.reject(new Error("no browser")))
        const services = await signedInServices({
            repositories: fakeRepositories({approvals: {queue: jest.fn(), view, answer: jest.fn()}}),
            openUrl,
        })
        const client = await renderWithServices(<RequestScreen id="appr_0001" />, services)
        await fireEvent.press(await screen.findByRole("button", {name: "Open the console"}))
        expect(await screen.findByText("https://ostrom.test/yes/appr_0001")).toBeOnTheScreen()
        // A refetch on focus or reconnect, not the person's "Try again".
        await act(async () => {
            await client.invalidateQueries()
        })
        expect(await screen.findByRole("header", {name: "Merge PR #6 into main"})).toBeOnTheScreen()
        expect(screen.queryByText("https://ostrom.test/yes/appr_0001")).toBeNull()
    })

    it("says clearly when an answer needs the organization named, and offers the console", async () => {
        const openUrl = jest.fn((_url: string) => Promise.resolve())
        await open(approvals.pending, {refuse: refused(409, "org_required"), openUrl})
        await fireEvent.press(screen.getByRole("button", {name: "Approve"}))
        await fireEvent.press(await screen.findByRole("button", {name: "Open the console"}))
        expect(openUrl).toHaveBeenCalledWith("https://ostrom.test/yes/appr_0001")
        expect(
            await screen.findByText(
                "You are a member of several organizations, and Ostrom needs to know which one this answer is for: choose it in Settings, or answer in the console.",
            ),
        ).toBeOnTheScreen()
        expect(screen.getByRole("button", {name: "Choose the organization"})).toBeOnTheScreen()
    })
})
