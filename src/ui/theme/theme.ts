import {palette} from "./tokens"

/** The semantic colours every screen reads. Same keys in light and dark, so a screen never branches on the scheme. */
export interface ThemeColors {
    readonly bg: string
    readonly bgSubtle: string
    readonly surface: string
    readonly surfaceRaised: string
    readonly surfaceSunken: string
    readonly border: string
    readonly borderStrong: string
    readonly text: string
    readonly textMuted: string
    readonly textSubtle: string
    /** A human is awaited: the gate, the approve button, the one primary action of a screen. */
    readonly signal: string
    readonly signalHover: string
    readonly onSignal: string
    readonly signalText: string
    readonly signalTint: string
    readonly success: string
    readonly successTint: string
    /** Budget and spending alerts. Never used for an approval. */
    readonly warning: string
    readonly warningText: string
    readonly warningTint: string
    readonly danger: string
    readonly dangerTint: string
    readonly info: string
    readonly infoTint: string
}

export type ColorSchemeName = "light" | "dark"

export interface Theme {
    readonly scheme: ColorSchemeName
    readonly colors: ThemeColors
}

export const lightTheme: Theme = {
    scheme: "light",
    colors: {
        bg: palette.ink50,
        bgSubtle: palette.ink100,
        surface: palette.ink0,
        surfaceRaised: palette.ink0,
        surfaceSunken: palette.ink25,
        border: palette.ink200,
        borderStrong: palette.ink300,
        text: palette.ink900,
        textMuted: palette.ink600,
        textSubtle: palette.ink500,
        signal: palette.garnet500,
        signalHover: palette.garnet600,
        onSignal: palette.ink0,
        signalText: palette.garnet500,
        signalTint: palette.garnet100,
        success: palette.green600,
        successTint: palette.green50,
        warning: palette.amber600,
        warningText: palette.amber800,
        warningTint: palette.amber50,
        danger: palette.red600,
        dangerTint: palette.red50,
        info: palette.blue600,
        infoTint: palette.blue50,
    },
}

export const darkTheme: Theme = {
    scheme: "dark",
    colors: {
        bg: palette.inkNight,
        bgSubtle: palette.ink850,
        surface: palette.ink850,
        surfaceRaised: "#271D21",
        surfaceSunken: "#100B0D",
        border: palette.ink800,
        borderStrong: palette.ink700,
        text: "#F4EEF0",
        textMuted: "#9C8F93",
        textSubtle: "#8C7F83",
        signal: palette.garnet400,
        signalHover: palette.garnet450,
        onSignal: palette.ink0,
        signalText: palette.garnet300,
        signalTint: palette.garnet900,
        success: palette.green400,
        successTint: palette.green900,
        warning: palette.amber400,
        warningText: palette.amber400,
        warningTint: palette.amber900,
        danger: palette.red400,
        dangerTint: palette.red900,
        info: palette.blue400,
        infoTint: palette.blue900,
    },
}

/** Picks the theme for a system colour scheme; anything unknown (web, tests) falls back to light. */
export function themeFor(scheme: string | null | undefined): Theme {
    return scheme === "dark" ? darkTheme : lightTheme
}
