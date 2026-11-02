// SPDX-License-Identifier: AGPL-3.0-only
import {useEffect, useState} from "react"
import {AppState, type AppStateStatus, StyleSheet, Text, View} from "react-native"
import {fontSize} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"

/** iOS takes the app switcher's picture while `inactive`; Android goes straight to `background`. */
function isHidden(status: AppStateStatus | null): boolean {
    return status === "inactive" || status === "background"
}

/**
 * Covers every screen while the app is not in front, so the picture the system keeps for the app switcher shows no
 * request, diff or receipt (AUDIT-SECU-APP-2 A1): anyone holding the unlocked phone would read them there without
 * biometrics. Nothing while the app is active.
 */
export function PrivacyCover(): React.JSX.Element | null {
    const {colors} = useTheme()
    const [hidden, setHidden] = useState(() => isHidden(AppState.currentState))
    useEffect(() => {
        const subscription = AppState.addEventListener("change", (status) => {
            setHidden(isHidden(status))
        })
        return () => {
            subscription.remove()
        }
    }, [])
    if (!hidden) return null
    return (
        <View testID="privacy-cover" style={[StyleSheet.absoluteFill, styles.cover, {backgroundColor: colors.bg}]}>
            <Text style={[styles.name, {color: colors.text}]}>Ostrom</Text>
        </View>
    )
}

const styles = StyleSheet.create({
    cover: {alignItems: "center", justifyContent: "center", zIndex: 1000, elevation: 1000},
    name: {fontSize: fontSize[28], fontWeight: "600"},
})
