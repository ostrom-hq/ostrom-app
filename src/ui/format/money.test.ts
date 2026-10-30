// SPDX-License-Identifier: AGPL-3.0-only
import {formatUsd} from "./money"

describe("formatUsd", () => {
    it("writes dollars with cents", () => {
        expect(formatUsd(0.128, "en")).toBe("$0.13")
        expect(formatUsd(1204.5, "en")).toBe("$1,204.50")
    })
})
