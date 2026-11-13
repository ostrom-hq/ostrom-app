// SPDX-License-Identifier: AGPL-3.0-only
import {badgeText} from "./badgeText"

describe("badgeText", () => {
    it.each([
        [{count: 3, more: false}, "3"],
        [{count: 12, more: true}, "12+"],
        [{count: 100, more: false}, "99+"],
        [{count: 99, more: false}, "99"],
    ])("writes %p as %s", (badge, text) => {
        expect(badgeText(badge)).toBe(text)
    })
})
