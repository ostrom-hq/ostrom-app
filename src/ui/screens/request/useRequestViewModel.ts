// SPDX-License-Identifier: AGPL-3.0-only
import {useCallback, useState} from "react"
import {useTranslation} from "react-i18next"
import type {Approval, ApprovalAnswer} from "@/api/contract/approvals"
import {useAnswer, useApproval} from "@/query/approvals"
import {useServices, useSessionState} from "@/services/AppServices"
import {biometricProblem, type DecisionProblem, decisionProblem} from "./decisionProblem"

export type RequestScreenState =
    | {readonly kind: "loading"}
    | {readonly kind: "error"; readonly retry: () => void}
    | {readonly kind: "ready"; readonly approval: Approval}

export interface RequestViewModel {
    readonly state: RequestScreenState
    /** The request waits for an answer: the buttons are shown, after the evidence. */
    readonly open: boolean
    /** The person asked for this themselves: said in clear, the server takes their yes only as sole approver. */
    readonly askedByMe: boolean
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
}

export function useRequestViewModel(id: string): RequestViewModel {
    const {t} = useTranslation()
    const {biometrics} = useServices()
    const session = useSessionState()
    const query = useApproval(id)
    const {mutateAsync} = useAnswer(id)
    const [sending, setSending] = useState<ApprovalAnswer>()
    const [problem, setProblem] = useState<DecisionProblem>()
    const [declining, setDeclining] = useState(false)
    const [reason, setReason] = useState("")
    const approval = query.data
    // The digest of the request as the person first read it: the answer is bound to it, never to data that a
    // refetch swapped in under their eyes (the server then refuses, and the screen asks for a new reading).
    const [pinned, setPinned] = useState<string>()
    if (approval !== undefined && pinned === undefined) setPinned(approval.shownDigest)
    const changed = approval !== undefined && pinned !== undefined && approval.shownDigest !== pinned
    const login = session.status === "signed-in" ? session.session.user.login : undefined
    const askedByMe = login !== undefined && approval?.askedBy?.includes(login) === true
    const open = approval?.status === "pending"
    const {refetch} = query

    const send = useCallback(
        (answer: ApprovalAnswer, comment?: string) => {
            if (approval === undefined || !open || pinned === undefined || changed) return
            setSending(answer)
            setProblem(undefined)
            void (async () => {
                const prompt = t(answer === "approve" ? "request.confirm.approve" : "request.confirm.decline", {title: approval.title})
                const outcome = await biometrics.confirm(prompt)
                if (outcome === "confirmed") {
                    try {
                        const shownDigest = pinned
                        await mutateAsync(comment === undefined ? {answer, shownDigest} : {answer, shownDigest, comment})
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
        [approval, open, pinned, changed, biometrics, t, mutateAsync, askedByMe],
    )

    const state: RequestScreenState =
        approval !== undefined
            ? {kind: "ready", approval}
            : query.isError
              ? {
                    kind: "error",
                    retry: () => {
                        void refetch()
                    },
                }
              : {kind: "loading"}
    return {
        state,
        open,
        askedByMe,
        sending,
        problem: changed ? "changed" : problem,
        changed,
        reread: () => {
            if (approval !== undefined) setPinned(approval.shownDigest)
            setProblem(undefined)
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
        decline: () => {
            const comment = reason.trim()
            if (comment !== "") send("reject", comment)
        },
    }
}
