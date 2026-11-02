// SPDX-License-Identifier: AGPL-3.0-only
import {type Query, QueryClient} from "@tanstack/react-query"
import {accountKeys} from "./account"
import {notificationKeys} from "./notifications"
import {isOrgScoped} from "./orgScope"

const client = new QueryClient()

afterEach(() => {
    client.clear()
})

function queryOf(key: readonly unknown[]): Query {
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

    it.each([{key: ["approvals", "queue"]}, {key: ["receipts"]}, {key: ["activity"]}])("reads $key again in another organization", ({key}) => {
        expect(isOrgScoped(queryOf(key))).toBe(true)
    })
})
