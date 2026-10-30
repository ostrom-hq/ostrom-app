import {DarkTheme, DefaultTheme} from "expo-router"
import type {Theme} from "./theme"

export type NavigationTheme = typeof DefaultTheme

/** The navigation colours and the status bar style that go with a Signal theme. */
export function navigationThemeFor(theme: Theme): {navigation: NavigationTheme; statusBar: "light" | "dark"} {
    const dark = theme.scheme === "dark"
    const base = dark ? DarkTheme : DefaultTheme
    return {
        navigation: {
            ...base,
            colors: {
                ...base.colors,
                primary: theme.colors.signal,
                background: theme.colors.bg,
                card: theme.colors.surface,
                text: theme.colors.text,
                border: theme.colors.border,
            },
        },
        statusBar: dark ? "light" : "dark",
    }
}
