// SPDX-License-Identifier: AGPL-3.0-only
import {useLocalSearchParams} from "expo-router"
import {useTranslation} from "react-i18next"
import {StyleSheet, Text} from "react-native"
import {fontSize, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"
import {RequestScreen} from "./RequestScreen"

/** Same alphabet as the push payload: a deep link `ostrom://request/<id>` cannot smuggle a path. */
const REQUEST_ID = /^[A-Za-z0-9_-]+$/

/** `/request/[id]`, opened from the inbox, the activity, a notification or the `ostrom://request/<id>` deep link. */
export function RequestRoute(): React.JSX.Element {
    const {t} = useTranslation()
    const {colors} = useTheme()
    const {id} = useLocalSearchParams()
    if (typeof id !== "string" || !REQUEST_ID.test(id)) {
        return <Text style={[styles.message, {color: colors.textMuted}]}>{t("request.error")}</Text>
    }
    return <RequestScreen id={id} />
}

const styles = StyleSheet.create({
    message: {padding: space[4], fontSize: fontSize[14]},
})
