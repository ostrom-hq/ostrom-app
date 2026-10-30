import {screen} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
import RootLayout from "./_layout"
import Index from "./index"

describe("RootLayout", () => {
    it("opens on the inbox", async () => {
        await renderRouter({_layout: RootLayout, index: Index}, {initialUrl: "/"})
        expect(await screen.findByText("Waiting for you")).toBeOnTheScreen()
    })
})
