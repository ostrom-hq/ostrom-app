// SPDX-License-Identifier: AGPL-3.0-only
import {type QueryClient, QueryClientProvider, useQueryClient} from "@tanstack/react-query"
import {Stack, ThemeProvider} from "expo-router"
import {StatusBar} from "expo-status-bar"
import {useEffect, useRef, useState} from "react"
import {useTranslation} from "react-i18next"
import {isOrgScoped} from "@/query/orgScope"
import {createQueryClient, followAppFocus} from "@/query/queryClient"
import {type AppServices, ServicesProvider, useServices, useSessionState} from "@/services/AppServices"
import {PrivacyGuard} from "@/ui/components/PrivacyGuard"
import {navigationThemeFor} from "@/ui/theme/navigationTheme"
import {useTheme} from "@/ui/theme/useTheme"

/** The routes, guarded by the session: signed out, only the login screen exists. */
function RootNavigator(): React.JSX.Element | null {
    const {t} = useTranslation()
    const {sessionStore, unlock, push} = useServices()
    const queryClient = useQueryClient()
    const state = useSessionState()
    useEffect(() => {
        void sessionStore.getState().hydrate()
    }, [sessionStore])
    useEffect(followAppFocus, [])
    const wasSignedIn = useRef(false)
    useEffect(() => {
        const signedOut = wasSignedIn.current && state.status === "signed-out"
        wasSignedIn.current = state.status === "signed-in"
        // Nothing of one account may show under the next one.
        if (state.status === "signed-out") queryClient.clear()
        // Nor prove anything for it: every sign-out, the person's and the server's (401), forgets the unlock key. A
        // launch that opens signed out (a keychain not readable yet) keeps it, with the session it belongs to.
        // Nor stay on its screen: the notifications it was shown go too, lock screen included (AUDIT-SECU-APP-2 N3).
        if (signedOut) {
            void unlock.forget().catch(() => undefined)
            void push.clearShown().catch(() => undefined)
        }
    }, [state.status, queryClient, unlock, push])
    const inSession = state.status === "signed-in"
    const orgId = inSession ? state.session.orgId : undefined
    // The organization of the session the screens read in; none before a launch or a sign-in reads anything.
    const readIn = useRef<{readonly orgId: string | undefined} | undefined>(undefined)
    useEffect(() => {
        const before = readIn.current
        readIn.current = inSession ? {orgId} : undefined
        // A launch or a sign-in reads everything for the first time (a sign-out clears all): only another organization
        // within the same session is read again.
        if (before === undefined || !inSession || before.orgId === orgId) return
        // Nothing of one organization may show under the next one. Here, in the parent of every screen, the effect runs
        // after the screens' own: their queries already read through the new organization's repositories.
        void queryClient.resetQueries({predicate: isOrgScoped})
    }, [inSession, orgId, queryClient])
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
                    {/* Every screen, signed in or out: the app switcher's picture shows none of them. */}
                    <PrivacyGuard>
                        <RootNavigator />
                    </PrivacyGuard>
                </ThemeProvider>
            </QueryClientProvider>
        </ServicesProvider>
    )
}
