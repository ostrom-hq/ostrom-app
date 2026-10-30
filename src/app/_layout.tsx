import "@/config/i18n"
import {DarkTheme, DefaultTheme, Stack, ThemeProvider} from "expo-router"
import {StatusBar} from "expo-status-bar"
import {useTheme} from "@/ui/theme/useTheme"

export default function RootLayout(): React.JSX.Element {
    const theme = useTheme()
    const base = theme.scheme === "dark" ? DarkTheme : DefaultTheme
    return (
        <ThemeProvider
            value={{
                ...base,
                colors: {
                    ...base.colors,
                    primary: theme.colors.signal,
                    background: theme.colors.bg,
                    card: theme.colors.surface,
                    text: theme.colors.text,
                    border: theme.colors.border,
                },
            }}
        >
            <StatusBar style={theme.scheme === "dark" ? "light" : "dark"} />
            <Stack screenOptions={{headerShown: false}} />
        </ThemeProvider>
    )
}
