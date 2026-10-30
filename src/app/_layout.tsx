import "@/config/i18n"
import {Stack, ThemeProvider} from "expo-router"
import {StatusBar} from "expo-status-bar"
import {navigationThemeFor} from "@/ui/theme/navigationTheme"
import {useTheme} from "@/ui/theme/useTheme"

export default function RootLayout(): React.JSX.Element {
    const {navigation, statusBar} = navigationThemeFor(useTheme())
    return (
        <ThemeProvider value={navigation}>
            <StatusBar style={statusBar} />
            <Stack screenOptions={{headerShown: false}} />
        </ThemeProvider>
    )
}
