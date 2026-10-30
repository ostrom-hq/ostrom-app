// SPDX-License-Identifier: AGPL-3.0-only
import {useCallback, useState} from "react"
import {useTranslation} from "react-i18next"
import {type Approval, type ApprovalAnswer, type ApprovalView, needsBiometrics, phoneMayApprove} from "@/api/contract/approvals"
import {isOrgRequired} from "@/api/http/ApiError"
import {useAnswer, useApprovalView} from "@/query/approvals"
import {useRepositories, useServices} from "@/services/AppServices"
import {biometricProblem, type DecisionProblem, decisionProblem} from "./decisionProblem"

export type RequestScreenState =
    | {readonly kind: "loading"}
    /** `orgRequired`: the server wants the organization named, which the app cannot do yet. */
    | {readonly kind: "error"; readonly orgRequired: boolean; readonly retry: () => void}
    | {readonly kind: "ready"; readonly approval: Approval; readonly view: ApprovalView}

/**
 * The whole change is not on the phone, or the server does not take the person's yes. Its `reason` is free text in
 * the server's language: the screen says it in the person's language instead.
 */
export type NoApprove = {readonly kind: "truncated"} | {readonly kind: "refused"}

export interface RequestViewModel {
    readonly state: RequestScreenState
    /** The request waits for an answer: the buttons are shown, after the evidence. */
    readonly open: boolean
    /** The person asked for this themselves (the server says so): their yes is taken only as sole approver. */
    readonly askedByMe: boolean
    /**
     * The phone may take a yes: not when the whole change is not shown (decision of 30/10 02:55), nor when the server
     * says the person may not approve. The screen hides Approve and `approve` sends nothing; a no stays possible.
     */
    readonly mayApprove: boolean
    /** Why there is no Approve, said under the buttons. */
    readonly noApprove: NoApprove | undefined
    /** The answer being confirmed or sent, to show which button is busy. */
    readonly sending: ApprovalAnswer | undefined
    readonly problem: DecisionProblem | undefined
    /**
     * What the screen shows is no longer what the person first read (a refetch changed it in place): no answer goes
     * until they say they read it again.
     */
    readonly changed: boolean
    readonly reread: () => void
    /** A no carries its reason: the decline field is open. */
    readonly declining: boolean
    readonly reason: string
    readonly setReason: (reason: string) => void
    readonly startDecline: () => void
    readonly cancelDecline: () => void
    readonly approve: () => void
    /** Sends the no with its reason; nothing happens without one. */
    readonly decline: () => void
    /**
     * Opens the console on this request (its universal link), where a yes that needs a fresh sign-in, or the
     * organization the app cannot name, is dealt with; on the server of the session as it is now, nothing after a
     * sign-out.
     */
    readonly openConsole: () => void
    /**
     * The console's address, once opening it failed (no browser, a refused link): shown as selectable text, to copy.
     * `copied` once it is on the clipboard.
     */
    readonly consoleLink: {readonly url: string; readonly copied: boolean} | undefined
    readonly copyConsoleLink: () => void
}

/**
 * What the person read: the server's digest of the request, and what the view shows around it (who asks, the command,
 * the gate), which a refetch could change under the same digest.
 */
function readingOf(view: ApprovalView): string {
    return JSON.stringify([view.approval.shownDigest, view.who, view.what, view.gate])
}

/** Why the phone takes no yes on this request, if it takes none: the server's word first, then the change not shown. */
function noApproveOf(view: ApprovalView): NoApprove | undefined {
    if (!view.you.canApprove) return {kind: "refused"}
    return phoneMayApprove(view.approval) ? undefined : {kind: "truncated"}
}

/** The console's address once opening it failed, and the screen it failed on. */
interface ConsoleLinkState {
    readonly url: string
    readonly copied: boolean
    readonly from: "error" | "ready"
}

