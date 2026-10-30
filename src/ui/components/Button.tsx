import {ActivityIndicator, Pressable, StyleSheet, Text} from "react-native"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import type {ThemeColors} from "@/ui/theme/theme"
import {useTheme} from "@/ui/theme/useTheme"

/**
 * `signal` is the one primary action of a screen where a person decides (garnet, brand rule);
 * `neutral` for everything else; `danger` for a destructive choice.
 */
export type ButtonVariant = "signal" | "neutral" | "danger"

interface ButtonProps {
    readonly label: string
    readonly onPress: () => void
    readonly variant?: ButtonVariant
    readonly disabled?: boolean
    readonly busy?: boolean
    readonly testID?: string
}

function colorsOf(variant: ButtonVariant, colors: ThemeColors): {background: string; text: string; border: string} {
    switch (variant) {
        case "signal":
            return {background: colors.signal, text: colors.onSignal, border: colors.signal}
        case "danger":
            return {background: colors.surface, text: colors.danger, border: colors.danger}
        case "neutral":
            return {background: colors.surface, text: colors.text, border: colors.borderStrong}
    }
}

export function Button({label, onPress, variant = "neutral", disabled = false, busy = false, testID}: ButtonProps): React.JSX.Element {
    const {colors} = useTheme()
    const palette = colorsOf(variant, colors)
    const inactive = disabled || busy
    return (
        <Pressable
            accessibilityRole="button"
            accessibilityLabel={label}
            accessibilityState={{disabled: inactive, busy}}
            disabled={inactive}
            onPress={onPress}
            testID={testID}
            style={({pressed}) => [
                styles.button,
                {backgroundColor: palette.background, borderColor: palette.border, opacity: inactive || pressed ? 0.6 : 1},
            ]}
        >
            {busy ? <ActivityIndicator color={palette.text} /> : <Text style={[styles.label, {color: palette.text}]}>{label}</Text>}
        </Pressable>
    )
}

const styles = StyleSheet.create({
    button: {
        minHeight: 48,
        borderRadius: radius.md,
        borderWidth: 1,
        paddingHorizontal: space[4],
        alignItems: "center",
        justifyContent: "center",
    },
    label: {fontSize: fontSize[16], fontWeight: "700"},
})
