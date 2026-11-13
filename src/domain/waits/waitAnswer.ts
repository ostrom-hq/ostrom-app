// SPDX-License-Identifier: AGPL-3.0-only
/**
 * What a person fills in a wait, and the check the server makes of it (`WaitRules.answer` in ostrom-server), done first
 * on the phone so that a mistake is shown at the field and not as a refusal. The server stays the judge: it checks again.
 */
import type {WaitAction, WaitField, WaitSpec, WaitValues} from "@/api/contract/waits"

/** The most a free "other" answer of a choice field may hold. */
export const MAX_OTHER = 500
/** The most a text field holds when it names no `maxLength`. */
export const DEFAULT_MAX_TEXT = 8_000

/** The kinds of field this app fills. Any other (`file`, `secret`, a newer one) is answered from the console. */
export type FieldKind = "text" | "choice" | "confirm" | "number" | "date" | "info" | "unsupported"

export function fieldKind(field: WaitField): FieldKind {
    switch (field.type) {
        case "text":
        case "choice":
        case "confirm":
        case "number":
        case "date":
        case "info":
            return field.type
        default:
            return "unsupported"
    }
}

/** What one field holds while the person fills it; each kind reads only its part. */
export interface FieldDraft {
    /** `text`, `number` and `date`. */
    readonly text: string
    /** `choice`: the options picked. */
    readonly picked: readonly string[]
    /** `choice` with `allowOther`: the free answer. */
    readonly other: string
    /** `confirm`. */
    readonly checked: boolean
}

/** The draft of a whole wait, by field id. */
export type WaitDraft = Readonly<Record<string, FieldDraft>>

const DECIMAL = /^[+-]?(\d+([.,]\d*)?|[.,]\d+)([eE][+-]?\d+)?$/

const EMPTY_DRAFT: FieldDraft = {text: "", picked: [], other: "", checked: false}

/** The draft of one field before anything is typed: its `default` when it fits the kind. */
export function initialField(field: WaitField): FieldDraft {
    const value = field.default
    switch (fieldKind(field)) {
        case "text":
        case "date":
            return typeof value === "string" ? {...EMPTY_DRAFT, text: value} : EMPTY_DRAFT
        case "number":
            return typeof value === "number" && Number.isFinite(value) ? {...EMPTY_DRAFT, text: String(value)} : EMPTY_DRAFT
        case "confirm":
            return {...EMPTY_DRAFT, checked: value === true}
        case "choice": {
            const options = field.options ?? []
            const defaults = (Array.isArray(value) ? value : [value]).filter((item): item is string => typeof item === "string")
            const picked = defaults.filter((item) => options.includes(item))
            return {...EMPTY_DRAFT, picked: field.multiple === true ? picked : picked.slice(0, 1)}
        }
        case "info":
        case "unsupported":
            return EMPTY_DRAFT
    }
}

export function initialDraft(spec: WaitSpec): WaitDraft {
    return Object.fromEntries((spec.fields ?? []).map((field) => [field.id, initialField(field)]))
}

/** What a field holds in a draft: its part of the draft, or its initial state when the draft has none yet. */
export function fieldDraft(draft: WaitDraft, field: WaitField): FieldDraft {
    return draft[field.id] ?? initialField(field)
}

/** The actions of a wait: those it declares, or the single one the server gives a wait that declares none. */
export function actionsOf(spec: WaitSpec, doneLabel: string): readonly WaitAction[] {
    const declared = spec.actions ?? []
    return declared.length > 0 ? declared : [{id: "done", label: doneLabel}]
}

/** Why a field is not accepted; the screen says it at the field. */
export type FieldError =
    | {readonly kind: "required"}
    | {readonly kind: "number"}
    | {readonly kind: "date"}
    | {readonly kind: "too-long"; readonly max: number}

export type AnswerCheck =
    | {readonly ok: true; readonly values: WaitValues}
    | {readonly ok: false; readonly errors: Readonly<Record<string, FieldError>>}