export function useRequestViewModel(id: string): RequestViewModel {
    const {t} = useTranslation()
    const {unlock, openUrl, copyText, sessionStore} = useServices()
    const {account} = useRepositories()
    // Tagged with the screen it was asked from: a link that failed on the error screen means nothing once the request
    // loads (a refetch on focus, a late rejection after "Try again"), and the other way round.
    const [consoleLink, setConsoleLink] = useState<ConsoleLinkState>()
    const query = useApprovalView(id)
    const {mutateAsync} = useAnswer(id)
    const [sending, setSending] = useState<ApprovalAnswer>()
    const [problem, setProblem] = useState<DecisionProblem>()
    const [declining, setDeclining] = useState(false)
    const [reason, setReason] = useState("")
    const view = query.data
    const approval = view?.approval
    // The digest of the request as the person first read it: the answer is bound to it, never to data that a
    // refetch swapped in under their eyes (the server then refuses, and the screen asks for a new reading).
    const reading = view === undefined ? undefined : readingOf(view)
    const [pinned, setPinned] = useState<string>()
    if (reading !== undefined && pinned === undefined) setPinned(reading)
    const changed = reading !== undefined && pinned !== undefined && reading !== pinned
    const askedByMe = view?.you.self === true
    const open = approval?.status === "pending"
    const noApprove = view === undefined ? undefined : noApproveOf(view)
    const mayApprove = view !== undefined && noApprove === undefined
    const {refetch} = query

    const send = useCallback(
        (answer: ApprovalAnswer, note?: string) => {
            if (approval === undefined || !open || pinned === undefined || changed) return
            if (answer === "approve" && !mayApprove) return
            setSending(answer)
            setProblem(undefined)
            setConsoleLink(undefined)
            void (async () => {
                // Biometrics unless the contract says the action is low risk (`needsBiometrics`), and then proven to
                // the server; a low-risk answer asks them only when the last unlock is too old for the server.
                const outcome = await unlock.unlock(
                    t(answer === "approve" ? "request.confirm.approve" : "request.confirm.decline", {title: approval.title}),
                    account,
                    {always: needsBiometrics(approval)},
                )
                if (outcome === "confirmed") {
                    try {
                        // Not changed: what is on screen is what the person read, digest included.
                        const {shownDigest} = approval
                        await mutateAsync(note === undefined ? {answer, shownDigest} : {answer, shownDigest, note})
                        setDeclining(false)
                    } catch (error) {
                        // The answer's own invalidation reads the request again (`useAnswer`).
                        setProblem(decisionProblem(error, askedByMe))
                    }
                } else if (outcome !== "cancelled") {
                    setProblem(biometricProblem(outcome))
                }
                setSending(undefined)
            })()
        },
        [approval, open, mayApprove, pinned, changed, unlock, account, t, mutateAsync, askedByMe],
    )

    const state: RequestScreenState =
        view !== undefined
            ? {kind: "ready", approval: view.approval, view}
            : query.isError
              ? {
                    kind: "error",
                    orgRequired: isOrgRequired(query.error),
                    retry: () => {
                        // A console link that did not open belongs to this attempt, not to the request read next.
                        setConsoleLink(undefined)
                        void refetch()
                    },
                }
              : {kind: "loading"}
    const shownLink = consoleLink?.from === state.kind ? consoleLink : undefined
    return {
        state,
        open,
        askedByMe,
        mayApprove,
        noApprove,
        sending,
        problem: changed ? "changed" : problem,
        changed,
        reread: () => {
            if (reading !== undefined) setPinned(reading)
            setProblem(undefined)
            setConsoleLink(undefined)
        },
        declining,
        reason,
        setReason,
        startDecline: () => {
            setDeclining(true)
        },
        cancelDecline: () => {
            setDeclining(false)
        },
        approve: () => {
            send("approve")
        },
        openConsole: () => {
            const session = sessionStore.getState().state
            if (session.status !== "signed-in") return
            const url = `${session.session.serverUrl}/yes/${encodeURIComponent(id)}`
            const from = view === undefined ? "error" : "ready"
            void openUrl(url).then(
                () => {
                    setConsoleLink(undefined)
                },
                () => {
                    setConsoleLink({url, copied: false, from})
                },
            )
        },
        consoleLink: shownLink,
        copyConsoleLink: () => {
            if (shownLink === undefined) return
            const {url, from} = shownLink
            void copyText(url).then(
                () => {
                    setConsoleLink({url, copied: true, from})
                },
                () => {
                    setConsoleLink({url, copied: false, from})
                },
            )
        },
        decline: () => {
            const note = reason.trim()
            if (note !== "") send("reject", note)
        },
    }
}
