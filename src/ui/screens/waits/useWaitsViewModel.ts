// SPDX-License-Identifier: AGPL-3.0-only
import {useCallback, useRef, useState} from "react"
import {useTranslation} from "react-i18next"
import type {Wait, WaitAction, WaitField, WaitFilter} from "@/api/contract/waits"
import {ApiError, isOrgRequired} from "@/api/http/ApiError"
import {actionsOf, blockedFields, checkAnswer, type FieldDraft, type FieldError, fieldDraft, fieldKind, type FieldKind, initialDraft, type WaitDraft} from "@/domain/waits/waitAnswer"
import {waitTexts} from "@/domain/waits/serverWaits"
import {isSupportedLanguage, type SupportedLanguage} from "@/i18n/types"
import {useAnswerWait, useOpenWaits, useWaits} from "@/query/waits"
import {useRepositories, useServices} from "@/services/AppServices"
import type {UnlockOutcome} from "@/services/unlock/PhoneUnlock"
import {answerProblem, unlockErrorProblem, unlockOutcomeProblem, type WaitProblem} from "./waitProblem"

/** How a button looks: the action's `style` (an unknown or missing one reads as secondary). */
export type ActionStyle = "primary" | "secondary" | "danger"

export interface FieldModel {
    readonly field: WaitField
    readonly kind: FieldKind
    readonly draft: FieldDraft
    readonly error: FieldError | undefined
}

export interface ActionModel {
    readonly id: string
    readonly label: string
    readonly style: ActionStyle
    /** The action needs a field this app cannot fill: it is answered from the console. */
    readonly blocked: boolean
    readonly busy: boolean
}

/** One wait as the screen shows it. */
export interface WaitRowModel {
    readonly wait: Wait
    readonly title: string
    readonly open: boolean
    /** The state is one this app does not know: shown, never answered. */
    readonly unknownStatus: boolean
    readonly fields: readonly FieldModel[]
    readonly actions: readonly ActionModel[]
    /** An action is on its way: no other leaves. */
    readonly sending: boolean
    readonly problem: WaitProblem | undefined
    /** Some action needs a field this app cannot fill. */
    readonly console: boolean
    /** For an answered wait: the button pressed (its label, or its id when the question no longer has it), who and when. */
    readonly answered: {readonly label: string; readonly author: string; readonly at: string} | undefined
}

export type WaitsState =
    | {readonly kind: "loading"}
    /** `orgRequired`: the server wants the organization named, which the app cannot do yet. */
    | {readonly kind: "error"; readonly orgRequired: boolean}
    | {
          readonly kind: "ready"
          readonly rows: readonly WaitRowModel[]
          readonly hasMore: boolean
          readonly loadingMore: boolean
          /** The last refresh failed: the rows are the last list read. */
          readonly stale: boolean
          readonly orgRequired: boolean
      }

/** The number on the tab: how many wait for me, `more` when a further page exists (shown "12+"). */
export interface WaitsBadge {
    readonly count: number
    readonly more: boolean
}

export interface WaitsViewModel {
    readonly filter: WaitFilter
    readonly setFilter: (filter: WaitFilter) => void
    readonly state: WaitsState
    readonly badge: WaitsBadge | undefined
    readonly refreshing: boolean
    readonly refresh: () => void
    readonly loadMore: () => void
    /** An answer found its wait closed: said once at the top, the list having been read again. */
    readonly closedNotice: boolean
    readonly dismissClosed: () => void
    readonly edit: (waitId: string, fieldId: string, change: Partial<FieldDraft>) => void
    readonly submit: (waitId: string, actionId: string) => void
    readonly openRef: (url: string) => void
}

/** The tab's badge: how many waits are open for the person, read apart from the screen. */
export function useWaitsBadge(): WaitsBadge | undefined {
    const {data} = useOpenWaits()
    if (data === undefined || data.items.length === 0) return undefined
    return {count: data.items.length, more: data.next !== undefined}
}

function styleOf(action: WaitAction): ActionStyle {
    return action.style === "primary" || action.style === "danger" ? action.style : "secondary"
}

function languageOf(language: string): SupportedLanguage {
    return isSupportedLanguage(language) ? language : "en"
}

interface Sending {
    readonly id: string
    readonly action: string
}

interface Problem {
    readonly id: string
    readonly problem: WaitProblem
}

