import {useTranslation} from "react-i18next"
import {StyleSheet, Text, View} from "react-native"
import {SafeAreaView} from "react-native-safe-area-context"
import {ScreenTitle} from "@/ui/components/ScreenTitle"
import {fontSize, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"

/** Placeholder home: the "Waiting for you" inbox lands here with the API client. */
export function HomeScreen(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    return (
        <SafeAreaView style={[styles.container, {backgroundColor: colors.bg}]}>
            <View style={styles.content}>
                <ScreenTitle>{t("home.title")}</ScreenTitle>
                <Text style={[styles.body, {color: colors.textMuted}]}>{t("home.empty")}</Text>
            </View>
        </SafeAreaView>
    )
}

const styles = StyleSheet.create({
    container: {flex: 1},
    content: {padding: space[4], gap: space[2]},
    body: {fontSize: fontSize[16]},
})
