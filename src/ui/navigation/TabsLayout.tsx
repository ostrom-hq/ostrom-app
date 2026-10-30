import {Tabs} from "expo-router"
import {useTranslation} from "react-i18next"
import {useTheme} from "@/ui/theme/useTheme"

/** The signed-in app: the inbox first, then the history of signed decisions. */
export function TabsLayout(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
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
        </Tabs>
    )
}
