// SPDX-License-Identifier: AGPL-3.0-only
import {useCallback, useState} from "react"
import {useTranslation} from "react-i18next"
import type {Ask, Decision} from "@/api/contract/yes"
import {useAsk, useDecide} from "@/query/approvals"
import {useServices} from "@/services/AppServices"
import {biometricProblem, type DecisionProblem, decisionProblem, shouldReload} from "./decisionProblem"

export type AskScreenState =
    | {readonly kind: "loading"}
    | {readonly kind: "error"; readonly retry: () => void}
    | {readonly kind: "ready"; readonly ask: Ask}

export interface AskViewModel {
    readonly state: AskScreenState
    /** True only for a pending request this person may decide (never their own). */
    readonly canDecide: boolean
    /** The decision being confirmed or sent, to show which button is busy. */
    readonly pending: Decision | undefined
    readonly problem: DecisionProblem | undefined
    readonly decide: (decision: Decision) => void
}

export function useAskViewModel(id: string): AskViewModel {
    const {t} = useTranslation()
    const {biometrics} = useServices()
    const query = useAsk(id)
    const {mutateAsync} = useDecide(id)
    const [pending, setPending] = useState<Decision>()
    const [problem, setProblem] = useState<DecisionProblem>()
    const ask = query.data
    const canDecide = ask?.status === "pending" && ask.you?.canApprove === true
    const {refetch} = query

    const decide = useCallback(
        (decision: Decision) => {
            if (ask === undefined || !canDecide) return
            setPending(decision)
            setProblem(undefined)
            void (async () => {
                const outcome = await biometrics.confirm(t(decision === "approved" ? "ask.confirm.approve" : "ask.confirm.reject", {title: ask.title}))
                if (outcome === "confirmed") {
                    try {
                        await mutateAsync({decision, shownDigest: ask.shownDigest})
                    } catch (error) {
                        const found = decisionProblem(error)
                        setProblem(found)
                        if (shouldReload(found)) await refetch()
                    }
                } else if (outcome !== "cancelled") {
                    setProblem(biometricProblem(outcome))
                }
                setPending(undefined)
            })()
        },
        [ask, canDecide, biometrics, t, mutateAsync, refetch],
    )

    const state: AskScreenState =
        ask !== undefined
            ? {kind: "ready", ask}
            : query.isError
              ? {
                    kind: "error",
                    retry: () => {
                        void refetch()
                    },
                }
              : {kind: "loading"}
    return {state, canDecide, pending, problem, decide}
}
