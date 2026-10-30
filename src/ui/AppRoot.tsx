// SPDX-License-Identifier: AGPL-3.0-only
import {type QueryClient, QueryClientProvider, useQueryClient} from "@tanstack/react-query"
import {Stack, ThemeProvider} from "expo-router"
import {StatusBar} from "expo-status-bar"
import {useEffect, useState} from "react"
import {useTranslation} from "react-i18next"
import {createQueryClient, followAppFocus} from "@/query/queryClient"
import {type AppServices, ServicesProvider, useServices, useSessionState} from "@/services/AppServices"
import {navigationThemeFor} from "@/ui/theme/navigationTheme"
import {useTheme} from "@/ui/theme/useTheme"

/** The routes, guarded by the session: signed out, only the login screen exists. */
function RootNavigator(): React.JSX.Element | null {
    const {t} = useTranslation()
    const {sessionStore} = useServices()
    const queryClient = useQueryClient()
    const state = useSessionState()
    useEffect(() => {
        void sessionStore.getState().hydrate()
    }, [sessionStore])
    useEffect(followAppFocus, [])
    useEffect(() => {
        // Nothing of one account may show under the next one.
        if (state.status === "signed-out") queryClient.clear()
    }, [state.status, queryClient])
    if (state.status === "loading") return null
    const signedIn = state.status === "signed-in"
    return (
        <Stack screenOptions={{headerShown: false}}>
            <Stack.Protected guard={signedIn}>
                <Stack.Screen name="(tabs)" />
                <Stack.Screen name="request/[id]" options={{headerShown: true, title: t("request.title"), headerBackTitle: t("inbox.title")}} />
            </Stack.Protected>
            <Stack.Protected guard={!signedIn}>
                <Stack.Screen name="login" />
            </Stack.Protected>
        </Stack>
    )
}

interface AppRootProps {
    readonly services: AppServices
    /** Tests give a client without retries; the app builds its own. */
    readonly queryClient?: QueryClient
}

/** Providers and navigation; `app/_layout.tsx` gives it the real services, tests give fakes. */
export function AppRoot({services, queryClient}: AppRootProps): React.JSX.Element {
    const {navigation, statusBar} = navigationThemeFor(useTheme())
    const [client] = useState(
        () =>
            queryClient ??
            createQueryClient({
                onUnauthorized: () => {
                    void services.sessionStore.getState().signOut()
                },
            }),
    )
    return (
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>
                <ThemeProvider value={navigation}>
                    <StatusBar style={statusBar} />
                    <RootNavigator />
                </ThemeProvider>
            </QueryClientProvider>
        </ServicesProvider>
    )
}
