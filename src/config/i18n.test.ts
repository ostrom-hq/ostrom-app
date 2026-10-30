import i18next, {detectLanguage} from "./i18n"

describe("detectLanguage", () => {
    it("takes the first device language Ostrom speaks", () => {
        expect(detectLanguage(["de", "fr", "en"])).toBe("fr")
    })

    it("falls back to English", () => {
        expect(detectLanguage(["de", null])).toBe("en")
        expect(detectLanguage([])).toBe("en")
    })
})

describe("translations", () => {
    it("serves French when asked", async () => {
        await i18next.changeLanguage("fr")
        expect(i18next.t("home.title")).toBe("En attente de toi")
        await i18next.changeLanguage("en")
        expect(i18next.t("home.title")).toBe("Waiting for you")
    })
})
