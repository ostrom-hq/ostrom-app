// SPDX-License-Identifier: AGPL-3.0-only
import {useTranslation} from "react-i18next"
import {ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import {Button} from "@/ui/components/Button"
import {ScreenTitle} from "@/ui/components/ScreenTitle"
import {Field, Section} from "@/ui/components/Section"
import {TAB_EDGES} from "@/ui/navigation/tabEdges"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {type Toggle, useSettingsViewModel} from "./useSettingsViewModel"

const TOGGLES: readonly Toggle[] = ["requests", "decisions", "spending"]

/** A labelled on/off row; the whole row is the control, read as a switch by screen readers. */
function SwitchRow({label, value, onChange}: {label: string; value: boolean; onChange: (value: boolean) => void}): React.JSX.Element {
    const {colors} = useTheme()
    return (
        <Pressable
            accessibilityRole="switch"
            accessibilityLabel={label}
            accessibilityState={{checked: value}}
            onPress={() => {
                onChange(!value)
            }}
            style={styles.switchRow}
        >
            <Text style={[styles.label, {color: colors.text}]}>{label}</Text>
            <View style={[styles.track, {backgroundColor: value ? colors.text : colors.borderStrong}]}>
                <View style={[styles.knob, {backgroundColor: colors.surface, alignSelf: value ? "flex-end" : "flex-start"}]} />
            </View>
        </Pressable>
    )
}

/** The phone's time zone, for quiet hours; the server stores it with them. */
export function phoneTimeZone(): string {
    return Intl.DateTimeFormat().resolvedOptions().timeZone
}

export function SettingsScreen(): React.JSX.Element {
    const {t, i18n} = useTranslation()
    const {colors} = useTheme()
    const vm = useSettingsViewModel(i18n.language, phoneTimeZone())
    const {state} = vm

    let notifications: React.JSX.Element
    switch (state.kind) {
        case "loading":
            notifications = <ActivityIndicator accessibilityLabel={t("settings.notifications")} />
            break
        case "error":
            notifications = (
                <>
                    <Text style={[styles.body, {color: colors.textMuted}]}>{t("settings.error")}</Text>
                    <Button label={t("common.retry")} onPress={vm.retry} />
                </>
            )
            break
        case "ready": {
            const {quietHours} = state.settings
            notifications = (
                <>
                    {TOGGLES.map((key) => (
                        <SwitchRow
                            key={key}
                            label={t(`settings.toggles.${key}`)}
                            value={state.settings[key]}
                            onChange={(value) => {
                                vm.toggle(key, value)
                            }}
                        />
                    ))}
                    <SwitchRow label={t("settings.quietHours")} value={quietHours !== undefined} onChange={vm.setQuietHours} />
                    {quietHours === undefined ? null : (
                        <Text style={[styles.body, {color: colors.textMuted}]}>
                            {t("settings.quietHoursRange", {start: quietHours.start, end: quietHours.end, timeZone: quietHours.timeZone})}
                        </Text>
                    )}
                    {vm.saveFailed ? (
                        <Text accessibilityRole="alert" style={[styles.body, {color: colors.danger}]}>
                            {t("settings.saveFailed")}
                        </Text>
                    ) : null}
                </>
            )
            break
        }
    }

    const push = vm.pushOn === undefined ? undefined : vm.pushOn ? t("settings.pushOn") : t("settings.pushOff")
    return (
        <SafeAreaView edges={TAB_EDGES} style={[styles.container, {backgroundColor: colors.bg}]}>
            <ScrollView contentContainerStyle={styles.content}>
                <ScreenTitle>{t("settings.title")}</ScreenTitle>
                <Section title={t("settings.account")}>
                    {vm.login === undefined ? null : <Field label={t("settings.login")} value={vm.login} />}
                    {vm.serverUrl === undefined ? null : <Field label={t("settings.server")} value={vm.serverUrl} />}
                </Section>
                <Section title={t("settings.notifications")}>
                    {push === undefined ? null : <Text style={[styles.body, {color: colors.textMuted}]}>{push}</Text>}
                    {notifications}
                </Section>
                <Button label={t("settings.signOut")} variant="danger" onPress={vm.signOut} busy={vm.signingOut} />
            </ScrollView>
        </SafeAreaView>
    )
}

const styles = StyleSheet.create({
    container: {flex: 1},
    content: {padding: space[4], gap: space[4]},
    body: {fontSize: fontSize[14]},
    switchRow: {flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: space[3]},
    label: {fontSize: fontSize[16], flexShrink: 1},
    track: {width: 44, height: 26, borderRadius: radius.pill, padding: 3},
    knob: {width: 20, height: 20, borderRadius: radius.pill},
})
