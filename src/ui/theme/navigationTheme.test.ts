import {navigationThemeFor} from "./navigationTheme"
import {darkTheme, lightTheme} from "./theme"

describe("navigationThemeFor", () => {
    it("paints the navigation in the dark Signal colours with a light status bar", () => {
        const {navigation, statusBar} = navigationThemeFor(darkTheme)
        expect(navigation.dark).toBe(true)
        expect(navigation.colors.primary).toBe(darkTheme.colors.signal)
        expect(navigation.colors.background).toBe(darkTheme.colors.bg)
        expect(statusBar).toBe("light")
    })

    it("paints the navigation in the light Signal colours with a dark status bar", () => {
        const {navigation, statusBar} = navigationThemeFor(lightTheme)
        expect(navigation.dark).toBe(false)
        expect(navigation.colors.card).toBe(lightTheme.colors.surface)
        expect(statusBar).toBe("dark")
    })
})
