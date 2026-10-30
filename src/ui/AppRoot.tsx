// SPDX-License-Identifier: AGPL-3.0-only
import {Stack, ThemeProvider} from "expo-router"
import {StatusBar} from "expo-status-bar"
import {useEffect} from "react"
import {type AppServices, ServicesProvider, useServices, useSessionState} from "@/services/AppServices"
import {navigationThemeFor} from "@/ui/theme/navigationTheme"
import {useTheme} from "@/ui/theme/useTheme"

/** The routes, guarded by the session: signed out, only the login screen exists. */
function RootNavigator(): React.JSX.Element | null {
    const {sessionStore} = useServices()
    const state = useSessionState()
    useEffect(() => {
        void sessionStore.getState().hydrate()
    }, [sessionStore])
    if (state.status === "loading") return null
    const signedIn = state.status === "signed-in"
    return (
        <Stack screenOptions={{headerShown: false}}>
            <Stack.Protected guard={signedIn}>
                <Stack.Screen name="index" />
            </Stack.Protected>
            <Stack.Protected guard={!signedIn}>
                <Stack.Screen name="login" />
            </Stack.Protected>
        </Stack>
    )
}

/** Providers and navigation; `app/_layout.tsx` gives it the real services, tests give fakes. */
export function AppRoot({services}: {services: AppServices}): React.JSX.Element {
    const {navigation, statusBar} = navigationThemeFor(useTheme())
    return (
        <ServicesProvider services={services}>
            <ThemeProvider value={navigation}>
                <StatusBar style={statusBar} />
                <RootNavigator />
            </ThemeProvider>
        </ServicesProvider>
    )
}
