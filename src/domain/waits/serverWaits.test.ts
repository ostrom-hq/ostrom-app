// SPDX-License-Identifier: AGPL-3.0-only
import type {Wait} from "@/api/contract/waits"
import {type ServerWaitRule, SERVER_WAIT_RULES, waitTexts} from "./serverWaits"

const wait = (kind: string, title: string): Wait => ({
    id: "w1",
    kind,
    status: "open",
    assignee: "m1",
    question: {title, actions: [{id: "yes", label: "Yes"}, {id: "no", label: "No"}]},
    createdAt: "2026-11-13T09:00:00Z",
})

/** A rule as the server will add one: a kind it writes, recognized by its exact title. */
const dropRule: ServerWaitRule = {
    kind: "drop_item",
    texts: (candidate) =>
        candidate.question.title === "Drop this item?"
            ? {title: {en: "Drop this item?", fr: "Abandonner cet élément ?"}, actions: {yes: {en: "Drop it", fr: "Abandonner"}}}
            : undefined,
}

describe("waitTexts", () => {
    it("writes no wait of the server yet: every wait is shown as written, in both languages", () => {
        expect(SERVER_WAIT_RULES).toEqual([])
        for (const language of ["en", "fr"] as const) {
            const texts = waitTexts(wait("human_request", "Which store?"), language)
            expect(texts.title).toBe("Which store?")
            expect(texts.action({id: "yes", label: "Yes"})).toBe("Yes")
        }
    })

    it("translates the title and the buttons of a wait the server wrote, in the app's language", () => {
        const drop = wait("drop_item", "Drop this item?")
        const fr = waitTexts(drop, "fr", [dropRule])
        expect(fr.title).toBe("Abandonner cet élément ?")
        expect(fr.action({id: "yes", label: "Yes"})).toBe("Abandonner")
        expect(waitTexts(drop, "en", [dropRule]).action({id: "yes", label: "Yes"})).toBe("Drop it")
    })

    it("keeps the label of an action the rule does not name", () => {
        expect(waitTexts(wait("drop_item", "Drop this item?"), "fr", [dropRule]).action({id: "no", label: "No"})).toBe("No")
    })

    it("leaves as written a wait of the kind that does not match, or of another kind", () => {
        expect(waitTexts(wait("drop_item", "Something an agent wrote"), "fr", [dropRule]).title).toBe("Something an agent wrote")
        expect(waitTexts(wait("human_request", "Drop this item?"), "fr", [dropRule]).title).toBe("Drop this item?")
    })
})
