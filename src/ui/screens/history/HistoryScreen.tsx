// SPDX-License-Identifier: AGPL-3.0-only
import {useTranslation} from "react-i18next"
import {ActivityIndicator, FlatList, RefreshControl, StyleSheet, Text, View} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import {Button} from "@/ui/components/Button"
import {ScreenTitle} from "@/ui/components/ScreenTitle"
import {formatDateTime} from "@/ui/format/dateTime"
import {TAB_EDGES} from "@/ui/navigation/tabEdges"
import type {ThemeColors} from "@/ui/theme/theme"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import type {ReceiptCheck} from "./receiptCheck"
import {type ReceiptCheckRun, type ReceiptRowModel, useHistoryViewModel} from "./useHistoryViewModel"

function CheckLine({check}: {check: ReceiptCheck}): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const [text, color] = lineOf(check, t, colors)
    return <Text style={[styles.check, {color}]}>{text}</Text>
}

function lineOf(check: ReceiptCheck, t: ReturnType<typeof useTranslation>["t"], colors: ThemeColors): readonly [string, string] {
    switch (check.kind) {
        case "verified":
            return [t("history.verified", {kid: check.kid}), colors.success]
        case "rejected":
            return [t("history.rejected", {reason: t(`history.reasons.${check.reason}`)}), colors.danger]
        case "no-key":
            return [t("history.noKey"), colors.textMuted]
    }
}

/** A check of the receipt with where it ran: only an Ostrom runner or the CI is shown as having run it. */
function CheckRunLine({run}: {run: ReceiptCheckRun}): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const declared = run.attestation === "workstation_declared"
    return (
        <View style={styles.checkRun}>
            <Text style={[styles.detail, styles.checkRunText, {color: colors.text}]}>{`${run.label} · ${run.outcome}`}</Text>
            <Text
                style={[
                    styles.badge,
                    declared
                        ? {color: colors.warningText, backgroundColor: colors.warningTint}
                        : {color: colors.success, backgroundColor: colors.successTint},
                ]}
            >
                {t(`history.attestation.${run.attestation}`)}
            </Text>
        </View>
    )
}

function ReceiptRow({row}: {row: ReceiptRowModel}): React.JSX.Element {
    const {t, i18n} = useTranslation()
    const {colors} = useTheme()
    const style = [styles.row, {backgroundColor: colors.surface, borderColor: colors.border}]
    if (!row.readable) {
        return (
            <View style={style} testID={`receipt-${row.key}`}>
                <Text style={[styles.rowTitle, {color: colors.text}]}>{t("history.unreadable")}</Text>
                <CheckLine check={row.check} />
            </View>
        )
    }
    // A result this app does not know is never shown raw inside a translated sentence.
    const title =
        row.result === undefined
            ? t("history.signed", {who: row.by})
            : row.result === "approved" || row.result === "rejected"
              ? t("history.decided", {result: t(`status.${row.result}`), who: row.by})
              : t("history.decidedOther", {who: row.by})
    return (
        <View style={style} testID={`receipt-${row.key}`}>
            <Text style={[styles.rowTitle, {color: colors.text}]}>
                {row.selfApproved ? `${title} · ${t("history.selfApproved")}` : title}
            </Text>
            <Text style={[styles.detail, {color: colors.textMuted}]}>{row.subject}</Text>
            <Text style={[styles.detail, {color: colors.textMuted}]}>{formatDateTime(row.at, i18n.language)}</Text>
            {row.checkRuns.map((run, index) => (
                // Index first: two checks may share a label.
                <CheckRunLine key={`${String(index)}:${run.label}`} run={run} />
            ))}
            <CheckLine check={row.check} />
        </View>
    )
}

export function HistoryScreen(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const {state, refreshing, refresh} = useHistoryViewModel()

    let body: React.JSX.Element
    switch (state.kind) {
        case "loading":
            body = <ActivityIndicator accessibilityLabel={t("history.title")} style={styles.center} />
            break
        case "error":
            body = (
                <View style={styles.message}>
                    <Text style={[styles.body, {color: colors.textMuted}]}>{t("history.error")}</Text>
                    <Button label={t("common.retry")} onPress={refresh} />
                </View>
            )
            break
        case "ready":
            body = (
                <FlatList
                    data={state.rows}
                    // Index first: two receipts with the same id (a server bug) still get a row each.
                    keyExtractor={(row, index) => `${String(index)}:${row.key}`}
                    contentContainerStyle={styles.list}
                    refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={colors.signal} />}
                    ListHeaderComponent={
                        <View style={styles.header}>
                            <ScreenTitle>{t("history.title")}</ScreenTitle>
                            <Text style={[styles.body, {color: colors.textMuted}]}>{t("history.intro")}</Text>
                            {state.stale ? (
                                <Text accessibilityRole="alert" style={[styles.body, {color: colors.danger}]}>
                                    {t("history.stale")}
                                </Text>
                            ) : null}
                        </View>
                    }
                    ListEmptyComponent={<Text style={[styles.body, {color: colors.textMuted}]}>{t("history.empty")}</Text>}
                    renderItem={({item}) => <ReceiptRow row={item} />}
                />
            )
            break
    }
    return <SafeAreaView edges={TAB_EDGES} style={[styles.container, {backgroundColor: colors.bg}]}>{body}</SafeAreaView>
}

const styles = StyleSheet.create({
    container: {flex: 1},
    center: {flex: 1},
    message: {padding: space[4], gap: space[3]},
    list: {padding: space[4], gap: space[3]},
    header: {gap: space[2], marginBottom: space[2]},
    body: {fontSize: fontSize[14]},
    row: {borderWidth: 1, borderRadius: radius.lg, padding: space[3], gap: space[1]},
    rowTitle: {fontSize: fontSize[16], fontWeight: "700"},
    detail: {fontSize: fontSize[14]},
    check: {fontSize: fontSize[12], fontWeight: "700"},
    checkRun: {flexDirection: "row", alignItems: "center", gap: space[2]},
    checkRunText: {flexShrink: 1},
    badge: {fontSize: fontSize[12], fontWeight: "700", borderRadius: radius.sm, paddingHorizontal: space[2], overflow: "hidden"},
})
