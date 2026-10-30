import {useTranslation} from "react-i18next"
import {StyleSheet, Text, View} from "react-native"
import type {AskStatus} from "@/api/contract/yes"
import type {ThemeColors} from "@/ui/theme/theme"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"

/** Garnet only while a human is awaited (brand rule); the outcome colours after. */
function colorsOf(status: AskStatus, colors: ThemeColors): {text: string; background: string} {
    switch (status) {
        case "pending":
            return {text: colors.signalText, background: colors.signalTint}
        case "approved":
            return {text: colors.success, background: colors.successTint}
        case "rejected":
            return {text: colors.danger, background: colors.dangerTint}
        case "expired":
            return {text: colors.textMuted, background: colors.bgSubtle}
    }
}

export function StatusBadge({status}: {status: AskStatus}): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const palette = colorsOf(status, colors)
    return (
        <View style={[styles.badge, {backgroundColor: palette.background}]}>
            <Text style={[styles.label, {color: palette.text}]}>{t(`status.${status}`)}</Text>
        </View>
    )
}

const styles = StyleSheet.create({
    badge: {alignSelf: "flex-start", borderRadius: radius.pill, paddingHorizontal: space[2], paddingVertical: 2},
    label: {fontSize: fontSize[12], fontWeight: "700", letterSpacing: 0.6, textTransform: "uppercase"},
})
