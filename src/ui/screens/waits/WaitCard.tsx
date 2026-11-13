// SPDX-License-Identifier: AGPL-3.0-only
import {useTranslation} from "react-i18next"
import {Pressable, StyleSheet, Text, View} from "react-native"
import {Button, type ButtonVariant} from "@/ui/components/Button"
import {formatDateTime} from "@/ui/format/dateTime"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import type {FieldDraft} from "@/domain/waits/waitAnswer"
import type {WaitRef} from "@/api/contract/waits"
import {WaitFieldInput} from "./WaitFieldInput"
import type {ActionStyle, WaitRowModel} from "./useWaitsViewModel"

interface WaitCardProps {
    readonly row: WaitRowModel
    readonly onEdit: (fieldId: string, change: Partial<FieldDraft>) => void
    readonly onSubmit: (actionId: string) => void
    readonly onOpenRef: (url: string) => void
}

/** The button of each action style: the primary action is the one garnet button of the card. */
const VARIANT: Readonly<Record<ActionStyle, ButtonVariant>> = {primary: "signal", secondary: "neutral", danger: "danger"}

function RefLine({reference, onOpen}: {reference: WaitRef; onOpen: (url: string) => void}): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const label = reference.title ?? t(`waits.refs.${reference.kind === "session" || reference.kind === "artifact" ? reference.kind : "url"}`, {id: reference.id ?? ""})
    // Only a link opens, and only an https one: the server accepts nothing else, and nothing else leaves the app.
    const url = reference.url
    if (url?.startsWith("https://") !== true) return <Text style={[styles.meta, {color: colors.textMuted}]}>{label}</Text>
    return (
        <Pressable accessibilityRole="link" accessibilityLabel={t("waits.openRef", {title: label})} onPress={() => { onOpen(url) }}>
            <Text style={[styles.link, {color: colors.signalText}]}>{label}</Text>
        </Pressable>
    )
}

/** One wait: its question, its typed fields filled in place, and a button per action; once closed, what was answered. */
export function WaitCard({row, onEdit, onSubmit, onOpenRef}: WaitCardProps): React.JSX.Element {
    const {t, i18n} = useTranslation()
    const {colors} = useTheme()
    const {wait} = row
    const locale = i18n.language
    const at = formatDateTime(wait.createdAt, locale)
    const accent = row.open ? colors.signal : colors.border
    return (
        <View
            style={[styles.card, {backgroundColor: colors.surface, borderColor: colors.border, borderLeftColor: accent}]}
            testID={`wait-${wait.id}`}
        >
            <Text accessibilityRole="header" style={[styles.title, {color: colors.text}]}>{row.title}</Text>
            <Text style={[styles.meta, {color: colors.textMuted}]}>
                {wait.createdBy === undefined ? t("waits.asked", {at}) : t("waits.askedBy", {at, who: wait.createdBy})}
            </Text>
            {wait.question.body === undefined ? null : <Text selectable style={[styles.body, {color: colors.text}]}>{wait.question.body}</Text>}
            {(wait.question.refs ?? []).map((reference, index) => (
                <RefLine key={`${reference.kind}-${String(index)}`} reference={reference} onOpen={onOpenRef} />
            ))}
            {row.open
                ? row.fields.map((model) => (
                      <WaitFieldInput
                          key={model.field.id}
                          model={model}
                          testID={`wait-${wait.id}-${model.field.id}`}
                          onChange={(change) => { onEdit(model.field.id, change) }}
                      />
                  ))
                : null}
            {row.open ? (
                <View style={styles.actions}>
                    {row.actions.map((action) => (
                        <Button
                            key={action.id}
                            label={action.label}
                            variant={VARIANT[action.style]}
                            disabled={action.blocked || row.sending}
                            busy={action.busy}
                            onPress={() => { onSubmit(action.id) }}
                            testID={`wait-${wait.id}-action-${action.id}`}
                        />
                    ))}
                </View>
            ) : null}
            {row.open && row.console ? <Text style={[styles.meta, {color: colors.textMuted}]}>{t("waits.console")}</Text> : null}
            {row.unknownStatus ? <Text style={[styles.meta, {color: colors.textMuted}]}>{t("waits.closedUnknown")}</Text> : null}
            {row.answered === undefined ? null : (
                <Text style={[styles.meta, {color: colors.textMuted}]}>
                    {`${row.answered.label} · ${t("waits.answeredBy", {who: row.answered.author, at: formatDateTime(row.answered.at, locale)})}`}
                </Text>
            )}
            {wait.status === "cancelled" && wait.resolvedAt !== undefined ? (
                <Text style={[styles.meta, {color: colors.textMuted}]}>{t("waits.cancelledAt", {at: formatDateTime(wait.resolvedAt, locale)})}</Text>
            ) : null}
            {row.problem === undefined ? null : (
                <Text accessibilityRole="alert" style={[styles.problem, {color: colors.danger}]}>{t(`waits.problems.${row.problem}`)}</Text>
            )}
        </View>
    )
}

const styles = StyleSheet.create({
    card: {borderWidth: 1, borderLeftWidth: 4, borderRadius: radius.lg, padding: space[3], gap: space[3]},
    title: {fontSize: fontSize[16], fontWeight: "700"},
    meta: {fontSize: fontSize[12]},
    body: {fontSize: fontSize[14]},
    link: {fontSize: fontSize[14], fontWeight: "600"},
    actions: {gap: space[2]},
    problem: {fontSize: fontSize[14]},
})
