import type * as I18nModule from "./i18n"
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
        expect(i18next.t("inbox.title")).toBe("En attente de toi")
        await i18next.changeLanguage("en")
        expect(i18next.t("inbox.title")).toBe("Waiting for you")
    })
})

describe("device language", () => {
    afterEach(() => {
        jest.resetModules()
        jest.dontMock("expo-localization")
    })

    it("starts in the device language", () => {
        jest.doMock("expo-localization", () => ({getLocales: () => [{languageCode: "fr"}]}))
        jest.isolateModules(() => {
            const isolated = jest.requireActual<typeof I18nModule>("./i18n")
            expect(isolated.default.language).toBe("fr")
        })
    })

    it("starts in English when the device locales cannot be read", () => {
        jest.doMock("expo-localization", () => ({
            getLocales: () => {
                throw new Error("native module missing")
            },
        }))
        jest.isolateModules(() => {
            const isolated = jest.requireActual<typeof I18nModule>("./i18n")
            expect(isolated.default.language).toBe("en")
        })
    })
})
