import {useRouter} from "expo-router"
import {useTranslation} from "react-i18next"
import {
    ActivityIndicator,
    Pressable,
    RefreshControl,
    SectionList,
    type SectionListData,
    StyleSheet,
    Text,
    View,
} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import {Button} from "@/ui/components/Button"
import {ScreenTitle} from "@/ui/components/ScreenTitle"
import {StatusBadge} from "@/ui/components/StatusBadge"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {type AskRowModel, useInboxViewModel} from "./useInboxViewModel"

interface InboxSection {
    readonly key: "pending" | "recent"
    readonly title: string
}

function AskRow({row, onOpen}: {row: AskRowModel; onOpen: (id: string) => void}): React.JSX.Element {
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
            testID={`ask-${row.id}`}
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
        router.push(`/ask/${encodeURIComponent(id)}`)
    }

    let body: React.JSX.Element
    switch (state.kind) {
        case "loading":
            body = <ActivityIndicator accessibilityLabel={t("inbox.title")} style={styles.center} />
            break
        case "error":
            body = (
                <View style={styles.message}>
                    <Text style={[styles.body, {color: colors.textMuted}]}>{t("inbox.error")}</Text>
                    <Button label={t("common.retry")} onPress={refresh} />
                </View>
            )
            break
        case "ready": {
            const pending: SectionListData<AskRowModel, InboxSection> = {key: "pending", title: t("inbox.title"), data: state.pending}
            const recent: SectionListData<AskRowModel, InboxSection> = {key: "recent", title: t("inbox.recent"), data: state.recent}
            const sections = state.recent.length > 0 ? [pending, recent] : [pending]
            body = (
                <SectionList<AskRowModel, InboxSection>
                    sections={sections}
                    testID="inbox-list"
                    keyExtractor={(row) => row.id}
                    contentContainerStyle={styles.list}
                    refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={colors.signal} />}
                    renderSectionHeader={({section}) =>
                        section.key === "pending" ? (
                            <View style={styles.header}>
                                <ScreenTitle>{section.title}</ScreenTitle>
                                {state.pending.length === 0 ? (
                                    <Text style={[styles.body, {color: colors.textMuted}]}>{t("inbox.empty")}</Text>
                                ) : null}
                            </View>
                        ) : (
                            <Text accessibilityRole="header" style={[styles.sectionTitle, {color: colors.textMuted}]}>
                                {section.title}
                            </Text>
                        )
                    }
                    renderItem={({item}) => <AskRow row={item} onOpen={open} />}
                    stickySectionHeadersEnabled={false}
                />
            )
            break
        }
    }

    return <SafeAreaView style={[styles.container, {backgroundColor: colors.bg}]}>{body}</SafeAreaView>
}

const styles = StyleSheet.create({
    container: {flex: 1},
    center: {flex: 1},
    message: {padding: space[4], gap: space[3]},
    list: {padding: space[4], gap: space[3]},
    header: {gap: space[2], marginBottom: space[2]},
    body: {fontSize: fontSize[16]},
    sectionTitle: {fontSize: fontSize[12], fontWeight: "700", letterSpacing: 0.96, textTransform: "uppercase", marginTop: space[4]},
    row: {borderWidth: 1, borderLeftWidth: 4, borderRadius: radius.lg, padding: space[3], gap: space[2]},
    rowHead: {flexDirection: "row", alignItems: "center", gap: space[2]},
    yours: {fontSize: fontSize[12]},
    rowTitle: {fontSize: fontSize[16], fontWeight: "700"},
    rowDetail: {fontSize: fontSize[14]},
})
