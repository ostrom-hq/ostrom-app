import {darkTheme, lightTheme, themeFor} from "./theme"

describe("themeFor", () => {
    it("uses the dark Signal theme for a dark system", () => {
        expect(themeFor("dark")).toBe(darkTheme)
    })

    it("falls back to light when the scheme is unknown", () => {
        expect(themeFor(null)).toBe(lightTheme)
        expect(themeFor(undefined)).toBe(lightTheme)
    })

    it("keeps garnet for the awaited human in both schemes", () => {
        expect(lightTheme.colors.signal).toBe("#8E1B3A")
        expect(darkTheme.colors.signal).toBe("#C7304A")
    })
})
