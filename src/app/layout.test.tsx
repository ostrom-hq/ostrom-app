import {screen} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
import RootLayout, {unstable_settings} from "./_layout"
import RequestById from "./request/[id]"
import Tabs from "./(tabs)/_layout"
import Activity from "./(tabs)/activity"
import History from "./(tabs)/history"
import Index from "./(tabs)/index"
import Settings from "./(tabs)/settings"
import Spending from "./(tabs)/spending"
import Login from "./login"

jest.mock("expo-notifications", () => ({setNotificationHandler: jest.fn()}))
jest.mock("expo-secure-store", () => ({
    WHEN_UNLOCKED_THIS_DEVICE_ONLY: 7,
    getItemAsync: jest.fn(() => Promise.resolve(null)),
    setItemAsync: jest.fn(() => Promise.resolve()),
    deleteItemAsync: jest.fn(() => Promise.resolve()),
}))

describe("RootLayout", () => {
    it("starts signed out on a new phone, on the login screen", async () => {
        await renderRouter(
            {
                _layout: RootLayout,
                "(tabs)/_layout": Tabs,
                "(tabs)/index": Index,
                "(tabs)/history": History,
                "(tabs)/activity": Activity,
                "(tabs)/spending": Spending,
                "(tabs)/settings": Settings,
                login: Login,
                "request/[id]": RequestById,
            },
            {initialUrl: "/"},
        )
        expect(await screen.findByRole("button", {name: "Continue with GitHub"})).toBeOnTheScreen()
    })

    it("puts a deep-linked request on top of the tabs", () => {
        expect(unstable_settings).toEqual({anchor: "(tabs)"})
    })
})
