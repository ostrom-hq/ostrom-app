import {StyleSheet, Text, TextInput, View} from "react-native"
import {fontSize, radius, space} from "@/ui/theme/tokens"
import {useTheme} from "@/ui/theme/useTheme"

interface TextFieldProps {
    readonly label: string
    readonly value: string
    readonly onChangeText: (value: string) => void
    readonly error?: string | undefined
    readonly keyboardType?: "default" | "url"
    readonly testID?: string
}

export function TextField({label, value, onChangeText, error, keyboardType = "default", testID}: TextFieldProps): React.JSX.Element {
    const {colors} = useTheme()
    return (
        <View style={styles.field}>
            <Text style={[styles.label, {color: colors.textMuted}]}>{label}</Text>
            <TextInput
                accessibilityLabel={label}
                autoCapitalize="none"
                autoCorrect={false}
                keyboardType={keyboardType}
                onChangeText={onChangeText}
                style={[
                    styles.input,
                    {color: colors.text, backgroundColor: colors.surface, borderColor: error === undefined ? colors.border : colors.danger},
                ]}
                testID={testID}
                value={value}
            />
            {error === undefined ? null : <Text style={[styles.error, {color: colors.danger}]}>{error}</Text>}
        </View>
    )
}

const styles = StyleSheet.create({
    field: {gap: space[1]},
    label: {fontSize: fontSize[12], fontWeight: "600", letterSpacing: 0.96, textTransform: "uppercase"},
    input: {minHeight: 48, borderRadius: radius.md, borderWidth: 1, paddingHorizontal: space[3], fontSize: fontSize[16]},
    error: {fontSize: fontSize[14]},
})
