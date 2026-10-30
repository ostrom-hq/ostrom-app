import {useColorScheme} from "react-native"
import {type Theme, themeFor} from "./theme"

/** The current Signal theme, following the system light or dark setting. */
export function useTheme(): Theme {
    return themeFor(useColorScheme())
}