/** Whether `text` is `YYYY-MM-DD` and a real calendar date (the server's `CalendarDates.isDate`). */
export function isCalendarDate(text: string): boolean {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text)
    if (match === null) return false
    const year = Number(match[1])
    const month = Number(match[2])
    const day = Number(match[3])
    const date = new Date(Date.UTC(year, month - 1, day))
    // Years 0 to 99 are read as 1900 to 1999 by Date.UTC: the year is set apart.
    date.setUTCFullYear(year)
    return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
}

/** A field's value as it goes in the answer, an error, or nothing when the field is left empty. */
type Read = {readonly value: unknown} | {readonly error: FieldError} | undefined

function readField(field: WaitField, draft: FieldDraft): Read {
    switch (fieldKind(field)) {
        case "text": {
            if (draft.text.trim() === "") return undefined
            const max = field.maxLength ?? DEFAULT_MAX_TEXT
            return draft.text.length > max ? {error: {kind: "too-long", max}} : {value: draft.text}
        }
        case "number": {
            const text = draft.text.trim()
            if (text === "") return undefined
            // A decimal comma (a French keyboard) reads as a point; hexadecimal and the like are no number here.
            const value = DECIMAL.test(text) ? Number(text.replace(",", ".")) : Number.NaN
            return Number.isFinite(value) ? {value} : {error: {kind: "number"}}
        }
        case "date": {
            const text = draft.text.trim()
            if (text === "") return undefined
            return isCalendarDate(text) ? {value: text} : {error: {kind: "date"}}
        }
        case "confirm":
            return {value: draft.checked}
        case "choice": {
            const other = field.allowOther === true ? draft.other.trim() : ""
            if (other.length > MAX_OTHER) return {error: {kind: "too-long", max: MAX_OTHER}}
            const chosen = other === "" ? draft.picked : [...draft.picked, other]
            if (chosen.length === 0) return undefined
            return {value: field.multiple === true ? [...new Set(chosen)] : chosen[0]}
        }
        case "info":
        case "unsupported":
            return undefined
    }
}

/**
 * The action's field ids that must be filled: the fields marked `required` and those the action `requires`. An `info`
 * field has no value, so it is never needed.
 */
function neededFor(spec: WaitSpec, action: WaitAction): ReadonlySet<string> {
    const fields = spec.fields ?? []
    const fillable = new Set(fields.filter((field) => fieldKind(field) !== "info").map((field) => field.id))
    const required = fields.filter((field) => field.required === true).map((field) => field.id)
    return new Set([...required, ...(action.requires ?? [])].filter((id) => fillable.has(id)))
}

/** The fields this app cannot fill that the action needs: such an action is answered from the console. */
export function blockedFields(spec: WaitSpec, action: WaitAction): readonly string[] {
    const needed = neededFor(spec, action)
    return (spec.fields ?? []).filter((field) => fieldKind(field) === "unsupported" && needed.has(field.id)).map((field) => field.id)
}

/**
 * Checks the draft for `action` as the server will: every value of the right type and size, and every field the wait or
 * the action needs filled. Gives the values to send, or the error of each field.
 */
export function checkAnswer(spec: WaitSpec, action: WaitAction, draft: WaitDraft): AnswerCheck {
    const needed = neededFor(spec, action)
    const values: Record<string, unknown> = {}
    const errors: Record<string, FieldError> = {}
    for (const field of spec.fields ?? []) {
        const read = readField(field, fieldDraft(draft, field))
        if (read === undefined) {
            if (needed.has(field.id) && fieldKind(field) !== "unsupported") errors[field.id] = {kind: "required"}
        } else if ("error" in read) {
            errors[field.id] = read.error
        } else {
            values[field.id] = read.value
        }
    }
    return Object.keys(errors).length === 0 ? {ok: true, values} : {ok: false, errors}
}
