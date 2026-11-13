// SPDX-License-Identifier: AGPL-3.0-only
import {useTranslation} from "react-i18next"
import {Pressable, StyleSheet, Switch, Text, View} from "react-native"
import {TextField} from "@/ui/components/TextField"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import type {FieldDraft} from "@/domain/waits/waitAnswer"
import type {FieldModel} from "./useWaitsViewModel"

interface WaitFieldInputProps {
    readonly model: FieldModel
    readonly testID: string
    readonly onChange: (change: Partial<FieldDraft>) => void
}

/** The text under a field that is not accepted. */
function useErrorText(error: FieldModel["error"]): string | undefined {
    const {t} = useTranslation()
    if (error === undefined) return undefined
    return error.kind === "too-long" ? t("waits.errors.too-long", {max: error.max}) : t(`waits.errors.${error.kind}`)
}

function ChoiceOption({label, picked, multiple, testID, onPress}: {label: string; picked: boolean; multiple: boolean; testID: string; onPress: () => void}): React.JSX.Element {
    const {colors} = useTheme()
    return (
        <Pressable
            accessibilityRole={multiple ? "checkbox" : "radio"}
            accessibilityLabel={label}
            accessibilityState={{checked: picked}}
            onPress={onPress}
            style={[styles.option, {borderColor: picked ? colors.signal : colors.border, backgroundColor: picked ? colors.signalTint : colors.surface}]}
            testID={testID}
        >
            <Text style={[styles.optionLabel, {color: picked ? colors.signalText : colors.text}]}>{label}</Text>
        </Pressable>
    )
}

/** One typed field of a wait: text, choice, confirm, number, date, or the help of an info field. Others are not drawn. */
export function WaitFieldInput({model, testID, onChange}: WaitFieldInputProps): React.JSX.Element | null {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const {field, kind, draft} = model
    const error = useErrorText(model.error)
    const label = field.required === true ? field.label : `${field.label} (${t("waits.optional")})`
    const help = field.help === undefined ? null : <Text style={[styles.help, {color: colors.textMuted}]}>{field.help}</Text>
    switch (kind) {
        case "text":
            return (
                <View style={styles.field}>
                    <TextField label={label} value={draft.text} onChangeText={(text) => { onChange({text}) }} error={error} testID={testID} />
                    {help}
                </View>
            )
        case "number":
            return (
                <View style={styles.field}>
                    <TextField label={label} value={draft.text} keyboardType="decimal-pad" onChangeText={(text) => { onChange({text}) }} error={error} testID={testID} />
                    {help}
                </View>
            )
        case "date":
            return (
                <View style={styles.field}>
                    <TextField label={label} value={draft.text} placeholder={t("waits.dateHint")} onChangeText={(text) => { onChange({text}) }} error={error} testID={testID} />
                    {help}
                </View>
            )
        case "confirm":
            return (
                <View style={styles.field}>
                    <View style={styles.confirm}>
                        <Text style={[styles.label, {color: colors.text}]}>{field.label}</Text>
                        <Switch
                            accessibilityLabel={field.label}
                            value={draft.checked}
                            onValueChange={(checked) => { onChange({checked}) }}
                            testID={testID}
                        />
                    </View>
                    {help}
                </View>
            )
        case "choice": {
            const multiple = field.multiple === true
            const toggle = (option: string): void => {
                if (multiple) {
                    onChange({picked: draft.picked.includes(option) ? draft.picked.filter((item) => item !== option) : [...draft.picked, option]})
                } else {
                    // One answer: a picked option replaces a free one, and the same one again takes it back.
                    onChange({picked: draft.picked.includes(option) ? [] : [option], other: ""})
                }
            }
            return (
                <View style={styles.field}>
                    <Text style={[styles.label, {color: colors.text}]}>{label}</Text>
                    {help}
                    {(field.options ?? []).map((option) => (
                        <ChoiceOption
                            key={option}
                            label={option}
                            picked={draft.picked.includes(option)}
                            multiple={multiple}
                            testID={`${testID}-${option}`}
                            onPress={() => { toggle(option) }}
                        />
                    ))}
                    {field.allowOther === true ? (
                        <TextField
                            label={t("waits.other")}
                            value={draft.other}
                            onChangeText={(other) => { onChange(multiple ? {other} : {other, picked: []}) }}
                            error={error}
                            testID={`${testID}-other`}
                        />
                    ) : error === undefined ? null : (
                        <Text style={[styles.error, {color: colors.danger}]}>{error}</Text>
                    )}
                </View>
            )
        }
        case "info":
            return (
                <View style={styles.field}>
                    <Text style={[styles.label, {color: colors.text}]}>{field.label}</Text>
                    {help}
                </View>
            )
        case "unsupported":
            return null
    }
}

const styles = StyleSheet.create({
    field: {gap: space[2]},
    label: {fontSize: fontSize[14], fontWeight: "600"},
    help: {fontSize: fontSize[14]},
    error: {fontSize: fontSize[14]},
    confirm: {flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: space[3]},
    option: {minHeight: 44, borderWidth: 1, borderRadius: radius.md, paddingHorizontal: space[3], justifyContent: "center"},
    optionLabel: {fontSize: fontSize[16]},
})
