// SPDX-License-Identifier: AGPL-3.0-only
import type {ReactNode} from "react"
import {StyleSheet, Text, View} from "react-native"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"

/** A titled block of a detail screen. */
export function Section({title, children}: {title: string; children: ReactNode}): React.JSX.Element {
    const {colors} = useTheme()
    return (
        <View style={styles.section}>
            <Text accessibilityRole="header" style={[styles.title, {color: colors.textMuted}]}>
                {title}
            </Text>
            <View style={[styles.body, {backgroundColor: colors.surface, borderColor: colors.border}]}>{children}</View>
        </View>
    )
}

/** A label and its value on one line of a section. */
export function Field({label, value}: {label: string; value: string}): React.JSX.Element {
    const {colors} = useTheme()
    return (
        <View style={styles.field}>
            <Text style={[styles.label, {color: colors.textMuted}]}>{label}</Text>
            <Text selectable style={[styles.value, {color: colors.text}]}>
                {value}
            </Text>
        </View>
    )
}

const styles = StyleSheet.create({
    section: {gap: space[2]},
    title: {fontSize: fontSize[12], fontWeight: "700", letterSpacing: 0.96, textTransform: "uppercase"},
    body: {borderWidth: 1, borderRadius: radius.lg, padding: space[3], gap: space[2]},
    field: {flexDirection: "row", gap: space[3], justifyContent: "space-between"},
    label: {fontSize: fontSize[14]},
    value: {fontSize: fontSize[14], fontWeight: "600", flexShrink: 1, textAlign: "right"},
})
