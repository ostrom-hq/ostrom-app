// SPDX-License-Identifier: AGPL-3.0-only
import {hasText, isPresent} from "./presence"

describe("presence", () => {
    it("tells a sent value from a missing one", () => {
        expect(isPresent(undefined)).toBe(false)
        expect(isPresent(null)).toBe(false)
        expect(isPresent(0)).toBe(true)
    })

    it("only shows a text that says something", () => {
        expect(hasText(null)).toBe(false)
        expect(hasText("")).toBe(false)
        expect(hasText("acme")).toBe(true)
    })
})
