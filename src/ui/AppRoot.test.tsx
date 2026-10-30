// SPDX-License-Identifier: AGPL-3.0-only
import {screen} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
import {SESSION_KEY} from "@/stores/session"
import {fakeServices, MemoryStorage, testSession} from "@/test/fakeServices"
import {HomeScreen} from "@/ui/screens/home/HomeScreen"
import {LoginScreen} from "@/ui/screens/login/LoginScreen"
import {AppRoot} from "./AppRoot"

async function launch(storage: MemoryStorage): Promise<void> {
    const services = fakeServices({storage})
    await renderRouter(
        {_layout: (): React.JSX.Element => <AppRoot services={services} />, index: HomeScreen, login: LoginScreen},
        {initialUrl: "/"},
    )
}

describe("AppRoot", () => {
    it("opens on the login screen when signed out", async () => {
        await launch(new MemoryStorage())
        expect(await screen.findByRole("button", {name: "Continue with GitHub"})).toBeOnTheScreen()
    })

    it("opens on the inbox when a session is kept", async () => {
        const storage = new MemoryStorage()
        storage.items.set(SESSION_KEY, JSON.stringify(testSession))
        await launch(storage)
        expect(await screen.findByText("Waiting for you")).toBeOnTheScreen()
    })
})
