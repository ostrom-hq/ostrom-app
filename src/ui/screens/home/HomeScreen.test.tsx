import {render, screen} from "@testing-library/react-native"
import {HomeScreen} from "./HomeScreen"

describe("HomeScreen", () => {
    it("shows the inbox title and the empty state", async () => {
        await render(<HomeScreen />)
        expect(screen.getByRole("header")).toHaveTextContent("Waiting for you")
        expect(screen.getByText("Nothing waits for your yes right now.")).toBeOnTheScreen()
    })
})
