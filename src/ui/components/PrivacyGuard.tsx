// SPDX-License-Identifier: AGPL-3.0-only
import {type ReactNode, useEffect, useState} from "react"
import {useTranslation} from "react-i18next"
import {AppState, type AppStateStatus, StyleSheet, Text, View} from "react-native"
import {fontSize} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"

/**
 * iOS takes the app switcher's picture while `inactive`; Android goes straight to `background`. `unknown` and
 * `extension` leave the screens visible: a cover that no later event lifts would lock the app.
 */
function isHidden(status: AppStateStatus): boolean {
    return status === "inactive" || status === "background"
}

/**
 * Covers every screen while the app is not in front, so the picture the system keeps for the app switcher shows no
 * request, diff or receipt (AUDIT-SECU-APP-2 A1): anyone holding the unlocked phone would read them there without
 * biometrics. Screen readers skip the covered screens too. Best effort: the cover is drawn by React, after the state
 * change; a native guard (`FLAG_SECURE`) is a question left to the product.
 */
export function PrivacyGuard({children}: {readonly children: ReactNode}): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const [hidden, setHidden] = useState(() => isHidden(AppState.currentState))
    useEffect(() => {
        const subscription = AppState.addEventListener("change", (status) => {
            setHidden(isHidden(status))
        })
        // A change between the first render and this subscription is read here, not lost.
        setHidden(isHidden(AppState.currentState))
        return () => {
            subscription.remove()
        }
    }, [])
    return (
        <>
            <View
                style={styles.screens}
                accessibilityElementsHidden={hidden}
                importantForAccessibility={hidden ? "no-hide-descendants" : "auto"}
            >
                {children}
            </View>
            {hidden ? (
                <View
                    testID="privacy-cover"
                    accessibilityViewIsModal
                    style={[StyleSheet.absoluteFill, styles.cover, {backgroundColor: colors.bg}]}
                >
                    <Text style={[styles.name, {color: colors.text}]}>{t("app.name")}</Text>
                </View>
            ) : null}
        </>
    )
}

const styles = StyleSheet.create({
    screens: {flex: 1},
    cover: {alignItems: "center", justifyContent: "center", zIndex: 1000, elevation: 1000},
    name: {fontSize: fontSize[28], fontWeight: "600"},
})
