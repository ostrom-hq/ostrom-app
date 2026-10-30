import {useTranslation} from "react-i18next"
import {ActivityIndicator, Platform, ScrollView, StyleSheet, Text, View} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import type {Approval, CheckState} from "@/api/contract/approvals"
import {Button} from "@/ui/components/Button"
import {Field, Section} from "@/ui/components/Section"
import {StatusBadge} from "@/ui/components/StatusBadge"
import {TextField} from "@/ui/components/TextField"
import {formatDateTime} from "@/ui/format/dateTime"
import {hasText} from "@/ui/format/presence"
import type {ThemeColors} from "@/ui/theme/theme"
import {fontSize, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {type RequestViewModel, useRequestViewModel} from "./useRequestViewModel"

const MONOSPACE = Platform.select({ios: "Menlo", default: "monospace"})

function checkColor(state: CheckState, colors: ThemeColors): string {
    switch (state) {
        case "success":
            return colors.success
        case "failure":
            return colors.danger
        case "pending":
            return colors.textMuted
        case "skipped":
        case "unknown":
            return colors.textSubtle
    }
}

/** The exact commits the yes covers: their titles when the server gives them, else their SHAs. */
function commitsOf(approval: Approval): readonly {readonly sha: string; readonly title: string | undefined; readonly author: string | undefined}[] {
    const details = approval.commitDetails ?? []
    if (details.length > 0) return details.map(({sha, title, author}) => ({sha, title, author}))
    return (approval.commits ?? []).map((sha) => ({sha, title: undefined, author: undefined}))
}

/** Everything the person reads before any button: who asks, where, the exact commits and checks, the evidence. */
function Evidence({approval}: {approval: Approval}): React.JSX.Element {
    const {t, i18n} = useTranslation()
    const {colors} = useTheme()
    const askedBy = approval.askedBy ?? []
    const commits = commitsOf(approval)
    const checks = approval.checks ?? []
    const evidence = approval.evidence ?? []
    return (
        <>
            {askedBy.length === 0 ? null : (
                <Section title={t("request.askedBy")}>
                    <Field label={t("request.people")} value={askedBy.join(", ")} />
                </Section>
            )}
            <Section title={t("request.target")}>
                {!hasText(approval.repo) ? null : <Field label={t("request.repository")} value={approval.repo} />}
                {!hasText(approval.sha) ? null : <Field label={t("request.head")} value={approval.sha.slice(0, 7)} />}
                <Field label={t("request.approver")} value={approval.requestedFrom} />
                {!hasText(approval.expiresAt) ? null : (
                    <Field label={t("request.expires")} value={formatDateTime(approval.expiresAt, i18n.language)} />
                )}
            </Section>
            {commits.length === 0 ? null : (
                <Section title={t("request.commits")}>
                    {commits.map((commit, index) => (
                        <View key={`${String(index)}:${commit.sha}`} style={styles.line}>
                            <Text style={[styles.sha, {color: colors.textMuted, fontFamily: MONOSPACE}]}>{commit.sha.slice(0, 7)}</Text>
                            {commit.title === undefined ? null : <Text style={[styles.lineText, {color: colors.text}]}>{commit.title}</Text>}
                            {commit.author === undefined ? null : (
                                <Text style={[styles.meta, {color: colors.textMuted}]}>{commit.author}</Text>
                            )}
                        </View>
                    ))}
                </Section>
            )}
            {checks.length === 0 ? null : (
                <Section title={t("request.checks")}>
                    {/* Two checks may share a name (a matrix, two workflows): each one is shown. */}
                    {checks.map((check, index) => (
                        <View key={`${String(index)}:${check.name}`} style={styles.line}>
                            <Text style={[styles.lineText, {color: colors.text}]}>{check.name}</Text>
                            <Text style={[styles.state, {color: checkColor(check.state, colors)}]}>{t(`request.checkState.${check.state}`)}</Text>
                        </View>
                    ))}
                </Section>
            )}
            {evidence.length === 0 ? null : (
                <Section title={t("request.evidence")}>
                    {evidence.map((item, index) => (
                        <Field key={`${String(index)}:${item.label}`} label={item.label} value={item.value} />
                    ))}
                </Section>
            )}
            <Section title={t("request.technical")}>
                <Text selectable style={[styles.code, {color: colors.textMuted, fontFamily: MONOSPACE}]}>
                    {[approval.id, approval.sha, approval.shownDigest].filter(hasText).join("\n")}
                </Text>
            </Section>
        </>
    )
}

/** Who decided and when, once the request is no longer waiting. */
function Outcome({approval}: {approval: Approval}): React.JSX.Element | null {
    const {t, i18n} = useTranslation()
    const {colors} = useTheme()
    if (approval.status === "expired") {
        const text = hasText(approval.expiresAt)
            ? t("request.expiredAt", {at: formatDateTime(approval.expiresAt, i18n.language)})
            : t("request.expired")
        return <Text style={[styles.body, {color: colors.textMuted}]}>{text}</Text>
    }
    if (approval.status === "pending" || !hasText(approval.decidedBy)) return null
    const who = approval.decidedBy
    const approved = approval.status === "approved"
    const line = hasText(approval.decidedAt)
        ? t(approved ? "request.approvedByAt" : "request.declinedByAt", {who, at: formatDateTime(approval.decidedAt, i18n.language)})
        : t(approved ? "request.approvedBy" : "request.declinedBy", {who})
    return (
        <Text style={[styles.body, {color: colors.textMuted}]}>
            {line}
            {approval.selfApproved === true ? ` · ${t("request.selfApproved")}` : ""}
        </Text>
    )
}

/** The buttons, after the evidence: Approve, or Decline… which asks for the reason the agent will read. */
function Actions({model}: {model: RequestViewModel}): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const {askedByMe, sending, problem, changed, declining, reason} = model
    return (
        <View style={[styles.actions, {borderTopColor: colors.border}]}>
            {askedByMe ? <Text style={[styles.body, {color: colors.text}]}>{t("request.askedByMe")}</Text> : null}
            {problem === undefined ? null : (
                <Text accessibilityRole="alert" style={[styles.body, {color: colors.danger}]}>
                    {t(`request.problems.${problem}`)}
                </Text>
            )}
            {changed ? (
                <Button label={t("request.reread")} variant="signal" onPress={model.reread} testID="request-reread" />
            ) : declining ? (
                <>
                    <TextField label={t("request.reason")} value={reason} onChangeText={model.setReason} testID="request-reason" />
                    <View style={styles.buttons}>
                        <View style={styles.button}>
                            <Button label={t("request.back")} onPress={model.cancelDecline} disabled={sending !== undefined} />
                        </View>
                        <View style={styles.button}>
                            <Button
                                label={t("request.sendDecline")}
                                variant="danger"
                                onPress={model.decline}
                                disabled={reason.trim() === ""}
                                busy={sending === "reject"}
                                testID="request-send-decline"
                            />
                        </View>
                    </View>
                </>
            ) : (
                <View style={styles.buttons}>
                    <View style={styles.button}>
                        <Button
                            label={t("request.decline")}
                            variant="danger"
                            onPress={model.startDecline}
                            disabled={sending !== undefined}
                            testID="request-decline"
                        />
                    </View>
                    <View style={styles.button}>
                        <Button
                            label={t("request.approve")}
                            variant="signal"
                            onPress={model.approve}
                            busy={sending === "approve"}
                            testID="request-approve"
                        />
                    </View>
                </View>
            )}
        </View>
    )
}

