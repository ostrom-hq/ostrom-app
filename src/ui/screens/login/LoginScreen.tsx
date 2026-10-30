// SPDX-License-Identifier: AGPL-3.0-only
import {useTranslation} from "react-i18next"
import {StyleSheet, Text, View} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import {Button} from "@/ui/components/Button"
import {TextField} from "@/ui/components/TextField"
import {fontSize, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {useLoginViewModel} from "./useLoginViewModel"

export function LoginScreen(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const {serverUrl, setServerUrl, busy, error, submit} = useLoginViewModel()
    return (
        <SafeAreaView style={[styles.container, {backgroundColor: colors.bg}]}>
            <View style={styles.content}>
                <View style={styles.brand}>
                    <Text accessibilityRole="header" style={[styles.name, {color: colors.text}]}>
                        {t("app.name")}
                    </Text>
                    <Text style={[styles.tagline, {color: colors.textMuted}]}>{t("app.tagline")}</Text>
                </View>
                <TextField
                    label={t("login.server")}
                    value={serverUrl}
                    onChangeText={setServerUrl}
                    keyboardType="url"
                    error={error === undefined ? undefined : t(`login.errors.${error}`)}
                    testID="login-server"
                />
                <Button label={t("login.github")} onPress={submit} variant="signal" busy={busy} testID="login-github" />
                <Text style={[styles.note, {color: colors.textSubtle}]}>{t("login.note")}</Text>
            </View>
        </SafeAreaView>
    )
}

const styles = StyleSheet.create({
    container: {flex: 1},
    content: {flex: 1, justifyContent: "center", padding: space[5], gap: space[5]},
    brand: {gap: space[2]},
    name: {fontSize: fontSize[40], fontWeight: "800", letterSpacing: -1.2},
    tagline: {fontSize: fontSize[16]},
    note: {fontSize: fontSize[14]},
})
