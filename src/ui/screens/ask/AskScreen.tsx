import {useTranslation} from "react-i18next"
import {ActivityIndicator, Platform, ScrollView, StyleSheet, Text, View} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import type {Ask, CheckState} from "@/api/contract/yes"
import {Button} from "@/ui/components/Button"
import {Field, Section} from "@/ui/components/Section"
import {hasText, isPresent} from "@/ui/format/presence"
import {StatusBadge} from "@/ui/components/StatusBadge"
import type {ThemeColors} from "@/ui/theme/theme"
import {fontSize, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {useAskViewModel} from "./useAskViewModel"

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

/** Everything the person reads before deciding: who asks, what it does, the exact commits and checks. */
function AskEvidence({ask}: {ask: Ask}): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const who: readonly (readonly [string, string | null | undefined])[] = [
        [t("ask.developer"), ask.who.dev],
        [t("ask.agent"), ask.who.agent],
        [t("ask.model"), ask.who.model],
        [t("ask.session"), ask.who.session],
    ]
    const what: readonly (readonly [string, string | null | undefined])[] = [
        [t("ask.action"), ask.what.action],
        [t("ask.repository"), ask.what.repo ?? ask.what.sessionRepo],
        [t("ask.pullRequest"), !isPresent(ask.pr) ? undefined : `#${ask.pr.number} · ${ask.pr.sha.slice(0, 7)}`],
    ]
    const fields = (rows: typeof who): React.JSX.Element[] =>
        rows.flatMap(([label, value]) => (!hasText(value) ? [] : [<Field key={label} label={label} value={value} />]))
    return (
        <>
            <Section title={t("ask.askedBy")}>{fields(who)}</Section>
            <Section title={t("ask.target")}>
                {fields(what)}
                {!hasText(ask.what.command) ? null : (
                    <Text selectable style={[styles.code, {color: colors.text, fontFamily: MONOSPACE}]}>
                        {ask.what.command}
                    </Text>
                )}
            </Section>
            {ask.commits.length === 0 ? null : (
                <Section title={t("ask.commits")}>
                    {ask.commits.map((commit) => (
                        <View key={commit.sha} style={styles.line}>
                            <Text style={[styles.sha, {color: colors.textMuted, fontFamily: MONOSPACE}]}>{commit.sha.slice(0, 7)}</Text>
                            <Text style={[styles.lineText, {color: colors.text}]}>{commit.title}</Text>
                        </View>
                    ))}
                </Section>
            )}
            {ask.checks.length === 0 ? null : (
                <Section title={t("ask.checks")}>
                    {ask.checks.map((check) => (
                        <View key={check.name} style={styles.line}>
                            <Text style={[styles.lineText, {color: colors.text}]}>{check.name}</Text>
                            <Text style={[styles.state, {color: checkColor(check.state, colors)}]}>{t(`ask.checkState.${check.state}`)}</Text>
                        </View>
                    ))}
                </Section>
            )}
            {!isPresent(ask.gate) ? null : (
                <Section title={t("ask.gate")}>
                    <Field label={t("ask.gate")} value={ask.gate.label} />
                    {ask.gate.approvers.length === 0 ? null : <Field label={t("ask.approvers")} value={ask.gate.approvers.join(", ")} />}
                </Section>
            )}
            {ask.rules.length === 0 ? null : (
                <Section title={t("ask.rules")}>
                    {ask.rules.map((rule) => (
                        <Text key={rule.id} style={[styles.lineText, {color: colors.text}]}>
                            {rule.statement}
                        </Text>
                    ))}
                </Section>
            )}
            {ask.evidence.length === 0 ? null : (
                <Section title={t("ask.evidence")}>
                    {ask.evidence.map((item) => (
                        <Field key={`${item.label}:${item.value}`} label={item.label} value={item.value} />
                    ))}
                </Section>
            )}
            {!hasText(ask.diff) ? null : (
                <Section title={t("ask.diff")}>
                    <ScrollView horizontal>
                        <Text selectable style={[styles.code, {color: colors.text, fontFamily: MONOSPACE}]}>
                            {ask.diff}
                        </Text>
                    </ScrollView>
                </Section>
            )}
            {!isPresent(ask.receipt) ? null : (
                <Section title={t("ask.receipt")}>
                    <Field label={t("ask.receipt")} value={ask.receipt.id} />
                    <Text style={[styles.lineText, {color: ask.receipt.valid ? colors.success : colors.danger}]}>
                        {t(ask.receipt.valid ? "ask.receiptValid" : "ask.receiptInvalid")}
                    </Text>
                </Section>
            )}
        </>
    )
}

export function AskScreen({id}: {id: string}): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const {state, canDecide, pending, problem, decide} = useAskViewModel(id)

    if (state.kind === "loading") return <ActivityIndicator accessibilityLabel={t("ask.title")} style={styles.center} />
    if (state.kind === "error") {
        return (
            <View style={styles.message}>
                <Text style={[styles.body, {color: colors.textMuted}]}>{t("ask.error")}</Text>
                <Button label={t("common.retry")} onPress={state.retry} />
            </View>
        )
    }

    const {ask} = state
    const cannot = ask.status !== "pending" || canDecide ? undefined : ask.you?.self === true ? t("ask.cannot.self") : t("ask.cannot.other")
    return (
        <SafeAreaView edges={["bottom"]} style={[styles.container, {backgroundColor: colors.bg}]}>
            <ScrollView contentContainerStyle={styles.content}>
                <View style={styles.head}>
                    <StatusBadge status={ask.status} />
                    <Text accessibilityRole="header" style={[styles.title, {color: colors.text}]}>
                        {ask.title}
                    </Text>
                    {!hasText(ask.decidedBy) ? null : (
                        <Text style={[styles.body, {color: colors.textMuted}]}>{t("ask.decidedBy", {who: ask.decidedBy})}</Text>
                    )}
                </View>
                <AskEvidence ask={ask} />
            </ScrollView>
            {ask.status === "pending" ? (
                <View style={[styles.actions, {borderTopColor: colors.border, backgroundColor: colors.surface}]}>
                    {problem === undefined ? null : (
                        <Text accessibilityRole="alert" style={[styles.body, {color: colors.danger}]}>
                            {t(`ask.problems.${problem}`)}
                        </Text>
                    )}
                    {cannot === undefined ? null : <Text style={[styles.body, {color: colors.textMuted}]}>{cannot}</Text>}
                    <View style={styles.buttons}>
                        <View style={styles.button}>
                            <Button
                                label={t("ask.reject")}
                                variant="danger"
                                onPress={() => {
                                    decide("rejected")
                                }}
                                disabled={!canDecide || pending === "approved"}
                                busy={pending === "rejected"}
                            />
                        </View>
                        <View style={styles.button}>
                            <Button
                                label={t("ask.approve")}
                                variant="signal"
                                onPress={() => {
                                    decide("approved")
                                }}
                                disabled={!canDecide || pending === "rejected"}
                                busy={pending === "approved"}
                            />
                        </View>
                    </View>
                </View>
            ) : null}
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
    line: {flexDirection: "row", gap: space[2], alignItems: "baseline"},
    lineText: {fontSize: fontSize[14], flexShrink: 1},
    sha: {fontSize: fontSize[12]},
    state: {fontSize: fontSize[12], fontWeight: "700", marginLeft: "auto"},
    code: {fontSize: fontSize[12]},
    actions: {borderTopWidth: 1, padding: space[4], gap: space[3]},
    buttons: {flexDirection: "row", gap: space[3]},
    button: {flex: 1},
})
