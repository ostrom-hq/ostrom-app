// SPDX-License-Identifier: AGPL-3.0-only
import {screen} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
// Kept out of src/app/: Expo Router bundles every file of that folder as a route, tests included.
import RootLayout, {unstable_settings} from "@/app/_layout"
import RequestById from "@/app/request/[id]"
import Account from "@/app/account"
import Tabs from "@/app/(tabs)/_layout"
import Activity from "@/app/(tabs)/activity"
import History from "@/app/(tabs)/history"
import Index from "@/app/(tabs)/index"
import Settings from "@/app/(tabs)/settings"
import Spending from "@/app/(tabs)/spending"
import Waits from "@/app/(tabs)/waits"
import Incompatible from "@/app/incompatible"
import Login from "@/app/login"
import {redirectSystemPath} from "@/app/+native-intent"

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
                "(tabs)/waits": Waits,
                "(tabs)/settings": Settings,
                login: Login,
                incompatible: Incompatible,
                "request/[id]": RequestById,
                account: Account,
            },
            {initialUrl: "/"},
        )
        expect(await screen.findByRole("button", {name: "Continue with GitHub"})).toBeOnTheScreen()
    })

    it("hands the system's links to the rule that keeps the sign-in return off the screens", () => {
        expect(redirectSystemPath({path: "ostrom://auth/callback?code=c&state=s", initial: false})).toBeNull()
    })

    it("puts a deep-linked request on top of the tabs", () => {
        expect(unstable_settings).toEqual({anchor: "(tabs)"})
    })
})
