// SPDX-License-Identifier: AGPL-3.0-only
import {useTranslation} from "react-i18next"
import {ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import type {SpendPeriod} from "@/api/contract/spending"
import {Button} from "@/ui/components/Button"
import {ScreenTitle} from "@/ui/components/ScreenTitle"
import {Section} from "@/ui/components/Section"
import {formatUsd} from "@/ui/format/money"
import {TAB_EDGES} from "@/ui/navigation/tabEdges"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {type Breakdown, type Budget, useSpendingViewModel} from "./useSpendingViewModel"

const PERIODS: readonly SpendPeriod[] = ["week", "month"]

function PeriodPicker({period, onChange}: {period: SpendPeriod; onChange: (period: SpendPeriod) => void}): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    return (
        <View accessibilityRole="tablist" style={[styles.picker, {borderColor: colors.border, backgroundColor: colors.surfaceSunken}]}>
            {PERIODS.map((option) => {
                const selected = option === period
                return (
                    <Pressable
                        key={option}
                        accessibilityRole="tab"
                        accessibilityState={{selected}}
                        onPress={() => {
                            onChange(option)
                        }}
                        style={[styles.option, selected ? {backgroundColor: colors.surface} : null]}
                    >
                        <Text style={[styles.optionLabel, {color: selected ? colors.text : colors.textMuted}]}>{t(`spending.period.${option}`)}</Text>
                    </Pressable>
                )
            })}
        </View>
    )
}

function BudgetBar({budget}: {budget: Budget}): React.JSX.Element {
    const {t, i18n} = useTranslation()
    const {colors} = useTheme()
    const color = budget.warning ? colors.warning : colors.textMuted
    return (
        <View style={styles.budget}>
            <View style={[styles.track, {backgroundColor: colors.bgSubtle}]}>
                <View style={[styles.fill, {backgroundColor: color, width: `${Math.min(budget.ratio, 1) * 100}%`}]} />
            </View>
            <Text style={[styles.detail, {color: budget.warning ? colors.warningText : colors.textMuted}]}>
                {t("spending.ofBudget", {percent: Math.round(budget.ratio * 100), budget: formatUsd(budget.usd, i18n.language)})}
            </Text>
        </View>
    )
}

function BreakdownSection({breakdown}: {breakdown: Breakdown}): React.JSX.Element {
    const {t, i18n} = useTranslation()
    const {colors} = useTheme()
    return (
        <Section title={t(`spending.${breakdown.key}`)}>
            {breakdown.lines.length === 0 ? <Text style={[styles.detail, {color: colors.textMuted}]}>{t("spending.none")}</Text> : null}
            {breakdown.lines.map((line) => (
                <View key={line.key} style={styles.line}>
                    <View style={styles.lineHead}>
                        <Text style={[styles.label, {color: colors.text}]}>{line.label}</Text>
                        <Text style={[styles.amount, {color: colors.text}]}>{formatUsd(line.usd, i18n.language)}</Text>
                    </View>
                    <View style={[styles.track, {backgroundColor: colors.bgSubtle}]}>
                        <View style={[styles.fill, {backgroundColor: colors.textSubtle, width: `${line.share * 100}%`}]} />
                    </View>
                    <Text style={[styles.detail, {color: colors.textMuted}]}>{t("spending.sessions", {n: line.sessions})}</Text>
                </View>
            ))}
        </Section>
    )
}

export function SpendingScreen(): React.JSX.Element {
    const {t, i18n} = useTranslation()
    const {colors} = useTheme()
    const {period, setPeriod, state, retry} = useSpendingViewModel()

    let body: React.JSX.Element
    switch (state.kind) {
        case "loading":
            body = <ActivityIndicator accessibilityLabel={t("spending.title")} />
            break
        case "error":
            body = (
                <View style={styles.message}>
                    <Text style={[styles.detail, {color: colors.textMuted}]}>{t("spending.error")}</Text>
                    <Button label={t("common.retry")} onPress={retry} />
                </View>
            )
            break
        case "ready":
            body = (
                <>
                    <View style={styles.total}>
                        <Text style={[styles.totalAmount, {color: state.budget?.warning === true ? colors.warningText : colors.text}]}>
                            {formatUsd(state.totalUsd, i18n.language)}
                        </Text>
                        {state.budget === undefined ? null : <BudgetBar budget={state.budget} />}
                    </View>
                    {state.breakdowns.map((breakdown) => (
                        <BreakdownSection key={breakdown.key} breakdown={breakdown} />
                    ))}
                </>
            )
            break
    }
    return (
        <SafeAreaView edges={TAB_EDGES} style={[styles.container, {backgroundColor: colors.bg}]}>
            <ScrollView contentContainerStyle={styles.content}>
                <ScreenTitle>{t("spending.title")}</ScreenTitle>
                <PeriodPicker period={period} onChange={setPeriod} />
                {body}
            </ScrollView>
        </SafeAreaView>
    )
}

const styles = StyleSheet.create({
    container: {flex: 1},
    content: {padding: space[4], gap: space[4]},
    message: {gap: space[3]},
    picker: {flexDirection: "row", borderWidth: 1, borderRadius: radius.md, padding: 2},
    option: {flex: 1, alignItems: "center", paddingVertical: space[2], borderRadius: radius.sm},
    optionLabel: {fontSize: fontSize[14], fontWeight: "600"},
    total: {gap: space[2]},
    totalAmount: {fontSize: fontSize[40], fontWeight: "800", letterSpacing: -1.2},
    budget: {gap: space[1]},
    track: {height: 6, borderRadius: radius.pill, overflow: "hidden"},
    fill: {height: 6},
    line: {gap: space[1]},
    lineHead: {flexDirection: "row", justifyContent: "space-between", gap: space[2]},
    label: {fontSize: fontSize[14], fontWeight: "600", flexShrink: 1},
    amount: {fontSize: fontSize[14], fontWeight: "700"},
    detail: {fontSize: fontSize[14]},
})
