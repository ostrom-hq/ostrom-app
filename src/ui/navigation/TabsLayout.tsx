// SPDX-License-Identifier: AGPL-3.0-only
import {Tabs} from "expo-router"
import {useTranslation} from "react-i18next"
import {FEATURES} from "@/domain/capabilities/compatibility"
import {useFeature, useServices} from "@/services/AppServices"
import {badgeText} from "@/ui/screens/waits/badgeText"
import {useWaitsBadge} from "@/ui/screens/waits/useWaitsViewModel"
import {usePushBridge} from "@/ui/push/usePushBridge"
import {useTheme} from "@/ui/theme/useTheme"

/** The signed-in app: the inbox first, then the waits asked of the person, the signed history, the activity, the spending and the settings. */
export function TabsLayout(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const {onTabPress} = useServices()
    usePushBridge()
    // A tab whose function the server does not serve is hidden.
    const receipts = useFeature(FEATURES.receipts)
    const budgets = useFeature(FEATURES.budgets)
    const waits = useFeature(FEATURES.waits)
    const waitsBadge = useWaitsBadge()
    return (
        <Tabs
            // The performance pass times a list from this press (the E2E build only: no other build listens).
            screenListeners={({route}) => ({
                tabPress: () => {
                    onTabPress?.(route.name)
                },
            })}
            screenOptions={{
                headerShown: false,
                // Garnet is kept for a human being awaited: the selected tab is ink, not garnet.
                tabBarActiveTintColor: colors.text,
                tabBarInactiveTintColor: colors.textSubtle,
                tabBarStyle: {backgroundColor: colors.surface, borderTopColor: colors.border},
            }}
        >
            <Tabs.Screen name="index" options={{title: t("tabs.inbox")}} />
            <Tabs.Screen
                name="waits"
                options={{
                    title: t("tabs.waits"),
                    href: waits ? "/waits" : null,
                    ...(waitsBadge === undefined ? {} : {tabBarBadge: badgeText(waitsBadge)}),
                }}
            />
            <Tabs.Screen name="history" options={{title: t("tabs.history"), href: receipts ? "/history" : null}} />
            <Tabs.Screen name="activity" options={{title: t("tabs.activity")}} />
            <Tabs.Screen name="spending" options={{title: t("tabs.spending"), href: budgets ? "/spending" : null}} />
            <Tabs.Screen name="settings" options={{title: t("tabs.settings")}} />
        </Tabs>
    )
}
