import {screen} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
import RootLayout from "./_layout"
import Index from "./index"
import Login from "./login"

jest.mock("expo-secure-store", () => ({
    WHEN_UNLOCKED_THIS_DEVICE_ONLY: 7,
    getItemAsync: jest.fn(() => Promise.resolve(null)),
    setItemAsync: jest.fn(() => Promise.resolve()),
    deleteItemAsync: jest.fn(() => Promise.resolve()),
}))

describe("RootLayout", () => {
    it("starts signed out on a new phone, on the login screen", async () => {
        await renderRouter({_layout: RootLayout, index: Index, login: Login}, {initialUrl: "/"})
        expect(await screen.findByRole("button", {name: "Continue with GitHub"})).toBeOnTheScreen()
    })
})
