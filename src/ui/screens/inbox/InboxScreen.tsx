// SPDX-License-Identifier: AGPL-3.0-only
import {useRouter} from "expo-router"
import {useTranslation} from "react-i18next"
import {ActivityIndicator, FlatList, Pressable, RefreshControl, StyleSheet, Text, View} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import {Button} from "@/ui/components/Button"
import {ScreenTitle} from "@/ui/components/ScreenTitle"
import {StatusBadge} from "@/ui/components/StatusBadge"
import {TAB_EDGES} from "@/ui/navigation/tabEdges"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {type RequestRowModel, useInboxViewModel} from "./useInboxViewModel"

function RequestRow({row, onOpen}: {row: RequestRowModel; onOpen: (id: string) => void}): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    // A pending request waits for a human: its edge is garnet, the only accent of the list.
    const accent = row.status === "pending" ? colors.signal : colors.border
    return (
        <Pressable
            accessibilityRole="button"
            accessibilityLabel={row.title}
            onPress={() => {
                onOpen(row.id)
            }}
            style={[styles.row, {backgroundColor: colors.surface, borderColor: colors.border, borderLeftColor: accent}]}
            testID={`request-${row.id}`}
        >
            <View style={styles.rowHead}>
                <StatusBadge status={row.status} />
                {row.yours ? <Text style={[styles.yours, {color: colors.textMuted}]}>{t("inbox.yours")}</Text> : null}
            </View>
            <Text style={[styles.rowTitle, {color: colors.text}]}>{row.title}</Text>
            {row.detail === "" ? null : <Text style={[styles.rowDetail, {color: colors.textMuted}]}>{row.detail}</Text>}
        </Pressable>
    )
}

export function InboxScreen(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const router = useRouter()
    const {state, refreshing, refresh} = useInboxViewModel()
    const open = (id: string): void => {
        router.push(`/request/${encodeURIComponent(id)}`)
    }

    let body: React.JSX.Element
    switch (state.kind) {
        case "loading":
            body = <ActivityIndicator accessibilityLabel={t("inbox.title")} style={styles.center} />
            break
        case "error":
            body = (
                <View style={styles.message}>
                    <Text style={[styles.body, {color: colors.textMuted}]}>{state.orgRequired ? t("common.orgRequired") : t("inbox.error")}</Text>
                    <Button label={t("common.retry")} onPress={refresh} />
                </View>
            )
            break
        case "ready":
            body = (
                <FlatList
                    data={state.rows}
                    testID="inbox-list"
                    keyExtractor={(row) => row.id}
                    contentContainerStyle={styles.list}
                    refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={colors.signal} />}
                    ListHeaderComponent={
                        <View style={styles.header}>
                            <ScreenTitle>{t("inbox.title")}</ScreenTitle>
                            {state.stale ? (
                                <Text accessibilityRole="alert" style={[styles.stale, {color: colors.danger}]}>
                                    {state.orgRequired ? t("common.orgRequired") : t("inbox.stale")}
                                </Text>
                            ) : null}
                        </View>
                    }
                    ListEmptyComponent={<Text style={[styles.body, {color: colors.textMuted}]}>{t("inbox.empty")}</Text>}
                    // What was answered lately comes after what waits, under its own heading: it only opens.
                    ListFooterComponent={
                        state.recent.length === 0 ? undefined : (
                            <View style={styles.recent}>
                                <Text accessibilityRole="header" style={[styles.recentTitle, {color: colors.textMuted}]}>
                                    {t("inbox.recent")}
                                </Text>
                                {state.recent.map((row) => (
                                    <RequestRow key={row.id} row={row} onOpen={open} />
                                ))}
                            </View>
                        )
                    }
                    renderItem={({item}) => <RequestRow row={item} onOpen={open} />}
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
    header: {gap: space[2]},
    recent: {gap: space[3], marginTop: space[3]},
    recentTitle: {fontSize: fontSize[14], fontWeight: "700"},
    stale: {fontSize: fontSize[14]},
    body: {fontSize: fontSize[16]},
    row: {borderWidth: 1, borderLeftWidth: 4, borderRadius: radius.lg, padding: space[3], gap: space[2]},
    rowHead: {flexDirection: "row", alignItems: "center", gap: space[2]},
    yours: {fontSize: fontSize[12]},
    rowTitle: {fontSize: fontSize[16], fontWeight: "700"},
    rowDetail: {fontSize: fontSize[14]},
})
