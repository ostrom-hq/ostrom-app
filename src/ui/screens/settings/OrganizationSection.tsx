// SPDX-License-Identifier: AGPL-3.0-only
import {useTranslation} from "react-i18next"
import {ActivityIndicator, Pressable, StyleSheet, Text, View} from "react-native"
import type {MyOrganization} from "@/api/contract/organizations"
import {Button} from "@/ui/components/Button"
import {Field, Section} from "@/ui/components/Section"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {useOrganizationChoice} from "./useOrganizationChoice"

interface OrganizationRowProps {
    readonly organization: MyOrganization
    readonly checked: boolean
    /** This row's organization is being switched to. */
    readonly switching: boolean
    /** Any switch is under way: no second one meanwhile. */
    readonly busy: boolean
    readonly onChoose: (id: string) => void
}

/** One organization to choose; the whole row is the control, read as a radio button by screen readers. */
function OrganizationRow({organization, checked, switching, busy, onChoose}: OrganizationRowProps): React.JSX.Element {
    const {colors} = useTheme()
    return (
        <Pressable
            accessibilityRole="radio"
            accessibilityLabel={organization.name}
            accessibilityState={{checked, disabled: busy}}
            disabled={busy}
            onPress={() => {
                onChoose(organization.id)
            }}
            style={styles.row}
            testID={`organization-${organization.id}`}
        >
            <Text style={[styles.name, {color: colors.text}]}>{organization.name}</Text>
            {switching ? (
                <ActivityIndicator />
            ) : (
                <View style={[styles.radio, {borderColor: checked ? colors.text : colors.borderStrong}]}>
                    {checked ? <View style={[styles.dot, {backgroundColor: colors.text}]} /> : null}
                </View>
            )}
        </Pressable>
    )
}

/** Settings: the organization the app acts in, and the choice of another when the person is in several. */
export function OrganizationSection(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const choice = useOrganizationChoice()
    const {state} = choice

    let body: React.JSX.Element
    if (state.kind === "loading") {
        body = <ActivityIndicator accessibilityLabel={t("settings.organization")} />
    } else if (state.kind === "error") {
        body = (
            <>
                <Text style={[styles.body, {color: colors.textMuted}]}>{t("settings.organizationsError")}</Text>
                <Button label={t("common.retry")} onPress={choice.retry} />
            </>
        )
    } else {
        const [only, ...others] = state.organizations
        if (only === undefined) {
            body = <Text style={[styles.body, {color: colors.textMuted}]}>{t("settings.noOrganizations")}</Text>
        } else if (others.length === 0) {
            body = <Field label={t("settings.organization")} value={only.name} />
        } else {
            body = (
                <View accessibilityRole="radiogroup" style={styles.list}>
                    <Text style={[styles.body, {color: colors.textMuted}]}>
                        {state.current === undefined ? t("settings.chooseOrganization") : t("settings.organizationIntro")}
                    </Text>
                    {state.organizations.map((organization) => (
                        <OrganizationRow
                            key={organization.id}
                            organization={organization}
                            checked={state.current === organization.id}
                            switching={choice.choosing === organization.id}
                            busy={choice.choosing !== undefined}
                            onChoose={choice.choose}
                        />
                    ))}
                </View>
            )
        }
    }
    return (
        <Section title={t("settings.organization")}>
            {body}
            {choice.failed ? (
                <Text accessibilityRole="alert" style={[styles.body, {color: colors.danger}]}>
                    {t("settings.chooseFailed")}
                </Text>
            ) : null}
        </Section>
    )
}

const styles = StyleSheet.create({
    body: {fontSize: fontSize[14]},
    list: {gap: space[3]},
    row: {flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: space[3], minHeight: 44},
    name: {fontSize: fontSize[16], flexShrink: 1},
    radio: {width: 22, height: 22, borderRadius: radius.pill, borderWidth: 2, alignItems: "center", justifyContent: "center"},
    dot: {width: 10, height: 10, borderRadius: radius.pill},
})
