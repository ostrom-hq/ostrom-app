// SPDX-License-Identifier: AGPL-3.0-only
import {Tabs} from "expo-router"
import {useTranslation} from "react-i18next"
import {usePushBridge} from "@/ui/push/usePushBridge"
import {useTheme} from "@/ui/theme/useTheme"

/** The signed-in app: the inbox first, then the signed history, the activity, the spending and the settings. */
export function TabsLayout(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    usePushBridge()
    return (
        <Tabs
            screenOptions={{
                headerShown: false,
                // Garnet is kept for a human being awaited: the selected tab is ink, not garnet.
                tabBarActiveTintColor: colors.text,
                tabBarInactiveTintColor: colors.textSubtle,
                tabBarStyle: {backgroundColor: colors.surface, borderTopColor: colors.border},
            }}
        >
            <Tabs.Screen name="index" options={{title: t("tabs.inbox")}} />
            <Tabs.Screen name="history" options={{title: t("tabs.history")}} />
            <Tabs.Screen name="activity" options={{title: t("tabs.activity")}} />
            <Tabs.Screen name="spending" options={{title: t("tabs.spending")}} />
            <Tabs.Screen name="settings" options={{title: t("tabs.settings")}} />
        </Tabs>
    )
}