export function RequestScreen({id}: {id: string}): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const model = useRequestViewModel(id)
    const {state} = model

    if (state.kind === "loading") return <ActivityIndicator accessibilityLabel={t("request.title")} style={styles.center} />
    if (state.kind === "error") {
        return (
            <View style={styles.message}>
                <Text style={[styles.body, {color: colors.textMuted}]}>{t("request.error")}</Text>
                <Button label={t("common.retry")} onPress={state.retry} />
            </View>
        )
    }

    const {approval} = state
    return (
        <SafeAreaView edges={["bottom"]} style={[styles.container, {backgroundColor: colors.bg}]}>
            <ScrollView contentContainerStyle={styles.content} testID="request-scroll">
                <View style={styles.head}>
                    <StatusBadge status={approval.status} />
                    <Text accessibilityRole="header" style={[styles.title, {color: colors.text}]}>
                        {approval.title}
                    </Text>
                    <Outcome approval={approval} />
                </View>
                <Evidence approval={approval} />
                {model.open ? <Actions model={model} /> : null}
            </ScrollView>
        </SafeAreaView>
    )
}

const styles = StyleSheet.create({
    container: {flex: 1},
    center: {flex: 1},
    content: {padding: space[4], gap: space[4]},
    message: {padding: space[4], gap: space[3]},
    head: {gap: space[2]},
    title: {fontSize: fontSize[20], fontWeight: "800"},
    body: {fontSize: fontSize[14]},
    line: {flexDirection: "row", gap: space[2], alignItems: "baseline", flexWrap: "wrap"},
    lineText: {fontSize: fontSize[14], flexShrink: 1},
    meta: {fontSize: fontSize[12]},
    sha: {fontSize: fontSize[12]},
    state: {fontSize: fontSize[12], fontWeight: "700", marginLeft: "auto"},
    code: {fontSize: fontSize[12]},
    actions: {borderTopWidth: 1, paddingTop: space[4], gap: space[3]},
    buttons: {flexDirection: "row", gap: space[3]},
    button: {flex: 1},
})