export function useWaitsViewModel(): WaitsViewModel {
    const {t, i18n} = useTranslation()
    const {unlock, openUrl, sessionStore} = useServices()
    const {account} = useRepositories()
    const [filter, setFilter] = useState<WaitFilter>("open")
    const [drafts, setDrafts] = useState<Readonly<Record<string, WaitDraft>>>({})
    const [errors, setErrors] = useState<Readonly<Record<string, Readonly<Record<string, FieldError>>>>>({})
    const [sending, setSending] = useState<Sending>()
    // Set at once, before the next render: a second press of the same instant sends nothing.
    const inFlight = useRef(false)
    const [pulling, setPulling] = useState(false)
    const [problem, setProblem] = useState<Problem>()
    const [closedNotice, setClosedNotice] = useState(false)
    const query = useWaits(filter)
    const badge = useWaitsBadge()
    const {mutateAsync} = useAnswerWait()
    const language = languageOf(i18n.language)
    const {data, error, isError, isFetchingNextPage, hasNextPage, refetch, fetchNextPage} = query
    const waits = data?.pages.flatMap((page) => page.items)

    const draftOf = useCallback((wait: Wait): WaitDraft => drafts[wait.id] ?? initialDraft(wait.question), [drafts])

    const rowOf = (wait: Wait): WaitRowModel => {
        const texts = waitTexts(wait, language)
        const draft = draftOf(wait)
        const spec = wait.question
        const open = wait.status === "open"
        const actions = actionsOf(spec, t("waits.done"))
        const mine = sending?.id === wait.id
        const models = actions.map((action): ActionModel => ({
            id: action.id,
            label: texts.action(action),
            style: styleOf(action),
            blocked: blockedFields(spec, action).length > 0,
            busy: mine && sending.action === action.id,
        }))
        const {answer} = wait
        const chosen = actions.find((action) => action.id === answer?.action)
        return {
            wait,
            title: texts.title,
            open,
            unknownStatus: wait.status === "unknown",
            fields: (spec.fields ?? []).map((field) => ({
                field,
                kind: fieldKind(field),
                draft: fieldDraft(draft, field),
                error: errors[wait.id]?.[field.id],
            })),
            actions: models,
            sending: mine,
            problem: problem?.id === wait.id ? problem.problem : undefined,
            console: models.some((model) => model.blocked),
            answered:
                wait.status === "resolved" && answer !== undefined
                    ? {label: chosen === undefined ? answer.action : texts.action(chosen), author: answer.author, at: answer.answeredAt}
                    : undefined,
        }
    }

    const state: WaitsState =
        waits !== undefined
            ? {
                  kind: "ready",
                  rows: waits.map(rowOf),
                  hasMore: hasNextPage,
                  loadingMore: isFetchingNextPage,
                  stale: isError,
                  orgRequired: isError && isOrgRequired(error),
              }
            : isError
              ? {kind: "error", orgRequired: isOrgRequired(error)}
              : {kind: "loading"}

    const submit = (waitId: string, actionId: string): void => {
        const wait = waits?.find((candidate) => candidate.id === waitId)
        if (wait?.status !== "open" || inFlight.current) return
        const action = actionsOf(wait.question, t("waits.done")).find((candidate) => candidate.id === actionId)
        if (action === undefined) return
        const checked = checkAnswer(wait.question, action, draftOf(wait))
        setProblem(undefined)
        if (!checked.ok) {
            setErrors((current) => ({...current, [waitId]: checked.errors}))
            return
        }
        setErrors((current) => ({...current, [waitId]: {}}))
        inFlight.current = true
        setSending({id: waitId, action: actionId})
        void (async () => {
            let outcome: UnlockOutcome
            try {
                // Biometrics when the last unlock is too old for the server (as a no on a gate); the unlock key is
                // proven to it before the answer leaves.
                outcome = await unlock.unlock(t("waits.confirm", {title: waitTexts(wait, language).title}), account, {always: false})
            } catch (failure) {
                // Offline, a 5xx, the keychain: the unlock comes first, so nothing was sent. It goes around the query
                // client, so a token the server no longer takes signs out here.
                if (failure instanceof ApiError && failure.problem.kind === "unauthorized") void sessionStore.getState().signOut()
                setProblem({id: waitId, problem: unlockErrorProblem(failure)})
                inFlight.current = false
                setSending(undefined)
                return
            }
            if (outcome === "confirmed") {
                try {
                    await mutateAsync({id: waitId, action: actionId, values: checked.values})
                    setDrafts((current) => Object.fromEntries(Object.entries(current).filter(([id]) => id !== waitId)))
                } catch (failure) {
                    const reason = answerProblem(failure)
                    if (reason === "closed") setClosedNotice(true)
                    else setProblem({id: waitId, problem: reason})
                }
            } else if (outcome !== "cancelled") {
                setProblem({id: waitId, problem: unlockOutcomeProblem(outcome)})
            }
            inFlight.current = false
            setSending(undefined)
        })()
    }

    return {
        filter,
        setFilter: (next) => {
            setFilter(next)
            setClosedNotice(false)
        },
        state,
        badge,
        // Only a pull spins: the list read again after an answer, or for the badge, does not.
        refreshing: pulling,
        refresh: () => {
            setPulling(true)
            void refetch().finally(() => {
                setPulling(false)
            })
        },
        loadMore: () => {
            void fetchNextPage()
        },
        closedNotice,
        dismissClosed: () => {
            setClosedNotice(false)
        },
        edit: (waitId, fieldId, change) => {
            const wait = waits?.find((candidate) => candidate.id === waitId)
            if (wait === undefined) return
            const field = (wait.question.fields ?? []).find((candidate) => candidate.id === fieldId)
            if (field === undefined) return
            // From the latest drafts, not the render's: two edits before a render both count.
            setDrafts((current) => {
                const draft = current[waitId] ?? initialDraft(wait.question)
                return {...current, [waitId]: {...draft, [fieldId]: {...fieldDraft(draft, field), ...change}}}
            })
        },
        submit,
        openRef: (url) => {
            void openUrl(url).catch(() => undefined)
        },
    }
}
