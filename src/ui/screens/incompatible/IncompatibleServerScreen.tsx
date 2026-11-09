// SPDX-License-Identifier: AGPL-3.0-only
import {useTranslation} from "react-i18next"
import {StyleSheet, Text, View} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import {Button} from "@/ui/components/Button"
import {fontSize, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {useIncompatibleViewModel} from "./useIncompatibleViewModel"

/** Blocks the app when the server speaks another API version than this app: only signing out is possible. */
export function IncompatibleServerScreen(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const {version, supported, signOut} = useIncompatibleViewModel()
    return (
        <SafeAreaView style={[styles.container, {backgroundColor: colors.bg}]}>
            <View style={styles.content}>
                <Text accessibilityRole="header" style={[styles.title, {color: colors.text}]}>
                    {t("incompatible.title")}
                </Text>
                <Text style={[styles.body, {color: colors.textMuted}]} testID="incompatible-message">
                    {version === undefined ? t("incompatible.tooOld", {supported}) : t("incompatible.unsupported", {version, supported})}
                </Text>
                <Button label={t("incompatible.signOut")} variant="danger" onPress={signOut} testID="incompatible-sign-out" />
            </View>
        </SafeAreaView>
    )
}

const styles = StyleSheet.create({
    container: {flex: 1},
    content: {flex: 1, justifyContent: "center", padding: space[5], gap: space[5]},
    title: {fontSize: fontSize[28], fontWeight: "800"},
    body: {fontSize: fontSize[16]},
})
