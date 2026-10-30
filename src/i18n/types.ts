import type {en} from "./locales/en"

type DeepString<T> = {readonly [K in keyof T]: T[K] extends string ? string : DeepString<T[K]>}

/** The shape every locale implements: the English keys, with any string as value. */
export type TranslationShape = DeepString<typeof en>

export const SUPPORTED_LANGUAGES = ["en", "fr"] as const
export type SupportedLanguage = (typeof SUPPORTED_LANGUAGES)[number]

export function isSupportedLanguage(value: string): value is SupportedLanguage {
    return (SUPPORTED_LANGUAGES as readonly string[]).includes(value)
}
