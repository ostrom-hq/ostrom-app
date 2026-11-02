// SPDX-License-Identifier: AGPL-3.0-only
import {type Query, QueryClient} from "@tanstack/react-query"
import {accountKeys} from "./account"
import {notificationKeys} from "./notifications"
import {isOrgScoped} from "./orgScope"

function queryOf(key: readonly unknown[]): Query {
    const client = new QueryClient()
    client.setQueryData(key, 1)
    const query = client.getQueryCache().find({queryKey: key, exact: true})
    if (query === undefined) throw new Error("no query")
    return query
}

describe("isOrgScoped", () => {
    it.each([accountKeys.organizations(), notificationKeys.settings, notificationKeys.device("fr")])(
        "keeps %p, a read of the person, across organizations",
        (key) => {
            expect(isOrgScoped(queryOf(key))).toBe(false)
        },
    )

    it.each([["approvals", "queue"], ["receipts"], ["activity"]])("reads %p again in another organization", (...key) => {
        expect(isOrgScoped(queryOf(key))).toBe(true)
    })
})
