// SPDX-License-Identifier: AGPL-3.0-only
import {useRouter} from "expo-router"
import {useTranslation} from "react-i18next"
import {ActivityIndicator, FlatList, Pressable, RefreshControl, StyleSheet, Text, View} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import type {WaitFilter} from "@/api/contract/waits"
import {Button} from "@/ui/components/Button"
import {ScreenTitle} from "@/ui/components/ScreenTitle"
import {TAB_EDGES} from "@/ui/navigation/tabEdges"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {WaitCard} from "./WaitCard"
import {badgeText} from "./badgeText"
import {useWaitsViewModel} from "./useWaitsViewModel"

const FILTERS: readonly WaitFilter[] = ["open", "answered", "cancelled"]

/** "Waiting for you": the waits asked of the person, answered in place; answered and withdrawn ones are read from the filters. */
export function WaitsScreen(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const router = useRouter()
    const model = useWaitsViewModel()
    const {state, filter} = model
    const chooseOrganization = (): void => {
        router.navigate("/settings")
    }

    const header = (
        <View style={styles.header}>
            <View style={styles.titleLine}>
                <ScreenTitle>{t("waits.title")}</ScreenTitle>
                {model.badge === undefined ? null : (
                    <View
                        accessibilityLabel={t(model.badge.more ? "waits.badgeMore" : "waits.badge", {count: model.badge.count})}
                        style={[styles.badge, {backgroundColor: colors.signal}]}
                        testID="waits-badge"
                    >
                        <Text style={[styles.badgeText, {color: colors.onSignal}]}>{badgeText(model.badge)}</Text>
                    </View>
                )}
            </View>
            <View accessibilityRole="tablist" style={styles.filters}>
                {FILTERS.map((option) => {
                    const selected = option === filter
                    return (
                        <Pressable
                            key={option}
                            accessibilityRole="tab"
                            accessibilityLabel={t(`waits.filters.${option}`)}
                            accessibilityState={{selected}}
                            onPress={() => { model.setFilter(option) }}
                            style={[styles.filter, {borderColor: selected ? colors.text : colors.border, backgroundColor: selected ? colors.bgSubtle : colors.surface}]}
                            testID={`waits-filter-${option}`}
                        >
                            <Text style={[styles.filterLabel, {color: selected ? colors.text : colors.textMuted}]}>{t(`waits.filters.${option}`)}</Text>
                        </Pressable>
                    )
                })}
            </View>
            {model.closedNotice ? (
                <Pressable accessibilityRole="alert" onPress={model.dismissClosed} testID="waits-closed">
                    <Text style={[styles.notice, {color: colors.danger}]}>{t("waits.closed")}</Text>
                </Pressable>
            ) : null}
            {state.kind === "ready" && state.stale ? (
                <Text accessibilityRole="alert" style={[styles.notice, {color: colors.danger}]}>
                    {state.orgRequired ? `${t("waits.stale")}\n${t("common.orgRequired")}` : t("waits.stale")}
                </Text>
            ) : null}
            {state.kind === "ready" && state.orgRequired ? (
                <Button label={t("common.chooseOrganization")} onPress={chooseOrganization} testID="waits-choose-organization" />
            ) : null}
        </View>
    )

    let body: React.JSX.Element
    switch (state.kind) {
        case "loading":
            // The filters stay under the finger while another list loads.
            body = (
                <View style={styles.message}>
                    {header}
                    <ActivityIndicator accessibilityLabel={t("waits.title")} />
                </View>
            )
            break
        case "error":
            body = (
                <View style={styles.message}>
                    {header}
                    <Text style={[styles.body, {color: colors.textMuted}]}>{state.orgRequired ? t("common.orgRequired") : t("waits.error")}</Text>
                    {state.orgRequired ? <Button label={t("common.chooseOrganization")} variant="signal" onPress={chooseOrganization} testID="waits-choose-organization" /> : null}
                    <Button label={t("common.retry")} onPress={model.refresh} testID="waits-retry" />
                </View>
            )
            break
        case "ready":
            body = (
                <FlatList
                    data={state.rows}
                    testID="waits-list"
                    keyboardShouldPersistTaps="handled"
                    keyExtractor={(row) => row.wait.id}
                    contentContainerStyle={styles.list}
                    refreshControl={<RefreshControl refreshing={model.refreshing} onRefresh={model.refresh} tintColor={colors.signal} />}
                    ListHeaderComponent={header}
                    ListEmptyComponent={<Text style={[styles.body, {color: colors.textMuted}]}>{t(`waits.empty.${filter}`)}</Text>}
                    ListFooterComponent={
                        state.hasMore ? (
                            <Button label={t("waits.loadMore")} busy={state.loadingMore} onPress={model.loadMore} testID="waits-more" />
                        ) : undefined
                    }
                    renderItem={({item}) => (
                        <WaitCard
                            row={item}
                            onEdit={(fieldId, change) => { model.edit(item.wait.id, fieldId, change) }}
                            onSubmit={(actionId) => { model.submit(item.wait.id, actionId) }}
                            onOpenRef={model.openRef}
                        />
                    )}
                />
            )
            break
    }

    return <SafeAreaView edges={TAB_EDGES} style={[styles.container, {backgroundColor: colors.bg}]}>{body}</SafeAreaView>
}

const styles = StyleSheet.create({
    container: {flex: 1},
    message: {padding: space[4], gap: space[3]},
    list: {padding: space[4], gap: space[3]},
    header: {gap: space[2]},
    titleLine: {flexDirection: "row", alignItems: "center", gap: space[2]},
    badge: {borderRadius: radius.pill, paddingHorizontal: space[2], minHeight: 20, justifyContent: "center"},
    badgeText: {fontSize: fontSize[12], fontWeight: "700"},
    filters: {flexDirection: "row", gap: space[2]},
    filter: {flex: 1, minHeight: 40, borderWidth: 1, borderRadius: radius.md, alignItems: "center", justifyContent: "center"},
    filterLabel: {fontSize: fontSize[14], fontWeight: "600"},
    notice: {fontSize: fontSize[14]},
    body: {fontSize: fontSize[16]},
})
