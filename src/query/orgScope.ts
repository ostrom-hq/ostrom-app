// SPDX-License-Identifier: AGPL-3.0-only
import type {Query} from "@tanstack/react-query"
import {accountKeys} from "./account"
import {notificationKeys} from "./notifications"

/** The reads of the person, not of an organization: the list of their organizations, this phone's push settings. */
const PERSON_SCOPED: ReadonlySet<unknown> = new Set([accountKeys.all[0], notificationKeys.settings[0], notificationKeys.device("")[0]])

/** A read made in the organization sent in `Ostrom-Org`: read again when the person chooses another. */
export function isOrgScoped(query: Query): boolean {
    return !PERSON_SCOPED.has(query.queryKey[0])
}
