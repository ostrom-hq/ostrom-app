import {StyleSheet, Text} from "react-native"
import {fontSize} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"

interface ScreenTitleProps {
    readonly children: string
}

/** The large title at the top of a screen. */
export function ScreenTitle({children}: ScreenTitleProps): React.JSX.Element {
    const {colors} = useTheme()
    return (
        <Text accessibilityRole="header" style={[styles.title, {color: colors.text}]}>
            {children}
        </Text>
    )
}

const styles = StyleSheet.create({
    title: {fontSize: fontSize[28], fontWeight: "800", letterSpacing: -0.5},
})
