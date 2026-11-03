// SPDX-License-Identifier: AGPL-3.0-only
import {useTranslation} from "react-i18next"
import {ActivityIndicator, ScrollView, StyleSheet, Text} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import {Button} from "@/ui/components/Button"
import {Field, Section} from "@/ui/components/Section"
import {fontSize, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {type AccountViewModel, useAccountViewModel} from "./useAccountViewModel"

/** The console did not open: its address, to select or copy and open by hand. */
function ConsoleLink({model}: {model: AccountViewModel}): React.JSX.Element | null {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const link = model.consoleLink
    if (link === undefined) return null
    return (
        <>
            <Text style={[styles.body, {color: colors.text}]}>{t("request.consoleNotOpened")}</Text>
            <Text selectable style={[styles.body, {color: colors.text}]} testID="account-console-url">
                {link.url}
            </Text>
            <Button label={link.copied ? t("request.copied") : t("request.copyLink")} onPress={model.copyConsoleLink} testID="account-copy-console" />
        </>
    )
}

/**
 * The person's own account, whatever organization the app acts in: who they are, whether their GitHub is linked, and
 * the way to their account in the console, where GitHub is linked and the sessions and workstations are managed.
 */
export function AccountScreen(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const model = useAccountViewModel()
    const {state} = model

    let details: React.JSX.Element
    switch (state.kind) {
        case "loading":
            details = <ActivityIndicator accessibilityLabel={t("account.details")} />
            break
        case "error":
            details = (
                <>
                    <Text style={[styles.body, {color: colors.textMuted}]}>{t("account.error")}</Text>
                    <Button label={t("common.retry")} onPress={model.retry} busy={state.retrying} />
                </>
            )
            break
        case "ready": {
            const {account, githubLinked} = state
            details = (
                <>
                    <Field label={t("account.login")} value={account.login} />
                    <Field label={t("account.name")} value={account.displayName} />
                    {account.emails.map(({email, verified}) => (
                        <Field key={email} label={verified ? t("account.email") : t("account.emailUnverified")} value={email} />
                    ))}
                    <Text style={[styles.body, {color: colors.textMuted}]}>{t("account.github")}</Text>
                    <Text style={[styles.body, {color: colors.text}]} testID={githubLinked ? "account-github-linked" : "account-github-not-linked"}>
                        {githubLinked ? t("account.githubLinked") : t("account.githubNotLinked")}
                    </Text>
                </>
            )
            break
        }
    }

    return (
        <SafeAreaView edges={["bottom"]} style={[styles.container, {backgroundColor: colors.bg}]}>
            <ScrollView contentContainerStyle={styles.content} testID="account-scroll">
                <Text style={[styles.body, {color: colors.textMuted}]}>{t("account.intro")}</Text>
                <Section title={t("account.details")}>{details}</Section>
                <Section title={t("account.console")}>
                    <Text style={[styles.body, {color: colors.text}]}>{t("account.consoleIntro")}</Text>
                    <Button label={t("account.openConsole")} onPress={model.openConsole} testID="account-open-console" />
                    <ConsoleLink model={model} />
                </Section>
            </ScrollView>
        </SafeAreaView>
    )
}

const styles = StyleSheet.create({
    container: {flex: 1},
    content: {padding: space[4], gap: space[4]},
    body: {fontSize: fontSize[14]},
})
