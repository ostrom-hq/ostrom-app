import {useLocalSearchParams} from "expo-router"
import {useTranslation} from "react-i18next"
import {StyleSheet, Text} from "react-native"
import {fontSize, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {AskScreen} from "./AskScreen"

/** Same alphabet as the push payload: a deep link `ostrom://ask/<id>` cannot smuggle a path. */
const ASK_ID = /^[A-Za-z0-9_-]+$/

/** `/ask/[id]`, opened from the inbox, a notification or the `ostrom://ask/<id>` deep link. */
export function AskRoute(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const {id} = useLocalSearchParams()
    if (typeof id !== "string" || !ASK_ID.test(id)) {
        return <Text style={[styles.message, {color: colors.textMuted}]}>{t("ask.error")}</Text>
    }
    return <AskScreen id={id} />
}

const styles = StyleSheet.create({
    message: {padding: space[4], fontSize: fontSize[14]},
})
