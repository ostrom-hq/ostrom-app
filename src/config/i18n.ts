import {getLocales} from "expo-localization"
import i18next from "i18next"
import {initReactI18next} from "react-i18next"
import {en} from "@/i18n/locales/en"
import {fr} from "@/i18n/locales/fr"
import {isSupportedLanguage, type SupportedLanguage} from "@/i18n/types"

/** The first device language Ostrom speaks, English otherwise. */
export function detectLanguage(languageCodes: readonly (string | null)[]): SupportedLanguage {
    for (const code of languageCodes) {
        if (code !== null && isSupportedLanguage(code)) return code
    }
    return "en"
}

function deviceLanguageCodes(): (string | null)[] {
    try {
        return getLocales().map((locale) => locale.languageCode)
    } catch {
        return []
    }
}

void i18next.use(initReactI18next).init({
    resources: {en: {translation: en}, fr: {translation: fr}},
    lng: detectLanguage(deviceLanguageCodes()),
    fallbackLng: "en",
    interpolation: {escapeValue: false},
})

export default i18next
