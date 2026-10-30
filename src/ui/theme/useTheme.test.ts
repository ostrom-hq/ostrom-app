import {renderHook} from "@testing-library/react-native"
import * as ReactNative from "react-native"
import {darkTheme, lightTheme} from "./theme"
import {useTheme} from "./useTheme"

describe("useTheme", () => {
    afterEach(() => {
        jest.restoreAllMocks()
    })

    it("follows a dark system", async () => {
        jest.spyOn(ReactNative, "useColorScheme").mockReturnValue("dark")
        const {result} = await renderHook(() => useTheme())
        expect(result.current).toBe(darkTheme)
    })

    it("follows a light system", async () => {
        jest.spyOn(ReactNative, "useColorScheme").mockReturnValue("light")
        const {result} = await renderHook(() => useTheme())
        expect(result.current).toBe(lightTheme)
    })
})
