// SPDX-License-Identifier: AGPL-3.0-only
/**
 * The waits the SERVER writes itself, shown in the app's language (TeamCode `WaitTexts.kt`, TC-NEW-4fb2da1-1). The
 * server writes its words in English; a wait asked by an agent or a person is shown as it was written, never translated.
 *
 * Ostrom's server writes no such wait yet (a wait is only asked by an agent or a person, `human_request`), so the list of
 * rules below is empty. A wait the server will write (a turn that failed, a permission) is added here as one rule, keyed
 * by its `kind`, with the exact title and actions it recognizes it by and their texts in each language; nothing else of
 * the app changes.
 */
import type {Wait} from "@/api/contract/waits"
import type {SupportedLanguage} from "@/i18n/types"

/** A text in every language of the app. */
export type Localized = Readonly<Record<SupportedLanguage, string>>

/** The title of a server-written wait, and the label of each of its actions by action id (an action left out keeps its own). */
export interface ServerWaitTexts {
    readonly title: Localized
    readonly actions: Readonly<Record<string, Localized>>
}

/** How one kind of server-written wait is recognized: by `kind` first, then by `texts` returning undefined for any other. */
export interface ServerWaitRule {
    readonly kind: string
    readonly texts: (wait: Wait) => ServerWaitTexts | undefined
}

/** Empty: the server writes no wait of its own yet, and `human_request` (asked by agents and people) stays as written. */
export const SERVER_WAIT_RULES: readonly ServerWaitRule[] = []

/** The title and the action labels of a wait as shown, in `language`. */
export interface WaitTexts {
    readonly title: string
    /** The label of an action by its id. */
    readonly action: (action: {readonly id: string; readonly label: string}) => string
}

/** The texts to show for `wait`: translated when a rule recognizes it as written by the server, as written otherwise. */
export function waitTexts(wait: Wait, language: SupportedLanguage, rules: readonly ServerWaitRule[] = SERVER_WAIT_RULES): WaitTexts {
    const texts = rules.find((rule) => rule.kind === wait.kind)?.texts(wait)
    return {
        title: texts?.title[language] ?? wait.question.title,
        action: (action) => texts?.actions[action.id]?.[language] ?? action.label,
    }
}
