import {useRouter} from "expo-router"
import {useTranslation} from "react-i18next"
import {ActivityIndicator, FlatList, Pressable, RefreshControl, StyleSheet, Text, View} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import type {ActivityItem} from "@/api/contract/activity"
import {Button} from "@/ui/components/Button"
import {ScreenTitle} from "@/ui/components/ScreenTitle"
import {formatDateTime} from "@/ui/format/dateTime"
import {formatUsd} from "@/ui/format/money"
import {hasText, isPresent} from "@/ui/format/presence"
import {TAB_EDGES} from "@/ui/navigation/tabEdges"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {useActivityViewModel} from "./useActivityViewModel"

function ActivityRow({item, onOpen}: {item: ActivityItem; onOpen: (approvalId: string) => void}): React.JSX.Element {
    const {t, i18n} = useTranslation()
    const {colors} = useTheme()
    const details = [
        // An agent is named as one; a person (or a kind this app does not know) by their name.
        item.actor.kind === "agent" ? t("activity.actor.agent", {name: item.actor.name}) : item.actor.name,
        hasText(item.repo) ? item.repo : undefined,
        isPresent(item.costUsd) ? formatUsd(item.costUsd, i18n.language) : undefined,
        formatDateTime(item.at, i18n.language),
    ].filter((part) => part !== undefined)
    const {approvalId} = item
    const content = (
        <>
            <Text style={[styles.summary, {color: colors.text}]}>{item.summary}</Text>
            <Text style={[styles.detail, {color: colors.textMuted}]}>{details.join(" · ")}</Text>
        </>
    )
    const style = [styles.row, {backgroundColor: colors.surface, borderColor: colors.border}]
    if (!hasText(approvalId)) return <View style={style}>{content}</View>
    return (
        <Pressable
            accessibilityRole="button"
            accessibilityLabel={item.summary}
            onPress={() => {
                onOpen(approvalId)
            }}
            style={style}
        >
            {content}
        </Pressable>
    )
}

export function ActivityScreen(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const router = useRouter()
    const {state, refreshing, refresh, loadMore} = useActivityViewModel()
    const open = (approvalId: string): void => {
        router.push(`/request/${encodeURIComponent(approvalId)}`)
    }

    let body: React.JSX.Element
    switch (state.kind) {
        case "loading":
            body = <ActivityIndicator accessibilityLabel={t("activity.title")} style={styles.center} />
            break
        case "error":
            body = (
                <View style={styles.message}>
                    <Text style={[styles.body, {color: colors.textMuted}]}>{t("activity.error")}</Text>
                    <Button label={t("common.retry")} onPress={refresh} />
                </View>
            )
            break
        case "ready":
            body = (
                <FlatList
                    data={state.items}
                    keyExtractor={(item) => item.id}
                    contentContainerStyle={styles.list}
                    refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={colors.signal} />}
                    ListHeaderComponent={<ScreenTitle>{t("activity.title")}</ScreenTitle>}
                    ListEmptyComponent={<Text style={[styles.body, {color: colors.textMuted}]}>{t("activity.empty")}</Text>}
                    ListFooterComponent={
                        state.hasMore ? (
                            <Button label={t("activity.more")} onPress={loadMore} busy={state.loadingMore} />
                        ) : undefined
                    }
                    renderItem={({item}) => <ActivityRow item={item} onOpen={open} />}
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
    body: {fontSize: fontSize[14]},
    row: {borderWidth: 1, borderRadius: radius.lg, padding: space[3], gap: space[1]},
    summary: {fontSize: fontSize[16], fontWeight: "600"},
    detail: {fontSize: fontSize[14]},
})
