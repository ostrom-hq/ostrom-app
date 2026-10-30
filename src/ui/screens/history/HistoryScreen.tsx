import {useTranslation} from "react-i18next"
import {ActivityIndicator, FlatList, RefreshControl, StyleSheet, Text, View} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import {Button} from "@/ui/components/Button"
import {ScreenTitle} from "@/ui/components/ScreenTitle"
import {formatDateTime} from "@/ui/format/dateTime"
import type {ThemeColors} from "@/ui/theme/theme"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import type {ReceiptCheck} from "./receiptCheck"
import {type ReceiptRowModel, useHistoryViewModel} from "./useHistoryViewModel"

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

function ReceiptRow({row}: {row: ReceiptRowModel}): React.JSX.Element {
    const {t, i18n} = useTranslation()
    const {colors} = useTheme()
    const result = row.result === "approved" || row.result === "rejected" ? t(`status.${row.result}`) : row.result
    const title = !row.readable
        ? t("history.unreadable")
        : result === undefined
          ? t("history.signed", {who: row.by})
          : t("history.decided", {result, who: row.by})
    return (
        <View style={[styles.row, {backgroundColor: colors.surface, borderColor: colors.border}]} testID={`receipt-${row.key}`}>
            <Text style={[styles.rowTitle, {color: colors.text}]}>{title}</Text>
            {row.subject === undefined ? null : <Text style={[styles.detail, {color: colors.textMuted}]}>{row.subject}</Text>}
            {row.at === undefined ? null : (
                <Text style={[styles.detail, {color: colors.textMuted}]}>{formatDateTime(row.at, i18n.language)}</Text>
            )}
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
                    keyExtractor={(row) => row.key}
                    contentContainerStyle={styles.list}
                    refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={colors.signal} />}
                    ListHeaderComponent={
                        <View style={styles.header}>
                            <ScreenTitle>{t("history.title")}</ScreenTitle>
                            <Text style={[styles.body, {color: colors.textMuted}]}>{t("history.intro")}</Text>
                        </View>
                    }
                    ListEmptyComponent={<Text style={[styles.body, {color: colors.textMuted}]}>{t("history.empty")}</Text>}
                    renderItem={({item}) => <ReceiptRow row={item} />}
                />
            )
            break
    }
    return <SafeAreaView style={[styles.container, {backgroundColor: colors.bg}]}>{body}</SafeAreaView>
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
})
