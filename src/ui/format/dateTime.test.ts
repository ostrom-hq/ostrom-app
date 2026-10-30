import {formatDateTime} from "./dateTime"

describe("formatDateTime", () => {
    it("writes the day, the month and the time", () => {
        const text = formatDateTime("2026-10-30T09:00:00.000Z", "en")
        expect(text).toMatch(/30/)
        expect(text).toMatch(/Oct/)
    })
})
